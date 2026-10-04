package com.shilapi.xcertplay.airplay

import android.os.Process
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class AudioCodecKind { AAC_LC, OPUS, LPCM }

data class AudioFormat(
    val codec: AudioCodecKind,
    val sampleRate: Int,
    val channels: Int,
    val payloadType: Int,
    val audioType: String = "media",
)

/**
 * Binds the RTP data and RTCP control UDP ports for one CarPlay audio stream.
 *
 * Wire layout follows LIVI `livi_audio_stream`: one RTP packet per datagram, a 12-byte header,
 * ciphertext, a 16-byte tag, then an 8-byte little-endian nonce. The header's last eight bytes
 * (timestamp + SSRC) are the AEAD associated data.
 */
class AudioStream(
    private val key: ByteArray,
    private val streamType: Int = -1,
    private val onDiagnostic: (String) -> Unit = {},
    // M6k7：对端（iPhone）通告的重传控制端口，来自 type=102 SETUP 的 controlPort
    // （m6f 日志 5/5 会话实证）。0 = 对端未通告（如 type=101）→ 重传/重排整体关闭。
    private val peerControlPort: Int = 0,
) : Closeable {
    interface Listener {
        fun onStarted(firstSample: Int) {}
        fun onRtp(rtp: ByteArray, sample: Int) {}
        fun onPacket(
            wire: ByteArray,
            rtp: ByteArray?,
            sample: Int?,
            error: Throwable?,
        ) {}
    }

    private val closed = AtomicBoolean(false)
    private val receivedPackets = AtomicInteger()
    private val decryptedPackets = AtomicInteger()
    private val authenticationFailures = AtomicInteger()
    private var dataSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null
    private var dataThread: Thread? = null
    private var controlThread: Thread? = null
    private var started = false

    // ===== M6k7：主动重传（PT=85 请求 / PT=86 回注）+ 按 SN 小窗口重排 =====
    // 重排必须在 AudioStream 层做：下游 compensateMissingFrames 对迟到帧照样写入
    // （gapUs<=0 时仅重置基准），重传帧不按序注入会错位出杂音。
    // held/expectedSeq 会被控制线程（PT=86 注入）与数据线程共同访问，一律在 reorderLock 内改。
    private val reorderLock = Any()
    private val held = TreeMap<Int, PendingFrame>()
    private var expectedSeq = -1          // 下一个应按序交付的 SN；-1 = 首包未到，未建立基准
    private var gapSinceNanos = 0L        // 最早在等缺口的起始时刻；0 = 无缺口计时
    private val requestSequence = AtomicInteger()
    @Volatile private var controlPeerAddress: InetAddress? = null

    // 限速簿记（仅数据线程访问）：同缺口按重试间隔、异缺口按最小间隔、全局每秒预算
    private var requestsSent = 0
    private var requestsDisabled = false
    private var reqFirst = -1
    private var reqCount = 0
    private var reqSentNanos = 0L
    private var reqWindowNanos = 0L
    private var reqBudget = 0

    // 诊断计数：带 Atomic 的会被控制线程写；其余仅数据线程访问
    private val retrFilled = AtomicInteger()
    private val retrLate = AtomicInteger()
    private val retrDuplicate = AtomicInteger()
    private val retrDecryptFailures = AtomicInteger()
    private val retrReplies = AtomicInteger()   // PT=86 原始到达数（未经重排过滤），应答率的分子
    private var retrHoldFlushes = 0

    fun listen(listener: Listener): Pair<Int, Int> {
        val data = bindAnyPort()
        // Keep short Wi-Fi bursts in the kernel while decrypting or scheduling pauses
        // the receive thread. The platform may cap this request; there is no harm either way.
        if (data.receiveBufferSize < AUDIO_RECEIVE_BUFFER_BYTES) {
            runCatching { data.receiveBufferSize = AUDIO_RECEIVE_BUFFER_BYTES }
        }
        val control = bindControlPort()
        dataSocket = data
        controlSocket = control
        // M6i：把两端端口的**绑定族**落盘。T43/T44 两份日志里控制口零入站（连 PT=84 都没有），
        // 而数据口正常 —— 有这一行才能分清"对方没发"还是"我们绑错了族收不到"。
        onDiagnostic(
            "Audio: ports type=$streamType dataPort=${data.localPort}(${familyOf(data)}) " +
                "controlPort=${control.localPort}(${familyOf(control)})",
        )
        if (peerControlPort > 0) {
            // M6k7：主动重传已武装。数据口加 SO_TIMEOUT 让收包线程周期性醒来做「缺口超时放行」，
            // 否则流停/收尾瞬间在手的包会一直扣到下一包才走。个别平台不允许就退化为
            // 「靠后续包到达驱动放行」，只影响收尾、不影响正确性。
            runCatching { data.soTimeout = RETRANSMIT_POLL_MS }
            onDiagnostic("Audio: retr armed type=$streamType peerControlPort=$peerControlPort")
        } else {
            // type=101（default）不带 controlPort（m6f 实证）：不做重排也不发请求——
            // 没有补包来源的等待是纯加时延，维持原直通路径。
            onDiagnostic("Audio: retr off type=$streamType no peer controlPort")
        }
        // 收包/解密/入队单独一条线程，**刻意不与后面的解码/写轨线程合并**（M6b 决策）：
        // 合并后若解码（libopus 或 AudioTrack 写）偶发耗时，socket.receive 就被顶住，
        // 内核 UDP 接收缓冲溢出 = 真丢包（比"多一次线程切换"严重得多）。现在解码线程再慢，
        // 收包最多进 queue，最多触发背压丢弃，而不会让内核丢包。
        // 因此弱机省 CPU 的方向是"抬优先级 + 减少唤醒"，不是合并线程。
        dataThread = Thread({
            // 弱机（C8815 双核 A5）上实测收包线程单包处理峰值被抢占到 93~163ms（而这段只做解密+入队）
            // ⇒ 抬到 URGENT_AUDIO，与视频解码/渲染抢 CPU 时音频优先，压掉调度延迟尖峰。
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            runData(data, listener)
        }, "airplay-audio-rx").apply {
            isDaemon = true
            start()
        }
        controlThread = Thread({ runControl(control) }, "airplay-rtcp-rx").apply {
            isDaemon = true
            start()
        }
        return data.localPort to control.localPort
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        dataSocket?.close()
        controlSocket?.close()
        dataThread?.interrupt()
        controlThread?.interrupt()
    }

    private fun runData(socket: DatagramSocket, listener: Listener) {
        val stats = StreamReceiveStats("audio type=$streamType", onDiagnostic)
        val buffer = ByteArray(DATAGRAM_BYTES)
        try {
            while (!closed.get()) {
                // M6k7：等待期轮询放行（SO_TIMEOUT 醒来也走到这里），防止流停后在手包被无限扣住
                if (peerControlPort > 0) releaseExpiredHeld(listener)
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    stats.reading()
                    socket.receive(packet)
                } catch (_: Exception) {
                    if (closed.get()) return else continue
                }
                // M6k7：PT=85 的目的地从入站包自然习得（数据口/控制口同源主机，比构造时传地址更自愈）
                if (controlPeerAddress == null && packet.address != null) {
                    controlPeerAddress = packet.address
                }
                stats.received(packet.length, if (packet.length >= 12)
                    ((buffer[2].toInt() and 0xff) shl 8) or (buffer[3].toInt() and 0xff) else null)
                val wire = packet.data.copyOf(packet.length)
                val packetNumber = receivedPackets.incrementAndGet()
                if (wire.size < RTP_HEADER_LEN + TAIL_LEN) {
                    if (packetNumber == 1) {
                        android.util.Log.w(
                            TAG,
                            "audio stream type=$streamType short packet bytes=${wire.size}",
                        )
                    }
                    listener.onPacket(
                        wire,
                        null,
                        null,
                        IOException("audio packet shorter than RTP header plus tail"),
                    )
                    stats.processed()
                    continue
                }

                val sample = readU32Be(wire, 4)
                val rtp = try {
                    decryptWire(wire)
                } catch (error: Exception) {
                    val failureNumber = authenticationFailures.incrementAndGet()
                    if (failureNumber == 1) {
                        android.util.Log.w(
                            TAG,
                            "audio stream type=$streamType first decrypt failure " +
                                "wire=${wire.toHexString()}",
                            error,
                        )
                    }
                    listener.onPacket(wire, null, sample, error)
                    stats.processed()
                    continue
                }
                val decryptedNumber = decryptedPackets.incrementAndGet()
                if (decryptedNumber <= FIRST_PACKET_LOG_COUNT) {
                    android.util.Log.i(
                        TAG,
                        "audio stream type=$streamType packet=$decryptedNumber sample=$sample " +
                            "wireBytes=${wire.size} payloadBytes=${rtp.size - RTP_HEADER_LEN} " +
                            "payloadHead=" +
                            "${rtp.copyOfRange(RTP_HEADER_LEN, minOf(rtp.size, RTP_HEADER_LEN + 16)).toHexString()}",
                    )
                } else if (decryptedNumber % PACKET_LOG_INTERVAL == 0) {
                    // M6k7：重传健康度并入周期日志；必须同时走文件日志（只进 logcat 无从取证，#30 教训）
                    val heldSize = synchronized(reorderLock) { held.size }
                    val health =
                        "audio stream type=$streamType decrypted=$decryptedNumber " +
                            "authFailures=${authenticationFailures.get()} retrReq=$requestsSent " +
                            "retrReply=${retrReplies.get()} retrFill=${retrFilled.get()} " +
                            "retrLate=${retrLate.get()} retrDup=${retrDuplicate.get()} " +
                            "retrFail=${retrDecryptFailures.get()} " +
                            "retrHoldFlush=$retrHoldFlushes heldSize=$heldSize"
                    android.util.Log.i(TAG, health)
                    onDiagnostic(health)
                }
                listener.onPacket(wire, rtp, sample, null)
                if (!started) {
                    started = true
                    listener.onStarted(sample)
                }
                if (peerControlPort > 0) {
                    // 重排开启：缺口检测 + 按序交付都在这里完成
                    acceptPacket(readU16Be(wire, 2), rtp, sample, listener)
                } else {
                    listener.onRtp(rtp, sample)
                }
                stats.processed()
            }
        } finally { stats.flush(ended = true) }
    }

    /**
     * M6h 取证：**controlPort 不是"没用的口"**。
     *
     * 依 AirPlay 的 RTCP 约定（openairplay: RTP Streams），这条 UDP 上跑的是：
     *   `PT=84` time sync（8B 头 + 8B NTP + 4B 下一个 RTP 时间戳，每秒一次）
     *   `PT=85` 重传请求（8B 头 + 首个丢包 SN(2B) + 丢包个数(2B)，Marker=1）
     *   `PT=86` 重传应答（8B 头 + 一条完整音频 RTP 包）
     * 而 `96` 才是音频数据。
     *
     * M6h 阶段只收包取证（谁在发、发什么、86 补的是哪些 SN）；M6k7 起升级为主动重传：
     * 数据线程发现缺口 → 主动发 PT=85（[maybeSendRequest]，目的地 = SETUP controlPort）；
     * 这里收到 PT=86 → 剥 8B 头解密 → 注入重排窗口（[handleRetransmitReply]），
     * 交付仍由数据线程按序完成。取证日志全部保留。
     */
    private fun runControl(socket: DatagramSocket) {
        val buffer = ByteArray(DATAGRAM_BYTES)
        val counts = HashMap<Int, Int>()
        val detailsLogged = HashMap<Int, Int>()
        var total = 0
        var lastSummarisedTotal = -1
        var nextSummaryNanos = System.nanoTime() + CONTROL_SUMMARY_INTERVAL_NANOS
        while (!closed.get()) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: Exception) {
                if (closed.get()) return
                continue
            }
            if (controlPeerAddress == null && packet.address != null) {
                controlPeerAddress = packet.address
            }
            val length = packet.length
            if (length < CONTROL_HEADER_LEN) {
                onDiagnostic("Audio: control rx type=$streamType short packet bytes=$length")
                continue
            }
            val headerByte = buffer[1].toInt() and 0xff
            val payloadType = headerByte and 0x7f
            val marker = (headerByte and 0x80) != 0
            val sequence = readU16Be(buffer, 2)
            total++
            if (total == 1) {
                onDiagnostic(
                    "Audio: control peer type=$streamType from=" +
                        "${packet.address?.hostAddress}:${packet.port}",
                )
            }
            // API16 适配：HashMap.merge 是 Java 8 API，低版本别用（同 computeIfAbsent 的坑）。
            val seen = (counts[payloadType] ?: 0) + 1
            counts[payloadType] = seen
            val detailed = detailsLogged[payloadType] ?: 0
            if (detailed < CONTROL_DETAIL_PACKETS) {
                detailsLogged[payloadType] = detailed + 1
                onDiagnostic(
                    "Audio: control rx type=$streamType pt=$payloadType marker=$marker " +
                        "len=$length seq=$sequence ${controlDetail(buffer, length, payloadType)}",
                )
            } else if (seen % CONTROL_LOG_INTERVAL == 0) {
                onDiagnostic(
                    "Audio: control rx type=$streamType pt=$payloadType n=$seen " +
                        "len=$length seq=$sequence ${controlDetail(buffer, length, payloadType)}",
                )
            }
            val now = System.nanoTime()
            if (now >= nextSummaryNanos) {
                nextSummaryNanos = now + CONTROL_SUMMARY_INTERVAL_NANOS
                if (total != lastSummarisedTotal) {
                    lastSummarisedTotal = total
                    onDiagnostic(
                        "Audio: control summary type=$streamType total=$total " +
                            counts.entries.sortedBy { it.key }
                                .joinToString(" ") { "pt${it.key}=${it.value}" },
                    )
                }
            }
            // M6k7：重传应答体 = 8B 控制头 + 一条完整音频 RTP 包 → 解密后注入重排窗口
            if (payloadType == CONTROL_PT_RETRANSMIT_REPLY) handleRetransmitReply(buffer, length)
        }
    }

    /** 逐 PT 解析取证字段；只读 buffer，不做任何修改。 */
    private fun controlDetail(buffer: ByteArray, length: Int, payloadType: Int): String =
        when (payloadType) {
            CONTROL_PT_RETRANSMIT_REQUEST -> {
                if (length >= CONTROL_HEADER_LEN + 4) {
                    val firstLost = readU16Be(buffer, CONTROL_HEADER_LEN)
                    val lostCount = readU16Be(buffer, CONTROL_HEADER_LEN + 2)
                    "firstLostSn=$firstLost lostCount=$lostCount"
                } else {
                    "truncated"
                }
            }
            CONTROL_PT_RETRANSMIT_REPLY -> {
                if (length >= CONTROL_HEADER_LEN + 4) {
                    // 应答体是一条完整音频 RTP 包，其序号在 8B 控制头之后又偏移 2 字节。
                    val resentSn = readU16Be(buffer, CONTROL_HEADER_LEN + 2)
                    val resentPt = buffer[CONTROL_HEADER_LEN + 1].toInt() and 0x7f
                    "resentSn=$resentSn resentPt=$resentPt"
                } else {
                    "truncated"
                }
            }
            CONTROL_PT_TIME_SYNC -> {
                if (length >= CONTROL_HEADER_LEN + 12) {
                    "nextRtpTimestamp=" + readU32Be(buffer, CONTROL_HEADER_LEN + 8)
                } else {
                    "truncated"
                }
            }
            else -> "head=" + buffer.copyOf(minOf(length, 16)).toHexString()
        }

    // ===== M6k7 重传/重排实现：数据线程是唯一交付者，控制线程只注入 =====

    /**
     * 数据路径按 SN 入队：首包建立基准直接交付；正向缺口先暂存后继包并按限速规则发 PT=85，
     * 等 PT=86 补齐后由 [drainHeld] 按序吐出；迟到/重复包丢弃（丢给下游只会错位出杂音）。
     * SN 为 16 位模运算，全程无符号距离比较，跨回绕安全。
     */
    private fun acceptPacket(seq: Int, rtp: ByteArray, sample: Int, listener: Listener) {
        var deliverNow: PendingFrame? = null
        var requestFirst = -1
        var requestCount = 0
        synchronized(reorderLock) {
            if (expectedSeq == -1) {
                expectedSeq = (seq + 1) and 0xffff
                deliverNow = PendingFrame(rtp, sample)
            } else {
                val distance = (seq - expectedSeq) and 0xffff
                when {
                    distance == 0 -> {
                        deliverNow = PendingFrame(rtp, sample)
                        expectedSeq = (expectedSeq + 1) and 0xffff
                    }
                    distance < SEQ_HALF_RANGE -> {
                        if (held.isEmpty()) gapSinceNanos = System.nanoTime()
                        held[seq] = PendingFrame(rtp, sample)
                        if (held.size > MAX_HOLD_PACKETS) {
                            // 在手包远超等待能力（链路重度劣化期）：放弃最老缺口整体放行，
                            // 被跳过的帧交给 P0 静音补偿，别把时延拖爆。
                            expectedSeq = held.firstKey()
                            gapSinceNanos = 0L
                        } else {
                            requestFirst = expectedSeq
                            requestCount = minOf(distance, REQUEST_MAX_LOST)
                        }
                    }
                    else -> retrDuplicate.incrementAndGet()
                }
            }
        }
        deliverNow?.let { listener.onRtp(it.rtp, it.sample) }
        drainHeld(listener)
        if (requestCount > 0) maybeSendRequest(requestFirst, requestCount)
    }

    /** 把与基准连续的在手包按序交付；停在新缺口处时重启缺口计时。 */
    private fun drainHeld(listener: Listener) {
        while (true) {
            val frame = synchronized(reorderLock) {
                val next = held[expectedSeq]
                if (next == null) {
                    if (held.isNotEmpty() && gapSinceNanos == 0L) gapSinceNanos = System.nanoTime()
                    return
                }
                held.remove(expectedSeq)
                expectedSeq = (expectedSeq + 1) and 0xffff
                next
            }
            listener.onRtp(frame.rtp, frame.sample)
        }
    }

    /** 缺口等待超时：基准拨到最早在手包按序放行，被跳过的缺口交给 P0 兜底。 */
    private fun releaseExpiredHeld(listener: Listener) {
        synchronized(reorderLock) {
            if (expectedSeq == -1 || held.isEmpty() || gapSinceNanos == 0L) return
            if (System.nanoTime() - gapSinceNanos < RETRANSMIT_HOLD_TIMEOUT_NANOS) return
            expectedSeq = held.firstKey()
            gapSinceNanos = 0L
            retrHoldFlushes++
        }
        drainHeld(listener)
    }

    /** 限速发送重传请求：同缺口重试间隔、异缺口最小间隔、每秒预算，三重防请求风暴。 */
    private fun maybeSendRequest(firstSn: Int, lostCount: Int) {
        if (requestsDisabled) return
        val now = System.nanoTime()
        if (now >= reqWindowNanos + 1_000_000_000L) {
            reqWindowNanos = now
            reqBudget = RETRANSMIT_MAX_REQUESTS_PER_SECOND
        }
        if (reqBudget <= 0) return
        val sameRange = firstSn == reqFirst && lostCount == reqCount
        if (sameRange) {
            if (now - reqSentNanos < RETRANSMIT_RETRY_NANOS) return
        } else if (now - reqSentNanos < RETRANSMIT_MIN_REQUEST_INTERVAL_NANOS) {
            return
        }
        if (sendRetransmitRequest(firstSn, lostCount)) {
            reqSentNanos = now
            reqFirst = firstSn
            reqCount = lostCount
            reqBudget--
        }
    }

    /**
     * PT=85 重传请求：8B 控制头（0x80 / Marker|PT=85 / 自增 seq / 4B 保留 0）
     * + firstLostSN(2B BE) + lostCount(2B BE)，与 controlDetail 的 85 解析同构。
     * 从控制 socket 发往 SETUP 通告的 controlPort。
     */
    private fun sendRetransmitRequest(firstSn: Int, lostCount: Int): Boolean {
        val socket = controlSocket
        val target = controlPeerAddress
        if (socket == null || target == null) return false
        val packet = ByteArray(CONTROL_HEADER_LEN + 4)
        packet[0] = 0x80.toByte()
        packet[1] = (0x80 or CONTROL_PT_RETRANSMIT_REQUEST).toByte()
        val seq = requestSequence.incrementAndGet() and 0xffff
        packet[2] = ((seq ushr 8) and 0xff).toByte()
        packet[3] = (seq and 0xff).toByte()
        packet[8] = ((firstSn ushr 8) and 0xff).toByte()
        packet[9] = (firstSn and 0xff).toByte()
        packet[10] = ((lostCount ushr 8) and 0xff).toByte()
        packet[11] = (lostCount and 0xff).toByte()
        requestsSent++
        return try {
            socket.send(DatagramPacket(packet, packet.size, target, peerControlPort))
            if (requestsSent <= FIRST_PACKET_LOG_COUNT || requestsSent % RETRANSMIT_LOG_INTERVAL == 0) {
                onDiagnostic(
                    "Audio: retr req type=$streamType n=$requestsSent firstSn=$firstSn " +
                        "lost=$lostCount to=${target.hostAddress}:$peerControlPort",
                )
            }
            true
        } catch (error: Exception) {
            // 典型成因：IPv4 socket 发往 IPv6 目标（家族不匹配）。整体停用请求，避免每缺口一次异常。
            onDiagnostic("Audio: retr req failed type=$streamType reason=${error.message} — retr req disabled")
            requestsDisabled = true
            false
        }
    }

    /**
     * PT=86 重传应答：剥 8B 控制头后是一条完整音频 RTP 包（resentSn 在 +2、resentPt 在 +1，
     * 见 controlDetail 取证），线格式与数据口一致 → 复用同一解密路径，插入重排窗口。
     * 不在此处交付：所有 onRtp 都从数据线程按序发出（下游对乱序敏感）。
     */
    private fun handleRetransmitReply(buffer: ByteArray, length: Int) {
        if (length < CONTROL_HEADER_LEN + RTP_HEADER_LEN + TAIL_LEN) return
        val replies = retrReplies.incrementAndGet()
        if (replies == 1) {
            // 实测第一判据：iPhone 是否应答主动重传。只进 logcat 等于没落盘（#30 教训）。
            onDiagnostic(
                "Audio: retr reply first type=$streamType len=$length " +
                    "from=${controlPeerAddress?.hostAddress ?: "?"}",
            )
        }
        val wire = buffer.copyOfRange(CONTROL_HEADER_LEN, length)
        val rtp = try {
            decryptWire(wire)
        } catch (error: Exception) {
            val failures = retrDecryptFailures.incrementAndGet()
            if (failures == 1) {
                // PT=86 解密路径本轮新增且未实测，首个失败必须把异常带进文件日志
                onDiagnostic(
                    "audio stream type=$streamType first retransmit decrypt failure " +
                        "len=$length ${error.javaClass.simpleName}: ${error.message}",
                )
                android.util.Log.w(
                    TAG,
                    "audio stream type=$streamType retransmit decrypt failure len=$length",
                    error,
                )
            }
            return
        }
        val seq = readU16Be(wire, 2)
        val sample = readU32Be(wire, 4)
        synchronized(reorderLock) {
            if (expectedSeq == -1) return
            if (((seq - expectedSeq) and 0xffff) >= SEQ_HALF_RANGE) {
                retrLate.incrementAndGet()
                return
            }
            if (held.containsKey(seq)) {
                retrDuplicate.incrementAndGet()
                return
            }
            held[seq] = PendingFrame(rtp, sample)
            retrFilled.incrementAndGet()
        }
    }

    /** 数据口与 PT=86 重传体共用的线格式解密：12B 头 + 密文 + 16B tag + 8B nonce（补齐到 12B）。 */
    private fun decryptWire(wire: ByteArray): ByteArray {
        val aad = wire.copyOfRange(4, RTP_HEADER_LEN)
        val sealedEnd = wire.size - NONCE_LEN
        val sealed = wire.copyOfRange(RTP_HEADER_LEN, sealedEnd)
        val nonce = ByteArray(12).also { wire.copyOfRange(sealedEnd, wire.size).copyInto(it, 4) }
        val payload = AirPlayCrypto.chachaOpen(key, nonce, sealed, aad)
        return wire.copyOf(RTP_HEADER_LEN) + payload
    }

    private class PendingFrame(val rtp: ByteArray, val sample: Int)

    // API16 车机实测：绑 IPv6 通配 [::] 抛异常会让该流被拒，回退 IPv4 通配（本热点对端为 IPv4）。
    private fun bindAnyPort(): DatagramSocket {
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        return try {
            socket.bind(InetSocketAddress(InetAddress.getByName("::"), 0))
            socket
        } catch (error: Exception) {
            onDiagnostic("audio 端口 IPv6 通配绑定失败，回退 IPv4: ${error.message}")
            runCatching { socket.close() }
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0))
            }
        }
    }

    /**
     * M6i：控制口**优先绑 IPv4**（本环境车机链路是 IPv4，见 `family=IPv4`）。
     *
     * 起因：T43/T44 两份日志里 controlPort **一个包都没收到**（连每秒一次的 PT=84 time sync 都没有），
     * 而音频数据口收得好好的；两端本来都走 `bindAnyPort()`（先试 `[::]`）。
     * 先把控制口钉到 IPv4，用来切开"iPhone 不发"与"我们收不到"这两种可能；
     * 若之后开始出现 `pt=84/85/86`，就证明是绑定族问题。
     */
    private fun bindControlPort(): DatagramSocket {
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        return try {
            socket.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0))
            socket
        } catch (error: Exception) {
            onDiagnostic("audio 控制口 IPv4 绑定失败，回退 IPv6 通配: ${error.message}")
            runCatching { socket.close() }
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("::"), 0))
            }
        }
    }

    private fun familyOf(socket: DatagramSocket): String =
        if (socket.localAddress is java.net.Inet6Address) "IPv6" else "IPv4"

    private fun readU32Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 24) or
            ((source[offset + 1].toInt() and 0xff) shl 16) or
            ((source[offset + 2].toInt() and 0xff) shl 8) or
            (source[offset + 3].toInt() and 0xff)

    private fun readU16Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    private fun ByteArray.toHexString(): String =
        joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val DATAGRAM_BYTES = 4_096
        const val AUDIO_RECEIVE_BUFFER_BYTES = 512 * 1024
        const val RTP_HEADER_LEN = 12
        const val TAG_LEN = 16
        const val NONCE_LEN = 8
        const val TAIL_LEN = TAG_LEN + NONCE_LEN
        const val FIRST_PACKET_LOG_COUNT = 3
        const val PACKET_LOG_INTERVAL = 100

        // M6h 控制口取证（见 runControl）：PT 语义与日志节流参数。
        const val CONTROL_HEADER_LEN = 8
        const val CONTROL_PT_TIME_SYNC = 84
        const val CONTROL_PT_RETRANSMIT_REQUEST = 85
        const val CONTROL_PT_RETRANSMIT_REPLY = 86
        const val CONTROL_DETAIL_PACKETS = 3
        const val CONTROL_LOG_INTERVAL = 100
        const val CONTROL_SUMMARY_INTERVAL_NANOS = 10_000_000_000L

        // M6k7 主动重传（PT=85/86 + 按 SN 重排）。参数保守起步，实机按日志再调：
        const val RETRANSMIT_POLL_MS = 40                    // 数据口 SO_TIMEOUT：缺口超时检查节拍
        const val RETRANSMIT_HOLD_TIMEOUT_NANOS = 150_000_000L   // 缺口最长等待，超时放行交 P0
        const val RETRANSMIT_RETRY_NANOS = 100_000_000L      // 同一缺口的再次请求间隔
        const val RETRANSMIT_MIN_REQUEST_INTERVAL_NANOS = 15_000_000L // 不同缺口请求最小间隔
        const val RETRANSMIT_MAX_REQUESTS_PER_SECOND = 30    // 每秒请求预算
        const val REQUEST_MAX_LOST = 24                      // 单次请求最大补包数（大缺口只补前段）
        const val MAX_HOLD_PACKETS = 48                      // 重排窗口上限，超过即放弃最老缺口
        const val SEQ_HALF_RANGE = 0x8000                    // 16 位 SN 的前后半区界
        const val RETRANSMIT_LOG_INTERVAL = 25
    }
}

/** Maps the phone's negotiated audioFormat bits to a decode/render format. */
object AudioStreamCodec {
    fun fromFormatBits(bits: Long, payloadType: Int, audioType: String = "media"): AudioFormat {
        val isAacLc = (bits and (AAC_LC_44K_STEREO or AAC_LC_48K_STEREO)) != 0L
        val isOpus = (bits and OPUS_MONO) != 0L
        // M4n：**按单个 PCM 位匹配**，不再要求整掩码精确命中。
        // 起因：M4n 让 iPhone 在不带无线输入的槽位上改发 LPCM（导航出声实验）。若它回的是
        // 多位置合的掩码（如 0xc000 = 48k mono|stereo），旧写法 `PCM_FORMAT[bits]` 会落空，
        // 兜底到 `LPCM 44.1k 立体声` —— 采样率错 8%，听感是变调/沙哑而不是"没声音"，会白费一轮实测。
        // 取"位数最高"（= 采样率/声道最优）的那个命中位。
        val pcm = PCM_FORMAT.entries.sortedByDescending { it.key }
            .firstOrNull { (it.key and bits) != 0L }?.value
        return when {
            isOpus -> AudioFormat(AudioCodecKind.OPUS, 48_000, 1, payloadType, audioType)
            isAacLc -> AudioFormat(
                AudioCodecKind.AAC_LC,
                if ((bits and AAC_LC_48K_STEREO) != 0L) 48_000 else 44_100,
                2,
                payloadType,
                audioType,
            )
            pcm != null -> AudioFormat(AudioCodecKind.LPCM, pcm.first, pcm.second, payloadType, audioType)
            else -> AudioFormat(AudioCodecKind.LPCM, 44_100, 2, payloadType, audioType)
        }
    }

    private const val AAC_LC_44K_STEREO = 0x400000L
    private const val AAC_LC_48K_STEREO = 0x800000L
    private const val OPUS_MONO = 0x10000000L or 0x20000000L or 0x40000000L

    private val PCM_FORMAT = mapOf(
        0x4L to (8_000 to 1),
        0x8L to (8_000 to 2),
        0x10L to (16_000 to 1),
        0x20L to (16_000 to 2),
        0x40L to (24_000 to 1),
        0x80L to (24_000 to 2),
        0x100L to (32_000 to 1),
        0x200L to (32_000 to 2),
        0x400L to (44_100 to 1),
        0x800L to (44_100 to 2),
        0x4000L to (48_000 to 1),
        0x8000L to (48_000 to 2),
    )
}
