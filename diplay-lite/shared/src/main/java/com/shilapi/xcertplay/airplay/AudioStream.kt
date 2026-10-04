package com.shilapi.xcertplay.airplay

import android.os.Process
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
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
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    stats.reading()
                    socket.receive(packet)
                } catch (_: Exception) {
                    if (closed.get()) return else continue
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

                val aad = wire.copyOfRange(4, RTP_HEADER_LEN)
                val sealedEnd = wire.size - NONCE_LEN
                val sealed = wire.copyOfRange(RTP_HEADER_LEN, sealedEnd)
                val shortNonce = wire.copyOfRange(sealedEnd, wire.size)
                val nonce = ByteArray(12).also { shortNonce.copyInto(it, 4) }
                val sample = readU32Be(wire, 4)

                val payload = try {
                    AirPlayCrypto.chachaOpen(key, nonce, sealed, aad)
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
                val rtp = wire.copyOf(RTP_HEADER_LEN) + payload
                val decryptedNumber = decryptedPackets.incrementAndGet()
                if (decryptedNumber <= FIRST_PACKET_LOG_COUNT) {
                    android.util.Log.i(
                        TAG,
                        "audio stream type=$streamType packet=$decryptedNumber sample=$sample " +
                            "wireBytes=${wire.size} payloadBytes=${payload.size} " +
                            "payloadHead=${payload.copyOf(minOf(payload.size, 16)).toHexString()}",
                    )
                } else if (decryptedNumber % PACKET_LOG_INTERVAL == 0) {
                    android.util.Log.i(
                        TAG,
                        "audio stream type=$streamType decrypted=$decryptedNumber " +
                            "authFailures=${authenticationFailures.get()}",
                    )
                }
                listener.onPacket(wire, rtp, sample, null)
                if (!started) {
                    started = true
                    listener.onStarted(sample)
                }
                listener.onRtp(rtp, sample)
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
     * 而 `96` 才是音频数据。我们此前只 receive 后丢弃 —— 于是丢包永远补不回来（T43 结论）。
     *
     * 这里先把流量如实打出来取证：谁在发、发什么、86 补的是哪些 SN。**零行为改动**——
     * 不改字节、不回包、不重排；等看清了再决定要不要实现「重排 + 补包」。
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
