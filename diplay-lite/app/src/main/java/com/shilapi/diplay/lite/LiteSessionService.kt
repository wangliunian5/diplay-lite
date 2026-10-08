package com.shilapi.diplay.lite

import android.app.Notification
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.media.AudioManager
import android.os.IBinder
import android.util.Base64
import android.view.WindowManager
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.AirPlayDeviceInfo
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.LockdownPairRecord
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 前台会话服务：无线 CarPlay 期间保活，避免 4.1 后台回收。
 *
 * M2：接线 shared 的 CarPlayController——本地离线 MFi 证书 + MANUAL 车机热点 +
 * 蓝牙 RFCOMM 引导的无线流水线；状态经 CarPlayStatus/LiteState 回流到 MainActivity。
 * M3：媒体面接 CarPlayMediaEngine + AndroidMediaSink（MediaCodec 解码 → SurfaceView）。
 *
 * 注意 4.1 兼容：startForeground(id, Notification) 为 API 5；
 * Notification.Builder(Context) 单参构造为 API 11；build() 是 API 24 才有，须用 getNotification()。
 */
@Suppress("DEPRECATION")
class LiteSessionService : Service() {

    private var controller: CarPlayController? = null

    /** M6k16：本次会话通道（car=车机热点 MANUAL_IPV4 / relay=双 client 接随身WiFi RELAY）。 */
    private var requestedChannel = "car"

    /** M3 媒体面：每轮 startController 新建一套（AndroidMediaSink.close() 后不可复用）。 */
    private var mediaSink: AndroidMediaSink? = null
    private var mediaEngine: CarPlayMediaEngine? = null

    private val sessionListener = object : AirPlaySessionListener {
        override fun onSessionActive(session: AirPlaySession) {
            LiteState.phase = LiteState.Phase.ACTIVE
            LiteState.statusText = "CarPlay 会话已激活"
            LiteLog.log("airplay", "会话已激活 peer=${session.host}")
        }

        override fun onSessionEnded(session: AirPlaySession) {
            LiteState.phase = LiteState.Phase.IDLE
            LiteState.statusText = "会话已结束"
            LiteLog.log("airplay", "会话已结束")
        }

        override fun onTransportError(message: String) {
            LiteLog.log("airplay", "传输错误：$message")
        }

        override fun onDeviceInfo(session: AirPlaySession, info: AirPlayDeviceInfo) {
            LiteLog.log("airplay", "设备信息 name=${info.name} model=${info.model}")
        }

        override fun onHostUiRequested(session: AirPlaySession) {
            LiteLog.log("airplay", "手机请求回到车机界面")
        }

        override fun onDebugLog(message: String) {
            LiteLog.log("airplay", message)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * M4k 触摸入口：MainActivity 的 SurfaceView 把 MotionEvent 归一化成 [AirPlayContact]
     * （x/y 为 0..1），由此转发到 shared 的 CarPlayController.sendTouch。
     * 未连接（controller 为空）时返回 false，调用方据此不消费事件。
     *
     * M6k：新增 [sticky]——DOWN/UP 等触点状态变化事件必须逐条送达（sticky=true），
     * 纯 MOVE（sticky=false）可被后续 MOVE 覆盖，避免快速滑动时排队导致触摸滞后。
     */
    fun sendTouch(contacts: List<AirPlayContact>, sticky: Boolean): Boolean =
        controller?.sendTouch(contacts, sticky) == true

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        current = this
        startForeground(NOTIFICATION_ID, buildNotification())
        LiteState.running = true
        LiteState.phase = LiteState.Phase.CONNECTING
        LiteState.statusText = "正在初始化…"
        LiteLog.log("service", "前台会话服务已启动")
        // 构建标识：实机每次启动都打印，用于区分本轮跑的是哪个包（T17 归档约定）。
        LiteLog.log("service", "构建标识 version=${buildVersion()} apk=${apkStamp()}")
        // M6k16：通道由主页 Intent 带入（car/relay）；START_STICKY 自启 intent 为 null 时沿用上一轮。
        intent?.getStringExtra(EXTRA_CHANNEL)?.let { requestedChannel = it }
        startController()
        return START_STICKY
    }

    override fun onDestroy() {
        // close() 在后台线程按序拆除：无线栈（AirPlay → 隧道 → 蓝牙）→ MFi → 定位。
        // 拆栈可能等待 socket 关闭，onDestroy 在主线程，故不 awaitClosed()；
        // 拆栈线程随进程存续（MainActivity 在前台时进程不会先死）。
        // M6k25：把关闭中的 controller 挂到静态 closingController，下一轮 startController
        // （stopService 后新实例启动 / 进程内重建重启后立即重连）先等它关闭 —— 否则残留的
        // 7000 监听/解码器会让新会话起不来、UI 永远停在"连接中"（10-02 EADDRINUSE 跨实例形态）。
        LiteSteeringKeys.detach()
        controller?.let { closingController = it; it.close() }
        controller = null
        mediaSink?.close()
        mediaSink = null
        mediaEngine = null
        LiteVideoBridge.bindSink(null)
        LiteState.running = false
        LiteState.phase = LiteState.Phase.IDLE
        LiteState.statusText = "未连接"
        stopForeground(true)
        LiteLog.log("service", "会话服务已停止")
        current = null
        super.onDestroy()
    }

    // ---------- M2：无线流水线接线 ----------

    private fun startController() {
        // M6k25：先等上一轮拆栈完成 —— 可能是 stopService 后新服务实例的跨实例关闭
        // （onDestroy 只发起不等待），避免残留 7000 监听让新会话 EADDRINUSE。
        closingController?.let { closing ->
            closingController = null
            closing.awaitClosed(2000)
        }
        // 重复 onStartCommand（START_STICKY 自启恢复 / 用户再点连接）时先按序拆除旧
        // controller，避免残留的 7000 监听让新会话 EADDRINUSE（10-02 20:01 实测）。
        controller?.let { old ->
            controller = null
            LiteSteeringKeys.detach()
            old.close()
            old.awaitClosed(2000)
        }
        mediaSink?.close()
        mediaSink = null
        mediaEngine = null
        LiteVideoBridge.bindSink(null)
        val config = LiteConfig.load(this)
        val usb = requestedChannel == "usb"
        val relay = requestedChannel == "relay"
        if (usb) {
            // U4 USB 有线：不走 Wi-Fi/蓝牙段，热点凭据校验全部跳过。
        } else if (relay && config.relaySsid.isBlank()) {
            fail("中继热点 SSID 未配置")
            return
        } else if (!relay && config.ssid.isBlank()) {
            fail("车机热点 SSID 未配置")
            return
        }
        val ssid = if (usb) "" else if (relay) config.relaySsid else config.ssid
        val passphrase = if (usb) "" else if (relay) config.relayPassword else config.password
        val security = if (usb) {
            ManualHotspotSecurity.OPEN // 占位：WIRED 传输不使用热点字段
        } else if (relay) {
            // 中继凭据即随身WiFi 的凭据：无密码视为开放网络，有密码按 WPA2 下发 0x5703。
            if (passphrase.isEmpty()) ManualHotspotSecurity.OPEN else ManualHotspotSecurity.WPA2
        } else {
            mapSecurity(config.security)
        }
        if (!usb && security != ManualHotspotSecurity.OPEN && passphrase.length !in 8..63) {
            fail("热点密码需 8-63 位（当前 ${passphrase.length} 位）")
            return
        }
        ensureMfiDirectory()
        val identity = loadOrCreateIdentity()
        val deviceId = deviceIdOf(identity)
        val btMac = localBluetoothAddress() ?: deviceId
        // M6b：/info 的 maxFPS 与分辨率都由 LiteConfig 控制（弱机省算力）。
        // fps 30/60 二选一；分辨率 = 车机屏 × 百分比（长宽等比、各自取偶）。
        val full = screenMetrics()
        val fps = if (config.limit30Fps) AirPlayDisplaySettings.MIN_FPS else AirPlayDisplaySettings.DEFAULT_FPS
        val size = scaleDisplay(full, config.videoScalePercent)
        LiteLog.log(
            "service",
            "构造 CarPlayController deviceId=$deviceId btMac=$btMac " +
                "screen=${size.first}x${size.second} full=${full.first}x${full.second} " +
                "fps=$fps scale=${config.videoScalePercent}% speakerForCalls=${config.speakerForCalls} " +
                "softDecode=${config.softwareVideoDecoder} " +
                "audioStream(media/nav)=${config.mediaAudioStream}/${config.navAudioStream} " +
                "avPath=${config.avPathMode} " +
                "channel=$requestedChannel ssid=$ssid security=$security",
        )
        // M3：MediaCodec 解码到 SurfaceView；SurfaceView 的 Surface 由 LiteVideoBridge 补齐。
        val sink = AndroidMediaSink(
            videoWidth = size.first,
            videoHeight = size.second,
            // M6e：强制软件解码视频（默认关）。重连即生效（解码器每次建流都会重建）。
            preferSoftwareHevcDecoder = config.softwareVideoDecoder,
            onAudioDiagnostic = { LiteLog.log("media", it) },
            onPhoneChannelActive = ::onPhoneChannelActive,
            // M6d：导航播报时压低音乐。取值函数——改设置后下一次播报即生效，无需重连。
            navigationDuckPercent = { LiteConfig.load(this).navDuckPercent },
            // M6k19：音乐缓存（设置页 10-4000ms 可调）。
            // M6k21：媒体/导航各自的音频流编号覆盖（0=自动路由，1~20=传统音频流编号）。
            mediaBufferMillis = config.mediaBufferMillis,
            mediaStreamOverride = config.mediaAudioStream,
            navStreamOverride = config.navAudioStream,
            // 1.0.16（P0-1）：通话走扬声器时 telephony 下行按 MEDIA（STREAM_MUSIC）路由。
            callsRoutedToMedia = { LiteConfig.load(this).speakerForCalls },
            // 1.0.11（FE-6 拉伸）：解码器实际尺寸 → UI 层等比适配 SurfaceView。
            onVideoOutputSizeChanged = { w, h ->
                LiteLog.log("media", "video output size coded=${w}x${h}")
                LiteVideoBridge.notifyVideoSize(w, h)
            },
        )
        // 麦克风上行：RECORD_AUDIO 在 targetSdk19 下安装即授予，原生分支同样按“可用”声明。
        val engine = CarPlayMediaEngine(sink = sink, microphoneEnabled = true)
        mediaSink = sink
        mediaEngine = engine
        // 1.0.11（FE-6 拉伸）：主屏幕流结束时让 UI 把 SurfaceView 恢复铺满（letterbox 复位）。
        sink.setScreenStreamActiveChangedListener { type, active ->
            if (type == 110 && !active) LiteVideoBridge.notifyVideoSize(0, 0)
        }
        LiteVideoBridge.bindSink(sink)
        val newController = try {
            CarPlayController(
                context = this,
                config = CarPlayRuntimeConfig(
                    hostMac = deviceId.split(":").map { it.toInt(16).toByte() }.toByteArray(),
                    identification = Iap2IdentificationConfig(
                        name = "DiPlay",
                        modelIdentifier = "DiPlay",
                        manufacturer = "DiPlay",
                        serialNumber = "DIPLAY-" + deviceId.replace(":", ""),
                        firmwareVersion = "0.1.0",
                        hardwareVersion = "1.0",
                        carPlayUsbInterfaceNumber = 3,
                    ),
                    label = "DiPlay",
                    hostName = "diplay-" + deviceId.replace(":", "").lowercase(),
                    // U4 USB 有线：channel=usb 走 WIRED（USBMUX/lockdown + NCM），热点/蓝牙字段不参与校验。
                    transport = if (usb) CarPlayTransport.WIRED else CarPlayTransport.WIRELESS,
                    // MANUAL_IPV4：MANUAL 在 API19+ 上会优先选 IPv6 链路本地地址（fe80::），
                    // iPhone 无法用它接入 Wi-Fi 侧（nova3e 实测：AirPlay 监听绑 fe80::…:7000、
                    // 会话永远建不起来）。C8815 之所以"碰巧"正常，是因为 API16 的
                    // NetworkInterface.getIndex 取不到（回退 0），IPv6 分支被跳过而落到 IPv4。
                    // RELAY：双 client 接随身WiFi，manualHotspotSsid/Passphrase 字段复用为中继凭据。
                    wirelessHotspotMode =
                        if (relay && !usb) WirelessHotspotMode.RELAY else WirelessHotspotMode.MANUAL_IPV4,
                    manualHotspotSsid = if (usb) null else ssid,
                    manualHotspotPassphrase = when {
                        usb -> null
                        relay -> passphrase.takeIf { it.isNotEmpty() }
                        security == ManualHotspotSecurity.OPEN -> null
                        else -> config.password
                    },
                    manualHotspotSecurity = security,
                    // 1.0.22（S7 实测）：UI「IPV4首选信道」从未接线——此处构造
                    // CarPlayRuntimeConfig 时一直漏传 manualHotspotChannel（恒默认 0），
                    // ManualHotspotManager 观测失败后永远兜底下发信道 6，iPhone 按凭证
                    // 扫不到实际在别的信道的热点（S7×7 代 First TCP timeout 根因，
                    // 设置页「即时生效」提示名不副实）。车机热点模式接上手填信道
                    // （0=自动）；relay 复用凭据字段不吃信道、usb 无热点，均保持 0。
                    manualHotspotChannel = if (usb || relay) 0 else config.carIpv4Channel,
                    wirelessBluetoothDeviceAddress = config.bluetoothAddress.takeIf { it.isNotBlank() },
                ),
                airPlayConfig = AirPlayConfig(
                    // 1.0.17：显示名称可自定义（自定义返回图标页），默认 DiPlay；流向 /info name + Bonjour 服务名。
                    deviceName = config.carIconName.ifBlank { "DiPlay" },
                    deviceId = deviceId,
                    btMac = btMac,
                    sourceVersion = "950.7.1",
                    main = AirPlayDisplayConfig(
                        widthPixels = size.first,
                        heightPixels = size.second,
                        fps = fps,
                    ),
                    manufacturer = "DiPlay",
                    model = "DiPlay",
                    // M6k24g：CarPlay 主页图标下的应用名（/info 的 oemIconLabel）。默认值是
                    // "xcertplay"，此前只覆写了 manufacturer/model 导致"图标换了名字没换"。
                    // 1.0.18：oemLabel 与 deviceName 同源吃 carIconName——1.0.17 只改了
                    // deviceName，但 CarPlay 桌面图标标签走的是 oemLabel，实测仍显示 DiPlay。
                    oemLabel = config.carIconName.ifBlank { "DiPlay" },
                    // /info 必须声明 audioInputFormats，否则 iPhone 不建立 CarPlay 音频流
                    // （原先走默认 microphone=false，会话里连基础 type=100 都没有）。
                    microphone = true,
                    // ⚠️ M4i（2026-10-03）：本字段当前**不再影响 audioFormats**——iPhone 要求带无线
                    // 输入的流"上下行 OPUS 位一致"（K60 都带→建流；T26 都不带→零流；M4h out 带/in 不带
                    // →仍零流），故 M4i 让上下行都声明 OPUS。此字段保留备用（将来若要"仅对播放分级"）。
                    // 详见 AirPlayInfoPlist.audioFormats / 超低版本实机测试记录.md T23/T26/T27。
                    opusEncoderAvailable = false,
                    // M6k21 方案二（音视频链路选择）：bt_audio=蓝牙音频+CarPlay —— /info 的
                    // features 换 NO_AUDIO 位且不下发 audioOutputFormats，iPhone 认为车机无
                    // CarPlay 音频能力、不建音流，音频留在 iPhone 走已配对的蓝牙 A2DP 出声。
                    // full=全 CarPlay（默认，音视频都走 2.4G）。重开会话生效。
                    disableAudioOutput = config.avPathMode == "bt_audio",
                    // M6k22：自定义返回桌面图标 → /info 的 oemIcons 下发（未设置/不合规走内置默认）。
                    icons = listOfNotNull(CarIconStore.loadIcon(this@LiteSessionService)),
                ),
                identity = identity,
                pairings = createPairingStore(),
                listener = sessionListener,
                media = engine,
                reportStatus = ::onStatus,
                // U4 USB 有线：Lockdown 配对记录持久化（重启后免重新信任；无线模式不消费）。
                loadPairRecord = { loadLockdownPairRecord() },
                savePairRecord = { saveLockdownPairRecord(it) },
                clearPairRecord = { clearLockdownPairRecord() },
            )
        } catch (error: IllegalArgumentException) {
            fail("配置不合法：${error.message}")
            return
        } catch (error: Exception) {
            fail("初始化失败：${error.message}")
            return
        }
        controller = newController
        newController.start()
        // M6k22 方控：会话建立即挂接（MediaSession + 监听器随控制器生命周期启停）。
        LiteSteeringKeys.attach(this, newController)
    }

    /** CarPlayStatus -> LiteState 映射；controller 已投递到主线程回调。 */
    private fun onStatus(status: CarPlayStatus) {
        LiteLog.log("carplay", "状态：$status")
        when (status) {
            is CarPlayStatus.DiscoveringMfi -> connecting("正在加载 MFi 证书")
            is CarPlayStatus.MfiReady -> connecting("MFi 证书就绪")
            is CarPlayStatus.StartingHotspot -> connecting("正在加入车机热点…")
            is CarPlayStatus.HotspotReady -> connecting("网络就绪 ssid=${status.ssid} band=${bandLabelCn(status.band)}")
            is CarPlayStatus.WaitingForPairedIphone -> connecting("等待 iPhone 连接热点（请先在蓝牙设置中配对）")
            is CarPlayStatus.AttachingNetwork -> connecting("正在下发网络配置（0x5703）")
            is CarPlayStatus.ConnectingBluetooth -> connecting("蓝牙 iAP2 握手中")
            is CarPlayStatus.RunningWireless -> connecting("无线链路建立中")
            is CarPlayStatus.WirelessActive -> {
                LiteState.phase = LiteState.Phase.ACTIVE
                LiteState.statusText = "无线 CarPlay 已激活"
            }
            // —— U4 USB 有线状态序列 ——
            is CarPlayStatus.DiscoveringIphone -> connecting("正在查找 USB 连接的 iPhone…")
            is CarPlayStatus.WaitingForIphone -> connecting("请用数据线连接 iPhone 到车机 USB 口")
            is CarPlayStatus.RequestingIphonePermission -> connecting("请在弹窗中允许 USB 访问 iPhone")
            is CarPlayStatus.WaitingForReenumeration -> connecting("iPhone 重新枚举中，请保持连接…")
            is CarPlayStatus.SelectingConfiguration -> connecting("正在选择 CarPlay USB 配置")
            is CarPlayStatus.OpeningDataPaths -> connecting("正在打开 USB 数据通道…")
            is CarPlayStatus.Pairing -> connecting("正在与 iPhone 配对（首次较慢，请勿拔线）…")
            is CarPlayStatus.ConnectingControl -> connecting("正在建立有线控制通道…")
            is CarPlayStatus.RunningControl -> connecting("有线链路建立中")
            is CarPlayStatus.ControlEnded -> {
                LiteState.phase = LiteState.Phase.IDLE
                LiteState.statusText = "控制通道已结束"
            }
            is CarPlayStatus.RecoveryHint -> connecting(status.message)
            is CarPlayStatus.Failed -> fail(localizeFailure(status.message))
        }
    }

    private fun connecting(text: String) {
        LiteState.phase = LiteState.Phase.CONNECTING
        LiteState.statusText = text
    }

    /** M6k18：band 标签中文化（relay=中继网络）。 */
    private fun bandLabelCn(band: String): String = when (band) {
        "relay" -> "中继网络"
        else -> band
    }

    /**
     * M6k18：底层英文报错转中文给 UI（原始英文仍完整进日志，便于回传排查）。
     * 匹配按子串，改底层文案时同步维护《超低版本-报错对照表.md》。
     */
    private fun localizeFailure(message: String): String {
        val m = message.lowercase()
        return when {
            "relay_unreachable" in m -> {
                val gateway = Regex("gateway (\\S+)").find(message)?.groupValues?.get(1) ?: "网关"
                "中继网络不通：网关 $gateway 无响应。通常是路由器开了AP隔离（设备间禁止互访），或iPhone没连同一个随身WiFi；请到路由器设置关闭AP隔离后重试"
            }
            "could not join relay network" in m ->
                "加入中继网络超时：请核对随身WiFi名称/密码，确认车机Wi-Fi已开启"
            "relay ssid is not configured" in m -> "中继SSID未配置，请先在「中继热点 → 配置」填写"
            "adding relay network" in m -> "添加中继网络失败：请核对随身WiFi密码"
            "enabling relay network" in m -> "启用中继网络失败，请重试"
            "wi-fi is off" in m -> "车机Wi-Fi未开启：中继模式需先打开Wi-Fi"
            "car hotspot is off" in m -> "车机热点未开启：请到车机设置打开热点后重试"
            "manual hotspot ssid is not configured" in m -> "车机热点SSID未配置，请先在「车机热点 → 配置」填写"
            "could not establish" in m && "hotspot" in m ->
                "建立网络失败：${message.substringAfterLast(": ", message)}"
            "bluetooth adapter is unavailable" in m -> "蓝牙适配器不可用"
            "bluetooth is not enabled" in m -> "蓝牙未开启，请打开车机蓝牙"
            "no longer paired" in m -> "所选iPhone已取消配对，请重新在配置页选择"
            "no unambiguous bonded iphone" in m || "pair one iphone" in m ->
                "未找到已配对的iPhone：请先与iPhone配对"
            "multiple connected iphones" in m -> "发现多台已连接的iPhone：请只保留一台连接"
            "multiple bonded iphones" in m -> "多台已配对iPhone均未连接：请先连上其中一台"
            "handoff timed out" in m -> "连接建立超时（画面握手未完成），请重试"
            // M6k20（对齐 carlito fork「可行动报错」f82d0e2）：新增 first-TCP 看门狗文案。
            // 1.0.0-demo：中继模式拆分文案——iPhone 已收到凭证却零 TCP，首要嫌疑是
            // 路由器 AP 隔离/访客隔离（家庭路由常见默认项），与热点模式自查项不同。
            "first tcp timeout (relay)" in m ->
                "iPhone已收到Wi-Fi凭证但30秒未连入。中继模式请检查：① 路由器是否开启「无线隔离/访客隔离」（最常见原因，需关闭）；② iPhone与本机连的是同一个WiFi（名字完全一致）；③ 在iPhone的WiFi设置中忽略本网络后重连，再点重试"
            "first tcp timeout" in m ->
                "iPhone迟迟未连入车机网络（30秒内无连接）：请确认iPhone已加入热点/随身WiFi且蓝牙已开启；系统会自动重试（旧记录约40秒后过期）"
            "control channel closed before tunnel" in m -> "iPhone提前断开控制通道，请重试或关开iPhone蓝牙"
            // —— U4 USB 有线错误文案 ——
            "usb host is unavailable" in m -> "此设备不支持 USB 主机模式，无法使用有线连接"
            "iphone usb permission was denied" in m -> "iPhone USB 权限被拒绝，请重新连接并允许"
            "iphone usb permission was not granted" in m -> "未授予 iPhone USB 权限，请点重连后再允许"
            "did not expose a complete carplay usb configuration" in m ->
                "iPhone 未暴露 CarPlay USB 配置：请拔插数据线（换原装线）后重试"
            // 1.0.2：错误拆分——按失败环节给可行动指引。
            "no carplay usb configuration" in m ->
                "iPhone 未切换到 CarPlay USB 模式：请解锁 iPhone、点「信任」，拔插数据线重试"
            "could not switch iphone usb configuration" in m ->
                "无法切换 iPhone USB 配置：请拔插数据线或重启 iPhone 后重试"
            "could not claim usbmux" in m ->
                "USB 控制通道被占用：请拔插数据线后重试，若反复出现请重启车机"
            // 1.0.20（3e EMUI）：图库抢 USB——弹出「USB 连接方式/相册」时选仅充电，回本应用授权。
            "permission has not been granted" in m || "permission was not granted" in m ->
                "iPhone USB 授权未生效：请弹出「USB 连接方式/相册」选择时勿选传输照片，回本应用点允许后重试"
            "could not open the iphone" in m ->
                "无法打开 iPhone USB 连接：若弹出图库或 USB 连接方式选择请关闭，勿选传输照片，拔插数据线重试"
            // 1.0.1：重枚举看门狗超时——原实现此场景永久卡死无提示。
            "did not re-enumerate" in m ->
                "iPhone 未重新枚举：请拔插数据线（或换线/换USB口）、点亮iPhone屏幕点「信任」后重试"
            "does not expose an ncm function" in m ->
                "iPhone 网络共享未就绪：请在 iPhone 上锁屏再解锁，或重插数据线后重试"
            "could not attach the ncm" in m -> "USB 网络通道建立失败，请拔插数据线重试"
            "timed out during wired iap2" in m -> "有线控制握手超时，请拔插数据线重试"
            "usbmux operation failed" in m -> "iPhone USB 通信异常，请拔插数据线重试"
            else -> "连接失败：$message"
        }
    }

    private fun fail(text: String) {
        LiteState.phase = LiteState.Phase.FAILED
        LiteState.statusText = text
        LiteLog.log("service", "失败：$text")
    }

    // ---------- 部署辅助 ----------

    /** 首次启动把 assets 内的离线 MFi 证书释放到 filesDir/offline-mfi（controller 的固定加载路径）。 */
    private fun ensureMfiDirectory(): File {
        val directory = File(filesDir, LocalMfiAuthenticationClient.DIRECTORY)
        if (!directory.exists()) directory.mkdirs()
        for (name in MFI_FILES) {
            val target = File(directory, name)
            if (target.exists()) continue
            assets.open("${LocalMfiAuthenticationClient.DIRECTORY}/$name").use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            LiteLog.log("service", "已释放 MFi 证书 $name（${target.length()} 字节）")
        }
        return directory
    }

    /** Ed25519 长期身份持久化（Base64/SharedPreferences）；首次生成后不再变化。 */
    private fun loadOrCreateIdentity(): AirPlayIdentity {
        val prefs = getSharedPreferences(IDENTITY_PREFS, MODE_PRIVATE)
        val privateKey = prefs.getString(KEY_PRIVATE_KEY, null)
        val publicKey = prefs.getString(KEY_PUBLIC_KEY, null)
        val pairingId = prefs.getString(KEY_PAIRING_ID, null)
        if (privateKey != null && publicKey != null && pairingId != null) {
            return AirPlayIdentity(
                privateKey = Base64.decode(privateKey, Base64.NO_WRAP),
                publicKey = Base64.decode(publicKey, Base64.NO_WRAP),
                pairingId = pairingId,
            )
        }
        val identity = AirPlayIdentity.generate()
        prefs.edit()
            .putString(KEY_PRIVATE_KEY, Base64.encodeToString(identity.privateKey, Base64.NO_WRAP))
            .putString(KEY_PUBLIC_KEY, Base64.encodeToString(identity.publicKey, Base64.NO_WRAP))
            .putString(KEY_PAIRING_ID, identity.pairingId)
            .apply()
        LiteLog.log("service", "已生成 AirPlay 身份 pairingId=${identity.pairingId}")
        return identity
    }

    /**
     * 与原版 DiPlayBootstrap.deviceId 一致：SHA-256(公钥) 前 6 字节，首字节置本地管理位，
     * 输出 XX:XX:XX:XX:XX:XX。稳定派生自持久身份，重装前不变。
     */
    private fun deviceIdOf(identity: AirPlayIdentity): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(identity.publicKey).copyOf(6)
        bytes[0] = ((bytes[0].toInt() and 0xfc) or 0x02).toByte()
        return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
    }

    /** 4.1 上 BluetoothAdapter.getAddress() 可用（返回值非全零时才可信）。 */
    private fun localBluetoothAddress(): String? = try {
        BluetoothAdapter.getDefaultAdapter()?.address
            ?.takeIf { it.isNotBlank() && !it.startsWith("02:00:00:00:00:00") }
    } catch (_: Exception) {
        null
    }

    /** 配对记录持久化：iPhone 侧长期公钥以 Base64 存 SharedPreferences，重启进程后恢复。 */
    private fun createPairingStore(): PairingStore {
        val prefs = getSharedPreferences(PAIRING_PREFS, MODE_PRIVATE)
        val store = PairingStore { identifier, publicKey ->
            prefs.edit()
                .putString(identifier, Base64.encodeToString(publicKey, Base64.NO_WRAP))
                .apply()
        }
        for ((identifier, value) in prefs.all) {
            val encoded = value as? String ?: continue
            try {
                store.save(identifier, Base64.decode(encoded, Base64.NO_WRAP))
            } catch (_: IllegalArgumentException) {
                // 单条损坏不阻断启动
            }
        }
        return store
    }

    // ---------- U4 USB 有线：Lockdown 配对记录持久化（9 字段，PEM 全部 Base64） ----------

    /** 恢复上次与 iPhone 的 Lockdown 配对记录；任一字段缺失/损坏即返回 null（触发重新配对）。 */
    private fun loadLockdownPairRecord(): LockdownPairRecord? {
        val prefs = getSharedPreferences(WIRED_PAIRING_PREFS, MODE_PRIVATE)
        val hostId = prefs.getString("host_id", null) ?: return null
        val systemBuid = prefs.getString("system_buid", null) ?: return null
        val wifiMac = prefs.getString("wifi_mac", null) ?: return null
        fun pem(key: String): ByteArray? = prefs.getString(key, null)?.let {
            try {
                Base64.decode(it, Base64.NO_WRAP)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        val devicePublicKey = pem("device_public") ?: return null
        val deviceCertificate = pem("device_cert") ?: return null
        val hostPrivateKey = pem("host_private") ?: return null
        val hostCertificate = pem("host_cert") ?: return null
        val rootPrivateKey = pem("root_private") ?: return null
        val rootCertificate = pem("root_cert") ?: return null
        return try {
            LockdownPairRecord.restore(
                hostId = hostId,
                systemBuid = systemBuid,
                wifiMacAddress = wifiMac,
                devicePublicKeyPem = devicePublicKey,
                deviceCertificatePem = deviceCertificate,
                hostPrivateKeyPem = hostPrivateKey,
                hostCertificatePem = hostCertificate,
                rootPrivateKeyPem = rootPrivateKey,
                rootCertificatePem = rootCertificate,
            )
        } catch (error: IllegalArgumentException) {
            LiteLog.log("service", "有线配对记录损坏，忽略并重新配对：${error.message}")
            null
        }
    }

    private fun saveLockdownPairRecord(record: LockdownPairRecord) {
        getSharedPreferences(WIRED_PAIRING_PREFS, MODE_PRIVATE).edit()
            .putString("host_id", record.hostId)
            .putString("system_buid", record.systemBuid)
            .putString("wifi_mac", record.wifiMacAddress)
            .putString("device_public", Base64.encodeToString(record.devicePublicKeyPem, Base64.NO_WRAP))
            .putString("device_cert", Base64.encodeToString(record.deviceCertificatePem, Base64.NO_WRAP))
            .putString("host_private", Base64.encodeToString(record.hostPrivateKeyPem, Base64.NO_WRAP))
            .putString("host_cert", Base64.encodeToString(record.hostCertificatePem, Base64.NO_WRAP))
            .putString("root_private", Base64.encodeToString(record.rootPrivateKeyPem, Base64.NO_WRAP))
            .putString("root_cert", Base64.encodeToString(record.rootCertificatePem, Base64.NO_WRAP))
            .apply()
        LiteLog.log("service", "已保存有线 Lockdown 配对记录 hostId=${record.hostId}")
    }

    private fun clearLockdownPairRecord() {
        getSharedPreferences(WIRED_PAIRING_PREFS, MODE_PRIVATE).edit().clear().apply()
        LiteLog.log("service", "已清除有线 Lockdown 配对记录")
    }

    private fun mapSecurity(value: String): ManualHotspotSecurity = when (value) {
        "WPA2-WPA3" -> ManualHotspotSecurity.WPA3_TRANSITION
        "WPA3" -> ManualHotspotSecurity.WPA3
        "OPEN" -> ManualHotspotSecurity.OPEN
        else -> ManualHotspotSecurity.WPA2
    }

    /**
     * M6c：CarPlay 通话流（telephony）出现/消失时的路由切换。
     *
     * 1.0.16（P0-1）起两条路：
     * - 「通话声音走扬声器」勾选：telephony 下行 track 在 AudioRenderer 内直接按
     *   USAGE_MEDIA（STREAM_MUSIC）路由——华为 3e 实测 IN_COMMUNICATION DSP 路径
     *   无真实蜂窝通话时静默（rx=714 playing=true 但用户听无声），MEDIA 路径不受影响。
     *   此分支不再摆弄 speakerphone/mode（track 本身已落扬声器，动了反而可能扰路由）。
     * - 未勾选（走听筒语义）：流结束恢复默认 speakerphone off + MODE_NORMAL。
     */
    private fun onPhoneChannelActive(active: Boolean) {
        val audio = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
        val speakerRouted = LiteConfig.load(this).speakerForCalls
        try {
            if (speakerRouted) {
                // 1.0.16（P0-1）：track 已按 USAGE_MEDIA 落扬声器，无需 MODE_IN_COMMUNICATION。
                LiteLog.log(
                    "media",
                    "通话声音路由 active=$active mediaRoute=true " +
                        "mode=${audio.mode} speakerphone=${audio.isSpeakerphoneOn}",
                )
            } else {
                if (active) {
                    audio.mode = AudioManager.MODE_IN_COMMUNICATION
                    audio.setSpeakerphoneOn(false)
                } else {
                    audio.setSpeakerphoneOn(false)
                    audio.mode = AudioManager.MODE_NORMAL
                }
                LiteLog.log(
                    "media",
                    "通话声音路由 active=$active mediaRoute=false " +
                        "mode=${audio.mode} speakerphone=${audio.isSpeakerphoneOn}",
                )
            }
        } catch (error: Exception) {
            LiteLog.log("media", "通话声音路由设置失败 active=$active speakerRouted=$speakerRouted ${error.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun screenMetrics(): Pair<Int, Int> {
        val manager = getSystemService(WINDOW_SERVICE) as? WindowManager
        val display = manager?.defaultDisplay ?: return DEFAULT_SCREEN
        val width = display.width
        val height = display.height
        if (width <= 0 || height <= 0) return DEFAULT_SCREEN
        // M6k24：向 iPhone 声明的画面方向完全跟随「横竖屏切换」设置（强制横屏/强制竖屏
        // 二选一，自动旋转已取消）：竖屏设置 → 声明竖屏（短边为宽）；横屏设置 → 声明横屏
        // （长边为宽）。用户改方向后重新连接即按新方向要画面。START_STICKY 无 UI 场景同样按设置走。
        // （M4m 的"无 UI 归一横屏"与 M6k23 的 isForeground 分支一并废弃——8815 实测
        // Display/传感器漂移，配置是唯一可靠的方向来源。）
        val portrait = LiteConfig.load(this).orientationMode == "portrait"
        return if (portrait) {
            if (width > height) height to width else width to height
        } else {
            if (width > height) width to height else height to width
        }
    }

    /**
     * M6b：把 /info 声明的显示分辨率按百分比缩小（M6k 起 10~100，100 = 原样返回）。
     * 长宽同比例缩小、各自取偶（H.264 要求偶数）；渲染仍铺满物理屏，故不会出现黑边。
     */
    private fun scaleDisplay(full: Pair<Int, Int>, percent: Int): Pair<Int, Int> {
        val value = percent.coerceIn(LiteConfig.MIN_SCALE_PERCENT, LiteConfig.MAX_SCALE_PERCENT)
        if (value >= LiteConfig.MAX_SCALE_PERCENT) return full
        return scaleEven(full.first, value) to scaleEven(full.second, value)
    }

    private fun scaleEven(pixels: Int, percent: Int): Int {
        val scaled = (pixels.toLong() * percent + 50L) / 100L
        val value = scaled.toInt().coerceAtLeast(2)
        return if (value % 2 == 0) value else value - 1
    }

    /** build.gradle.kts 的 versionName（每轮出包带阶段后缀），用于日志区分构建。 */
    private fun buildVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
    } catch (_: Exception) {
        "unknown"
    }

    /** 已安装 APK 的时间戳，配合 versionName 交叉确认跑的是哪个产物。 */
    private fun apkStamp(): String {
        val path = applicationInfo?.sourceDir ?: return "unknown"
        val file = File(path)
        if (!file.exists()) return file.name
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(file.lastModified()))
        return "${file.name}@$stamp"
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this)
            .setSmallIcon(android.R.drawable.ic_media_play) // 零图片资源策略：借用系统图标
            .setContentTitle("DiPlay Lite")
            .setContentText("无线 CarPlay 会话保持中")
            .setOngoing(true)
            .getNotification()
    }

    companion object {
        private const val NOTIFICATION_ID = 1

        /**
         * 进程内当前服务实例：MainActivity（触摸/视频）需要拿到同进程的 Session 转触点。
         * 无绑定、无 AIDL —— 与 LiteVideoBridge 相同的进程内单例约定。
         */
        @Volatile
        var current: LiteSessionService? = null

        /**
         * M6k25：关闭中的旧 controller。onDestroy 只发起 close() 不等待（主线程不能阻塞），
         * 下一轮 startController 先 awaitClosed 它 —— 覆盖 stopService 后新服务实例启动、
         * 进程内重建重启后立即重连等跨实例场景，避免残留端口/解码器让新会话卡死在连接中。
         */
        @Volatile
        private var closingController: CarPlayController? = null

        private const val IDENTITY_PREFS = "diplay_lite_identity"
        private const val PAIRING_PREFS = "diplay_lite_pairings"
        private const val WIRED_PAIRING_PREFS = "diplay_lite_wired_pairing"
        private const val KEY_PRIVATE_KEY = "private_key"
        private const val KEY_PUBLIC_KEY = "public_key"
        private const val KEY_PAIRING_ID = "pairing_id"
        const val EXTRA_CHANNEL = "channel"
        private val MFI_FILES = listOf("identity.pk8", "certificate.p7b")
        private val DEFAULT_SCREEN = 800 to 480 // C8815 屏幕兜底
    }
}
