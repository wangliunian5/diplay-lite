package com.shilapi.diplay.lite

import android.app.Notification
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.IBinder
import android.util.Base64
import android.view.WindowManager
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.AirPlayDeviceInfo
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
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
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
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
     */
    fun sendTouch(contacts: List<AirPlayContact>): Boolean = controller?.sendTouch(contacts) == true

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        current = this
        startForeground(NOTIFICATION_ID, buildNotification())
        LiteState.running = true
        LiteState.phase = LiteState.Phase.CONNECTING
        LiteState.statusText = "正在初始化…"
        LiteLog.log("service", "前台会话服务已启动")
        // 构建标识：实机每次启动都打印，用于区分本轮跑的是哪个包（T17 归档约定）。
        LiteLog.log("service", "构建标识 version=${buildVersion()} apk=${apkStamp()}")
        startController()
        return START_STICKY
    }

    override fun onDestroy() {
        // close() 在后台线程按序拆除：无线栈（AirPlay → 隧道 → 蓝牙）→ MFi → 定位。
        // 拆栈可能等待 socket 关闭，onDestroy 在主线程，故不 awaitClosed()；
        // 拆栈线程随进程存续（MainActivity 在前台时进程不会先死）。
        controller?.close()
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
        // 重复 onStartCommand（START_STICKY 自启恢复 / 用户再点连接）时先按序拆除旧
        // controller，避免残留的 7000 监听让新会话 EADDRINUSE（10-02 20:01 实测）。
        controller?.let { old ->
            controller = null
            old.close()
            old.awaitClosed(2000)
        }
        mediaSink?.close()
        mediaSink = null
        mediaEngine = null
        LiteVideoBridge.bindSink(null)
        val config = LiteConfig.load(this)
        if (config.ssid.isBlank()) {
            fail("车机热点 SSID 未配置")
            return
        }
        val security = mapSecurity(config.security)
        if (security != ManualHotspotSecurity.OPEN && config.password.length !in 8..63) {
            fail("热点密码需 8-63 位（当前 ${config.password.length} 位）")
            return
        }
        ensureMfiDirectory()
        val identity = loadOrCreateIdentity()
        val deviceId = deviceIdOf(identity)
        val btMac = localBluetoothAddress() ?: deviceId
        val size = screenMetrics()
        LiteLog.log(
            "service",
            "构造 CarPlayController deviceId=$deviceId btMac=$btMac " +
                "screen=${size.first}x${size.second} ssid=${config.ssid} " +
                "channel=${config.channel} security=${config.security}",
        )
        // M3：MediaCodec 解码到 SurfaceView；SurfaceView 的 Surface 由 LiteVideoBridge 补齐。
        val sink = AndroidMediaSink(
            videoWidth = size.first,
            videoHeight = size.second,
            onAudioDiagnostic = { LiteLog.log("media", it) },
        )
        // 麦克风上行：RECORD_AUDIO 在 targetSdk19 下安装即授予，原生分支同样按“可用”声明。
        val engine = CarPlayMediaEngine(sink = sink, microphoneEnabled = true)
        mediaSink = sink
        mediaEngine = engine
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
                    // MANUAL_IPV4：MANUAL 在 API19+ 上会优先选 IPv6 链路本地地址（fe80::），
                    // iPhone 无法用它接入 Wi-Fi 侧（nova3e 实测：AirPlay 监听绑 fe80::…:7000、
                    // 会话永远建不起来）。C8815 之所以"碰巧"正常，是因为 API16 的
                    // NetworkInterface.getIndex 取不到（回退 0），IPv6 分支被跳过而落到 IPv4。
                    wirelessHotspotMode = WirelessHotspotMode.MANUAL_IPV4,
                    manualHotspotSsid = config.ssid,
                    manualHotspotPassphrase = if (security == ManualHotspotSecurity.OPEN) null else config.password,
                    manualHotspotChannel = config.channel,
                    manualHotspotSecurity = security,
                    wirelessBluetoothDeviceAddress = config.bluetoothAddress.takeIf { it.isNotBlank() },
                ),
                airPlayConfig = AirPlayConfig(
                    deviceName = "DiPlay",
                    deviceId = deviceId,
                    btMac = btMac,
                    sourceVersion = "950.7.1",
                    main = AirPlayDisplayConfig(
                        widthPixels = size.first,
                        heightPixels = size.second,
                    ),
                    manufacturer = "DiPlay",
                    model = "DiPlay",
                    // /info 必须声明 audioInputFormats，否则 iPhone 不建立 CarPlay 音频流
                    // （原先走默认 microphone=false，会话里连基础 type=100 都没有）。
                    microphone = true,
                    // ⚠️ M4i（2026-10-03）：本字段当前**不再影响 audioFormats**——iPhone 要求带无线
                    // 输入的流"上下行 OPUS 位一致"（K60 都带→建流；T26 都不带→零流；M4h out 带/in 不带
                    // →仍零流），故 M4i 让上下行都声明 OPUS。此字段保留备用（将来若要"仅对播放分级"）。
                    // 详见 AirPlayInfoPlist.audioFormats / 超低版本实机测试记录.md T23/T26/T27。
                    opusEncoderAvailable = false,
                ),
                identity = identity,
                pairings = createPairingStore(),
                listener = sessionListener,
                media = engine,
                reportStatus = ::onStatus,
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
    }

    /** CarPlayStatus -> LiteState 映射；controller 已投递到主线程回调。 */
    private fun onStatus(status: CarPlayStatus) {
        LiteLog.log("carplay", "状态：$status")
        when (status) {
            is CarPlayStatus.DiscoveringMfi -> connecting("正在加载 MFi 证书")
            is CarPlayStatus.MfiReady -> connecting("MFi 证书就绪")
            is CarPlayStatus.StartingHotspot -> connecting("正在加入车机热点…")
            is CarPlayStatus.HotspotReady -> connecting("热点就绪 ssid=${status.ssid} band=${status.band}")
            is CarPlayStatus.WaitingForPairedIphone -> connecting("等待 iPhone 连接热点（请先在蓝牙设置中配对）")
            is CarPlayStatus.AttachingNetwork -> connecting("正在下发网络配置（0x5703）")
            is CarPlayStatus.ConnectingBluetooth -> connecting("蓝牙 iAP2 握手中")
            is CarPlayStatus.RunningWireless -> connecting("无线链路建立中")
            is CarPlayStatus.WirelessActive -> {
                LiteState.phase = LiteState.Phase.ACTIVE
                LiteState.statusText = "无线 CarPlay 已激活"
            }
            is CarPlayStatus.ControlEnded -> {
                LiteState.phase = LiteState.Phase.IDLE
                LiteState.statusText = "控制通道已结束"
            }
            is CarPlayStatus.Failed -> fail(status.message)
        }
    }

    private fun connecting(text: String) {
        LiteState.phase = LiteState.Phase.CONNECTING
        LiteState.statusText = text
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

    private fun mapSecurity(value: String): ManualHotspotSecurity = when (value) {
        "WPA2-WPA3" -> ManualHotspotSecurity.WPA3_TRANSITION
        "WPA3" -> ManualHotspotSecurity.WPA3
        "OPEN" -> ManualHotspotSecurity.OPEN
        else -> ManualHotspotSecurity.WPA2
    }

    @Suppress("DEPRECATION")
    private fun screenMetrics(): Pair<Int, Int> {
        val manager = getSystemService(WINDOW_SERVICE) as? WindowManager
        val display = manager?.defaultDisplay ?: return DEFAULT_SCREEN
        val width = display.width
        val height = display.height
        if (width <= 0 || height <= 0) return DEFAULT_SCREEN
        // M4m：本工程固定横屏（manifest screenOrientation=landscape），但服务可能在没有 Activity 的
        // 情况下被系统 START_STICKY 拉起来（实测 2026-10-03 14:47 那次 `screen=540x960`，即崩溃重启后
        // 的自动重连），此时 Display 还是竖屏，会把竖屏尺寸写进 /info 与 HID 描述符。
        // 统一按"长边为宽"归一成横屏；真·横屏车机上 width>height，不受影响。
        return if (width > height) width to height else height to width
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

        private const val IDENTITY_PREFS = "diplay_lite_identity"
        private const val PAIRING_PREFS = "diplay_lite_pairings"
        private const val KEY_PRIVATE_KEY = "private_key"
        private const val KEY_PUBLIC_KEY = "public_key"
        private const val KEY_PAIRING_ID = "pairing_id"
        private val MFI_FILES = listOf("identity.pk8", "certificate.p7b")
        private val DEFAULT_SCREEN = 800 to 480 // C8815 屏幕兜底
    }
}
