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
) : MediaSink {
    private val screenStateLock = Any()
    private val activeScreenTypes = mutableSetOf<Int>()
    private val defaultSurface = surface
    @Volatile private var screenStreamActiveChanged = onScreenStreamActiveChanged
    private val surfaces = ConcurrentHashMap<Int, Surface>()
    private val videoDecoders = ConcurrentHashMap<Int, VideoDecoder>()
    private val audioRenderers = ConcurrentHashMap<Int, AudioRenderer>()
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
        audioRenderer(type, format).start()
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
        )
        return videoDecoders.putIfAbsent(type, created) ?: created
    }

    @Synchronized
    private fun audioRenderer(type: Int, format: AudioFormat): AudioRenderer {
        val existing = audioRenderers[type]
        if (existing?.format == format) return existing
        existing?.close()
        return AudioRenderer(format, advancedAudioChannelMapping, mediaBufferMillis, onAudioDiagnostic).also { audioRenderers[type] = it }
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
) : Closeable {
    private val queue = VideoDecodeQueue()
    @Volatile private var running = true
    @Volatile private var decoder: MediaCodec? = null
    private var outputSurface: Surface? = surface
    private var lastConfig: VideoJob.Config? = null
    private var renderedFrameLogged = false
    private var submittedFrameLogged = false
    private var duplicateConfigLogged = false
    private val referenceChain = VideoReferenceChain()
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
        try {
            while (running) {
                val job = queue.poll(5)
                try {
                    when (job) {
                        is VideoJob.Config -> configureDecoder(job)
                        is VideoJob.Frame -> {
                            if (System.nanoTime() - job.receivedNs > MAX_FRAME_AGE_NS) {
                                queue.discardFrames()
                                recover("video backlog exceeded 250 ms")
                            } else feed(job.nalus)
                        }
                        is VideoJob.SurfaceChanged -> changeSurface(job.surface)
                        is VideoJob.Resync -> recover("video queue overflow")
                        null -> Unit
                    }
                    decoder?.let(::drainOutput)
                    stats.logIfDue()?.let(report)
                    if (referenceChain.needsKeyFrame && lastConfig != null && outputSurface != null) requestKeyFrameIfDue()
                } catch (error: Exception) {
                    if (running) Log.e(TAG, "video decoder job failed: ${job?.javaClass?.simpleName}", error)
                    if (running) report("decoder error ${error.javaClass.simpleName}; waiting for keyframe")
                    releaseDecoder()
                    referenceChain.reset()
                    requestKeyFrameIfDue()
                } catch (error: Throwable) {
                    // 码率/帧长突变时可能抛 Error（OutOfMemoryError 等），catch(Exception) 兜不住，
                    // 逃出 run() 即线程死亡 → 进程被杀（用户反馈"视频流突然变大就闪退"）。
                    // 这里按普通解码失败处理：释放解码器、等下一个关键帧，保住进程。
                    if (running) Log.e(TAG, "video decoder job crashed: ${job?.javaClass?.simpleName}", error)
                    if (running) report("decoder crashed ${error.javaClass.name}; waiting for keyframe")
                    releaseDecoder()
                    referenceChain.reset()
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
        val surface = outputSurface ?: return
        val codec = config.codec
        val codecData = config.codecData
        val mime = if (codec == VideoCodec.H265) MediaFormat.MIMETYPE_VIDEO_HEVC
        else MediaFormat.MIMETYPE_VIDEO_AVC
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
        if (preferSoftwareHevcDecoder) {
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
                    return
                } catch (error: Exception) {
                    Log.w(TAG, "video decoder output surface update failed; reconfiguring", error)
                }
            }
        }
        releaseDecoder()
        lastConfig?.let(::configureDecoder)
    }

    private fun feed(nalus: ByteArray) {
        val annexB = MediaCodecSupport.toAnnexB(nalus)
        val config = lastConfig ?: return
        if (outputSurface == null) return
        if (annexB.isEmpty()) { recover("invalid video access unit"); return }
        if (!referenceChain.accepts(annexB, config.codec)) {
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
        if (index < 0) { recover("video decoder input stalled"); return }
        val input = checkNotNull(codecInputBuffer(codec, index)) { "Decoder input buffer unavailable" }
        input.clear()
        if (annexB.size <= input.remaining()) {
            input.put(annexB)
            codec.queueInputBuffer(index, 0, annexB.size, System.nanoTime() / 1000, 0)
            referenceChain.onQueued()
        } else {
            recover("video frame exceeded codec input capacity")
            return
        }
        drainOutput(codec)
    }

    private fun recover(reason: String) {
        Log.w(TAG, "Video recovery: $reason; waiting for keyframe")
        stats.onRecovery()
        report("recovery: $reason; waiting for keyframe")
        // Recreate with codec-specific data: flush can discard CSD before the first output.
        releaseDecoder()
        referenceChain.reset()
        requestKeyFrameIfDue()
    }

    private fun requestKeyFrameIfDue() {
        val now = System.nanoTime()
        if (lastKeyFrameRequestNs != 0L && now - lastKeyFrameRequestNs < 1_000_000_000L) return
        lastKeyFrameRequestNs = now
        requestKeyFrame()
    }

    private fun drainOutput(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> logOutputFormat(codec.outputFormat)
                index >= 0 -> {
                    val render = outputSurface != null
                    codec.releaseOutputBuffer(index, render)
                    if (render) stats.onRendered()
                    if (render && !renderedFrameLogged) {
                        renderedFrameLogged = true
                        report("first frame rendered")
                        Log.i(TAG, "video decoder rendered first frame bytes=${info.size}")
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun logOutputFormat(format: MediaFormat) {
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
        const val MAX_FRAME_AGE_NS = 250_000_000L
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
    private var fadeApplied = false
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
    private val bufferProgress = AudioBufferProgress(if (format.channels >= 2) 4 else 2)
    private var underrunsAtPlaybackStart = 0
    private var lastPcmWriteNs = 0L
    private var rebufferCount = 0
    // P0: RTP frame-loss compensation state (see compensateMissingFrames).
    private var lastTimestampUs = -1L
    private var lastIncrementUs = 0L
    private var expectedTimestampUs = -1L
    private var silenceScratch = ByteArray(0)
    private var packetsCompensated = 0L
    private var gapUsCompensated = 0L
    private val thread = Thread(::run, "carplay-audio").apply { isDaemon = true }

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
        val built = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
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
                .setBufferSizeInBytes(plan.trackBufferBytes)
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
                plan.trackBufferBytes,
                AudioTrack.MODE_STREAM,
            )
        }
        track = built
        val capacityBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            built.bufferSizeInFrames * frameBytes
        } else {
            plan.trackBufferBytes
        }
        startThresholdBytes = MediaAudioBuffer.startBytesFor(plan.startBytes, capacityBytes, PREBUFFER_WRITE_CHUNK_BYTES)
        report("Audio: ready audioType=${format.audioType} codec=${format.codec} " +
            "rate=${format.sampleRate} channels=${format.channels} " +
            "bufferMs=${capacityBytes * 1000L / bytesPerSecond} startMs=${startThresholdBytes * 1000L / bytesPerSecond}")
        Log.i(
            TAG,
            "audio track prepared type=${format.payloadType} audioType=${format.audioType} " +
                "codec=${format.codec} " +
                "rate=${format.sampleRate} channels=${format.channels} " +
                "buffer=${capacityBytes * 1000L / bytesPerSecond}ms start=${startThresholdBytes * 1000L / bytesPerSecond}ms",
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
        AudioChannel.PHONE -> AudioAttributes.USAGE_VOICE_COMMUNICATION
        AudioChannel.ASSISTANT -> AudioAttributes.USAGE_ASSISTANT
        AudioChannel.NAVIGATION -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
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
                writePcmSilence(missingBytes)
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

    private fun writePcmSilence(bytes: Int) {
        val track = track ?: return
        if (bytes <= 0 || bytesPerSecond <= 0) return
        if (silenceScratch.size < bytes) silenceScratch = ByteArray(bytes)
        java.util.Arrays.fill(silenceScratch, 0, bytes, 0)
        writePcm(silenceScratch, 0, bytes)
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
        if (!fadeApplied) {
            applyFadeIn(data, offset, length)
            fadeApplied = true
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
    }

    private fun startPlayback(track: AudioTrack) {
        underrunsAtPlaybackStart = trackUnderruns(track)
        track.play()
        playbackStarted = true
    }

    private fun maintainPlaybackBuffer() {
        val track = track ?: return
        if (bufferProgress.shouldRebuffer(format.audioType, playbackStarted,
                trackUnderruns(track) > underrunsAtPlaybackStart, queue.isEmpty(), track.playbackHeadPosition)) {
            // The hardware buffer has actually drained. Pause without flushing or discarding PCM,
            // then use the configured start threshold again when music resumes.
            track.pause()
            playbackStarted = false
            prebufferBytes = 0
            rebufferCount++
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
        val lastRx = lastArrivalNs.get()
        BandwidthMonitor.setAudioMetrics(
            maxArrivalGapMs.get().toInt(),
            (underruns - statsLastUnderruns).toInt(),
            rebufferCount,
        )
        val line = "audio stats audioType=${format.audioType} codec=${format.codec} rx=${packetsReceived.getAndSet(0)} " +
            "dropped=${packetsDropped.getAndSet(0)} underruns=+${underruns - statsLastUnderruns} queue=${queue.size} " +
            "playing=$playbackStarted maxGapMs=${maxArrivalGapMs.getAndSet(0)} " +
            "gapCompMs=${gapUsCompensated / 1000} gapFrames=$packetsCompensated " +
            "sinceRxMs=${if (lastRx == 0L) -1 else (now - lastRx) / 1_000_000L} maxWriteMs=$maxWriteMs " +
            "maxDecodeMs=$maxDecodeMs " +
            "decoderDroppedTotal=$inputDropped outputBuffersTotal=$outputBuffers rebuffers=$rebufferCount ended=$force"
        Log.i(STATS_TAG, line)
        report(line)
        statsLastUnderruns = underruns
        maxWriteMs = 0L
        maxDecodeMs = 0L
        gapUsCompensated = 0L
        packetsCompensated = 0L
        statsWindowStartNs = now
    }

    private fun applyFadeIn(data: ByteArray, offset: Int, length: Int) {
        val samples = (length - length % 2) / 2
        val fadeSamples = minOf(samples, maxOf(1, format.sampleRate / 100))
        for (index in 0 until fadeSamples) {
            val position = offset + index * 2
            val sample = (data[position].toInt() and 0xff) or (data[position + 1].toInt() shl 8)
            val scaled = (sample.toLong() * (index + 1) / fadeSamples).toInt()
            data[position] = scaled.toByte()
            data[position + 1] = (scaled shr 8).toByte()
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
        // Holds a burst after a Wi-Fi gap (~4 s of AAC) instead of dropping it.
        const val MAX_QUEUED_PACKETS = 192
        const val PREBUFFER_WRITE_CHUNK_BYTES = 2 * 1024
        const val STATS_TAG = "DiPlay-AudioStats"
        const val STATS_WINDOW_NS = 5_000_000_000L
        const val DECODED_BUFFER_LOG_INTERVAL = 50
        // P0: frame-loss compensation bounds. Worst observed wireless loss burst is ~640 ms.
        const val MAX_FRAME_US = 1_000_000L
        const val MAX_COMPENSATE_US = 1_000_000L
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
 * API16 的 MediaCodecList 只有无参构造 + getCodecCount/getCodecInfoAt，而这三者在 API21 起被
 * `MediaCodecList(int kind)` + `getCodecInfos()` 取代、**已从 compileSdk 34 的 android.jar 里移除**，
 * 编译期无法直接调用 —— 低版本只能反射调用设备上真实存在的这些方法。
 *
 * 查询本身失败时的兜底：只禁掉"API<21 上的 OPUS"这个已知会崩的组合，其余放行——
 * 免得探针异常反而把能用的 AAC 也一起禁掉、连音乐都没声音了。
 */
internal fun codecComponentExists(mime: String, encoder: Boolean): Boolean = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any {
            it.isEncoder == encoder && mime in it.supportedTypes
        }
    } else {
        val clazz = Class.forName("android.media.MediaCodecList")
        val list = clazz.getDeclaredConstructor().newInstance()
        val codecCount = clazz.getMethod("getCodecCount").invoke(list) as Int
        val getCodecInfoAt = clazz.getMethod("getCodecInfoAt", java.lang.Integer.TYPE)
        (0 until codecCount).any { index ->
            val info = getCodecInfoAt.invoke(list, index) as MediaCodecInfo
            info.isEncoder == encoder && mime in info.supportedTypes
        }
    }
} catch (error: Throwable) {
    Log.w("xcertplay-usb", "codec component query failed mime=$mime encoder=$encoder", error)
    !(Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP && mime == MediaFormat.MIMETYPE_AUDIO_OPUS)
}
