package com.shilapi.xcertplay.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import com.shilapi.xcertplay.airplay.toHexString
import java.io.Closeable
import java.nio.ByteBuffer

/**
 * 麦克风上行帧编码器的公共接口。
 *
 * 两个实现同接口、可互换：
 *  · [OpusEncoder]（MediaCodec，API 29+ 才有 OPUS 编码器）
 *  · [NativeOpusEncoder]（内置 libopus 软件编码，本分支 API16 的兜底）
 * [MicrophoneUplink] 按"MediaCodec 优先、libopus 兜底"选择。
 */
internal interface OpusFrameEncoder : Closeable {
    val available: Boolean

    /** 编码一帧 PCM（20ms），返回若干 Opus access unit；失败返回空列表。 */
    fun encode(pcm: ByteArray): List<ByteArray>
}

/**
 * Encodes 20 ms chunks of 48 kHz mono PCM into raw Opus access units for the CarPlay
 * microphone uplink.
 */
internal class OpusEncoder(bitrate: Int) : OpusFrameEncoder {
    // M4m：本机没有 OPUS 编码器时**绝不**去 createEncoderByType —— C8815（API16）上厂商 OMX 层会
    // 直接原生崩进程（同 codecComponentExists 的注释；T32 的闪退就是解码侧同一机制）。
    // 代价只是麦克风上行不可用（Siri 没上行数据），不影响下行播放（那是解码路径）。
    private val codec: MediaCodec? = if (!codecComponentExists(MediaFormat.MIMETYPE_AUDIO_OPUS, encoder = true)) {
        Log.w(TAG, "Opus microphone encoder absent on this device; uplink disabled")
        null
    } else {
        try {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_OPUS,
                SAMPLE_RATE,
                CHANNELS,
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
            }
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).also {
                it.configure(
                    format,
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE,
                )
                it.start()
                Log.i(TAG, "Opus microphone encoder started bitrate=$bitrate")
            }
        } catch (error: Exception) {
            Log.w(TAG, "Opus microphone encoder unavailable", error)
            null
        }
    }
    private val bufferInfo = MediaCodec.BufferInfo()
    private var presentationTimeUs = 0L
    private var closed = false
    private var outputPackets = 0

    override val available: Boolean get() = codec != null && !closed

    /**
     * Queues one 20 ms PCM frame and returns all Opus access units made available by the codec.
     */
    override fun encode(pcm: ByteArray): List<ByteArray> {
        val codec = codec ?: return emptyList()
        if (closed) return emptyList()
        val inputIndex = try {
            codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        } catch (error: Exception) {
            Log.w(TAG, "Opus microphone input dequeue failed", error)
            return emptyList()
        }
        if (inputIndex >= 0) {
            // API16 适配：MediaCodec.getInputBuffer 是 API 21+，低版本用 getInputBuffers()[index]。
            val input = codecInputBuffer(codec, inputIndex)
            if (input == null || pcm.size > input.remaining()) {
                codec.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, 0)
            } else {
                input.clear()
                input.put(pcm)
                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    pcm.size,
                    presentationTimeUs,
                    0,
                )
                presentationTimeUs += INPUT_DURATION_US
            }
        }
        return drain()
    }

    private fun drain(): List<ByteArray> {
        val codec = codec ?: return emptyList()
        val output = ArrayList<ByteArray>()
        while (!closed) {
            val index = try {
                codec.dequeueOutputBuffer(bufferInfo, 0)
            } catch (error: Exception) {
                Log.w(TAG, "Opus microphone output dequeue failed", error)
                return output
            }
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return output
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                index >= 0 -> {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        codec.releaseOutputBuffer(index, false)
                        continue
                    }
                    val buffer = codecOutputBuffer(codec, index)
                    if (buffer != null && bufferInfo.size > 0) {
                        val bytes = ByteArray(bufferInfo.size)
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        buffer.get(bytes)
                        output.add(bytes)
                        outputPackets++
                        if (outputPackets <= FIRST_PACKET_LOG_COUNT) {
                            Log.i(
                                TAG,
                                "Opus microphone packet=$outputPackets bytes=${bytes.size} " +
                                    "head=${bytes.copyOf(minOf(bytes.size, 16)).toHexString()}",
                            )
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }
        return output
    }

    override fun close() {
        if (closed) return
        closed = true
        val codec = codec ?: return
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

    // ---- API16 兼容 helper ----

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

    private companion object {
        const val TAG = "xcertplay-usb"
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 1
        const val INPUT_TIMEOUT_US = 10_000L
        const val INPUT_DURATION_US = 20_000L
        const val MAX_INPUT_BYTES = 4_096
        const val FIRST_PACKET_LOG_COUNT = 3
    }
}
