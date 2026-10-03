package com.shilapi.xcertplay.media

import android.util.Log
import java.io.Closeable

/**
 * 内置 libopus（JNI，静态链接 `libxcertplay_opus.so`）软件 OPUS 解码器。
 *
 * 为什么需要它：API 21 起才有 `OMX.google.opus.decoder`，而本分支跑在 Android 4.1（API16）上
 * —— 本机连厂商自带的 OPUS 解码器都没有。而 /info **必须**声明 OPUS（T26/T33/T34 已三次证伪
 * "清掉 OPUS 换 PCM"），于是 iPhone 的导航播报（`type=101 audioType=default`）、通话对方声音
 * （`type=100 audioType=telephony`）、Siri（`type=100 audioType=speechRecognition`）全都发 OPUS，
 * 没有解码器就全是静音（日志里 `Audio: decoder absent，跳过创建` + `outputBuffersTotal=0`）。
 * 音乐走 `type=102 AAC_LC`，API16 自带 AAC 解码器，所以只有它一直正常 —— 这正是 T35 的现象。
 *
 * 采样率 48kHz / 单声道，与流协商值一致；一个 access unit 解出一帧 PCM（16-bit 小端）。
 */
internal class NativeOpusDecoder private constructor(
    private var handle: Long,
    private val sampleRate: Int,
    private val channels: Int,
) : Closeable {
    private var closed = false

    val available: Boolean get() = !closed && handle != 0L

    /** 解一个 Opus access unit，返回 16-bit 小端 PCM；失败返回 null。 */
    fun decode(packet: ByteArray, maxSamples: Int = sampleRate / 50): ByteArray? {
        if (closed || handle == 0L || packet.isEmpty()) return null
        return try {
            nativeDecode(handle, packet, maxSamples, channels)
        } catch (error: Throwable) {
            Log.w(TAG, "libopus decode failed", error)
            null
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        val current = handle
        handle = 0L
        if (current != 0L) nativeDestroy(current)
    }

    companion object {
        private const val TAG = "xcertplay-usb"
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 1
        /** CarPlay 音频按 20ms 一帧协商：48000 / 50 = 960 采样/声道。 */
        const val FRAME_SAMPLES_20MS = SAMPLE_RATE / 50

        /** 进程级一次探测：.so 存在且能创建解码器。 */
        @Volatile private var probed = false
        private var probeResult = false

        val nativeAvailable: Boolean
            get() {
                if (!probed) {
                    synchronized(this) {
                        if (!probed) {
                            probeResult = probe()
                            probed = true
                        }
                    }
                }
                return probeResult
            }

        private fun probe(): Boolean = try {
            System.loadLibrary("xcertplay_opus")
            val handle = nativeCreate(SAMPLE_RATE, CHANNELS)
            if (handle != 0L) {
                nativeDestroy(handle)
                true
            } else {
                false
            }
        } catch (error: Throwable) {
            Log.w(TAG, "libopus decoder unavailable", error)
            false
        }

        /** 创建解码器实例；失败（含 .so 缺失/不兼容）返回 null。 */
        fun create(sampleRate: Int = SAMPLE_RATE, channels: Int = CHANNELS): NativeOpusDecoder? {
            if (!nativeAvailable) return null
            return try {
                val handle = nativeCreate(sampleRate, channels)
                if (handle == 0L) {
                    Log.w(TAG, "libopus decoder create failed rate=$sampleRate channels=$channels")
                    null
                } else {
                    NativeOpusDecoder(handle, sampleRate, channels).also {
                        Log.i(TAG, "libopus decoder started rate=$sampleRate channels=$channels")
                    }
                }
            } catch (error: Throwable) {
                Log.w(TAG, "libopus decoder create error", error)
                null
            }
        }

        // @JvmStatic 必须加（同 NativeOpusEncoder 的注释）。
        @JvmStatic
        private external fun nativeCreate(sampleRate: Int, channels: Int): Long

        @JvmStatic
        private external fun nativeDecode(
            handle: Long,
            packet: ByteArray,
            maxSamples: Int,
            channels: Int,
        ): ByteArray?

        @JvmStatic
        private external fun nativeDestroy(handle: Long)
    }
}