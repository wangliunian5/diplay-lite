package com.shilapi.xcertplay.media

import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import android.util.Log
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.MicrophoneCounters
import com.shilapi.xcertplay.airplay.MicrophonePacketizer
import com.shilapi.xcertplay.airplay.toHexString
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Captures one PCM microphone stream and sends it back to the phone as sealed CarPlay RTP.
 *
 * The recorder runs only while the matching audio stream is active, so callers start this after
 * the first downlink audio packet and close it on stream teardown.
 */
internal class MicrophoneUplink(
    private val config: MicrophoneConfig,
    private val diagnostics: ((String) -> Unit)? = null,
) : Closeable {
    private val running = AtomicBoolean(false)
    private val firstPacketLogged = AtomicBoolean(false)
    /** 1.0.7：TX 计数落盘（每秒一次），判定 Siri 卡死时 mic 数据有没有真正出门。 */
    private val txPackets = java.util.concurrent.atomic.AtomicLong(0)
    private var lastTxLogMs = 0L
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var opusEncoder: OpusFrameEncoder? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var thread: Thread? = null
    // 1.1.1（EU5 C40DB 104759 修复）：录音初始化兜底矩阵的胜出参数与转换状态。
    // EU5 实测：HAL audio_policy.conf 声明 24k/mono 可用，但 phone 协商的 24k+源6/1
    // 全部 STATE_UNINITIALIZED（PATEO HAL 疑似拒开或输入被自家语音服务占用）。
    // 策略：源×采样率×声道全矩阵试开，胜出参数≠协商参数时走线性重采样/声道变换，
    // 编码器与 RTP 打包仍按协商参数走，对正常车机零行为变化。
    private var nativeRate = 0
    private var nativeChannels = 0
    private var resamplePos = 0.0
    private var resampleTail = ShortArray(0)

    /** Reports a start failure into the diagnostic log so field reports show why calls went silent. */
    private fun fail(message: String): Boolean {
        Log.w(TAG, message)
        diagnostics?.invoke("Microphone uplink failed: $message")
        running.set(false)
        return false
    }

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true

        // 上行编码器选择：**MediaCodec 优先、libopus 兜底**（API 29+ 行为零变化）。
        // 本分支（API16）没有 MediaCodec OPUS 编码器，改由内置 libopus 软件编码 ——
        // 否则带无线输入的流协商了 OPUS 上行却编不出来，通话/微信语音对方就听不到。
        var encoderBackend = "pcm"
        val nextEncoder = if (config.codec == AudioCodecKind.OPUS) {
            val mediaCodec = OpusEncoder(config.bitrate ?: 48_000).takeIf { it.available }
            val native = if (mediaCodec != null) {
                null
            } else {
                NativeOpusEncoder.create(config.bitrate ?: 48_000)
            }
            encoderBackend = when {
                mediaCodec != null -> "MediaCodec"
                native != null -> "libopus"
                else -> "none"
            }
            mediaCodec ?: native
        } else {
            null
        }
        if (config.codec == AudioCodecKind.OPUS && nextEncoder == null) {
            return fail(
                "Opus encoder is unavailable on this device; the phone negotiated " +
                    "an OPUS microphone uplink this build cannot encode " +
                    "(MediaCodec absent, libopus probe failed)",
            )
        }
        // AudioRecord.Builder()/AudioFormat.Builder() are API 23+; on Android 5.x they throw
        // NoSuchMethodError (an Error the catch below cannot intercept) and crash the app.
        // The plain constructor works from API 21: bad parameters only throw from API 23,
        // earlier builds land in the state check below as STATE_UNINITIALIZED.
        // API16 适配（T36 实测）：`AudioSource.VOICE_COMMUNICATION`(7) 在 Android 4.1 上
        // AudioRecord 直接 `STATE_UNINITIALIZED`（该源要求音频模式为 MODE_IN_COMMUNICATION，
        // 4.x 的 HAL 在 MODE_NORMAL 下不给）—— 现象就是编码器已就绪却仍报
        // `recorder failed to initialize`、收音全无。
        //
        // 1.1.1（EU5 实测）：24k+源6/1 也全灭而 HAL 声明支持 —— 升级为
        // **源×采样率×声道兜底矩阵**，每次尝试都落盘诊断；胜出参数≠协商参数时由
        // capture() 做线性重采样/声道变换，编码器与 RTP 打包不变。
        val opened = openRecorder()
        val nextRecorder = opened?.recorder
        if (nextRecorder == null) {
            nextEncoder?.close()
            return fail("recorder failed to initialize (all source×rate×mask candidates failed)")
        }
        nativeRate = opened.rate
        nativeChannels = opened.channels
        resamplePos = 0.0
        resampleTail = ShortArray(0)
        val chosenSource = opened.source

        val nextSocket = try {
            bindAnyPort()
        } catch (error: Exception) {
            Log.e(TAG, "microphone socket creation failed", error)
            nextRecorder.release()
            nextEncoder?.close()
            return fail("socket creation failed: ${error.message}")
        }

        recorder = nextRecorder
        socket = nextSocket
        opusEncoder = nextEncoder
        return try {
            val effects = attachAudioEffects(nextRecorder)
            nextRecorder.startRecording()
            thread = Thread({
                // 麦克风采集 + libopus 编码：上行是通话体验的一等公民，同样抬到 URGENT_AUDIO。
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                capture(nextRecorder, nextSocket)
            }, "carplay-mic").apply {
                isDaemon = true
                start()
            }
            Log.i(
                TAG,
                "microphone uplink started type=${config.audioType} codec=${config.codec} " +
                    "encoder=$encoderBackend source=$chosenSource rate=${config.sampleRate} " +
                    "channels=${config.channels} frameMs=${config.frameMillis} " +
                    "port=${config.port} native=(${nativeRate}Hz/${nativeChannels}ch) $effects",
            )
            // 成功路径也落文件日志（此前只写 logcat，实测报告里"查无此话"，见 #30）。
            diagnostics?.invoke(
                "Microphone uplink started type=${config.audioType} codec=${config.codec} " +
                    "encoder=$encoderBackend source=$chosenSource rate=${config.sampleRate} " +
                    "channels=${config.channels} frameMs=${config.frameMillis} " +
                    "native=(${nativeRate}Hz/${nativeChannels}ch) $effects",
            )
            true
        } catch (error: Exception) {
            Log.e(TAG, "microphone recording failed", error)
            fail("recording failed to start: ${error.message}")
            release()
            false
        }
    }

    // API16 适配：按优先级给出可尝试的录音源。4.1 上 VOICE_COMMUNICATION 常常初始化不了
    // （要求 MODE_IN_COMMUNICATION），所以让 7 → 6 → 1 逐级退；语音识别走 6 → 1。
    // 代价：退到 MIC 时没有平台回声消除，远端可能听到回声（后续可跟 #33 的 AEC/NS）。
    private fun sourcesFor(audioType: String): List<Int> = when (audioType) {
        "telephony" -> listOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
        "speechrecognition" -> listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
        else -> listOf(MediaRecorder.AudioSource.MIC)
    }

    private class OpenedRecorder(
        val recorder: AudioRecord,
        val source: Int,
        val rate: Int,
        val channels: Int,
    )

    /**
     * 1.1.1（EU5 C40DB 104759 修复）：录音初始化兜底矩阵。
     *
     * EU5 实测：PATEO HAL 的 audio_policy.conf 声明 primary 输入支持
     * 8000..48000（含 24k）+ MONO/STEREO，但 phone 协商的 24k + 源6/1 全部
     * STATE_UNINITIALIZED —— HAL 实际拒开参数或输入被自家语音服务占用。
     * 策略：源（协商优先级）×采样率（协商值→48k→16k→8k）×声道（协商→MONO→STEREO）
     * 全矩阵逐个试开，每次尝试（含失败原因）写进诊断。胜出参数≠协商参数时
     * capture() 内做线性重采样与声道变换，编码器/RTP 打包完全不受影响，
     * 对「协商参数本来就能开」的正常车机第一个候选即命中、零行为变化。
     */
    private fun openRecorder(): OpenedRecorder? {
        val requestedMask = if (config.channels >= 2) {
            AndroidAudioFormat.CHANNEL_IN_STEREO
        } else {
            AndroidAudioFormat.CHANNEL_IN_MONO
        }
        val rates = intArrayOf(config.sampleRate, 48_000, 16_000, 8_000).distinct().toIntArray()
        val masks = intArrayOf(requestedMask, AndroidAudioFormat.CHANNEL_IN_MONO, AndroidAudioFormat.CHANNEL_IN_STEREO).distinct().toIntArray()
        val attempts = StringBuilder()
        val winner = tryOpen(rates, masks, attempts)
        if (winner != null && attempts.isNotEmpty()) {
            diagnostics?.invoke(
                "microphone fallback matrix winner source=${winner.source} rate=${winner.rate} " +
                    "channels=${winner.channels} (negotiated rate=${config.sampleRate} " +
                    "channels=${config.channels}) attempts:$attempts",
            )
        }
        return winner
    }

    private fun tryOpen(rates: IntArray, masks: IntArray, attempts: StringBuilder): OpenedRecorder? {
        for (source in sourcesFor(config.audioType)) {
            for (rate in rates) {
                for (mask in masks) {
                    val minBuffer = AudioRecord.getMinBufferSize(rate, mask, AndroidAudioFormat.ENCODING_PCM_16BIT)
                    if (minBuffer <= 0) {
                        attempts.append(" s$source/r$rate/minbuf<=0")
                        continue
                    }
                    val bufferSize = maxOf(minBuffer * 2, config.frameBytes * 4)
                    val candidate = try {
                        AudioRecord(source, rate, mask, AndroidAudioFormat.ENCODING_PCM_16BIT, bufferSize)
                    } catch (error: Throwable) {
                        attempts.append(" s$source/r$rate/ex=${error.javaClass.simpleName}")
                        continue
                    }
                    if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                        val channels = if (mask == AndroidAudioFormat.CHANNEL_IN_STEREO) 2 else 1
                        return OpenedRecorder(candidate, source, rate, channels)
                    }
                    attempts.append(" s$source/r$rate/state${candidate.state}")
                    runCatching { candidate.release() }
                }
            }
        }
        return null
    }

    /**
     * M6h 回声消除：4.1 上 `VOICE_COMMUNICATION`(7) 起不来，实际跑的是 `VOICE_RECOGNITION`(6)
     * —— 该源**不带平台 AEC**，所以对方能听到回声（T37 用户反馈）。这里把平台 AEC/NS
     * 显式挂到录音会话上；某台机器不支持就只记日志，**绝不阻断收音**。
     */
    private fun attachAudioEffects(recorder: AudioRecord): String {
        val session = recorder.audioSessionId
        if (session <= 0) return "audioEffect=session-unavailable"
        val aec = enableEffect("AEC", AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(session)
        }
        val ns = enableEffect("NS", NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(session)
        }
        echoCanceler = aec
        noiseSuppressor = ns
        return "audioEffect=aec:${if (aec != null) "on" else "off"} " +
            "ns:${if (ns != null) "on" else "off"} sessionId=$session"
    }

    private fun <T : AudioEffect> enableEffect(
        name: String,
        available: Boolean,
        create: () -> T?,
    ): T? {
        if (!available) {
            diagnostics?.invoke("Microphone $name unavailable on this device")
            return null
        }
        return try {
            create()?.also { effect ->
                effect.enabled = true
                if (!effect.enabled) {
                    diagnostics?.invoke("Microphone $name created but refused to enable")
                }
            }
        } catch (error: Throwable) {
            Log.w(TAG, "microphone $name attach failed", error)
            null
        }
    }

    // 1.0.12 验证改动：优先绑定会话源地址（与 IapTunnel 同款），避免多网卡车机上 mic 包
    // 从错误接口发出；绑定失败再逐级回落 IPv6 通配/IPv4 通配。
    // API16 适配：绑 IPv6 通配 [::] 在本机 ROM 上会抛 “Can't bind to a link-local address
    // without a scope id”，回退 IPv4 通配（与 AudioStream/ScreenStream 的处理一致）。
    // 少了这个回退，libopus 补上之后麦克风仍会停在 "socket creation failed"。
    private fun bindAnyPort(): DatagramSocket {
        config.bindAddress?.let { bind ->
            try {
                val socket = DatagramSocket(null)
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(bind, 0))
                diagnostics?.invoke("microphone socket bound to ${bind.hostAddress}")
                return socket
            } catch (error: Exception) {
                diagnostics?.invoke(
                    "microphone 绑定会话地址 ${bind.hostAddress} 失败，回落通配: ${error.message}",
                )
            }
        }
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        return try {
            socket.bind(InetSocketAddress(InetAddress.getByName("::"), 0))
            socket
        } catch (error: Exception) {
            diagnostics?.invoke("microphone 端口 IPv6 通配绑定失败，回退 IPv4: ${error.message}")
            runCatching { socket.close() }
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0))
            }
        }
    }

    private fun capture(recorder: AudioRecord, socket: DatagramSocket) {
        val frame = ByteArray(config.frameBytes)
        // 1.1.1：胜出参数≠协商参数时，读入缓冲按 native 速率/声道放大，读多读少都能处理。
        val conversionFactor = maxOf(
            1,
            (nativeRate.toLong() * nativeChannels / (config.sampleRate.toLong() * config.channels)).toInt(),
        )
        val readBuffer = ByteArray(maxOf(frame.size, MIN_READ_BYTES) * conversionFactor)
        // 1.0.19 回归上游：RTP 头 SSRC 位恒 0（上游 sealPacket 根本不写 8–11 字节）。
        // BE+SSRC=0（1.0.8）与 BE+SSRC=rand（1.0.12）在 3e 均失败 → SSRC 非主因，
        // 但基线必须与上游逐字节等价，排除变量后剩余失败才能归因到包外。
        val counters = MicrophoneCounters(ssrc = 0)
        var filled = 0
        // 1.0.8 Siri 排查：包数正常≠有声。每秒统计帧峰值并落盘，判定采集内容是否静音
        // （包格式/密钥/发送已与上游逐字节对齐，剩余嫌疑只有 PCM 内容）。
        var windowPeak = 0
        var windowFrames = 0
        var lastLevelLog = 0L
        // 1.1.1：胜出参数恰好等于协商参数时走零转换快路径（对正常车机零行为变化）。
        val passthrough = nativeRate == config.sampleRate && nativeChannels == config.channels
        try {
            while (running.get()) {
                // API16 适配：带 mode 的 AudioRecord.read 是 API 23+；低版本用阻塞语义的 int 重载。
                val count = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    recorder.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
                } else {
                    recorder.read(readBuffer, 0, readBuffer.size)
                }
                if (count < 0) {
                    if (running.get()) Log.e(TAG, "microphone read failed code=$count")
                    return
                }
                if (count == 0) {
                    continue
                }
                val data: ByteArray
                val size: Int
                if (passthrough) {
                    data = readBuffer
                    size = count
                } else {
                    data = convertChunk(readBuffer, count)
                    size = data.size
                    if (size == 0) continue
                }
                var offset = 0
                while (offset < size && running.get()) {
                    val copied = minOf(frame.size - filled, size - offset)
                    data.copyInto(frame, filled, offset, offset + copied)
                    filled += copied
                    offset += copied
                    if (filled == frame.size) {
                        windowPeak = maxOf(windowPeak, framePeak(frame))
                        windowFrames++
                        sendFrame(socket, counters, frame)
                        filled = 0
                        val now = System.currentTimeMillis()
                        if (now - lastLevelLog >= 1000) {
                            lastLevelLog = now
                            // 峰值满量程=32767；持续 <300 视为近静音（采集源问题）。
                            diagnostics?.invoke(
                                "Microphone level window frames=$windowFrames peak=$windowPeak",
                            )
                            windowPeak = 0
                            windowFrames = 0
                        }
                    }
                }
            }
        } catch (error: Exception) {
            if (running.get()) {
                Log.e(TAG, "microphone capture failed", error)
                // 1.0.7：采集线程死亡必须落盘，否则字段日志看到"uplink started"后无下文。
                diagnostics?.invoke(
                    "Microphone capture failed: ${error.javaClass.simpleName}: ${error.message}",
                )
            }
        } finally {
            running.set(false)
            release()
        }
    }

    /**
     * 1.1.1：native 参数（源 rate/channels）→ 协商参数（config）的 PCM 变换。
     * 管线：native 声道 → 单声道（双声道取均值）→ 线性重采样到协商采样率 →
     * 复制到协商声道数。仅语音上行使用，线性插值质量足够且 CPU 开销可忽略。
     */
    private fun convertChunk(src: ByteArray, count: Int): ByteArray {
        val frames = count / (2 * nativeChannels)
        if (frames <= 0) return EMPTY_BYTES
        val mono = ShortArray(frames)
        var idx = 0
        for (f in 0 until frames) {
            val l = leShort(src, idx)
            mono[f] = if (nativeChannels >= 2) {
                ((l + leShort(src, idx + 2)) / 2).toShort()
            } else {
                l
            }
            idx += 2 * nativeChannels
        }
        val resampled = if (nativeRate == config.sampleRate) mono else resampleMono(mono)
        val out = ByteArray(resampled.size * config.channels * 2)
        var o = 0
        for (s in resampled) {
            repeat(config.channels) {
                putLeShort(out, o, s)
                o += 2
            }
        }
        return out
    }

    /** 跨读增量的线性重采样：nativeRate → config.sampleRate，插值断点由 [resampleTail] 承接。 */
    private fun resampleMono(src: ShortArray): ShortArray {
        val buffer = if (resampleTail.isEmpty()) src else resampleTail + src
        val step = nativeRate.toDouble() / config.sampleRate
        val out = ShortArray(((buffer.size - 1) / step).toInt() + 1)
        var oi = 0
        var p = resamplePos
        while (p + 1 < buffer.size && oi < out.size) {
            val i0 = p.toInt()
            val frac = p - i0
            val v = buffer[i0] * (1.0 - frac) + buffer[i0 + 1] * frac
            out[oi++] = v.roundToInt().toShort()
            p += step
        }
        val consumed = minOf(p.toInt(), buffer.size - 1)
        resampleTail = buffer.copyOfRange(consumed, buffer.size)
        resamplePos = p - consumed
        return out.copyOf(oi)
    }

    private fun leShort(b: ByteArray, off: Int): Short =
        ((b[off].toInt() and 0xff) or (b[off + 1].toInt() shl 8)).toShort()

    private fun putLeShort(out: ByteArray, off: Int, v: Short) {
        out[off] = (v.toInt() and 0xff).toByte()
        out[off + 1] = ((v.toInt() shr 8) and 0xff).toByte()
    }

    /** Peak absolute sample of one little-endian PCM16 frame (full scale = 32767). */
    private fun framePeak(frame: ByteArray): Int {
        var peak = 0
        var index = 0
        while (index + 1 < frame.size) {
            val sample =
                ((frame[index].toInt() and 0xff) or (frame[index + 1].toInt() shl 8)).toShort()
                    .toInt()
            val magnitude = if (sample < 0) -sample else sample
            if (magnitude > peak) peak = magnitude
            index += 2
        }
        return peak
    }

    private fun sendFrame(socket: DatagramSocket, counters: MicrophoneCounters, frame: ByteArray) {
        val bodies = if (config.codec == AudioCodecKind.OPUS) {
            opusEncoder?.encode(frame).orEmpty()
        } else {
            listOf(MicrophonePacketizer.toWirePcm(frame))
        }
        bodies.forEach { body ->
            sendPacket(
                socket = socket,
                counters = counters,
                body = body,
                samples = config.samplesPerPacket,
            )
        }
    }

    private fun sendPacket(
        socket: DatagramSocket,
        counters: MicrophoneCounters,
        body: ByteArray,
        samples: Int,
    ) {
        val packet = MicrophonePacketizer.sealPacket(
            key = config.key,
            payloadType = config.payloadType,
            counters = counters,
            body = body,
            samples = samples,
        )
        try {
            socket.send(DatagramPacket(packet, packet.size, config.host, config.port))
            val total = txPackets.incrementAndGet()
            if (firstPacketLogged.compareAndSet(false, true)) {
                // 1.0.7：首包走文件日志（原来只进 logcat），字段报告能判定 mic 是否出门。
                diagnostics?.invoke(
                    "Microphone first packet bytes=${packet.size} body=${body.size} " +
                        "head=${packet.copyOf(minOf(packet.size, 16)).toHexString()} " +
                        "ssrc=${counters.ssrc} localPort=${socket.localPort} " +
                        "be=${MicrophonePacketizer.PCM_WIRE_BIG_ENDIAN} " +
                        "host=${config.host.hostAddress} port=${config.port}",
                )
                Log.i(
                    TAG,
                    "microphone first packet bytes=${packet.size} body=${body.size} " +
                        "head=${packet.copyOf(minOf(packet.size, 16)).toHexString()} " +
                        "port=${config.port}",
                )
                lastTxLogMs = System.currentTimeMillis()
            } else if (total % 50L == 0L) {
                // 每约 1 秒（50 包 × 20ms 帧）落一条 TX 心跳。
                val now = System.currentTimeMillis()
                if (now - lastTxLogMs >= 900) {
                    lastTxLogMs = now
                    diagnostics?.invoke("Microphone uplink tx packets=$total")
                }
            }
        } catch (error: Exception) {
            if (running.get()) {
                // 1.0.7：发送异常进文件日志（原来只进 logcat，上行线程死了字段无感知）。
                diagnostics?.invoke(
                    "Microphone send failed: ${error.javaClass.simpleName}: ${error.message}",
                )
                throw error
            }
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) {
            release()
            return
        }
        try {
            recorder?.stop()
        } catch (_: Exception) {
            // Best effort; release below is authoritative.
        }
        try {
            socket?.close()
        } catch (_: Exception) {
            // Best effort.
        }
        thread?.let { worker ->
            try {
                worker.join(CLOSE_JOIN_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (worker.isAlive) worker.interrupt()
        }
        release()
    }

    @Synchronized
    private fun release() {
        running.set(false)
        val currentRecorder = recorder
        recorder = null
        try {
            currentRecorder?.release()
        } catch (_: Exception) {
            // Best effort.
        }
        val currentSocket = socket
        socket = null
        try {
            currentSocket?.close()
        } catch (_: Exception) {
            // Best effort.
        }
        val currentEncoder = opusEncoder
        opusEncoder = null
        currentEncoder?.close()
        val currentEcho = echoCanceler
        echoCanceler = null
        try {
            currentEcho?.release()
        } catch (_: Exception) {
            // Best effort.
        }
        val currentNoise = noiseSuppressor
        noiseSuppressor = null
        try {
            currentNoise?.release()
        } catch (_: Exception) {
            // Best effort.
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val MIN_READ_BYTES = 2_048
        const val CLOSE_JOIN_MILLIS = 500L
        val EMPTY_BYTES = ByteArray(0)
    }
}
