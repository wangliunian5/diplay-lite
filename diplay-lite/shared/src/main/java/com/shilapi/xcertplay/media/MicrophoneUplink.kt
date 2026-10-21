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
    // 1.1.5/e5t1(vc85)：麦克风静音位检查/自动恢复，由持 Context 的上层注入（见
    // LiteSessionService）。EU5 QG DSP 仲裁链可能在 Android 层留下 mute 位——App 层
    // 能做的修复就是检测到即解除。空实现跳过，对其他平台零行为变化。
    private val muteRestore: (() -> String)? = null,
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
    // 1.1.5/e5t1(vc85)：EU5 数字静默自愈（核心修复）。判据=windowPeak==0（真全零，
    // DSP/静音路径特征；近静音 <300 是环境噪音，绝不能触发，否则正常通话轮流拆重建）。
    // 连续 5s 全零 → 换下一个未试过的音频源重建录音（源序沿 6→1→7 逐个排除）；
    // 全部源轮完仍全零 → 停止轮换，落 QG DSP 层嫌疑取证（EU5 实锤 Android 全栈无错
    // 可报的静音只能出在该层，App 层无罪）。
    private val triedSources = LinkedHashSet<Int>()
    @Volatile private var healExhausted = false
    // vc156（#866 容错）：编码失败限报计数。只在采集线程读写，无需同步。
    private var encodeFailureReports = 0

    /** Reports a start failure into the diagnostic log so field reports show why calls went silent. */
    private fun fail(message: String): Boolean {
        Log.w(TAG, message)
        diagnostics?.invoke("Microphone uplink failed: $message")
        running.set(false)
        return false
    }

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true

        // 上行编码器选择：统一走 OpusEncoderSupport 选择层（移植自上游 0.2.21
        // MicrophoneOpusEncoders，vc156）。本分支（API16）没有 MediaCodec OPUS 编码器，
        // 顺序为 **libopus 优先、MediaCodec 兜底**，创建/初始化失败逐级容错回落 ——
        // 否则带无线输入的流协商了 OPUS 上行却编不出来，通话/微信语音对方就听不到。
        var encoderBackend = "pcm"
        val nextEncoder = if (config.codec == AudioCodecKind.OPUS) {
            OpusEncoderSupport.createEncoder(config.bitrate ?: 48_000, diagnostics).also { encoder ->
                encoderBackend = when (encoder) {
                    is NativeOpusEncoder -> "libopus"
                    is OpusEncoder -> "MediaCodec"
                    else -> "none"
                }
            }
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
        // 1.1.5/e5t1(vc85)：静音位检查/自动恢复放在录音源打开之前——mute 位可能正是
        // 源打不开/开了全零的原因，先解除再开源，避免「解除前开的源被 DSP 静音」窗口。
        muteRestore?.let { restore ->
            try {
                diagnostics?.invoke("Microphone mute-bit check: ${restore()}")
            } catch (error: Throwable) {
                diagnostics?.invoke("Microphone mute-bit check failed: ${error.javaClass.simpleName}")
            }
        }
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
        // e5t1：记录初始胜出源，静默自愈轮换时排除已试过的源。
        synchronized(triedSources) { triedSources.add(opened.source) }
        healExhausted = false
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
    // （要求 MODE_IN_COMMUNICATION），所以逐级退；语音识别走 6 → 1。
    // 代价：退到 MIC 时没有平台回声消除，远端可能听到回声（后续可跟 #33 的 AEC/NS）。
    // 1.1.4（EU5 123838 实锤）：telephony 源序 7→6→1 改 6→1→7 —— 同一台车 Siri（源 6）
    // 能开而电话全灭，只有「源 7 先行」一个差异变量；源 7 初始化失败会短暂占住/弄脏
    // PATEO HAL 的输入句柄，300ms 内殃及矩阵里后续的源 6/1 全败。7 移末位，即使失败
    // 也不殃及前面的尝试；正常车机源 6 本就能开，第一候选即命中、零行为变化。
    private fun sourcesFor(audioType: String): List<Int> = when (audioType) {
        "telephony" -> listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
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
    private fun openRecorder(excludeSources: Set<Int> = emptySet()): OpenedRecorder? {
        val sources = sourcesFor(config.audioType).filter { it !in excludeSources }
        val requestedMask = if (config.channels >= 2) {
            AndroidAudioFormat.CHANNEL_IN_STEREO
        } else {
            AndroidAudioFormat.CHANNEL_IN_MONO
        }
        val rates = intArrayOf(config.sampleRate, 48_000, 16_000, 8_000).distinct().toIntArray()
        val masks = intArrayOf(requestedMask, AndroidAudioFormat.CHANNEL_IN_MONO, AndroidAudioFormat.CHANNEL_IN_STEREO).distinct().toIntArray()
        val attempts = StringBuilder()
        val winner = tryOpen(sources, rates, masks, attempts)
        if (winner != null) {
            if (attempts.isNotEmpty()) {
                diagnostics?.invoke(
                    "microphone fallback matrix winner source=${winner.source} rate=${winner.rate} " +
                        "channels=${winner.channels} (negotiated rate=${config.sampleRate} " +
                        "channels=${config.channels}) attempts:$attempts",
                )
            }
        } else {
            // 1.1.4 补盲：全败时也落盘逐候选明细（此前只在胜出时打 attempts，
            // EU5 123838 报告里因此查不到每个源失败的具体原因，A/B 定性失据）。
            diagnostics?.invoke(
                "microphone fallback matrix exhausted (negotiated rate=${config.sampleRate} " +
                    "channels=${config.channels}) attempts:$attempts",
            )
        }
        return winner
    }

    private fun tryOpen(
        sources: List<Int>,
        rates: IntArray,
        masks: IntArray,
        attempts: StringBuilder,
    ): OpenedRecorder? {
        for (source in sources) {
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

    private fun capture(initialRecorder: AudioRecord, socket: DatagramSocket) {
        var currentRecorder = initialRecorder
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
        // e5t1：改为动态判定——静默自愈轮换可能改变 native 参数，不能用启动时的快照。
        // e5t1：连续全零秒计数（数字静默自愈触发器，见字段注释）。
        var zeroPeakSeconds = 0
        // vc87（fe6t5）：read 零字节连击计数（HAL 出数失败检测，见常量注释）。
        var zeroReadStreak = 0
        try {
            while (running.get()) {
                // API16 适配：带 mode 的 AudioRecord.read 是 API 23+；低版本用阻塞语义的 int 重载。
                val count = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    currentRecorder.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
                } else {
                    currentRecorder.read(readBuffer, 0, readBuffer.size)
                }
                if (count < 0) {
                    if (running.get()) Log.e(TAG, "microphone read failed code=$count")
                    return
                }
                if (count == 0) {
                    // vc87（fe6t5）：read 恒返 0 = HAL 出数失败（非 error 非 EOF），循环空转
                    // 连整帧都凑不满。连续超阈值落证并走换源自愈（同 peak==0 路径）。
                    if (!healExhausted && ++zeroReadStreak >= ZERO_READ_HEAL_THRESHOLD) {
                        zeroReadStreak = 0
                        diagnostics?.invoke(
                            "Microphone zero-read streak: AudioRecord.read returns 0 x" +
                                "$ZERO_READ_HEAL_THRESHOLD; rotating source",
                        )
                        val rotated = rotateSilentRecorder(currentRecorder)
                        if (rotated != null) {
                            currentRecorder = rotated
                        }
                    }
                    continue
                }
                zeroReadStreak = 0
                val data: ByteArray
                val size: Int
                if (nativeRate == config.sampleRate && nativeChannels == config.channels) {
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
                            // e5t1：数字静默自愈——peak==0（真全零）连续 SILENT_HEAL_SECONDS 秒
                            // 才触发；<300 的近静音是环境噪音，绝不触发（避免正常静场轮流拆建）。
                            zeroPeakSeconds = if (windowFrames > 0 && windowPeak == 0) {
                                zeroPeakSeconds + 1
                            } else {
                                0
                            }
                            if (zeroPeakSeconds >= SILENT_HEAL_SECONDS && !healExhausted) {
                                zeroPeakSeconds = 0
                                val rotated = rotateSilentRecorder(currentRecorder)
                                if (rotated != null) {
                                    currentRecorder = rotated
                                }
                                // rotated==null 时 rotateSilentRecorder 内部已置 healExhausted
                                // 并落 QG DSP 嫌疑取证，此后静默只观察不再轮换。
                            }
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
     * e5t1：数字静默源轮换。当前源连续全零 5s（采集路径在 AudioFlinger 之下被静音）时，
     * 换下一个未试过的音频源重建 AudioRecord 并热切换（采集线程内完成，RTP 发送不断流）。
     * 无未试源可换时置 healExhausted 并落 QG DSP 嫌疑取证——EU5 实锤该层静音在 Android
     * 全栈无错可报，App 层只能探测到并留证。
     */
    @Synchronized
    private fun rotateSilentRecorder(old: AudioRecord): AudioRecord? {
        if (!running.get()) return null
        val exclude = synchronized(triedSources) { triedSources.toSet() }
        val opened = openRecorder(excludeSources = exclude)
        if (opened == null) {
            healExhausted = true
            diagnostics?.invoke(
                "Microphone silent-source heal exhausted (all sources peak==0): capture path " +
                    "digitally muted below AudioFlinger — suspect QG DSP arbitration " +
                    "(com.qinggan.audiopolicy setMicMute). qg probe: ${probeQgDsp()}",
            )
            return null
        }
        synchronized(triedSources) { triedSources.add(opened.source) }
        val previousEffects = "aec:${if (echoCanceler != null) "on" else "off"} " +
            "ns:${if (noiseSuppressor != null) "on" else "off"}"
        try {
            old.stop()
        } catch (_: Exception) {
        }
        try {
            old.release()
        } catch (_: Exception) {
        }
        recorder = opened.recorder
        nativeRate = opened.rate
        nativeChannels = opened.channels
        resamplePos = 0.0
        resampleTail = ShortArray(0)
        val effects = attachAudioEffects(opened.recorder)
        try {
            opened.recorder.startRecording()
        } catch (error: Exception) {
            Log.e(TAG, "microphone heal restart failed", error)
            diagnostics?.invoke(
                "Microphone silent-source heal: rotated source=${opened.source} failed to start: " +
                    "${error.message} (previous effects $previousEffects)",
            )
            return null
        }
        diagnostics?.invoke(
            "Microphone silent-source heal: rotated to source=${opened.source} " +
                "rate=${opened.rate} channels=${opened.channels} $effects",
        )
        return opened.recorder
    }

    /** e5t1：QG 情感科技 DSP 仲裁层存在性探测（无需 root，pm 查询包名即可）。 */
    private fun probeQgDsp(): String = try {
        val process = Runtime.getRuntime().exec(arrayOf("pm", "list", "packages", "qinggan"))
        val output = process.inputStream.bufferedReader().use { reader -> reader.readText().trim() }
        process.destroy()
        if (output.isEmpty()) {
            "com.qinggan.* absent"
        } else {
            output.lineSequence().take(5).joinToString(separator = ",", prefix = "found=[", postfix = "]")
        }
    } catch (error: Throwable) {
        "probe failed: ${error.javaClass.simpleName}: ${error.message}"
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
            // #866 容错（移植自上游 0.2.21 SoftwareOpusEncoder 的 failed() 语义，vc156）：
            // 编码失败（含 JNI/MediaCodec 抛出的 Error）只损当前一帧 20ms，绝不让异常
            // 炸穿采集循环把整个上行会话拆掉 —— 一帧丢音远好过通话整段静音。
            // 与上游一致限报 3 次，防持续故障刷爆诊断日志。
            try {
                opusEncoder?.encode(frame).orEmpty()
            } catch (error: Throwable) {
                if (encodeFailureReports < MAX_ENCODE_FAILURE_REPORTS) {
                    encodeFailureReports++
                    Log.w(TAG, "microphone opus encode failed (frame dropped, uplink kept)", error)
                    diagnostics?.invoke(
                        "Microphone opus encode failed: ${error.javaClass.simpleName}: " +
                            "${error.message}（本帧丢弃，会话保持）",
                    )
                }
                emptyList()
            }
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
        // e5t1：连续全零 5s 触发换源。秒级日志窗口本身就是约 1s 粒度，5s 兼顾响应速度
        // 与误触发余量；换源成本=一次 AudioRecord 重建（~百 ms），误触发代价可接受。
        const val SILENT_HEAL_SECONDS = 5
        // vc87（fe6t5）：连续零读阈值——AudioRecord.read 恒返 0 的 HAL 出数失败形态
        // （fe6t3 日志 uplink started 后无任何 level window/tx 心跳即此形态），凑不满
        // 整帧连 e5t1 的 peak==0 静默自愈都触发不了。约 1s 内 50 次零读即按采集失效轮换源。
        const val ZERO_READ_HEAL_THRESHOLD = 50
        // vc156（#866 容错）：编码失败最多落 3 条诊断（对齐上游 MAX_REPORTED_FAILURES）。
        const val MAX_ENCODE_FAILURE_REPORTS = 3
        val EMPTY_BYTES = ByteArray(0)
    }
}
