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
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var opusEncoder: OpusFrameEncoder? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var thread: Thread? = null

    /** Reports a start failure into the diagnostic log so field reports show why calls went silent. */
    private fun fail(message: String): Boolean {
        Log.w(TAG, message)
        diagnostics?.invoke("Microphone uplink failed: $message")
        running.set(false)
        return false
    }

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true

        val channelMask = if (config.channels >= 2) {
            AndroidAudioFormat.CHANNEL_IN_STEREO
        } else {
            AndroidAudioFormat.CHANNEL_IN_MONO
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            config.sampleRate,
            channelMask,
            AndroidAudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            return fail("microphone unavailable rate=${config.sampleRate} channels=${config.channels}")
        }

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
        val bufferSize = maxOf(minBuffer * 2, config.frameBytes * 4)
        // AudioRecord.Builder()/AudioFormat.Builder() are API 23+; on Android 5.x they throw
        // NoSuchMethodError (an Error the catch below cannot intercept) and crash the app.
        // The plain constructor works from API 21: bad parameters only throw from API 23,
        // earlier builds land in the state check below as STATE_UNINITIALIZED.
        //
        // API16 适配（T36 实测）：`AudioSource.VOICE_COMMUNICATION`(7) 在 Android 4.1 上
        // AudioRecord 直接 `STATE_UNINITIALIZED`（该源要求音频模式为 MODE_IN_COMMUNICATION，
        // 4.x 的 HAL 在 MODE_NORMAL 下不给）—— 现象就是编码器已就绪却仍报
        // `recorder failed to initialize`、收音全无。改为**按优先级逐个源尝试**，
        // 取第一个能初始化的，并把胜出的源号写进文件日志。
        val attemptedSources = sourcesFor(config.audioType)
        var selection: Pair<AudioRecord, Int>? = null
        for (source in attemptedSources) {
            val candidate = try {
                AudioRecord(
                    source,
                    config.sampleRate,
                    channelMask,
                    AndroidAudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                )
            } catch (error: Exception) {
                Log.w(TAG, "microphone recorder creation failed source=$source", error)
                null
            }
            if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                selection = candidate to source
                break
            }
            candidate?.release()
        }
        val nextRecorder = selection?.first
        if (nextRecorder == null) {
            nextEncoder?.close()
            return fail("recorder failed to initialize (tried sources=$attemptedSources)")
        }
        val chosenSource = selection?.second ?: -1

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
                    "port=${config.port} $effects",
            )
            // 成功路径也落文件日志（此前只写 logcat，实测报告里"查无此话"，见 #30）。
            diagnostics?.invoke(
                "Microphone uplink started type=${config.audioType} codec=${config.codec} " +
                    "encoder=$encoderBackend source=$chosenSource rate=${config.sampleRate} " +
                    "channels=${config.channels} frameMs=${config.frameMillis} $effects",
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

    // API16 适配：绑 IPv6 通配 [::] 在本机 ROM 上会抛 “Can't bind to a link-local address
    // without a scope id”，回退 IPv4 通配（与 AudioStream/ScreenStream 的处理一致）。
    // 少了这个回退，libopus 补上之后麦克风仍会停在 "socket creation failed"。
    private fun bindAnyPort(): DatagramSocket {
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
        val readBuffer = ByteArray(maxOf(frame.size, MIN_READ_BYTES))
        val counters = MicrophoneCounters()
        var filled = 0
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
                var offset = 0
                while (offset < count && running.get()) {
                    val copied = minOf(frame.size - filled, count - offset)
                    readBuffer.copyInto(frame, filled, offset, offset + copied)
                    filled += copied
                    offset += copied
                    if (filled == frame.size) {
                        sendFrame(socket, counters, frame)
                        filled = 0
                    }
                }
            }
        } catch (error: Exception) {
            if (running.get()) Log.e(TAG, "microphone capture failed", error)
        } finally {
            running.set(false)
            release()
        }
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
            if (firstPacketLogged.compareAndSet(false, true)) {
                Log.i(
                    TAG,
                    "microphone first packet bytes=${packet.size} body=${body.size} " +
                        "head=${packet.copyOf(minOf(packet.size, 16)).toHexString()} " +
                        "port=${config.port}",
                )
            }
        } catch (error: Exception) {
            if (running.get()) throw error
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
    }
}
