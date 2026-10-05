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
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import com.shilapi.xcertplay.network.ManualHotspotManager
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.network.WirelessHotspotManager
import com.shilapi.xcertplay.network.diagnosticSummary
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.transport.BluetoothRfcommDuplexStream
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.Iap2WirelessCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WirelessControlClient
import com.shilapi.xcertplay.transport.Iap2WirelessControlTerminal
import com.shilapi.xcertplay.transport.Iap2WirelessIdentification
import com.shilapi.xcertplay.transport.IphoneCarPlayConfiguration
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
    data class Failed(val message: String) : CarPlayStatus()
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
    context: Context,
    private val config: CarPlayRuntimeConfig,
    private val airPlayConfig: AirPlayConfig,
    private val identity: AirPlayIdentity,
    private val pairings: PairingStore,
    listener: AirPlaySessionListener,
    private val media: AirPlayMediaHandler,
    reportStatus: (CarPlayStatus) -> Unit,
    private val locationProvider: Iap2LocationProvider? = null,
) : Closeable {
    init {
        require(!config.locationReportingEnabled || locationProvider != null) {
            "A location provider is required when location reporting is enabled"
        }
        BydNavigationOutputs.start(context.applicationContext)
    }

    private enum class Phase { IDLE, MFI, WIRELESS }

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
    private val wirelessGeneration = AtomicInteger(0)
    private val wirelessConnectionProof = WirelessConnectionProof<AirPlaySession>()
    private var lastReportedStatus: CarPlayStatus? = null
    private val teardownComplete = CountDownLatch(1)

    private val sessionListener = object : AirPlaySessionListener {
        override fun onSessionActive(session: AirPlaySession) {
            if (activeSession !== session) BydNavigationOutputs.start(appContext)
            activeSession = session
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
        startMfi()
    }

    /** 重新加载本地离线 MFi 证书并重启无线流水线。 */
    fun reconnectMfi() = synchronized(lifecycleLock) {
        if (closed) return
        closeMfiSession()
        startMfi()
    }

    /** 重跑无线流水线（热点 -> 蓝牙 -> AirPlay）。保留原方法名便于上层复用。 */
    fun reconnectIphone() = synchronized(lifecycleLock) {
        if (closed) return
        // M6h：用户手动重连 = 重新给满自动重试额度，否则上一轮用尽的计数会让这次秒失败。
        wirelessRetryCount.set(0)
        if (mfiSession == null) {
            startMfi()
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
        wirelessGeneration.incrementAndGet()
        touchExecutor.shutdownNow()
        tunnelExecutor.shutdownNow()
        Thread(
            {
                try {
                    closeBestEffort("wireless stack") { closeWirelessStack() }
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
                startWireless()
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
        wirelessGeneration.incrementAndGet()
        Thread(
            {
                closeWirelessStack()
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
            closeWirelessStack()
            if (closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get()) {
                return
            }

            val mfi = mfiSession?.client
                ?: throw IOException("MFi coprocessor client is unavailable")
            val hotspotInfo = startWirelessHotspot(generation)
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
    private fun wirelessFailureClass(error: Throwable): String {
        val message = error.message.orEmpty().lowercase()
        return when {
            !isRetryableWirelessFailure(error) -> "OTHER"
            message.contains("abort") -> "CONNECTION_ABORT"
            message.contains("service discovery") || message.contains("refused") -> "SERVICE_DISCOVERY_FAILED"
            message.contains("iap2") || message.contains("tunnel") -> "IAP2_LINK_ENDED"
            message.contains("eof") -> "EOF"
            else -> "SOCKET_EXCEPTION"
        }
    }

    /** M6h：延时后重跑无线流水线；额度用尽才把失败交给 UI。 */
    private fun scheduleWirelessRetry(generation: Int, error: Throwable) {
        val attempt = wirelessRetryCount.incrementAndGet()
        if (attempt > MAX_WIRELESS_RETRIES) {
            debugLog("wireless auto-retry exhausted after $MAX_WIRELESS_RETRIES attempts")
            fail(error)
            return
        }
        val delayMs = minOf(
            WIRELESS_RETRY_DELAY_BASE_MILLIS * attempt,
            WIRELESS_RETRY_DELAY_MAX_MILLIS,
        )
        debugLog(
            "wireless link dropped (${error.message ?: error.javaClass.simpleName}) " +
                "class=${wirelessFailureClass(error)}; " +
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
                sessionListener.onSessionEnded(session)
            }

            override fun onVideoFrameRendered(session: AirPlaySession) {
                if (isStaleWirelessRun(generation) || activeSession !== session) return
                wirelessConnectionProof.rendered(generation, session)
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
        wirelessRetryCount.set(0)
        val generation = wirelessGeneration.get()
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
                            wirelessRetryCount.set(0)
                            debugLog(
                                "handoff degraded: keeping Bluetooth iAP2 + Wi-Fi A/V " +
                                    "(hasRenderedFrame, no type-130 iAP2 tunnel)",
                            )
                            onStatus(CarPlayStatus.WirelessActive)
                            return@Thread
                        }
                        closeWirelessStack()
                        fail(IOException("Wireless CarPlay handoff timed out"))
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

    private fun closeBluetoothBootstrapTransport() {
        val activeStream = bluetoothStream
        bluetoothStream = null
        if (activeStream != null) closeBestEffort("wireless RFCOMM stream") { activeStream.close() }

        val activeSocket = bluetoothSocket
        bluetoothSocket = null
        if (activeSocket != null) closeBestEffort("wireless Bluetooth socket") { activeSocket.close() }
    }

    /** Lite：仅 MANUAL / MANUAL_IPV4（车机自带热点）。 */
    private fun startWirelessHotspot(generation: Int): WirelessHotspotInfo {
        val hotspotMode = config.wirelessHotspotMode
        if (
            hotspotMode.isManualHotspot &&
            CarHotspotStatus.isEnabled(appContext) == false
        ) {
            // OEM 可能魔改 AP 状态值；把原始值打进日志便于诊断 4.1 定制 ROM。
            debugLog("car hotspot AP state=${CarHotspotStatus.rawState(appContext)} (AOSP 13=enabled)")
            throw IOException("The car hotspot is off. Turn it on in the car settings and connect again.")
        }
        val manager: WirelessHotspotManager = ManualHotspotManager(
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
        hotspot = manager
        return try {
            manager.start(HOTSPOT_START_TIMEOUT_MILLIS)
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

    private fun closeWirelessStack() {
        wirelessConnectionProof.clear()

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
        timedStep("HotspotHandle") { activeHotspot?.close() }
        wirelessIdentification = null
        wirelessAirPlayEndpoint = null
        wirelessHandoffRequested.set(false)
        wirelessTunnelReady.set(false)
        wirelessActiveReported.set(false)
        wirelessDegradedActive.set(false)
        debugLog("wireless teardown complete")
    }

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
        return listOfNotNull(address, settingsAddress)
            .firstOrNull {
                BLUETOOTH_ADDRESS.matches(it) &&
                    !it.equals(ADAPTER_ADDRESS_PLACEHOLDER, ignoreCase = true)
            }
            ?: airPlayConfig.btMac
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
            uiListener?.onDebugLog(
                "$message: ${error.message ?: error.javaClass.simpleName}",
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
        CarPlayStatus.ControlEnded ->
            "STEP control/end: the control window ended"
        is CarPlayStatus.Failed ->
            "ERROR $message"
    }

    /** M6k：排队中的一条触摸事件。[sticky] 标记它是否代表触点状态变化（不可被 MOVE 覆盖）。 */
    private class PendingTouch(val contacts: List<AirPlayContact>, val sticky: Boolean)

    companion object {
        private const val IAP2_IPHONE_UUID = "00000000-deca-fade-deca-deafdecacafe"
        private const val HOTSPOT_START_TIMEOUT_MILLIS = 60_000L
        private const val CONTROL_LOOP_TIMEOUT_MILLIS = 5 * 60_000L
        private const val LOCATION_CONTROL_LOOP_TIMEOUT_MILLIS = 24 * 60 * 60 * 1_000L
        private const val WIRELESS_HANDOFF_TIMEOUT_MILLIS = 45_000L
        // M6h：链路掉线后的自动重连（次数 + 间隔）。
        // M6k9：额度 3 → 5，间隔由固定 5s 改为指数退避（4s×attempt，封顶 15s）；
        // 重建会话成功（WirelessActive / degraded）即 attempt 归零，避免陈旧计数放大退避。
        private const val MAX_WIRELESS_RETRIES = 5
        private const val WIRELESS_RETRY_DELAY_BASE_MILLIS = 4_000L
        private const val WIRELESS_RETRY_DELAY_MAX_MILLIS = 15_000L
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
        )
        private const val RFCOMM_CONNECT_TIMEOUT_MILLIS = 15_000L
        private const val RFCOMM_FALLBACK_DELAY_MILLIS = 150L
        private const val EXECUTOR_CLOSE_TIMEOUT_MILLIS = 2_000L
        private const val ADAPTER_ADDRESS_PLACEHOLDER = "02:00:00:00:00:00"
        private val BLUETOOTH_ADDRESS = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
    }
}
