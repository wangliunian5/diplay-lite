package com.shilapi.diplay.lite

import android.content.Context

/**
 * MANUAL（车机自带热点）配置的持久化。
 * 对应低版本分支 AirPlayPersistence 的 MANUAL 子集，只存凭据，不存任何手机标识。
 */
data class LiteConfig(
    val ssid: String,
    val password: String,
    val channel: Int,          // 0 = 未知/自动（0x5703 下发前应尽量实测填充，难点 B3）
    val security: String,      // WPA2 / WPA2-WPA3 / WPA3 / OPEN
    val bluetoothAddress: String, // 空 = 自动选择已配对 iPhone
    // M6b：弱机性能开关。limit30Fps = true 时 /info 的 maxFPS 声明 30（默认 60）。
    val limit30Fps: Boolean = false,
    // M6b：/info 声明分辨率 = 车机屏 × 该百分比（50~100，整数）；100 = 原生、不改。
    val videoScalePercent: Int = 100,
    // M6c：CarPlay 通话声音走扬声器（false = 听筒，本机默认）。
    val speakerForCalls: Boolean = false,
    // M6d：导航播报时把音乐"压低"到的百分比（30~70，整数；100 之外的值都钳到区间内）。
    val navDuckPercent: Int = 50,
    // M6d：上一次连接时实际生效的"分辨率/帧率"键（"<scale>/<limit30>"）。
    // 用来判断本次是否改了视频参数 —— 改了必须先断开重连（否则渲染异常），据此弹二次确认。
    val appliedVideoKey: String = "",
    // M6e：强制用软件解码器解视频（默认关）。给"厂商硬解异常"的机型兜底，也用于二分定位。
    val softwareVideoDecoder: Boolean = false,
) {
    companion object {
        private const val PREFS = "diplay_lite"
        // M6k：分辨率下限由 50 放宽到 10 —— 高分辨率车机（如 1080p）可借此把码流压得更低。
        const val MIN_SCALE_PERCENT = 10
        const val MAX_SCALE_PERCENT = 100
        const val MIN_DUCK_PERCENT = 30
        const val MAX_DUCK_PERCENT = 70

        /** M6d：视频参数是否与"上次连接时生效的值"一致（不一致 = 必须先断开重连）。 */
        fun videoKeyOf(scalePercent: Int, limit30Fps: Boolean): String = "$scalePercent/$limit30Fps"

        fun saveAppliedVideoKey(context: Context, key: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("applied_video_key", key)
                .apply()
        }

        /** M6k3：蓝牙点选缓存的设备名，只用于按钮展示（名字不参与连接）。 */
        fun bluetoothDeviceName(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("bt_name", "")
                .orEmpty()

        /** M6k3：蓝牙点选落盘入口。bt_address 与 bt_name 成对写，避免半新半旧。 */
        fun saveBluetoothPick(context: Context, address: String, name: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("bt_address", address)
                .putString("bt_name", name)
                .apply()
        }

        fun load(context: Context): LiteConfig {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return LiteConfig(
                ssid = prefs.getString("ssid", "").orEmpty(),
                password = prefs.getString("password", "").orEmpty(),
                channel = prefs.getInt("channel", 0),
                security = prefs.getString("security", "WPA2") ?: "WPA2",
                bluetoothAddress = prefs.getString("bt_address", "").orEmpty(),
                limit30Fps = prefs.getBoolean("limit_30fps", false),
                videoScalePercent = prefs.getInt("video_scale_percent", MAX_SCALE_PERCENT)
                    .coerceIn(MIN_SCALE_PERCENT, MAX_SCALE_PERCENT),
                speakerForCalls = prefs.getBoolean("speaker_for_calls", false),
                navDuckPercent = prefs.getInt("nav_duck_percent", 50)
                    .coerceIn(MIN_DUCK_PERCENT, MAX_DUCK_PERCENT),
                appliedVideoKey = prefs.getString("applied_video_key", "").orEmpty(),
                softwareVideoDecoder = prefs.getBoolean("software_video_decoder", false),
            )
        }

        fun save(context: Context, config: LiteConfig) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("ssid", config.ssid)
                .putString("password", config.password)
                .putInt("channel", config.channel)
                .putString("security", config.security)
                .putString("bt_address", config.bluetoothAddress)
                .putBoolean("limit_30fps", config.limit30Fps)
                .putInt("video_scale_percent", config.videoScalePercent)
                .putBoolean("speaker_for_calls", config.speakerForCalls)
                .putInt("nav_duck_percent", config.navDuckPercent)
                .putBoolean("software_video_decoder", config.softwareVideoDecoder)
                // 注意：applied_video_key 故意不在这里写 —— 它只由 saveAppliedVideoKey() 维护，
                // 否则表单保存（每次勾选都会触发）会用默认值把它冲掉。
                .apply()
        }
    }
}
