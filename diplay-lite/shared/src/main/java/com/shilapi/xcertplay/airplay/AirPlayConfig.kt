package com.shilapi.xcertplay.airplay

/** Display insets in pixels, used for CarPlay viewArea and safeArea declarations. */
data class AirPlayInsets(
    val top: Int = 0,
    val bottom: Int = 0,
    val left: Int = 0,
    val right: Int = 0,
)

/** One display advertised to the phone in /info. */
data class AirPlayDisplayConfig(
    val widthPixels: Int,
    val heightPixels: Int,
    val widthPhysicalMm: Int? = null,
    val heightPhysicalMm: Int? = null,
    val fps: Int = 60,
    val primaryInputDevice: Int = 1,
    val viewArea: AirPlayInsets? = null,
    val safeArea: AirPlayInsets? = null,
    val safeAreaDrawOutside: Boolean? = null,
    val initialUrl: String? = null,
    /** Display feature bits; null keeps the main-screen default (high-fidelity touch and knobs). */
    val features: Int? = null,
)

/** One OEM homescreen icon. */
data class AirPlayIcon(
    val widthPixels: Int,
    val heightPixels: Int,
    val data: ByteArray,
)

/** Immutable accessory configuration consumed by the AirPlay session server. */
data class AirPlayConfig(
    val deviceName: String,
    val deviceId: String,
    val btMac: String,
    val sourceVersion: String,
    val main: AirPlayDisplayConfig,
    val cluster: AirPlayDisplayConfig? = null,
    val rightHandDrive: Boolean = false,
    // M6k8 强制固定 7100（勿改回 7000）：部分车机 ROM 的互联组件占 0.0.0.0:7000（K60 实测
    // EADDRINUSE，占用诊断见 AirPlayTcpServer.tcpPortOwners）。TCP 监听、Bonjour 广报、
    // 0x4301 airPlayPort 全部取本字段（同源）；iPhone 按 Bonjour 广告连，改端口不影响协议。
    // 红线：不许加"先试 7000 再兜底"的探测逻辑——端口翻转会让 iPhone 的 mDNS 缓存反复失效。
    val port: Int = 7100,
    val entertainmentSampleRate: Int = 48000,
    val hevc: Boolean = false,
    val disableAudioOutput: Boolean = false,
    // 1.1.3（bt_audio 试验）：/info 声明变体。no_audio=清音频位+整体省略 audioLatencies/audioFormats
    // （现状，iOS 16+ 实测不建屏幕流）；keep_formats=同样清音频位但保留两组声明键
    // （试验 iPhone 的拒绝是否源于键缺失而非 features 位；与上游 modes/resources 声明一致）。
    val btAudioInfoVariant: String = "no_audio",
    val microphone: Boolean = false,
    /**
     * Whether the device has an OPUS encoder (MediaCodec "audio/opus" encoder).
     * Android 10 (API 29) ships the OPUS encoder; older devices only decode OPUS.
     * When false, /info advertises PCM-only inputs so the phone negotiates PCM
     * uplink audio instead of OPUS, which would silently fail to encode.
     */
    val opusEncoderAvailable: Boolean = true,
    val manufacturer: String = "xcertplay",
    val model: String = "xcertplay",
    val oemLabel: String = "xcertplay",
    val icons: List<AirPlayIcon> = emptyList(),
)
