package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbRequest
import android.os.Build
import android.util.Log
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

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
    private val asyncRead = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
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
        connection.close()
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
                throw failSession("NCM read buffer does not begin with an NTB16 header")
            }
            val blockLength = readU16(buffered, 8)
            if (blockLength < 28) throw failSession("Invalid NTB16 block length $blockLength")
            // 1.0.22（3e 实测 20:42 首画即卡死）：wBlockLength 为 512 整数倍时 iPhone 的
            // 定界方式既可能是 1 字节 0x00 短包、也可能是 ZLP（bulkTransfer 层面 0 长度，
            // 字节流中不可见）。原实现假设必有 0x00 pad：ZLP 定界时下一字节实为下一个
            // NTB 的 NTH16 签名首字节 → 校验失败 failSession → NCM 读线程终止 → 视频
            // 冻结（rx=6fps shown=1fps，"Invalid NTB16 short-packet pad"×2）。改为 pad
            // 可选：块边界处等下一字节判别，0x00 才消费 pad，否则按 blockLength 原样解析。
            val blockAligned = blockLength % USB_PACKET_SIZE == 0
            if (blockAligned && bufferedSize <= blockLength) return
            val wireLength =
                if (blockAligned && buffered[blockLength].toInt() == 0) blockLength + 1 else blockLength
            if (bufferedSize < wireLength) return
            for (frame in Ntb16Codec.parse(buffered, 0, blockLength)) enqueueFrame(frame)
            val remaining = bufferedSize - wireLength
            buffered.copyInto(buffered, 0, wireLength, bufferedSize)
            bufferedSize = remaining
        }
    }

    private fun appendBuffered(source: ByteArray, length: Int) {
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
        return if (transferred <= 0) null else transferred
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
        private const val USB_PACKET_SIZE = 512
        private const val STATUS_POLL_TIMEOUT_MILLIS = 20
        private const val STATUS_POLL_INTERVAL_MILLIS = 500L
        private const val MAX_QUEUED_FRAMES = 256
        private const val MAX_QUEUED_BYTES = 1 shl 20
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val ASYNC_QUEUE_FAILURE_LIMIT = 3

        /** 1.0.3：getAlternateSetting 是 API 24+，< 24 固定打 0（仅诊断日志用途）。 */
        private fun altOf(usbInterface: UsbInterface): Int =
            if (Build.VERSION.SDK_INT >= 24) usbInterface.alternateSetting else 0

        /** Claims and activates the NCM control/data interfaces; owns the connection on success. */
        fun open(connection: UsbDeviceConnection, function: NcmFunctionDiscovery.NcmFunction): NcmUsbBridge {
            // 1.1.3（root 抢回）：claim 失败且车机有 root 时，unbind 内核 cdc_ncm 后重试一次。
            // 同一 connection 复用安全：失败路径已 releaseInterface，重试重新 claim。
            try {
                return openOnce(connection, function)
            } catch (error: IphoneUsbException.DeviceUnavailable) {
                val message = error.message ?: ""
                if (!message.contains("could not claim") && !message.contains("could not select")) throw error
                if (!RootNcmReclaimer.reclaim(message)) throw error
                try {
                    Thread.sleep(400)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                return openOnce(connection, function)
            }
        }

        private fun openOnce(connection: UsbDeviceConnection, function: NcmFunctionDiscovery.NcmFunction): NcmUsbBridge {
            val claimed = ArrayList<UsbInterface>(2)
            try {
                val descriptorHostMac = readNcmHostMac(connection, function.control.id)
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "ncm descriptor hostMac=${descriptorHostMac?.macString() ?: "unavailable"}",
                )
                // Apple's Ethernet function exposes control and data as alternate settings of the
                // same interface id, so it must be claimed once and switched with setInterface.
                val sameInterface = function.control.id == function.data.id
                val first = if (sameInterface) function.data else function.control
                val firstClaimed = connection.claimInterface(first, true)
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "claim iface=${first.id}/${altOf(first)} class=${first.interfaceClass}" +
                        " subclass=${first.interfaceSubclass} proto=${first.interfaceProtocol} ok=$firstClaimed",
                )
                if (!firstClaimed) {
                    throw IphoneUsbException.DeviceUnavailable(
                        "Android could not claim the NCM interface ${first.id}",
                    )
                }
                claimed.add(first)
                if (!sameInterface) {
                    val dataClaimed = connection.claimInterface(function.data, true)
                    Log.i(
                        IphoneCarPlayConfiguration.TAG,
                        "claim iface=${function.data.id}/${altOf(function.data)}" +
                            " class=${function.data.interfaceClass} ok=$dataClaimed",
                    )
                    if (!dataClaimed) {
                        throw IphoneUsbException.DeviceUnavailable(
                            "Android could not claim the NCM data interface ${function.data.id}",
                        )
                    }
                    claimed.add(function.data)
                }
                // API16 适配：setInterface（选择 alternate setting）疑似 API 21+ 且无法在本地
                // 验证 android-19 头文件。<21 上 runCatching 兜底：方法缺失/失败都只记警告继续，
                // 由后续数据路径超时暴露问题（护栏无害，>= 21 行为与上游一致）。
                val altSelected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    connection.setInterface(function.data)
                } else {
                    runCatching { connection.setInterface(function.data) }.getOrElse {
                        Log.w(
                            IphoneCarPlayConfiguration.TAG,
                            "legacy setInterface unavailable (alt setting may stay 0): ${it.javaClass.simpleName}",
                        )
                        false
                    }
                }
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "setInterface iface=${function.data.id}/${altOf(function.data)} ok=$altSelected",
                )
                if (!altSelected && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
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
                )
            } catch (error: Throwable) {
                for (usbInterface in claimed.asReversed()) {
                    try {
                        connection.releaseInterface(usbInterface)
                    } catch (_: RuntimeException) {
                        // The connection close below is authoritative.
                    }
                }
                connection.close()
                if (error is IphoneUsbException) throw error
                throw IphoneUsbException.DeviceUnavailable("Android NCM open failed", error)
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
    }
}
