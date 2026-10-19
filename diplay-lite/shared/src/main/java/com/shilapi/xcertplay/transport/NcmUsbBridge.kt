package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbRequest
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.Closeable
import java.io.File
import java.io.FileWriter
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * vc103（T3）：NCM claim 专用临时文件追踪——T3 logcat 环形区被系统 fsync 洪水冲掉，
 * Log.i 全丢；独立文件落 /sdcard/DiPlayLite/ncm-claim-debug.txt（与主日志同目录），
 * 拿到完整 claim 现场（errno/分支/异常栈）。写入失败静默（无存储权限时不拖累会话）。
 * 排障结束整文件可删；剥离探针=删本对象+openOnce 内 trace() 调用。
 */
internal object NcmClaimTrace {
    private val lock = Any()
    private var warned = false

    fun trace(message: String) {
        synchronized(lock) {
            try {
                val dir = File(Environment.getExternalStorageDirectory(), "DiPlayLite")
                if (!dir.exists() && !dir.mkdirs()) return
                FileWriter(File(dir, "ncm-claim-debug.txt"), true).use { writer ->
                    val ts = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                        .format(java.util.Date())
                    writer.write("$ts $message\n")
                }
            } catch (_: Throwable) {
                warned = true // 首次失败后不再抛，静默降级（Log.i 仍在）
            }
        }
    }
}

/**
 * A blocking NCM data pipe that moves Ethernet frames as NTB16 blocks over bulk endpoints.
 *
 * The caller opens the USB connection while the CarPlay configuration is already active; this
 * bridge claims only the NCM control/data interfaces and owns the connection thereafter. All
 * calls may block and must run away from the Android main thread.
 */
class NcmUsbBridge internal constructor(
    private val connection: UsbDeviceConnection,
    private val outEndpoint: UsbEndpoint,
    private val inEndpoint: UsbEndpoint,
    private val statusEndpoint: UsbEndpoint?,
    private val claimedInterfaces: List<UsbInterface>,
    descriptorHostMac: ByteArray?,
    // vc103r（Legacy 对齐）：NCM 复用 usbmux 会话的同一 fd 时为 false——bridge 关闭只
    // release 接口，不 close 连接（连接归 usbmux 会话所有）；独立 fd 时保持 true 原行为。
    private val ownsConnection: Boolean = true,
) : Closeable {
    private val descriptorMac = descriptorHostMac?.copyOf()
    val hostMac: ByteArray? get() = descriptorMac?.copyOf()
    private val stateLock = Any()
    private val readLock = Any()
    private val writeLock = Any()
    private var closed = false
    private var failure: IphoneUsbException? = null
    private var sequence = 0
    private var loggedWriteTimeout = false
    private val frames = ArrayDeque<ByteArray>()
    private var queuedBytes = 0
    private var buffered = ByteArray(0)
    private var bufferedSize = 0
    // vc72：NTB16 失步重同步累计计数；vc73 改窗口化——距上次错位 >30s 重置为 0，
    // 窗口内超 RESYNC_STORM_LIMIT 才判真死。
    private var resyncCount = 0
    private var resyncStreakStartNanos = 0L

    // vc140 观测探针（只记录不干预）：最近消费的 NTB 块元数据 / 最近 USB 读块大小，
    // 随 resync 日志带出，用于反推恒定 3 字节错位的真实来源。
    private val recentBlocks = ArrayDeque<String>()
    private val recentReads = ArrayDeque<Int>()
    private val readBuffer = ByteArray(READ_CHUNK_BYTES)
    // 1.0.25（3e 1.0.24 双会话实测 21:02:48/21:03:19）：EMUI 的 UsbRequest.queue() 会瞬时
    // 返回 false（21:02:48.685+48.999、21:03:19.875×2），原实现直接 failSession → NCM 读线程
    // 死亡 → 音视频同刻冻结（rx=3.4fps 即窗口残值、touch2frame max=25304ms、用户感知卡死）。
    // queue() 失败按可恢复瞬态处理：本轮降级同步读；连续 ASYNC_QUEUE_FAILURE_LIMIT 次失败
    // 才永久退化（API 26+ 首选异步的原因见 asyncRead 注释——同步 bulkTransfer 全程钉住
    // JNI critical 区会拖慢 GC，表现为 ~0.8s 音视频顿卡，故不作默认）。
    @Volatile
    private var permanentSyncRead = false
    private var asyncQueueFailures = 0
    // Bulk IN uses one persistent async request on API 26+: bulkTransfer() pins its byte[] in a
    // JNI critical section for the whole wait, which blocks ART's GC thread flip and, with it,
    // every other USB transfer (seen as ~0.8 s stalls of video and audio). A timed-out request
    // stays queued, so no data is lost between calls. On API < 26 (dalvik/老 ART 无
    // requestWait(timeout)/queue(buffer)) 退化为同步 bulkTransfer 读——控制面/视频速率下可接受。
    // vc107（ONEPLUS 5 185358 实测定案）：requestWait() 是连接级的——返回本 fd 上
    // 任何一个完成的 UsbRequest。vc103r 共享 fd 后 usbmux 与 NCM 两个异步读线程在
    // 同一请求队列里互相偷请求：识别阶段 NCM 无流量相安无事，TLS 一启动 NCM 入向
    // 一响即撞「completed !== request」→ failSession → CSM closed。共享 fd（
    // ownsConnection=false）时禁用 NCM 异步读、退回同步 bulkTransfer（Legacy 共享
    // fd 正是同步读）；独立 fd 保持 API26+ 异步（防 JNI critical 顿卡的原设计）。
    private val asyncRead = ownsConnection && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    private val directReadBuffer = ByteBuffer.allocateDirect(READ_CHUNK_BYTES)
    private var readRequest: UsbRequest? = null
    private var readQueued = false
    private val statusRunning = AtomicBoolean(statusEndpoint != null)
    private val statusThread = statusEndpoint?.let { endpoint ->
        Thread({ drainStatus(endpoint) }, "ncm-status-in").apply {
            isDaemon = true
            start()
        }
    }

    /** Wraps one Ethernet frame in one NTB16 block and writes it to bulk OUT. */
    fun send(frame: ByteArray, timeoutMillis: Int) = synchronized(writeLock) {
        checkOpen()
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        // fe6t1：出站 tap（车机→iPhone 完整以太网帧，发送意图即记录，写失败不影响判定）。
        PcapTap.writeFrame(frame, PcapTap.DIR_OUTBOUND)
        val sequence = synchronized(stateLock) {
            checkOpenLocked()
            this.sequence.also { this.sequence = (this.sequence + 1) and 0xffff }
        }
        val block = Ntb16Codec.build(frame, sequence)
        val transferred = connection.bulkTransfer(outEndpoint, block, block.size, timeoutMillis)
        // Before StartCarPlaySession the phone keeps the NCM data path NAKed. Android reports the
        // resulting timeout as -1; it is not a detach and later packets must be allowed to retry.
        if (transferred <= 0) {
            if (!loggedWriteTimeout) {
                loggedWriteTimeout = true
                Log.i(IphoneCarPlayConfiguration.TAG, "ncm bulk-out not ready; retaining bridge for retry")
            }
            return@synchronized
        }
        if (transferred != block.size) {
            throw IphoneUsbException.DeviceUnavailable(
                "NCM write transferred $transferred of ${block.size} bytes",
            )
        }
        if (loggedWriteTimeout) {
            loggedWriteTimeout = false
            Log.i(IphoneCarPlayConfiguration.TAG, "ncm bulk-out became ready")
        }
    }

    /**
     * Returns the next complete Ethernet frame, or null when [timeoutMillis] elapses without one.
     * USB reads may split or coalesce NTB blocks; this method reassembles whole blocks internally.
     */
    fun recv(timeoutMillis: Long): ByteArray? {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        synchronized(readLock) {
            checkOpen()
            if (frames.isNotEmpty()) return pollFrame()

            val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
            while (true) {
                drainFrames()
                if (frames.isNotEmpty()) return pollFrame()
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0) return null
                val chunkLength =
                    readChunk((remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
                        ?: continue
                appendBuffered(readBuffer, chunkLength)
            }
        }
    }

    override fun close() {
        statusRunning.set(false)
        val requestToClose = synchronized(stateLock) {
            if (closed) return
            closed = true
            readRequest
        }
        // Wakes a reader blocked in requestWait(); it then observes the closed state.
        runCatching { requestToClose?.cancel() }
        statusThread?.let { thread ->
            thread.interrupt()
            try {
                thread.join(STATUS_POLL_TIMEOUT_MILLIS + 250L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        for (usbInterface in claimedInterfaces.asReversed()) {
            try {
                connection.releaseInterface(usbInterface)
            } catch (_: RuntimeException) {
                // Best-effort release; the connection close below is authoritative.
            }
        }
        if (ownsConnection) connection.close()
        // API16 适配：UsbRequest.close() 是 API 26+；异步请求只会在 >= 26 路径上创建。
        if (asyncRead) runCatching { requestToClose?.close() }
    }

    /**
     * 1.0.26（3e 1.0.25 实测 21:17:50/21:18:07）：对数据面 IN 端点发 CLEAR_FEATURE
     * (ENDPOINT_HALT)。EMUI USB 栈偶发把 IN 端点楔死（queue() 失败、同步读也永远
     * 拿不到数据），clear halt 有机会恢复数据流；对未 halt 的健康端点是 USB spec
     * 定义内的无害 no-op。与 close 用 stateLock 互斥。
     */
    fun clearHaltInEndpoints() {
        synchronized(stateLock) {
            if (closed) return
            runCatching {
                connection.controlTransfer(
                    /* requestType = */ 0x02, // standard, endpoint recipient, host-to-device
                    /* request = */ 0x01, // CLEAR_FEATURE
                    /* value = */ 0x0000, // ENDPOINT_HALT
                    /* index = */ inEndpoint.address,
                    /* buffer = */ null,
                    /* length = */ 0,
                    /* timeout = */ 100,
                )
            }
        }
    }

    private fun drainStatus(endpoint: UsbEndpoint) {
        val buffer = ByteArray(endpoint.maxPacketSize.coerceAtLeast(64))
        var loggedFirst = false
        while (statusRunning.get()) {
            // Short synchronous polls keep this thread out of JNI critical sections most of the time;
            // notifications are rare, small interrupt packets that are only logged.
            val transferred = try {
                connection.bulkTransfer(endpoint, buffer, buffer.size, STATUS_POLL_TIMEOUT_MILLIS)
            } catch (_: RuntimeException) {
                return
            }
            if (transferred <= 0) {
                try {
                    Thread.sleep(STATUS_POLL_INTERVAL_MILLIS)
                } catch (_: InterruptedException) {
                    return
                }
                continue
            }
            if (!loggedFirst) {
                loggedFirst = true
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "ncm status notification bytes=$transferred data=${buffer.copyOf(transferred).hex(32)}",
                )
            }
        }
    }

    private fun ByteArray.hex(limit: Int): String =
        take(limit).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun drainFrames() {
        while (true) {
            if (bufferedSize < 12) return
            if (readU32(buffered, 0) != Ntb16Codec.NTH16_SIG) {
                // vc72（TS7 实测 19:57:28.081）：视频满流（~50pkt/s 全 1500B）时 MUSB RX 丢
                // USB 包（FIFO overrun 嫌疑）→ 字节流错位 → 签名校验失败。原实现 failSession
                // 自杀 → 桥拆 → control CHANNEL_CLOSED → 会话死（activeStreams=[110] 且画面
                // 已出）。改为向前扫描重同步：丢错位前缀对齐到下一 NTH16 签名；无签名则保留
                // 尾 3 字节等拼接。丢帧花屏由 keyframe recovery 兜底。
                // vc73（TS7 实测 20:26:14）：静态画面期错位加速至 ~1-2 次/秒，vc72 的累计
                // 上限 64 在 2.5 分钟内耗尽自拆。改为 30s 窗口化计数：距上次错位 >30s 重置，
                // 窗口内 >256 次才判真死（错端点/错接口的垃圾流秒级触顶，正常丢包永不触顶）。
                val nowNanos = System.nanoTime()
                if (resyncStreakStartNanos == 0L ||
                    nowNanos - resyncStreakStartNanos > RESYNC_STREAK_RESET_NANOS
                ) {
                    resyncCount = 0
                    resyncStreakStartNanos = nowNanos
                }
                if (++resyncCount > RESYNC_STORM_LIMIT) {
                    throw failSession("NTB16 resync storm ($resyncCount)")
                }
                val resynced = resyncToNth16Signature()
                if (!resynced) return
                continue
            }
            val blockLength = readU16(buffered, 8)
            if (blockLength < 28) throw failSession("Invalid NTB16 block length $blockLength")
            // 1.0.22（3e 实测 20:42 首画即卡死）：wBlockLength 为 512 整数倍时 iPhone 的
            // 定界方式既可能是 1 字节 0x00 短包、也可能是 ZLP（bulkTransfer 层面 0 长度，
            // 字节流中不可见）。原实现假设必有 0x00 pad：ZLP 定界时下一字节实为下一个
            // NTB 的 NTH16 签名首字节 → 校验失败 failSession → NCM 读线程终止 → 视频
            // 冻结（rx=6fps shown=1fps，"Invalid NTB16 short-packet pad"×2）。改为 pad
            // 可选：块边界处等下一字节判别，0x00 才消费 pad，否则按 blockLength 原样解析。
            // vc138（TS7 实测 13:07-13:10）：iPhone 的块定界短包实测为 4 字节全零，vc 原假设
            // 固定 1 字节 pad 时恒剩 3 字节垃圾 → 静态画面期每 1~2 秒假失步一次 resync
            // （dropped 3 head=000000），错位叠加向抖动时触发 audio gap compensated，
            // 形成可感知的音乐卡顿。
            // vc139（TS7 实测 13:45-13:46）：vc138 的 pad 消费被 blockAligned 限定只覆盖
            // 512 倍数块，而实际失步块的 blockLength 非 512 倍数（pad 疑为 4 字节对齐填充）
            // ——resync 照旧频发。改为 pad 扫描通用化：不限块对齐，块边界处一律跳过连续
            // 0x00 至下一非零字节（下一 NTH16 签名首字节 0x4E 恒非零、pad 恒全零，判别
            // 安全；上限一个 USB 包）。无 pad（下一字节即签名）时跳过 0 字节，行为不变；
            // 1/4 字节、块尾对齐填充、ZLP 定界统一覆盖，vc72/73 失步扫描与风暴防护不动。
            if (bufferedSize <= blockLength) return
            var wireLength = blockLength
            if (buffered[blockLength].toInt() == 0) {
                var padEnd = blockLength
                val padLimit = minOf(bufferedSize, blockLength + USB_PACKET_SIZE)
                while (padEnd < padLimit && buffered[padEnd].toInt() == 0) padEnd++
                if (padEnd >= bufferedSize) return
                wireLength = padEnd
            }
            for (frame in Ntb16Codec.parse(buffered, 0, blockLength)) enqueueFrame(frame)
            val remaining = bufferedSize - wireLength
            // vc140 观测探针：记录最近消费块的元数据（块长/消费长/pad 数），resync 时带出，
            // 用于反推恒定 3 字节错位的来源（块间 pad？wBlockLength 与实际不符？读拼接丢字节？）。
            // vc141：追加 NTH16 wSequence（offset 6）——resync 时新旧块序号对比，序号连续
            // 无跳 = 字节流只多不缺（重复实锤），跳号 = 存在丢块，直接区分两类控制器 bug。
            if (recentBlocks.size >= 8) recentBlocks.removeFirst()
            recentBlocks.addLast(
                "seq=${readU16(buffered, 6)} len=$blockLength wire=$wireLength " +
                    "pad=${wireLength - blockLength} t=${System.nanoTime() / 1000_000 % 100_000}",
            )
            buffered.copyInto(buffered, 0, wireLength, bufferedSize)
            bufferedSize = remaining
        }
    }

    /**
     * vc72：NTB16 失步后向前扫描下一个 NTH16 签名并丢弃错位前缀。找到 → 丢弃并返回 true；
     * 未找到 → 保留末尾 3 字节（可能是被读边界截断的签名前缀）并返回 false，等下一读块拼接。
     * vc140：日志扩展观测现场——丢弃字节全文 hex、随后新块头 16B（可反解 wBlockLength）、
     * 最近 8 个成功消费块元数据、最近 16 次 USB 读块大小。解析行为零改动。
     */
    private fun resyncToNth16Signature(): Boolean {
        for (i in 1..bufferedSize - NTH16_SIG_BYTES) {
            if (readU32(buffered, i) == Ntb16Codec.NTH16_SIG) {
                Log.w(
                    IphoneCarPlayConfiguration.TAG,
                    "ncm ntb16 resync #$resyncCount: dropped $i misaligned bytes " +
                        "(head=${buffered.hex(minOf(i, 32))}) " +
                        "newSeq=${readU16(buffered, i + 6)} newLen=${readU16(buffered, i + 8)} " +
                        "next16=${buffered.copyOfRange(i, minOf(i + 16, bufferedSize)).hex(16)} " +
                        "recent=[${recentBlocks.joinToString("; ")}] " +
                        "reads=$recentReads",
                )
                buffered.copyInto(buffered, 0, i, bufferedSize)
                bufferedSize -= i
                return true
            }
        }
        val keep = minOf(NTH16_SIG_BYTES - 1, bufferedSize)
        buffered.copyInto(buffered, 0, bufferedSize - keep, bufferedSize)
        bufferedSize = keep
        return false
    }

    private fun appendBuffered(source: ByteArray, length: Int) {
        if (recentReads.size >= 16) recentReads.removeFirst()
        recentReads.addLast(length)
        val required = bufferedSize + length
        if (required > buffered.size) {
            val capacity = maxOf(required, maxOf(READ_CHUNK_BYTES, buffered.size * 2))
            val grown = ByteArray(capacity)
            buffered.copyInto(grown, 0, 0, bufferedSize)
            buffered = grown
        }
        source.copyInto(buffered, bufferedSize, 0, length)
        bufferedSize += length
    }

    private fun enqueueFrame(frame: ByteArray) {
        if (frames.size >= MAX_QUEUED_FRAMES || queuedBytes + frame.size > MAX_QUEUED_BYTES) {
            throw failSession("NCM frame queue exceeded its bounds")
        }
        frames.addLast(frame)
        queuedBytes += frame.size
    }

    private fun pollFrame(): ByteArray {
        val frame = frames.removeFirst()
        queuedBytes -= frame.size
        // fe6t1：入站 tap（iPhone→车机完整以太网帧，未启用时零开销直返）。
        PcapTap.writeFrame(frame, PcapTap.DIR_INBOUND)
        return frame
    }

    private fun readChunk(timeoutMillis: Long): Int? {
        checkOpen()
        return if (asyncRead && !permanentSyncRead) readChunkAsync(timeoutMillis) else readChunkSync(timeoutMillis)
    }

    /** API < 26：同步 bulkTransfer 读（超时/失败返回 null，交由上层重试）。 */
    private fun readChunkSync(timeoutMillis: Long): Int? {
        val transferred = try {
            connection.bulkTransfer(inEndpoint, readBuffer, readBuffer.size, timeoutMillis.coerceAtLeast(1).toInt())
        } catch (error: RuntimeException) {
            throw failSession("NCM read failed", error)
        }
        if (transferred <= 0) return null
        // vc144（TS7 vc143-snap-2008 定案）：丢块机理=MUSB 在「上一传输以 36B 短包结束 →
        // 重新 arm 下一次传输」交界处吃掉下一 NTB 的第一个 512B 包——实证：每次块丢失
        // resync 前必有一次 1060 读（=1572−512，块的头部包被丢后余量无 NTH16 签名，只能
        // 走 resync 扫描），audio gap 与其一一对应。此处成功读后 settle 1ms 再返回（即
        // 推迟下一次 arm），给控制器短包后的 FIFO 清理留余量。音频块 ~7.5ms 一个，
        // 1ms 占空比影响可忽略；判据=实测 resync 频率对比（vc143 听歌 10 秒 5 次）。
        try {
            Thread.sleep(READ_SETTLE_MILLIS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return transferred
    }

    private fun readChunkAsync(timeoutMillis: Long): Int? {
        var queueFailed = false
        val request = try {
            // Publish and queue atomically with close(), so detach cannot miss a new request.
            synchronized(stateLock) {
                checkOpenLocked()
                val current = readRequest ?: UsbRequest().also {
                    if (!it.initialize(connection, inEndpoint)) {
                        it.close()
                        throw failSession("Android could not initialize the NCM read request")
                    }
                    readRequest = it
                }
                if (!readQueued) {
                    directReadBuffer.clear()
                    // 1.0.25：queue() 返回 false（EMUI 瞬态）或抛 RuntimeException 都不再
                    // failSession——标记本轮降级同步读，锁外执行（同步 bulkTransfer 可能
                    // 阻塞整个超时窗口，绝不能握着 stateLock 阻塞 send()）。
                    val queued = try {
                        current.queue(directReadBuffer)
                    } catch (_: RuntimeException) {
                        false
                    }
                    if (!queued) {
                        asyncQueueFailures += 1
                        // 1.0.26：降级首次发生时落日志——3e 1.0.25 实测中降级是静默的，
                        // 无法与入向楔死（USBMUX fallback 同刻）做日志对齐取证。
                        if (asyncQueueFailures == 1) {
                            Log.i(
                                IphoneCarPlayConfiguration.TAG,
                                "ncm async queue unavailable; sync fallback engaged this round",
                            )
                        }
                        if (asyncQueueFailures >= ASYNC_QUEUE_FAILURE_LIMIT && !permanentSyncRead) {
                            permanentSyncRead = true
                            Log.w(
                                IphoneCarPlayConfiguration.TAG,
                                "ncm async queue keeps failing (x$asyncQueueFailures); degrading to sync reads",
                            )
                        }
                        queueFailed = true
                    } else {
                        asyncQueueFailures = 0
                        readQueued = true
                    }
                }
                if (queueFailed) null else current
            }
        } catch (error: RuntimeException) {
            throw failSession("NCM read failed", error)
        }
        if (request == null) return readChunkSync(timeoutMillis)
        try {
            val completed = try {
                connection.requestWait(timeoutMillis.coerceAtLeast(1))
            } catch (_: TimeoutException) {
                // Nothing arrived yet; the request stays queued for the next call. USBMUX owns
                // authoritative detach/failure detection for the same phone.
                return null
            } ?: throw failSession("Android returned no NCM read request")
            if (completed !== request) throw failSession("Android completed an unexpected NCM request")
            readQueued = false
            val transferred = directReadBuffer.position()
            if (transferred <= 0) return null
            directReadBuffer.flip()
            directReadBuffer.get(readBuffer, 0, transferred)
            return transferred
        } catch (error: IphoneUsbException) {
            throw error
        } catch (error: RuntimeException) {
            throw failSession("NCM read failed", error)
        }
    }

    private fun failSession(message: String, cause: Throwable? = null): IphoneUsbException.DeviceUnavailable {
        val error = IphoneUsbException.DeviceUnavailable(message, cause)
        synchronized(stateLock) {
            if (failure == null) failure = error
        }
        return error
    }

    private fun checkOpen() {
        synchronized(stateLock) { checkOpenLocked() }
    }

    private fun checkOpenLocked() {
        failure?.let { throw it }
        if (closed) throw IphoneUsbException.DeviceUnavailable("NCM bridge is closed")
    }

    private fun readU16(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private fun readU32(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or
            ((source[offset + 1].toInt() and 0xff) shl 8) or
            ((source[offset + 2].toInt() and 0xff) shl 16) or
            ((source[offset + 3].toInt() and 0xff) shl 24)

    companion object {
        // 1.0.6：usbfs 单次 bulk 请求上限 16384 字节，32KB 缓冲导致 queue/bulkTransfer
        // 直接失败（3e 实测 "NCM read failed"）。上限即取 16384。
        private const val READ_CHUNK_BYTES = 16_384
        // vc144：同步读成功后重新 arm 前的 settle 延时（机理与实测判据见 readChunkSync 注释）。
        private const val READ_SETTLE_MILLIS = 1L
        private const val USB_PACKET_SIZE = 512
        private const val STATUS_POLL_TIMEOUT_MILLIS = 20
        private const val STATUS_POLL_INTERVAL_MILLIS = 500L
        private const val MAX_QUEUED_FRAMES = 256
        private const val MAX_QUEUED_BYTES = 1 shl 20
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        // vc103r（Legacy 对齐）：SET_INTERFACE 重试参数——vendor cdc_ncm 在 claim 与
        // SET_INTERFACE 之间抢绑的竞速窗口（Legacy retryAlternateSelection 同款）。
        private const val ALT_SETTING_ATTEMPTS = 5
        private const val ALT_SETTING_RETRY_MILLIS = 100L
        private const val ASYNC_QUEUE_FAILURE_LIMIT = 3

        /** vc72/73：NTB16 重同步——签名 4 字节；30s 无错位重置计数；窗口内 256 次判真死。 */
        private const val NTH16_SIG_BYTES = 4
        private const val RESYNC_STORM_LIMIT = 256
        private const val RESYNC_STREAK_RESET_NANOS = 30_000_000_000L

        /** 1.0.3：getAlternateSetting 是 API 24+，< 24 固定打 0（仅诊断日志用途）。 */
        private fun altOf(usbInterface: UsbInterface): Int =
            if (Build.VERSION.SDK_INT >= 24) usbInterface.alternateSetting else 0

        /**
         * vc101（T3）：usbfs 原子断驱动 claim 兜底（借鉴 legacy-ref forceClaim）。
         * 仅 API<=P；返回内核 errno：0=claim 成功，>0=内核错误码（2=ENOENT 接口号不存在），
         * -1=native 不可用（API>P / fd 异常 / so 缺失）。
         * 注意：native 已 claim 时 Java 层 claim 状态与 framework 无关，releaseInterface
         * 走 close() 的 best-effort 路径，无碍。
         */
        /** vc103l：KK 配置舞 2.0 用——遍历全部配置描述符，返回「拓扑匹配」的配置值：
         *  该配置里 controlId 是 CDC-Control（class 2/sub 13）且 dataId 是 CDC-Data
         *  （class 10）。vc103k 实证必须拓扑匹配而非任取含 CDC-Data 的配置：T3
         *  framework 缓存 control=2/data=3 只对应配置 5（配置 6 的 CDC-Data 在
         *  iface 4，切过去照样 claim 不到 iface 3）；含 NCM 对的配置可能有多个，
         *  取第一个匹配（配置 5 在 6 之前）。无匹配返回 -1。 */
        private fun parseNcmConfigValue(raw: ByteArray, controlId: Int, dataId: Int): Int {
            var off = 18
            var pendingConfigValue = -1
            var controlClass = -1
            var controlSub = -1
            var dataClass = -1
            while (off + 2 <= raw.size) {
                val len = raw[off].toInt() and 0xff
                if (len < 2 || off + len > raw.size) break
                when (raw[off + 1].toInt() and 0xff) {
                    0x02 -> if (len >= 9) {
                        // 新配置描述符：先裁决上一个配置是否拓扑匹配（首个匹配即返回）
                        if (pendingConfigValue >= 0 &&
                            controlClass == 2 && controlSub == 13 && dataClass == 10
                        ) {
                            return pendingConfigValue
                        }
                        pendingConfigValue = raw[off + 5].toInt() and 0xff
                        controlClass = -1
                        controlSub = -1
                        dataClass = -1
                    }
                    0x04 -> if (len >= 9) {
                        val num = raw[off + 2].toInt() and 0xff
                        if (num == controlId) {
                            controlClass = raw[off + 5].toInt() and 0xff
                            controlSub = raw[off + 6].toInt() and 0xff
                        }
                        if (num == dataId) {
                            dataClass = raw[off + 5].toInt() and 0xff
                        }
                    }
                }
                off += len
            }
            if (pendingConfigValue >= 0 &&
                controlClass == 2 && controlSub == 13 && dataClass == 10
            ) {
                return pendingConfigValue
            }
            return -1
        }

        /** vc103j：ByteArray hex（trace 用，紧凑小写）。 */
        private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

        /** vc103d：KK 配置舞用——从 rawDescriptors 解析第一个配置描述符的 bConfigurationValue。
         *  blob = 设备描述符(18B) + 配置描述符(s)，/dev/bus/usb 节点只含活动配置。 */
        private fun parseFirstConfigValue(raw: ByteArray): Int {
            if (raw.size < 18 + 9) return -1
            var off = 18
            while (off + 9 <= raw.size) {
                val len = raw[off].toInt() and 0xff
                if (len < 9) break
                val type = raw[off + 1].toInt() and 0xff
                if (type == 0x02) {
                    return raw[off + 5].toInt() and 0xff
                }
                off += len
            }
            return -1
        }

        private fun nativeForceClaim(connection: UsbDeviceConnection, interfaceId: Int): Int {
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) return -1
            val fd = runCatching { connection.fileDescriptor }.getOrDefault(-1)
            if (fd < 0 || !LegacyUsbHostNative.available) return -1
            val errno = try {
                LegacyUsbHostNative.forceClaim(fd, interfaceId)
            } catch (_: LinkageError) {
                return -1
            }
            Log.i(
                IphoneCarPlayConfiguration.TAG,
                "usbfs force-claim iface=$interfaceId errno=$errno",
            )
            return errno
        }

        /**
         * vc101（T3）：usbfs USBDEVFS_SETINTERFACE ioctl（借鉴 legacy-ref select）。
         * 内核 usb_set_interface 同步更新 ep 启用表并向设备发 SET_INTERFACE，比裸
         * controlTransfer 更完整；仅 API<=P。
         */
        private fun nativeSetInterface(
            connection: UsbDeviceConnection,
            interfaceId: Int,
            alternateSetting: Int,
        ): Boolean {
            return nativeSetInterfaceErrno(connection, interfaceId, alternateSetting) == 0
        }

        /** vc103：SET_INTERFACE ioctl 的 errno 版（0=成功，>0=内核错误码，-1=native 不可用）。 */
        private fun nativeSetInterfaceErrno(
            connection: UsbDeviceConnection,
            interfaceId: Int,
            alternateSetting: Int,
        ): Int {
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) return -1
            val fd = runCatching { connection.fileDescriptor }.getOrDefault(-1)
            if (fd < 0 || !LegacyUsbHostNative.available) return -1
            val errno = try {
                LegacyUsbHostNative.setInterface(fd, interfaceId, alternateSetting)
            } catch (_: LinkageError) {
                return -1
            }
            Log.i(
                IphoneCarPlayConfiguration.TAG,
                "usbfs set-interface iface=$interfaceId alt=$alternateSetting errno=$errno",
            )
            return errno
        }

        /** vc103r（Legacy 对齐）：GET_CONFIGURATION 回读内核活动配置值，null=回读失败。 */
        internal fun kernelActiveConfiguration(connection: UsbDeviceConnection): Int? {
            val value = ByteArray(1)
            val read = runCatching {
                connection.controlTransfer(
                    UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_STANDARD,
                    USB_REQUEST_GET_CONFIGURATION,
                    0,
                    0,
                    value,
                    value.size,
                    USB_CONTROL_TIMEOUT_MILLIS,
                )
            }.getOrDefault(-1)
            return if (read == 1) value[0].toInt() and 0xff else null
        }

        /** Claims and activates the NCM control/data interfaces; owns the connection on success. */
        fun open(
            connection: UsbDeviceConnection,
            function: NcmFunctionDiscovery.NcmFunction,
            reopen: (() -> UsbDeviceConnection?)? = null,
            ownsConnection: Boolean = true,
        ): NcmUsbBridge {
            // 1.1.3（root 抢回）：claim 失败且车机有 root 时，unbind 内核 cdc_ncm 后重试一次。
            try {
                return openOnce(connection, function, ownsConnection)
            } catch (error: IphoneUsbException.DeviceUnavailable) {
                val message = error.message ?: ""
                if (!message.contains("could not claim") && !message.contains("could not select")) throw error
                if (!RootNcmReclaimer.reclaim(message)) throw error
                try {
                    Thread.sleep(400)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                // vc94：openOnce 失败路径已 connection.close()（authoritative close），复用已关闭
                // 连接重试 claim 必败——1.1.3 起 root 抢回实际整体失效。有 reopen 工厂（调用方
                // 提供 usbManager.openDevice(device)）时重新打开；工厂返回 null 或缺省时显性失败。
                val retryConnection = if (reopen != null) {
                    reopen()
                        ?: throw IphoneUsbException.DeviceUnavailable(
                            "Could not reopen the iPhone NCM connection after root reclaim",
                        )
                } else {
                    connection
                }
                return openOnce(retryConnection, function, ownsConnection)
            }
        }

        private fun openOnce(
            connection: UsbDeviceConnection,
            function: NcmFunctionDiscovery.NcmFunction,
            ownsConnection: Boolean,
        ): NcmUsbBridge {
            val claimed = ArrayList<UsbInterface>(2)
            try {
                // vc103n（T3 终审后 legacy 序列移植）：任何 claim 之前先做配置对齐——
                // T3 dmesg 实锤（kmsg-replug.txt）legacy 的成功配方是：0x52 重枚举后的
                // 全新 device 节点上（零 usbfs claim）SET config 6 → 内核自动摘 hid/snd
                // 驱动（仅 WARNING 不挡路）→ 内核重解析出接口 3 → cdc_ncm 抢注 →
                // DISCONNECT 摘掉 → 自己 claim。我们此前全部失败（claim(3) ENOENT/
                // set(5) EBUSY）的根因 = 内核活动配置停在 4（PTP 形态，无接口 3），
                // 而 framework 缓存的是 CarPlay 配置的接口布局。顺序对齐后：
                // 拓扑匹配目标配置 → native SETCONFIGURATION（全新节点无 claim 可挡，
                // EBUSY 时 native 内部已带 GETDRIVER+DISCONNECT 踢内核驱动兜底）→
                // 内核表已含接口 3 → 后续常规 claim/setInterface 全部成立。
                // 仅 KK（<=P 且 <L 走 legacy 发现，本路径天然 KK 域）且 native 可用时执行；
                // 切配置失败（含 EBUSY）不硬失败——保留原路径继续（老机型上 framework
                // 若已对齐，舞是多余的，失败也无害）。
                // vc103o 护栏：findKernelAligned 已按内核真实活动配置对齐发现时，切配置
                // 多余且有害（会把内核从对齐好的配置切走，而本 function 是按当前配置拼的）。
                // 仅在发现层未对齐（走 findLegacy 原路）时才尝试 legacy 序列切配置。
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT &&
                    LegacyUsbHostNative.available &&
                    !function.kernelAligned
                ) {
                    val preFd = runCatching { connection.fileDescriptor }.getOrDefault(-1)
                    val preConfig = parseNcmConfigValue(connection.rawDescriptors, function.control.id, function.data.id)
                    NcmClaimTrace.trace(
                        "vc103n pre-claim config align: fd=$preFd topology-matched" +
                            " ncmConfigValue=$preConfig (kernel active config may differ)",
                    )
                    if (preFd >= 0 && preConfig > 0) {
                        val alignErrno = try {
                            LegacyUsbHostNative.setConfiguration(preFd, preConfig)
                        } catch (_: LinkageError) {
                            -1
                        }
                        // vc103r（Legacy 对齐）：setConfiguration 后 GET_CONFIGURATION 回读——
                        // errno=0 不代表内核真切过去了（KK proc_setconfig 有静默失败先例），
                        // 回读值才是后续 claim 能否成立的唯一依据（Legacy activeConfiguration 同款）。
                        val activeAfter = kernelActiveConfiguration(connection)
                        NcmClaimTrace.trace(
                            "vc103n pre-claim setConfiguration($preConfig) errno=$alignErrno" +
                                " readback=$activeAfter" +
                                " (readback==preConfig=kernel re-parsed CarPlay config;" +
                                " EBUSY=claimed, keep going)",
                        )
                        Log.i(
                            IphoneCarPlayConfiguration.TAG,
                            "vc103n pre-claim config align set($preConfig) errno=$alignErrno" +
                                " readback=$activeAfter",
                        )
                    }
                }
                val descriptorHostMac = readNcmHostMac(connection, function.control.id)
                NcmClaimTrace.trace("openOnce control=${function.control.id} data=${function.data.id} hostMac=${descriptorHostMac?.macString() ?: "unavailable"}")
                // vc103j（T3 拓扑定案）：全量 raw descriptors hex dump——接口 3 是真实独立
                // 接口还是接口 2 双 alt 的 framework 幻影，看 bInterfaceNumber/bAlternateSetting
                // 字节序列即刻定案（内核 usbcore 与 framework 用户态解析谁对谁错）。
                NcmClaimTrace.trace("raw descriptors: " + connection.rawDescriptors.toHexString())
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "ncm descriptor hostMac=${descriptorHostMac?.macString() ?: "unavailable"}",
                )
                // Apple's Ethernet function exposes control and data as alternate settings of the
                // same interface id, so it must be claimed once and switched with setInterface.
                val sameInterface = function.control.id == function.data.id
                val first = if (sameInterface) function.data else function.control
                // vc101（T3）：framework claim 败（内核 cdc_ncm 占用）先试 usbfs 原子抢回——
                // USBDEVFS_DISCONNECT_CLAIM 在内核锁下一步「断驱动+claim」，消除 sysfs unbind
                // 后 67ms re-probe 抢绑的竞速窗口；免 root，作用于已授权 fd。
                val firstClaimed = connection.claimInterface(first, true) ||
                    nativeForceClaim(connection, first.id) == 0
                NcmClaimTrace.trace("first claim iface=${first.id} ok=$firstClaimed")
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "claim iface=${first.id}/${altOf(first)} class=${first.interfaceClass}" +
                        " subclass=${first.interfaceSubclass} proto=${first.interfaceProtocol} ok=$firstClaimed",
                )
                if (!firstClaimed) {
                    NcmClaimTrace.trace("first claim FAILED iface=${first.id} -> DeviceUnavailable")
                    throw IphoneUsbException.DeviceUnavailable(
                        "Android could not claim the NCM interface ${first.id}",
                    )
                }
                claimed.add(first)
                // vc103（T3 定案）：KK libusbhost 把 Apple「同接口号双 alt」NCM（控制=2/alt0、
                // 数据=2/alt1）拆成幻影接口号——Java 描述符见 data=3，内核 usb_ifnum_to_if(3)
                // 为 NULL，framework claim 与 DISCONNECT_CLAIM 双双 ENOENT（T3 实测 errno=2）。
                // data claim 失败且 force-claim 报 ENOENT 时走 Apple alt-style：数据面复用
                // 已 claim 成功的 control 接口号（bulk ep 来自 Java 解析仍正确），SET_INTERFACE
                // 切 alt 激活。其他 errno（EBUSY 等）维持原失败——真被占用时 ENOENT 兜底无意义。
                var dataIfaceId = function.data.id
                if (!sameInterface) {
                    var dataClaimed = connection.claimInterface(function.data, true)
                    val forceErrno = if (dataClaimed) 0 else nativeForceClaim(connection, function.data.id)
                    dataClaimed = forceErrno == 0
                    Log.i(
                        IphoneCarPlayConfiguration.TAG,
                        "claim iface=${function.data.id}/${altOf(function.data)}" +
                            " class=${function.data.interfaceClass} ok=$dataClaimed" +
                            " errno=$forceErrno",
                    )
                    if (!dataClaimed) {
                        if (forceErrno != 2) {
                            NcmClaimTrace.trace("data claim FAILED iface=${function.data.id} errno=$forceErrno (not ENOENT) -> fail")
                            throw IphoneUsbException.DeviceUnavailable(
                                "Android could not claim the NCM data interface ${function.data.id}",
                            )
                        }
                        // vc103k/l（sysfs 终审定案后的配置舞 2.0）：内核活动配置（sysfs
                        // bConfigurationValue=4）与 framework 缓存（CarPlay 配置 5）错位——
                        // 0x52 重枚举后内核 choose_configuration 选了配置 4，framework
                        // 缓存的是设备添加瞬间的配置 5。舞 2.0：按拓扑匹配（control=CDC-
                        // Control 且 data=CDC-Data）解析目标配置值，native SETCONFIGURATION
                        // 强制内核切过去（4→5 真切换），内核重解析后 iface 3 真实注册，
                        // 重 claim 后走常规 SET_INTERFACE(data, alt1)。vc103l：KK 内核
                        // proc_setconfig 在设备上有 usbfs claim 时拒绝（EBUSY=16 实测），
                        // 舞前先 RELEASEINTERFACE 释放 usbmux(1)+control(2)，舞后重 claim。
                        // 舞失败回退 alt-style→裸 URB→RESET 旧行为。
                        var repaired = false
                        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT &&
                            LegacyUsbHostNative.available
                        ) {
                            val fd = runCatching { connection.fileDescriptor }.getOrDefault(-1)
                            val configValue =
                                parseNcmConfigValue(connection.rawDescriptors, function.control.id, function.data.id)
                            NcmClaimTrace.trace(
                                "data claim ENOENT iface=${function.data.id} -> config dance 2.0" +
                                    " (kernel active config != framework cached) fd=$fd" +
                                    " ncmConfigValue=$configValue (topology-matched)",
                            )
                            if (fd >= 0 && configValue > 1) {
                                // 舞前释放本 fd 的 usbfs claim（EBUSY=16 根因）。
                                // usbmux(1) 是调用方 claim 的，control(2) 是上面刚 claim 的。
                                // vc103l trace：usbmux1 release=22(EINVAL)=本 fd 从未 claim
                                // 1（KK UsbDeviceConnection.claimInterface 走了 native 旁路，
                                // usbfs 位图无记录可释放）——22 非致命，忽略；control2=0 正常。
                                val r1 = try {
                                    LegacyUsbHostNative.releaseInterface(fd, 1)
                                } catch (_: LinkageError) {
                                    -1
                                }
                                val r2 = try {
                                    LegacyUsbHostNative.releaseInterface(fd, function.control.id)
                                } catch (_: LinkageError) {
                                    -1
                                }
                                NcmClaimTrace.trace(
                                    "config dance 2.0 pre-release usbmux1=$r1" +
                                        " control${function.control.id}=$r2 (0=released)",
                                )
                                val danceErrno = try {
                                    LegacyUsbHostNative.setConfiguration(fd, configValue)
                                } catch (_: LinkageError) {
                                    -1
                                }
                                NcmClaimTrace.trace(
                                    "config dance 2.0: set($configValue) errno=$danceErrno" +
                                        " (0=kernel re-parsed CarPlay config)",
                                )
                                // vc103m：KK proc_setconfig 对「接口已被 usbfs claim」的判定
                                // 含非本 fd 的内核驱动绑定（t3watchd2 常驻接口 2——dmesg
                                // 实锤），EBUSY 释放不掉。fallback：RESET（portReset 自动
                                // 断开重枚举，内核对全部配置全量重解析），RESET 后内核落在
                                // 自己选的配置，此时 framework 缓存与内核可能对齐（设备实例
                                // 全新、无 0x52 中途切配置的历史）——重开连接后常规路径即可。
                                if (danceErrno == 16) {
                                    NcmClaimTrace.trace(
                                        "config dance 2.0 EBUSY(16) persists (foreign kernel" +
                                            " driver claims) -> fall through to RESET path",
                                    )
                                } else if (danceErrno == 0) {
                                    // 配置切换清内核 claim 位图并可能重绑驱动——按号位重 claim。
                                    // usbmux(1) 由调用方 claim，此处 forceClaim 恢复；control/data 重 claim。
                                    val c1 = try {
                                        LegacyUsbHostNative.forceClaim(fd, 1)
                                    } catch (_: LinkageError) {
                                        -1
                                    }
                                    val c2 = try {
                                        LegacyUsbHostNative.forceClaim(fd, function.control.id)
                                    } catch (_: LinkageError) {
                                        -1
                                    }
                                    val c3 = try {
                                        LegacyUsbHostNative.forceClaim(fd, function.data.id)
                                    } catch (_: LinkageError) {
                                        -1
                                    }
                                    NcmClaimTrace.trace(
                                        "config dance 2.0 re-claim usbmux1=$c1 control" +
                                            "${function.control.id}=$c2 data${function.data.id}=$c3",
                                    )
                                    if (c3 == 0) {
                                        dataClaimed = true
                                        dataIfaceId = function.data.id
                                        claimed.add(function.data)
                                        repaired = true
                                    }
                                }
                            }
                        }
                        if (!repaired) {
                            NcmClaimTrace.trace(
                                "data claim ENOENT iface=${function.data.id} -> apple alt-style reuse control iface ${function.control.id}",
                            )
                            Log.i(
                                IphoneCarPlayConfiguration.TAG,
                                "apple alt-style NCM: data iface ${function.data.id} absent in" +
                                    " kernel; reusing control iface ${function.control.id}",
                            )
                            dataIfaceId = function.control.id
                        }
                    } else {
                        claimed.add(function.data)
                    }
                }
                // vc98（KK 适配）：API<21 无 setInterface 方法（API21+ 才有），旧 runCatching
                // 兜底在 KK 上必然 NoSuchMethodError→false，设备侧 NCM alt 恒 0 → iPhone 数据
                // 面零包（T3 20261017 晚实测闭环：协议栈全通、iPhone 永不发包、60s 看门狗）。
                // 改发 USB 标准类请求 SET_INTERFACE(bmRequestType=0x01, bRequest=0x0B)，
                // claim 过接口即可发，无需 root；TS7 等内核 cdc_ncm 自动发 alt=1 的平台不受影响。
                // vc101（T3 审查修正）：controlTransfer 只切设备侧、不更新内核 ep 启用表——
                // cdc_ncm detach 后内核可能已 disable bulk ep，内核视角 alt 仍 0 时后续 bulk
                // URB 提交失败。改 usbfs USBDEVFS_SETINTERFACE ioctl 优先（内核 usb_set_interface
                // 同步 ep 表+发设备请求，legacy-ref 同款），controlTransfer 仅作 ioctl 不可用时兜底。
                // vc98（KK 适配）：API<21 无 setInterface 方法（API21+ 才有），旧 runCatching
                // 兜底在 KK 上必然 NoSuchMethodError→false，设备侧 NCM alt 恒 0 → iPhone 数据
                // 面零包（T3 20261017 晚实测闭环：协议栈全通、iPhone 永不发包、60s 看门狗）。
                // vc103r（Legacy 对齐）：SET_INTERFACE 改描述符驱动 + 5次×100ms 重试——
                // alt 取自描述符（altOf，不再硬编码）；vendor cdc_ncm 驱动可能在 claim
                // 成功与 SET_INTERFACE 之间抢绑（Legacy 同款竞速场景），重试窗口消除。
                // 每次 attempt 三级尝试：framework setInterface → native USBDEVFS_SETINTERFACE
                // （errno 透传）→ controlTransfer 类请求兜底；成功即止，全败落裸 URB/RESET
                // 旧裁决链。健康路径（nova3e 等 L+ 机型）首 attempt framework 即中，行为不变。
                val targetAlt = altOf(function.data).takeIf { it > 0 } ?: 1
                var altSelected = false
                var lastAltErrno: Int? = null
                for (altAttempt in 1..ALT_SETTING_ATTEMPTS) {
                    if (altAttempt > 1) {
                        try {
                            Thread.sleep(ALT_SETTING_RETRY_MILLIS)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
                        runCatching { connection.setInterface(function.data) }.getOrDefault(false)
                    ) {
                        altSelected = true
                        NcmClaimTrace.trace(
                            "setInterface attempt=$altAttempt framework iface=$dataIfaceId alt=$targetAlt ok=true",
                        )
                        break
                    }
                    val ioctlErrno = nativeSetInterfaceErrno(connection, dataIfaceId, targetAlt)
                    lastAltErrno = ioctlErrno
                    if (ioctlErrno == 0) {
                        altSelected = true
                        NcmClaimTrace.trace(
                            "setInterface attempt=$altAttempt ioctl iface=$dataIfaceId alt=$targetAlt ok=true",
                        )
                        break
                    }
                    val rc = connection.controlTransfer(
                        0x01, // Host-to-Device | Class | Interface
                        0x0B, // SET_INTERFACE
                        targetAlt,
                        dataIfaceId,
                        null,
                        0,
                        USB_CONTROL_TIMEOUT_MILLIS,
                    )
                    NcmClaimTrace.trace(
                        "setInterface attempt=$altAttempt iface=$dataIfaceId alt=$targetAlt" +
                            " ioctlErrno=$ioctlErrno ctlRc=$rc (negative=usbfs error)",
                    )
                    if (rc >= 0) {
                        altSelected = true
                        Log.i(
                            IphoneCarPlayConfiguration.TAG,
                            "legacy SET_INTERFACE iface=$dataIfaceId alt=$targetAlt rc=$rc",
                        )
                        break
                    }
                }
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "setInterface iface=$dataIfaceId alt=$targetAlt ok=$altSelected",
                )
                NcmClaimTrace.trace(
                    "setInterface FINAL iface=$dataIfaceId alt=$targetAlt ok=$altSelected" +
                        " lastErrno=$lastAltErrno",
                )
                // vc103f（T3 终审）：SET_INTERFACE 失败后的裸 URB 探测——向 data bulk 端点
                // 直接提交 1 字节 IN 探测（usbfs 按端点地址校验，绕过接口/alt 表）。
                // framework bulkTransfer 只返 -1 拿不到 errno，必须 native 提交才有裁决权：
                // 0 = 端点活着（SET_INTERFACE 的 EINVAL 无关紧要，直接用）；
                // EINVAL/ENOENT = 内核端点表残缺实锤（枚举期竞速半解析终审证据）。
                var bareUrbProbed = false
                if (!altSelected) {
                    val probeFd = runCatching { connection.fileDescriptor }.getOrDefault(-1)
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                        probeFd >= 0 &&
                        LegacyUsbHostNative.available
                    ) {
                        val epProbe = try {
                            LegacyUsbHostNative.submitUrbProbe(probeFd, function.bulkIn.address)
                        } catch (_: LinkageError) {
                            -1
                        }
                        NcmClaimTrace.trace(
                            "bare URB probe bulkIn=0x${function.bulkIn.address.toString(16)}" +
                                " errno=$epProbe" +
                                " (0=endpoint alive; EINVAL/ENOENT=kernel endpoint table broken)",
                        )
                        Log.i(
                            IphoneCarPlayConfiguration.TAG,
                            "vc103f bare URB probe bulkIn" +
                                " ep=0x${function.bulkIn.address.toString(16)} errno=$epProbe",
                        )
                        bareUrbProbed = true
                        if (epProbe == 0) {
                            NcmClaimTrace.trace(
                                "bare URB probe OK -> endpoint alive; proceeding without SET_INTERFACE",
                            )
                        } else {
                            // vc103g（T3 终审）：EINVAL/ENOENT = 内核端点表残缺实锤
                            // （枚举期竞速半解析，与 claim(3) ENOENT / SET_INTERFACE EINVAL
                            // 三证合一）。唯一修复=USBDEVFS_RESET 强制内核重枚举：RESET 后
                            // 本 fd 失效，抛专用标记异常让上层走 reopen（重开连接后内核
                            // 全量重解析，常规 claim/SET_INTERFACE 全部正常）。
                            // resetErrno -1 = native 不可用（LinkageError），维持旧行为失败。
                            val resetFd = runCatching { connection.fileDescriptor }.getOrDefault(-1)
                            val resetErrno = try {
                                LegacyUsbHostNative.portReset(resetFd)
                            } catch (_: LinkageError) {
                                -1
                            }
                            NcmClaimTrace.trace(
                                "bare URB probe REJECTED errno=$epProbe -> USBDEVFS_RESET" +
                                    " fd=$resetFd errno=$resetErrno" +
                                    " (0|19=reset done, reopen next; -1=native unavailable)",
                            )
                            // errno 19(ENODEV)：RESET 触发断开重枚举，原设备节点失效——
                            // 这是 reset 生效的正常表现（T3 vc103h 实测），与 0 同归重试路。
                            if (resetErrno == 0 || resetErrno == 19) {
                                throw IphoneUsbException.DeviceUnavailable(
                                    "KERNEL_RESET_DONE: endpoint table repaired by USBDEVFS_RESET," +
                                        " reopen required (bare URB probe errno=$epProbe)",
                                )
                            }
                            throw IphoneUsbException.DeviceUnavailable(
                                "Kernel USB endpoint table broken" +
                                    " (bare URB probe errno=$epProbe, reset errno=$resetErrno)",
                            )
                        }
                    }
                }
                if (!altSelected && !bareUrbProbed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    throw IphoneUsbException.DeviceUnavailable(
                        "Android could not select the NCM data alternate setting",
                    )
                }
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "ncm status endpoint=${function.statusIn?.address?.let { "0x${it.toString(16)}" } ?: "none"}",
                )
                return NcmUsbBridge(
                    connection,
                    function.bulkOut,
                    function.bulkIn,
                    function.statusIn,
                    claimed,
                    descriptorHostMac,
                    ownsConnection,
                )
            } catch (error: Throwable) {
                for (usbInterface in claimed.asReversed()) {
                    try {
                        connection.releaseInterface(usbInterface)
                    } catch (_: RuntimeException) {
                        // The connection close below is authoritative.
                    }
                }
                if (ownsConnection) connection.close()
                if (error is IphoneUsbException) throw error
                // vc103：catch-all 不再吞真凶——类名+消息+栈顶进错误消息与 trace 文件，
                // 远程排障不再只见 "Android NCM open failed" 盲猜。
                val topFrame = error.stackTrace.firstOrNull()?.let { "${it.methodName}:${it.lineNumber}" } ?: "?"
                NcmClaimTrace.trace("openOnce UNEXPECTED ${error.javaClass.simpleName}: ${error.message} @ $topFrame")
                throw IphoneUsbException.DeviceUnavailable(
                    "Android NCM open failed: ${error.javaClass.simpleName}: ${error.message} @ $topFrame",
                    error,
                )
            }
        }

        private fun readNcmHostMac(connection: UsbDeviceConnection, controlInterfaceId: Int): ByteArray? {
            val index = ethernetMacStringIndex(connection.rawDescriptors, controlInterfaceId) ?: return null
            val buffer = ByteArray(256)
            val length = connection.controlTransfer(
                UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_STANDARD,
                USB_REQUEST_GET_DESCRIPTOR,
                (USB_STRING_DESCRIPTOR_TYPE shl 8) or index,
                USB_ENGLISH_US,
                buffer,
                buffer.size,
                USB_CONTROL_TIMEOUT_MILLIS,
            )
            if (length < 4 || (buffer[1].toInt() and 0xff) != USB_STRING_DESCRIPTOR_TYPE) return null
            val descriptorLength = (buffer[0].toInt() and 0xff).coerceAtMost(length)
            if (descriptorLength < 4) return null
            val value = buffer.copyOfRange(2, descriptorLength).toString(Charsets.UTF_16LE)
            val hex = value.filter { it.digitToIntOrNull(16) != null }
            if (hex.length != 12) return null
            return ByteArray(6) { offset -> hex.substring(offset * 2, offset * 2 + 2).toInt(16).toByte() }
        }

        private fun ethernetMacStringIndex(raw: ByteArray, controlInterfaceId: Int): Int? {
            var offset = 0
            var currentInterface = -1
            while (offset + 2 <= raw.size) {
                val length = raw[offset].toInt() and 0xff
                val type = raw[offset + 1].toInt() and 0xff
                if (length < 2 || offset + length > raw.size) return null
                if (type == USB_INTERFACE_DESCRIPTOR_TYPE && length >= 9) {
                    currentInterface = raw[offset + 2].toInt() and 0xff
                } else if (
                    type == CDC_FUNCTIONAL_DESCRIPTOR_TYPE &&
                    length >= 4 &&
                    currentInterface == controlInterfaceId &&
                    (raw[offset + 2].toInt() and 0xff) == CDC_ETHERNET_SUBTYPE
                ) {
                    return (raw[offset + 3].toInt() and 0xff).takeIf { it != 0 }
                }
                offset += length
            }
            return null
        }

        private fun ByteArray.macString(): String =
            joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

        private const val USB_INTERFACE_DESCRIPTOR_TYPE = 0x04
        private const val USB_REQUEST_GET_DESCRIPTOR = 0x06
        private const val USB_STRING_DESCRIPTOR_TYPE = 0x03
        private const val CDC_FUNCTIONAL_DESCRIPTOR_TYPE = 0x24
        private const val CDC_ETHERNET_SUBTYPE = 0x0f
        private const val USB_ENGLISH_US = 0x0409
        private const val USB_CONTROL_TIMEOUT_MILLIS = 1_000
        private const val USB_REQUEST_GET_CONFIGURATION = 8
    }
}
