package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Process
import android.util.Log
import android.view.Surface
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.airplay.toHexString
import com.shilapi.xcertplay.media.BandwidthMonitor
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

/**
 * Android rendering backend for the CarPlay media engine. Video frames are
 * decoded with MediaCodec onto a Surface; audio streams are decoded to PCM and
 * played through AudioTrack. Call [close] when the session tears down.
 */
class AndroidMediaSink(
    surface: Surface? = null,
    private val videoWidth: Int = 1280,
    private val videoHeight: Int = 720,
    private val preferSoftwareHevcDecoder: Boolean = false,
    private val advancedAudioChannelMapping: Boolean = false,
    onScreenStreamActiveChanged: ((Int, Boolean) -> Unit)? = null,
    private val mediaBufferMillis: Int = MediaAudioBuffer.DEFAULT_MILLIS,
    private val onAudioDiagnostic: (String) -> Unit = {},
    /**
     * M6c：CarPlay 电话流（[AudioChannel.PHONE]）出现/消失时回调 true/false。
     * 只用来通知"通话音频正在播/已停"，具体走听筒还是扬声器由 App 侧（AudioManager）决定——
     * sink 不持 Context，也不该知道设备路由策略。
     */
    private val onPhoneChannelActive: ((Boolean) -> Unit)? = null,
    /**
     * M6d：导航播报时把音乐压低到的百分比（30~70；>=100 视为不压低）。
     * 用取值函数而不是常量，这样 App 改设置后下一次播报就能生效，无需重建 sink。
     */
    private val navigationDuckPercent: () -> Int = { 100 },
    /**
     * M6x（参考 fork 0.2.7 音频路由）：媒体/导航音频的传统 stream type 覆盖。
     * 0=自动 usage 路由（默认，行为不变）；1~20=旧 AudioTrack 构造的 streamType
     * （车机音频流编号，ROM 拒绝时自动回退 usage 构建）。通话流不受此控制。
     */
    private val mediaStreamOverride: Int = 0,
    private val navStreamOverride: Int = 0,
    /**
     * 1.0.16（P0-1，3e 无声修复）：「通话声音走扬声器」勾选时，telephony 下行 track
     * 直接按 USAGE_MEDIA（STREAM_MUSIC）路由，绕开华为等 ROM 上「无真实蜂窝通话时
     * VOICE_COMMUNICATION+IN_COMMUNICATION 的 DSP 路径静默」问题。取值函数——
     * 每次建流现读，改设置后下一条 telephony 流即生效（iPhone 通话中会重建流）。
     */
    private val callsRoutedToMedia: () -> Boolean = { false },
    /**
     * 1.1.2（EU5 双向无声）：Siri/助手语音走媒体通道开关。ASSISTANT（speechrecognition）
     * 与 NAVIGATION（type=101 default）usage 在 PATEO 等老 HAL 上静音——勾选后改按
     * USAGE_MEDIA 路由（媒体音量，已实测出声）。取值函数——每次建流现读，改设置即生效。
     */
    private val voiceRoutedToMedia: () -> Boolean = { false },
    /**
     * 1.0.11（FE-6 拉伸）：解码器实际输出尺寸回调（crop 优先于 KEY_WIDTH/HEIGHT）。
     * iPhone 实际编码尺寸可能与声明的流尺寸不一致（如超宽屏 + 高缩放被编码器封顶），
     * UI 层据此把 SurfaceView 等比适配，避免硬拉伸变形。
     */
    private val onVideoOutputSizeChanged: ((Int, Int) -> Unit)? = null,
) : MediaSink {
    private val screenStateLock = Any()
    private val activeScreenTypes = mutableSetOf<Int>()
    private val defaultSurface = surface
    @Volatile private var screenStreamActiveChanged = onScreenStreamActiveChanged
    private val surfaces = ConcurrentHashMap<Int, Surface>()
    private val videoDecoders = ConcurrentHashMap<Int, VideoDecoder>()
    private val audioRenderers = ConcurrentHashMap<Int, AudioRenderer>()
    /**
     * M6i：本会话"建过流的 `<type>/<audioType>`"汇总。
     * 起因：T43/T44 都没等到 `通话声音路由 …`，无法判断是"没打电话"还是"压根没建 telephony 流"——
     * 有这行汇总就能一眼看出本机到底建了哪些 audioType（如 `110/video` 不算，音频形如 `102/media`）。
     */
    private val audioStreamTypes =
        java.util.Collections.synchronizedSet(java.util.LinkedHashSet<String>())
    private val microphoneUplinks = ConcurrentHashMap<Int, MicrophoneUplink>()
    private val pendingVideoCodec = ConcurrentHashMap<Int, VideoCodec>()
    private val videoRecoveryHandlers = ConcurrentHashMap<Int, () -> Unit>()
    private val videoDiagnosticHandlers = ConcurrentHashMap<Int, (String) -> Unit>()
    private val recoveryPending = AtomicBoolean(false)
    private val recoveryExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "carplay-video-recovery").apply { isDaemon = true }
    }

    override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) {
        videoRecoveryHandlers[type] = handler
    }

    override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) {
        videoDiagnosticHandlers[type] = handler
    }

    private fun requestVideoRecovery(type: Int) {
        if (!recoveryPending.compareAndSet(false, true)) return
        try {
            recoveryExecutor.execute {
                try { videoRecoveryHandlers[type]?.invoke() }
                catch (error: Exception) { Log.w("xcertplay-usb", "Video keyframe request failed", error) }
                finally { recoveryPending.set(false) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { recoveryPending.set(false) }
    }

    fun setSurface(type: Int, surface: Surface) {
        surfaces[type] = surface
        videoDecoders[type]?.setSurface(surface)
    }

    fun clearSurface(type: Int, surface: Surface) {
        if (surfaces.remove(type, surface)) videoDecoders[type]?.setSurface(null)
    }

    fun setScreenStreamActiveChangedListener(listener: ((Int, Boolean) -> Unit)?) {
        synchronized(screenStateLock) {
            screenStreamActiveChanged = listener
            activeScreenTypes.forEach { listener?.invoke(it, true) }
        }
    }

    override fun onVideoCodec(type: Int, codec: VideoCodec) {
        pendingVideoCodec[type] = codec
    }

    override fun onVideoConfig(type: Int, codecData: ByteArray) {
        val codec = pendingVideoCodec[type] ?: VideoCodec.H264
        videoDecoder(type).configure(codec, codecData)
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
        videoDecoder(type).submit(naluBytes)
    }

    override fun onScreenStreamActive(type: Int, active: Boolean) {
        if (!active) {
            videoRecoveryHandlers.remove(type)
            videoDiagnosticHandlers.remove(type)
            videoDecoders.remove(type)?.close()
            pendingVideoCodec.remove(type)
        }
        synchronized(screenStateLock) {
            if (active) activeScreenTypes.add(type) else activeScreenTypes.remove(type)
            screenStreamActiveChanged?.invoke(type, active)
        }
    }

    override fun onAudioStarted(type: Int, format: AudioFormat, firstSample: Int) {
        noteAudioStreamType(type, format.audioType)
        audioRenderer(type, format).start()
    }

    /** 新出现的 audioType 才打一行汇总，避免刷屏。 */
    private fun noteAudioStreamType(type: Int, audioType: String) {
        if (!audioStreamTypes.add("$type/$audioType")) return
        val summary = synchronized(audioStreamTypes) { audioStreamTypes.joinToString(",") }
        onAudioDiagnostic("Audio: stream types so far=$summary")
    }

    override fun onAudioRtp(type: Int, format: AudioFormat, rtp: ByteArray, sample: Int) {
        audioRenderer(type, format).submit(rtp, sample)
    }

    override fun onAudioStopped(type: Int) {
        audioRenderers.remove(type)?.close()
    }

    override fun onMicrophoneStarted(type: Int, config: MicrophoneConfig) {
        // API16 适配：ConcurrentHashMap.computeIfAbsent 是 API 24+，改两段式 get + putIfAbsent。
        var uplink = microphoneUplinks[type]
        if (uplink == null) {
            val created = MicrophoneUplink(config, onAudioDiagnostic)
            val raced = microphoneUplinks.putIfAbsent(type, created)
            uplink = raced ?: created
        }
        if (!uplink.start()) microphoneUplinks.remove(type, uplink)
    }

    override fun onMicrophoneStopped(type: Int) {
        microphoneUplinks.remove(type)?.close()
    }

    fun close() {
        synchronized(screenStateLock) {
            activeScreenTypes.forEach { screenStreamActiveChanged?.invoke(it, false) }
            activeScreenTypes.clear()
            screenStreamActiveChanged = null
        }
        videoDecoders.values.forEach(VideoDecoder::close)
        videoDecoders.clear()
        videoRecoveryHandlers.clear()
        videoDiagnosticHandlers.clear()
        recoveryExecutor.shutdownNow()
        audioRenderers.values.forEach(AudioRenderer::close)
        audioRenderers.clear()
        microphoneUplinks.values.forEach(MicrophoneUplink::close)
        microphoneUplinks.clear()
    }

    private fun videoDecoder(type: Int): VideoDecoder {
        // API16 适配：ConcurrentHashMap.computeIfAbsent 是 API 24+，改两段式 get + putIfAbsent。
        val existing = videoDecoders[type]
        if (existing != null) return existing
        val created = VideoDecoder(
            type,
            surfaces[type] ?: defaultSurface,
            videoWidth,
            videoHeight,
            preferSoftwareHevcDecoder,
            requestKeyFrame = { requestVideoRecovery(type) },
            report = { videoDiagnosticHandlers[type]?.invoke(it) },
            onOutputSizeChanged = onVideoOutputSizeChanged,
        )
        return videoDecoders.putIfAbsent(type, created) ?: created
    }

    @Synchronized
    private fun audioRenderer(type: Int, format: AudioFormat): AudioRenderer {
        val existing = audioRenderers[type]
        if (existing?.format == format) return existing
        existing?.close()
        return AudioRenderer(
            format,
            advancedAudioChannelMapping,
            mediaBufferMillis,
            onAudioDiagnostic,
            ::onRendererChannelState,
            mediaStreamOverride,
            navStreamOverride,
            // 1.0.16（P0-1）：建流瞬间现读「通话声音走扬声器」，命中则通话下行按 MEDIA 路由。
            callsRoutedToMedia(),
            // 1.1.2：建流瞬间现读「Siri/语音提示走媒体音量」，命中则 ASSISTANT/NAVIGATION 按 MEDIA 路由。
            voiceRoutedToMedia(),
        ).also { audioRenderers[type] = it }
    }

    // ---------- M6d：导航压低音乐 ----------

    private val duckLock = Any()
    @Volatile private var navigationActive = false

    /**
     * 某条音频流的"通道活跃/结束"通知（由 AudioRenderer 在建/销毁输出轨时回调）。
     * PHONE 交给 App 决定听筒/扬声器；NAVIGATION 与 MEDIA 用来做 duck。
     */
    private fun onRendererChannelState(channel: AudioChannel?, active: Boolean) {
        when (channel) {
            AudioChannel.PHONE -> onPhoneChannelActive?.invoke(active)
            AudioChannel.NAVIGATION -> {
                onAudioDiagnostic(
                    "Audio: navigation ${if (active) "start" else "end"} → " +
                        "音乐音量 ${if (active) "${navigationDuckPercent().coerceIn(0, 100)}%" else "100%"}",
                )
                synchronized(duckLock) {
                    navigationActive = active
                    applyDuckLocked()
                }
            }
            AudioChannel.MEDIA -> if (active) synchronized(duckLock) { applyDuckLocked() }
            else -> Unit
        }
    }

    /** 只在导航活跃时压低 MEDIA 轨（导航自身不受影响）。 */
    private fun applyDuckLocked() {
        val factor = if (navigationActive) {
            (navigationDuckPercent().coerceIn(0, 100)) / 100f
        } else {
            1f
        }
        audioRenderers.values.forEach { renderer ->
            if (renderer.channel == AudioChannel.MEDIA) renderer.setPlaybackVolume(factor)
        }
    }
}

/** Serial MediaCodec video decoder: one worker owns configure and frame feeding. */
private class VideoDecoder(
    streamType: Int,
    surface: Surface?,
    private val width: Int,
    private val height: Int,
    private val preferSoftwareHevcDecoder: Boolean,
    private val requestKeyFrame: () -> Unit,
    private val report: (String) -> Unit,
    private val onOutputSizeChanged: ((Int, Int) -> Unit)? = null,
) : Closeable {
    private val queue = VideoDecodeQueue()
    @Volatile private var running = true
    @Volatile private var decoder: MediaCodec? = null
    private var outputSurface: Surface? = surface
    private var lastConfig: VideoJob.Config? = null
    private var renderedFrameLogged = false
    private var submittedFrameLogged = false
    private var duplicateConfigLogged = false
    // M6e：解码器清单只打一次（确认本机有哪些解码器、软解开关到底有没有生效）。
    private var decoderInventoryLogged = false
    // M6j：解码失败的**首个**异常要带 message + 堆栈落盘（T44 只打了类名，等于没有证据）。
    private val decoderErrorDetailLogged = AtomicBoolean(false)
    /**
     * M6j：软解在本机试过并失败了。T44 实测：C8815 上 `OMX.google.h264.decoder` 能创建、
     * 能 configure/start，但**每一帧都抛异常**（`decoder error IllegalStateException`、`shown=0.0fps`）
     * ⇒ 勾了"强制软解"反而永久无画面。这里记住失败，之后自动回落默认硬解，并明确提示。
     */
    @Volatile private var softwareDecoderDisabled = false
    // M6j：软解探测日志只打一次（T44 里它每帧刷一次，45 条把日志淹了）。
    private var softwareProbeLogged = false
    private val referenceChain = VideoReferenceChain()
    // 1.1.3（绿线修复②）：恢复/重建/surface 重接后抑制渲染，直到下一个 IDR 真正喂进解码器，
    // 挡掉厂商 OMX flush 后吐出的残留脏缓冲（8815 短暂绿线的另一来源）。
    @Volatile private var renderHold = true
    // M6k：最近一次喂入的帧是不是随机访问帧（IDR）。解码失败时落盘，用于判读"是否从 P 帧续解"。
    @Volatile private var lastFrameKeyFrame = true
    // 1.0.29（AC8227 倒车闪屏）：恢复看门狗计数——关键帧连发无渲染 = control 通道疑似死亡。
    private var keyframeRequestsWithoutRender = 0
    private var keyframeStallReported = false
    private var lastKeyFrameRequestNs = 0L
    // The main screen keeps the historical log format; other screens are labelled.
    private val stats = VideoStats(if (streamType == 110) "" else " stream=$streamType")
    private val thread = Thread(::run, "carplay-video").apply { isDaemon = true; start() }

    fun configure(codec: VideoCodec, codecData: ByteArray) {
        queue.offer(VideoJob.Config(codec, codecData))
    }

    fun submit(nalus: ByteArray) {
        stats.onReceived(nalus.size)
        BandwidthMonitor.onVideoBytes(nalus.size)
        queue.offer(VideoJob.Frame(nalus))
    }

    fun setSurface(surface: Surface?) {
        queue.offer(VideoJob.SurfaceChanged(surface))
    }

    override fun close() {
        running = false
        thread.interrupt()
    }

    private fun run() {
        // M6v：视频解码线程抬到 nice -8（THREAD_PRIORITY_URGENT_DISPLAY，即 URGENT_AUDIO 附近的解码档）：
        // 高于系统默认(0)/后台，仍低于音频写轨(-16)与收包(-19)，保证弱机上「收包 > 音频 > 视频」的调度次序；
        // 部分 ROM 拒绝该调用，拒绝就记一行继续。
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        } catch (error: Throwable) {
            report("Video: setThreadPriority(-8) refused ${error.javaClass.simpleName}")
        }
        try {
            while (running) {
                val job = queue.poll(5)
                try {
                    when (job) {
                        is VideoJob.Config -> configureDecoder(job)
                        is VideoJob.Frame -> {
                            if (System.nanoTime() - job.receivedNs > MAX_FRAME_AGE_NS) {
                                // M6f：积压只是"我们落后了"，解码器没坏 —— 丢过时帧 + 要关键帧即可，
                                // 不再重降解码器（重配要走厂商 OMX，还得再等关键帧，纯属白花时间）。
                                stats.onBacklog()
                                queue.discardFrames()
                                recover(
                                    "video backlog exceeded ${MAX_FRAME_AGE_NS / 1_000_000} ms",
                                    rebuildDecoder = false,
                                )
                            } else feed(job.nalus)
                        }
                        is VideoJob.SurfaceChanged -> changeSurface(job.surface)
                        // 队列溢出同样是"落后"，不是解码器故障。
                        is VideoJob.Resync -> recover("video queue overflow", rebuildDecoder = false)
                        null -> Unit
                    }
                    decoder?.let(::drainOutput)
                    stats.logIfDue()?.let(report)
                    if (referenceChain.needsKeyFrame && lastConfig != null && outputSurface != null) requestKeyFrameIfDue()
                } catch (error: Exception) {
                    if (running) Log.e(TAG, "video decoder job failed: ${job?.javaClass?.simpleName}", error)
                    if (running) report(errorReport("decoder error", error))
                    fallBackFromSoftwareDecoder(error)
                    releaseDecoder()
                    referenceChain.reset()
                    renderHold = true
                    requestKeyFrameIfDue()
                } catch (error: Throwable) {
                    // 码率/帧长突变时可能抛 Error（OutOfMemoryError 等），catch(Exception) 兜不住，
                    // 逃出 run() 即线程死亡 → 进程被杀（用户反馈"视频流突然变大就闪退"）。
                    // 这里按普通解码失败处理：释放解码器、等下一个关键帧，保住进程。
                    if (running) Log.e(TAG, "video decoder job crashed: ${job?.javaClass?.simpleName}", error)
                    if (running) report(errorReport("decoder crashed", error))
                    fallBackFromSoftwareDecoder(error)
                    releaseDecoder()
                    referenceChain.reset()
                    renderHold = true
                    requestKeyFrameIfDue()
                }
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } finally {
            releaseDecoder()
        }
    }

    private fun configureDecoder(config: VideoJob.Config) {
        val previous = lastConfig
        if (
            decoder != null &&
            previous?.codec == config.codec &&
            previous.codecData.contentEquals(config.codecData)
        ) {
            if (!duplicateConfigLogged) {
                duplicateConfigLogged = true
                Log.i(TAG, "video decoder config unchanged; keeping existing decoder")
            }
            return
        }
        lastConfig = config
        duplicateConfigLogged = false
        releaseDecoder()
        referenceChain.reset()
        renderHold = true
        val surface = outputSurface ?: return
        val codec = config.codec
        val codecData = config.codecData
        val mime = if (codec == VideoCodec.H265) MediaFormat.MIMETYPE_VIDEO_HEVC
        else MediaFormat.MIMETYPE_VIDEO_AVC
        if (!decoderInventoryLogged) {
            decoderInventoryLogged = true
            report(
                "Video: $mime decoders=${decoderNamesFor(mime)} " +
                    "software=${softwareDecoderNameFor(mime) ?: "none"} " +
                    "preferSoftware=$preferSoftwareHevcDecoder " +
                    "probe=${codecProbeNote()}",
            )
        }
        val csd = if (codec == VideoCodec.H265) {
            MediaCodecSupport.hevcCodecSpecificData(codecData).takeIf { it.isNotEmpty() }
                ?.let { listOf(it) } ?: emptyList()
        } else {
            val (sps, pps) = MediaCodecSupport.avcParameterSets(codecData)
            listOfNotNull(
                sps.takeIf { it.isNotEmpty() }?.let { START_CODE + it },
                pps.takeIf { it.isNotEmpty() }?.let { START_CODE + it },
            )
        }
        // Some vendor hardware decoders (e.g. MediaTek/Mali) reject the tuned parameters
        // with BAD_VALUE. Fall back to a minimal format on the default hardware decoder path;
        // createDecoder already selects a software decoder when preferSoftwareHevcDecoder.
        val attempts = listOf(
            DecoderAttempt(tuned = true),
            DecoderAttempt(tuned = false),
        )
        var next: MediaCodec? = null
        for (attempt in attempts) {
            next = tryConfigure(mime, csd, surface, attempt)
            if (next != null) break
        }
        if (next == null) {
            report("decoder configuration failed mime=$mime size=${width}x$height")
        }
        decoder = next
        renderedFrameLogged = false
        submittedFrameLogged = false
        if (next != null) {
            report("decoder=${codecName(next)} mime=$mime size=${width}x$height")
            Log.i(
                TAG,
                "video decoder configured name=${codecName(next)} mime=$mime size=${width}x$height",
            )
        }
    }

    private data class DecoderAttempt(val tuned: Boolean)

    private fun buildFormat(mime: String, csd: List<ByteArray>, tuned: Boolean): MediaFormat =
        MediaFormat.createVideoFormat(mime, width, height).apply {
            if (tuned) {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            csd.forEachIndexed { index, bytes -> setByteBuffer("csd-$index", ByteBuffer.wrap(bytes)) }
        }

    private fun tryConfigure(
        mime: String,
        csd: List<ByteArray>,
        surface: Surface,
        attempt: DecoderAttempt,
    ): MediaCodec? {
        var candidate: MediaCodec? = null
        return try {
            val format = buildFormat(mime, csd, attempt.tuned)
            val codec = createDecoder(mime)
            candidate = codec
            if (attempt.tuned && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                codec.codecInfo.getCapabilitiesForType(mime).isFeatureSupported("low-latency")) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            codec.configure(format, surface, null, 0)
            codec.start()
            codec
        } catch (error: Exception) {
            runCatching { candidate?.release() }
            Log.w(
                TAG,
                "video decoder configure failed tuned=${attempt.tuned} mime=$mime size=${width}x$height",
                error,
            )
            null
        }
    }

    private fun createDecoder(mime: String): MediaCodec {
        // Old head units (Android 5-9) often ship broken vendor decoders: green frames,
        // ~300 ms/frame stalls and endless keyframe recovery. Prefer a stock software
        // decoder for both AVC and HEVC when the user opts in. API < 29 has no
        // MediaCodecInfo.isSoftwareOnly(), so fall back to name prefixes there.
        if (preferSoftwareHevcDecoder && !softwareDecoderDisabled) {
            // API16 适配：MediaCodecList(REGULAR_CODECS).codecInfos 是 API 21+；
            // 低版本既无该常量也无 isEncoder/supportedTypes，无法按 mime 筛软解，
            // 直接跳过优化分支，交由 createDecoderByType 兜底。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val software = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
                    !it.isEncoder && it.isSoftwareDecoder() && mime in it.supportedTypes
                }
                if (software != null) {
                    try {
                        return MediaCodec.createByCodecName(software.name)
                    } catch (error: Exception) {
                        Log.w(TAG, "software decoder unavailable name=${software.name} mime=$mime", error)
                    }
                }
            } else {
                // M6e：API16 也要能挑软解（低版本分支就是靠软解绕开厂商硬解异常）。
                // M6i：T43 曾误判"清单枚举恒为空"；T44 已证实 `MediaCodecList.static` 能拿到 38 个
                // 组件（`MediaCodec.static` 才是 NoSuchMethodException）。这里仍走 probe 兜底。
                val name = probeSoftwareDecoderName(mime)
                if (!softwareProbeLogged) {
                    softwareProbeLogged = true
                    report(
                        "Video: software decoder probe mime=$mime name=${name ?: "none"} " +
                            "probe=${codecProbeNote()}",
                    )
                }
                if (name != null) {
                    try {
                        return MediaCodec.createByCodecName(name)
                    } catch (error: Exception) {
                        Log.w(TAG, "software decoder unavailable name=$name mime=$mime", error)
                    }
                }
            }
        }
        return MediaCodec.createDecoderByType(mime)
    }

    private fun MediaCodecInfo.isSoftwareDecoder(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isSoftwareOnly
        } else {
            name.startsWith("OMX.google.") || name.startsWith("c2.android.") ||
                name.startsWith("c2.soft.")
        }

    private fun changeSurface(surface: Surface?) {
        if (outputSurface === surface) return
        outputSurface = surface
        if (surface == null) {
            releaseDecoder()
            Log.i(TAG, "video decoder detached from surface")
            return
        }
        val codec = decoder
        if (codec != null) {
            // API16 适配：setOutputSurface 是 API 23+，低版本没有该能力，直接重建解码器。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    codec.setOutputSurface(surface)
                    Log.i(TAG, "video decoder output surface updated")
                    // 新 surface 上首帧必须来自干净 IDR（旧 surface 的在途帧到新 surface 上就是花屏/绿线）。
                    renderHold = true
                    // 1.0.29（AC8227 倒车闪屏）：surface 重新接线立即请求关键帧，
                    // 不等首帧 P 帧被拒才触发 —— 缩短倒车结束后的黑屏恢复期。
                    forceKeyFrameRequest("surface reattached")
                    return
                } catch (error: Exception) {
                    Log.w(TAG, "video decoder output surface update failed; reconfiguring", error)
                }
            }
        }
        releaseDecoder()
        lastConfig?.let(::configureDecoder)
        if (decoder != null) forceKeyFrameRequest("surface reattached after reconfigure")
    }

    /**
     * 1.0.29（AC8227 倒车闪屏）：绕过 1s 节流的关键帧请求——resume 是明确的新起点，
     * 每次 surface 重接都应立即要 IDR，而不是沿用上一轮的节流窗口。
     */
    private fun forceKeyFrameRequest(reason: String) {
        Log.i(TAG, "video keyframe requested early ($reason)")
        lastKeyFrameRequestNs = System.nanoTime()
        requestKeyFrame()
    }

    private fun feed(nalus: ByteArray) {
        val annexB = MediaCodecSupport.toAnnexB(nalus)
        val config = lastConfig ?: return
        if (outputSurface == null) return
        if (annexB.isEmpty()) { recover("invalid video access unit"); return }
        val keyFrame = MediaCodecSupport.isRandomAccess(annexB, config.codec)
        lastFrameKeyFrame = keyFrame
        if (!referenceChain.accepts(keyFrame)) {
            requestKeyFrameIfDue()
            return
        }
        if (decoder == null) configureDecoder(config)
        val codec = decoder ?: return
        if (!submittedFrameLogged) {
            submittedFrameLogged = true
            Log.i(
                TAG,
                "video decoder first input avcc=${nalus.size} annexB=${annexB.size} " +
                    "head=${annexB.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }}",
            )
        }
        val index = VideoInputPump.acquire(
            running = { running }, drain = { drainOutput(codec) },
            dequeue = { codec.dequeueInputBuffer(INPUT_TIMEOUT_US) },
        )
        if (index < 0) { stats.onInputStalled(); recover("video decoder input stalled"); return }
        val input = codecInputBuffer(codec, index)
        if (input == null) {
            // M6j：`checkNotNull` 抛的就是 IllegalStateException —— 很可能正是 T44 里每帧
            // `decoder error IllegalStateException` 的来源（API<21 走 getInputBuffers()[index]）。
            // 现在把它显式报出来（含数组长度），而不是被泛化成"某处 IllegalStateException"。
            report(
                "Video: input buffer unavailable index=$index " +
                    "inputBuffers=${runCatching { codec.inputBuffers?.size }.getOrNull()} " +
                    "api=${Build.VERSION.SDK_INT}",
            )
            releaseDecoder()
            referenceChain.reset()
            renderHold = true
            requestKeyFrameIfDue()
            return
        }
        input.clear()
        if (annexB.size <= input.remaining()) {
            input.put(annexB)
            codec.queueInputBuffer(index, 0, annexB.size, System.nanoTime() / 1000, 0)
            referenceChain.onQueued()
            // IDR 已入解码器：解除渲染抑制（其后输出才是干净画面）。
            if (keyFrame) renderHold = false
        } else {
            recover("video frame exceeded codec input capacity")
            return
        }
        drainOutput(codec)
    }

    /**
     * M6j：`decoder error ${简单类名}` 这种日志在 T44 把原因完全丢掉了（只有 logcat 有堆栈）。
     * 这里把 **message** 一定带上，并把**第一条**的堆栈前几帧写进文件日志。
     */
    private fun errorReport(prefix: String, error: Throwable): String {
        val detail = error.message?.let { ": $it" } ?: ""
        // M6k：带上最近一帧的类型，区分"丢帧后从 P 帧续解"与"厂商解码器自身出错"。
        val frameKind = if (lastFrameKeyFrame) "IDR" else "non-IDR"
        if (!decoderErrorDetailLogged.compareAndSet(false, true)) {
            return "$prefix ${error.javaClass.simpleName}$detail lastFrame=$frameKind; waiting for keyframe"
        }
        val frames = error.stackTrace.take(ERROR_STACK_FRAMES)
            .joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        return "$prefix ${error.javaClass.simpleName}$detail lastFrame=$frameKind; waiting for keyframe stack=$frames"
    }

    /**
     * M6j：软解一旦在真机上失败，就**永久回落到默认解码器**，并明确告诉用户原因。
     * 否则"勾了强制软解"= 永久无画面（T44：`shown=0.0fps`、每帧 `IllegalStateException`）。
     */
    private fun fallBackFromSoftwareDecoder(error: Throwable) {
        if (!preferSoftwareHevcDecoder || softwareDecoderDisabled) return
        softwareDecoderDisabled = true
        report(
            "Video: 软解在本机不可用（${error.javaClass.simpleName}" +
                (error.message?.let { ": $it" } ?: "") + "），已自动回落默认解码器",
        )
    }

    private fun recover(reason: String, rebuildDecoder: Boolean = true) {
        Log.w(TAG, "Video recovery: $reason; waiting for keyframe")
        stats.onRecovery()
        report("recovery: $reason; waiting for keyframe" + if (rebuildDecoder) "" else " (flush decoder)")
        renderHold = true
        if (rebuildDecoder) {
            // Recreate with codec-specific data: flush can discard CSD before the first output.
            releaseDecoder()
        } else {
            // M6k：不重建解码器时，必须把解码器里"在途"的旧输入/输出帧清掉。否则恢复后
            // 它会把丢帧前残留的画面吐出来，与新关键帧混排 —— 观感就是花屏/跳帧。
            flushDecoder()
        }
        referenceChain.reset()
        requestKeyFrameIfDue()
    }

    /** M6k：清空解码器在途帧（不释放组件）。失败则退化为重建，保证下次喂帧前状态一致。 */
    private fun flushDecoder() {
        val codec = decoder ?: return
        try {
            codec.flush()
            stats.onFlush()
        } catch (error: Exception) {
            Log.w(TAG, "video decoder flush failed; rebuilding instead", error)
            releaseDecoder()
        }
    }

    private fun requestKeyFrameIfDue() {
        val now = System.nanoTime()
        if (lastKeyFrameRequestNs != 0L && now - lastKeyFrameRequestNs < 1_000_000_000L) return
        lastKeyFrameRequestNs = now
        // 1.0.29（AC8227 倒车闪屏）：恢复看门狗——关键帧连发 3 次仍无渲染帧，
        // 大概率不是解码问题而是 control 通道已死（forceKeyFrame 发不达）。
        // 落一条诊断日志，供会话级恢复逻辑定位（AC8227 实测：发到第 8 秒才暴露 Socket closed）。
        keyframeRequestsWithoutRender++
        if (keyframeRequestsWithoutRender == KEYFRAME_STALL_REPORT_AFTER && !keyframeStallReported) {
            keyframeStallReported = true
            report(
                "recovery stalled: keyframe requested $keyframeRequestsWithoutRender times " +
                    "with no rendered frame; control channel may be dead",
            )
        }
        requestKeyFrame()
    }

    private fun drainOutput(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        // 1.1.3（绿线修复① + burst 节流）：原实现每个输出帧都立刻 release(render=true)，
        // Wi-Fi 断流后突发 N 帧时，旧 MTK overlay/显示管线跟不上连续合成 → 短暂绿线，
        // 中间帧还都是肉眼看不见的闪过（纯浪费合成算力）。
        // 改法：只持有最新一个输出缓冲，被更新帧取代的旧帧静默归还（render=false），
        // 等没有新帧时才真正渲染最新帧 —— burst 期间合成量 N→1，撕裂源消失。
        var pendingIndex = -1
        var pendingEos = false
        fun renderPending(): Boolean {
            if (pendingIndex >= 0) {
                // 1.1.3 修复（8815 011235 无画面定案②）：**不得按 info.size 过滤**——Surface 输出模式下
                // 高通 OMX 的输出 BufferInfo.size 恒为 0（数据直送 surface，size 无意义），
                // 按 size>0 过滤会把每一帧都当"零长度填充"静默丢掉 → shown=0 全黑。
                // 渲染判据只看 surface 与 renderHold。
                val render = outputSurface != null && !renderHold
                codec.releaseOutputBuffer(pendingIndex, render)
                pendingIndex = -1
                if (render) {
                    stats.onRendered()
                    // 1.0.29：恢复看门狗复位——有画面了，通道健康。
                    keyframeRequestsWithoutRender = 0
                    keyframeStallReported = false
                    if (!renderedFrameLogged) {
                        renderedFrameLogged = true
                        report("first frame rendered")
                        Log.i(TAG, "video decoder rendered first frame")
                    }
                }
            }
            pendingEos = false
            return false
        }
        while (running) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                // 1.1.3 修复（8815 010252 无画面定案①）：TRY_AGAIN 必须**无条件退出**本轮 drain。
                // 上一版写成 `if (renderPending()) return`，无挂起帧时 renderPending 返回 false，
                // 循环继续 → dequeue 又是 TRY_AGAIN → **忙死循环**：解码线程卡死在本函数，
                // 队列无人消费（无 stats/无首帧/无关键帧请求），网络层视频包堆积——1.1.3 首包即中招。
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> { renderPending(); return }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    renderPending()
                    logOutputFormat(codec.outputFormat)
                }
                index >= 0 -> {
                    // 上一帧被更新帧取代：静默归还，不上合成器。
                    if (pendingIndex >= 0) codec.releaseOutputBuffer(pendingIndex, false)
                    pendingIndex = index
                    pendingEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                }
                // 负值未知码（防御）：与 TRY_AGAIN 同样必须退出，防忙循环。
                else -> { renderPending(); return }
            }
        }
    }

    private fun logOutputFormat(format: MediaFormat) {
        // 1.0.11（FE-6 拉伸）：实际编码尺寸以 crop 为准（部分解码器 KEY_WIDTH/HEIGHT 是对齐后的 stride 尺寸）。
        val cropLeft = format.intOrNull("crop-left")
        val cropTop = format.intOrNull("crop-top")
        val cropRight = format.intOrNull("crop-right")
        val cropBottom = format.intOrNull("crop-bottom")
        val codedWidth = if (cropLeft != null && cropRight != null) cropRight - cropLeft + 1
            else format.intOrNull(MediaFormat.KEY_WIDTH) ?: width
        val codedHeight = if (cropTop != null && cropBottom != null) cropBottom - cropTop + 1
            else format.intOrNull(MediaFormat.KEY_HEIGHT) ?: height
        try {
            if (codedWidth > 0 && codedHeight > 0) onOutputSizeChanged?.invoke(codedWidth, codedHeight)
        } catch (error: Throwable) {
            Log.w(TAG, "video output size callback failed", error)
        }
        report("output format requested=${width}x${height} " +
            "coded=${format.intOrNull(MediaFormat.KEY_WIDTH)}x${format.intOrNull(MediaFormat.KEY_HEIGHT)} " +
            "crop=${format.intOrNull("crop-left")},${format.intOrNull("crop-top")}," +
            "${format.intOrNull("crop-right")},${format.intOrNull("crop-bottom")} " +
            "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
            "color=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)}/${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)}/${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}")
        Log.i(
            TAG,
            "video decoder output format " +
                "size=${format.intOrNull(MediaFormat.KEY_WIDTH)}x" +
                "${format.intOrNull(MediaFormat.KEY_HEIGHT)} " +
                "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} " +
                "slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
                "standard=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)} " +
                "range=${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)} " +
                "transfer=${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}",
        )
    }

    @Synchronized
    private fun releaseDecoder() {
        val codec = decoder
        decoder = null
        if (codec != null) {
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val MAX_INPUT_SIZE = 8 * 1024 * 1024
        const val INPUT_TIMEOUT_US = 10_000L
        // M6j：解码失败首次落盘时附带的堆栈帧数（够定位"是哪一步抛的"即可）。
        const val ERROR_STACK_FRAMES = 4
        // 1.0.29：恢复看门狗阈值——关键帧请求达此次数仍无渲染帧，报通道疑似死亡（只报一次）。
        const val KEYFRAME_STALL_REPORT_AFTER = 3
        // 可容忍的最大排队延迟：帧从到达到被解码超过它，就判为"积压"——丢队列 + 等关键帧。
        // M6b：250ms → 350ms。C8815 上解码偶发被抢占（processMaxUs 峰值 111ms/包），250ms 会被
        // 误判成积压而频繁 forceKeyFrame（每次触发画面冻结到关键帧到达，主观上更卡）；
        // 350ms 留出余量，只在真积压（网络/渲染真的跟不上）时才丢帧。
        const val MAX_FRAME_AGE_NS = 350_000_000L
        val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    }
}

private fun MediaFormat.intOrNull(key: String): Int? =
    if (!containsKey(key)) {
        null
    } else {
        try {
            getInteger(key)
        } catch (_: Exception) {
            null
        }
    }

/** Decodes AAC-LC/Opus to PCM and plays it, or plays wired LPCM directly. */
private class AudioRenderer(
    val format: AudioFormat,
    private val advancedAudioChannelMapping: Boolean,
    private val mediaBufferMillis: Int,
    private val report: (String) -> Unit,
    /** M6c/M6d：通道活跃状态变化（PHONE=听筒/扬声器、NAVIGATION/MEDIA=duck）。 */
    private val onChannelState: ((AudioChannel?, Boolean) -> Unit)? = null,
    /** M6x：媒体/导航各自的传统 stream type 覆盖（0=usage 路由，1~20=旧构造 streamType）。 */
    private val mediaStreamOverride: Int = 0,
    private val navStreamOverride: Int = 0,
    /**
     * 1.0.16（P0-1）：true 时 PHONE 通道按 USAGE_MEDIA（STREAM_MUSIC）路由扬声器，
     * 不再走 VOICE_COMMUNICATION+IN_COMMUNICATION（华为等 ROM 无真实通话时该 DSP 路径静默）。
     */
    private val callsRoutedToMedia: Boolean = false,
    /**
     * 1.1.2（EU5 双向无声）：true 时 ASSISTANT（speechrecognition）与 NAVIGATION（type=101
     * default）通道按 USAGE_MEDIA 路由——PATEO 等老 HAL 对这两个 usage 静音。
     */
    private val voiceRoutedToMedia: Boolean = false,
) : Closeable {
    private data class AudioPacket(val rtp: ByteArray, val sample: Int)

    private val queue = LinkedBlockingQueue<AudioPacket>(MAX_QUEUED_PACKETS)
    @Volatile private var running = true
    @Volatile private var started = false
    private var codec: MediaCodec? = null
    // M5a：本机没有 OPUS 解码器（API16）时改用内置 libopus 软件解码。两者互斥：
    // codec != null 走 MediaCodec，否则走它；都为 null 才是"这条流注定静音"。
    private var nativeOpusDecoder: NativeOpusDecoder? = null
    private var firstSoftwareOpusLogged = false
    private var track: AudioTrack? = null
    private var pcm = ByteArray(64 * 1024)
    private var playbackStarted = false
    private var prebufferBytes = 0
    private var startThresholdBytes = 0
    // M6v：低水位补缓冲（EasyPlay 逆向方案）。queuedMs 由「累计写入 vs 播放头」估算（API16 无
    // getUnderrunCount），跌破水位 = 即将 rebuffer：只 pause 不 flush、不重置 track，等上层补包、
    // prebuffer 重新攒到 startThreshold 后再 play，避免 AudioFlinger 跑空产生 underrun 咔哒声。
    // watermark 取 min(capacity/4, start/2, capacity-start)：末项保证「残量 + 起播阈值 < 容量」，
    // 否则暂停态下 prebuffer 写满整条 paused track 会永久阻塞（write 对暂停且满的轨不返回）。
    private var lowWatermarkMs = 0L
    private var watermarkPauseNs = 0L
    private var lowWatermarkLogged = false
    private var fadeApplied = false
    // M6f：丢包补偿（填静音）之后，下一段真实音频要淡入，消除"静音→有声"的爆音；
    // lastSampleValue 用来在填静音前做一小段淡出（硬切静音同样会"啪"一下）。
    private var fadeInAfterGap = false
    private var lastSampleValue = 0
    private var droppedPacketsLogged = false
    private var firstAacPayloadLogged = false
    private var firstOpusShortPacketLogged = false
    private var firstInputQueuedLogged = false
    private var inputQueued = 0
    private var inputDropped = 0
    private var outputBuffers = 0
    private var firstPcmLogged = false
    private val packetsReceived = AtomicInteger()
    private val packetsDropped = AtomicInteger()
    private val lastArrivalNs = AtomicLong()
    private val maxArrivalGapMs = AtomicLong()
    private var maxWriteMs = 0L
    // M5a：软件 OPUS 解码耗时（弱机性能验收用，随 stats 一起清零）。
    private var maxDecodeMs = 0L
    private var statsWindowStartNs = 0L
    private var statsLastUnderruns = 0
    private var bytesPerSecond = 0
    // M6a：弱机优化的量化依据（CPU 占用 / 线程数）。与视频 stats 各自独立窗口。
    private val processLoad = ProcessLoadSampler()
    private val bufferProgress = AudioBufferProgress(if (format.channels >= 2) 4 else 2)
    private var underrunsAtPlaybackStart = 0
    private var lastPcmWriteNs = 0L
    private var rebufferCount = 0
    // P0: RTP frame-loss compensation state (see compensateMissingFrames).
    private var lastTimestampUs = -1L
    private var lastIncrementUs = 0L
    private var expectedTimestampUs = -1L
    private var silenceScratch = ByteArray(0)
    // M6k：丢包遮蔽（PLC）。保存最近约 20ms 的真实 PCM，缺口时平铺它并整体淡出，
    // 代替"整段填静音"——2.4GHz 成片丢包下，纯静音缺口听感就是"声音断续"。
    private var lastPcmTail = ByteArray(0)
    private var packetsCompensated = 0L
    private var gapUsCompensated = 0L
    // M6c/M6d：本条流落在哪个 CarPlay 音频通道（audioRoute() 里写），以及是否已把"通道活跃"通知出去。
    @Volatile private var routeChannel: AudioChannel? = null
    private var channelActiveNotified = false

    /** 供 sink 侧判断是否需要 duck（MEDIA）/路由（PHONE）。 */
    val channel: AudioChannel? get() = routeChannel

    /** M6d：调本轨的播放音量（1.0 = 原音量）。导航播报时把音乐压到 duck 值。 */
    fun setPlaybackVolume(factor: Float) {
        val current = track ?: return
        runCatching { current.setVolume(factor.coerceIn(0f, 1f)) }
    }
    private val thread = Thread({
        // 音频解码 + 写轨：抬到 AUDIO —— 低于收包（URGENT_AUDIO），但高于视频/系统默认，
        // 避免它和视频解码抢 CPU 时被拖出 underrun。
        // M6v：部分 ROM 拒绝 setThreadPriority（SecurityException/IllegalArgumentException），
        // 拒绝时记一行继续跑。值保持 -16：已优于方案目标 nice -8，不降级。
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        } catch (error: Throwable) {
            Log.w(TAG, "audio thread setThreadPriority refused", error)
            report("Audio: setThreadPriority(-16) refused ${error.javaClass.simpleName} audioType=${format.audioType}")
        }
        run()
    }, "carplay-audio").apply { isDaemon = true }

    fun start() {
        if (started) return
        started = true
        thread.start()
    }

    fun submit(rtp: ByteArray, sample: Int) {
        if (started) {
            packetsReceived.incrementAndGet()
            BandwidthMonitor.onAudioBytes(rtp.size)
            val now = System.nanoTime()
            val previous = lastArrivalNs.getAndSet(now)
            // API16 适配：AtomicLong.accumulateAndGet 是 API 24+，改 CAS 循环。
            if (previous != 0L) updateMaxArrivalGap((now - previous) / 1_000_000L)
        }
        if (!started || !queue.offer(AudioPacket(rtp, sample))) {
            if (started) packetsDropped.incrementAndGet()
            if (started && !droppedPacketsLogged) {
                droppedPacketsLogged = true
                Log.w(TAG, "audio queue full; dropping newest packets to bound latency")
                report("Audio: queue full audioType=${format.audioType}")
            }
        }
    }

    override fun close() {
        running = false
        thread.interrupt()
    }

    private fun run() {
        try {
            when (format.codec) {
                AudioCodecKind.AAC_LC -> configureCodec(MediaFormat.MIMETYPE_AUDIO_AAC)
                AudioCodecKind.OPUS -> {
                    configureCodec(MediaFormat.MIMETYPE_AUDIO_OPUS)
                    // M5a：API16 本机没有 OMX.google.opus.decoder（API21+ 才有），而 /info **必须**
                    // 声明 OPUS（T26/T33/T34 三次证伪"清 OPUS 换 PCM"）⇒ 导航/通话/Siri 的 OPUS
                    // 下行只能靠内置 libopus 解出来，否则一直静音（T35：rx 有包、outputBuffersTotal=0）。
                    if (codec == null) {
                        nativeOpusDecoder = NativeOpusDecoder.create(format.sampleRate, format.channels)
                        report(
                            "Audio: opus software decoder " +
                                (if (nativeOpusDecoder != null) "ready" else "unavailable") +
                                " type=${format.payloadType} rate=${format.sampleRate} " +
                                "channels=${format.channels}（libopus）",
                        )
                    }
                }
                AudioCodecKind.LPCM -> Unit
            }
            createTrack()
            while (running) {
                queue.poll(AUDIO_POLL_MILLIS, TimeUnit.MILLISECONDS)?.let(::handle)
                // Output becomes ready asynchronously, including after the last packet of a burst.
                // Waiting for the next UDP packet can strand decoded sound for hundreds of ms.
                codec?.let(::drainCodec)
                maintainPlaybackBuffer()
                logStatsIfDue()
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } catch (error: Exception) {
            if (running) {
                Log.e(TAG, "audio renderer worker failed", error)
                report("Audio: renderer failed audioType=${format.audioType} error=${error.javaClass.simpleName}")
            }
        } catch (error: Throwable) {
            // Error（NoClassDefFoundError / UnsatisfiedLinkError / OutOfMemoryError …）不会进上面的
            // catch(Exception)；逃出去就是线程死亡 → 进程被系统杀掉（实测 C8815 无声闪退、零日志）。
            // 这里兜住并写进文件日志，让"哪条流、什么类型"可判读。
            if (running) {
                Log.e(TAG, "audio renderer worker crashed", error)
                report("Audio: renderer crashed audioType=${format.audioType} error=${error.javaClass.name}")
            }
        } finally {
            runCatching { logStatsIfDue(force = true) }
            release()
        }
    }

    private fun configureCodec(mime: String) {
        val mediaFormat = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, mime)
            setInteger(MediaFormat.KEY_SAMPLE_RATE, format.sampleRate)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, format.channels)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_IS_ADTS, 1)
                setByteBuffer("csd-0", ByteBuffer.wrap(aacAudioSpecificConfig()))
            } else {
                setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
                setByteBuffer("csd-1", ByteBuffer.wrap(opusCodecDelay()))
                setByteBuffer("csd-2", ByteBuffer.wrap(opusSeekPreRoll()))
            }
        }
        if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
            Log.i(
                TAG,
                "audio AAC config rate=${format.sampleRate} channels=${format.channels} " +
                    "csd0=${aacAudioSpecificConfig().toHexString()}",
            )
        }
        codec = try {
            // 面包屑（2026-10-03 闪退排查）：创建解码器是唯一会进入厂商 OMX/HAL 的操作，
            // 先落地"即将创建"，进程若死在这里，日志最后一行就是它。
            //
            // M4m 修复：**本机没有这个 mime 的解码器时绝不调用 createDecoderByType**。
            // T32 实证（C8815/API16，一开导航就闪退）：日志最后一条是
            // `Audio: creating decoder mime=audio/opus codec=OPUS type=100`，之后进程消失、无 `[crash]`
            // ⇒ 厂商 OMX 层原生崩溃，Java 侧 catch 拦不住。先查组件列表（不实例化）再决定创建。
            if (!codecComponentExists(mime, encoder = false)) {
                report("Audio: decoder absent，跳过创建 mime=$mime codec=${format.codec} type=${format.payloadType}（本机无该组件，创建会原生崩进程）")
                null
            } else {
                report("Audio: creating decoder mime=$mime codec=${format.codec} type=${format.payloadType}")
                MediaCodec.createDecoderByType(mime).also {
                    it.configure(mediaFormat, null, null, 0)
                    it.start()
                    Log.i(TAG, "audio decoder configured mime=$mime name=${codecName(it)}")
                    report("Audio: decoder ready mime=$mime name=${codecName(it)} type=${format.payloadType}")
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "audio decoder configuration failed mime=$mime", error)
            report("Audio: decoder unavailable mime=$mime codec=${format.codec} type=${format.payloadType}")
            null
        }
    }

    private fun createTrack() {
        // 面包屑：AudioTrack 构造会进入 AudioFlinger/HAL，是第二个可能原生崩溃的点。
        report("Audio: creating track type=${format.payloadType} codec=${format.codec} " +
            "rate=${format.sampleRate} channels=${format.channels} audioType=${format.audioType}")
        val encoding = AndroidAudioFormat.ENCODING_PCM_16BIT
        val channelMask = if (format.channels >= 2) AndroidAudioFormat.CHANNEL_OUT_STEREO
        else AndroidAudioFormat.CHANNEL_OUT_MONO
        val minBuffer = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, encoding)
        if (minBuffer <= 0) {
            Log.e(TAG, "AudioTrack buffer size unavailable rate=${format.sampleRate} channels=${format.channels}")
            report("Audio: track unavailable (minBufferSize=$minBuffer) rate=${format.sampleRate}")
            return
        }
        val plan = MediaAudioBuffer.plan(format.audioType, format.sampleRate, format.channels, minBuffer, mediaBufferMillis)
        val frameBytes = if (format.channels >= 2) 4 else 2
        bytesPerSecond = format.sampleRate * frameBytes
        // AudioTrack.Builder()/AudioFormat.Builder()/getBufferSizeInFrames() are API 23+; calling
        // them on Android 5.x throws NoSuchMethodError (an Error no catch() intercepts) as soon
        // as the first audio stream starts. API 23+ keeps the builder (zero behaviour change on
        // every tested device); API 21/22 falls back to the legacy constructor with a stream type
        // approximating the mapped AudioAttributes usage, using the requested buffer as capacity.
        // API16 适配（A3）：AudioAttributes 类本身是 API 21，4.1/4.4 上构造即 NoClassDefFoundError。
        // 先算纯 Int 的 usage/contentType（AudioAttributes.USAGE_* 为编译期内联常量，16 上安全），
        // 仅 >=23 分支构造 AudioAttributes 对象；4.x 与 5.x 统一走旧 AudioTrack 构造。
        val route = audioRoute()
        // M6x：媒体/导航流的传统 streamType 覆盖（参考 fork）。0=走现有 usage 路由路径；
        // 1~20=旧 AudioTrack 构造（车机音频流编号，部分车机 ROM 只认传统 stream type 才
        // 路由到指定输出）。构造抛异常或 state != STATE_INITIALIZED（ROM 拒绝）时回退
        // usage 构建，保证总有声。通话/助手等其他通道不做覆盖。
        val streamOverride = when (routeChannel) {
            AudioChannel.MEDIA -> mediaStreamOverride
            AudioChannel.NAVIGATION -> navStreamOverride
            else -> 0
        }
        var routeLabel: String? = null
        val built = if (streamOverride in 1..20) {
            routeLabel = "streamType=$streamOverride"
            val legacy = try {
                @Suppress("DEPRECATION")
                AudioTrack(
                    streamOverride,
                    format.sampleRate,
                    channelMask,
                    encoding,
                    plan.trackBufferBytes,
                    AudioTrack.MODE_STREAM,
                )
            } catch (error: Throwable) {
                Log.w(TAG, "streamType=$streamOverride rejected by this ROM; falling back to usage-based track", error)
                report("Audio: streamType=$streamOverride rejected by this ROM (${error.javaClass.simpleName}); " +
                    "falling back to usage routing audioType=${format.audioType}")
                null
            }
            if (legacy != null && legacy.state == AudioTrack.STATE_INITIALIZED) {
                legacy
            } else {
                routeLabel = "streamType=$streamOverride(fallback=usage)"
                Log.w(TAG, "streamType=$streamOverride rejected by this ROM (state=${legacy?.state}); " +
                    "falling back to usage-based track")
                report("Audio: streamType=$streamOverride rejected by this ROM (state=${legacy?.state}); " +
                    "falling back to usage routing audioType=${format.audioType}")
                legacy?.release()
                buildUsageRoutedTrack(route, channelMask, encoding, plan.trackBufferBytes)
            }
        } else {
            buildUsageRoutedTrack(route, channelMask, encoding, plan.trackBufferBytes)
        }
        track = built
        val capacityBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            built.bufferSizeInFrames * frameBytes
        } else {
            plan.trackBufferBytes
        }
        startThresholdBytes = MediaAudioBuffer.startBytesFor(plan.startBytes, capacityBytes, PREBUFFER_WRITE_CHUNK_BYTES)
        // M6v：低水位 = min(容量 25%, 起播阈值一半, 容量-起播阈值)。第三项防死锁：暂停态补缓冲时
        // 「残量(<水位) + 起播阈值」必须小于容量，否则 blocking write 会卡死在写满的 paused track 上。
        lowWatermarkMs = minOf(
            capacityBytes * 1000L / bytesPerSecond / 4,
            startThresholdBytes * 1000L / bytesPerSecond / 2,
            (capacityBytes - startThresholdBytes) * 1000L / bytesPerSecond,
        )
        // M6x：streamType 覆盖生效时在诊断行带上最终路由结论（回退后标 fallback=usage）。
        val routeFragment = routeLabel?.let { "route=$it " } ?: ""
        report("Audio: ready audioType=${format.audioType} codec=${format.codec} " +
            "rate=${format.sampleRate} channels=${format.channels} " + routeFragment +
            "bufferMs=${capacityBytes * 1000L / bytesPerSecond} startMs=${startThresholdBytes * 1000L / bytesPerSecond} " +
            "lowWaterMs=$lowWatermarkMs")
        Log.i(
            TAG,
            "audio track prepared type=${format.payloadType} audioType=${format.audioType} " +
                "codec=${format.codec} " +
                "rate=${format.sampleRate} channels=${format.channels} " + routeFragment +
                "buffer=${capacityBytes * 1000L / bytesPerSecond}ms start=${startThresholdBytes * 1000L / bytesPerSecond}ms",
        )
        // M6c/M6d：输出轨一建好就通知"通道活跃"（PHONE→App 切听筒/扬声器；NAVIGATION→压低音乐）。
        if (routeChannel != null && !channelActiveNotified) {
            channelActiveNotified = true
            onChannelState?.invoke(routeChannel, true)
        }
    }

    /**
     * 现有 usage 路由构建路径（M6x 覆盖回退也走这里）：API 23+ 走 AudioTrack.Builder，
     * 更低版本走旧构造（按 usage 反推 streamType）。API 23+ 分支仍在运行时 SDK 守卫内。
     */
    private fun buildUsageRoutedTrack(
        route: AudioRoute,
        channelMask: Int,
        encoding: Int,
        trackBufferBytes: Int,
    ): AudioTrack =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // API16 适配：AudioAttributes/AudioTrack.Builder 构造内联在此 API 23+ 守卫内。
            val attributes = AudioAttributes.Builder()
                .setUsage(route.usage)
                .setContentType(route.contentType)
                .build()
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AndroidAudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(format.sampleRate)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setBufferSizeInBytes(trackBufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            val streamType = if (route.usage == AudioAttributes.USAGE_VOICE_COMMUNICATION) {
                AudioManager.STREAM_VOICE_CALL
            } else {
                AudioManager.STREAM_MUSIC
            }
            // 反推结果落进文件日志：这是"导航走媒体音量还是语音音量"的最终判据。
            report(
                "Audio: legacy track streamType=$streamType usage=${route.usage} " +
                    "(3=STREAM_MUSIC 媒体音量, 0=STREAM_VOICE_CALL 语音/通话音量)",
            )
            @Suppress("DEPRECATION")
            AudioTrack(
                streamType,
                format.sampleRate,
                channelMask,
                encoding,
                trackBufferBytes,
                AudioTrack.MODE_STREAM,
            )
        }

    private fun aacAudioSpecificConfig(): ByteArray {
        val frequencyIndex = MediaCodecSupport.aacFrequencyIndex(format.sampleRate)
        val value = (AAC_OBJECT_TYPE_LC shl 11) or
            (frequencyIndex shl 7) or
            (format.channels.coerceIn(1, 7) shl 3)
        return byteArrayOf((value ushr 8).toByte(), value.toByte())
    }

    /** 纯 Int 音频路由选择：usage/contentType 均为编译期内联常量，API 16 安全。 */
    private data class AudioRoute(val usage: Int, val contentType: Int)

    /** API16 适配（A3）：路由计算不触碰 API 21 的 AudioAttributes 对象。 */
    private fun audioRoute(): AudioRoute {
        val mode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        val selection = AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = mode,
        )
        val usage = usageFor(selection.channel)
        val contentType = contentTypeFor(selection.contentType)
        routeChannel = selection.channel
        Log.i(
            TAG,
            "audio route type=${format.payloadType} audioType=${format.audioType} " +
                "mode=$mode channel=${selection.channel} " +
                "usage=$usage contentType=$contentType",
        )
        // 路由结论同时写入文件日志：此前只进 logcat，无 adb 时"这条流算哪种音量"无从取证。
        report(
            "Audio: route type=${format.payloadType} audioType=${format.audioType} " +
                "mode=$mode channel=${selection.channel} " +
                "usage=$usage contentType=$contentType",
        )
        return AudioRoute(usage, contentType)
    }

    private fun usageFor(channel: AudioChannel): Int = when (channel) {
        AudioChannel.MEDIA -> AudioAttributes.USAGE_MEDIA
        // 1.0.16（P0-1）：通话走扬声器开关打开 → 按 MEDIA 路由（STREAM_MUSIC），绕开
        // 华为等 ROM 上 IN_COMMUNICATION DSP 静默路径；走听筒语义时保持原 VOICE 路由。
        AudioChannel.PHONE ->
            if (callsRoutedToMedia) AudioAttributes.USAGE_MEDIA
            else AudioAttributes.USAGE_VOICE_COMMUNICATION
        AudioChannel.ASSISTANT ->
            // 1.1.2：助手/Siri 语音在老 HAL 上静音 → 开关打开时走媒体通道（媒体音量）。
            if (voiceRoutedToMedia) AudioAttributes.USAGE_MEDIA
            else AudioAttributes.USAGE_ASSISTANT
        AudioChannel.NAVIGATION ->
            // 1.1.2：导航/Siri 提示音同受开关控制（NAVIGATION usage 同样存在老 HAL 静音场景）。
            if (voiceRoutedToMedia) AudioAttributes.USAGE_MEDIA
            else AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
    }

    private fun contentTypeFor(contentType: AudioContentType): Int = when (contentType) {
        AudioContentType.MUSIC -> AudioAttributes.CONTENT_TYPE_MUSIC
        AudioContentType.SPEECH -> AudioAttributes.CONTENT_TYPE_SPEECH
    }

    /** Minimal OpusHead CSD for the mono 48 kHz stream CarPlay negotiates. */
    private fun opusHead(): ByteArray {
        val head = ByteArray(19)
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(head, 0)
        head[8] = 1
        head[9] = format.channels.toByte()
        head[10] = 0x38
        head[11] = 0x01
        head[12] = format.sampleRate.toByte()
        head[13] = (format.sampleRate ushr 8).toByte()
        head[14] = (format.sampleRate ushr 16).toByte()
        head[15] = (format.sampleRate ushr 24).toByte()
        return head
    }

    private fun opusCodecDelay(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_CODEC_DELAY_NANOS)
            .array()

    private fun opusSeekPreRoll(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_SEEK_PRE_ROLL_NANOS)
            .array()

    private fun handle(packet: AudioPacket) {
        val rtp = packet.rtp
        val timestampUs = sampleTimestampUs(packet.sample)
        compensateMissingFrames(timestampUs)
        when (format.codec) {
            AudioCodecKind.LPCM -> writePcm(byteSwapS16(rtp.copyOfRange(12, rtp.size)))
            AudioCodecKind.AAC_LC -> {
                val accessUnit = rtp.copyOfRange(12, rtp.size)
                if (accessUnit.isNotEmpty()) {
                    if (!firstAacPayloadLogged) {
                        firstAacPayloadLogged = true
                        Log.i(
                            TAG,
                            "audio AAC access unit bytes=${accessUnit.size} " +
                                "head=${accessUnit.copyOf(minOf(accessUnit.size, 16)).toHexString()}",
                        )
                    }
                    feedCodec(
                        MediaCodecSupport.adtsFrame(accessUnit, format.sampleRate, format.channels),
                        timestampUs,
                    )
                }
            }
            AudioCodecKind.OPUS -> {
                val accessUnit = rtp.copyOfRange(12, rtp.size)
                if (accessUnit.size < MIN_OPUS_PACKET_BYTES) {
                    if (!firstOpusShortPacketLogged) {
                        firstOpusShortPacketLogged = true
                        Log.i(
                            TAG,
                            "audio Opus skipping short packet bytes=${accessUnit.size} " +
                                "head=${accessUnit.toHexString()}",
                        )
                    }
                    return
                }
                // M5a：本机无 OPUS 解码器时走内置 libopus；解出的 PCM 是 16-bit 小端，
                // 与 AudioTrack 的 ENCODING_PCM_16BIT 一致，**不需要** LPCM 那样的 byteSwap。
                val decoder = nativeOpusDecoder
                if (decoder != null) {
                    val startedNs = System.nanoTime()
                    // 上限给到 40ms（20ms 帧的 2 倍）：opus_decode 会按包内实际帧数解码并返回真实采样数。
                    val decodedPcm = decoder.decode(accessUnit, format.sampleRate / 25)
                    val decodeMs = (System.nanoTime() - startedNs) / 1_000_000L
                    if (decodeMs > maxDecodeMs) maxDecodeMs = decodeMs
                    if (decodedPcm != null) {
                        outputBuffers++
                        if (!firstSoftwareOpusLogged) {
                            firstSoftwareOpusLogged = true
                            report(
                                "Audio: opus software decoded bytes=${decodedPcm.size} " +
                                    "type=${format.payloadType} decodeMs=$decodeMs（libopus）",
                            )
                        }
                        writePcm(decodedPcm)
                    }
                    return
                }
                feedCodec(accessUnit, timestampUs)
            }
        }
    }

    private fun sampleTimestampUs(sample: Int): Long =
        (sample.toLong() and 0xffff_ffffL) * 1_000_000L / format.sampleRate

    /**
     * P0: fill holes left by lost RTP packets so the decoded PCM timeline stays continuous.
     *
     * The RTP timestamps of packets that actually arrive are contiguous; a positive gap against
     * the expected timestamp means whole frames never reached this device (logs show
     * lateOrDuplicate=0, so lost frames never arrive late). For such gaps we synthesize silence
     * of exactly the missing duration and write it to the track, instead of letting the decoder
     * emit a hole that AudioTrack renders as an audible dropout.
     *
     * A legitimate stream pause (user stops audio, Siri burst ends) also produces a timestamp
     * jump, so compensation is bounded: only gaps up to [MAX_COMPENSATE_US] while packets keep
     * flowing within [MAX_PACKET_IDLE_NS] are filled; larger jumps reset the tracking state.
     */
    private fun compensateMissingFrames(timestampUs: Long) {
        if (lastTimestampUs < 0L) {
            lastTimestampUs = timestampUs
            expectedTimestampUs = timestampUs
            return
        }
        val incrementUs = timestampUs - lastTimestampUs
        if (incrementUs > 0L && incrementUs <= MAX_FRAME_US) lastIncrementUs = incrementUs
        val gapUs = timestampUs - expectedTimestampUs
        lastTimestampUs = timestampUs
        if (gapUs <= 0L || lastIncrementUs <= 0L) {
            expectedTimestampUs = timestampUs + lastIncrementUs
            return
        }
        val streamActive = System.nanoTime() - lastArrivalNs.get() <= MAX_PACKET_IDLE_NS
        if (streamActive && gapUs <= MAX_COMPENSATE_US) {
            val missingBytes = (gapUs * bytesPerSecond / 1_000_000L).toInt()
            if (missingBytes > 0) {
                writePcmConcealment(gapUs, missingBytes)
                packetsCompensated++
                gapUsCompensated += gapUs
                if (packetsCompensated <= MAX_COMPENSATE_LOG || packetsCompensated % 100 == 0L) {
                    Log.i(
                        TAG,
                        "audio gap compensated codec=${format.codec} gapMs=${gapUs / 1000} " +
                            "bytes=$missingBytes total=$packetsCompensated",
                    )
                }
            }
        }
        expectedTimestampUs = timestampUs + lastIncrementUs
    }

    /**
     * M6k：丢包遮蔽。**按缺口长度分流**——这是 T47 实测（C8815，12:36 日志）后修正的关键点：
     * 该机上音频 UDP 丢包极重（`seqForwardGaps` 每 5s 160~210、`gapCompMs≈3000/5000ms`），
     * 缺口平均 **约 200ms**（≈10 个 AAC 帧）。若一律"平铺上一帧并整体淡出"，20ms 的样本
     * 会被重复十几次 → 变成持续的低频嗡嗡声（用户报的"音频异常"）。
     *
     * 因此：
     * - 短缺口（≤ [MAX_CONCEAL_REPEAT_US]）平铺上一帧，听感比"断一小段静音"连续；
     * - 长缺口（> 该阈值）退回 [writePcmSilence]，只做 5ms 淡出后留静音——不制造持续音调。
     * 还没有可复用样本时（起播即丢包）同样退回静音。
     */
    private fun writePcmConcealment(gapUs: Long, bytes: Int) {
        val tailSamples = lastPcmTail.size / 2
        if (tailSamples == 0 || gapUs > MAX_CONCEAL_REPEAT_US) {
            writePcmSilence(bytes)
            return
        }
        if (bytes <= 0 || bytesPerSecond <= 0) return
        if (silenceScratch.size < bytes) silenceScratch = ByteArray(bytes)
        val sampleCount = bytes / 2
        for (index in 0 until sampleCount) {
            val value = readS16(lastPcmTail, (index % tailSamples) * 2).toLong() *
                (sampleCount - index) / sampleCount
            writeS16(silenceScratch, index * 2, value)
        }
        if (bytes % 2 != 0) silenceScratch[bytes - 1] = 0
        fadeInAfterGap = false
        writePcm(silenceScratch, 0, bytes)
        // 遮蔽段之后回来的真实音频要淡入，避免"遮蔽尾巴→真实音频"的爆音。
        fadeInAfterGap = true
    }

    /** M6k：把本次写入的最后一段 PCM 存进 [lastPcmTail]（约 20ms，偶数长度），供丢包遮蔽复用。 */
    private fun rememberPcmTail(data: ByteArray, offset: Int, written: Int) {
        if (written <= 0 || bytesPerSecond <= 0) return
        val tailBytes = maxOf(2, (bytesPerSecond / 50 / 2) * 2)
        val copy = minOf(written, tailBytes)
        if (lastPcmTail.size != copy) lastPcmTail = ByteArray(copy)
        System.arraycopy(data, offset + written - copy, lastPcmTail, 0, copy)
    }

    private fun writePcmSilence(bytes: Int) {
        val track = track ?: return
        if (bytes <= 0 || bytesPerSecond <= 0) return
        if (silenceScratch.size < bytes) silenceScratch = ByteArray(bytes)
        java.util.Arrays.fill(silenceScratch, 0, bytes, 0)
        // M6f：不再"硬切静音"——先花约 5ms 从上一个采样线性落到 0，减少爆音。
        val fadeSamples = minOf((bytes - bytes % 2) / 2, maxOf(1, format.sampleRate / 200))
        val last = lastSampleValue
        for (index in 0 until fadeSamples) {
            writeS16(silenceScratch, index * 2, last.toLong() * (fadeSamples - index) / fadeSamples)
        }
        fadeInAfterGap = false
        writePcm(silenceScratch, 0, bytes)
        // 静音之后再回来的真实音频要淡入（开标志，由 writePcm 消费）。
        fadeInAfterGap = true
    }

    private fun feedCodec(payload: ByteArray, presentationTimeUs: Long) {
        val codec = codec ?: return
        val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index < 0) {
            inputDropped++
            if (inputDropped == 1) {
                Log.w(
                    TAG,
                    "audio decoder input unavailable codec=${format.codec} " +
                        "queued=$inputQueued dropped=$inputDropped",
                )
            }
            return
        }
        val input = codecInputBuffer(codec, index) ?: return
        input.clear()
        if (payload.size <= input.remaining()) {
            input.put(payload)
            codec.queueInputBuffer(index, 0, payload.size, presentationTimeUs, 0)
            inputQueued++
            if (!firstInputQueuedLogged) {
                firstInputQueuedLogged = true
                Log.i(
                    TAG,
                    "audio decoder first input codec=${format.codec} bytes=${payload.size} " +
                        "head=${payload.copyOf(minOf(payload.size, 16)).toHexString()}",
                )
            }
        } else {
            codec.queueInputBuffer(index, 0, 0, 0, 0)
            inputDropped++
        }
        drainCodec(codec)
    }

    private fun drainCodec(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                index >= 0 -> {
                    val size = info.size
                    if (size > 0) {
                        outputBuffers++
                        if (outputBuffers == 1 || outputBuffers % DECODED_BUFFER_LOG_INTERVAL == 0) {
                            Log.i(
                                TAG,
                                "audio decoder output codec=${format.codec} " +
                                    "buffers=$outputBuffers bytes=$size " +
                                    "queued=$inputQueued dropped=$inputDropped",
                            )
                        }
                    }
                    if (size > 0) {
                        val output = codecOutputBuffer(codec, index)
                        if (output != null) {
                            if (size > pcm.size) pcm = ByteArray(size)
                            output.position(info.offset)
                            output.limit(info.offset + size)
                            output.get(pcm, 0, size)
                            writePcm(pcm, 0, size)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun writePcm(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        val track = track ?: return
        if (!firstPcmLogged && length > 0) {
            firstPcmLogged = true
            val end = minOf(data.size, offset + minOf(length, 16))
            Log.i(
                TAG,
                "audio first PCM type=${format.payloadType} bytes=$length " +
                    "head=${data.copyOfRange(offset, end).toHexString()}",
            )
        }
        // M6f：起播淡入一次；此后每段"丢包补偿静音"之后也淡入一次，避免爆音。
        if (!fadeApplied || fadeInAfterGap) {
            fadeInAfterGap = false
            fadeApplied = true
            applyFadeIn(data, offset, length)
        }
        var written = 0
        while (written < length && running) {
            val writeLength = if (playbackStarted) {
                length - written
            } else {
                minOf(length - written, PREBUFFER_WRITE_CHUNK_BYTES)
            }
            val writeStarted = System.nanoTime()
            // API16 适配：带 mode 的 AudioTrack.write 是 API 23+；低版本用阻塞语义的 int 重载（API 3）。
            val count = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                track.write(data, offset + written, writeLength, AudioTrack.WRITE_BLOCKING)
            } else {
                track.write(data, offset + written, writeLength)
            }
            maxWriteMs = maxOf(maxWriteMs, (System.nanoTime() - writeStarted) / 1_000_000L)
            if (count <= 0) break
            written += count
            bufferProgress.written(count)
            lastPcmWriteNs = System.nanoTime()
            if (!playbackStarted) {
                prebufferBytes += count
                if (prebufferBytes >= startThresholdBytes) {
                    startPlayback(track)
                    Log.i(TAG, "audio playback started type=${format.payloadType}")
                }
            }
        }
        // M6f：记住最后一个采样值，丢包填静音前用它做一小段淡出（避免硬切静音的爆音）。
        if (length >= 2) lastSampleValue = readS16(data, offset + length - 2)
        // M6k：记住刚写入的真实 PCM，供下一次丢包遮蔽复用（复用上一帧 + 淡出）。
        rememberPcmTail(data, offset, written)
    }

    private fun startPlayback(track: AudioTrack) {
        underrunsAtPlaybackStart = trackUnderruns(track)
        track.play()
        playbackStarted = true
        watermarkPauseNs = 0L
    }

    /**
     * M6v：queuedMs 估算（EasyPlay 逆向方案）。API16 没有 getUnderrunCount（API 24+），
     * 用「累计写入字节 - 播放头帧数 × 帧宽」估算 track 里还没播完的毫秒数；
     * 播放头跨 wrap 由 [AudioBufferProgress.queuedBytes] 处理，负值钳到 0（头读数异常时只退化、不误停）。
     */
    private fun queuedMillis(track: AudioTrack): Long {
        if (bytesPerSecond <= 0) return 0L
        return bufferProgress.queuedBytes(track.playbackHeadPosition) * 1000L / bytesPerSecond
    }

    /** 只暂停不 flush、不重置 track：已排队的 PCM 原样保留，恢复时接着播（消 underrun 咔哒声的关键）。 */
    private fun pauseForRebuffer(track: AudioTrack) {
        track.pause()
        playbackStarted = false
        prebufferBytes = 0
        rebufferCount++
    }

    /** 短促交互音不做低水位停轨：源发完即止，水位逻辑只会把残量卡成静音。 */
    private fun lowWatermarkExempt(audioType: String): Boolean =
        audioType == "speechrecognition" || audioType == "alert"

    private fun maintainPlaybackBuffer() {
        val track = track ?: return
        if (bufferProgress.shouldRebuffer(format.audioType, playbackStarted,
                trackUnderruns(track) > underrunsAtPlaybackStart, queue.isEmpty(), track.playbackHeadPosition)) {
            // The hardware buffer has actually drained. Pause without flushing or discarding PCM,
            // then use the configured start threshold again when music resumes.
            pauseForRebuffer(track)
        } else if (playbackStarted && queue.isEmpty() && lowWatermarkMs > 0 &&
            queuedMillis(track) < lowWatermarkMs && !lowWatermarkExempt(format.audioType)) {
            // M6v：低水位补缓冲（API<24 上 underrunCount 恒 0、上面的 reactive 判定失效，这是主路径）：
            // queuedMs 跌破水位就先主动 pause（不 flush），等上层补包、prebuffer 攒到 startThreshold 再 play。
            // 仅在压缩包队列已空时触发——队列还有货说明瓶颈在本地解码，写循环自己会追上，无需停轨。
            // 1.0.9：speechrecognition/alert 是短促交互音（iPhone 发完就停，如 Siri「嘟」），
            // 水位停轨只会让它反复 rebuffer 直至静音（214032 日志 rx=172 包 playing=false 实证）
            // —— 这类流宁可放行残量（可能一次轻微咔哒），也绝不停轨。
            if (!lowWatermarkLogged) {
                lowWatermarkLogged = true
                report("Audio: low watermark audioType=${format.audioType} queuedMs=${queuedMillis(track)} " +
                    "thresholdMs=$lowWatermarkMs → pause+rebuffer (no flush)")
            }
            pauseForRebuffer(track)
            watermarkPauseNs = System.nanoTime()
        } else if (!playbackStarted && watermarkPauseNs != 0L && queue.isEmpty() &&
            System.nanoTime() - watermarkPauseNs >= WATERMARK_PAUSE_MAX_NS) {
            // M6v：「暂停一小段」的界：等补缓冲超过 WATERMARK_PAUSE_MAX_NS 仍无新包，就放行残量——
            // 流尾残量自然播完（否则被永久卡住）；长停顿则接受结尾一次咔哒，好过无限期静音。
            // 残量已为 0 就只清标志保持暂停，等数据回来走正常 prebuffer。
            watermarkPauseNs = 0L
            if (queuedMillis(track) > 0L) startPlayback(track)
        }
        // A short final burst may never reach the start threshold. Play it after a bounded wait.
        if (!playbackStarted && prebufferBytes > 0 && queue.isEmpty() &&
            System.nanoTime() - lastPcmWriteNs >= BUFFER_TAIL_WAIT_NS) {
            startPlayback(track)
        }
    }

    // Persist counters even during packet starvation, and flush before disconnect releases the track.
    private fun logStatsIfDue(force: Boolean = false) {
        val now = System.nanoTime()
        if (statsWindowStartNs == 0L) statsWindowStartNs = now
        if (!force && now - statsWindowStartNs < STATS_WINDOW_NS) return
        val underruns = track?.let(::trackUnderruns) ?: 0
        // M6v：track 内未播完的毫秒数（估算），配合 lowWaterMs 判读 rebuffer 是否按预期工作。
        val queuedMs = track?.let { queuedMillis(it) } ?: -1L
        val lastRx = lastArrivalNs.get()
        BandwidthMonitor.setAudioMetrics(
            maxArrivalGapMs.get().toInt(),
            (underruns - statsLastUnderruns).toInt(),
            rebufferCount,
        )
        val line = "audio stats audioType=${format.audioType} codec=${format.codec} rx=${packetsReceived.getAndSet(0)} " +
            "dropped=${packetsDropped.getAndSet(0)} underruns=+${underruns - statsLastUnderruns} queue=${queue.size} " +
            "queuedMs=$queuedMs lowWaterMs=$lowWatermarkMs " +
            "playing=$playbackStarted maxGapMs=${maxArrivalGapMs.getAndSet(0)} " +
            "gapCompMs=${gapUsCompensated / 1000} gapFrames=$packetsCompensated " +
            "sinceRxMs=${if (lastRx == 0L) -1 else (now - lastRx) / 1_000_000L} maxWriteMs=$maxWriteMs " +
            "maxDecodeMs=$maxDecodeMs " +
            "decoderDroppedTotal=$inputDropped outputBuffersTotal=$outputBuffers rebuffers=$rebufferCount ended=$force " +
            (processLoad.sample(now) ?: "cpu=? threads=?")
        Log.i(STATS_TAG, line)
        report(line)
        statsLastUnderruns = underruns
        maxWriteMs = 0L
        maxDecodeMs = 0L
        gapUsCompensated = 0L
        packetsCompensated = 0L
        statsWindowStartNs = now
    }

    /** 16-bit little-endian 采样读写（带符号）。淡入/淡出必须按有符号数算，否则负采样会被放大成正数（爆音）。 */
    private fun readS16(data: ByteArray, position: Int): Int {
        val raw = (data[position].toInt() and 0xff) or (data[position + 1].toInt() shl 8)
        return if (raw >= 0x8000) raw - 0x10000 else raw
    }

    private fun writeS16(data: ByteArray, position: Int, value: Long) {
        val clamped = value.coerceIn(-32768L, 32767L).toInt()
        data[position] = clamped.toByte()
        data[position + 1] = (clamped shr 8).toByte()
    }

    private fun applyFadeIn(data: ByteArray, offset: Int, length: Int) {
        val samples = (length - length % 2) / 2
        val fadeSamples = minOf(samples, maxOf(1, format.sampleRate / 100))
        for (index in 0 until fadeSamples) {
            val position = offset + index * 2
            writeS16(data, position, readS16(data, position).toLong() * (index + 1) / fadeSamples)
        }
    }

    private fun byteSwapS16(source: ByteArray): ByteArray {
        for (index in 0 until source.size - 1 step 2) {
            val tmp = source[index]
            source[index] = source[index + 1]
            source[index + 1] = tmp
        }
        return source
    }

    @Synchronized
    private fun release() {
        // M6c/M6d：流结束就撤掉"通道活跃"（PHONE→App 恢复默认路由；NAVIGATION→音乐恢复原音量）。
        if (channelActiveNotified) {
            channelActiveNotified = false
            onChannelState?.invoke(routeChannel, false)
        }
        // M5a：软件 OPUS 解码器与 MediaCodec 互斥，两者都要在流结束时释放。
        val currentNativeDecoder = nativeOpusDecoder
        nativeOpusDecoder = null
        if (currentNativeDecoder != null) {
            runCatching { currentNativeDecoder.close() }
        }
        val codec = codec
        this.codec = null
        if (codec != null) {
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
        val track = track
        this.track = null
        if (track != null) {
            try {
                track.pause()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.flush()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    /** AtomicLong.accumulateAndGet 是 API 24+，CAS 循环实现同语义。 */
    private fun updateMaxArrivalGap(value: Long) {
        while (true) {
            val current = maxArrivalGapMs.get()
            if (value <= current || maxArrivalGapMs.compareAndSet(current, value)) return
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val AAC_OBJECT_TYPE_LC = 2
        const val MIN_OPUS_PACKET_BYTES = 4
        const val OPUS_CODEC_DELAY_NANOS = 6_500_000L
        const val OPUS_SEEK_PRE_ROLL_NANOS = 80_000_000L
        const val INPUT_TIMEOUT_US = 10_000L
        const val AUDIO_POLL_MILLIS = 10L
        const val BUFFER_TAIL_WAIT_NS = 500_000_000L
        // M6v：低水位 pause 后等补缓冲的最长时长（「一小段」）；超时放行残量，防止流尾被永久卡住。
        const val WATERMARK_PAUSE_MAX_NS = 1_000_000_000L
        // Holds a burst after a Wi-Fi gap (~4 s of AAC) instead of dropping it.
        const val MAX_QUEUED_PACKETS = 192
        const val PREBUFFER_WRITE_CHUNK_BYTES = 2 * 1024
        const val STATS_TAG = "DiPlay-AudioStats"
        const val STATS_WINDOW_NS = 5_000_000_000L
        const val DECODED_BUFFER_LOG_INTERVAL = 50
        // P0: frame-loss compensation bounds. Worst observed wireless loss burst is ~640 ms.
        const val MAX_FRAME_US = 1_000_000L
        const val MAX_COMPENSATE_US = 1_000_000L
        // M6k：缺口超过该长度就不再用"平铺上一帧"（会变持续音调），改填淡出静音。
        // 取 40ms ≈ 2 个 AAC 帧——真实音频帧长 21.3ms，短缺口复用一两次是合理遮蔽。
        const val MAX_CONCEAL_REPEAT_US = 40_000L
        const val MAX_PACKET_IDLE_NS = 1_000_000_000L
        const val MAX_COMPENSATE_LOG = 20
    }
}

// ---- API16 兼容 helper（顶层，供 AndroidMediaSink/AudioRenderer/VideoDecoder 共用） ----

/** MediaCodec.getName 是 API 18+，低版本仅日志用途，回退 "unknown"。 */
private fun codecName(codec: MediaCodec): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) codec.name else "unknown"

/** MediaCodec.getInputBuffer 是 API 21+，低版本改用 getInputBuffers()[index]（API 16）。 */
private fun codecInputBuffer(codec: MediaCodec, index: Int): ByteBuffer? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        codec.getInputBuffer(index)
    } else {
        codec.inputBuffers.getOrNull(index)
    }

/** MediaCodec.getOutputBuffer 是 API 21+，低版本改用 getOutputBuffers()[index]（API 16）。 */
private fun codecOutputBuffer(codec: MediaCodec, index: Int): ByteBuffer? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        codec.getOutputBuffer(index)
    } else {
        codec.outputBuffers.getOrNull(index)
    }

/** AudioTrack.getUnderrunCount 是 API 24+，低版本无此指标，返回 0。 */
private fun trackUnderruns(track: AudioTrack): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) track.underrunCount else 0

/**
 * 本机是否存在该 mime 的编/解码器组件（M4m，2026-10-03 实证）。
 *
 * **为什么必须"先查字典再创建"**：C8815（API16）上对**本机不存在的 OPUS** 调
 * `MediaCodec.createDecoderByType("audio/opus")`，会在厂商 OMX/stagefright 层**原生崩溃**：
 * 日志最后一行就是 `Audio: creating decoder mime=audio/opus …`，之后进程直接消失、没有 `[crash]`
 * （`catch (Exception)` 与 `catch (Throwable)` 都拦不住，因为进程不是被 Java 异常杀掉的）。
 * 查组件列表只是读 media_codecs.xml，**不实例化组件**，所以安全。
 *
 * ⚠️ **M6h 修正（T43 实证，勿回退）**：低版本**不能**走 `MediaCodecList` —— 那个类是
 * **API 21 才引入**的，在 C8815（API16）上 `Class.forName("android.media.MediaCodecList")`
 * 直接抛异常，于是清单恒为空：日志里是 `video/avc decoders=[] software=none`，
 * `softwareDecoderNameFor` 恒返 null ⇒ **「强制软解」在 API16 上等于没开**（勾了也回落厂商硬解）。
 * API16–20 的正确入口是 **`MediaCodec.getCodecCount()` / `getCodecInfoAt(int)`**（均为静态，
 * 也已从 compileSdk 34 的 android.jar 移除，同样只能反射）。
 *
 * 查询本身失败时的兜底：只禁掉"API<21 上的 OPUS"这个已知会崩的组合，其余放行——
 * 免得探针异常反而把能用的 AAC 也一起禁掉、连音乐都没声音了。
 */
internal fun codecComponentExists(mime: String, encoder: Boolean): Boolean {
    val infos = codecProbe.infos
    if (infos.isNotEmpty()) {
        return infos.any { it.isEncoder == encoder && mime in it.supportedTypes }
    }
    return !(Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP &&
        mime == MediaFormat.MIMETYPE_AUDIO_OPUS)
}

/** M6e：列出本机所有支持 mime 的解码器名字（诊断用，确认这台机器到底有没有软解可用）。 */
internal fun decoderNamesFor(mime: String): List<String> =
    codecProbe.infos
        .filter { !it.isEncoder && mime in it.supportedTypes }
        .map { it.name }

/** M6i：探测结果的一句话摘要（进文件日志，用于判断"空表"到底是哪一步挂了）。 */
internal fun codecProbeNote(): String = codecProbe.note

/**
 * M6i：本机编解码器清单的探测结果。
 *
 * T43/T44 两次实测在 C8815 上都得到 `decoders=[]`，但当时失败原因只写了 logcat、
 * **没有进文件日志**，等于没有证据。这里改成：逐策略尝试，并把**每个策略的异常类型与消息**
 * 记进 [note]，随视频清单行一起落盘，下一次就能直接看到是哪一步、为什么挂。
 */
private class CodecProbe(val infos: List<MediaCodecInfo>, val note: String)

private val codecProbe: CodecProbe by lazy { probeCodecList() }

private class LegacyCodecProbe(val name: String, val load: () -> List<MediaCodecInfo>)

/**
 * API16–20 的候选探测策略。官方入口是 [MediaCodec] 的静态方法（[MediaCodecList] 是 API21 才有的类），
 * 但厂商 ROM 可能把它放在别处，故逐个试；顺序即优先级。
 */
private val LEGACY_CODEC_PROBES = listOf(
    LegacyCodecProbe("MediaCodec.static") {
        val clazz = Class.forName("android.media.MediaCodec")
        val count = clazz.getMethod("getCodecCount").invoke(null) as Int
        val getCodecInfoAt = clazz.getMethod("getCodecInfoAt", java.lang.Integer.TYPE)
        (0 until count).mapNotNull { index ->
            getCodecInfoAt.invoke(null, index) as? MediaCodecInfo
        }
    },
    LegacyCodecProbe("MediaCodecList.static") {
        val clazz = Class.forName("android.media.MediaCodecList")
        val count = clazz.getMethod("getCodecCount").invoke(null) as Int
        val getCodecInfoAt = clazz.getMethod("getCodecInfoAt", java.lang.Integer.TYPE)
        (0 until count).mapNotNull { index ->
            getCodecInfoAt.invoke(null, index) as? MediaCodecInfo
        }
    },
    LegacyCodecProbe("MediaCodecList.instance") {
        val clazz = Class.forName("android.media.MediaCodecList")
        val instance = clazz.getConstructor(java.lang.Integer.TYPE).newInstance(0)
        val count = clazz.getMethod("getCodecCount").invoke(instance) as Int
        val getCodecInfoAt = clazz.getMethod("getCodecInfoAt", java.lang.Integer.TYPE)
        (0 until count).mapNotNull { index ->
            getCodecInfoAt.invoke(instance, index) as? MediaCodecInfo
        }
    },
)

private fun probeCodecList(): CodecProbe {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        return try {
            val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList()
            CodecProbe(infos, "MediaCodecList=${infos.size}")
        } catch (error: Throwable) {
            CodecProbe(emptyList(), "MediaCodecList=" + describe(error))
        }
    }
    val reasons = StringBuilder()
    for (strategy in LEGACY_CODEC_PROBES) {
        try {
            val infos = strategy.load()
            if (infos.isNotEmpty()) {
                reasons.append(strategy.name).append("=ok(").append(infos.size).append(')')
                return CodecProbe(infos, reasons.toString())
            }
            reasons.append(strategy.name).append("=empty;")
        } catch (error: Throwable) {
            reasons.append(strategy.name).append('=').append(describe(error)).append(';')
        }
    }
    return CodecProbe(emptyList(), reasons.toString())
}

private fun describe(error: Throwable): String =
    error.javaClass.simpleName + (error.message?.let { "($it)" } ?: "")

/**
 * M6i：给 `createDecoder` 用的软解选择 —— **清单优先，拿不到清单就按"已知软解组件名"逐个试**。
 *
 * T44 实测：C8815 上清单恒为空，于是"强制软解"等于没开（勾了仍回落厂商硬解）。兜底分两级：
 * ① 只读 ROM 的 `media_codecs.xml` 判断组件名是否声明（零风险，能读到时不给 ROM 任何机会崩）；
 * ② 读不到 XML 才真去 `createByCodecName` 试一把，并逐个 try/catch。
 *
 * ⚠️ 只在用户**显式打开"强制软解"**时才调用（风险受控）。T32 的教训是"按 mime 查找不存在的
 * 组件"会在厂商 OMX 层原生崩进程，所以这里只按**具体组件名**试、并优先走只读判断。
 */
internal fun probeSoftwareDecoderName(mime: String): String? {
    decoderNamesFor(mime).firstOrNull(::isSoftwareDecoderName)?.let { return it }
    for (name in KNOWN_SOFTWARE_DECODERS[mime].orEmpty()) {
        when (codecNameDeclaredInRom(name)) {
            true -> return name
            false -> continue
            null -> if (canCreateCodec(name)) return name
        }
    }
    return null
}

private fun isSoftwareDecoderName(name: String): Boolean =
    name.startsWith("OMX.google.") || name.startsWith("c2.android.") ||
        name.startsWith("c2.soft.") || name.contains("sw.decoder", ignoreCase = true)

/** 读 ROM 的 media_codecs.xml 判断组件名是否存在（只读文件，零风险）。null = 哪个都读不到。 */
private fun codecNameDeclaredInRom(name: String): Boolean? {
    for (path in MEDIA_CODECS_XML_PATHS) {
        val file = java.io.File(path)
        if (!file.canRead()) continue
        val declared = try {
            file.readText().contains(name)
        } catch (_: Throwable) {
            null
        }
        if (declared != null) return declared
    }
    return null
}

private fun canCreateCodec(name: String): Boolean = try {
    MediaCodec.createByCodecName(name).release()
    true
} catch (error: Throwable) {
    Log.w("xcertplay-usb", "software decoder probe failed name=$name", error)
    false
}

/** 已知的通用软解组件名（按 AOSP 惯例命名；老机器上通常存在）。 */
private val KNOWN_SOFTWARE_DECODERS = mapOf(
    MediaFormat.MIMETYPE_VIDEO_AVC to listOf(
        "OMX.google.h264.decoder",
        "c2.android.avc.decoder",
    ),
    MediaFormat.MIMETYPE_VIDEO_HEVC to listOf(
        "OMX.google.hevc.decoder",
        "c2.android.hevc.decoder",
    ),
)

private val MEDIA_CODECS_XML_PATHS = listOf(
    "/system/etc/media_codecs.xml",
    "/etc/media_codecs.xml",
    "/vendor/etc/media_codecs.xml",
    "/system/vendor/etc/media_codecs.xml",
)

/**
 * M6e：挑一个软件解码器（API<29 没有 isSoftwareOnly，按厂商前缀判断）。
 * 8815/C8815 这类老机型的厂商硬解偶发异常时，用它做兜底/二分定位。
 */
internal fun softwareDecoderNameFor(mime: String): String? =
    decoderNamesFor(mime).firstOrNull(::isSoftwareDecoderName)
