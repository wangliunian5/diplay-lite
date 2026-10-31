package com.shilapi.xcertplay.media

import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import android.os.SystemClock
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
    // vc197：root 直采兜底。FE6 上 AudioRecord 这条路已被 13 档矩阵穷尽（能读的全零），
    // 而车机自带语音助手正常收音 ⇒ 静音是在 AudioFlinger 层加的。绕过它直读 ALSA 采集
    // 节点是唯一还能拿到真音的路，代价是必须 root。空实现时零行为变化。
    private val rootMicSource: RootMicSource? = null,
    /**
     * vc197：**实验性 FE6 语音兜底**总开关（默认关）。
     *
     * 打开后同时启用两件事：
     * 1. 采集/发送解耦（pacer 补静音帧保活）—— 解决「read 阻塞 ⇒ 一个包都不发 ⇒ iPhone
     *    2s 拆流」导致的「一叫 Siri 就断线」；
     * 2. root 直采兜底 —— 绕过 AudioFlinger 从 ALSA 采集节点拿真音，解决「读到全零」。
     *
     * 关闭时发送路径与历史版本逐字节一致（读到就发、不补帧），对非目标机型零行为变化。
     * 由上层按「机型是 FE6 + 用户勾选」决定是否置真。
     */
    private val keepAliveUplink: Boolean = false,
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
    // vc196：当前胜出源。声道升级要在**同源**上换声道重开，必须知道源号。
    private var nativeSource = 0
    // ── vc197：采集实例改由「唯一真相」字段持有 ──────────────────────────────
    // vc196 的声道升级写在采集循环里（每秒 level window 处），实测在 FE6 的**β 形态**
    // （第一次 read 返回 2048B 全零、第二次 read 永久阻塞）下永远执行不到——循环卡在
    // read() 里，连一秒一次的统计都进不去。于是升级必须能从**看门狗线程**发起：
    // 由 watchdog 检出「read 超时未返回」→ 换声道重开 → stop() 旧实例唤醒阻塞读 →
    // 采集线程下一轮自然用上新实例。为此 currentRecorder 从局部变量升格为本字段。
    @Volatile private var activeRecorder: AudioRecord? = null
    // 升级命中时探测窗口里已读到的音频，交给采集线程回灌（跨线程，故用字段）。
    @Volatile private var prefillSink: ByteArray? = null
    // 每会话只升级一次；「读到过非零」后不再救援（已经通了就别折腾）。
    @Volatile private var promotionAttempted = false
    @Volatile private var sawNonZeroAudio = false
    private val rescueInFlight = AtomicBoolean(false)
    @Volatile private var rescueCount = 0
    private var resamplePos = 0.0
    private var resampleTail = ShortArray(0)
    // 1.1.5/e5t1(vc85)：EU5 数字静默自愈（核心修复）。判据=windowPeak==0（真全零，
    // DSP/静音路径特征；近静音 <300 是环境噪音，绝不能触发，否则正常通话轮流拆重建）。
    // 连续 5s 全零 → 换下一个未试过的音频源重建录音（源序沿 6→1→7 逐个排除）；
    // 全部源轮完仍全零 → 停止轮换，落 QG DSP 层嫌疑取证（EU5 实锤 Android 全栈无错
    // 可报的静音只能出在该层，App 层无罪）。
    private val triedSources = LinkedHashSet<Int>()
    // vc196：声道升级已试过的 (源/采样率/声道) 组合。与 triedSources 分开记账——
    // 源轮换失败会置 healExhausted 全面停手，而声道升级是另一条独立维度。
    private val triedChannels = LinkedHashSet<String>()
    @Volatile private var healExhausted = false
    // vc156（#866 容错）：编码失败限报计数。只在采集线程读写，无需同步。
    private var encodeFailureReports = 0
    // ── vc193：FE6「β 形态（读阻塞）」可观测性 ─────────────────────────────
    // 背景（日志 20261028-174703）：FE6 上 AudioRecord.read 会永久阻塞——不返回正数、
    // 不返回 0（zeroReadStreak 不触发）、不报错（count<0 分支不进），采集线程活着但
    // 一个字节都读不到，日志里只剩一句 uplink started。这两个心跳让该形态可判：
    //  · lastReadReturnMs：read 每次返回（无论几字节）都刷新
    //  · readCallCount / totalReadBytes：看门狗据此区分「没返回过」与「返回 0 空转」
    @Volatile private var lastReadReturnMs = 0L
    @Volatile private var readCallCount = 0
    @Volatile private var totalReadBytes = 0L
    // 会话级看门狗只报一次（避免每次换源后重复刷屏）。
    @Volatile private var firstPacketWatchdogReported = false

    // ── vc197：采集 / 发送解耦（「全力保不掉线」的根）──────────────────────────
    // 旧结构是「采集线程 read → 填帧 → 直接 sendFrame」，所以 **read 一阻塞就一个包都发不出去**。
    // FE6 实测（20261029-203743）：第一次 read 返 2048B（全零），第二次永久阻塞，此后
    // `tx packets` 归零 —— iPhone 收不到音频包，约 2s 就发 TEARDOWN（types=[100]），
    // 表现为「一叫 Siri 就断线」。这不是麦克风问题，是**上行断流**问题。
    // 现在拆成两条线程：
    //   · 采集线程：read → 转换 → 写 [ring]（阻塞也只影响写入，不影响发送）
    //   · pacer 线程：每 frameMillis 从 [ring] 取一帧，**取不到就用静音补满**再发
    // ⇒ 无论采集是否出数，音频包都以恒定节奏出门，iPhone 不再拆流；
    //   采集一旦出数，ring 有货就立刻发真音（切换透明，只多 <120ms 缓冲）。
    @Volatile private var ring: FrameRing? = null
    private var pacerThread: Thread? = null
    /** pacer 用零填充发出的帧数（会话累计），用于区分「在发真音」与「在保活」。 */
    @Volatile private var silencePaddedFrames = 0L
    // ── vc197：root 直采兜底状态 ────────────────────────────────────────────
    @Volatile private var rootStream: RootMicStream? = null
    @Volatile private var rootActive = false
    private var rootThread: Thread? = null
    private val rootInFlight = AtomicBoolean(false)
    @Volatile private var rootAttempts = 0
    @Volatile private var rootNonZeroSeen = false
    /** 上次发起 root 直采的时刻（`elapsedRealtime`），用于节流重试。 */
    @Volatile private var lastRootAttemptMs = 0L

    // ── vc198：首读指纹（判「真采集」还是「AudioFlinger 静音填充」）─────────────
    // 2026-10-29 剖析 FE6 唯一成功的那次（1.1.3，`peak=2118`）得到两个正交指标：
    //   · 成功：`uplink started` → 首个数据块 **272ms**，首块 peak **5**（≈−76dBFS 底噪）
    //   · 失败（vc197）：**5/15ms**，首块 `peak=0 pad=16`
    // 真采集必须等 HAL 采满一个 period 才返回；`mSilenceBuffer` 是现成缓冲，立刻返回。
    // 所以「首块时延 + 首块 peak」能一次性把 `peak=0` 的歧义（安静 vs 没开）解掉。
    // 这两段以前只能靠 level window（1s 粒度）间接推，现在直接落成一等日志。
    /** 本次上行 `startRecording()` 成功的时刻（`elapsedRealtime`），首读时延的基准。 */
    @Volatile private var uplinkStartMs = 0L
    /** 首块数据只报一次。 */
    @Volatile private var firstReadReported = false
    /**
     * 首块到达的时刻（`elapsedRealtime`）。**vc199 新增**：用来在保活打点里判 β 形态 ——
     * 首块之后 `readCalls` 一直停在 1，说明 `read()` 永久阻塞，这条链路只会给一帧。
     */
    @Volatile private var firstReadAtMs = 0L

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
        nativeSource = opened.source
        resamplePos = 0.0
        resampleTail = ShortArray(0)
        // e5t1：记录初始胜出源，静默自愈轮换时排除已试过的源。
        synchronized(triedSources) { triedSources.add(opened.source) }
        // vc196：声道升级记账按会话重置——新会话的车机音频状态可能已经变了。
        synchronized(triedChannels) { triedChannels.clear() }
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
            val captureStartMs = SystemClock.elapsedRealtime()
            lastReadReturnMs = captureStartMs
            // vc198：首读指纹的计时基准（真采集 272ms / 静音填充 5ms，见字段注释）。
            uplinkStartMs = captureStartMs
            firstReadReported = false
            firstReadAtMs = 0L
            // vc197：先立起解耦缓冲与定速发送线程，采集线程随后才启动。
            // 顺序不能反 —— pacer 先跑才能在「采集一个字节都读不到」时依然持续发包保活。
            // 实验性开关关闭时 ring 为 null，capture 内部自动走历史路径（读到就发）。
            val audioRing = if (keepAliveUplink) FrameRing(config.frameBytes * RING_FRAMES) else null
            ring = audioRing
            silencePaddedFrames = 0
            rootStream = null
            rootActive = false
            rootInFlight.set(false)
            rootAttempts = 0
            rootNonZeroSeen = false
            if (audioRing != null) startPacer(nextSocket, audioRing)
            thread = Thread({
                // 麦克风采集 + libopus 编码：上行是通话体验的一等公民，同样抬到 URGENT_AUDIO。
                // vc178：setThreadPriority 在部分 ROM 上抛 SecurityException/IllegalArgumentException
                // （与 AudioRenderer 线程同一风险，那边已包 try/catch）。未捕获会直接杀死采集线程
                // ⇒ 上行全静、且只剩一句无上下文的 UncaughtException。抬优先级失败不该影响采集。
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                } catch (error: Throwable) {
                    Log.w(TAG, "microphone thread priority not raised: ${error.message}")
                }
                capture(nextRecorder, nextSocket)
            }, "carplay-mic").apply {
                isDaemon = true
                start()
            }
            // vc193：采集态看门狗（首包超时 + 阻塞读），与采集线程同生命周期
            startCaptureWatchdog(captureStartMs)
            Log.i(
                TAG,
                "microphone uplink started type=${config.audioType} codec=${config.codec} " +
                    "encoder=$encoderBackend source=$chosenSource rate=${config.sampleRate} " +
                    "channels=${config.channels} frameMs=${config.frameMillis} " +
                    "port=${config.port} native=(${nativeRate}Hz/${nativeChannels}ch) " +
                    "recState=${runCatching { nextRecorder.recordingState }.getOrDefault(-1)} " +
                    "session=${runCatching { nextRecorder.audioSessionId }.getOrDefault(-1)} $effects",
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
        // vc196：这里刻意**不做**有声性探测——开机探测要临时开流读几百毫秒，
        // 正常机型上会把这期间的说话声丢掉（探测缓冲区直接丢弃）。而「读到全零」
        // 这件事本身在 capture() 里每秒都在统计，触发升级的时机更好、且那时本来
        // 就没有音频可丢。详见 promoteChannels()。
        for (source in sources) {
            for (rate in rates) {
                for (mask in masks) {
                    val opened = openCandidate(source, rate, mask, attempts) ?: continue
                    return opened
                }
            }
        }
        return null
    }

    /** 构造单个候选；失败只记 attempts，不抛。 */
    private fun openCandidate(
        source: Int,
        rate: Int,
        mask: Int,
        attempts: StringBuilder,
    ): OpenedRecorder? {
        val minBuffer = AudioRecord.getMinBufferSize(rate, mask, AndroidAudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) {
            attempts.append(" s$source/r$rate/minbuf<=0")
            return null
        }
        val bufferSize = maxOf(minBuffer * 2, config.frameBytes * 4)
        val candidate = try {
            AudioRecord(source, rate, mask, AndroidAudioFormat.ENCODING_PCM_16BIT, bufferSize)
        } catch (error: Throwable) {
            attempts.append(" s$source/r$rate/ex=${error.javaClass.simpleName}")
            return null
        }
        if (candidate.state != AudioRecord.STATE_INITIALIZED) {
            attempts.append(" s$source/r$rate/state${candidate.state}")
            runCatching { candidate.release() }
            return null
        }
        val channels = if (mask == AndroidAudioFormat.CHANNEL_IN_STEREO) 2 else 1
        return OpenedRecorder(candidate, source, rate, channels)
    }

    /** vc196：有声性探测结果。[peak] >0 时 [samples] 是探测窗口里已经读到的原始 PCM。 */
    private class ProbeAudio(val peak: Int, val samples: ByteArray)

    /**
     * vc196：有声性探测。开流读一小段，返回窗口内最大绝对采样值。
     *
     * 与 `STATE_INITIALIZED` 判据的区别：FE6（AK7738）上 mono 恒能初始化，但 AudioFlinger
     * 交付的是静音填充（vc194/vc195 十七次 `peak=0` 实测），必须读真实样本才能分辨。
     *
     * **命中（peak>0）时不 stop**——实例仍在录音、[ProbeAudio.samples] 原样交回调用方
     * 回灌进发送管线，探测本身不吞音频。未命中由调用方 stop/release。
     *
     * 必须独立线程执行：api22 没有非阻塞 read，某些源/参数组合上 `read()` 会永久阻塞
     * （FE6 矩阵里源 6/48000 就是 BLOCKED）。超时后用 `stop()` 唤醒——这正是在
     * `Fe6VoiceProbe` 里踩过两次的坑，这里一次做对。
     *
     * @return `null` 表示读阻塞（无结论）；否则 peak `>0` 有声、`0` 纯静音。
     */
    private fun probeAudio(recorder: AudioRecord, rate: Int, channels: Int): ProbeAudio? {
        val peakOut = java.util.concurrent.atomic.AtomicInteger(-1)
        var captured = ByteArray(0)
        val worker = Thread({
            try {
                recorder.startRecording()
                val windowBytes = maxOf(rate * channels * 2 * PROBE_MILLIS / 1000, 1024)
                val buffer = ByteArray(windowBytes)
                val deadline = SystemClock.elapsedRealtime() + PROBE_MILLIS + PROBE_GRACE_MILLIS
                var peak = 0
                var keep = 0
                while (SystemClock.elapsedRealtime() < deadline) {
                    val count = recorder.read(buffer, 0, buffer.size)
                    if (count <= 0) continue
                    keep = count
                    peak = framePeak(buffer, count)
                    if (peak > 0) break
                }
                if (peak > 0 && keep > 0) captured = buffer.copyOf(keep)
                peakOut.set(peak)
            } catch (error: Throwable) {
                Log.w(TAG, "microphone probe failed: ${error.javaClass.simpleName}")
                peakOut.set(0)
            }
        }, "carplay-mic-probe").apply {
            isDaemon = true
            start()
        }
        worker.join(PROBE_MILLIS + PROBE_GRACE_MILLIS + 300L)
        if (worker.isAlive) {
            // 阻塞：stop() 是唤醒阻塞 read 的唯一手段（api22 无 READ_NONBLOCKING）。
            runCatching { recorder.stop() }
            worker.join(400L)
            return null
        }
        val peak = peakOut.get()
        if (peak > 0) return ProbeAudio(peak, captured)
        runCatching { recorder.stop() }
        return ProbeAudio(peak, ByteArray(0))
    }

    /**
     * M6h 回声消除：4.1 上 `VOICE_COMMUNICATION`(7) 起不来，实际跑的是 `VOICE_RECOGNITION`(6)
     * —— 该源**不带平台 AEC**，所以对方能听到回声（T37 用户反馈）。这里把平台 AEC/NS
     * 显式挂到录音会话上；某台机器不支持就只记日志，**绝不阻断收音**。
     */
    private fun attachAudioEffects(recorder: AudioRecord): String {
        val session = recorder.audioSessionId
        if (session <= 0) return "audioEffect=session-unavailable"
        // vc178：换源时旧 AEC/NS 从不 release——每次静默自愈轮换都泄漏一对 AudioEffect
        // （AudioEffect 占 native 资源且受全局实例数限制，EU5 上多次轮换后可致后续
        // create 直接失败）。附加新实例前先释放上一轮。
        releaseEffects()
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

    /** vc178：释放 AEC/NS（置空并 release），失败静默——效果释放失败不构成链路故障。 */
    private fun releaseEffects() {
        echoCanceler?.let { effect ->
            try {
                effect.release()
            } catch (error: Throwable) {
                Log.w(TAG, "microphone AEC release failed", error)
            }
        }
        noiseSuppressor?.let { effect ->
            try {
                effect.release()
            } catch (error: Throwable) {
                Log.w(TAG, "microphone NS release failed", error)
            }
        }
        echoCanceler = null
        noiseSuppressor = null
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

    /**
     * vc193（FE6 可观测性补丁）：采集态看门狗。
     *
     * 外测在 FE6 上看到的是「Siri 没反应」，日志里只有一句 `microphone uplink started`，
     * 无法区分三种情况：阻塞在读 / 读得到但全零 / 采集线程根本没跑起来。本线程把三者分开：
     *  · A1 首包看门狗（3s，对齐 iPhone 3.31s 拆流）：`readCalls=0` ⇒ **一次 read 都没返回**
     *    （β 形态铁证）；`readCalls>0 && readBytes==0` ⇒ 返回 0 空转（HAL 不出数）；
     *    `readBytes>0` 但无包 ⇒ 问题在编码/发送侧，不在采集。
     *  · A2 阻塞探针（>2s）：进入与解除各落一条，带持续时长。
     * 两者都只在「已启动」的分支触发，且首包看门狗整个会话只报一次（换源后不刷屏）。
     */
    private fun startCaptureWatchdog(sessionStartMs: Long) {
        val watchdog = Thread({
            var blockStartMs = 0L
            var blockReported = false
            while (running.get()) {
                try {
                    Thread.sleep(WATCHDOG_POLL_MILLIS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val current = recorder ?: continue
                val now = SystemClock.elapsedRealtime()
                val calls = readCallCount
                val bytes = totalReadBytes
                val lastReturn = lastReadReturnMs
                val recState = runCatching { current.recordingState }.getOrDefault(-1)

                if (!firstPacketLogged.get() && !firstPacketWatchdogReported &&
                    now - sessionStartMs >= FIRST_PACKET_WATCHDOG_MS
                ) {
                    firstPacketWatchdogReported = true
                    diagnostics?.invoke(
                        "Microphone firstPacketWatchdog: no packet in ${now - sessionStartMs}ms " +
                            "threadAlive=${thread?.isAlive} recorderState=${current.state} " +
                            "recordingState=$recState readCalls=$calls readBytes=$bytes " +
                            "lastReadMsAgo=${if (lastReturn > 0) now - lastReturn else -1} " +
                            "native=${nativeRate}Hz/${nativeChannels}ch type=${config.audioType}",
                    )
                }

                if (lastReturn > 0 && now - lastReturn > READ_BLOCKED_MILLIS) {
                    if (!blockReported) {
                        blockReported = true
                        blockStartMs = lastReturn
                        diagnostics?.invoke(
                            "Microphone read blocked: no return in ${now - lastReturn}ms " +
                                "readCalls=$calls readBytes=$bytes recordingState=$recState",
                        )
                    }
                } else if (blockReported) {
                    blockReported = false
                    diagnostics?.invoke(
                        "Microphone read unblocked after ${now - blockStartMs}ms " +
                            "(readCalls=$calls readBytes=$bytes)",
                    )
                }

                // vc197：β 形态救援。FE6 实测（20261029-203743）：第一次 read 返回 2048B
                // **全零**，第二次 read 永久阻塞 —— 采集线程卡在 read() 里，一秒一次的
                // level window 统计进不去 ⇒ vc196 写在循环内的声道升级永远执行不到。
                // 这里由看门狗发起；阈值 600ms（20ms/帧的流上 30 帧没返回已不正常），
                // 远早于 iPhone 约 2s 的 TEARDOWN，给切换留出时间。
                if (!sawNonZeroAudio && !promotionAttempted && rescueCount < MAX_RESCUE_ATTEMPTS &&
                    lastReturn > 0 && now - lastReturn >= RESCUE_BLOCK_MILLIS
                ) {
                    rescueBlockedRead(current, now - lastReturn)
                }

                // vc197：root 直采兜底也要能从看门狗发起 —— β 形态（read 永久阻塞）下采集
                // 循环根本进不到「每秒 level window」，写在那里的兜底等于不存在（vc196 的教训）。
                // 另加一条 6s 无条件兜底：覆盖任何"既不阻塞也不进 level window"的未知形态。
                if (!sawNonZeroAudio) {
                    val blockedLong = lastReturn > 0 && now - lastReturn >= ROOT_FALLBACK_BLOCK_MILLIS
                    val tooLongNoAudio = now - sessionStartMs >= ROOT_FALLBACK_FORCE_MILLIS
                    if (blockedLong || tooLongNoAudio) maybeStartRootFallback()
                }
            }
        }, "carplay-mic-watchdog")
        watchdog.isDaemon = true
        watchdog.start()
    }

    /**
     * vc197：从看门狗线程救援一次「read 已阻塞」的采集会话。
     *
     * 为什么要跨线程：api22 没有 `READ_NONBLOCKING`，`read()` 阻塞时调用方**没有**任何
     * 主动检查的机会——`stop()` 是唯一能唤醒它的手段。所以救援必须在别的线程上：
     * 新实例先跑起来 → `old.stop()/release()`（旧 read 随即返回错误）→ 采集线程下一轮
     * 从 [activeRecorder] 取到新实例继续，不丢会话。
     *
     * 顺序：先换声道/换率（FE6 主路是 16000/2ch），都不行再退回换源。
     */
    private fun rescueBlockedRead(old: AudioRecord, blockedMs: Long) {
        if (!rescueInFlight.compareAndSet(false, true)) return
        promotionAttempted = true
        rescueCount++
        diagnostics?.invoke(
            "Microphone rescue #$rescueCount: read 未返回 ${blockedMs}ms " +
                "native=${nativeRate}Hz/${nativeChannels}ch → 试换声道/换率重开",
        )
        Thread({
            try {
                if (!promoteChannels(old) && running.get()) {
                    // 换声道/换率都没命中 ⇒ 退回换源（此时 old 未被降级，仍是活实例）。
                    rotateSilentRecorder(old)
                }
            } catch (error: Throwable) {
                Log.e(TAG, "microphone rescue failed", error)
                diagnostics?.invoke(
                    "Microphone rescue failed: ${error.javaClass.simpleName}: ${error.message}",
                )
            } finally {
                rescueInFlight.set(false)
            }
        }, "carplay-mic-rescue").apply {
            isDaemon = true
            start()
        }
    }

    private fun capture(initialRecorder: AudioRecord, socket: DatagramSocket) {
        val frame = ByteArray(config.frameBytes)
        // 1.1.1：胜出参数≠协商参数时，读入缓冲按 native 速率/声道放大，读多读少都能处理。
        val conversionFactor = maxOf(
            1,
            (nativeRate.toLong() * nativeChannels / (config.sampleRate.toLong() * config.channels)).toInt(),
        )
        val readBuffer = ByteArray(maxOf(frame.size, MIN_READ_BYTES) * conversionFactor)
        // 传统发送路径专用（实验性开关关闭时）。开兜底时这两者由 pacer 线程持有。
        val counters = MicrophoneCounters(ssrc = 0)
        var filled = 0
        // 1.0.8 Siri 排查：包数正常≠有声。每秒统计帧峰值并落盘，判定采集内容是否静音
        // （包格式/密钥/发送已与上游逐字节对齐，剩余嫌疑只有 PCM 内容）。
        var windowPeak = 0
        var windowBytes = 0
        var lastLevelLog = 0L
        // 1.1.1：胜出参数恰好等于协商参数时走零转换快路径（对正常车机零行为变化）。
        // e5t1：改为动态判定——静默自愈轮换可能改变 native 参数，不能用启动时的快照。
        // e5t1：连续全零秒计数（数字静默自愈触发器，见字段注释）。
        var zeroPeakSeconds = 0
        // vc87（fe6t5）：read 零字节连击计数（HAL 出数失败检测，见常量注释）。
        var zeroReadStreak = 0
        try {
            // vc197：采集实例每轮从字段取——看门狗线程可能在任意时刻把它换掉。
            activeRecorder = initialRecorder
            prefillSink = null
            promotionAttempted = false
            sawNonZeroAudio = false
            rescueCount = 0
            while (running.get()) {
                val currentRecorder = activeRecorder ?: break
                // API16 适配：带 mode 的 AudioRecord.read 是 API 23+；低版本用阻塞语义的 int 重载。
                val pending = prefillSink
                if (pending != null) prefillSink = null
                val count = if (pending != null) {
                    // 探测缓冲区可能比 readBuffer 大（升级前后声道数/采样率不同），截断即可。
                    val carry = minOf(pending.size, readBuffer.size)
                    pending.copyInto(readBuffer, 0, 0, carry)
                    carry
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    currentRecorder.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
                } else {
                    currentRecorder.read(readBuffer, 0, readBuffer.size)
                }
                // vc193：read 心跳（阻塞探针的唯一数据源）——返回正数、0、错误码都算「返回」
                lastReadReturnMs = SystemClock.elapsedRealtime()
                readCallCount++
                if (count > 0) totalReadBytes += count
                // vc198：首块数据指纹 —— 时延判「是否真在采集」，peak 判「是否有内容」。
                // 这两个指标来自 FE6 唯一成功那次（272ms / peak=5）与失败那次（5ms / peak=0）
                // 的对照，正交且不受「当时是否有人说话」影响，比 1 秒粒度的 level window 准。
                if (!firstReadReported && count > 0) {
                    firstReadReported = true
                    firstReadAtMs = SystemClock.elapsedRealtime()
                    val sinceUplink = if (uplinkStartMs > 0) {
                        firstReadAtMs - uplinkStartMs
                    } else {
                        -1L
                    }
                    val firstPeak = framePeak(readBuffer, count)
                    // vc199：判据只剩 peak。「首块时延」已作废 —— `via=pacer` 的保活让首包恒为
                    // ~11ms（与是否真采无关），且矩阵里 SILENT 档 firstByteMs=322~355ms 与
                    // 真采集同量级（那 300ms 只是 AudioRecord 启动到第一个 period 的固有延迟）。
                    // 所以文案里不能再写「此刻没人说话」这种把"没采"说成"安静"的话。
                    val verdict = when {
                        firstPeak > 0 -> "真采集（ADC 已工作，有底噪）"
                        sinceUplink in 0 until FIRST_READ_SILENCE_MS ->
                            "静音填充（mSilenceBuffer 立刻返回，等于没采）"
                        else ->
                            "首块全零（仅 1 块 ${count}B 不足以代表整段；" +
                                "看 keepalive 的 readCalls 是否继续增长，不增长=β 形态）"
                    }
                    diagnostics?.invoke(
                        "Microphone FIRST read sinceUplink=${sinceUplink}ms bytes=$count " +
                            "peak=$firstPeak native=${nativeRate}Hz/${nativeChannels}ch ⇒ $verdict",
                    )
                }
                if (count < 0) {
                    // vc197：救援线程换了实例时会 stop() 旧实例，此刻 read 返回错误属预期，
                    // 不能因此退出采集线程——换下一轮用新实例继续。
                    if (activeRecorder !== currentRecorder) {
                        diagnostics?.invoke(
                            "Microphone capture switched recorder after read error code=$count " +
                                "(old instance retired by rescue)",
                        )
                        continue
                    }
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
                        rotateSilentRecorder(currentRecorder)
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
                // vc197：两种发送模式，由「实验性 FE6 语音兜底」开关决定（默认关）。
                //  · 开（[keepAliveUplink]）：写解耦缓冲，由 pacer 线程按 20ms 节拍取走发送，
                //    采集断流时补静音帧 ⇒ iPhone 不会因收不到音频包而 2s 拆流。
                //  · 关：与历史版本逐字节一致（读到就发），对非 FE6 机型零行为变化。
                val sinkRing = ring
                if (sinkRing != null) {
                    // vc197：root 直采接管期间**停止**写入 AudioRecord 的数据 —— 否则
                    // ring 里会交替出现「真音（root）」与「全零（AudioFlinger 填充）」，
                    // 变成断续的嗡嗡声。接管结束后（rootActive 落回 false）再恢复写入。
                    if (!rootActive) sinkRing.write(data, size)
                    if (!rootActive) {
                        windowPeak = maxOf(windowPeak, framePeak(data, size))
                        windowBytes += size
                    }
                } else {
                    var offset = 0
                    while (offset < size && running.get()) {
                        val copied = minOf(frame.size - filled, size - offset)
                        data.copyInto(frame, filled, offset, offset + copied)
                        filled += copied
                        offset += copied
                        if (filled == frame.size) {
                            windowPeak = maxOf(windowPeak, framePeak(frame))
                            windowBytes += frame.size
                            sendFrame(socket, counters, frame)
                            filled = 0
                        }
                    }
                }
                // 每秒统计（不再依赖"填满一帧"这个事件——帧边界已交给 pacer）。
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastLevelLog >= 1000) {
                    lastLevelLog = nowMs
                    val frames = (windowBytes / frame.size).coerceAtLeast(if (windowBytes > 0) 1 else 0)
                    // 峰值满量程=32767；持续 <300 视为近静音（采集源问题）。
                    diagnostics?.invoke(
                        "Microphone level window frames=$frames peak=$windowPeak " +
                            "pad=$silencePaddedFrames root=${if (rootActive) "on" else "off"}",
                    )
                    // e5t1：数字静默自愈——peak==0（真全零）连续 SILENT_HEAL_SECONDS 秒
                    // 才触发；<300 的近静音是环境噪音，绝不触发（避免正常静场轮流拆建）。
                    zeroPeakSeconds = if (windowBytes > 0 && windowPeak == 0) {
                        zeroPeakSeconds + 1
                    } else {
                        0
                    }
                    // vc196：先试「换声道」，再试「换源」。精确全零（不是 <300 的近静音）
                    // 意味着这条通路由 HAL 交付静音填充，此刻升级不丢任何音频；正常机型
                    // 永远走不到这里。
                    if (windowPeak > 0) sawNonZeroAudio = true
                    if (zeroPeakSeconds >= ZERO_PEAK_PROMOTE_SECONDS && !promotionAttempted) {
                        promotionAttempted = true
                        // vc197：成功时 promoteChannels 自己写 activeRecorder + prefillSink。
                        promoteChannels(currentRecorder)
                    }
                    if (zeroPeakSeconds >= SILENT_HEAL_SECONDS && !healExhausted) {
                        zeroPeakSeconds = 0
                        rotateSilentRecorder(currentRecorder)
                        // 轮换失败时 rotateSilentRecorder 内部已置 healExhausted
                        // 并落 QG DSP 嫌疑取证，此后静默只观察不再轮换。
                    }
                    // vc197：AudioRecord 连「读到全零」都做不到（阻塞/能读全零）时，
                    // 走 root 直采兜底 —— 绕过 AudioFlinger，直接从 ALSA 采集节点拿真音。
                    maybeStartRootFallback()
                    windowPeak = 0
                    windowBytes = 0
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
     * vc197：采集→发送解耦缓冲（单写单读的字节环）。
     *
     * 为什么必须有它：FE6 的 β 形态下 `AudioRecord.read()` 会永久阻塞，旧结构「读到才发」
     * 直接导致**一个包都发不出去**（vc196 日志 `readCalls=1` 之后 `tx` 归零），iPhone
     * 约 2s 就 TEARDOWN。解耦后采集线程卡在 read 里也没关系，pacer 照常按 20ms 发包。
     *
     * 写满时**丢最旧**：语音上行要的是"最新"，宁可丢 100ms 也不要把延迟越堆越高。
     */
    private class FrameRing(private val capacity: Int) {
        private val buf = ByteArray(capacity)
        private var head = 0
        private var size = 0

        fun write(src: ByteArray, length: Int) {
            if (length <= 0) return
            synchronized(this) {
                if (length >= capacity) {
                    src.copyInto(buf, 0, length - capacity, length)
                    head = 0
                    size = capacity
                    return
                }
                val over = size + length - capacity
                if (over > 0) {
                    head = (head + over) % capacity
                    size -= over
                }
                val tail = (head + size) % capacity
                val firstPart = minOf(length, capacity - tail)
                src.copyInto(buf, tail, 0, firstPart)
                if (firstPart < length) src.copyInto(buf, 0, firstPart, length)
                size += length
            }
        }

        fun read(dst: ByteArray, length: Int): Int {
            synchronized(this) {
                val got = minOf(size, length)
                if (got <= 0) return 0
                val firstPart = minOf(got, capacity - head)
                buf.copyInto(dst, 0, head, head + firstPart)
                if (firstPart < got) buf.copyInto(dst, firstPart, 0, got - firstPart)
                head = (head + got) % capacity
                size -= got
                return got
            }
        }

        fun clear() {
            synchronized(this) {
                head = 0
                size = 0
            }
        }
    }

    /**
     * vc197：定速发送线程 —— 「全力保不掉线」的执行者。
     *
     * 每 [config] 的 `frameMillis` 从 [ring] 取一帧；取不满就用静音（PCM16 全零）补满再发。
     * 于是 **`tx packets` 恒定增长**，与采集是否出数完全解耦：
     * · 采集正常 ⇒ 发真音，听感与旧版一模一样（多 <120ms 缓冲）
     * · 采集阻塞/全零 ⇒ 发静音帧，iPhone 认为流还活着，不会 TEARDOWN
     *
     * 节拍用 `System.nanoTime()` 绝对时间累加而非 `sleep(period)`，避免累积漂移；落后超过
     * 100ms 就重置节拍——否则网络抖动之后会连续狂发补包，反而更容易被打回。
     */
    private fun startPacer(socket: DatagramSocket, ring: FrameRing) {
        val frame = ByteArray(config.frameBytes)
        // 1.0.19 回归上游：RTP 头 SSRC 位恒 0（上游 sealPacket 根本不写 8–11 字节）。
        // BE+SSRC=0（1.0.8）与 BE+SSRC=rand（1.0.12）在 3e 均失败 → SSRC 非主因，
        // 但基线必须与上游逐字节等价，排除变量后剩余失败才能归因到包外。
        val counters = MicrophoneCounters(ssrc = 0)
        val periodNs = maxOf(config.frameMillis, 10) * 1_000_000L
        val pacer = Thread({
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (_: Throwable) {
                // 抬优先级失败不影响发包，别让它拆线程。
            }
            var next = System.nanoTime()
            var silentStreak = 0
            var pacerSendFailures = 0
            while (running.get()) {
                next += periodNs
                val got = ring.read(frame, frame.size)
                if (got < frame.size) {
                    java.util.Arrays.fill(frame, got, frame.size, 0)
                    silencePaddedFrames++
                    silentStreak++
                    // 第一次静音补帧 + 之后每 100 帧（约 2s）落一条，避免刷屏。
                    if (silentStreak == 1 || silentStreak % 100 == 0) {
                        // vc199：readCalls 停在 1 = 首块之后 read() 永久阻塞 = FE6 的 β 形态。
                        // 直接判出来，省得每次人工数 readCalls（vc198 就是这么一条条数出来的）。
                        val betaNote = if (firstReadAtMs > 0 && readCallCount <= 1) {
                            " ⇒ β 形态：首块后 " +
                                "${SystemClock.elapsedRealtime() - firstReadAtMs}ms 无新数据（read 永久阻塞）"
                        } else {
                            ""
                        }
                        diagnostics?.invoke(
                            "Microphone keepalive: 无采集数据，发静音帧保活 " +
                                "(streak=$silentStreak padded=$silencePaddedFrames " +
                                "readCalls=$readCallCount readBytes=$totalReadBytes " +
                                "root=${if (rootActive) "on" else "off"} " +
                                "native=${nativeRate}Hz/${nativeChannels}ch)$betaNote",
                        )
                    }
                } else {
                    silentStreak = 0
                }
                // vc197：发送失败（UDP 瞬时错误 / 编码异常）**不能**杀死 pacer ——
                // 它是整个会话唯一的发包者，死了就等于上行彻底断流。旧结构里 sendFrame
                // 抛异常会被 capture 的 catch 兜住，现在必须在这里兜。
                try {
                    sendFrame(socket, counters, frame)
                } catch (error: Throwable) {
                    if (!running.get()) break
                    if (pacerSendFailures < MAX_PACER_SEND_FAILURES) {
                        pacerSendFailures++
                        diagnostics?.invoke(
                            "Microphone keepalive send failed #$pacerSendFailures: " +
                                "${error.javaClass.simpleName}: ${error.message}（保活继续）",
                        )
                    }
                }
                val sleepMs = (next - System.nanoTime()) / 1_000_000L
                if (sleepMs > 0) {
                    try {
                        Thread.sleep(sleepMs)
                    } catch (_: InterruptedException) {
                        break
                    }
                } else if (sleepMs < -100L) {
                    // 落后太多：放弃补帧，重置节拍。
                    next = System.nanoTime()
                }
            }
        }, "carplay-mic-pacer")
        pacer.isDaemon = true
        pacer.start()
        pacerThread = pacer
    }

    /**
     * vc197：root 直采兜底 —— 「语音可用」的执行者。
     *
     * 触发条件（均为实测过的失败形态）：
     * · AudioRecord 能读但**精确全零**（α 形态，vc194/vc195/vc196 共 17 次 `peak=0`）
     * · AudioRecord `read()` **阻塞不返回**（β 形态，vc196：`readCalls=1` 后永久卡住）
     *
     * 之所以绕道：13 档（源×采样率×声道 × 2 轮）矩阵已把 App 侧参数穷尽，而车机自带语音
     * 助手正常收音 ⇒ 静音是 AudioFlinger 给第三方 record track 加填充，不是硬件没音。
     * 直读 ALSA 采集节点（`/dev/snd/pcmC0D1c`，需 root）绕开这一层。
     *
     * 每会话最多试 [MAX_ROOT_ATTEMPTS] 次；一旦 AudioRecord 自己读到非零就再也不绕道。
     */
    private fun maybeStartRootFallback() {
        // 实验性开关关闭 ⇒ 不碰 root（非 FE6 机型也不该走这条）。
        if (!keepAliveUplink) return
        val source = rootMicSource ?: return
        if (rootActive || rootInFlight.get()) return
        if (rootAttempts >= MAX_ROOT_ATTEMPTS) return
        if (sawNonZeroAudio) return
        if (!running.get()) return
        // 看门狗每 500ms 一轮，但两次直采尝试之间必须留间隔：su 起进程 + ALSA open
        // 本身要 1~2s，紧邻重试只会撞上同一个 EBUSY。
        val nowMs = SystemClock.elapsedRealtime()
        if (lastRootAttemptMs > 0 && nowMs - lastRootAttemptMs < ROOT_RETRY_INTERVAL_MILLIS) return
        lastRootAttemptMs = nowMs
        rootAttempts++
        if (!rootInFlight.compareAndSet(false, true)) return
        diagnostics?.invoke(
            "Microphone root fallback #$rootAttempts: AudioRecord 持续全零/无返回 " +
                "(native=${nativeRate}Hz/${nativeChannels}ch readCalls=$readCallCount " +
                "readBytes=$totalReadBytes) → 试 ALSA 直采绕过 AudioFlinger",
        )
        Thread({
            var stream: RootMicStream? = null
            try {
                if (!source.isAvailable()) {
                    diagnostics?.invoke("Microphone root fallback skipped: su 不可用或直采二进制缺失")
                    return@Thread
                }
                stream = source.open()
                if (stream == null) {
                    diagnostics?.invoke(
                        "Microphone root fallback failed: 直采节点全打不开" +
                            "（EBUSY/无权限，详见 root 诊断行）",
                    )
                    return@Thread
                }
                if (!running.get()) {
                    stream.close()
                    return@Thread
                }
                rootStream = stream
                rootActive = true
                diagnostics?.invoke(
                    "Microphone root fallback active: node=${stream.node} " +
                        "${stream.rate}Hz/${stream.channels}ch → 已接管上行音频",
                )
                pumpRootStream(stream)
            } catch (error: Throwable) {
                diagnostics?.invoke(
                    "Microphone root fallback error: ${error.javaClass.simpleName}: ${error.message}",
                )
            } finally {
                rootActive = false
                rootStream = null
                runCatching { stream?.close() }
                if (running.get() && !rootNonZeroSeen && rootAttempts < MAX_ROOT_ATTEMPTS) {
                    diagnostics?.invoke(
                        "Microphone root capture 无产出，将在下一窗口重试 " +
                            "($rootAttempts/$MAX_ROOT_ATTEMPTS)",
                    )
                }
                rootInFlight.set(false)
            }
        }, "carplay-mic-root").apply {
            isDaemon = true
            start()
        }
    }

    /** vc197：把 root 直采流按块读进来，转成协商格式后写进 [ring]。 */
    private fun pumpRootStream(stream: RootMicStream) {
        // 单次读 50ms（16000Hz/2ch ⇒ 3200 字节），足够小到不涨延迟、大到不空转。
        val buf = ByteArray(maxOf(stream.rate * stream.channels * 2 / 20, 4_096))
        val sameRate = stream.rate == config.sampleRate
        val sameChannels = stream.channels == config.channels
        var windowPeak = 0
        var windowBytes = 0
        var lastLog = System.currentTimeMillis()
        // 连续多少秒读到全零——超过阈值就结束本轮，让看门狗换节点/换参数重试
        // （否则会一直挂在一条不出数的通路上直到会话结束）。
        var silentWindows = 0
        while (running.get()) {
            val n = try {
                stream.input.read(buf, 0, buf.size)
            } catch (_: Exception) {
                -1
            }
            if (n < 0) break
            if (n == 0) continue
            val out: ByteArray
            val size: Int
            if (sameRate && sameChannels) {
                out = buf
                size = n
            } else {
                out = convertChunkFrom(buf, n, stream.rate, stream.channels)
                size = out.size
            }
            if (size <= 0) continue
            ring?.write(out, size)
            val peak = framePeak(out, size)
            if (peak > 0) {
                if (!rootNonZeroSeen) {
                    rootNonZeroSeen = true
                    diagnostics?.invoke(
                        "Microphone root capture FIRST NON-ZERO peak=$peak node=${stream.node} " +
                            "⇒ 车机侧确实有真实音频，直采已拿到",
                    )
                }
            }
            windowPeak = maxOf(windowPeak, peak)
            windowBytes += size
            val now = System.currentTimeMillis()
            if (now - lastLog >= 1000) {
                lastLog = now
                diagnostics?.invoke(
                    "Microphone root capture window bytes=$windowBytes peak=$windowPeak " +
                        "node=${stream.node}",
                )
                silentWindows = if (windowPeak == 0) silentWindows + 1 else 0
                windowPeak = 0
                windowBytes = 0
                if (silentWindows >= ROOT_SILENT_WINDOWS) {
                    diagnostics?.invoke(
                        "Microphone root capture 连续 ${silentWindows}s 全零，结束本轮 " +
                            "（node=${stream.node}）",
                    )
                    break
                }
            }
        }
        diagnostics?.invoke(
            "Microphone root capture ended: node=${stream.node} nonzero=$rootNonZeroSeen",
        )
    }

    /**
     * vc197：从**任意源参数**转换到协商格式（root 直采专用，与 AudioRecord 的
     * [convertChunk] 分开：后者带跨读增量的重采样状态，两条通路同时跑会互相踩状态）。
     * 这里用块内线性插值，代价是块边界可能有极小不连续，对语音可懂度无影响。
     */
    private fun convertChunkFrom(
        src: ByteArray,
        count: Int,
        srcRate: Int,
        srcChannels: Int,
    ): ByteArray {
        val frames = count / (2 * srcChannels)
        if (frames <= 0) return EMPTY_BYTES
        val mono = ShortArray(frames)
        var idx = 0
        for (f in 0 until frames) {
            val l = leShort(src, idx)
            mono[f] = if (srcChannels >= 2) ((l + leShort(src, idx + 2)) / 2).toShort() else l
            idx += 2 * srcChannels
        }
        val monoOut: ShortArray = if (srcRate == config.sampleRate) {
            mono
        } else {
            val step = srcRate.toDouble() / config.sampleRate
            if (step <= 0.0) mono else {
                val outSize = ((mono.size - 1) / step).toInt().coerceAtLeast(0)
                val tmp = ShortArray(outSize)
                var p = 0.0
                var i = 0
                while (i < outSize) {
                    val i0 = p.toInt()
                    if (i0 + 1 >= mono.size) break
                    val frac = p - i0
                    tmp[i] = (mono[i0] * (1.0 - frac) + mono[i0 + 1] * frac).roundToInt().toShort()
                    p += step
                    i++
                }
                tmp
            }
        }
        val out = ByteArray(monoOut.size * config.channels * 2)
        var o = 0
        for (s in monoOut) {
            repeat(config.channels) {
                putLeShort(out, o, s)
                o += 2
            }
        }
        return out
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
        // vc178（顺序修正）：原实现先 release 旧 AudioRecord、再赋字段、最后才 startRecording()。
        // startRecording 失败时字段已指向一个**从未启动**的新实例、旧实例又已释放，但本函数
        // return null ⇒ 调用方 `if (rotated != null)` 不成立 ⇒ capture 继续读**已释放**的旧
        // AudioRecord ⇒ 上行彻底静默且无诊断（正是"静默自愈后反而没声音"的表现）。
        // 改为：先把新实例跑起来，成功后才停旧实例、才落字段——失败路径因此天然可回滚。
        val effects = attachAudioEffects(opened.recorder)
        try {
            opened.recorder.startRecording()
        } catch (error: Exception) {
            Log.e(TAG, "microphone heal restart failed", error)
            // 回滚：释放刚建的实例与刚附加的效果，旧实例原样继续工作（字段未被改写）。
            releaseEffects()
            try {
                opened.recorder.release()
            } catch (_: Throwable) {
            }
            diagnostics?.invoke(
                "Microphone silent-source heal: rotated source=${opened.source} failed to start: " +
                    "${error.message} — rolled back, previous source keeps running " +
                    "(previous effects $previousEffects)",
            )
            return null
        }
        try {
            old.stop()
        } catch (_: Exception) {
        }
        try {
            old.release()
        } catch (_: Exception) {
        }
        recorder = opened.recorder
        activeRecorder = opened.recorder
        nativeRate = opened.rate
        nativeChannels = opened.channels
        nativeSource = opened.source
        resamplePos = 0.0
        resampleTail = ShortArray(0)
        diagnostics?.invoke(
            "Microphone silent-source heal: rotated to source=${opened.source} " +
                "rate=${opened.rate} channels=${opened.channels} $effects",
        )
        return opened.recorder
    }

    /**
     * vc196/vc197：声道升级——「同源换声道重开」，FE6（AK7738）有线 Siri 全零的核心修复。
     *
     * 立论链条（全部来自实测，非猜测）：
     * 1. vc194/vc195 十七次 `Microphone level window peak=0`、`tx packets=50`——链路全通、
     *    采样值恒为 0；同一台车机**自带语音助手正常收音**（用户实测录像），⇒ 硬件、DSP、
     *    偏置供电都没坏，坏的是「DiPlay 这条 AudioRecord 通路拿不到数据」。
     * 2. vc195 拓扑：车机自己的录音进程（pid 3191）以 **16000Hz / 2ch** 独占
     *    `pcm1c`(aif3, `MultiMedia1_Capture ak7738-aif3-1`) 且 `state=RUNNING`、
     *    `hw_ptr` 稳定递增——**aif3 就是麦克风主通路，它按 2 声道工作**。
     * 3. DiPlay 侧 `sourcesFor("speechrecognition")` 只给 6→1，`config.channels` 由 iPhone
     *    协商为 1，兜底矩阵虽含 STEREO 但「首个 STATE_INITIALIZED 即胜出」⇒ **永远停在
     *    mono 第一档**；静默自愈又只轮换「源」、不轮换声道 ⇒ 二十多个版本从未试过 stereo。
     *
     * 触发有两条路（都只在「没读到过非零」时才走）：
     * - **α 形态**（read 持续返回全零）：`capture()` 的 level window 里 1s 精确全零即触发。
     * - **β 形态**（vc196 实测：第一次 read 返回 2048B 全零、第二次 read 永久阻塞）：
     *   采集线程卡在 `read()` 里，上面那条路永远走不到。改由**看门狗线程**在
     *   `RESCUE_BLOCK_MILLIS` 未返回时发起（见 startCaptureWatchdog）。
     *
     * 成功时本函数负责：`old.stop()/release()`（顺带唤醒阻塞的 read）→ 字段切到新实例 →
     * 把探测窗口里已读到的音频塞进 [prefillSink]。采集线程下一轮自然用上新实例。
     *
     * @return true 表示已切换到「能读到非零样本」的新实例。
     */
    @Synchronized
    private fun promoteChannels(old: AudioRecord): Boolean {
        if (!running.get()) return false
        val source = nativeSource
        val oldRate = nativeRate
        val oldChannels = nativeChannels
        val currentMask =
            if (oldChannels >= 2) AndroidAudioFormat.CHANNEL_IN_STEREO
            else AndroidAudioFormat.CHANNEL_IN_MONO
        val alternateMask =
            if (currentMask == AndroidAudioFormat.CHANNEL_IN_MONO) AndroidAudioFormat.CHANNEL_IN_STEREO
            else AndroidAudioFormat.CHANNEL_IN_MONO
        // 候选序（同源，不换源——换源是 rotateSilentRecorder 的职责）。
        // vc197 关键调整：**16000/2ch 提到第一位**。它是车机自己进程的真实配置，
        // 命中率最高；而 vc196 把「同率 stereo」排第一，在 FE6 上要多花一次 450ms 探测，
        // 而 iPhone 只给约 2s 就 TEARDOWN。
        val candidates = (
            if (oldChannels < 2) {
                listOf(
                    16_000 to AndroidAudioFormat.CHANNEL_IN_STEREO,
                    oldRate to AndroidAudioFormat.CHANNEL_IN_STEREO,
                    48_000 to AndroidAudioFormat.CHANNEL_IN_STEREO,
                )
            } else {
                listOf(
                    16_000 to AndroidAudioFormat.CHANNEL_IN_MONO,
                    oldRate to AndroidAudioFormat.CHANNEL_IN_MONO,
                )
            }
            ).distinct()
        val attempts = StringBuilder()
        for ((rate, mask) in candidates) {
            if (rate <= 0) continue
            if (rate == oldRate && mask == currentMask) continue
            val key = "$source/$rate/$mask"
            val fresh = synchronized(triedChannels) { triedChannels.add(key) }
            if (!fresh) {
                attempts.append(" s$source/r$rate/m$mask=already-tried")
                continue
            }
            val opened = openCandidate(source, rate, mask, attempts) ?: continue
            val probe = probeAudio(opened.recorder, opened.rate, opened.channels)
            if (probe == null || probe.peak <= 0) {
                attempts.append(
                    " s$source/r$rate/c${opened.channels}/" +
                        if (probe == null) "blocked" else "silent",
                )
                runCatching { opened.recorder.release() }
                continue
            }
            // 命中：probeAudio 成功路径不 stop，实例仍在录音，音频从下一帧起即可续上。
            try {
                old.stop()
            } catch (_: Exception) {
            }
            try {
                old.release()
            } catch (_: Exception) {
            }
            // vc178 教训：旧实例的 AEC/NS 必须释放，否则每次升级泄漏一对 AudioEffect。
            // 升级后的通路**不挂效果**——AEC/NS 本身也是「全零」的嫌疑之一（未设
            // MODE_IN_COMMUNICATION 时部分 HAL 的 AEC 会把输入打成静音），这里顺带把它
            // 变成一个可观测变量。
            releaseEffects()
            recorder = opened.recorder
            activeRecorder = opened.recorder
            prefillSink = probe.samples
            nativeRate = opened.rate
            nativeChannels = opened.channels
            nativeSource = opened.source
            resamplePos = 0.0
            resampleTail = ShortArray(0)
            sawNonZeroAudio = true
            diagnostics?.invoke(
                "Microphone channel promotion: ${oldRate}Hz/${oldChannels}ch -> " +
                    "${opened.rate}Hz/${opened.channels}ch source=${opened.source} peak=${probe.peak} " +
                    "effects=off prefill=${probe.samples.size}B attempts:$attempts",
            )
            return true
        }
        diagnostics?.invoke(
            "Microphone channel promotion failed (常零且换声道/换率均无声，交由源轮换): " +
                "was ${oldRate}Hz/${oldChannels}ch source=$source attempts:$attempts",
        )
        return false
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
    private fun framePeak(frame: ByteArray): Int = framePeak(frame, frame.size)

    /** vc196：只统计前 [length] 字节（探测时 read 的返回值可能小于缓冲区）。 */
    private fun framePeak(frame: ByteArray, length: Int): Int {
        var peak = 0
        var index = 0
        while (index + 1 < length) {
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
                // vc198：补 sinceUplinkMs + via —— pacer 保活发的首包恒 ~20ms，与「采集是否
                // 出数」无关，只有 `via=record` 的首包时延才有指纹意义（判读看 FIRST read 那行）。
                val sinceUplink = if (uplinkStartMs > 0) {
                    SystemClock.elapsedRealtime() - uplinkStartMs
                } else {
                    -1L
                }
                diagnostics?.invoke(
                    "Microphone first packet bytes=${packet.size} body=${body.size} " +
                        "head=${packet.copyOf(minOf(packet.size, 16)).toHexString()} " +
                        "ssrc=${counters.ssrc} localPort=${socket.localPort} " +
                        "be=${MicrophonePacketizer.PCM_WIRE_BIG_ENDIAN} " +
                        "host=${config.host.hostAddress} port=${config.port} " +
                        "sinceUplinkMs=$sinceUplink via=${if (ring != null) "pacer" else "record"}",
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
        // vc197：先停 root 直采（子进程 + 流），再停 pacer，最后放设备 ——
        // 反过来的话 pacer 可能在 socket 关闭后继续 send 而抛异常刷日志。
        val currentRootStream = rootStream
        rootStream = null
        rootActive = false
        try {
            currentRootStream?.close()
        } catch (_: Exception) {
            // Best effort.
        }
        try {
            rootMicSource?.release()
        } catch (_: Throwable) {
            // Best effort.
        }
        val currentPacer = pacerThread
        pacerThread = null
        if (currentPacer != null && currentPacer.isAlive) {
            currentPacer.interrupt()
        }
        ring = null
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
        // vc196：有声性探测窗口。把「能初始化」升级为「能读到非零样本」——
        // FE6（AK7738）实证 mono 恒 STATE_INITIALIZED 但交付静音填充，车机真实通路是
        // 16000/2ch。250ms ≈ 6 个 20ms 帧，够覆盖一次人声起音。
        const val PROBE_MILLIS = 250
        const val PROBE_GRACE_MILLIS = 200L
        // vc196：连续**精确全零**多少秒触发声道升级。取 1s（远早于 5s 的换源阈值），
        // 让修复能落在一次 Siri 会话之内；判据是 peak 恒等于 0 而非「近静音」，
        // 真实麦克风即使静场也必有 1 LSB 以上底噪 ⇒ 1s 全零只可能是数字静音，误触发≈0。
        const val ZERO_PEAK_PROMOTE_SECONDS = 1
        // vc197：解耦缓冲深度 = 8 帧（160ms @20ms/帧）。够吸收 read 抖动与线程调度延迟，
        // 又不至于把 Siri 的响应拖慢到可感知（>200ms 用户会觉得"它根本没听见"）。
        const val RING_FRAMES = 8
        // vc197：root 直采兜底的触发线。read 阻塞 800ms 即试直采（iPhone 约 2s 拆流，
        // 必须抢在前面）；6s 内没有出现过任何非零音频则无条件试一次，覆盖未知形态。
        const val ROOT_FALLBACK_BLOCK_MILLIS = 800L
        const val ROOT_FALLBACK_FORCE_MILLIS = 6_000L
        // 每会话最多两次：第一次可能撞上 EBUSY（车机语音服务占着采集节点），
        // 隔几秒它释放后再试一次通常就通了。
        const val MAX_ROOT_ATTEMPTS = 2
        // root 直采连续多少秒全零就结束本轮（换节点/参数由看门狗重试发起）。
        // 取 6s：足够覆盖一次 Siri 的静默等待，又不至于把整场会话耗在一条死通路上。
        const val ROOT_SILENT_WINDOWS = 6
        /** 两次 root 直采尝试的最小间隔（su + ALSA open 本身要 1~2s）。 */
        const val ROOT_RETRY_INTERVAL_MILLIS = 4_000L
        /** pacer 发送失败最多落几条诊断（防止持续故障刷爆日志）。 */
        const val MAX_PACER_SEND_FAILURES = 3
        /**
         * vc198：首块数据「快得可疑」的界线（毫秒）。
         * 真采集要等 HAL 填满一个 period（FE6 成功那次 272ms，@48k/1ch、period 2048B ≈ 21ms，
         * 实际还要算上 HAL 起流与调度）；`mSilenceBuffer` 是现成缓冲，5~15ms 就返回。
         * 取 20ms：低于它基本只可能是「没采、直接拿填充」。
         */
        const val FIRST_READ_SILENCE_MS = 20L
        // vc197：β 形态（read 阻塞）的救援阈值。20ms 一帧的流上 600ms 没返回就是 30 帧缺失，
        // 已经不正常；而 iPhone 在收不到音频包后约 2s 就发 TEARDOWN，所以必须抢在它前面。
        const val RESCUE_BLOCK_MILLIS = 600L
        const val MAX_RESCUE_ATTEMPTS = 3
        // vc87（fe6t5）：连续零读阈值——AudioRecord.read 恒返 0 的 HAL 出数失败形态
        // （fe6t3 日志 uplink started 后无任何 level window/tx 心跳即此形态），凑不满
        // 整帧连 e5t1 的 peak==0 静默自愈都触发不了。约 1s 内 50 次零读即按采集失效轮换源。
        const val ZERO_READ_HEAL_THRESHOLD = 50
        // vc156（#866 容错）：编码失败最多落 3 条诊断（对齐上游 MAX_REPORTED_FAILURES）。
        const val MAX_ENCODE_FAILURE_REPORTS = 3
        // vc193：采集态看门狗。3s 对齐 iPhone 拆 Siri 流的 3.31s（超时即判采集失效）；
        // 阻塞判据 2s（正常一帧 20ms，2s 足够排除抖动）；500ms 轮询对电量的影响可忽略。
        const val FIRST_PACKET_WATCHDOG_MS = 3_000L
        const val READ_BLOCKED_MILLIS = 2_000L
        const val WATCHDOG_POLL_MILLIS = 500L
        val EMPTY_BYTES = ByteArray(0)
    }
}
