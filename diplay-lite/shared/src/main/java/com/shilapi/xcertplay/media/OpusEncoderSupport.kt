package com.shilapi.xcertplay.media

import android.util.Log

/**
 * 上行 OPUS 编码器选择层（移植自上游 0.2.21 的 MicrophoneOpusEncoders + OpusEncoderSupport 探测/
 * 容错思路，vc156）。
 *
 * 上游顺序是「平台 MediaCodec 优先 → Concentus 纯 Java 软编兜底」；本分支自带 JNI 静态
 * libopus（[NativeOpusEncoder]，即上游 SoftwareOpusEncoder 的同角色实现），且目标平台是
 * API16 老车机 —— MediaCodec 的 OPUS 编码器要 API 29+ 才存在，故顺序取
 * **native 优先 → 平台 MediaCodec 兜底**：native 创建/初始化失败（.so 缺失、创建抛错、
 * 句柄为 0）时才回落 MediaCodec；两者皆败返回 null（由调用方决定放弃上行，不拆会话）。
 *
 * SoftwareOpusEncoder 本体**不移植**：它依赖第三方纯 Java Opus 库 Concentus（org.concentus），
 * 与本分支「零新增第三方依赖」硬约束冲突，整库移植亦不现实；内置 libopus 已承担同职。
 */
internal object OpusEncoderSupport {
    private const val TAG = "xcertplay-usb"

    /**
     * 按优先级创建一个可用的上行 OPUS 编码器；全部失败返回 null。
     * 每一步都 runCatching 兜底：低版本 ROM 上组件探测/JNI 加载抛出的任何 Throwable
     * 都按「该后端不可用」处理，绝不让选择过程本身炸掉启动流程。
     */
    fun createEncoder(bitrate: Int, diagnostics: ((String) -> Unit)? = null): OpusFrameEncoder? {
        // 1) native 优先：内置 libopus（API16 主路径）。
        val native = runCatching { NativeOpusEncoder.create(bitrate) }.getOrNull()
        if (native != null) return native
        diagnostics?.invoke("Opus encoder: libopus unavailable, falling back to platform MediaCodec")
        // 2) 回落平台 MediaCodec。组件存在性已由 codecComponentExists 先查（没有该组件时
        //    构造器直接 unavailable），避开厂商 OMX 层「createEncoderByType 原生崩进程」路径。
        val platform = runCatching { OpusEncoder(bitrate) }.getOrNull()
        if (platform != null && platform.available) return platform
        runCatching { platform?.close() }
        Log.w(TAG, "no Opus uplink encoder available (libopus + MediaCodec both failed)")
        return null
    }
}
