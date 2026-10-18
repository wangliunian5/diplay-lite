package com.shilapi.xcertplay.airplay

/**
 * Builds the /info response the phone reads before it requests media streams.
 *
 * The declaration is complete on purpose: it must carry the display, audio formats/latencies,
 * CarPlay resource modes, and the HID input devices, otherwise the phone aborts the session.
 */
object AirPlayInfoPlist {
    const val MAIN_UUID = "b7e6c5a0-1111-4000-8000-000000000001"
    const val ALT_UUID = "b7e6c5a0-2222-4000-8000-000000000002"

    private const val STREAM_TYPE_MAIN_SCREEN = 110
    private const val STREAM_TYPE_ALT_SCREEN = 111
    private const val DISPLAY_FEATURE_KNOBS = 0x02
    private const val DISPLAY_FEATURE_HIGH_FIDELITY_TOUCH = 0x08
    private const val CARPLAY_FEATURES = 0x615653aee2L
    private const val CARPLAY_AUDIO_FEATURES = 0x10004540a00L
    private val CARPLAY_FEATURES_NO_AUDIO = CARPLAY_FEATURES and CARPLAY_AUDIO_FEATURES.inv()

    private const val RESOURCE_SCREEN = 1
    private const val RESOURCE_AUDIO = 2
    private const val TRANSFER_TAKE = 1
    private const val PRIORITY_NICE_TO_HAVE = 100
    private const val CONSTRAINT_ANYTIME = 100

    fun build(config: AirPlayConfig): Map<String, Any?> {
        val displays = arrayListOf<Any?>(
            displayEntry(config.main, STREAM_TYPE_MAIN_SCREEN, MAIN_UUID),
        )
        config.cluster?.let { displays.add(displayEntry(it, STREAM_TYPE_ALT_SCREEN, ALT_UUID)) }

        val info = linkedMapOf<String, Any?>(
            "sourceVersion" to config.sourceVersion,
            "features" to if (config.disableAudioOutput) CARPLAY_FEATURES_NO_AUDIO else CARPLAY_FEATURES,
            "statusFlags" to 4L,
            "model" to config.model,
            "manufacturer" to config.manufacturer,
            "deviceID" to config.deviceId,
            "bluetoothIDs" to listOf(config.btMac),
            "name" to config.deviceName,
            "rightHandDrive" to config.rightHandDrive,
            "keepAliveLowPower" to false,
            "keepAliveSendStatsAsBody" to false,
            "modes" to modes(),
        )
        // 1.1.3（bt_audio 试验）：keep_formats 变体在清音频位的同时保留音频声明键，
        // 用于分辨 iOS 16+ 拒绝零音频会话的判定依据（features 位 vs 键缺失）。
        if (!config.disableAudioOutput || config.btAudioInfoVariant == "keep_formats") {
            info["audioLatencies"] = audioLatencies()
            info["audioFormats"] = audioFormats(
                config.entertainmentSampleRate,
                config.microphone,
                config.opusEncoderAvailable,
            )
        }
        // fe6t5 试验（vc92，无开关直接关）：移除 "enhancedRequestCarUI" —— 它是 /info 层唯一
        // 主动宣告「支持增强式 Siri 请求车机 UI」的声明位，疑为 FE-6 Siri 后无声
        // （iPhone 进 Siri 拆媒体音频流、结束后不再重发 SETUP，diplay-20261015-110554 实证）
        // 的协商载体。注意：SETUP 响应的 enabledFeatures 本就只回基础能力（不回 enhancedSiri），
        // TS7/8815 与 FE-6 在该通路上完全一致，故关闭点收敛到 extendedFeatures。
        // 若试验导致会话被拒/回退，恢复值为 listOf("vocoderInfo", "enhancedRequestCarUI")。
        info["extendedFeatures"] = listOf("vocoderInfo")
        info["displays"] = displays
        info["hidDevices"] = listOf(
            AirPlayHid.touchHidDevice(config.main.widthPixels, config.main.heightPixels, MAIN_UUID),
            AirPlayHid.knobHidDevice(MAIN_UUID),
            AirPlayHid.mediaHidDevice(MAIN_UUID),
            AirPlayHid.telephonyHidDevice(MAIN_UUID),
        )
        if (config.icons.isNotEmpty()) {
            info["oemIconVisible"] = true
            info["oemIconLabel"] = config.oemLabel
            info["oemIcons"] = config.icons.map { icon ->
                linkedMapOf(
                    "imageData" to icon.data,
                    "widthPixels" to icon.widthPixels,
                    "heightPixels" to icon.heightPixels,
                    "prerendered" to true,
                )
            }
        }
        if (config.hevc) info["hevcInfo"] = emptyMap<String, Any?>()
        return info
    }

    private fun resource(resourceId: Int): Map<String, Any?> = linkedMapOf(
        "resourceID" to resourceId,
        "transferType" to TRANSFER_TAKE,
        "transferPriority" to PRIORITY_NICE_TO_HAVE,
        "takeConstraint" to CONSTRAINT_ANYTIME,
        "borrowConstraint" to CONSTRAINT_ANYTIME,
        "unborrowConstraint" to CONSTRAINT_ANYTIME,
    )

    private fun modes(): Map<String, Any?> = linkedMapOf(
        "resources" to listOf(resource(RESOURCE_SCREEN), resource(RESOURCE_AUDIO)),
        "appStates" to listOf(
            linkedMapOf("appStateID" to 2, "state" to false),
            linkedMapOf("appStateID" to 1, "speechMode" to -1),
            linkedMapOf("appStateID" to 3, "state" to false),
        ),
    )

    private fun audioLatencies(): List<Map<String, Any?>> {
        fun base(type: Int, audioType: String? = null): Map<String, Any?> {
            val entry = linkedMapOf<String, Any?>(
                "type" to type,
                "inputLatencyMicros" to 0L,
                "outputLatencyMicros" to 0L,
            )
            if (audioType != null) entry["audioType"] = audioType
            return entry
        }
        return listOf(
            base(100), base(100, "default"), base(100, "media"), base(100, "telephony"),
            base(100, "speechRecognition"), base(100, "alert"), base(101), base(101, "default"),
            base(102, "default"),
        )
    }

    private fun audioFormats(
        entertainmentRate: Int,
        microphone: Boolean,
        opusEncoderAvailable: Boolean,
    ): List<Map<String, Any?>> {
        fun format(type: Int, audioType: String, outputFormats: Int, inputFormats: Int? = null): Map<String, Any?> {
            val entry = linkedMapOf<String, Any?>(
                "type" to type,
                "audioType" to audioType,
                "audioOutputFormats" to outputFormats,
            )
            if (inputFormats != null) entry["audioInputFormats"] = inputFormats
            return entry
        }

        val is48 = entertainmentRate == 48000
        val pcmVoice = 0x3fc
        val pcm = pcmVoice or (if (is48) 0xc000 else 0xc00)
        val pcmMono = 0x154 or (if (is48) 0x4000 else 0x400)
        val opus = 0x70000000
        val aacLc = if (is48) 0x800000 else 0x400000
        val pcmInput = if (microphone) pcmMono else null
        // M4i（T26 + M4h 结论）：iPhone 对"带无线输入的流"要求**上下行 OPUS 位一致**——
        //   K60 基线(out/in 都带) → 建流；T26(out/in 都不带) → 零流；M4h(out 带 / in 不带) → 仍零流。
        // 所以上行也声明 OPUS，让双向一致以换取 iPhone 建流。
        // 代价：上行会协商 OPUS 而本机（API<29）编不出来 —— 只影响电话/Siri 的麦克风，不阻塞播放。
        val wirelessInput = if (microphone) pcmMono or opus else null

        return listOf(
            format(100, "compatibility", pcm, pcmInput),
            format(101, "compatibility", pcm),
            // 带无线输入的三条流：上下行都带 OPUS（双向一致 = iPhone 建流的条件）。
            format(100, "default", pcm or opus, wirelessInput),
            // ⚠️ 已回退 M4n 的"不带无线输入的槽位一律只声明 PCM"实验（实测失败，勿重做）：
            //   M4n 把 100/alert、101/default 的 OPUS 位清成 `pcm`，期望逼 iPhone 发 LPCM 让 C8815
            //   的导航出声。实测（M4o 日志 logs/20261003）：/info 变成 out=0xc3fc 后，iPhone
            //   **整场一条音频流都不建**（连 type=102 音乐流都没有）⇒ 8815 彻底无声，与 T26 现象一致。
            //   对照 M4k：同样的会话里 100/alert、101/default 带 OPUS（0x7000c3fc）时音乐正常出声。
            //   结论：**OPUS 位是 iPhone 开启整个音频面的前提**，不能为"换 PCM"而清掉任何一条槽的 OPUS。
            //   代价保留：本机无 OPUS 解码器 ⇒ 导航/Siri 走 OPUS 时静音（但不崩，见 codecComponentExists 守卫）。
            format(100, "alert", pcm or opus),
            format(100, "media", pcm),
            format(100, "telephony", pcmMono or opus, wirelessInput),
            format(100, "speechRecognition", pcmMono or opus, wirelessInput),
            // T30 实测：导航/提示音落在这个槽（`type=101 audioType=default codec=OPUS`）。
            format(101, "default", pcm or opus),
            format(102, "media", aacLc),
        )
    }

    private fun displayEntry(display: AirPlayDisplayConfig, type: Int, uuid: String): Map<String, Any?> {
        val widthPhysical = AirPlayDisplaySettings.sanitizeReportedPhysicalMm(
            display.widthPhysicalMm ?: AirPlayDisplaySettings.DEFAULT_WIDTH_PHYSICAL_MM,
        )
        val heightPhysical = AirPlayDisplaySettings.sanitizeReportedPhysicalMm(
            display.heightPhysicalMm
                ?: Math.round(
                    widthPhysical * display.heightPixels.toDouble() / display.widthPixels,
                ).toInt(),
        )
        val fps = AirPlayDisplaySettings.sanitizeFps(display.fps)

        val entry = linkedMapOf<String, Any?>(
            "uuid" to uuid,
            "type" to type,
            "maxFPS" to fps,
            "widthPixels" to display.widthPixels,
            "heightPixels" to display.heightPixels,
            "widthPhysical" to widthPhysical,
            "heightPhysical" to heightPhysical,
            "features" to (display.features ?: (DISPLAY_FEATURE_HIGH_FIDELITY_TOUCH or DISPLAY_FEATURE_KNOBS)),
            "primaryInputDevice" to display.primaryInputDevice,
        )

        entry["viewAreas"] = listOf(areaDict(display))
        entry["initialViewArea"] = 0
        if (display.initialUrl != null) entry["initialURL"] = display.initialUrl
        return entry
    }

    private fun areaDict(display: AirPlayDisplayConfig): Map<String, Any?> {
        // The session SETUP response enables "viewAreas", so /info must always describe one.
        // A display without custom insets uses the full panel for both the view and safe areas.
        val view = display.viewArea ?: AirPlayInsets()
        val width = display.widthPixels
        val height = display.heightPixels
        val result = linkedMapOf<String, Any?>(
            "widthPixels" to (width - view.left - view.right),
            "heightPixels" to (height - view.top - view.bottom),
            "originXPixels" to view.left,
            "originYPixels" to view.top,
        )
        val safe = display.safeArea ?: AirPlayInsets()
        val safeArea = linkedMapOf<String, Any?>(
            "widthPixels" to (width - safe.left - safe.right),
            "heightPixels" to (height - safe.top - safe.bottom),
            "originXPixels" to safe.left,
            "originYPixels" to safe.top,
            "drawUIOutsideSafeArea" to (display.safeAreaDrawOutside ?: true),
        )
        result["safeArea"] = safeArea
        return result
    }
}
