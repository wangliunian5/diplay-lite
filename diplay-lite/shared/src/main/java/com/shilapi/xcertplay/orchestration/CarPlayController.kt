package com.shilapi.xcertplay.orchestration

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.AirPlayDeviceInfo
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.network.AirPlayTcpServer
import com.shilapi.xcertplay.network.CarHotspotStatus
import com.shilapi.xcertplay.network.CarPlayBonjour
import com.shilapi.xcertplay.network.ConfirmedChannelMemory
import com.shilapi.xcertplay.network.Ipv6NcmBridge
import com.shilapi.xcertplay.network.ManualHotspotManager
import com.shilapi.xcertplay.network.RelayClientManager
import com.shilapi.xcertplay.network.WiredVpnService
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.network.WirelessHotspotManager
import com.shilapi.xcertplay.network.diagnosticSummary
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.transport.BluetoothRfcommDuplexStream
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.Iap2UsbMuxHost
import com.shilapi.xcertplay.transport.Iap2UsbSession
import com.shilapi.xcertplay.transport.Iap2WirelessCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WirelessControlClient
import com.shilapi.xcertplay.transport.Iap2WirelessControlTerminal
import com.shilapi.xcertplay.transport.Iap2WirelessIdentification
import com.shilapi.xcertplay.transport.Iap2WiredCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WiredControlClient
import com.shilapi.xcertplay.transport.IphoneCarPlayConfiguration
import com.shilapi.xcertplay.transport.IphoneUsbException
import com.shilapi.xcertplay.transport.IphoneUsbHost
import com.shilapi.xcertplay.transport.IphoneUsbMatcher
import com.shilapi.xcertplay.transport.LockdownCarKitClient
import com.shilapi.xcertplay.transport.LockdownPairingClient
import com.shilapi.xcertplay.transport.LockdownPairRecord
import com.shilapi.xcertplay.transport.NcmFunctionDiscovery
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.Closeable
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 无线 CarPlay 状态序列（Lite：仅保留无线路径）。
 *
 * 改编思路：原有线 USB 状态（DiscoveringIphone/WaitingForReenumeration/Pairing 等）
 * 与 P2P 相关状态全部裁剪，只留下「MFi → 热点 → 蓝牙 → AirPlay → Handoff」主线。
 */
sealed class CarPlayStatus {
    object DiscoveringMfi : CarPlayStatus() { override fun toString() = "DiscoveringMfi" }
    object MfiReady : CarPlayStatus() { override fun toString() = "MfiReady" }
    object StartingHotspot : CarPlayStatus() { override fun toString() = "StartingHotspot" }
    data class HotspotReady(
        val ssid: String,
        val band: String,
        val channel: Int,
        val bssid: String,
        val address: String,
        val backend: String,
    ) : CarPlayStatus()
    object WaitingForPairedIphone : CarPlayStatus() { override fun toString() = "WaitingForPairedIphone" }
    object ConnectingBluetooth : CarPlayStatus() { override fun toString() = "ConnectingBluetooth" }
    object RunningWireless : CarPlayStatus() { override fun toString() = "RunningWireless" }
    object WirelessActive : CarPlayStatus() { override fun toString() = "WirelessActive" }
    object AttachingNetwork : CarPlayStatus() { override fun toString() = "AttachingNetwork" }
    object ControlEnded : CarPlayStatus() { override fun toString() = "ControlEnded" }
    // —— USB 有线状态序列（U3，语义对齐上游有线编排）——
    object DiscoveringIphone : CarPlayStatus() { override fun toString() = "DiscoveringIphone" }
    object WaitingForIphone : CarPlayStatus() { override fun toString() = "WaitingForIphone" }
    object RequestingIphonePermission : CarPlayStatus() { override fun toString() = "RequestingIphonePermission" }
    object WaitingForReenumeration : CarPlayStatus() { override fun toString() = "WaitingForReenumeration" }
    object SelectingConfiguration : CarPlayStatus() { override fun toString() = "SelectingConfiguration" }
    object OpeningDataPaths : CarPlayStatus() { override fun toString() = "OpeningDataPaths" }
    object Pairing : CarPlayStatus() { override fun toString() = "Pairing" }
    object ConnectingControl : CarPlayStatus() { override fun toString() = "ConnectingControl" }
    object RunningControl : CarPlayStatus() { override fun toString() = "RunningControl" }
    data class Failed(val message: String) : CarPlayStatus()
    // M6k11：iPhone 僵尸会话提示——重试大概率无效，需要用户干预（关开 iPhone 蓝牙）。
    data class RecoveryHint(val message: String) : CarPlayStatus()
}

internal fun isWirelessHandoffInProgress(
    handoffRequested: Boolean,
    tunnelActive: Boolean,
    sessionActive: Boolean,
): Boolean = handoffRequested || tunnelActive || sessionActive

/**
 * 无线 CarPlay 编排器（Lite：车机自带热点 + 蓝牙引导 + Wi-Fi AirPlay）。
 *
 * 改编思路（相对 minapi21 分支）：
 * - 删除全部有线 USB/NCM/USBMUX/Lockdown 路径与 CH341/I2C/REMOTE MFi 后端，
 *   MFi 固定为 LOCAL 离线证书，热点固定为 MANUAL（车机自带热点）。
 * - AirPlay TCP 监听从 CarPlayVpnService（Android Service + Binder + VPN）
 *   替换为进程内 [AirPlayTcpServer]，去掉 Service 绑定与 VPN 权限流程，
 *   降低 4.1/4.4 老系统上的系统资源占用与兼容风险。
 * - 保留原分支的无线握手、type-130 隧道 handoff、看门狗与降级逻辑，行为不变。
 *
 * 阻塞工作运行在单线程 executor；状态回调投递到主线程。
 */
class CarPlayController(
    private val context: Context,
    private val config: CarPlayRuntimeConfig,
    private val airPlayConfig: AirPlayConfig,
    private val identity: AirPlayIdentity,
    private val pairings: PairingStore,
    listener: AirPlaySessionListener,
    private val media: AirPlayMediaHandler,
    reportStatus: (CarPlayStatus) -> Unit,
    private val locationProvider: Iap2LocationProvider? = null,
    private val loadPairRecord: () -> LockdownPairRecord? = { null },
    private val savePairRecord: (LockdownPairRecord) -> Unit = {},
    private val clearPairRecord: () -> Unit = {},
) : Closeable {
    init {
        require(!config.locationReportingEnabled || locationProvider != null) {
            "A location provider is required when location reporting is enabled"
        }
        BydNavigationOutputs.start(context.applicationContext)
    }

    private enum class Phase { IDLE, MFI, WIRELESS, IPHONE, REENUMERATION, DATAPATHS, CONTROL }

    private val appContext = context.applicationContext

    // API16 适配：BluetoothManager 类是 API 18+（getSystemService(String) 于 API 23 才支持
    // 该字符串键），4.x 上无法实例化。>=18 先尝试 Manager 路径，低版本直接回退
    // getDefaultAdapter()（API 1，虽已废弃但 4.1/4.4 上是唯一稳定入口）。
    private val bluetoothAdapter: BluetoothAdapter? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        } else {
            null
        } ?: BluetoothAdapter.getDefaultAdapter()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val touchExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val tunnelExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // M6k 触摸 latest-wins：C8815 上每个 ACTION_MOVE 都会走一次同步阻塞的 TCP 写
    // （AirPlaySession.sendCommand 内 write+flush），手指快速滑动时 MOVE 在单线程队列里
    // 排长队 → 触摸"不跟手"。改为：DOWN/UP（sticky）必发不合并；MOVE 只保留最新，
    // 中间态直接丢弃。发送泵（pump）在 touchExecutor 上串行排空 pendingTouch。
    private val touchLock = Any()
    private val pendingTouch = ArrayDeque<PendingTouch>()
    private var touchPumpRunning = false
    private var touchSentCount = 0
    private var touchCoalescedCount = 0
    private var touchDroppedCount = 0
    private var lastTouchStatsNs = System.nanoTime()
    private val lifecycleLock = Any()
    @Volatile private var uiListener: AirPlaySessionListener? = listener
    @Volatile private var uiStatusReporter: ((CarPlayStatus) -> Unit)? = reportStatus

    @Volatile private var closed = false
    @Volatile private var phase = Phase.IDLE
    @Volatile private var mfiSession: MfiSession? = null
    @Volatile private var activeSession: AirPlaySession? = null
    @Volatile private var hotspot: WirelessHotspotManager? = null
    // M6k24（C 方案）：未建流 EOF 整代重启时暂存的热点附着（manager + 上轮信息），下轮复用。
    @Volatile private var retainedHotspot: Pair<WirelessHotspotManager, WirelessHotspotInfo>? = null
    @Volatile private var lastHotspotInfo: WirelessHotspotInfo? = null
    @Volatile private var bonjour: CarPlayBonjour? = null
    @Volatile private var bluetoothSocket: BluetoothSocket? = null
    @Volatile private var bluetoothStream: BluetoothRfcommDuplexStream? = null
    @Volatile private var wirelessTunnelChannel: Iap2Session? = null
    @Volatile private var wirelessIdentification: Iap2IdentificationConfig? = null
    @Volatile private var wirelessAirPlayEndpoint: Iap2WirelessCarPlayEndpoint? = null
    @Volatile private var airPlayServer: AirPlayTcpServer? = null
    private val wirelessHandoffRequested = AtomicBoolean(false)
    private val wirelessTunnelReady = AtomicBoolean(false)
    private val wirelessActiveReported = AtomicBoolean(false)

    /**
     * M6g：已判定为「降级态」——iPhone 已建立 Wi-Fi A/V，但不请求 type-130 iAP2 隧道，
     * 于是蓝牙 RFCOMM 继续承载 iAP2 控制面（见 armWirelessHandoffWatchdog）。
     *
     * 与 [wirelessActiveReported] 刻意分开：后者是「真交接完成（隧道就绪且已放开蓝牙）」，
     * 还被 CHANNEL_CLOSED / catch 分支用来决定「保活」还是「拆栈失败」；若在降级态复用它，
     * 蓝牙 RFCOMM 真断时就再也不会拆栈，会留下僵尸会话。
     */
    private val wirelessDegradedActive = AtomicBoolean(false)

    /** M6h：蓝牙 iAP2 掉链后的自动重连计数；只在「会话真的建立」或「用户手动重连」时清零。 */
    private val wirelessRetryCount = AtomicInteger(0)
    // 1.0.10：wired peer 断链自动重试计数（FE-6 实测 iPhone 主动 connection closed 后 App 躺平）。
    private val wiredRetryCount = AtomicInteger(0)
    // 1.0.15：本代 0x5703 实际下发的信道；SESSION_ACTIVE 后写入成功配置记忆。
    @Volatile private var lastAdvertisedHotspotChannel = 0
    private val wirelessGeneration = AtomicInteger(0)
    private val wirelessConnectionProof = WirelessConnectionProof<AirPlaySession>()

    // M6k10 重连可观测：iAP2 隧道就绪即 authenticated；采样器每秒输出 tcpAccepted 等判据，
    // 定位「连上但 iPhone 不进来」卡在 Wi-Fi 段（tcpAccepted=0）还是 RTSP 段（tcpAccepted≥1）。
    private val wirelessAuthenticated = AtomicBoolean(false)
    @Volatile private var wirelessBringUpStartedAtMs = 0L
    @Volatile private var wirelessRestartEpisodeStartedAtMs = 0L
    // M6k11：RFCOMM 连接成功时刻锚点——CSM 打开后 <500ms 被 iPhone 关流（瞬时 EOF）
    // 即归为 IPHONE_ZOMBIE（TS7 实测 3 轮全是 +175ms 秒杀）。
    @Volatile private var wirelessLinkOpenedAtMs = 0L
    // M6k11：本轮重试是否为僵尸族——决定长退避、UI 提示与蓝牙适配器 reset。
    @Volatile private var wirelessZombieMode = false
    // M6k11 hotfix：分类结果缓存（teardown 前算好，scheduleWirelessRetry 消费后清空）。
    @Volatile private var lastWirelessFailureClass: String? = null

    // M6k23：未建流阶段（无 SETUP/未激活）控制流 peer EOF 连续计数。8815 实测：
    // pair-verify 通过、加密开启后 iPhone 立即 EOF，每 ~5s 重连 TCP 循环、gen 恒定，
    // 重连状态机完全不触发。连续 ≥2 次 → 归入可重试族走整代重启（既有 RESTART 链）。
    // 会话已激活（换流 peer-teardown）或已渲染帧不计数，避免误杀正常流程。
    @Volatile private var preStreamEofCount = 0
    @Volatile private var wirelessSamplerThread: Thread? = null
    private var lastReportedStatus: CarPlayStatus? = null
    private val teardownComplete = CountDownLatch(1)

    // —— USB 有线（U3）：wired 资源独立字段，不与无线族共享；UsbManager 不可用时不致命，
    // 仅在启动有线模式时才报错（构造期崩溃会连带破坏无线路径）。
    private val usbManager: UsbManager? =
        runCatching { appContext.getSystemService(Context.USB_SERVICE) as? UsbManager }.getOrNull()
    private val iphoneHost: IphoneUsbHost? = usbManager?.let {
        IphoneUsbHost(appContext, it, IphoneUsbMatcher.appleVendor())
    }

    init {
        // 1.0.2：把 USB 主机侧关键节拍（setConfiguration/claim/重试）接进文件日志，
        // 避免只写 logcat 导致远端排障信息缺失。
        iphoneHost?.debugSink = { message -> debugLog(message) }
    }

    private val hostId = UUID.randomUUID().toString().uppercase(Locale.US)
    private val systemBuid = UUID.randomUUID().toString().uppercase(Locale.US)
    private val permissionGrant = AtomicBoolean(false)
    private val availabilityPollGeneration = AtomicInteger(0)
    private var permissionPollGeneration = 0
    private var reenumerationAttempts = 0
    // 1.0.1：重枚举阶段看门狗 + 轮询兜底的车代（attach/权限路径会递增 availabilityPollGeneration），
    // 双定时器都以 phase==REENUMERATION && 代未变 && 未关闭 为存活条件。
    private val reenumerationPollGeneration = AtomicInteger(0)
    @Volatile private var permissionCloseable: Closeable? = null
    @Volatile private var attachCloseable: Closeable? = null
    @Volatile private var wiredMux: Iap2UsbMuxHost? = null
    @Volatile private var wiredCsm: Iap2Session? = null
    @Volatile private var wiredBridge: Ipv6NcmBridge? = null

    private val sessionListener = object : AirPlaySessionListener {
        override fun onSessionActive(session: AirPlaySession) {
            if (activeSession !== session) BydNavigationOutputs.start(appContext)
            activeSession = session
            // 1.0.10：会话真建立 = wired 自动重试额度重置（对齐无线链「成功即归零」语义）。
            wiredRetryCount.set(0)
            debugLog(
                "AirPlay session active controller=${session.controllerId ?: "unknown"} " +
                    "peer=${session.host}",
            )
            uiListener?.onSessionActive(session)
        }

        override fun onSessionEnded(session: AirPlaySession) {
            if (activeSession === session) {
                activeSession = null
                BydNavigationOutputs.endNow()
            }
            debugLog("AirPlay session ended peer=${session.host}")
            uiListener?.onSessionEnded(session)
        }

        override fun onTransportError(message: String) {
            debugLog("AirPlay transport error: $message")
            uiListener?.onTransportError(message)
        }

        override fun onDeviceInfo(session: AirPlaySession, info: AirPlayDeviceInfo) {
            debugLog(
                "AirPlay device info name=${info.name} deviceId=${info.deviceId} " +
                    "wifiMac=${info.wifiMac} model=${info.model}",
            )
            uiListener?.onDeviceInfo(session, info)
        }

        // The user tapped the car icon in CarPlay: show the head unit's own menu, like its Home button.
        // The session keeps running in the background, so returning to DiPlay resumes CarPlay.
        override fun onHostUiRequested(session: AirPlaySession) {
            debugLog("CarPlay requested the car UI; opening the head-unit home screen")
            runCatching {
                appContext.startActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { debugLog("Car home screen could not open: ${it.javaClass.simpleName}") }
            uiListener?.onHostUiRequested(session)
        }

        override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {
            debugLog(
                "AirPlay command type=$type params=${params.keys.sorted().joinToString(",")}",
            )
            if (
                !closed &&
                activeSession === session &&
                isBluetoothHandoffCommand(type) &&
                wirelessHandoffRequested.compareAndSet(false, true)
            ) {
                debugLog(
                    "wireless CarPlay Bluetooth handoff requested; " +
                        "waiting for tunnel iAP2 readiness",
                )
                armWirelessHandoffWatchdog(wirelessGeneration.get())
                maybeCompleteWirelessHandoff()
            }
            uiListener?.onCommand(session, type, params)
        }

        override fun onDebugLog(message: String) {
            debugLog(message)
        }
    }

    fun attachUi(
        listener: AirPlaySessionListener,
        reportStatus: (CarPlayStatus) -> Unit,
    ) {
        uiListener = listener
        uiStatusReporter = reportStatus
        mainHandler.post {
            if (uiListener === listener) lastReportedStatus?.let(reportStatus)
        }
    }

    fun isClosed(): Boolean = closed

    fun hasActiveAirPlayAttachment(): Boolean = airPlayServer?.isAttached() == true

    fun start() {
        synchronized(this) {
            if (closed) return
        }
        wirelessRetryCount.set(0)
        // 1.0.10：用户手动重连 = 重新给满 wired 自动重试额度（对齐无线链 M6h 语义）。
        wiredRetryCount.set(0)
        if (config.transport == CarPlayTransport.WIRED) {
            startWiredReceivers()
        }
        startMfi()
    }

    /** 重新加载本地离线 MFi 证书并重启流水线。 */
    fun reconnectMfi() = synchronized(lifecycleLock) {
        if (closed) return
        closeMfiSession()
        startMfi()
    }

    /** 重跑 iPhone 流水线（无线：热点 -> 蓝牙 -> AirPlay；有线：发现 -> 配对 -> NCM）。保留原方法名便于上层复用。 */
    fun reconnectIphone() = synchronized(lifecycleLock) {
        if (closed) return
        // M6h：用户手动重连 = 重新给满自动重试额度，否则上一轮用尽的计数会让这次秒失败。
        wirelessRetryCount.set(0)
        // 1.0.10：wired 额度同步重置。
        wiredRetryCount.set(0)
        if (mfiSession == null) {
            startMfi()
        } else if (config.transport == CarPlayTransport.WIRED) {
            startIphone()
        } else {
            restartWireless()
        }
    }

    /**
     * M6k：触摸入口。[sticky] 为 true 表示该事件代表"触点状态变化"（DOWN/UP/POINTER_UP 等），
     * 必须逐条送达；为 false 表示纯 MOVE，可被后续 MOVE 覆盖（latest-wins）。
     *
     * 与旧实现（每个事件都 execute 一个任务 → 无界排队）不同：这里先把事件并入 pendingTouch，
     * 再确保只有一个"发送泵"任务在跑，泵在线程上串行排空队列。这样快速滑动时中间 MOVE 会被
     * 合并掉，队列长度有界，触摸不再因排队而滞后。
     */
    fun sendTouch(contacts: List<AirPlayContact>, sticky: Boolean): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        val shouldStart = synchronized(touchLock) {
            enqueueTouchLocked(contacts, sticky)
            if (touchPumpRunning) {
                false
            } else {
                touchPumpRunning = true
                true
            }
        }
        if (!shouldStart) return true
        return try {
            touchExecutor.execute { pumpTouch(session) }
            true
        } catch (_: Exception) {
            synchronized(touchLock) { touchPumpRunning = false }
            false
        }
    }

    // M6k22 方控：媒体 HID 按键 / Siri 请求入口（移植自上游 fork sendMediaButton）。
    fun sendMediaButton(index: Int): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        return try {
            touchExecutor.execute { session.sendMedia(index) }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun requestSiri(): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        return try {
            touchExecutor.execute { session.invokeSiri() }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 入队：sticky 事件必留；MOVE 若队尾也是 MOVE 则就地覆盖（丢弃中间态）。 */
    private fun enqueueTouchLocked(contacts: List<AirPlayContact>, sticky: Boolean) {
        if (sticky) {
            if (pendingTouch.size >= MAX_PENDING_TOUCH) {
                // 极端滞后：丢最旧的 MOVE（队首通常是陈旧移动），但绝不丢 sticky 状态变化。
                val head = pendingTouch.peekFirst()
                if (head != null && !head.sticky) {
                    pendingTouch.removeFirst()
                    touchDroppedCount++
                }
            }
            pendingTouch.addLast(PendingTouch(contacts, true))
            return
        }
        val tail = pendingTouch.peekLast()
        if (tail != null && !tail.sticky) {
            pendingTouch.removeLast()
            pendingTouch.addLast(PendingTouch(contacts, false))
            touchCoalescedCount++
        } else {
            pendingTouch.addLast(PendingTouch(contacts, false))
        }
    }

    /** 发送泵：在 touchExecutor 单线程上串行排空队列；排空后释放"运行中"标志。 */
    private fun pumpTouch(session: AirPlaySession) {
        while (true) {
            val next = synchronized(touchLock) {
                if (closed || pendingTouch.isEmpty()) {
                    touchPumpRunning = false
                    null
                } else {
                    pendingTouch.removeFirst()
                }
            } ?: return
            try {
                session.sendTouch(next.contacts)
                synchronized(touchLock) { touchSentCount++ }
            } catch (error: Exception) {
                debugLog("touch send failed", error)
            }
            logTouchStatsIfDue()
        }
    }

    /** 每 [TOUCH_STATS_INTERVAL_NS] 输出一次发送/合并/丢弃计数，用于实机验证是否在合并。 */
    private fun logTouchStatsIfDue() {
        val now = System.nanoTime()
        val line = synchronized(touchLock) {
            if (now - lastTouchStatsNs < TOUCH_STATS_INTERVAL_NS) return
            if (touchSentCount == 0 && touchCoalescedCount == 0 && touchDroppedCount == 0) {
                lastTouchStatsNs = now
                return
            }
            val message = "touch stats sent=$touchSentCount coalesced=$touchCoalescedCount " +
                "dropped=$touchDroppedCount pending=${pendingTouch.size}"
            touchSentCount = 0
            touchCoalescedCount = 0
            touchDroppedCount = 0
            lastTouchStatsNs = now
            message
        }
        debugLog(line)
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        BydNavigationOutputs.endNow()
        closeWiredReceivers()
        // 1.0.1：整体关闭时停掉重枚举看门狗/轮询兜底，防止关闭后定时器仍触发。
        cancelReenumerationTimers()
        availabilityPollGeneration.incrementAndGet()
        wirelessGeneration.incrementAndGet()
        permissionPollGeneration += 1
        touchExecutor.shutdownNow()
        tunnelExecutor.shutdownNow()
        Thread(
            {
                try {
                    if (config.transport == CarPlayTransport.WIRED) {
                        closeBestEffort("wired stack") { closeWiredStack() }
                    } else {
                        // M6k24：控制器整体关闭时，保留待复用的热点附着也要一并释放。
                        val retained = retainedHotspot
                        if (retained != null) {
                            closeBestEffort("RetainedHotspot") { retained.first.close() }
                            retainedHotspot = null
                        }
                        closeBestEffort("wireless stack") { closeWirelessStack() }
                    }
                    closeBestEffort("MFi") { mfiSession?.close() }
                    mfiSession = null
                    closeBestEffort("location provider") { locationProvider?.close() }
                } finally {
                    executor.shutdownNow()
                    try {
                        executor.awaitTermination(EXECUTOR_CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    teardownComplete.countDown()
                }
            },
            "xcertplay-controller-teardown",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** 等待无线各层资源拆除完成；供非主线程的生命周期调用。 */
    fun awaitClosed(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        return try {
            teardownComplete.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    // HUD (SOME/IP) and cluster (AMap broadcast) keep separate state so one failing cannot stall the other.
    private fun onRouteFrame(frame: com.shilapi.xcertplay.iap2.wire.Iap2Frame) {
        BydNavigationOutputs.onFrame(frame)
    }

    /** Lite：MFi 仅支持 LOCAL 离线证书，无 CH341/I2C/REMOTE 轮询。 */
    private fun startMfi() {
        phase = Phase.MFI
        onStatus(CarPlayStatus.DiscoveringMfi)
        // API16 适配：noBackupFilesDir 是 API 21，改用 filesDir（API 1）。
        val offlineDirectory = java.io.File(appContext.filesDir, LocalMfiAuthenticationClient.DIRECTORY)
        openLocalMfi(offlineDirectory)
    }

    private fun openLocalMfi(directory: java.io.File) {
        debugLog("mfi discovery backend=LocalOffline remoteFallback=disabled")
        executor.execute {
            try {
                val signatures = AtomicInteger(0)
                val client = LocalMfiAuthenticationClient.load(directory) { size ->
                    debugLog("mfi local signature count=${signatures.incrementAndGet()} digestBytes=$size")
                }
                if (closed || phase != Phase.MFI) return@execute
                mfiSession = MfiSession(client, null)
                debugLog("mfi local offline ready protocolMajor=${client.protocolMajor()} certificateBytes=${client.readCertificate().size}")
                onStatus(CarPlayStatus.MfiReady)
                if (config.transport == CarPlayTransport.WIRED) startIphone() else startWireless()
            } catch (error: Throwable) {
                // A broken local identity must fail closed rather than silently skip authentication.
                fail(error)
            }
        }
    }

    private fun startWireless() {
        phase = Phase.WIRELESS
        wirelessHandoffRequested.set(false)
        wirelessTunnelReady.set(false)
        wirelessActiveReported.set(false)
        wirelessDegradedActive.set(false)
        onStatus(CarPlayStatus.StartingHotspot)
        val generation = wirelessGeneration.incrementAndGet()
        executor.execute {
            runWireless(generation)
        }
    }

    private fun restartWireless() {
        // M6k11 修复 gen 双自增：startWireless() 内部已 incrementAndGet，
        // 此处再 +1 导致实测 gen 1→3→5→7→…；陈旧重试失效判断不受影响。
        Thread(
            {
                closeWirelessStack()
                // M6k11：僵尸族第 3 次起，teardown 后顺带 reset 本机蓝牙适配器，
                // 清车机侧 RFCOMM 服务状态。失败只记日志不阻断重连。
                if (!closed && wirelessZombieMode && wirelessRetryCount.get() >= 3) {
                    try {
                        val adapter = bluetoothAdapter
                        if (adapter?.isEnabled == true) {
                            debugLog("wireless local BT adapter reset begin (zombie recovery)")
                            adapter.disable()
                            SystemClock.sleep(1200)
                            adapter.enable()
                            // enable() 是异步的，等回 ON 再继续（最多 8s），防止 RFCOMM 白跑一轮
                            var waited = 0
                            while (!adapter.isEnabled && waited < 8_000) {
                                SystemClock.sleep(250)
                                waited += 250
                            }
                            debugLog("wireless local BT adapter reset done waitedMs=$waited")
                        }
                    } catch (error: Throwable) {
                        debugLog("wireless local BT adapter reset failed: ${error.message}")
                    }
                }
                if (!closed) startWireless()
            },
            "xcertplay-wireless-restart",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun runWireless(generation: Int) {
        try {
            debugLog("wireless bring-up generation=$generation starting")
            wirelessBringUpStartedAtMs = SystemClock.elapsedRealtime()
            closeWirelessStack()
            if (closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get()) {
                return
            }

            val mfi = mfiSession?.client
                ?: throw IOException("MFi coprocessor client is unavailable")
            val hotspotInfo = startWirelessHotspot(generation)
            // 1.0.15：记住本代实际下发的信道，供 SESSION_ACTIVE 后写入成功配置记忆。
            lastAdvertisedHotspotChannel = hotspotInfo.channel
            if (isStaleWirelessRun(generation)) {
                closeWirelessStack()
                return
            }
            val startedHotspot = hotspot
            wirelessConnectionProof.begin(generation) {
                if (!isStaleWirelessRun(generation)) startedHotspot?.onCarPlayConfirmed()
            }
            val hostAddress = hotspotInfo.hostAddress
                ?: throw IOException(
                    "Wireless hotspot did not provide a usable host address",
                )
            if (
                hostAddress is Inet6Address &&
                (!hostAddress.isLinkLocalAddress || hostAddress.scopeId == 0)
            ) {
                throw IOException(
                    "Wireless hotspot link-local IPv6 address is not scoped",
                )
            }
            val hostAddressText = hostAddressText(hostAddress)
            val deviceIdentifier = hotspotInfo.bssid
                ?.takeUnless { it.equals(ADAPTER_ADDRESS_PLACEHOLDER, ignoreCase = true) }
                ?: airPlayConfig.deviceId
            debugLog(
                "wireless hotspot backend=${hotspotInfo.backend.label} " +
                    "iface=${hotspotInfo.interfaceName ?: "unknown"} " +
                    "family=${if (hostAddress is Inet6Address) "IPv6" else "IPv4"} " +
                    "identitySource=${if (deviceIdentifier == hotspotInfo.bssid) "interface" else "saved"} " +
                    "host=$hostAddressText " +
                    "band=${hotspotInfo.bandLabel} channel=${hotspotInfo.channel} " +
                    "frequency=${hotspotInfo.frequencyMHz?.toString() ?: "unknown"}MHz",
            )
            onStatus(
                CarPlayStatus.HotspotReady(
                    ssid = hotspotInfo.ssid,
                    band = hotspotInfo.bandLabel,
                    channel = hotspotInfo.channel,
                    bssid = deviceIdentifier,
                    address = hostAddressText,
                    backend = hotspotInfo.backend.label,
                ),
            )
            onStatus(CarPlayStatus.WaitingForPairedIphone)

            val adapter = bluetoothAdapter
                ?: throw IOException("Bluetooth adapter is unavailable")
            if (!adapter.isEnabled) throw IOException("Bluetooth is not enabled")
            val device = selectWirelessBluetoothDevice(adapter)
            val hostBluetoothMac = accessoryBluetoothMac(adapter)
            debugLog(
                "wireless selected Bluetooth target name=${device.name ?: "unknown"} " +
                    "address=${device.address} localBt=$hostBluetoothMac",
            )
            val wirelessAirPlayConfig = airPlayConfig.copy(
                deviceId = deviceIdentifier,
                btMac = hostBluetoothMac,
            )

            // Lite：进程内 AirPlayTcpServer 取代 CarPlayVpnService 绑定。
            onStatus(CarPlayStatus.AttachingNetwork)
            startWirelessSampler(generation)
            val server = AirPlayTcpServer()
            when (
                val result = server.attach(
                    bindAddress = hostAddress,
                    config = wirelessAirPlayConfig,
                    identity = identity,
                    pairings = pairings,
                    mfi = mfi,
                    listener = wirelessSessionListener(generation),
                    media = media,
                )
            ) {
                AirPlayTcpServer.AttachResult.Started -> airPlayServer = server
                AirPlayTcpServer.AttachResult.AlreadyStarted ->
                    throw IOException("Wireless AirPlay transport is already attached")
                is AirPlayTcpServer.AttachResult.Failed ->
                    throw IOException(result.message)
            }
            debugLog(
                "wireless AirPlay listener attached bind=$hostAddressText " +
                    "port=${airPlayConfig.port} (Bonjour advertisement uses the same port)",
            )
            // M6k20（移植自上游 FirstTcpWatchdog 语义，适配 Lite 架构）：
            // 热点+监听口就绪后，若 FIRST_TCP_TIMEOUT_MILLIS 内 iPhone 从未发起任何 TCP
            // 连接（陈旧 mDNS 缓存、Wi-Fi 未入网、AP 隔离），显性失败并走重连状态机，
            // 而不是挂到 45s handoff 超时（旧路径 bootstrap 超时不触发重连，等于死等）。
            armFirstTcpWatchdog(generation)
            if (isStaleWirelessRun(generation)) {
                closeWirelessStack()
                return
            }

            val bonjourClient = CarPlayBonjour(
                context = appContext,
                config = wirelessAirPlayConfig,
                identity = identity,
                advertisedHost = hostAddress.hostAddress,
                // Bind discovery and its connect probe to the same AP/address family as AirPlay.
                // The car hotspot previously used system NSD, which could resolve another interface
                // or IPv6 while the listener/probe was bound to the AP's IPv4 address.
                useInterfaceMdns = true,
                onEvent = { event -> debugLog("wireless bonjour: ${event.diagnosticSummary()}") },
            )
            bonjour = bonjourClient
            try {
                bonjourClient.start()
                debugLog(
                    "wireless Bonjour services started mode=interface " +
                        "iface=${hotspotInfo.interfaceName ?: "unknown"}",
                )
            } catch (error: Throwable) {
                // 自研 mDNS 落地前的临时策略（10-02）：4.1 上 JmDNS joinGroup 报 ENODEV，
                // 但 mDNS 广播与蓝牙 iAP2 握手相互独立。此处降级为非致命、放行蓝牙段，
                // 以便先探测 Apple 端鉴权与 iPhone 入网行为；换自研 mDNS 后恢复为致命。
                bonjour = null
                runCatching { bonjourClient.close() }
                debugLog(
                    "wireless Bonjour 广播失败（非致命，继续蓝牙段）: ${error.message}",
                )
            }
            if (isStaleWirelessRun(generation)) {
                closeWirelessStack()
                return
            }

            onStatus(CarPlayStatus.ConnectingBluetooth)
            val socket = openWirelessRfcommSocket(device)
            wirelessLinkOpenedAtMs = SystemClock.elapsedRealtime()
            debugLog("wireless RFCOMM connected address=${device.address}")
            if (isStaleWirelessRun(generation)) {
                closeWirelessStack()
                return
            }
            val stream = BluetoothRfcommDuplexStream(socket).also { bluetoothStream = it }
            val channel = Iap2Session.openWireless(
                stream,
                traceContext = "wireless-rfcomm",
                onTrace = ::debugLog,
            )
            debugLog("wireless iAP2 CSM channel opened over RFCOMM")
            if (isStaleWirelessRun(generation)) {
                closeWirelessStack()
                return
            }
            val identification = config.identification.copy(
                wireless = Iap2WirelessIdentification(hostBluetoothMac, hotspotInfo.ssid),
            )
            val endpoint = Iap2WirelessCarPlayEndpoint(
                ssid = hotspotInfo.ssid,
                passphrase = hotspotInfo.passphrase,
                channel = hotspotInfo.channel,
                security = hotspotInfo.security,
                ipAddresses = listOf(hostAddressText),
                airPlayPort = airPlayConfig.port,
                deviceIdentifier = deviceIdentifier,
                publicKey = identity.publicKeyHex,
                sourceVersion = airPlayConfig.sourceVersion,
                bssid = hotspotInfo.bssid,
            )
            wirelessIdentification = identification
            wirelessAirPlayEndpoint = endpoint
            media.setIapTunnelHandler(::startWirelessTunnelControl)

            onStatus(CarPlayStatus.RunningWireless)
            debugLog("wireless Bluetooth iAP2 control starting")
            val result = Iap2WirelessControlClient(
                session = channel,
                mfi = Iap2MfiAuthenticationClient(mfi),
            ).run(
                identification = identification,
                endpoint = endpoint,
                timeoutMillis = controlLoopTimeoutMillis(),
                locationProvider = locationProvider,
                onIncoming = ::onRouteFrame,
                onProgress = ::debugLog,
                // M6k24（A 方案）：iPhone 一旦发起过 AirPlay TCP 连接就停止凭证重发。
                phoneOnAirPlay = { (airPlayServer?.acceptedConnectionCount() ?: 0) > 0 },
            )
            if (isStaleWirelessRun(generation)) {
                closeWirelessStack()
                return
            }
            when (result.terminal) {
                Iap2WirelessControlTerminal.CHANNEL_CLOSED -> {
                    debugLog(
                        "wireless RFCOMM EOF: iap2State=${result.stage} " +
                            "wirelessCarPlayAvailable=${result.wirelessCarPlayAvailableSeen} " +
                            "transportIdentifier=${result.transportNotificationSeen} " +
                            "carPlayStartSessions=${result.carPlayStartSessionsSent} " +
                            "postTransportConfigs=${result.postTransportWiFiConfigurationsSent} " +
                            "handoffRequested=${wirelessHandoffRequested.get()} " +
                            "tunnelReady=${wirelessTunnelReady.get()} " +
                            "wirelessActive=${wirelessActiveReported.get()}",
                    )
                    if (!wirelessActiveReported.get()) {
                        val handoffInProgress = isWirelessHandoffInProgress(
                            handoffRequested = wirelessHandoffRequested.get(),
                            tunnelActive = wirelessTunnelChannel != null,
                            sessionActive = activeSession != null,
                        )
                        if (!handoffInProgress) {
                            throw IOException(
                                "Wireless CarPlay control channel closed before tunnel iAP2 ready",
                            )
                        }
                        debugLog(
                            "wireless Bluetooth bootstrap closed during handoff; " +
                                "keeping the Wi-Fi AirPlay tunnel alive",
                        )
                    }
                }
                Iap2WirelessControlTerminal.TIMED_OUT ->
                    // M6g：降级态下 iAP2 挂在蓝牙上、音视频照常，「控制窗口结束」不等于会话结束，
                    // 不要报 ControlEnded 误导（T43 实测过这个误报）。真交接态由
                    // wirelessActiveReported 抑制；两者都没发生（iAP2 建链就没起来）才如实上报。
                    if (!wirelessActiveReported.get() && !wirelessDegradedActive.get()) {
                        onStatus(CarPlayStatus.ControlEnded)
                    }
            }
        } catch (error: Throwable) {
            if (closed || generation != wirelessGeneration.get()) {
                return
            }
            if (wirelessActiveReported.get() && error !is Error) {
                debugLog("wireless RFCOMM control ended after tunnel handoff: ${error.message}")
            } else {
                debugLog("wireless bring-up failed", error)
                // M6k11 hotfix：必须在 closeWirelessStack() 之前完成分类——teardown 会清零
                // wirelessLinkOpenedAtMs，先 teardown 后分类会丢失僵尸时间窗证据，
                // 导致 hoo 实测（23:11 日志 +172ms 秒杀）全部误落普通族 IAP2_LINK_ENDED。
                lastWirelessFailureClass = wirelessFailureClass(error)
                closeWirelessStack()
                if (error is Error) throw error
                // M6h：链路掉线（abort/reset/RFCOMM 断）不再直接停在 Failed 要手点，
                // 自动重试若干次；热点没开、配置不合法这类确定性失败仍如实报错。
                if (isRetryableWirelessFailure(error)) {
                    scheduleWirelessRetry(generation, error)
                } else {
                    fail(error)
                }
            }
        }
    }

    /**
     * M6h：判断是否值得自动重连。只认"链路掉了"这一族瞬时故障
     * （T36 实测：微信电话期间 `IAP2 RX FAILED: Software caused connection abort`；
     * RFCOMM 层也常见 `Connection refused` / `Service discovery failed`，T14 曾手点 7 次才连上）。
     */
    private fun isRetryableWirelessFailure(error: Throwable): Boolean {
        val message = error.message.orEmpty().lowercase()
        return RETRYABLE_WIRELESS_FAILURES.any { message.contains(it) }
    }

    /**
     * M6k9：把错误归到一族标签，纯为日志可读（判据仍是 [RETRYABLE_WIRELESS_FAILURES] 子串表，
     * 不另建一套分类逻辑）。日志里见到 class=PEER_REQUEST/LOCAL_STOP 即「不该重连」，
     * class=OTHER 即「不在可重连族内」。
     */
    /**
     * M6k11：瞬时 EOF 单独归类 IPHONE_ZOMBIE——判据是时间窗：CSM 打开后 <500ms
     * 被关即视为 iPhone 侧僵尸会话（TS7 实测 3 轮全是 +175ms rx EOF；重试治不了，
     * 需长退避 + 用户关开 iPhone 蓝牙）。正常断链（如 Wi-Fi 切断后几十秒的 EOF）不受影响。
     */
    private fun wirelessFailureClass(error: Throwable): String {
        val message = error.message.orEmpty().lowercase()
        // 中继确定性失败（AP 隔离/连错网/Wi-Fi 关闭/密码错）单独成族——不重试，直接 Failed。
        if (message.contains("relay_unreachable") || message.contains("relay network") ||
            message.contains("relay client") || message.contains("wi-fi is off")
        ) {
            return "RELAY_CONFIG"
        }
        if (isRetryableWirelessFailure(error) &&
            (message.contains("iap2") || message.contains("eof") || message.contains("tunnel")) &&
            wirelessLinkOpenedAtMs != 0L
        ) {
            val sinceOpen = SystemClock.elapsedRealtime() - wirelessLinkOpenedAtMs
            if (sinceOpen < ZOMBIE_WINDOW_MILLIS) {
                return "IPHONE_ZOMBIE"
            }
        }
        val message2 = message
        return when {
            !isRetryableWirelessFailure(error) -> "OTHER"
            message2.contains("handoff") -> "HANDOFF_TIMEOUT"
            message2.contains("abort") -> "CONNECTION_ABORT"
            message2.contains("service discovery") || message2.contains("refused") -> "SERVICE_DISCOVERY_FAILED"
            message2.contains("iap2") || message2.contains("tunnel") -> "IAP2_LINK_ENDED"
            message2.contains("eof") -> "EOF"
            else -> "SOCKET_EXCEPTION"
        }
    }

    /** M6h：延时后重跑无线流水线；额度用尽才把失败交给 UI。 */
    private fun scheduleWirelessRetry(generation: Int, error: Throwable) {
        val attempt = wirelessRetryCount.incrementAndGet()
        if (wirelessRestartEpisodeStartedAtMs == 0L) {
            // 纪元起点 = 本轮故障第一次触发重连的时刻；SESSION_ACTIVE 的 total 由此起算。
            wirelessRestartEpisodeStartedAtMs = SystemClock.elapsedRealtime()
        }
        if (attempt > MAX_WIRELESS_RETRIES) {
            debugLog("wireless auto-retry exhausted after $MAX_WIRELESS_RETRIES attempts")
            fail(error)
            return
        }
        val failureClass = lastWirelessFailureClass ?: wirelessFailureClass(error)
        lastWirelessFailureClass = null
        // M6k11：僵尸族分流——快试 2 次后切长退避；第 3 次起提示用户干预并标记僵尸模式
        //（restartWireless 会据此做本机蓝牙适配器 reset）。非僵尸族走原 4s×attempt 封顶 15s。
        if (failureClass == "IPHONE_ZOMBIE") {
            wirelessZombieMode = true
        }
        val delayMs = if (wirelessZombieMode) {
            when (attempt) {
                1, 2 -> ZOMBIE_FAST_RETRY_MILLIS
                else -> {
                    if (attempt == 3) {
                        onStatus(
                            CarPlayStatus.RecoveryHint(
                                "连接被 iPhone 立即断开，正在长间隔重试；若持续失败，请关开 iPhone 蓝牙后重试",
                            ),
                        )
                    }
                    if (attempt == 3) ZOMBIE_LONG_RETRY_1_MILLIS else ZOMBIE_LONG_RETRY_2_MILLIS
                }
            }
        } else {
            minOf(
                WIRELESS_RETRY_DELAY_BASE_MILLIS * attempt,
                WIRELESS_RETRY_DELAY_MAX_MILLIS,
            )
        }
        debugLog(
            "wireless link dropped (${error.message ?: error.javaClass.simpleName}) " +
                "class=$failureClass; " +
                "auto-retry $attempt/$MAX_WIRELESS_RETRIES " +
                "RESTART gen=$generation backoff=${delayMs}ms",
        )
        mainHandler.postDelayed(
            {
                if (
                    closed ||
                    phase != Phase.WIRELESS ||
                    generation != wirelessGeneration.get()
                ) {
                    return@postDelayed
                }
                restartWireless()
            },
            delayMs,
        )
    }

    private fun startWirelessTunnelControl(stream: BlockingDuplexByteStream): Boolean {
        if (closed) return false
        val identification = wirelessIdentification ?: return false
        val endpoint = wirelessAirPlayEndpoint ?: return false
        val mfi = mfiSession?.client ?: return false
        debugLog("wireless type-130 tunnel data stream accepted")
        val channel = try {
            Iap2Session.openTunnel(
                stream,
                traceContext = "wireless-tunnel",
                onTrace = ::debugLog,
            )
        } catch (error: Throwable) {
            debugLog("Could not open the tunneled iAP2 link", error)
            return false
        }
        wirelessTunnelChannel = channel
        val generation = wirelessGeneration.get()
        debugLog("wireless iAP2 tunnel control starting")
        return try {
            tunnelExecutor.execute {
                try {
                    val result = Iap2WirelessControlClient(
                        session = channel,
                        mfi = Iap2MfiAuthenticationClient(mfi),
                    ).run(
                        identification = identification,
                        endpoint = endpoint,
                        timeoutMillis = Iap2WirelessControlClient.NO_TIMEOUT_MILLIS,
                        locationProvider = locationProvider,
                        onReady = {
                            onWirelessTunnelReady(generation)
                        },
                        onIncoming = ::onRouteFrame,
                        onProgress = { message -> debugLog("iAP tunnel $message") },
                    )
                    if (closed || generation != wirelessGeneration.get()) return@execute
                    when (result.terminal) {
                        Iap2WirelessControlTerminal.TIMED_OUT ->
                            onStatus(CarPlayStatus.ControlEnded)
                        Iap2WirelessControlTerminal.CHANNEL_CLOSED ->
                            onStatus(CarPlayStatus.Failed("Wireless iAP2 tunnel closed"))
                    }
                } catch (error: Throwable) {
                    if (!closed && generation == wirelessGeneration.get()) {
                        debugLog("tunneled iAP2 control failed", error)
                        onStatus(
                            CarPlayStatus.Failed(
                                error.message ?: error.javaClass.simpleName,
                            ),
                        )
                    }
                } finally {
                    if (wirelessTunnelChannel === channel) wirelessTunnelChannel = null
                }
            }
            true
        } catch (error: Throwable) {
            if (wirelessTunnelChannel === channel) wirelessTunnelChannel = null
            closeBestEffort("tunneled iAP2 link") { channel.close() }
            debugLog("iAP2 tunnel executor rejected the link", error)
            false
        }
    }

    private fun wirelessSessionListener(generation: Int): AirPlaySessionListener =
        object : AirPlaySessionListener by sessionListener {
            override fun onSessionActive(session: AirPlaySession) {
                if (isStaleWirelessRun(generation)) return
                wirelessConnectionProof.activate(generation, session)
                sessionListener.onSessionActive(session)
            }

            override fun onSessionEnded(session: AirPlaySession) {
                if (isStaleWirelessRun(generation)) return
                wirelessConnectionProof.end(generation, session)
                handlePreStreamEof(generation, session)
                sessionListener.onSessionEnded(session)
            }

            override fun onVideoFrameRendered(session: AirPlaySession) {
                if (isStaleWirelessRun(generation) || activeSession !== session) return
                wirelessConnectionProof.rendered(generation, session)
            }
        }

    /**
     * M6k23：未建流阶段控制流异常断开计数 → 连续 ≥2 次触发整代重启。
     * 判定条件（全部满足才计数）：本代未激活、未降级、无渲染帧；结束原因为 peer EOF
     * 或解密失败（8815 实测 pair-verify 循环的特征）。重启走既有 RESTART 链
     * （closeWirelessStack + scheduleWirelessRetry），gen 单调递增/退避语义不变。
     */
    private fun handlePreStreamEof(generation: Int, session: AirPlaySession) {
        if (closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get()) return
        if (wirelessActiveReported.get() || wirelessDegradedActive.get()) return
        if (wirelessConnectionProof.hasRenderedFrame(generation)) return
        val reason = session.lastCloseReason.lowercase()
        if (!reason.contains("eof") && !reason.contains("decrypt failed")) return
        preStreamEofCount++
        debugLog(
            "wireless pre-stream control EOF #$preStreamEofCount " +
                "(reason=${session.lastCloseReason} activeStreams=${session.activeStreams.size})",
        )
        if (preStreamEofCount < PRE_STREAM_EOF_RESTART_THRESHOLD) return
        preStreamEofCount = 0
        debugLog(
            "wireless pre-stream EOF x$PRE_STREAM_EOF_RESTART_THRESHOLD; " +
                "failing generation=$generation for full restart",
        )
        Thread(
            {
                if (closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get()) return@Thread
                // M6k24（C 方案）：重启保留热点附着，iPhone 不掉网，TCP 重试即刻可达。
                closeWirelessStack(keepHotspotForReuse = true)
                scheduleWirelessRetry(generation, IOException("pre-stream control peer EOF (pair-verify loop)"))
            },
            "xcertplay-prestream-eof",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun onWirelessTunnelReady(generation: Int) {
        if (
            closed ||
            phase != Phase.WIRELESS ||
            generation != wirelessGeneration.get()
        ) {
            return
        }
        wirelessTunnelReady.set(true)
        wirelessAuthenticated.set(true)
        wirelessConnectionProof.authenticated(generation)
        debugLog(
            "wireless iAP2 tunnel ready; " +
                "handoffRequested=${wirelessHandoffRequested.get()}",
        )
        maybeCompleteWirelessHandoff()
    }

    private fun maybeCompleteWirelessHandoff() {
        if (!wirelessHandoffRequested.get() || !wirelessTunnelReady.get()) return
        if (!wirelessActiveReported.compareAndSet(false, true)) return
        val generation = wirelessGeneration.get()
        val attempt = wirelessRetryCount.getAndSet(0)
        // M6k11：会话真建立即退出僵尸模式（后续断链重新按普通族分类）。
        wirelessZombieMode = false
        // M6k23：会话建立即清未建流 EOF 计数。
        preStreamEofCount = 0
        // 1.0.15：会话真建立 → 本代下发的信道记为「验证过能连上」，下次读不到信道时复用。
        ConfirmedChannelMemory.remember(
            context,
            if (config.wirelessHotspotMode == WirelessHotspotMode.RELAY) {
                ConfirmedChannelMemory.ROLE_RELAY
            } else {
                ConfirmedChannelMemory.ROLE_CAR
            },
            lastAdvertisedHotspotChannel,
        )
        logWirelessSessionActive(generation, attempt)
        Thread(
            {
                if (
                    closed ||
                    phase != Phase.WIRELESS ||
                    generation != wirelessGeneration.get()
                ) {
                    return@Thread
                }
                debugLog("wireless handoff ready; closing Bluetooth bootstrap transport")
                closeBluetoothBootstrapTransport()
                onStatus(CarPlayStatus.WirelessActive)
            },
            "xcertplay-wireless-handoff",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun armWirelessHandoffWatchdog(generation: Int) {
        mainHandler.postDelayed(
            {
                if (
                    closed ||
                    phase != Phase.WIRELESS ||
                    generation != wirelessGeneration.get() ||
                    !wirelessHandoffRequested.get() ||
                    wirelessActiveReported.get()
                ) {
                    return@postDelayed
                }
                debugLog("wireless handoff timed out waiting for tunnel iAP2 readiness")
                Thread(
                    {
                        if (
                            closed ||
                            phase != Phase.WIRELESS ||
                            generation != wirelessGeneration.get() ||
                            wirelessActiveReported.get()
                        ) {
                            return@Thread
                        }
                        // 合并方案A与作者 hasRenderedFrame 判定：以是否确已渲染视频帧作为分类依据。
                        // - 已渲染帧：iPhone 已建立 Wi-Fi A/V 但始终未请求 type-130 iAP2 隧道，属合法降级场景，
                        //   不再拆栈/失败，保持「蓝牙 iAP2 控制 + Wi-Fi A/V」继续运行，避免可见重连循环。
                        // - 未渲染帧：会话并未真正建立，走作者逻辑拆栈并 fail。
                        if (wirelessConnectionProof.hasRenderedFrame(generation)) {
                            // M6g：先置位再上报，避免与控制环超时分支竞态。
                            wirelessDegradedActive.set(true)
                            // M6h：会话已经可用（A/V 在跑），自动重试额度重新给满。
                            val attempt = wirelessRetryCount.getAndSet(0)
                            wirelessZombieMode = false // M6k11：degraded 收口同样退出僵尸模式
                            preStreamEofCount = 0 // M6k23：degraded 收口同样清未建流 EOF 计数
                            logWirelessSessionActive(generation, attempt, degraded = true)
                            debugLog(
                                "handoff degraded: keeping Bluetooth iAP2 + Wi-Fi A/V " +
                                    "(hasRenderedFrame, no type-130 iAP2 tunnel)",
                            )
                            onStatus(CarPlayStatus.WirelessActive)
                            return@Thread
                        }
                        // 1.0.13：handoff 超时不再终态失败（MX6DQ 实测弹「画面握手未完成」后
                        // 直接躺平，而 First TCP 超时靠重连自愈）——与控制环 catch 同款分类：
                        // 先分类留证，再拆栈，可重试族走 RESTART 链；postDelayed 里的
                        // generation 护栏保证控制环并发调度时后到者作废，不会双开新一代。
                        val error = IOException("Wireless CarPlay handoff timed out")
                        lastWirelessFailureClass = wirelessFailureClass(error)
                        closeWirelessStack()
                        if (isRetryableWirelessFailure(error)) {
                            scheduleWirelessRetry(generation, error)
                        } else {
                            fail(error)
                        }
                    },
                    "xcertplay-wireless-handoff-timeout",
                ).apply {
                    isDaemon = true
                    start()
                }
            },
            WIRELESS_HANDOFF_TIMEOUT_MILLIS,
        )
    }

    /** M6k20：first-TCP 看门狗。触发条件与 handoff 看门狗同代次护栏；TCP 已有连接或已激活则静默过期。 */
    private fun armFirstTcpWatchdog(generation: Int) {
        mainHandler.postDelayed(
            {
                if (
                    closed ||
                    phase != Phase.WIRELESS ||
                    generation != wirelessGeneration.get() ||
                    wirelessActiveReported.get() ||
                    wirelessDegradedActive.get() ||
                    // handoff 流程有自己的 45s 看门狗与降级判定，互不干预。
                    wirelessHandoffRequested.get()
                ) {
                    return@postDelayed
                }
                val accepted = airPlayServer?.acceptedConnectionCount() ?: 0
                if (accepted > 0) return@postDelayed
                debugLog(
                    "first TCP watchdog: no iPhone connection within " +
                        "${FIRST_TCP_TIMEOUT_MILLIS}ms; failing generation=$generation",
                )
                // 1.0.0-demo：中继模式（形态B）单独标记——iPhone 收到凭证却零 TCP，
                // 首要嫌疑是路由器 AP 隔离/访客隔离，与热点模式的自查指引不同。
                val relaySuspect =
                    config.transport == CarPlayTransport.WIRELESS &&
                        config.wirelessHotspotMode == WirelessHotspotMode.RELAY
                if (relaySuspect) {
                    debugLog("backend=relay isolation-suspect: credential accepted but 0 TCP within window")
                }
                Thread(
                    {
                        if (closed || generation != wirelessGeneration.get()) return@Thread
                        closeWirelessStack()
                        fail(
                            IOException(
                                (if (relaySuspect) {
                                    "First TCP timeout (relay): iPhone never connected over the relay Wi-Fi " +
                                        "(AP/client isolation on the router is the top suspect)"
                                } else {
                                    "First TCP timeout: iPhone never connected to the car network " +
                                        "(stale mDNS cache / Wi-Fi not joined / AP isolation)"
                                }),
                            ),
                        )
                    },
                    "xcertplay-first-tcp-timeout",
                ).apply {
                    isDaemon = true
                    start()
                }
            },
            FIRST_TCP_TIMEOUT_MILLIS,
        )
    }

    private fun closeBluetoothBootstrapTransport() {
        val activeStream = bluetoothStream
        bluetoothStream = null
        if (activeStream != null) closeBestEffort("wireless RFCOMM stream") { activeStream.close() }

        val activeSocket = bluetoothSocket
        bluetoothSocket = null
        if (activeSocket != null) closeBestEffort("wireless Bluetooth socket") { activeSocket.close() }
    }

    /**
     * Lite：MANUAL / MANUAL_IPV4（车机自带热点）或 RELAY（双 client 接入随身WiFi）。
     * RELAY 复用 manualHotspotSsid/Passphrase 字段作外部网络凭据；下游 0x5703/监听/Bonjour
     * 全部吃返回的 WirelessHotspotInfo，无需感知网络是建出来的还是加入的。
     */
    private fun startWirelessHotspot(generation: Int): WirelessHotspotInfo {
        // M6k24（C 方案）：上轮未建流 EOF 重启保留的热点附着仍健康 → 直接复用，
        // 跳过重新附着（最慢一步），iPhone 也不掉网、TCP 重试即刻可达。
        retainedHotspot?.let { (manager, info) ->
            retainedHotspot = null
            hotspot = manager
            lastHotspotInfo = info
            debugLog(
                "wireless hotspot reused from previous generation ssid=${info.ssid} " +
                    "host=${info.hostAddress?.let(::hostAddressText) ?: "unknown"}",
            )
            return info
        }
        val hotspotMode = config.wirelessHotspotMode
        if (
            hotspotMode.isManualHotspot &&
            CarHotspotStatus.isEnabled(appContext) == false
        ) {
            // OEM 可能魔改 AP 状态值；把原始值打进日志便于诊断 4.1 定制 ROM。
            debugLog("car hotspot AP state=${CarHotspotStatus.rawState(appContext)} (AOSP 13=enabled)")
            throw IOException("The car hotspot is off. Turn it on in the car settings and connect again.")
        }
        val manager: WirelessHotspotManager = if (hotspotMode == WirelessHotspotMode.RELAY) {
            RelayClientManager(
                context = appContext,
                ssid = config.manualHotspotSsid
                    ?: throw IOException("Relay SSID is not configured"),
                passphrase = config.manualHotspotPassphrase.orEmpty(),
                onDiagnostic = ::debugLog,
            )
        } else {
            ManualHotspotManager(
                context = appContext,
                ssid = config.manualHotspotSsid
                    ?: throw IOException("Manual hotspot SSID is not configured"),
                passphrase = config.manualHotspotPassphrase.orEmpty(),
                band = config.manualHotspotBand,
                channel = config.manualHotspotChannel,
                security = config.manualHotspotSecurity,
                preferIpv4 = hotspotMode == WirelessHotspotMode.MANUAL_IPV4,
                onDiagnostic = ::debugLog,
            )
        }
        hotspot = manager
        return try {
            val info = manager.start(HOTSPOT_START_TIMEOUT_MILLIS)
            lastHotspotInfo = info
            info
        } catch (failure: Exception) {
            if (hotspot === manager) hotspot = null
            closeBestEffort(hotspotMode.name) { manager.close() }
            if (isStaleWirelessRun(generation)) throw failure
            throw IOException(
                "Could not establish ${hotspotMode.name} hotspot: " +
                    (failure.message ?: failure.javaClass.simpleName),
                failure,
            )
        }
    }

    private fun isStaleWirelessRun(generation: Int): Boolean =
        closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get()

    private fun selectWirelessBluetoothDevice(adapter: BluetoothAdapter): BluetoothDevice {
        val bonded = adapter.bondedDevices.orEmpty()
        config.wirelessBluetoothDeviceAddress?.let { selected ->
            return bonded.firstOrNull { it.address.equals(selected, ignoreCase = true) }
                ?: throw IOException("The selected iPhone is no longer paired. Choose it again in DiPlay.")
        }
        val iPhones = bonded.filter { device ->
            device.name?.contains("iPhone", ignoreCase = true) == true
        }
        val directlyConnectedIPhones = iPhones.filter(::isBluetoothDeviceConnected)
        Log.i(
            IphoneCarPlayConfiguration.TAG,
            "wireless Bluetooth bondedIPhones=${iPhones.size} " +
                "directlyConnected=${directlyConnectedIPhones.size}",
        )
        val connectedIPhones = if (directlyConnectedIPhones.isNotEmpty()) {
            directlyConnectedIPhones
        } else {
            val connectedAddresses = connectedBluetoothDevices(adapter).mapTo(mutableSetOf()) {
                it.address
            }
            iPhones.filter { it.address in connectedAddresses }
        }
        if (connectedIPhones.size == 1) return connectedIPhones.single()
        if (connectedIPhones.size > 1) {
            throw IOException(
                "Multiple connected iPhones found: " +
                    connectedIPhones.joinToString { "${it.name ?: "iPhone"} (${it.address})" },
            )
        }
        if (iPhones.size == 1) return iPhones.single()
        if (iPhones.size > 1) {
            throw IOException(
                "Multiple bonded iPhones found and none is currently connected; " +
                    "connect one iPhone and retry",
            )
        }
        if (bonded.size == 1) return bonded.single()
        throw IOException(
            "No unambiguous bonded iPhone found; pair one iPhone and retry",
        )
    }

    /**
     * M6k6：RFCOMM 建链加固。C8815 实测（20:31 日志，导出档 -203326；注意与车机 B 的
     * 20:33 日志区分——后者是 `iAP2 link ended`，与 `Service discovery failed` 不同病）`Service discovery failed` 可连续
     * 十余次失败（T14 手点 7 次才连上的同族问题），此前每轮重试都用同一种套接字：
     * ①建链前取消适配器在跑的发现——发现扫描与 SDP 建链冲突是老栈的经典坑；
     * ②安全口失败后回落不安全口（API10+；MFi 鉴权在 iAP2 层完成，不依赖 RFCOMM 加密）。
     */
    private fun openWirelessRfcommSocket(device: BluetoothDevice): BluetoothSocket {
        bluetoothAdapter?.takeIf { it.isDiscovering }?.let { adapter ->
            debugLog("wireless RFCOMM canceling discovery before connect")
            adapter.cancelDiscovery()
        }
        var lastFailure: Throwable? = null
        val modes = listOf("secure", "insecure")
        for ((index, mode) in modes.withIndex()) {
            try {
                val uuid = UUID.fromString(IAP2_IPHONE_UUID)
                val socket = if (mode == "secure") {
                    device.createRfcommSocketToServiceRecord(uuid)
                } else {
                    device.createInsecureRfcommSocketToServiceRecord(uuid)
                }.also { bluetoothSocket = it }
                debugLog(
                    "wireless RFCOMM connecting address=${device.address} " +
                        "mode=$mode uuid=$IAP2_IPHONE_UUID",
                )
                connectBluetoothSocket(socket, device.address)
                return socket
            } catch (failure: Throwable) {
                lastFailure = failure
                debugLog(
                    "wireless RFCOMM connect ($mode) failed: " +
                        (failure.message ?: failure.javaClass.simpleName),
                )
                runCatching { bluetoothSocket?.close() }
                if (index < modes.lastIndex) {
                    bluetoothAdapter?.takeIf { it.isDiscovering }?.let { adapter ->
                        debugLog("wireless RFCOMM canceling discovery before fallback")
                        adapter.cancelDiscovery()
                    }
                    runCatching { Thread.sleep(RFCOMM_FALLBACK_DELAY_MILLIS) }
                }
            }
        }
        throw lastFailure ?: IOException("RFCOMM connect failed")
    }

    private fun connectBluetoothSocket(socket: BluetoothSocket, address: String) {
        val result = AtomicReference<Throwable?>()
        val connected = CountDownLatch(1)
        Thread(
            {
                try {
                    socket.connect()
                } catch (error: Throwable) {
                    result.set(error)
                } finally {
                    connected.countDown()
                }
            },
            "wireless-rfcomm-connect",
        ).apply {
            isDaemon = true
            start()
        }
        val completed = try {
            connected.await(RFCOMM_CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            runCatching { socket.close() }
            throw IOException("Interrupted while connecting RFCOMM to $address", error)
        }
        if (!completed) {
            debugLog(
                "wireless RFCOMM connect timed out after " +
                    "${RFCOMM_CONNECT_TIMEOUT_MILLIS}ms address=$address",
            )
            runCatching { socket.close() }
            throw IOException(
                "Timed out after ${RFCOMM_CONNECT_TIMEOUT_MILLIS}ms connecting RFCOMM to $address",
            )
        }
        when (val failure = result.get()) {
            null -> Unit
            is IOException -> throw failure
            else -> throw IOException("Could not connect RFCOMM to $address", failure)
        }
    }

    /**
     * M6k10：bring-up 期间每秒采样关键判据（作者 0.2.11 诊断包结构）。
     * tcpAccepted=0 → iPhone 还没加入热点 Wi-Fi（Wi-Fi 段等待）；≥1 → 已进 RTSP 阶段。
     * active=true 或代次失效时自动停止；teardown 也会强制停止。
     */
    private fun startWirelessSampler(generation: Int) {
        stopWirelessSampler()
        val sampler = Thread(
            {
                var elapsedSeconds = 0L
                while (
                    !closed &&
                    phase == Phase.WIRELESS &&
                    generation == wirelessGeneration.get() &&
                    !wirelessActiveReported.get() &&
                    !wirelessDegradedActive.get()
                ) {
                    debugLog(
                        "wireless sample gen=$generation t=+${elapsedSeconds}s " +
                            "tcpAccepted=${airPlayServer?.acceptedConnectionCount() ?: 0} " +
                            "authenticated=${wirelessAuthenticated.get()} " +
                            "handoffRequested=${wirelessHandoffRequested.get()} " +
                            "tunnelReady=${wirelessTunnelReady.get()} " +
                            "wifiConfigs=${wifiConfigCount()}",
                    )
                    try {
                        Thread.sleep(1_000L)
                    } catch (interrupted: InterruptedException) {
                        return@Thread
                    }
                    elapsedSeconds += 1
                }
            },
            "xcertplay-wireless-sampler",
        )
        sampler.isDaemon = true
        wirelessSamplerThread = sampler
        sampler.start()
    }

    private fun stopWirelessSampler() {
        val sampler = wirelessSamplerThread ?: return
        wirelessSamplerThread = null
        sampler.interrupt()
    }

    /** 会话建立收口：SESSION_ACTIVE 一行讲清耗时（重连总耗时从故障纪元起点起算）。 */
    private fun logWirelessSessionActive(generation: Int, attempt: Int, degraded: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val bringUpMs = wirelessBringUpStartedAtMs.takeIf { it > 0 }?.let { now - it } ?: -1L
        val reconnectTotalMs =
            wirelessRestartEpisodeStartedAtMs.takeIf { it > 0 }?.let { now - it }
        wirelessRestartEpisodeStartedAtMs = 0L
        debugLog(
            "wireless SESSION_ACTIVE gen=$generation attempt=$attempt" +
                "${if (degraded) " (degraded)" else ""} " +
                "bringUpMs=$bringUpMs " +
                "reconnectTotalMs=${reconnectTotalMs ?: "n/a(first-run)"}",
        )
    }

    @Suppress("DEPRECATION")
    private fun wifiConfigCount(): Int = try {
        val wifiManager =
            appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifiManager?.configuredNetworks?.size ?: -1
    } catch (error: Exception) {
        // API16 适配：configuredNetworks 部分厂商 ROM 抛 SecurityException/RuntimeException。
        -1
    }

    private fun closeWirelessStack(keepHotspotForReuse: Boolean = false) {
        wirelessConnectionProof.clear()
        wirelessLinkOpenedAtMs = 0L

        // 断链顺序（M6k8 调整）：**先下线 Bonjour 广报**（NsdManager unregisterService /
        // JmDNS close 发 goodbye），iPhone 立即感知服务消失并清 mDNS 缓存；随后才关 AirPlay
        // 监听与会话。若反序：监听已死而广报记录仍在 TTL 内，iPhone 重连会先撞死端口、
        // 等缓存过期（实测 ~40s）。10-02 八轮实测的约束保留：AirPlay 会话的 TCP FIN 必须先于
        // iAP2 隧道/蓝牙断开，否则 iOS 把蓝牙掉线当瞬时抖动而保持 CarPlay（用户实测：
        // 必须手动关车机热点/蓝牙才退）。每步补文件日志，供下一轮无 adb 的实机日志定位。
        debugLog("wireless teardown step=1/5 unregistering Bonjour advertisement")
        val activeBonjour = bonjour
        bonjour = null
        timedStep("Bonjour") { activeBonjour?.close() }

        val activeServer = airPlayServer
        airPlayServer = null
        debugLog("wireless teardown step=2/5 closing AirPlay server and sessions")
        timedStep("AirPlayServer") { activeServer?.detach() }

        debugLog("wireless teardown step=3/5 releasing iAP2 tunnel handler")
        media.setIapTunnelHandler(null)
        val activeTunnel = wirelessTunnelChannel
        wirelessTunnelChannel = null
        timedStep("iAP2Tunnel") { activeTunnel?.close() }

        debugLog("wireless teardown step=4/5 closing Bluetooth bootstrap transport")
        timedStep("BtBootstrap") { closeBluetoothBootstrapTransport() }

        debugLog("wireless teardown step=5/5 releasing hotspot handle")
        val activeHotspot = hotspot
        hotspot = null
        if (keepHotspotForReuse && activeHotspot != null && retainedHotspot == null) {
            // M6k24（C 方案）：未建流 EOF 的整代重启保留热点附着——车机热点是既有 AP，
            // 重建附着最慢且会打断 iPhone 的 Wi-Fi 连接（它每 5s 重试 TCP 的前提就是还在网上）。
            // 把 (manager, info) 移出栈外暂存，下一轮 startWirelessHotspot 直接复用。
            val info = lastHotspotInfo
            if (info != null) {
                retainedHotspot = activeHotspot to info
                debugLog("wireless teardown hotspot retained for reuse")
            } else {
                timedStep("HotspotHandle") { activeHotspot.close() }
            }
        } else {
            timedStep("HotspotHandle") { activeHotspot?.close() }
        }
        wirelessIdentification = null
        wirelessAirPlayEndpoint = null
        wirelessHandoffRequested.set(false)
        wirelessTunnelReady.set(false)
        wirelessActiveReported.set(false)
        wirelessDegradedActive.set(false)
        wirelessAuthenticated.set(false)
        stopWirelessSampler()
        debugLog("wireless teardown complete")
    }

    // ==================================================================
    // USB 有线（U3）：iPhone 经 USB 直连，USBMUX/lockdown 控制面 + NCM 数据面。
    // 流程对齐上游有线编排：发现 -> USB 权限 -> (0x52 重枚举) -> USBMUX 会话 ->
    // Lockdown 配对 -> carkit TLS 流 -> iAP2 CSM -> NCM/TUN/AirPlay attach ->
    // 0x4300/0x4301 控制环。USB 拔出 = 本地用户断开语义：控制环 EOF 只报
    // ControlEnded 停住，不进无线自动重连族。
    // ==================================================================

    private fun startWiredReceivers() {
        val host = iphoneHost
        if (host == null) {
            debugLog("wired USB host service unavailable on this device")
            return
        }
        permissionCloseable = host.registerPermissionReceiver(::onIphonePermission)
        attachCloseable = host.registerAttachReceiver(::onIphoneAttached)
    }

    private fun closeWiredReceivers() {
        val permission = permissionCloseable
        permissionCloseable = null
        runCatching { permission?.close() }
        val attach = attachCloseable
        attachCloseable = null
        runCatching { attach?.close() }
    }

    private fun startIphone() {
        availabilityPollGeneration.incrementAndGet()
        phase = Phase.IPHONE
        reenumerationAttempts = 0
        onStatus(CarPlayStatus.DiscoveringIphone)
        checkIphoneAvailability()
    }

    private fun checkIphoneAvailability() {
        if (closed || phase != Phase.IPHONE) return
        val host = iphoneHost
        if (host == null) {
            fail(IphoneUsbException.DeviceUnavailable("USB host is unavailable on this device"))
            return
        }
        val device = host.discover().firstOrNull()
        if (device == null) {
            onStatus(CarPlayStatus.WaitingForIphone)
            scheduleAvailabilityPoll(Phase.IPHONE) { checkIphoneAvailability() }
        } else {
            debugLog(
                "wired iPhone discovered vid=0x${device.vendorId.toString(16)} " +
                    "pid=0x${device.productId.toString(16)}",
            )
            availabilityPollGeneration.incrementAndGet()
            requestIphonePermission(device)
        }
    }

    private fun requestIphonePermission(device: UsbDevice) {
        mainHandler.post { doRequestIphonePermission(device) }
    }

    private fun doRequestIphonePermission(device: UsbDevice) {
        if (closed) return
        val host = iphoneHost
        if (host == null) {
            fail(IphoneUsbException.DeviceUnavailable("USB host is unavailable on this device"))
            return
        }
        try {
            when (val request = host.requestPermission(device)) {
                is IphoneUsbHost.PermissionRequest.AlreadyGranted -> {
                    debugLog("wired iPhone USB permission already granted")
                    permissionGrant.set(false)
                    onIphonePermission(IphoneUsbHost.PermissionResult.Granted(request.device))
                }
                is IphoneUsbHost.PermissionRequest.Requested -> {
                    debugLog("wired iPhone USB permission requested")
                    permissionGrant.set(false)
                    onStatus(CarPlayStatus.RequestingIphonePermission)
                    pollIphonePermission(device)
                }
            }
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun onIphonePermission(result: IphoneUsbHost.PermissionResult) {
        when (result) {
            is IphoneUsbHost.PermissionResult.Granted -> {
                // The system broadcast and the polling fallback can both observe the grant.
                if (!permissionGrant.compareAndSet(false, true)) return
                debugLog("wired iPhone USB permission granted")
                permissionPollGeneration += 1
                when (phase) {
                    Phase.REENUMERATION, Phase.IPHONE -> {
                        // <21 上 find 返回 null（UsbConfiguration 是 API 21+），由重枚举 +
                        // NcmFunctionDiscovery.findLegacy 用默认配置兜底。
                        // 1.0.4：API<21 的重枚举判定改走 legacy 探测——此前恒判"无 CarPlay
                        // 配置"导致重发 0x52（iPhone 已切模式会拒绝 0x6）并重试耗尽误报。
                        val configuration = IphoneCarPlayConfiguration.find(result.device)
                        val legacyCarPlayReady = configuration != null ||
                            (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP &&
                                IphoneCarPlayConfiguration.usbMuxInterfaceLegacy(result.device) != null &&
                                NcmFunctionDiscovery.findLegacy(result.device) != null)
                        if (legacyCarPlayReady) {
                            openDataPaths(result.device)
                        } else {
                            // M6k24d 诊断：重枚举后 iPhone 未带 CarPlay 配置时，dump 完整
                            // 描述符（配置/接口/类三元组），确认它到底以什么形态回来。
                            debugLog(
                                "wired re-attached iPhone pid=0x" + result.device.productId.toString(16) +
                                    " topology=" + IphoneCarPlayConfiguration.describeDevice(result.device),
                            )
                            if (reenumerationAttempts < MAXIMUM_REENUMERATION_ATTEMPTS) {
                                beginReenumeration(result.device)
                            } else {
                                fail(
                                    IphoneUsbException.Protocol(
                                        "iPhone did not expose a complete CarPlay USB configuration",
                                    ),
                                )
                            }
                        }
                    }
                    else -> Unit
                }
            }
            is IphoneUsbHost.PermissionResult.Denied -> {
                permissionGrant.set(true)
                onStatus(CarPlayStatus.Failed("iPhone USB permission was denied"))
            }
        }
    }

    /** Some Android builds grant the dialog without delivering the permission broadcast. */
    private fun pollIphonePermission(device: UsbDevice) {
        permissionPollGeneration += 1
        val generation = permissionPollGeneration
        val deadlineNanos = System.nanoTime() + PERMISSION_POLL_TIMEOUT_MILLIS * 1_000_000L
        val check = object : Runnable {
            override fun run() {
                if (closed || generation != permissionPollGeneration) return
                if (usbManager?.hasPermission(device) == true) {
                    onIphonePermission(IphoneUsbHost.PermissionResult.Granted(device))
                    return
                }
                if (System.nanoTime() >= deadlineNanos) {
                    if (permissionGrant.compareAndSet(false, true)) {
                        onStatus(
                            CarPlayStatus.Failed(
                                "iPhone USB permission was not granted; tap Reconnect iPhone to retry",
                            ),
                        )
                    }
                    return
                }
                mainHandler.postDelayed(this, PERMISSION_POLL_INTERVAL_MILLIS)
            }
        }
        mainHandler.postDelayed(check, PERMISSION_POLL_INTERVAL_MILLIS)
    }

    private fun beginReenumeration(device: UsbDevice) {
        phase = Phase.REENUMERATION
        reenumerationAttempts += 1
        onStatus(CarPlayStatus.SelectingConfiguration)
        iphoneHost?.requestCarPlayReenumerationAsync(device, executor) { transition ->
            when (transition) {
                is IphoneUsbHost.TransitionResult.ReenumerationRequested -> {
                    // M6k24d 诊断：0x52 的响应字节决定 iPhone 是否接受 CarPlay 模式切换。
                    debugLog("wired CarPlay vendor request 0x52 response=0x${transition.vendorResponseByte.toString(16)}")
                    onStatus(CarPlayStatus.WaitingForReenumeration)
                    startReenumerationWatchdog()
                }
                is IphoneUsbHost.TransitionResult.Failed -> fail(transition.error)
            }
        }
    }

    /**
     * 1.0.1：重枚举阶段的三重保障（车机实测卡 WaitingForReenumeration 永久无事件）。
     * 1) 15s 看门狗：超时显性失败给可行动提示（原实现会永久卡死无提示无重试）；
     * 2) 2s 轮询兜底：扫系统 USB 设备列表发现 Apple 设备即按重枚举完成处理，
     *    防 ROM 的 ACTION_USB_DEVICE_ATTACHED 广播丢失；
     * 3) 5s 样本日志：记录当前 Apple 设备 vid/pid，下次日志可直接判定 iPhone 是否物理重枚举。
     */
    private fun startReenumerationWatchdog() {
        val generation = reenumerationPollGeneration.incrementAndGet()
        // 轮询兜底 + 样本日志（自续约）
        var polls = 0
        fun pollOnce() {
            if (closed || phase != Phase.REENUMERATION || generation != reenumerationPollGeneration.get()) return
            polls += 1
            val appleDevices = usbManager?.deviceList?.values?.filter {
                IphoneUsbMatcher.appleVendor().matches(it.vendorId, it.productId)
            }.orEmpty()
            if (polls % 2 == 1) {
                debugLog(
                    "reenumerate waiting: appleDevices=" +
                        appleDevices.joinToString(separator = ", ", prefix = "[", postfix = "]") { "vid=0x${it.vendorId.toString(16)} pid=0x${it.productId.toString(16)}" },
                )
            }
            if (appleDevices.isNotEmpty()) {
                debugLog("reenumerate poll: detected Apple device; treating as re-attached")
                // 1.0.19（AC822X API17 实测）：补打重枚举后拓扑——此前只有首 attach 的
                // legacy dump，renum 后进没进 CarPlay 配置、iface2/3 是不是真 NCM 全靠猜。
                debugLog(
                    "reenumerate poll: re-attached topology " +
                        IphoneCarPlayConfiguration.describeDevice(appleDevices.first()),
                )
                onIphoneAttached(appleDevices.first())
                return
            }
            mainHandler.postDelayed(::pollOnce, REENUMERATION_POLL_INTERVAL_MILLIS)
        }
        mainHandler.postDelayed(::pollOnce, REENUMERATION_POLL_INTERVAL_MILLIS)
        // 看门狗
        mainHandler.postDelayed(
            {
                if (closed || phase != Phase.REENUMERATION || generation != reenumerationPollGeneration.get()) {
                    return@postDelayed
                }
                debugLog("reenumerate watchdog: no re-enumeration within ${REENUMERATION_WATCHDOG_MILLIS}ms; failing")
                reenumerationPollGeneration.incrementAndGet()
                fail(
                    IOException(
                        "iPhone did not re-enumerate within ${REENUMERATION_WATCHDOG_MILLIS / 1000}s " +
                            "(cable/port/trust prompt); ask the user to replug",
                    ),
                )
            },
            REENUMERATION_WATCHDOG_MILLIS,
        )
    }

    private fun cancelReenumerationTimers() {
        reenumerationPollGeneration.incrementAndGet()
    }

    private fun onIphoneAttached(device: UsbDevice) {
        when (phase) {
            Phase.IPHONE, Phase.REENUMERATION -> {
                // 1.0.1：离开重枚举阶段（广播或轮询任一路径），停掉看门狗与轮询兜底。
                cancelReenumerationTimers()
                availabilityPollGeneration.incrementAndGet()
                requestIphonePermission(device)
            }
            else -> Unit
        }
    }

    private fun scheduleAvailabilityPoll(expected: Phase, check: () -> Unit) {
        val generation = availabilityPollGeneration.get()
        mainHandler.postDelayed(
            {
                if (
                    !closed &&
                    phase == expected &&
                    generation == availabilityPollGeneration.get()
                ) {
                    check()
                }
            },
            DEVICE_AVAILABILITY_POLL_INTERVAL_MILLIS,
        )
    }

    private fun openDataPaths(device: UsbDevice) {
        // 1.0.20（3e EMUI 图库抢 USB 实测）：授权与开路之间设备可能被图库/USB 模式切换
        // 触发重枚举，授权对象是旧实例 → hasPermission=false / openDevice=null。
        // 开路前重新枚举取当前 Apple 实例；当前实例无权限则补走一次授权流程
        //（回到 IPHONE 相位复用既有 granted→openDataPaths 链，比原地等待更稳）。
        val current = currentAppleDevice()
        val target = current ?: device
        if (current != null && current.deviceId != device.deviceId) {
            debugLog(
                "wired device instance changed before open: old id=" + device.deviceId +
                    " new id=" + current.deviceId,
            )
        }
        if (usbManager?.hasPermission(target) == false) {
            debugLog("wired current instance lacks permission; re-requesting once")
            phase = Phase.IPHONE
            permissionGrant.set(false)
            requestIphonePermission(target)
            return
        }
        phase = Phase.DATAPATHS
        debugLog("wired opening iPhone USB data paths")
        onStatus(CarPlayStatus.SelectingConfiguration)
        onStatus(CarPlayStatus.OpeningDataPaths)
        iphoneHost?.openIap2UsbSessionAsync(target, executor) { result ->
            when (result) {
                is IphoneUsbHost.Iap2SessionResult.Connected -> {
                    try {
                        val ncm = openNcm(target)
                        runWiredStack(result.session, ncm)
                    } catch (error: Throwable) {
                        result.session.close()
                        fail(error)
                    }
                }
                is IphoneUsbHost.Iap2SessionResult.Failed -> fail(result.error)
            }
        }
    }

    /** 1.0.20：重新枚举当前在位的 Apple 设备实例（图库/USB 模式切换会造成实例更替）。 */
    private fun currentAppleDevice(): UsbDevice? {
        val manager = usbManager ?: return null
        return try {
            manager.deviceList.values.firstOrNull { it.vendorId == 0x05ac }
        } catch (error: Throwable) {
            null
        }
    }

    private fun openNcm(device: UsbDevice): NcmUsbBridge {
        // 1.0.19（AC822X API17 实测）：NCM 数据接口 claim 失败原为一次即终态；现比照
        // 1.0.2 usbmux 段做整连接重试（关连接→重开→重发现→再 claim），耗尽才失败。
        var target = device
        var lastError: IphoneUsbException? = null
        repeat(2) { attempt ->
            if (attempt > 0) {
                // 1.0.20：重试前重新枚举——图库/USB 模式切换重枚举后旧实例 openDevice 恒 null。
                val current = currentAppleDevice()
                if (current != null && current.deviceId != target.deviceId) {
                    debugLog("ncm retry switching to current device instance id=" + current.deviceId)
                    target = current
                }
                debugLog("ncm open retry #$attempt after claim failure")
                Thread.sleep(500L)
            }
            try {
                return openNcmOnce(target, attempt)
            } catch (error: IphoneUsbException) {
                lastError = error
            }
        }
        throw lastError ?: IphoneUsbException.DeviceUnavailable("Could not open the iPhone NCM connection")
    }

    private fun openNcmOnce(device: UsbDevice, attempt: Int): NcmUsbBridge {
        // 调度器：>=21 且拿到多配置时走 find(configuration)，否则 findLegacy 扫默认配置。
        val configuration = IphoneCarPlayConfiguration.find(device)
        val function = NcmFunctionDiscovery.find(device, configuration)
            ?: throw IphoneUsbException.Protocol("iPhone configuration does not expose an NCM function")
        debugLog(
            // getAlternateSetting 是 API 24+（at8317 实测 API17 崩），诊断行低版本打 0。
            // 1.0.19：补 control/data 接口类——真 NCM 应为 control=2(CDC) data=10(CDC-Data)，
            // 用于识别 ROM 拓扑误配。
            "ncm attempt#$attempt config=${configuration?.id} " +
                "control=${function.control.id}/${if (Build.VERSION.SDK_INT >= 24) function.control.alternateSetting else 0}" +
                " class=${function.control.interfaceClass}" +
                " data=${function.data.id}/${if (Build.VERSION.SDK_INT >= 24) function.data.alternateSetting else 0}" +
                " class=${function.data.interfaceClass}" +
                " status=${function.statusIn?.address?.let { "0x${it.toString(16)}" } ?: "none"}" +
                " in=0x${function.bulkIn.address.toString(16)} out=0x${function.bulkOut.address.toString(16)}",
        )
        val connection = usbManager?.openDevice(device)
            ?: throw IphoneUsbException.DeviceUnavailable("Could not open the iPhone NCM connection")
        return NcmUsbBridge.open(connection, function)
    }

    private fun runWiredStack(usbSession: Iap2UsbSession, ncm: NcmUsbBridge) {
        phase = Phase.CONTROL
        var ncmOwnedLocally = true
        try {
            if (closed) return
            val mux = Iap2UsbMuxHost.open(usbSession)
            wiredMux = mux
            debugLog("wired USBMUX host opened")
            onStatus(CarPlayStatus.Pairing)
            val pairingClient = LockdownPairingClient(mux)
            val savedPairRecord = loadPairRecord()
            var pairRecord = savedPairRecord ?: pairNewRecord(pairingClient)
            debugLog(
                if (savedPairRecord != null) {
                    "wired using saved Lockdown pair record"
                } else {
                    "wired created a new Lockdown pair record"
                },
            )
            onStatus(CarPlayStatus.ConnectingControl)
            val carKitClient = LockdownCarKitClient(mux)
            val carkit = try {
                carKitClient.open(pairRecord, config.label)
            } catch (error: Throwable) {
                val rejection = rejectedPairRecordError(error)
                if (savedPairRecord == null || rejection == null) throw error
                debugLog("saved Lockdown pair record rejected by Lockdown error=$rejection; clearing and pairing again")
                clearPairRecord()
                pairRecord = pairNewRecord(pairingClient)
                carKitClient.open(pairRecord, config.label)
            }
            debugLog("wired com.apple.carkit.service stream opened")
            val csm = Iap2Session.open(
                carkit,
                traceContext = "wired",
                onTrace = ::debugLog,
            )
            wiredCsm = csm
            debugLog("wired iAP2 CSM channel opened")

            val ncmHostMac = ncm.hostMac ?: config.hostMac
            debugLog("ncm using hostMac=${ncmHostMac.macString()}")
            val bridge = attachWiredNetwork(ncm, ncmHostMac)
                ?: throw IphoneUsbException.DeviceUnavailable("Could not attach the NCM/AirPlay transport")
            wiredBridge = bridge
            ncmOwnedLocally = false
            debugLog("wired NCM/AirPlay transport attached")
            if (closed) return

            val mfi = mfiSession?.client
                ?: throw IphoneUsbException.DeviceUnavailable("MFi coprocessor client is unavailable")
            val endpoint = Iap2WiredCarPlayEndpoint(
                ipv6Addresses = listOf(config.linkLocal),
                airPlayPort = airPlayConfig.port,
                publicKey = identity.publicKeyHex,
                sourceVersion = airPlayConfig.sourceVersion,
                deviceIdentifier = ncmHostMac.macString(),
            )
            onStatus(CarPlayStatus.RunningControl)
            debugLog("wired iAP2 control starting")
            val result = Iap2WiredControlClient(csm, Iap2MfiAuthenticationClient(mfi)).run(
                identification = config.identification,
                endpoint = endpoint,
                availableCurrentMilliAmps = config.availableCurrentMilliAmps,
                timeoutMillis = Iap2WiredControlClient.NO_TIMEOUT_MILLIS,
                locationProvider = locationProvider,
                onIncoming = ::onRouteFrame,
                onProgress = { message -> debugLog("wired $message") },
            )
            debugLog("wired control ended terminal=${result.terminal} stage=${result.stage}")
            // 1.0.16（方案四①）：只有终态（用户已断开/controller 已关）才做网络恢复；自动重试路径不动 Wi-Fi。
            closeWiredStack(restoreNetwork = closed)
            // 1.0.10：控制环结束 ≠ 一定是用户断开。controller 未关闭（用户没点断开、服务没停）
            // 即 iPhone 侧主动断链（FE-6 实测：iPhone 停发视频后 connection closed，App 躺平 3 分半
            // 等手动）。此时走自动重试：4s×attempt 封顶 15s，额度 5 次；第 3 次起提示用户干预。
            // 全部 API16 安全原语（Handler.postDelayed + AtomicInteger），与无线重连链同款节奏。
            if (closed) {
                onStatus(CarPlayStatus.ControlEnded)
                return
            }
            val attempt = wiredRetryCount.incrementAndGet()
            if (attempt > MAX_WIRED_RETRIES) {
                debugLog("wired auto-retry exhausted after $MAX_WIRED_RETRIES attempts")
                onStatus(CarPlayStatus.ControlEnded)
                return
            }
            val delayMs = minOf(WIRED_RETRY_DELAY_BASE_MILLIS * attempt, WIRED_RETRY_DELAY_MAX_MILLIS)
            if (attempt >= 3) {
                onStatus(
                    CarPlayStatus.RecoveryHint(
                        "iPhone 已断开连接，正在自动重连；若持续失败，请关开 iPhone 蓝牙或重新插拔数据线",
                    ),
                )
            }
            debugLog("wired peer closed; auto-retry $attempt/$MAX_WIRED_RETRIES backoff=${delayMs}ms")
            mainHandler.postDelayed({
                if (closed) return@postDelayed
                startIphone()
            }, delayMs)
        } catch (error: Throwable) {
            debugLog("wired bring-up failed", error)
            closeWiredStack()
            if (error is Error) throw error
            fail(error)
        } finally {
            if (ncmOwnedLocally) {
                closeBestEffort("wired NCM") { ncm.close() }
            }
        }
    }

    /**
     * 有线数据面 attach（对应上游 attachVpn，Lite 裁剪为三步）：
     * 1) WiredVpnService 仅承担 establish()（需要系统 VPN 授权），fd 交付给桥；
     * 2) AirPlayTcpServer 绑 NCM 链路本地地址（与无线共用进程内 server，互斥使用）；
     * 3) Ipv6NcmBridge 双向泵（桥 owns ncm+tun，close 时级联释放）。
     */
    private fun attachWiredNetwork(ncm: NcmUsbBridge, hostMac: ByteArray): Ipv6NcmBridge? {
        onStatus(CarPlayStatus.AttachingNetwork)
        WiredVpnService.request(config.linkLocal)
        try {
            appContext.startService(
                Intent(appContext, WiredVpnService::class.java)
                    .putExtra(WiredVpnService.EXTRA_LINK_LOCAL, config.linkLocal),
            )
        } catch (error: Throwable) {
            // 4.x 上 startService 一般可用；失败则 awaitTun 超时返回 null 走失败路径。
            debugLog("wired VPN service start failed: ${error.message}")
        }
        val tun = WiredVpnService.awaitTun(WIRED_VPN_TUN_TIMEOUT_MILLIS)
        if (tun == null) {
            debugLog("wired VPN TUN unavailable")
            ncm.close()
            return null
        }
        val server = AirPlayTcpServer()
        when (val result = server.attach(
            bindAddress = InetAddress.getByName(config.linkLocal),
            config = airPlayConfig,
            identity = identity,
            pairings = pairings,
            mfi = mfiSession?.client,
            listener = sessionListener,
            media = media,
        )) {
            AirPlayTcpServer.AttachResult.Started -> airPlayServer = server
            AirPlayTcpServer.AttachResult.AlreadyStarted -> {
                debugLog("wired AirPlay transport is already attached")
                runCatching { tun.close() }
                ncm.close()
                return null
            }
            is AirPlayTcpServer.AttachResult.Failed -> {
                debugLog("wired AirPlay attach failed: ${result.message}")
                runCatching { tun.close() }
                ncm.close()
                return null
            }
        }
        val bridge = Ipv6NcmBridge(ncm, tun, hostMac) { error ->
            debugLog("wired NCM bridge error: ${error.message ?: error.javaClass.simpleName}")
        }
        bridge.start()
        return bridge
    }

    private fun pairNewRecord(client: LockdownPairingClient): LockdownPairRecord =
        client.pair(
            label = config.label,
            hostId = hostId,
            systemBuid = systemBuid,
            totalTimeoutMillis = PAIR_TIMEOUT_MILLIS,
            isCancelled = { closed },
        ).pairRecord.also(savePairRecord)

    private fun rejectedPairRecordError(error: Throwable): String? {
        var cause: Throwable? = error
        while (cause != null) {
            val message = cause.message.orEmpty()
            if (message.contains("InvalidPairRecord", ignoreCase = true)) return "InvalidPairRecord"
            if (message.contains("InvalidHostID", ignoreCase = true)) return "InvalidHostID"
            cause = cause.cause
        }
        return null
    }

    /** 有线栈 teardown：先断 AirPlay（发 FIN，让 iPhone 先撤会话），再关控制面与数据面。
     * restoreNetwork=false 用于自动重连路径（FE-6 实测 iPhone 侧断链会周期性重建有线栈），
     * 此时不动车机 Wi-Fi，避免重连间隙反复断网。 */
    private fun closeWiredStack(restoreNetwork: Boolean = true) {
        debugLog("wired teardown starting")
        val activeServer = airPlayServer
        airPlayServer = null
        timedStep("WiredAirPlayServer") { activeServer?.detach() }

        val activeCsm = wiredCsm
        wiredCsm = null
        timedStep("WiredCsm") { activeCsm?.close() }

        val activeMux = wiredMux
        wiredMux = null
        timedStep("WiredUsbMux") { activeMux?.close() }

        val activeBridge = wiredBridge
        wiredBridge = null
        timedStep("WiredNcmBridge") { activeBridge?.close() }

        // bridge 未建立而中途失败的残留兜底（幂等）。
        timedStep("WiredVpnTun") { WiredVpnService.shutdownTun() }
        // M6k24m（1.0.0-demo）：USB 断开必须显式停掉 VPN 服务——EMUI 上仅关 fd 后
        // 系统 VPN 状态可能残留，用户侧表现为断开后流量仍走死代理，Gitee 上传失败。
        try {
            appContext.stopService(Intent(appContext, WiredVpnService::class.java))
        } catch (error: Throwable) {
            debugLog("wired VPN service stop failed: ${error.message}")
        }
        // 1.0.16（方案四①）：USB 断开后「车机网络未恢复」取证 + 自动恢复——终态断开
        // （用户断开/失败/关闭）时探测 DNS，失败则把 Wi-Fi 关→1.2s→开 强制 ConnectivityService
        // 重评网络（fd 已关但系统未切回的 VPN 残留兜底）。自动重连路径（restoreNetwork=false）
        // 完全跳过探测与恢复，不拖慢重连节奏。
        if (restoreNetwork) {
            val dnsReachable = try {
                java.net.InetAddress.getByName("gitee.com")
                debugLog("wired post-teardown network: dns=gitee.com OK")
                true
            } catch (dnsError: Throwable) {
                debugLog("wired post-teardown network: dns=gitee.com FAIL ${dnsError.javaClass.simpleName}")
                false
            }
            try {
                val cm = appContext.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                    as? android.net.ConnectivityManager
                val active = cm?.activeNetworkInfo
                debugLog(
                    "wired post-teardown network: active=" +
                        (active?.let { "${it.typeName}/${it.detailedState}" } ?: "none"),
                )
            } catch (error: Throwable) {
                debugLog("wired post-teardown network probe failed: ${error.message}")
            }
            if (!dnsReachable) {
                val wifi = appContext.getSystemService(android.content.Context.WIFI_SERVICE)
                    as? android.net.wifi.WifiManager
                if (wifi != null) {
                    // 1.0.17（C40DB 实测）：部分车机 ROM 的 isWifiEnabled 会误报 false
                    // （active=WIFI/CONNECTED 但 WifiManager 报关），原来这种情况兜底被跳过。
                    // 现在统一处理：开着 → 关→1.2s→开 重评；报关 → 直接 enable 尝试拉起。
                    val wasEnabled = runCatching { wifi.isWifiEnabled }.getOrDefault(false)
                    if (wasEnabled) {
                        debugLog("wired post-teardown network: dns FAIL → Wi-Fi 重开强制重评")
                        runCatching { wifi.isWifiEnabled = false }
                            .onFailure { debugLog("wired network restore: Wi-Fi off failed ${it.message}") }
                        try {
                            Thread.sleep(1_200)
                        } catch (_: InterruptedException) {
                        }
                    } else {
                        debugLog("wired post-teardown network: dns FAIL + isWifiEnabled=false（疑似 ROM 误报）→ 直接 enable")
                    }
                    runCatching { wifi.isWifiEnabled = true }
                        .onFailure { debugLog("wired network restore: Wi-Fi on failed ${it.message}") }
                    try {
                        java.net.InetAddress.getByName("gitee.com")
                        debugLog("wired post-teardown network: dns after Wi-Fi re-toggle OK")
                    } catch (retryError: Throwable) {
                        debugLog(
                            "wired post-teardown network: dns after Wi-Fi re-toggle FAIL " +
                                retryError.javaClass.simpleName,
                        )
                    }
                }
            }
        }
        debugLog("wired teardown complete")
    }

    private fun ByteArray.macString(): String =
        joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun isBluetoothDeviceConnected(device: BluetoothDevice): Boolean = try {
        val method = BluetoothDevice::class.java.getMethod("isConnected")
        method.invoke(device) as? Boolean == true
    } catch (error: RuntimeException) {
        Log.w(IphoneCarPlayConfiguration.TAG, "Could not read Bluetooth connection state", error)
        false
    } catch (error: Exception) {
        // API16 适配：ReflectiveOperationException 是 API 19+，统一按 Exception 捕获。
        false
    }

    private fun connectedBluetoothDevices(adapter: BluetoothAdapter): Set<BluetoothDevice> =
        buildSet {
            addAll(connectedBluetoothDevices(adapter, BluetoothProfile.HEADSET, BluetoothHeadset::class.java))
            addAll(connectedBluetoothDevices(adapter, BluetoothProfile.A2DP, BluetoothA2dp::class.java))
        }

    private fun <T : BluetoothProfile> connectedBluetoothDevices(
        adapter: BluetoothAdapter,
        profile: Int,
        profileClass: Class<T>,
    ): Set<BluetoothDevice> {
        val latch = CountDownLatch(1)
        val devices = java.util.Collections.synchronizedSet(mutableSetOf<BluetoothDevice>())
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile) {
                try {
                    if (profileClass.isInstance(proxy)) {
                        devices.addAll(proxy.connectedDevices.orEmpty())
                    }
                } catch (error: SecurityException) {
                    Log.w(IphoneCarPlayConfiguration.TAG, "Could not read connected Bluetooth devices", error)
                } finally {
                    adapter.closeProfileProxy(profileId, proxy)
                    latch.countDown()
                }
            }

            override fun onServiceDisconnected(profileId: Int) {
                latch.countDown()
            }
        }
        if (!adapter.getProfileProxy(appContext, listener, profile)) return emptySet()
        if (!latch.await(3, TimeUnit.SECONDS)) {
            Log.w(IphoneCarPlayConfiguration.TAG, "Timed out reading Bluetooth profile $profile")
        }
        return synchronized(devices) { devices.toSet() }
    }

    @Suppress("DEPRECATION")
    private fun accessoryBluetoothMac(adapter: BluetoothAdapter): String {
        val address = try {
            adapter.address
        } catch (_: SecurityException) {
            null
        }
        val settingsAddress = try {
            Settings.Secure.getString(appContext.contentResolver, "bluetooth_address")
        } catch (_: SecurityException) {
            null
        }
        val live = listOfNotNull(address, settingsAddress).firstOrNull {
            BLUETOOTH_ADDRESS.matches(it) &&
                !it.equals(ADAPTER_ADDRESS_PLACEHOLDER, ignoreCase = true)
        }
        // M6k24（D' 方案）配件身份稳定：本机蓝牙地址一旦取到就进程级缓存。
        // 8815 实测（11:52:44）：僵尸恢复 disable/enable 适配器窗口内 address 取不到，
        // 回落成 deviceId（96:…:89）——iPhone 看到同一热点下配件蓝牙身份漂移，pair-verify 后弃连。
        // 修复：取不到时沿用上一次成功值，绝不在代际间漂移。
        if (live != null) {
            cachedLocalBtMac = live
            return live
        }
        return cachedLocalBtMac ?: airPlayConfig.btMac
    }

    private fun hostAddressText(address: InetAddress): String {
        val text = address.hostAddress?.substringBefore('%')
        if (text.isNullOrBlank()) {
            throw IOException("Wireless hotspot host address is unavailable")
        }
        return text
    }

    private fun closeBestEffort(name: String, close: () -> Unit) {
        try {
            close()
        } catch (error: Throwable) {
            debugLog("$name teardown failed", error)
        }
    }

    /**
     * M6k9：teardown 逐资源打点。每条独立 try，失败只记 completed=false、不中断其余步骤
     * （下代 bring-up 自会重建）；耗时用于判断是哪一步拖慢重连。资源为 null 时按 completed=true
     * 记一条（表示「本代未持有，无需拆」），保证 5 步每代都有完整痕迹。
     */
    private inline fun timedStep(name: String, close: () -> Unit) {
        val startedNs = System.nanoTime()
        var ok = true
        try {
            close()
        } catch (error: Throwable) {
            ok = false
            debugLog("$name teardown failed", error)
        }
        debugLog(
            "teardown resource=$name completed=$ok " +
                "elapsedMs=${(System.nanoTime() - startedNs) / 1_000_000}",
        )
    }

    private fun controlLoopTimeoutMillis(): Long = when {
        config.locationReportingEnabled -> LOCATION_CONTROL_LOOP_TIMEOUT_MILLIS
        else -> CONTROL_LOOP_TIMEOUT_MILLIS
    }

    private fun closeMfiSession() {
        val session = mfiSession
        mfiSession = null
        if (session != null) {
            executor.execute {
                try {
                    session.close()
                } catch (_: Exception) {
                    // Best effort.
                }
            }
        }
    }

    private fun isBluetoothHandoffCommand(type: String): Boolean =
        type.equals("disableBluetooth", ignoreCase = true) ||
            type.equals("disable-bluetooth", ignoreCase = true)

    private fun fail(error: Throwable) {
        if (closed) return
        // M6k11：重连链终结后清掉僵尸模式，防止污染用户下一次手动重连的退避策略。
        wirelessZombieMode = false
        lastWirelessFailureClass = null
        onStatus(CarPlayStatus.Failed(error.message ?: error.javaClass.simpleName))
    }

    private fun debugLog(message: String) {
        Log.i(IphoneCarPlayConfiguration.TAG, message)
        try {
            uiListener?.onDebugLog(message)
        } catch (error: Exception) {
            Log.w(IphoneCarPlayConfiguration.TAG, "debug log callback failed", error)
        }
    }

    private fun debugLog(message: String, error: Throwable) {
        Log.w(IphoneCarPlayConfiguration.TAG, message, error)
        try {
            // 文件日志只收单行 UI 回调：把栈顶几帧拼进来，远程排障不再盲猜 NPE 位置。
            val topFrames = error.stackTrace
                .take(4)
                .joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            uiListener?.onDebugLog(
                "$message: ${error.javaClass.simpleName}: ${error.message ?: "-"} [$topFrames]",
            )
        } catch (callbackError: Exception) {
            Log.w(IphoneCarPlayConfiguration.TAG, "debug log callback failed", callbackError)
        }
    }

    private fun onStatus(status: CarPlayStatus) {
        if (closed) return
        mainHandler.post {
            if (!closed && status != lastReportedStatus) {
                lastReportedStatus = status
                uiListener?.onDebugLog(status.debugLogMessage())
                uiStatusReporter?.invoke(status)
            }
        }
    }

    private fun CarPlayStatus.debugLogMessage(): String = when (this) {
        CarPlayStatus.DiscoveringMfi ->
            "STEP mfi/start: loading the local offline MFi identity"
        CarPlayStatus.MfiReady ->
            "STEP mfi/ready: MFi authentication provider is ready"
        CarPlayStatus.StartingHotspot ->
            "STEP wifi/ap: attaching to the car's own hotspot"
        is CarPlayStatus.HotspotReady ->
            "STEP wifi/ap-ready: backend=$backend ssid=$ssid band=$band " +
                "channel=$channel bssid=$bssid address=$address"
        CarPlayStatus.WaitingForPairedIphone ->
            "STEP bt/select: waiting for a paired or connected iPhone"
        CarPlayStatus.ConnectingBluetooth ->
            "STEP bt/rfcomm: connecting to the iPhone iAP2 RFCOMM service"
        CarPlayStatus.RunningWireless ->
            "STEP iap2/wireless: Bluetooth control loop running"
        CarPlayStatus.WirelessActive ->
            // 该状态同时被“真交接完成”和“handoff degraded（无 type-130，蓝牙保持）”两条路径使用。
            // 原文案宣称 tunnel ready / 蓝牙已释放，在 degraded 路径上与事实不符（T25 实测：
            // 之后仍有 credential resend 走蓝牙），会误导排查，故改为中性描述。
            "STEP wireless/active: wireless CarPlay session active"
        CarPlayStatus.AttachingNetwork ->
            "STEP network/attach: starting the AirPlay TCP listener"
        CarPlayStatus.DiscoveringIphone ->
            "STEP usb/discover: discovering a connected iPhone"
        CarPlayStatus.WaitingForIphone ->
            "STEP usb/wait: waiting for an iPhone to be plugged in"
        CarPlayStatus.RequestingIphonePermission ->
            "STEP usb/permission: requesting USB permission for the iPhone"
        CarPlayStatus.WaitingForReenumeration ->
            "STEP usb/reenumerate: waiting for the iPhone to re-enumerate"
        CarPlayStatus.SelectingConfiguration ->
            "STEP usb/config: selecting the CarPlay USB configuration"
        CarPlayStatus.OpeningDataPaths ->
            "STEP usb/open: opening the USBMUX and NCM data paths"
        CarPlayStatus.Pairing ->
            "STEP usb/pair: performing Lockdown pairing with the iPhone"
        CarPlayStatus.ConnectingControl ->
            "STEP usb/carkit: opening com.apple.carkit.service over lockdown"
        CarPlayStatus.RunningControl ->
            "STEP iap2/wired: wired control loop running"
        CarPlayStatus.ControlEnded ->
            "STEP control/end: the control window ended"
        is CarPlayStatus.Failed ->
            "ERROR $message"
        is CarPlayStatus.RecoveryHint ->
            "HINT $message"
    }

    /** M6k：排队中的一条触摸事件。[sticky] 标记它是否代表触点状态变化（不可被 MOVE 覆盖）。 */
    private class PendingTouch(val contacts: List<AirPlayContact>, val sticky: Boolean)

    companion object {
        private const val IAP2_IPHONE_UUID = "00000000-deca-fade-deca-deafdecacafe"
        // M6k24（D' 方案）：本机蓝牙地址进程级缓存（accessoryBluetoothMac 写入/消费）。
        @Volatile private var cachedLocalBtMac: String? = null
        // —— USB 有线（U3）——
        private const val PAIR_TIMEOUT_MILLIS = 5 * 60_000L
        private const val PERMISSION_POLL_INTERVAL_MILLIS = 500L
        private const val PERMISSION_POLL_TIMEOUT_MILLIS = 120_000L
        private const val DEVICE_AVAILABILITY_POLL_INTERVAL_MILLIS = 2_000L
        private const val MAXIMUM_REENUMERATION_ATTEMPTS = 2
        private const val WIRED_VPN_TUN_TIMEOUT_MILLIS = 10_000L
        private const val HOTSPOT_START_TIMEOUT_MILLIS = 60_000L
        private const val CONTROL_LOOP_TIMEOUT_MILLIS = 5 * 60_000L
        private const val LOCATION_CONTROL_LOOP_TIMEOUT_MILLIS = 24 * 60 * 60 * 1_000L
        private const val WIRELESS_HANDOFF_TIMEOUT_MILLIS = 45_000L
        // M6k20：first-TCP 看门狗超时。比 handoff 45s 短——iPhone 若 30s 都没连上热点 TCP，
        // 大概率是陈旧 mDNS 缓存（实测过期约 40s）或没入网，早失败早重连（4s 退避重试
        // 恰好覆盖缓存过期窗口）。
        private const val FIRST_TCP_TIMEOUT_MILLIS = 30_000L

        // 1.0.1：重枚举阶段看门狗（15s 显性失败）与轮询兜底（2s）/样本日志节拍（4s=2次一打）。
        private const val REENUMERATION_WATCHDOG_MILLIS = 15_000L
        private const val REENUMERATION_POLL_INTERVAL_MILLIS = 2_000L
        // M6k23：未建流阶段控制流 peer EOF 触发整代重启的阈值（连续次数）。
        private const val PRE_STREAM_EOF_RESTART_THRESHOLD = 2
        // M6h：链路掉线后的自动重连（次数 + 间隔）。
        // M6k9：额度 3 → 5，间隔由固定 5s 改为指数退避（4s×attempt，封顶 15s）；
        // 重建会话成功（WirelessActive / degraded）即 attempt 归零，避免陈旧计数放大退避。
        private const val MAX_WIRELESS_RETRIES = 5
        private const val WIRELESS_RETRY_DELAY_BASE_MILLIS = 4_000L
        private const val WIRELESS_RETRY_DELAY_MAX_MILLIS = 15_000L
        // 1.0.10：wired peer 断链自动重试（与无线链同款退避节奏，API16 安全原语）。
        private const val MAX_WIRED_RETRIES = 5
        private const val WIRED_RETRY_DELAY_BASE_MILLIS = 4_000L
        private const val WIRED_RETRY_DELAY_MAX_MILLIS = 15_000L
        // M6k11：CSM 打开后 <500ms 被 iPhone 关流 → IPHONE_ZOMBIE（重试治不了）。
        // 僵尸策略：快试 2 次（4s）→ 第 3 次起长退避 30s/60s 并提示用户关开 iPhone 蓝牙；
        // 第 3 次重试前顺带 reset 本机蓝牙适配器（清车机侧 RFCOMM 服务状态）。
        private const val ZOMBIE_WINDOW_MILLIS = 500L
        private const val ZOMBIE_FAST_RETRY_MILLIS = 4_000L
        private const val ZOMBIE_LONG_RETRY_1_MILLIS = 30_000L
        private const val ZOMBIE_LONG_RETRY_2_MILLIS = 60_000L
        // M6k：触摸队列上限（超过则丢最旧 MOVE）与计数日志间隔。
        private const val MAX_PENDING_TOUCH = 8
        private const val TOUCH_STATS_INTERVAL_NS = 5_000_000_000L
        private val RETRYABLE_WIRELESS_FAILURES = listOf(
            "connection abort",
            "iap2 link ended",
            "connection reset",
            "broken pipe",
            "connection refused",
            "service discovery failed",
            "eof",
            "closed before tunnel iap2 ready",
            // M6k20：first-TCP 超时归入可重试族（陈旧 mDNS 缓存场景靠重连自愈）。
            "first tcp timeout",
            // 1.0.13：handoff（画面握手）超时同归可重试族——MX6DQ 实测 TCP 已通、A/V 未出帧，
            // 弹「画面握手未完成」终态躺平；换新一代重连实测可自愈（重试 8 次后 gen=3 成功）。
            "handoff timed out",
        )
        private const val RFCOMM_CONNECT_TIMEOUT_MILLIS = 15_000L
        private const val RFCOMM_FALLBACK_DELAY_MILLIS = 150L
        private const val EXECUTOR_CLOSE_TIMEOUT_MILLIS = 2_000L
        private const val ADAPTER_ADDRESS_PLACEHOLDER = "02:00:00:00:00:00"
        private val BLUETOOTH_ADDRESS = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
    }
}
