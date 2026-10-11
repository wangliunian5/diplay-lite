package com.shilapi.xcertplay.media

import android.util.Log
import java.io.Closeable

/**
 * 内置 libopus（JNI，静态链接 `libxcertplay_opus.so`）软件 OPUS 编码器。
 *
 * 为什么需要它：MediaCodec 的 OPUS 编码器要 API 29+，而本分支跑在 Android 4.1（API16）上；
 * 没有编码器时 /info 仍必须声明 OPUS（T26/T33/T34 证：清掉 OPUS 位会让 iPhone 一条音频流
 * 都不建），于是带无线输入的流会协商 OPUS 上行，而本机编不出来 —— 现象就是
 * `Microphone uplink failed`、通话/微信语音对方听不到我们说话。
 *
 * 采样率 48kHz / 单声道 / OPUS_APPLICATION_VOIP，与 CarPlay 协商的
 * `type=100/101 audioFormat=…OPUS mono 48k` 一致；每帧 20ms，输出单个 access unit。
 */
internal class NativeOpusEncoder private constructor(private var handle: Long) :
    OpusFrameEncoder,
    Closeable {
    private var closed = false

    override val available: Boolean get() = !closed && handle != 0L

    override fun encode(pcm: ByteArray): List<ByteArray> {
        if (closed || handle == 0L || pcm.isEmpty()) return emptyList()
        val packet = nativeEncode(handle, pcm, pcm.size / 2) ?: return emptyList()
        return listOf(packet)
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
        private const val SAMPLE_RATE = 48_000
        private const val CHANNELS = 1
        private const val APPLICATION_VOIP = 2048 // OPUS_APPLICATION_VOIP
        private const val PROBE_BITRATE = 48_000

        /** 进程级一次探测：.so 存在且能创建编码器。 */
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
            val handle = nativeCreate(SAMPLE_RATE, CHANNELS, APPLICATION_VOIP, PROBE_BITRATE)
            if (handle != 0L) {
                nativeDestroy(handle)
                true
            } else {
                false
            }
        } catch (error: Throwable) {
            Log.w(TAG, "libopus encoder unavailable", error)
            false
        }

        /** 创建编码器实例；失败（含 .so 缺失/不兼容）返回 null。 */
        fun create(bitrate: Int): NativeOpusEncoder? {
            if (!nativeAvailable) return null
            return try {
                val handle = nativeCreate(SAMPLE_RATE, CHANNELS, APPLICATION_VOIP, bitrate)
                if (handle == 0L) {
                    Log.w(TAG, "libopus encoder create failed bitrate=$bitrate")
                    null
                } else {
                    NativeOpusEncoder(handle).also {
                        Log.i(TAG, "libopus encoder started bitrate=$bitrate")
                    }
                }
            } catch (error: Throwable) {
                Log.w(TAG, "libopus encoder create error", error)
                null
            }
        }

        // @JvmStatic 必须加：companion object 里的 external fun 默认注册在 Companion 类上，
        // JNI 名会变成 ..._00024Companion_nativeCreate，与 .so 导出名不匹配 → UnsatisfiedLinkError。
        @JvmStatic
        private external fun nativeCreate(
            sampleRate: Int,
            channels: Int,
            application: Int,
            bitrate: Int,
        ): Long

        @JvmStatic
        private external fun nativeEncode(handle: Long, pcm: ByteArray, frameSamples: Int): ByteArray?

        @JvmStatic
        private external fun nativeDestroy(handle: Long)
    }
}