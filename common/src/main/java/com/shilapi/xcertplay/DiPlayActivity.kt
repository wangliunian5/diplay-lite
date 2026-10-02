// SPDX-License-Identifier: AGPL-3.0-only
// UI copy and visual language adapted from DiAuto. See docs/THIRD_PARTY_NOTICES.md.
package com.shilapi.xcertplay

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.BandwidthMonitor
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** DiAuto's visual language, with a connection flow for an independent CarPlay receiver. */
class DiPlayActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var page = "home"
    private var pendingCarHotspotSetup = false
    private var pendingHotspotMode = WirelessHotspotMode.MANUAL
    private var setupError: String? = null
    private var status: TextView? = null
    private var connectButton: Button? = null
    private var disconnectButton: Button? = null
    private var lastRunning: Boolean? = null
    private var pendingWireless = false
    private var initialLaunch = true
    private var notificationTransport = true
    private var exportInProgress = false
    private var exportButton: Button? = null
    private val logoTapTimes = ArrayDeque<Long>()
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        connect(notificationTransport)
    }
    private val tick = object : Runnable {
        override fun run() { refreshStatus(); handler.postDelayed(this, 1000) }
    }
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) choosePhone() else permissionHelp("附近设备", "请允许“附近设备”权限，以便 DiPlay 连接到您已配对的 iPhone。")
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) exportDiagnostics(uri)
    }

    /**
     * 点 5 次主界面中央的 DiPlay 图标以启动调试日志（带宽监测面板 + BW 日志）。进程级开关，
     * 冷启动后失效，需重新点 5 次。
     */
    private fun onLogoDebugTap() {
        val now = SystemClock.elapsedRealtime()
        logoTapTimes.addLast(now)
        while (logoTapTimes.isNotEmpty() && now - logoTapTimes.first() > 2500) logoTapTimes.removeFirst()
        if (logoTapTimes.size >= 5) {
            logoTapTimes.clear()
            BandwidthMonitor.overlayRequested = true
            toast("调试日志已开启：CarPlay 界面将显示带宽面板")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.shilapi.xcertplay.hud.BydNavigationOutputs.onAppOpened(applicationContext)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = BG; window.navigationBarColor = BG
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            hide(WindowInsetsCompat.Type.statusBars())
        }
        setupError = runCatching { DiPlayBootstrap.ensure(this) }.exceptionOrNull()?.let {
            android.util.Log.e("DiPlaySetup", "CarPlay authentication could not be loaded", it)
            "无法加载 CarPlay 认证。请在此应用上安装完整的 DiPlay 构建。无需卸载或更改热点。"
        }
        pendingCarHotspotSetup = savedInstanceState?.getBoolean("pending_car_hotspot") ?: false
        pendingHotspotMode = savedInstanceState?.getString("pending_hotspot_mode")
            ?.let { stored -> WirelessHotspotMode.entries.firstOrNull { it.name == stored } }
            ?.takeIf { it.isManualHotspot } ?: WirelessHotspotMode.MANUAL
        page = savedInstanceState?.getString("page") ?: intent.getStringExtra("page") ?: "home"
        render()
        handleWirelessRecovery()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page != "home") { page = "home"; render() }
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
        showDisclaimerDialog()
    }

    /** 每次启动软件时展示免责提示。本版本为第三方汉化修改版，供用户自行承担后果地学习/测试。 */
    private fun showDisclaimerDialog() {
        val notice =
            "本应用为“DiPlay汉化”，基于开源 DiPlay 汉化修改，并非 Apple 官方或项目作者的版本。\n\n" +
                "免责声明：\n" +
                "1. 本版本仅供个人学习与技术测试使用，请遵守所在地区法律法规。\n" +
                "2. 使用 CarPlay 功能时请遵守交通法规，切勿在行车过程中进行任何分散注意力的操作，安全驾驶第一。\n" +
                "3. 若因使用本版本造成设备异常、连接问题或任何损失，作者不承担任何责任。\n\n" +
                "继续使用即表示您已知晓并同意以上内容。"
        AlertDialog.Builder(this)
            .setTitle("免责提示")
            .setMessage(notice)
            .setCancelable(false)
            .setPositiveButton("我已阅读并同意") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        page = intent.getStringExtra("page") ?: "home"; render()
        handleWirelessRecovery()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", page)
        outState.putBoolean("pending_car_hotspot", pendingCarHotspotSetup)
        outState.putString("pending_hotspot_mode", pendingHotspotMode.name)
        super.onSaveInstanceState(outState)
    }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); render() }
    override fun onResume() {
        super.onResume(); handler.removeCallbacks(tick); handler.post(tick)
        // Back from the car settings: refresh the car hotspot reminder on the home page.
        if (!initialLaunch && (page == "home" || page == "settings" || page == "connection")) render()
        if (initialLaunch) {
            initialLaunch = false
            if (setupError == null && !CarPlayBackgroundSession.hasSession() &&
                DiPlayPreferences.autoConnect(this) && intent.getStringExtra("page") == null) {
                handler.post { connect(AirPlayPersistence.loadWirelessEnabled(this)) }
            }
        }
    }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }

    private fun render() {
        status = null; connectButton = null; disconnectButton = null; lastRunning = null
        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true; clipToPadding = false }
        val content = column().apply { setPadding(dp(32), dp(24), dp(32), dp(32)) }
        scroll.addView(content)
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply { setImageResource(R.drawable.ic_carplay); contentDescription = "CarPlay" }, LinearLayout.LayoutParams(dp(36), dp(36)))
        header.addView(label("DiPlay", 26, TEXT, true).apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, dp(56), 1f))
        header.addView(button(if (page == "home") "车机主页" else "返回", false) {
            if (page == "home") startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            else { page = "home"; render() }
        }, LinearLayout.LayoutParams(dp(130), dp(56)))
        content.addView(header)
        content.addView(space(24))
        when (page) {
            "connection" -> connectionSetup(content)
            "settings" -> settings(content)
            "about" -> about(content)
            else -> home(content)
        }
        setContentView(scroll)
        refreshStatus()
    }

    private fun home(content: LinearLayout) {
        val wide = resources.configuration.screenWidthDp >= 850
        val body = column()
        val left = column()
        left.addView(label("你的手机 · 你的驾驶。", 12, ACCENT, true).apply { letterSpacing = .16f })
        left.addView(label("熟悉的驾驶体验。", if (wide) 42 else 36, TEXT, true).apply { setPadding(0, dp(12), 0, dp(10)) })
        left.addView(label("你的地图、音乐与通话。\nCarPlay，就在你的车载屏幕上。", 19, MUTED))
        val card = card()
        card.addView(label("无线 CARPLAY", 12, ACCENT, true).apply { letterSpacing = .12f })
        status = label("随时准备就绪", 24, TEXT, true).apply { setPadding(0, dp(10), 0, dp(16)) }
        card.addView(status)
        connectButton = button("连接手机", true) {
            if (CarPlayBackgroundSession.hasSession()) openProjection()
            else connect(true)
        }
        card.addView(connectButton, matchButton())
        val connectionHint = when (AirPlayPersistence.loadWirelessHotspotMode(this)) {
            WirelessHotspotMode.MANUAL -> "车机自带热点IPv6 · 请保持车机热点、蓝牙和 iPhone 的 Wi-Fi 开启。"
            WirelessHotspotMode.MANUAL_IPV4 -> "车机自带热点IPv4 · 请保持车机热点、蓝牙和 iPhone 的 Wi-Fi 开启。"
            WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> "打开连接设置以保存车机自带热点的信息。"
            else -> "Wi-Fi Direct · 请保持车机的 Wi-Fi 开关、蓝牙和 iPhone 的 Wi-Fi 开启。"
        }
        card.addView(label(connectionHint, 15, MUTED).apply { setPadding(0, dp(14), 0, 0) })
        if (carHotspotOff()) {
            card.addView(label("车机热点“${AirPlayPersistence.loadManualHotspotSsid(this)}”已关闭。连接前请在车机设置中开启。", 15, WARNING).apply { setPadding(0, dp(14), 0, 0) })
            card.addView(button("打开车机热点设置", false) { openCarWifiSettings() }, matchButton(10, 56))
        }
        card.addView(button("选择 iPhone", false) { choosePhone() }, matchButton(16, 56))
        disconnectButton = button("断开连接", false) {
            disconnectButton?.isEnabled = false
            CarPlayBackgroundSession.stop { runOnUiThread { refreshStatus() } }
        }.apply { visibility = View.GONE }
        card.addView(disconnectButton, matchButton(10, 56))
        val right = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_carplay)
            contentDescription = "CarPlay 图标"
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener { onLogoDebugTap() }
        }
        val branding = column().apply {
            gravity = Gravity.CENTER
            addView(logo, LinearLayout.LayoutParams(dp(96), dp(96)))
        }
        right.addView(button("使用 USB 连接", false) { connect(false) }, matchButton())
        right.addView(label("将 iPhone 插入 USB 数据接口。\n当 iPhone 提示时，请允许 CarPlay。", 14, MUTED).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), dp(24)) })
        right.addView(button("设置", false) { page = "settings"; render() }, matchButton())
        right.addView(label("让 DiPlay 更贴合你的爱车。", 14, MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(24)) })
        right.addView(label("公开预览版 · ${version()}", 12, MUTED).apply { letterSpacing = .08f })
        if (wide) {
            // Both rows share column widths. The USB button starts at the wireless
            // card's top edge, independently of hero wrapping or font scaling.
            fun columns(first: View, second: View, stretchSecond: Boolean = false) = row().apply {
                gravity = Gravity.TOP
                addView(first, LinearLayout.LayoutParams(0, -2, 1.6f))
                addView(space(40), LinearLayout.LayoutParams(dp(40), 1))
                addView(second, LinearLayout.LayoutParams(0, if (stretchSecond) -1 else -2, 1f))
            }
            body.addView(columns(left, branding, true))
            body.addView(space(26))
            body.addView(columns(card, right))
        } else {
            body.addView(left)
            body.addView(space(26))
            body.addView(card)
            body.addView(space(26))
            body.addView(branding)
            body.addView(space(24))
            body.addView(right)
        }
        setupError?.let { body.addView(label(it, 16, WARNING).apply { setPadding(0, dp(16), 0, 0) }) }
        content.addView(body)
    }

    private fun settings(content: LinearLayout) {
        content.addView(label("你的驾驶，你做主。", 34, TEXT, true))
        content.addView(label("应用更改时 CarPlay 会重新连接以应用尺寸、分辨率、音乐缓冲和帧率设置。其他更改将在下次连接时生效。", 17, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        section(content, "连接设置", R.drawable.ic_dp_connection) { card ->
            card.addView(label("选择连接方式，按照设置步骤操作并保存车机热点详情。", 16, MUTED))
            card.addView(button("打开连接设置", false) { page = "connection"; render() }, matchButton(12, 60))
        }
        section(content, "诊断", R.drawable.ic_dp_diagnostics) { card ->
            exportButton = button(if (exportInProgress) "正在保存报告…" else "保存诊断报告", false) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) exportDiagnostics()
                else chooseReportDestination()
            }.apply { isEnabled = !exportInProgress }
            card.addView(exportButton, matchButton(10, 60))
            card.addView(button("选择保存位置", false) { chooseReportDestination() }, matchButton(10, 60))
            val destination = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "报告将保存到 Downloads/DiPlay。 " else "请选择报告的保存位置。 "
            card.addView(label(destination + "不会自动发送任何内容。协议负载和凭据均已排除。", 14, MUTED).apply { setPadding(0, dp(12), 0, 0) })
        }
        section(content, "自动连接", R.drawable.ic_dp_automation) { card ->
            toggle(card, "打开 DiPlay 时自动连接", "使用你上次的连接方式和所选 iPhone。", DiPlayPreferences.autoConnect(this)) { DiPlayPreferences.saveAutoConnect(this, it) }
            toggle(card, "汽车启动后自动打开", "是否可用取决于车机启动设置。", AirPlayPersistence.loadAutoStartOnBoot(this)) { AirPlayPersistence.saveAutoStartOnBoot(this, it) }
            card.addView(button("选择 iPhone · ${DiPlayPreferences.phoneName(this)}", false) { choosePhone() }, matchButton(12, 60))
        }
        section(content, "显示和性能", R.drawable.ic_dp_display) { card ->
            carPlaySizeControl(card)
            choice(card, "分辨率", listOf("原始", "80% · 负载较轻", "60% · 负载最轻"), listOf(10, 8, 6).indexOf(AirPlayPersistence.loadDisplayScaleTenths(this)).coerceAtLeast(0)) { AirPlayPersistence.saveDisplayScaleTenths(this, listOf(10, 8, 6)[it]) }
            val bufferPresets = com.shilapi.xcertplay.media.MediaAudioBuffer.presets
            choice(card, "音乐缓冲", listOf("300 毫秒 · 默认", "500 毫秒", "1000 毫秒 · 最稳定"),
                bufferPresets.indexOf(AirPlayPersistence.loadMediaBufferMillis(this)).coerceAtLeast(0)) {
                AirPlayPersistence.saveMediaBufferMillis(this, bufferPresets[it])
            }
            choice(card, "帧率", listOf("30 帧/秒 · 负载较轻", "60 帧/秒 · 更流畅"), if (AirPlayPersistence.loadFps(this) == 60) 1 else 0) { AirPlayPersistence.saveFps(this, if (it == 1) 60 else 30) }
            toggle(card, "高效视频", "使用 HEVC。如需最广的车机兼容性，请保持关闭。", AirPlayPersistence.loadHevcEnabled(this)) { AirPlayPersistence.saveHevcEnabled(this, it) }
            toggle(card, "软件解码", "画面绿屏或卡顿的老车机请开启：改用兼容的软件视频解码器（H.264 与 HEVC 均生效）。", AirPlayPersistence.loadHevcSoftwareDecoderEnabled(this)) { AirPlayPersistence.saveHevcSoftwareDecoderEnabled(this, it) }
            toggle(card, "右手驾驶", "将 CarPlay 的控件放置在更靠近驾驶员的一侧。", AirPlayPersistence.loadRightHandDrive(this)) { AirPlayPersistence.saveRightHandDrive(this, it) }
            toggle(card, "全屏", "CarPlay 打开时隐藏车机系统栏。", AirPlayPersistence.loadHideTopBar(this) && AirPlayPersistence.loadHideBottomBar(this)) {
                AirPlayPersistence.saveHideTopBar(this, it); AirPlayPersistence.saveHideBottomBar(this, it)
            }
        }
        if (com.shilapi.xcertplay.hud.BydOutputSettings.available(this)) section(content, "BYD 导航", R.drawable.ic_dp_navigation) { card ->
            toggle(card, "在 HUD 和仪表盘上显示导航",
                "在支持的 BYD 显示屏上显示手机导航箭头、距离和街道名称。不同车型的兼容性有所差异。",
                com.shilapi.xcertplay.hud.BydOutputSettings.enabled(this)) { com.shilapi.xcertplay.hud.BydOutputSettings.setEnabled(this, it) }
            if (ClusterMapPresentation.findDisplay(this) != null) {
                toggle(card, "在仪表盘上显示 CarPlay 地图 · 实验性",
                    "在仪表盘上显示 iPhone 的仪表地图。请在方向盘的仪表菜单中选择小屏或全屏导航。",
                    AirPlayPersistence.loadClusterMapEnabled(this)) {
                    AirPlayPersistence.saveClusterMapEnabled(this, it)
                    reconnectForClusterMap()
                }
                if (DiLink51ClusterLayout.supported()) {
                    val automatic = DiLink51ClusterLayout.automatic(this)
                    toggle(card, "跟随仪表主题和地图卡片",
                        "仅在地图卡片打开时显示侧边地图，并在“地图”主题中切换到全屏地图。主题和卡片的更改会保持 CarPlay 连接。", automatic) {
                        DiLink51ClusterLayout.saveAutomatic(this, it)
                        render()
                        reconnectForClusterMap()
                    }
                    val allowed = DiLink51ClusterMonitor.hasAccess(this)
                    card.addView(label(if (allowed) "使用情况访问权限：已启用"
                        else "使用情况访问权限：自动模式需要先进行设置", 14, if (allowed) MUTED else WARNING))
                    card.addView(button("自动地图设置 · ADB", false) { showClusterAccessSetup() }, matchButton(10, 56))
                    if (!automatic) {
                        val themes = DiLink51ClusterLayout.Theme.entries
                        choice(card, "仪表主题", themes.map { it.label }, themes.indexOf(DiLink51ClusterLayout.theme(this))) {
                            DiLink51ClusterLayout.saveTheme(this, themes[it])
                            reconnectForClusterMap()
                        }
                        card.addView(label("手动模式：请在此匹配仪表主题。没有使用情况访问权限时，地图无法跟随卡片的可见状态。", 14, MUTED))
                    }
                    val contrasts = DiLink51ClusterLayout.Contrast.entries
                    choice(card, "仪表对比度", contrasts.map { it.label }, contrasts.indexOf(DiLink51ClusterLayout.contrast(this))) {
                        DiLink51ClusterLayout.saveContrast(this, contrasts[it])
                        reconnectForClusterMap()
                    }
                } else {
                    val sizes = CarPlayClusterDisplay.scalePresets
                    choice(card, "仪表地图大小", listOf("标准 · 最清晰", "较大 · 默认", "最大"),
                        sizes.indexOf(AirPlayPersistence.loadClusterMapScalePercent(this)).coerceAtLeast(0)) {
                        AirPlayPersistence.saveClusterMapScalePercent(this, sizes[it])
                    }
                    val across = CarPlayClusterDisplay.horizontalSteps.toList()
                    choice(card, "车辆标记 · 水平", across.map { markerStepLabel(it, "左", "右") },
                        across.indexOf(AirPlayPersistence.loadClusterMarkerHorizontalStep(this)).coerceAtLeast(0)) {
                        AirPlayPersistence.saveClusterMarkerHorizontalStep(this, across[it])
                    }
                    val upDown = CarPlayClusterDisplay.verticalSteps.toList()
                    choice(card, "车辆标记 · 垂直", upDown.map { markerStepLabel(it, "上", "下") },
                        upDown.indexOf(AirPlayPersistence.loadClusterMarkerVerticalStep(this)).coerceAtLeast(0)) {
                        AirPlayPersistence.saveClusterMarkerVerticalStep(this, upDown[it])
                    }
                    card.addView(button("将车辆标记重置到中心", false) {
                        AirPlayPersistence.saveClusterMarkerHorizontalStep(this, 0)
                        AirPlayPersistence.saveClusterMarkerVerticalStep(this, 0)
                        render()
                        reconnectForClusterMap()
                    }, matchButton(10, 56))
                }
            }
        }
        section(content, "权限和连接帮助", R.drawable.ic_dp_permissions) { card ->
            card.addView(label("附近设备权限用于连接你的 iPhone。麦克风权限用于启用 Siri 和通话。较旧的 Android 版本在无线设置时还需要定位权限。USB 模式可能会要求建立本地 VPN 连接。", 16, MUTED))
            card.addView(button("应用权限", false) { openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }, matchButton(16, 60))
            card.addView(button("蓝牙设置", false) { openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }, matchButton(10, 60))
            card.addView(button("无线连接帮助", false) { wirelessHelp() }, matchButton(10, 60))
        }
        section(content, "关于", R.drawable.ic_dp_about) { card ->
            card.addView(button("关于 DiPlay", false) { page = "about"; render() }, matchButton(0, 60))
        }
    }

    private fun about(content: LinearLayout) {
        content.addView(label("DiPlay", 40, TEXT, true))
        content.addView(label("CarPlay，自在驰骋于你的车内。", 20, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        section(content, "公开预览版 · ${version()}") { card ->
            card.addView(label("一款适用于 Android 车机的独立 CarPlay 接收器。有线和无线连接均在车机上运行，并采用本地认证。普通 iPhone 无需越狱、Mac、外设或登录即可连接。\n\n此预览版使用实验性配件身份。与所有 iPhone 和车机的兼容性仍在测试中。它不是苹果认证的产品。", 17, TEXT))
        }
        section(content, "得益于开源") { card ->
            card.addView(label("接收器基于 xcertplay，采用 GPL-3.0 许可。DiPlay 的界面遵循 DiAuto 的设计，采用 AGPL-3.0 许可。\n\n包含 AndroidX、Bouncy Castle、JmDNS 和 SLF4J。本版本附带源代码和许可声明。\n\nCarPlay 和 CarPlay 图标属于 Apple Inc.。DiPlay 是一个独立项目。", 16, MUTED))
        }
    }

    // The car hotspot link needs the hotspot on; DiPlay only checks it (turning it on needs ADB-only permission).
    private fun carHotspotOff(): Boolean =
        AirPlayPersistence.loadWirelessHotspotMode(this).isManualHotspot &&
            com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this) == false

    private fun carHotspotOffDialog() {
        AlertDialog.Builder(this).setTitle("车机热点已关闭")
            .setMessage("DiPlay 通过车机热点“${AirPlayPersistence.loadManualHotspotSsid(this)}”进行连接。请在车机设置中开启热点，然后连接。")
            .setPositiveButton("打开车机设置") { _, _ -> openCarWifiSettings() }
            .setNeutralButton("连接") { _, _ -> connect(true) }
            .setNegativeButton("取消", null).show()
    }

    // BYD maps the AOSP tether action to its own hotspot screen; other firmware falls back to Wi-Fi settings.
    // BYD shows that screen as a dialog and closes it unless its own settings or the car home screen is on top,
    // so the home screen goes first.
    private fun openCarWifiSettings() {
        val hotspot = Intent("com.android.settings.WIFI_TETHER_SETTINGS")
        val target = packageManager.resolveActivity(hotspot, 0)?.activityInfo?.packageName
        if (target == null) {
            openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            return
        }
        if (target == "com.byd.carsettings") {
            runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
        }
        if (runCatching { startActivity(hotspot) }.isSuccess) return
        openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }

    private fun connectionSetup(content: LinearLayout) {
        content.addView(label("连接设置", 34, TEXT, true))
        content.addView(label("只需设置一次。你的详细信息会保存到下次驾驶。更改将在下次连接时生效。", 17, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        section(content, "1 · 选择连接方式") { card -> wirelessLinkControls(card) }
        section(content, "2 · 配对您的 iPhone") { card ->
            card.addView(label("请保持 iPhone 的蓝牙和 Wi-Fi 开启。与车机蓝牙配对，然后在此处选择你的 iPhone。如被询问，请允许“附近设备”权限。", 16, MUTED))
            card.addView(button("选择 iPhone · ${DiPlayPreferences.phoneName(this)}", false) { choosePhone() }, matchButton(12, 60))
            card.addView(button("查看应用权限", false) {
                openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }, matchButton(12, 60))
        }
        section(content, "3 · 连接") { card ->
            card.addView(label("从车机设置返回 DiPlay，然后连接。请在 iPhone 上接受 CarPlay 提示。无需车机上网套餐；移动数据是否可用取决于你手机的网络设置。", 16, MUTED))
            card.addView(button("连接手机", true) { connect(true) }, matchButton(12, 60))
        }
        section(content, "偏好使用数据线？") { card ->
            card.addView(label("使用 USB 数据线和车机的 USB 数据接口。解锁 iPhone 并允许 CarPlay。无需设置热点。", 16, MUTED))
            card.addView(button("使用 USB 连接", false) { connect(false) }, matchButton(12, 60))
        }
    }

    private fun wirelessLinkControls(parent: LinearLayout) {
        val mode = if (pendingCarHotspotSetup) pendingHotspotMode else AirPlayPersistence.loadWirelessHotspotMode(this)
        // Wi-Fi Direct is unavailable on this build; the car hotspot is offered over IPv6 and IPv4.
        val modes = listOf(WirelessHotspotMode.MANUAL, WirelessHotspotMode.MANUAL_IPV4)
        val titles = listOf("车机自带热点IPV6", "车机自带热点IPV4")
        val descriptions = listOf(
            "使用车机自带热点，通过 IPv6 链路本地地址连接。如可用，请在车机设置中选择 5 GHz。",
            "备选方案。使用同一个车机自带热点，改用 IPv4 地址连接；适合 IPv6 方式连不上的车型。"
        )
        val wide = resources.configuration.screenWidthDp >= 850
        val choices = if (wide) row().apply { gravity = Gravity.TOP } else column()
        parent.addView(choices)
        modes.forEachIndexed { index, candidate ->
            val option = column()
            choices.addView(option, if (wide) LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (index > 0) marginStart = dp(16)
            } else LinearLayout.LayoutParams(-1, -2))
            option.addView(button("${if (mode == candidate) "✓  " else ""}${titles[index]}", mode == candidate) {
                if (candidate.isManualHotspot) {
                    pendingCarHotspotSetup = true
                    pendingHotspotMode = candidate
                    render()
                } else {
                    pendingCarHotspotSetup = false
                    applyWirelessLink(candidate)
                }
            }, matchButton(12, 60))
            option.addView(label(descriptions[index], 15, MUTED).apply { setPadding(0, dp(6), 0, dp(12)) })
        }
        if (mode.isManualHotspot) {
            parent.addView(label("热点设置", 22, TEXT, true))
            parent.addView(label("1. 打开车机热点设置，开启热点，如可用请选择 5 GHz。\n2. 在下方准确复制其名称和密码。\n3. 连接时保持车机热点开启。DiPlay 会通过蓝牙发送详细信息；你的 iPhone 会自动加入。", 16, MUTED).apply { setPadding(0, dp(8), 0, dp(12)) })
            parent.addView(button("打开车机热点设置", false) { openCarWifiSettings() }, matchButton(0, 60))
            parent.addView(button(if (pendingCarHotspotSetup) "保存热点详情并使用此模式" else "编辑已保存的热点 · ${storedSsid()}", false) {
                askHotspotCredentials { ssid, password ->
                    saveHotspotCredentials(ssid, password)
                    pendingCarHotspotSetup = false
                    applyWirelessLink(pendingHotspotMode)
                }
            }, matchButton(12, 60))
            parent.addView(label(if (pendingCarHotspotSetup) "完成设置 · 保存热点详情以使用此模式。" else if (carHotspotOff()) "热点已关闭 · 请在车机设置中开启。" else "详情已保存 · 连接前请确认车机热点已开启。", 15, if (carHotspotOff()) WARNING else MUTED).apply { setPadding(0, dp(12), 0, 0) })
        }
    }

    private fun storedSsid() = AirPlayPersistence.loadManualHotspotSsid(this)
    private fun storedPassword() = AirPlayPersistence.loadManualHotspotPassphrase(this)
    private fun hotspotError(ssid: String, password: String) =
        com.shilapi.xcertplay.orchestration.ManualHotspotValidation.validate(ssid, password)

    private fun saveHotspotCredentials(ssid: String, password: String) {
        AirPlayPersistence.saveManualHotspotSsid(this, ssid)
        AirPlayPersistence.saveManualHotspotPassphrase(this, password)
        AirPlayPersistence.saveManualHotspotSecurity(this,
            com.shilapi.xcertplay.orchestration.ManualHotspotValidation.securityFor(password))
        AirPlayPersistence.saveManualHotspotBand(this, com.shilapi.xcertplay.orchestration.ManualHotspotBand.AUTO)
        AirPlayPersistence.saveManualHotspotChannel(this, 0)
    }

    private fun askHotspotCredentials(done: (String, String) -> Unit) {
        val fields = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        fields.addView(label("请从车机热点设置中复制这些信息。如可用请使用 5 GHz。在此保存不会改变车机热点。", 16, MUTED))
        val ssid = EditText(this).apply { hint = "热点名称"; setText(storedSsid()); setSingleLine() }
        val password = EditText(this).apply {
            hint = "热点密码"; setText(storedPassword()); setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        ssid.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        password.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        fun hideKeyboard() {
            val token = password.windowToken ?: ssid.windowToken
            (this.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(token, 0)
            ssid.clearFocus(); password.clearFocus()
        }
        ssid.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) { password.requestFocus(); true } else false
        }
        password.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { hideKeyboard(); true } else false
        }
        fields.addView(ssid); fields.addView(password)
        fields.addView(CheckBox(this).apply {
            text = "显示密码"
            setOnCheckedChangeListener { _, checked ->
                password.transformationMethod = if (checked) null else android.text.method.PasswordTransformationMethod.getInstance()
                password.setSelection(password.text.length)
            }
        })
        val error = label("", 14, WARNING)
        error.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        fields.addView(error)
        val dialog = AlertDialog.Builder(this).setTitle("车机热点详情")
            .setView(ScrollView(this).apply { addView(fields) })
            .setPositiveButton("保存详情", null).setNegativeButton("取消") { _, _ -> hideKeyboard() }
            .setNeutralButton("隐藏键盘", null).create()
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener { hideKeyboard() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = ssid.text.toString().trim()
                val secret = password.text.toString()
                val problem = hotspotError(name, secret)
                if (problem != null) error.text = problem
                else { hideKeyboard(); dialog.dismiss(); done(name, secret) }
            }
        }
        dialog.show()
    }

    // "左 20%", "居中 · 默认", "下 10%": a signed step reads as a direction and a distance.
    private fun markerStepLabel(step: Int, negative: String, positive: String): String = when {
        step == 0 -> "居中 · 默认"
        step < 0 -> "$negative ${-step * CarPlayClusterDisplay.MARKER_STEP_PERCENT} %"
        else -> "$positive ${step * CarPlayClusterDisplay.MARKER_STEP_PERCENT} %"
    }

    private fun showClusterAccessSetup() {
        val command = "adb shell appops set $packageName GET_USAGE_STATS allow"
        val body = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        body.addView(label("在此车上的一次性设置", 20, TEXT, true))
        body.addView(label("使用情况访问权限让 DiPlay 可以跟随仪表主题和地图卡片。仅在本地处理 BYD 仪表活动事件。此车机没有可用的权限设置页面。", 15, MUTED))
        body.addView(label("1. 使用现有的 USB 或无线调试连接，将安装了 ADB 的电脑连接到车机。如被询问，请在车机上批准调试。\n\n2. 在电脑的终端中运行此命令：", 16, TEXT))
        body.addView(label(command, 16, TEXT).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(16), 0, dp(16))
        })
        body.addView(button("复制命令", false) {
            val clipboard = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                getSystemService(android.content.ClipboardManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            }
            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText("DiPlay 使用情况访问权限", command))
            toast("已复制到车机剪贴板。请在电脑上运行该命令。")
        }, matchButton(0, 56))
        body.addView(label("如果 ADB 列出了多个设备，请使用 adb -s <车机设备 ID> shell appops set $packageName GET_USAGE_STATS allow。可通过 adb devices 查找该 ID。", 14, MUTED))
        body.addView(label("3. 点击下方的“检查并启用”。这将启用仪表地图和自动跟随主题。进行中的 CarPlay 会话会重新连接一次。之后即可断开电脑。", 16, TEXT))
        val status = label(if (DiLink51ClusterMonitor.hasAccess(this)) "权限已启用 · 可以正常使用" else "权限尚未启用", 16, TEXT)
        body.addView(status)
        val dialog = AlertDialog.Builder(this).setTitle("自动仪表地图设置")
            .setView(ScrollView(this).apply { addView(body) })
            .setNegativeButton("关闭", null)
            .setPositiveButton("检查并启用", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (DiLink51ClusterMonitor.hasAccess(this)) {
                    AirPlayPersistence.saveClusterMapEnabled(this, true)
                    DiLink51ClusterLayout.saveAutomatic(this, true)
                    dialog.dismiss()
                    render()
                    toast("已启用自动地图。请打开仪表地图卡片或选择“地图”主题。")
                    reconnectForClusterMap()
                } else {
                    status.text = "仍在等待使用情况访问权限。请检查该命令是否已在此车机上成功运行，然后重试。"
                }
            }
        }
        dialog.show()
    }

    // The cluster screen is described at connection time, so a running session reconnects over
    // its current link. The position choices need no call: "Apply and reconnect" already does it.
    private fun reconnectForClusterMap() {
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun applyWirelessLink(mode: WirelessHotspotMode) {
        AirPlayPersistence.saveWirelessHotspotMode(this, mode)
        render()
        toast("已保存，将在下次连接时生效")
    }

    private fun textInput(title: String, current: String, secret: Boolean, save: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(current)
            setSingleLine()
            inputType = if (secret) {
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                android.text.InputType.TYPE_CLASS_TEXT
            }
        }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton("保存") { _, _ -> save(input.text.toString().let { if (secret) it else it.trim() }) }
            .setNegativeButton("取消", null).show()
    }

    private fun carPlaySizeControl(parent: LinearLayout) {
        val sizes = com.shilapi.xcertplay.airplay.CarPlaySize.entries
        val current = com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(this))
        choice(parent, "CarPlay 尺寸", sizes.map { it.label }, sizes.indexOf(current)) {
            AirPlayPersistence.saveWidthPhysicalMm(this, sizes[it].widthMillimeters)
        }
        parent.addView(label("更改 CarPlay 图标和文字的大小。应用尺寸更改会重新连接 CarPlay。", 14, MUTED).apply {
            setPadding(0, 0, 0, dp(18))
        })
    }

    private fun connect(wireless: Boolean) {
        if (wireless && pendingCarHotspotSetup) { toast("请先在连接设置中保存热点详情"); page = "connection"; render(); return }
        if (setupError != null) { toast(setupError!!); return }
        if (wireless && AirPlayPersistence.loadWirelessHotspotMode(this).isManualHotspot &&
            hotspotError(storedSsid(), storedPassword()) != null) {
            pendingCarHotspotSetup = true
            pendingHotspotMode = AirPlayPersistence.loadWirelessHotspotMode(this)
            page = "connection"
            render()
            toast("请先从车机热点设置中保存名称和密码")
            return
        }
        if (wireless && carHotspotOff()) { carHotspotOffDialog(); return }
        if (wireless && DiPlayPreferences.phoneAddress(this) == null) {
            pendingWireless = true; choosePhone(); return
        }
        val preferences = getSharedPreferences("diplay", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !preferences.getBoolean("notification_asked", false)) {
            preferences.edit().putBoolean("notification_asked", true).apply()
            notificationTransport = wireless
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val open = {
            AirPlayPersistence.saveWirelessEnabled(this, wireless)
            openProjection()
        }
        if (CarPlayBackgroundSession.hasSession()) CarPlayBackgroundSession.stop { runOnUiThread { open() } }
        else open()
    }
    private fun openProjection() {
        startActivity(Intent(this, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }
    private fun choosePhone() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT); return
        }
        val adapter: android.bluetooth.BluetoothAdapter? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getSystemService(BluetoothManager::class.java)?.adapter
        } else {
            @Suppress("DEPRECATION")
            getSystemService(android.content.Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothAdapter?
        }
        if (adapter == null || !adapter.isEnabled) {
            AlertDialog.Builder(this).setTitle("开启蓝牙")
                .setMessage("请先开启车机蓝牙并配对您的 iPhone。")
                .setPositiveButton("打开蓝牙") { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton("稍后", null).show(); return
        }
        val devices = runCatching { adapter.bondedDevices.sortedBy { it.name ?: "" } }.getOrDefault(emptyList())
        if (devices.isEmpty()) {
            AlertDialog.Builder(this).setTitle("配对您的 iPhone")
                .setMessage("在您的 iPhone 上，打开“设置 → 蓝牙”并与车机配对。然后返回 DiPlay 并选择“连接手机”。")
                .setPositiveButton("打开蓝牙") { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton("知道了", null).show(); return
        }
        AlertDialog.Builder(this).setTitle("选择您的 iPhone")
            .setItems(devices.map { device ->
                val name = device.name ?: "已配对设备"
                if (devices.count { it.name == device.name } > 1) "$name · ${device.address.takeLast(5)}" else name
            }.toTypedArray()) { _, index ->
                val device = devices[index]
                DiPlayPreferences.savePhone(this, device.address, device.name ?: "iPhone")
                val start = pendingWireless; pendingWireless = false
                render()
                if (start) connect(true)
            }.setNeutralButton("配对另一台") { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .setNegativeButton("取消") { _, _ -> pendingWireless = false }.show()
    }

    private fun wirelessHelp() {
        AlertDialog.Builder(this).setTitle("无线连接帮助")
            .setMessage("将你的 iPhone 与车机蓝牙配对，保持 Wi-Fi 开启，并在 iPhone 上允许 CarPlay。请关闭其他任何手机投屏应用。\n\n如果之前的投屏应用留下了正在运行的连接，请在下方重置 CarPlay Wi-Fi 并重新连接。车机的正常上网 Wi-Fi 会保持开启。")
            .setPositiveButton("知道了", null)
            .setNeutralButton("重置 CarPlay Wi-Fi") { _, _ ->
                confirmWirelessReset()
            }.show()
    }

    private fun handleWirelessRecovery() {
        if (page != "wireless-recovery") return
        page = "home"; render()
        confirmWirelessReset()
    }

    private fun confirmWirelessReset() {
        AlertDialog.Builder(this).setTitle("重置 CarPlay Wi-Fi？")
            .setMessage("这将结束现有的 Wi-Fi Direct 连接，包括重新安装后遗留的连接。请先关闭其他投屏应用。车机的上网 Wi-Fi 会保持开启。")
            .setPositiveButton("重置并连接") { _, _ ->
                CarPlayBackgroundSession.stop { runOnUiThread { resetWirelessGroup() } }
            }.setNegativeButton("取消", null).show()
    }

    private fun resetWirelessGroup() {
        val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getSystemService(android.net.wifi.p2p.WifiP2pManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            getSystemService(android.content.Context.WIFI_P2P_SERVICE) as android.net.wifi.p2p.WifiP2pManager?
        }
        if (manager == null) { toast("此车机不支持 Wi-Fi Direct。"); return }
        val channel = manager.initialize(this, mainLooper, null)
        try {
            manager.requestGroupInfo(channel) { group ->
                if (group == null) { channel.close(); connect(true); return@requestGroupInfo }
                manager.removeGroup(channel, object : android.net.wifi.p2p.WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        val deadline = android.os.SystemClock.elapsedRealtime() + 4000
                        fun waitUntilRemoved() {
                            manager.requestGroupInfo(channel) { remaining ->
                                when {
                                    remaining == null -> { channel.close(); if (!isFinishing && !isDestroyed) connect(true) }
                                    android.os.SystemClock.elapsedRealtime() >= deadline -> {
                                        channel.close(); toast("Wi-Fi Direct 仍处于繁忙状态。请关闭其他投屏应用后重试。")
                                    }
                                    else -> handler.postDelayed({ waitUntilRemoved() }, 200)
                                }
                            }
                        }
                        waitUntilRemoved()
                    }
                    override fun onFailure(reason: Int) { channel.close(); toast("无法重置 Wi-Fi Direct。请关闭其他投屏应用后重试。") }
                })
            }
        } catch (_: SecurityException) {
            channel.close(); permissionHelp("无线权限", "在重置 CarPlay Wi-Fi 前，请允许“附近设备”权限；在较旧的 Android 版本上还需要定位权限。")
        }
    }

    private fun refreshStatus() {
        val running = CarPlayBackgroundSession.hasSession()
        status?.text = when {
            setupError != null -> "需要处理设置"
            CarPlayBackgroundSession.active -> "CarPlay 已连接"
            running -> "正在连接你的 iPhone…"
            DiPlayPreferences.phoneAddress(this) != null -> "已就绪：${DiPlayPreferences.phoneName(this)}"
            else -> "随时可以开始"
        }
        if (lastRunning != running) {
            connectButton?.text = if (running) "打开 CarPlay" else "连接手机"
            disconnectButton?.visibility = if (running) View.VISIBLE else View.GONE
            disconnectButton?.isEnabled = true
            lastRunning = running
        }
        connectButton?.isEnabled = setupError == null
    }
    private fun reportFileName() = "DiPlay-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}.txt"

    private fun chooseReportDestination() {
        // Some head units omit or disable DocumentsUI. Launch itself can throw, before
        // the result callback and the background writer's exception handler ever run.
        runCatching { export.launch(reportFileName()) }.onFailure {
            toast(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                "此车机无法打开保存位置。请再次尝试保存到“下载”。"
                else "此车机没有可用的文件选择器来保存报告。")
        }
    }

    private fun exportDiagnostics(uri: Uri? = null) {
        if (exportInProgress) return
        exportInProgress = true
        exportButton?.apply { isEnabled = false; text = "正在保存报告…" }
        val appContext = applicationContext
        val fileName = reportFileName()
        Thread({
            val result = runCatching {
                val report = buildString {
                    appendLine("DiPlay ${version()} · 私有测试版诊断报告")
                    appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
                    appendLine("车机：${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("连接方式：${if (AirPlayPersistence.loadWirelessEnabled(appContext)) "无线" else "USB"}")
                    appendLine("认证：本地实验性测试版身份；无远程回退")
                    appendLine("CarPlay 设置：${if (setupError == null) "就绪" else "认证不可用"}")
                    appendLine("已保存的视频偏好（可能与会话中的实际设置不同）：${if (AirPlayPersistence.loadHevcEnabled(appContext)) "HEVC" else "H.264"}; ${AirPlayPersistence.loadFps(appContext)} 帧/秒")
                    appendLine("CarPlay 尺寸：${com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(appContext)).label}")
                    appendLine("已保存的分辨率偏好（可能与会话中的实际设置不同）：${AirPlayPersistence.loadDisplayScaleTenths(appContext) * 10}%")
                    appendLine("会话：${if (CarPlayBackgroundSession.active) "进行中" else if (CarPlayBackgroundSession.hasSession()) "连接中" else "已停止"}")
                    appendLine("车机主板：${Build.BOARD}；硬件：${Build.HARDWARE}；构建：${Build.DISPLAY}")
                    appendLine()
                    appendLine("--- 最近一次显示协商（时间戳可将其与当前设置区分开）---")
                    appendLine(DisplayDiagnosticSnapshot.report(appContext))
                    appendLine()
                    for (name in SessionLogFile.REPORT_NAMES) {
                        val file = File(appContext.filesDir, "logs/$name")
                        if (file.isFile) {
                            appendLine("--- $name ---")
                            file.useLines { lines -> lines.forEach { line -> DiagnosticRedactor.redact(line)?.let { appendLine(it) } } }
                        }
                    }
                }
                if (uri != null) { DiagnosticExportStore.write(appContext.contentResolver, uri, report); uri }
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    DiagnosticExportStore.saveToDownloads(appContext.contentResolver, fileName, report)
                } else error("需要保存位置")
            }
            runOnUiThread {
                exportInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                exportButton?.apply { isEnabled = true; text = "保存诊断报告" }
                if (result.isSuccess) {
                    val savedUri = result.getOrThrow()
                    AlertDialog.Builder(this).setTitle("诊断报告已保存")
                        .setMessage(if (uri == null) "Downloads/DiPlay/$fileName" else "报告已保存到所选位置。")
                        .setPositiveButton("完成", null)
                        .setNeutralButton("分享") { _, _ ->
                            runCatching {
                                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"; putExtra(Intent.EXTRA_STREAM, savedUri)
                                    clipData = android.content.ClipData.newRawUri("诊断报告", savedUri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, "分享诊断报告"))
                            }.onFailure { toast("报告已保存。请从文件管理器中打开以进行分享。") }
                        }.show()
                } else {
                    AlertDialog.Builder(this).setTitle("无法保存报告")
                        .setMessage("请检查存储空间是否可用，或选择其他保存位置。")
                        .setPositiveButton("选择位置") { _, _ -> chooseReportDestination() }
                        .setNegativeButton("关闭", null).show()
                }
            }
        }, "diplay-export").start()
    }
    private fun permissionHelp(title: String, body: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("应用设置") { _, _ ->
            openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }.setNegativeButton("稍后", null).show()
    }
    private fun openSystem(intent: Intent) { runCatching { startActivity(intent) }.onFailure { toast("请从车机的“设置”应用中打开此设置。") } }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    private fun version() = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1.0-beta.1"
    private fun section(parent: LinearLayout, title: String, icon: Int? = null, build: (LinearLayout) -> Unit) {
        val card = card()
        val heading = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(16)) }
        if (icon != null) heading.addView(ImageView(this).apply {
            setImageResource(icon); imageTintList = ColorStateList.valueOf(ACCENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) })
        heading.addView(label(title, 22, TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(heading)
        build(card)
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
    }
    private fun toggle(parent: LinearLayout, title: String, description: String, value: Boolean, save: (Boolean) -> Unit) {
        val line = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, dp(12)) }
        val text = column(); text.addView(label(title, 18, TEXT, true)); text.addView(label(description, 14, MUTED).apply { setPadding(0, dp(6), dp(16), 0) })
        line.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(Switch(this).apply { contentDescription = title; isChecked = value; minHeight = dp(56); buttonTintList = ColorStateList.valueOf(ACCENT); setOnCheckedChangeListener { _, checked -> save(checked) } })
        parent.addView(line)
    }
    private fun choice(parent: LinearLayout, title: String, options: List<String>, current: Int, save: (Int) -> Unit) {
        var selection = current
        val button = button("$title · ${options[selection]}", false) {}
        button.setOnClickListener {
            var pendingSelection = selection
            AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), selection) { _, index -> pendingSelection = index }
                .setPositiveButton(if (CarPlayBackgroundSession.hasSession()) "应用并重新连接" else "保存") { _, _ ->
                    if (pendingSelection != selection) {
                        selection = pendingSelection
                        save(selection)
                        button.text = "$title · ${options[selection]}"
                        if (CarPlayBackgroundSession.hasSession()) {
                            connect(AirPlayPersistence.loadWirelessEnabled(this))
                        }
                    }
                }.setNegativeButton("取消", null).show()
        }
        parent.addView(button, matchButton(0, 60)); parent.addView(space(12))
    }
    private fun card() = column().apply { background = rounded(SURFACE, BORDER); setPadding(dp(24), dp(24), dp(24), dp(24)) }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.create("sans-serif", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun button(title: String, primary: Boolean, click: () -> Unit) = Button(this).apply {
        text = title; isAllCaps = false; textSize = 18f; setTextColor(if (primary) BG else TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x336F9FD9), rounded(if (primary) ACCENT else SURFACE, if (primary) ACCENT else BORDER), null)
        setPadding(dp(16), 0, dp(16), 0); minHeight = dp(56); stateListAnimator = null
        setOnClickListener { click() }
    }
    private fun rounded(color: Int, stroke: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(20).toFloat(); setStroke(dp(1), stroke) }
    private fun matchButton(top: Int = 0, height: Int = 68) = LinearLayout.LayoutParams(-1, dp(height)).apply { topMargin = dp(top) }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object {
        private val BG = Color.rgb(12, 17, 27)
        private val SURFACE = Color.rgb(21, 30, 44)
        private val BORDER = Color.rgb(42, 56, 75)
        private val ACCENT = Color.rgb(166, 200, 255)
        private val TEXT = Color.rgb(241, 245, 252)
        private val MUTED = Color.rgb(168, 182, 202)
        private val WARNING = Color.rgb(255, 196, 128)
    }
}
