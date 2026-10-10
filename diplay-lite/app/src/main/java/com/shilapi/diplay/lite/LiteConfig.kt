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
    // M6e：强制用软件解码视频（默认关）。给"厂商硬解异常"的机型兜底，也用于二分定位。
    val softwareVideoDecoder: Boolean = false,
    // M6k-UI：横竖屏模式。land=强制横屏 / portrait=强制竖屏 / auto=跟随传感器。
    val orientationMode: String = "auto",
    // M6k-UI：音视频路径。full=全 CarPlay（音频+视频）/ bt_audio=蓝牙出音频、CarPlay 只出视频。
    val avPathMode: String = "full",
    // M6k19：音乐缓存毫秒数（10-4000，默认 300 = MediaAudioBuffer.DEFAULT_MILLIS）。
    val mediaBufferMillis: Int = 300,
    // 1.0.29：分辨率设定三选一。fit=自动（保持纵横比，等比 letterbox）/
    // stretch=自动（拉伸铺满）/ manual=手动指定宽高。
    val resolutionMode: String = "fit",
    // 1.0.29：手动分辨率（横×纵，320-4096）。回报给 iPhone = 该值 × videoScalePercent，
    // 强制竖屏时自动交换横纵（长边为高）。
    val manualWidth: Int = 1024,
    val manualHeight: Int = 600,
    // M6k21（参考 fork carlito12345 版）：媒体/导航音频各自的音频流编号覆盖，独立生效。
    // 0=自动路由（usage 构建，默认立体声）；1~20=传统音频流编号（旧 AudioTrack 构造，
    // 含车机厂商扩展流；ROM 拒绝时播放层自动回退 usage 路由）。
    val mediaAudioStream: Int = 0,
    val navAudioStream: Int = 0,
    // M6k-UI：开机自启（BootReceiver 读它决定是否拉起主页）。
    val bootAutostart: Boolean = false,
    // 1.0.11：开机自动开启蓝牙（BootReceiver 里 BluetoothAdapter.enable() 静默执行）。
    val bootAutoBluetooth: Boolean = false,
    // 1.0.11：开机自动开启热点（蓝牙就绪后反射 setWifiApEnabled，STA/AP 互斥先关 Wi-Fi）。
    val bootAutoHotspot: Boolean = false,
    // 1.0.29：开机自动开启 Wi-Fi（setWifiEnabled(true) 静默执行；与热点互斥，勾了热点则跳过）。
    val bootAutoWifi: Boolean = false,
    // M6k-UI：启动后自动连接（主页 onResume 读它发起）。
    val autoConnect: Boolean = false,
    // M6k14：默认自动连接通道，三选一互斥（主页 toggle）。car=车机热点 / relay=中继热点 / usb=USB。
    val activeChannel: String = "car",
    // M6k14：中继热点（随身WiFi）凭据 —— 手机与车机同连该 WiFi，适用于车机无法开热点的 ROM。
    // 连接编排依赖中继热点方案（GLM）。
    val relaySsid: String = "",
    val relayPassword: String = "",
    // M6k14：热点首选信道（0=自动）。车机/中继两个配置页各存一份、独立使用。
    val carIpv6Channel: Int = 0,
    val carIpv4Channel: Int = 0,
    val relayIpv6Channel: Int = 0,
    val relayIpv4Channel: Int = 0,
    // 1.0.17：CarPlay 显示名称（/info name + Bonjour 服务名）。自定义返回图标页可改，
    // 选厂家预置图标时自动替换为厂家名；空串回落默认。
    // 1.1.1：默认改 "BEI JING"（用户指定，配合内置 BEIJING 车标图标）。
    val carIconName: String = "BEI JING",
) {
    companion object {
        private const val PREFS = "diplay_lite"
        // M6k：分辨率下限由 50 放宽到 10 —— 高分辨率车机（如 1080p）可借此把码流压得更低。
        // M6k14：上限放宽到 150（渲染图 10%-150%）；>100 是放大超采样，应用时有二次确认兜底。
        const val MIN_SCALE_PERCENT = 10
        const val MAX_SCALE_PERCENT = 150
        // 压低上限放宽到 100：用户可以选择"不压低"（100 = 等于没压）。
        const val MIN_DUCK_PERCENT = 30
        const val MAX_DUCK_PERCENT = 100

        // M6k19：音乐缓存输入范围（ms）
        const val MIN_MEDIA_BUFFER_MILLIS = 10
        const val MAX_MEDIA_BUFFER_MILLIS = 4000

        // 1.0.29：手动分辨率输入范围（横/纵各自限制）。
        const val MIN_MANUAL_RESOLUTION = 320
        const val MAX_MANUAL_RESOLUTION = 4096

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
                orientationMode = prefs.getString("orientation_mode", "auto") ?: "auto",
                avPathMode = prefs.getString("av_path_mode", "full") ?: "full",
                mediaBufferMillis = prefs.getInt("media_buffer_millis", 300),
                resolutionMode = prefs.getString("video_resolution_mode", "fit") ?: "fit",
                manualWidth = prefs.getInt("manual_resolution_w", 1024)
                    .coerceIn(MIN_MANUAL_RESOLUTION, MAX_MANUAL_RESOLUTION),
                manualHeight = prefs.getInt("manual_resolution_h", 600)
                    .coerceIn(MIN_MANUAL_RESOLUTION, MAX_MANUAL_RESOLUTION),
                mediaAudioStream = prefs.getInt("media_audio_stream", 0).let { if (it in 0..20) it else 0 },
                navAudioStream = prefs.getInt("nav_audio_stream", 0).let { if (it in 0..20) it else 0 },
                bootAutostart = prefs.getBoolean("boot_autostart", false),
                bootAutoBluetooth = prefs.getBoolean("boot_auto_bluetooth", false),
                bootAutoHotspot = prefs.getBoolean("boot_auto_hotspot", false),
                bootAutoWifi = prefs.getBoolean("boot_auto_wifi", false),
                autoConnect = prefs.getBoolean("auto_connect", false),
                activeChannel = prefs.getString("active_channel", "car") ?: "car",
                relaySsid = prefs.getString("relay_ssid", "").orEmpty(),
                relayPassword = prefs.getString("relay_password", "").orEmpty(),
                carIpv6Channel = prefs.getInt("car_ipv6_channel", 0),
                carIpv4Channel = prefs.getInt("car_ipv4_channel", 0),
                relayIpv6Channel = prefs.getInt("relay_ipv6_channel", 0),
                relayIpv4Channel = prefs.getInt("relay_ipv4_channel", 0),
                carIconName = prefs.getString("car_icon_name", "BEI JING").orEmpty().ifBlank { "BEI JING" },
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
                .putString("orientation_mode", config.orientationMode)
                .putString("av_path_mode", config.avPathMode)
                .putInt("media_buffer_millis", config.mediaBufferMillis)
                .putInt("media_audio_stream", config.mediaAudioStream)
                .putInt("nav_audio_stream", config.navAudioStream)
                .putBoolean("boot_autostart", config.bootAutostart)
                .putBoolean("boot_auto_bluetooth", config.bootAutoBluetooth)
                .putBoolean("boot_auto_hotspot", config.bootAutoHotspot)
                .putBoolean("boot_auto_wifi", config.bootAutoWifi)
                .putBoolean("auto_connect", config.autoConnect)
                .putString("active_channel", config.activeChannel)
                .putString("relay_ssid", config.relaySsid)
                .putString("relay_password", config.relayPassword)
                .putInt("car_ipv6_channel", config.carIpv6Channel)
                .putInt("car_ipv4_channel", config.carIpv4Channel)
                .putInt("relay_ipv6_channel", config.relayIpv6Channel)
                .putInt("relay_ipv4_channel", config.relayIpv4Channel)
                .putString("car_icon_name", config.carIconName)
                // 注意：applied_video_key 故意不在这里写 —— 它只由 saveAppliedVideoKey() 维护，
                // 否则表单保存（每次勾选都会触发）会用默认值把它冲掉。
                //
                // M6k24i：必须 commit()（同步）而非 apply()（异步）——分辨率/横竖屏修改走
                // 「保存→killProcess 自重启」路径，apply 的磁盘写入还没落进程就没了，
                // 重启后读回旧值，表现为"强制竖屏不生效/分辨率改了没变"（nova 3e 实测）。
                // 本表极小，同步 commit 开销可忽略。
                .commit()
        }
    }
}
