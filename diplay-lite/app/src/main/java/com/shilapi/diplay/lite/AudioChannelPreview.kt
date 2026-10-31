package com.shilapi.diplay.lite

import android.annotation.TargetApi
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin

/**
 * M6k21：音频流编号试听（参考 fork carlito12345 版 AudioChannelPreview 精简）。
 *
 * 与实际播放同路径：stream=0 走 usage 路由（AudioTrack.Builder）；1~20 走传统音频流
 * 编号（旧 AudioTrack 构造，含车机厂商扩展流）。试听到什么，连接后就走什么。
 *
 * M6k24g：试听源从 600ms 880Hz 正弦音换成内置音乐片段（res/raw/audio_preview.mp3），
 * MediaExtractor+MediaCodec 解码出 PCM 后喂给同一套 buildTrack 建轨逻辑——路由路径
 * 完全不变，只换素材；解码失败回落正弦短音。
 *
 * Lite 适配点：无 AudioFocusRequest（API 26+）；无车机厂商工厂依赖。单 worker 线程 +
 * generation 计数——连续点选立即切换、旧音即停。MediaCodec 用 deprecated input/output
 * Buffers 数组 API（API16-20 无 getInputBuffer/getOutputBuffer）。
 */
object AudioChannelPreview {

    private const val FALLBACK_SAMPLE_RATE = 48000
    private const val FALLBACK_DURATION_MS = 600
    private const val FADE_SAMPLES = FALLBACK_SAMPLE_RATE * 10 / 1000 // 10ms 淡入淡出
    private const val VOLUME = 0.6f

    private val worker = Thread { loop() }.apply {
        name = "audio-channel-preview"
        isDaemon = true
        start()
    }
    private val generation = AtomicInteger(0)
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var pending: Pair<Int, Boolean>? = null // stream to media?
    @Volatile private var currentTrack: AudioTrack? = null

    /**
     * 点选任一流编号即调用：停止上一条试听，播放新的音乐片段。
     * [media] = true 用媒体用途（MEDIA usage / stream 透传），false 用导航（GUIDANCE usage）。
     * 失败（ROM 拒绝该流编号/解码不可用）时回落正弦短音或 toast。
     */
    fun play(activity: android.app.Activity, stream: Int, media: Boolean) {
        currentActivity = activity
        pending = stream to media
        generation.incrementAndGet()
    }

    /** 对话框 dismiss 时调用：停止试听并释放。 */
    fun close() {
        pending = null
        generation.incrementAndGet()
    }

    private fun loop() {
        var playedGeneration = 0
        while (true) {
            val gen = generation.get()
            if (gen != playedGeneration) {
                // 新的试听请求（或 close）：先停掉在响的
                playedGeneration = gen
                stopTrack()
            }
            val task = pending
            if (task == null) {
                try { Thread.sleep(40) } catch (_: InterruptedException) { return }
                continue
            }
            pending = null
            val activity = currentActivity ?: continue
            playClip(activity, task.first, task.second, gen)
        }
    }

    /** 解码内置 mp3 → 按所选流建轨播放；任何一步失败回落 880Hz 短音。 */
    private fun playClip(activity: android.app.Activity, stream: Int, media: Boolean, gen: Int) {
        val resId = activity.resources.getIdentifier("audio_preview", "raw", activity.packageName)
        if (resId == 0) {
            playTone(activity, stream, media, gen)
            return
        }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var track: AudioTrack? = null
        try {
            val afd = activity.resources.openRawResourceFd(resId) ?: throw IllegalStateException("no afd")
            extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    format = f
                    extractor.selectTrack(i)
                    break
                }
            }
            val fmt = format ?: throw IllegalStateException("no audio track")
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val newCodec = MediaCodec.createDecoderByType(mime)
            codec = newCodec
            newCodec.configure(fmt, null, null, 0)
            newCodec.start()

            val newTrack = buildTrack(stream, media, sampleRate, if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO)
                ?: throw IllegalStateException("track rejected")
            if (newTrack.state != AudioTrack.STATE_INITIALIZED) {
                newTrack.release()
                throw IllegalStateException("track not init")
            }
            track = newTrack
            currentTrack = newTrack
            newTrack.play()

            val inputBuffers = newCodec.inputBuffers
            val outputBuffers = newCodec.outputBuffers
            val info = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var totalFrames = 0L
            while (generation.get() == gen) {
                if (!sawInputEOS) {
                    val inIdx = newCodec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = inputBuffers[inIdx]
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            newCodec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            newCodec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = newCodec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    val out = outputBuffers[outIdx]
                    if (out != null && info.size > 0) {
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        val shorts = info.size / 2
                        val pcm = ShortArray(shorts)
                        out.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
                        totalFrames += (shorts / channels).toLong()
                        newTrack.write(pcm, 0, shorts)
                    }
                    newCodec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            if (generation.get() == gen) {
                // 等缓冲里的尾巴播完（write 已阻塞灌入），上限兜底 3s
                var waited = 0L
                while (generation.get() == gen && waited < 3_000) {
                    val head = newTrack.playbackHeadPosition.toLong()
                    if (head >= totalFrames) break
                    Thread.sleep(50)
                    waited += 50
                }
            }
        } catch (_: Exception) {
            stopTrack()
            playTone(activity, stream, media, gen)
            return
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
        if (generation.get() == gen && currentTrack === track) stopTrack()
    }

    /** 回落素材：600ms 880Hz 正弦短音（M6k21 原行为）。 */
    private fun playTone(activity: android.app.Activity, stream: Int, media: Boolean, gen: Int) {
        val track = try {
            buildTrack(stream, media, FALLBACK_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO)
        } catch (error: Exception) {
            notifyUnavailable(activity, stream)
            return
        } ?: run {
            notifyUnavailable(activity, stream)
            return
        }
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            notifyUnavailable(activity, stream)
            return
        }
        currentTrack = track
        val samples = FALLBACK_SAMPLE_RATE * FALLBACK_DURATION_MS / 1000
        val pcm = ShortArray(samples)
        for (i in 0 until samples) {
            val envelope = when {
                i < FADE_SAMPLES -> i.toFloat() / FADE_SAMPLES
                i >= samples - FADE_SAMPLES -> (samples - i).toFloat() / FADE_SAMPLES
                else -> 1f
            }
            pcm[i] = (sin(2.0 * PI * 880.0 * i / FALLBACK_SAMPLE_RATE) * VOLUME * envelope * Short.MAX_VALUE).toInt().toShort()
        }
        val written = track.write(pcm, 0, samples)
        if (written < 0) {
            stopTrack()
            notifyUnavailable(activity, stream)
            return
        }
        track.play()
        // 播完自动释放；中途被点新编号时由 stopTrack() 抢先释放
        handler.postDelayed({
            if (generation.get() == gen && currentTrack === track) stopTrack()
        }, (FALLBACK_DURATION_MS + 120).toLong())
    }

    /**
     * 与 AndroidMediaSink 建轨同路径：0=usage（媒体/导航各用各的 usage）；1~20=传统
     * stream type 旧构造。采样率/声道随试听素材走（解码出什么用什么）。
     */
    private fun buildTrack(stream: Int, media: Boolean, sampleRate: Int, channelMask: Int): AudioTrack? {
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        if (minBuffer <= 0) return null
        return if (stream <= 0) {
            // API16 适配：AudioTrack.Builder/AudioAttributes 是 API 23+；更低版本回退传统
            // stream 构造（NoSuchMethodError 是 Error，外层 catch Exception 拦不住，必须走分支）。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                buildUsageTrack(media, sampleRate, channelMask, minBuffer)
            } else {
                buildLegacyTrack(sampleRate, channelMask, minBuffer)
            }
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(stream, sampleRate, channelMask, encoding, minBuffer, AudioTrack.MODE_STREAM)
        }
    }

    /** API 23+：usage 路由建轨（媒体/导航各用各的 usage），与 AndroidMediaSink 同路径。
     *  `AudioTrack.Builder` / `AudioFormat.Builder` / `AudioAttributes.Builder` 全是 API 21~23，
     *  唯一调用点已由 `Build.VERSION.SDK_INT >= M` 分支保护；注解把这个契约写下来，
     *  免得将来别处误调（lint NewApi 也能因此不再误报）。 */
    @TargetApi(23)
    private fun buildUsageTrack(
        media: Boolean,
        sampleRate: Int,
        channelMask: Int,
        minBuffer: Int,
    ): AudioTrack {
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val usage = if (media) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
        val contentType = if (media) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_SPEECH
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(contentType).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(channelMask).setEncoding(encoding).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuffer)
            .build()
    }

    /** API < 23：无 usage 路由，传统 stream 路径（与低版本实际播放一致，STREAM_MUSIC）。 */
    @Suppress("DEPRECATION")
    private fun buildLegacyTrack(
        sampleRate: Int,
        channelMask: Int,
        minBuffer: Int,
    ): AudioTrack {
        return AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer,
            AudioTrack.MODE_STREAM,
        )
    }

    private fun stopTrack() {
        val track = currentTrack ?: return
        currentTrack = null
        try {
            track.stop()
        } catch (_: IllegalStateException) {
        }
        try {
            track.release()
        } catch (_: Exception) {
        }
    }

    private fun notifyUnavailable(activity: android.app.Activity, stream: Int) {
        handler.post {
            if (!activity.isFinishing) {
                Toast.makeText(activity, "通道 $stream 试听不可用（该 ROM 拒绝此音频流）", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 设置弹窗持有当前 Activity 引用（dismiss 时置空），避免泄漏。 */
    @Volatile var currentActivity: android.app.Activity? = null
}
