package com.shilapi.xcertplay.usbbt

import android.util.Log
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** vc205/vc206：配对状态机（未配对 / 配对中 / 已配对）。顶层 public：UI 经 UsbBtStandby 读取。 */
enum class PairState { UNPAIRED, PAIRING, PAIRED }

/** vc206：已配对设备快照条目（UI 列表数据源；address 为冒号 MAC，name 可能未知）。 */
class PairedDevice(val address: String, val name: String?)

/** vc207：inquiry 发现的附近设备（UI「已发现设备」行；address 为冒号 MAC，name/rssi 可能未知）。 */
class DiscoveredDevice(
    val address: String,
    val name: String?,
    val rssi: Int?,
    val classOfDevice: Int,
    val lastSeenMillis: Long,
)

/**
 * vc213：配对持久化接口（实现=SharedPreferences，由 UsbBtStandby 注入）。
 * key = BdAddr.key()（12 位 hex 大写）；linkKey 16 字节。
 */
internal interface UsbBtPairingStore {
    fun loadAll(): Map<String, Pair<ByteArray, String?>>
    fun save(addrKey: String, linkKey: ByteArray, name: String?)
    fun saveName(addrKey: String, name: String)
    fun clear()
}

/**
 * vc181（外置 USB 蓝牙 C/D 批）：dongle 建链状态机（精简重写）。
 * 参照上游参考实现 usbbt.UsbBtHost.java（1717 行），按指导书 §4 简化：
 * 只保留「初始化 → 可连接 →（配对/认证/加密）→ SDP 查 RFCOMM 信道 → RFCOMM → iAP2 DLC」
 * 这一条主链路；PeerStatus/发布/扫描列表/重连器/UI 配对确认全部裁掉，
 * SSP 走 Just-Works 自动确认，link key 存内存 map（进程内有效，断电重配对可接受，TODO 持久化）。
 *
 * 线程模型：
 * - driver 事件/ACL 读 daemon → [onHciEvent]/[onAclPacket]；
 * - 命令 daemon 线程逐条同步执行 [commandQueue]（一次一条，天然无需命令 credits 流控）；
 * - ACL 发送经 [aclLock] 队列 + credits（初值 1，初始化完成后取 Read_Buffer_Size）；
 * - 锁顺序固定：l2capLock → aclLock，禁止反向。
 */
internal class UsbBtHost(
    private val identity: UsbBtIdentity,
    private val driver: UsbBtDriver,
    private val log: (String) -> Unit,
) : UsbBtDriver.Listener {
    /** 生命周期：STOPPED 是正常关停，FAILED 是异常（差异写入 [statusText]）。 */
    enum class Phase { IDLE, STARTING, DISCOVERABLE, FAILED, STOPPED }

    /** 初始化单步：超时 3s；required 失败即整体失败，optional 跳过。 */
    private class Step(
        val name: String,
        val opcode: Int,
        val required: Boolean,
        val build: () -> ByteArray,
    )

    private class AuthRetry(val attempts: Int, val nextAt: Long)

    companion object {
        private const val STEP_TIMEOUT_MILLIS = 3000L
        private const val AUTH_RETRY_BASE_MILLIS = 1000L
        private const val AUTH_RETRY_MAX_MILLIS = 10000L
        private const val ACL_CREDITS_MAX = 64
        private const val PAGE_TIMEOUT_MILLIS = 30000L

        /** vc215：主动回连 page 重试（实测 iPhone 首轮 page 不应答 status=4；2s 间隔最多再试 3 次）。 */
        private const val PAGE_RETRY_MAX = 3
        private const val PAGE_RETRY_GAP_MILLIS = 2000L

        /** vc226：iAP2 建链重试（对照 FreeAuto：它 sdp 计数爬到 8、"retry 4 of SDP + RFCOMM" 才成功）。 */
        private const val IAP2_RETRY_MAX = 8
        private const val IAP2_RETRY_GAP_MILLIS = 1500L

        /** vc227：配对恢复后 / 对端断链后自动回连的延迟。 */
        private const val AUTO_RECONNECT_AFTER_RESTORE_MILLIS = 1500L
        private const val AUTO_RECONNECT_AFTER_DROP_MILLIS = 3000L

        /** vc212：弹码确认等待用户的最长窗口（对齐 SSP 30s 惯例）。 */
        private const val PAIR_CONFIRM_TIMEOUT_MILLIS = 30000L

        /** vc209：inquiry 轮间纯监听窗（iPhone 的 inquiry 只在此时隙内才打得中我们）。 */
        private const val INQUIRY_LISTEN_GAP_MILLIS = 6000L

        private const val PSM_SDP = 1
        private const val PSM_RFCOMM = 3

        /**
         * vc205：配对状态进程级快照（dongle 单例），上层「外置蓝牙是否可用」判据的数据源。
         * 未配对成功前外置链路不对外宣告可用。
         * vc206：配对引导——iPhone 蓝牙列表里请选 dongle 广播名（如「DiPlay CarPlay」），
         * 勿选本机系统蓝牙名（iPhone 会优先命中已配对的本机蓝牙，走框架链路而非外置适配器）。
         */
        @Volatile
        var pairStateSnapshot: PairState = PairState.UNPAIRED
            private set

        @Volatile
        var pairedRemoteSnapshot: String? = null
            private set

        /**
         * vc206：link key 提升为进程级（原实例级）——standby 常驻广播每轮换 host 实例，
         * 实例级表会让配对态在链路交接后丢失、UI 回 UNPAIRED 把用户锁回未配对。
         * 语义不变：仍是内存态（TODO：持久化，参照上游 PreferencesLinkKeyStore）。
         */
        private val sharedLinkKeys = ConcurrentHashMap<String, ByteArray>()

        /**
         * vc238：外置蓝牙总闸（常驻广播开关）。false = 静默态：
         * - onTick 强制 WriteScanEnable(2)：inquiry scan 关、page scan 保留（搜不到但可主动回连）；
         * - 不启动 inquiry、不自动 page（armAutoReconnect / page 重试全部跳过）。
         * 进程级：standby 每轮重建 host 仍生效；App 启动时由 MainActivity 按配置写入。
         */
        @Volatile
        var discoverableEnabled: Boolean = true
            private set

        internal fun setDiscoverableEnabled(enabled: Boolean) {
            discoverableEnabled = enabled
        }

        fun isDiscoverableEnabled(): Boolean = discoverableEnabled

        /**
         * vc238：控制器是否正忙（有 ACL 链路 / page 在飞）——总闸切换前由 standby 校验，
         * 忙时不发 HCI 命令，避免把活动连接/配对打断。
         */
        fun isLinkBusy(): Boolean {
            val host = activeHost ?: return false
            return host.hasAclLink || host.isPaging
        }

        /**
         * vc238：总闸切换后立刻重发 Write_Scan_Enable（不等 onTick 的 1s 节拍）。
         * true=已入队；false=当前无就绪实例（初始化未完成等），由 onTick 兜底。
         */
        internal fun applyScanModeNow(): Boolean = activeHost?.pushScanModeNow() == true

        /** vc213：配对持久化仓库（standby 启动时注入；null=仅内存态）。 */
        @Volatile
        internal var pairingStore: UsbBtPairingStore? = null

        /** vc206：已配对对端名称（RemoteNameRequestComplete 记录；UI 已配对列表展示用）。 */
        private val pairedNames = ConcurrentHashMap<String, String>()

        /** vc206：dongle 本机地址快照（冒号 MAC，ReadBdAddr 完成后非空；UI 配对窗展示用）。 */
        @Volatile
        var localAddressSnapshot: String? = null
            private set

        /**
         * vc206：配对模式（可发现）窗口截止时刻（uptime 无关，wall clock）；0=关闭。
         * 默认关闭可发现（init 末步 WriteScanEnable(2)=仅 page scan，已配对设备可回连但不可被发现）；
         * 进入配对模式拉到 3（inquiry+page scan），配对成功/手动取消/超时即回 2。
         */
        @Volatile
        private var pairingModeUntil = 0L

        /** vc206：已配对设备只读快照（不暴露可变 map；UI 列表数据源）。 */
        fun pairedDevicesSnapshot(): List<PairedDevice> = sharedLinkKeys.keys.sorted().map { key ->
            PairedDevice(key.chunked(2).joinToString(":"), pairedNames[key])
        }

        /**
         * vc213：从持久化仓库恢复配对（standby.start 注入 store 后调用，幂等）。
         * 有记录 → 内存 key 表灌回（LinkKeyRequest 可直接应答）+ PairState 直接置 PAIRED
         * + isUsable 解锁主页，**不重新配对**。
         */
        internal fun restorePairing() {
            val store = pairingStore ?: return
            // vc227：配对流程进行中不覆盖状态（重建恰好落在配对窗口内时交给配对流程收尾）
            if (pairStateSnapshot == PairState.PAIRING) {
                Log.i(UsbBtSession.TAG, "pair restore: 跳过（配对进行中）")
                return
            }
            val saved = try {
                store.loadAll()
            } catch (t: Throwable) {
                Log.w(UsbBtSession.TAG, "pair restore: 读取持久化失败 ${t.message}")
                return
            }
            if (saved.isEmpty()) return
            var lastAddr: BdAddr? = null
            for ((key, pair) in saved) {
                if (pair.first.size != 16) continue
                sharedLinkKeys[key] = pair.first
                if (!pair.second.isNullOrEmpty()) pairedNames[key] = pair.second!!
                lastAddr = try {
                    BdAddr(key.toLong(16))
                } catch (_: Throwable) {
                    null
                }
            }
            if (sharedLinkKeys.isEmpty()) return
            pairStateSnapshot = PairState.PAIRED
            pairedRemoteSnapshot = lastAddr?.redacted()
            Log.i(
                UsbBtSession.TAG,
                "host: pair restore: 使用已保存 link key addr=${lastAddr?.redacted() ?: "-"} count=${sharedLinkKeys.size}",
            )
            // vc227：恢复成功即安排一次主动回连（"配对成功就保持连接"的落地；
            // 失败由 onTick 按 PAGE_RETRY_MAX 退避，iPhone 不应答时不会无限打）
            activeHost?.armAutoReconnect(lastAddr, AUTO_RECONNECT_AFTER_RESTORE_MILLIS)
        }

        /**
         * vc206：清除配对的实际动作 —— 删全部 link key + 名称表 + PairState 回 UNPAIRED。
         * 在飞 ACL 链路不主动断（host 实例在 standby 循环深处，够不到；旧 key 已删，
         * 链路自然结束或下次回连走重配对自愈）。UI 弹层「清除配对」入口（经 UsbBtStandby）。
         * vc213：持久化记录一并删除（下轮不再 restore、不自动连）。
         */
        fun clearPairing() {
            sharedLinkKeys.clear()
            pairedNames.clear()
            try {
                pairingStore?.clear()
            } catch (_: Throwable) {
            }
            pairStateSnapshot = PairState.UNPAIRED
            pairedRemoteSnapshot = null
            Log.i(UsbBtSession.TAG, "host: pair state=UNPAIRED remote=-（清除配对，含持久化）")
        }

        /** vc206：进入配对模式（可发现开 [windowMillis]，对齐 UsbBtStandby.LINK_WINDOW_MILLIS=10min）。 */
        fun requestPairingMode(windowMillis: Long) {
            pairingModeUntil = System.currentTimeMillis() + windowMillis
        }

        /** vc206：退出配对模式（可发现立即关闭）。 */
        fun cancelPairingMode() {
            pairingModeUntil = 0L
        }

        /** vc206：配对模式剩余毫秒（<=0 表示未开启/已结束；UI 倒计时读它）。 */
        fun pairingRemainingMillis(): Long = pairingModeUntil - System.currentTimeMillis()

        /** vc207：inquiry 发现的附近设备（进程级快照，standby 换轮重建 host 不丢；key=addr.key()）。 */
        private val discoveredDevices = ConcurrentHashMap<String, DiscoveredDevice>()

        /** vc207：当前存活 host 实例（UI 发起配对 / inquiry 状态查询的入口；换轮时更新）。 */
        @Volatile
        private var activeHost: UsbBtHost? = null

        /** vc207：我方主动发起的配对目标 addr.key()（UI 行态「配对中」；page 单飞由实例 paging 保证）。 */
        @Volatile
        private var pairTarget: String? = null

        /** vc207：dongle 芯片/固件信息（init 时 ReadLocalVersion 实读拼装，如「CSR · HCI 4.0 · LMP 4.0」）。 */
        @Volatile
        var dongleInfoSnapshot: String? = null
            private set

        /** vc207：附近设备只读快照（最近见到的排前面）。 */
        fun discoveredDevicesSnapshot(): List<DiscoveredDevice> =
            discoveredDevices.values.sortedByDescending { it.lastSeenMillis }

        /** vc207：inquiry 进行中（活实例在跑发现）。 */
        fun isInquiryActive(): Boolean = activeHost?.inquiryRunning == true

        /** vc207：我方主动配对目标（null=未发起）。 */
        fun pairTargetSnapshot(): String? = pairTarget

        /** vc207：点击已发现设备 → 发起配对（dedicated bonding：page 连上后主动要认证走 SSP）。 */
        fun startPairing(address: String): Boolean = activeHost?.pageForPairing(address) ?: false

        /**
         * vc215：主动回连已配对设备（用户点「立即连接」时由 UsbBtBootstrapStream.open 调用）。
         * 实测（vc214 K60）：常驻广播只做被动等回连，已配对 iPhone 不主动发起 → awaitStream
         * 白等 60s。有 key 时直接 page 回连 → 认证（LinkKeyRequest 用持久化 key 应答）→ 加密
         * → startIap2 → DLC 交回 standby 槽位。多台已配对取第一台（当前实际只有一台）。
         * false = 无活 host / 无已配对设备（调用方维持被动等待）。
         * vc232：true 语义放宽为「链路可用」——page 已发出 / page 在飞 / **链路已经建立不必 page**
         * 都返回 true。这样 iPhone 主动连进来时调用方直接去 awaitStream，不会再去抢连接。
         */
        internal fun requestActiveConnect(): Boolean {
            val host = activeHost ?: return false
            // vc232：链路已在（iPhone 已 incoming 连进来，或上一轮 page 已建链）→ 绝不再 page。
            // page 与 incoming 互斥：vc231 实测「iPhone 手动连上后我们补发 page」把对方
            // 的 incoming 链路打断（双方各自等 ConnectionComplete），表现为「卡在网络链路中」。
            if (host.hasAclLink) {
                Log.i(UsbBtSession.TAG, "host: 链路已在（incoming/outgoing 已建立），跳过 page（角色=被叫方）")
                return true
            }
            if (host.isPaging) {
                Log.i(UsbBtSession.TAG, "host: page 已在飞，不重复发起（角色=主叫方）")
                return true
            }
            val key = sharedLinkKeys.keys.firstOrNull() ?: return false
            val addr = try {
                BdAddr(key.toLong(16))
            } catch (_: Throwable) {
                return false
            }
            return host.connectTo(addr)
        }

        /**
         * vc212：SSP 弹码确认出口（DisplayYesNo + MITM）。UI 层（MainActivity）注册：
         * 参数 = 对端冒号 MAC、6 位确认数字、答案回调（true=确认 false=取消）。
         * 回调可在任意线程调用（内部走线程安全命令队列）。null=无 UI 接管 → 一律拒绝。
         */
        @Volatile
        var pairConfirmHandler: ((addr: String, digits: String, answer: (Boolean) -> Unit) -> Unit)? = null

        /** vc207：蓝牙版本号 → 可读名（HCI/LMP 同表）。 */
        private fun btVersionName(v: Int): String = when (v) {
            0 -> "1.0"; 1 -> "1.1"; 2 -> "1.2"; 3 -> "2.0"; 4 -> "2.1"; 5 -> "3.0"
            6 -> "4.0"; 7 -> "4.1"; 8 -> "4.2"; 9 -> "5.0"; 10 -> "5.1"; 11 -> "5.2"
            12 -> "5.3"; 13 -> "5.4"
            else -> "0x%02x".format(v)
        }

        /** vc207：蓝牙厂商 id → 可读名（只列常见，其余显 id，不猜型号）。 */
        private fun btManufacturerName(id: Int): String = when (id) {
            0x0002 -> "Intel"; 0x000a -> "CSR"; 0x000f -> "Broadcom"
            0x001d -> "Qualcomm"; 0x0000 -> "Ericsson"
            else -> "厂商0x%04x".format(id)
        }

        /** vc207：ReadLocalVersion 返回参数（已去 status 字节）→ 芯片/固件信息行。 */
        private fun recordDongleInfo(payload: ByteArray) {
            if (payload.size < 8) return
            val hciVer = payload[0].toInt() and 0xff
            val hciRev = (payload[1].toInt() and 0xff) or ((payload[2].toInt() and 0xff) shl 8)
            val lmpVer = payload[3].toInt() and 0xff
            val mfr = (payload[4].toInt() and 0xff) or ((payload[5].toInt() and 0xff) shl 8)
            dongleInfoSnapshot = btManufacturerName(mfr) +
                " · HCI " + btVersionName(hciVer) + "(rev 0x" + "%04x".format(hciRev) + ")" +
                " · LMP " + btVersionName(lmpVer)
        }
    }

    // ---- 并发面：跨线程字段全部无锁/并发容器；锁只护本线程私有序列 ----

    @Volatile
    var phase: Phase = Phase.IDLE
        private set

    /**
     * vc232：当前是否已有 ACL 链路（incoming 已接受或 outgoing 已建立）。
     * 用于「链路已在就不要 page」——page 与 incoming 互斥，两边同时建链会互相打断。
     */
    internal val hasAclLink: Boolean get() = connections.isNotEmpty()

    /** vc232：我方 page 是否在飞（connectTo 已发 CREATE_CONNECTION、等 ConnectionComplete）。 */
    internal val isPaging: Boolean get() = paging.get() != null

    /**
     * vc213：配对失败/取消后的状态收口——只在没有**任何**已存 key 时才回 UNPAIRED；
     * 已有配对记录（含持久化恢复）时保持 PAIRED，避免「给别的设备配对失败把已配对状态
     * 清掉」（vc212 实测：MEIZU 20 配对失败把"华"的 iPhone 的 PAIRED 顶掉 → 主页误锁）。
     */
    private fun settlePairStateAfterFailure(addr: BdAddr?) {
        if (linkKeys.isEmpty()) {
            updatePairState(PairState.UNPAIRED, addr)
        } else if (pairStateSnapshot != PairState.PAIRED) {
            // 有已存 key：保持 PAIRED，remote 沿用既有快照（不拿失败对端冒充）
            pairStateSnapshot = PairState.PAIRED
            log("pair state=PAIRED remote=${pairedRemoteSnapshot ?: "-"}（存在已保存 link key，本次失败不回退）")
        }
    }

    /** 状态文本（D2 沿途搬进 UsbBtBootstrapStream.lastStatusText）。 */
    @Volatile
    var statusText: String = "未启动"
        private set

    private fun setStatus(text: String) {
        statusText = text
        log(text)
        onStatus?.invoke(text)
    }

    /**
     * vc205：配对状态迁移（幂等，状态+对端都不变时不重复打日志）。
     * 日志格式固定 `pair state=… remote=…`（经 host log 出口带 "host: " 前缀进 LiteLog）。
     */
    private fun updatePairState(state: PairState, remote: BdAddr?) {
        val remoteText = remote?.redacted()
        if (pairStateSnapshot == state && pairedRemoteSnapshot == remoteText) return
        pairStateSnapshot = state
        pairedRemoteSnapshot = remoteText
        log("pair state=$state remote=${remoteText ?: "-"}")
        if (state == PairState.PAIRED) {
            // 日志头段锚点：配对完成（固定文案，可 grep）。
            log("ANCHOR pair-done")
        }
    }

    /** 状态行出口（D2 搬进 UsbBtBootstrapStream.lastStatusText 供设置页显示）。 */
    @Volatile
    var onStatus: ((String) -> Unit)? = null

    /** dongle 本机地址（初始化 ReadBdAddr 完成后非空）。 */
    fun localAddress(): BdAddr? = localAddr

    /** 等一条就绪的 iAP2 DLC（D2 主循环轮询）；超时返回 null。 */
    fun awaitIap2Dlc(timeoutMillis: Long): RfcommDlc? = iap2Link.take(timeoutMillis)

    /** 初始化步骤序列：仅命令线程读写。 */
    private var steps: List<Step> = emptyList()
    private var stepIndex = 0

    /** 命令队列元素（命令线程消费）。 */
    private class PendingCommand(val opcode: Int, val params: ByteArray)

    private val commandQueue = ArrayDeque<PendingCommand>()

    @Volatile
    private var commandThreadRunning = false
    private var commandThread: Thread? = null

    /** 控制器信息（Read_BdAddr / Read_Buffer_Size / TxPower）。 */
    @Volatile
    private var localAddr: BdAddr? = null

    /**
     * vc206：当前 ScanEnable 模式（0/1/2/3；-1=未知）。init 末步写 2（仅 page scan：
     * 已配对可回连、不可被发现）；onTick 按配对模式窗口收敛到 2/3。
     */
    @Volatile
    private var currentScanMode = -1
    @Volatile
    private var aclPackets = 1
    @Volatile
    private var aclDataLength = 27
    @Volatile
    private var inquiryTxPower: Int? = null

    /** link key 表（vc206 起进程级共享：standby 换轮不丢配对态；TODO：持久化）。 */
    private val linkKeys: ConcurrentHashMap<String, ByteArray> get() = sharedLinkKeys

    private val connections = ConcurrentHashMap<Int, BdAddr>()
    // v1 无主动扫描：链路可能由 iPhone 发起（incoming），故加密成功即启动 iAP2，
    // 不做方向假设；outgoing 仅用于本机主动 page 的记账。
    // 用 Collections.newSetFromMap：minSdk16 上 ConcurrentHashMap.newKeySet() 是 API21+，会炸。
    private val outgoing = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
    private val securing = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
    private val secured = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
    private val startedIap2 = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
    private val authRetries = ConcurrentHashMap<Int, AuthRetry>()

    /** vc207：inquiry 进行中（本实例；companion.isInquiryActive 经 activeHost 读）。 */
    @Volatile
    private var inquiryRunning = false

    /**
     * vc209：上一轮 inquiry 结束时刻。vc208 实测 iPhone 搜不到我们——InquiryComplete 后
     * 8ms 内立刻续下一轮，CSR8510 持续 inquiry 时 inquiry-scan 监听时隙几乎被挤没
     * （iPhone 的 inquiry 全打在我们的"耳聋期"）。改为「探测 ~10s → 空 6s 纯监听」节奏。
     */
    @Volatile
    private var lastInquiryCompleteAt = 0L

    /** 主动 connectTo 后等 ConnectionComplete 的对端（单飞）。 */
    private val paging = AtomicReference<BdAddr?>(null)
    @Volatile
    private var pagingSince = 0L

    /** vc215：主动回连 page 重试状态（connectTo 武装；ConnectionComplete 失败/onTick 超时消费）。 */
    @Volatile
    private var pageRetryAddr: BdAddr? = null
    @Volatile
    private var pageRetryLeft = 0
    @Volatile
    private var pageRetryAt = 0L

    /** vc226：iAP2 建链重试状态（-1 = 无待办）。 */
    @Volatile
    private var iap2RetryHandle = -1
    @Volatile
    private var iap2RetryAt = 0L
    @Volatile
    private var iap2RetryCount = 0

    /** vc212：待用户确认的 SSP 弹码请求（单飞；配对一次只会有一个在飞）。 */
    @Volatile
    private var pendingConfirm: BdAddr? = null
    @Volatile
    private var pendingConfirmSince = 0L

    /**
     * vc212：弹码答案入口（UI 回调，任意线程）。确认 → USER_CONFIRMATION_REQUEST_REPLY；
     * 取消 → NEGATIVE + pair state 回 UNPAIRED。只在请求仍在飞时生效（幂等）。
     */
    private fun answerPairConfirm(addr: BdAddr, ok: Boolean) {
        val pending = pendingConfirm
        if (pending == null || pending.value != addr.value) return
        pendingConfirm = null
        pendingConfirmSince = 0
        if (ok) {
            enqueueCommand(HciCommands.USER_CONFIRMATION_REQUEST_REPLY, addr.toLittleEndian())
            log("pair confirm accepted for ${addr.redacted()}")
        } else {
            enqueueCommand(HciCommands.USER_CONFIRMATION_REQUEST_NEGATIVE_REPLY, addr.toLittleEndian())
            settlePairStateAfterFailure(addr) // vc213
            log("pair confirm cancel（用户取消）${addr.redacted()}")
        }
    }

    // ---- ACL 发送面 ----
    private val aclLock = Object()
    private val aclQueue = ArrayDeque<Pair<Int, ByteArray>>()

    @Volatile
    private var aclCredits = 1
    private val aclOutstanding = ConcurrentHashMap<Int, Int>()

    // ---- L2CAP / RFCOMM / iAP2 ----
    private val l2capLock = Object()
    private val reassembler = L2capReassembler()
    private val iap2Link = UsbBtIap2Link()

    private fun newRfcommService(connectTo: Int?): RfcommL2capService =
        RfcommL2capService(
            serverChannels = setOf(identity.iap2RfcommChannel),
            log = log,
            onOpen = { dlc ->
                // vc230：对端（iPhone）主动开的 DLC 同样算 iAP2 就绪——实测 iPhone 会先
                // 打 PSM 3 到我们、PN 协商后在 DLCI 2 上开通道；此时不必再自己 page/SDP 重试。
                resetIap2Retry()
                setStatus("iAP2 流已就绪")
                iap2Link.offer(dlc)
            },
            connectTo = connectTo,
        )

    private val l2cap = L2capLayer(
        // vc228：必须注册 SDP 服务端（PSM 0x0001）——实测 iPhone 一连上就打 SDP 查询到我们，
        // 此前只做客户端、回 result=0x0002（PSM 不支持）→ iPhone 1.1s 内主动断链
        //（reason 0x13），表现为「配对成功但连不上/不在范围」。RFCOMM 服务端信道一并保留。
        services = mapOf(
            PSM_SDP to SdpServer(identity.iap2RfcommChannel, log = log),
            PSM_RFCOMM to newRfcommService(connectTo = null),
        ),
        send = { handle, payload -> sendAclFlow(handle, payload) },
        observer = object : L2capLayer.Observer {
            override fun onPeerService(handle: Int, addr: BdAddr?, psm: Int, supported: Boolean) {
                log("l2cap: peer uses psm=$psm supported=$supported")
            }
        },
    )

    /** 建链全部就绪（初始化完成进入 DISCOVERABLE）时回调一次。 */
    @Volatile
    var onReady: (() -> Unit)? = null

    // ---- 生命周期 ----

    /** 启动初始化序列（幂等：仅 IDLE 态有效）。 */
    fun start() {
        if (phase != Phase.IDLE) return
        phase = Phase.STARTING
        setStatus("正在初始化 USB 蓝牙 dongle…")
        steps = buildSteps()
        stepIndex = 0
        log("usbbt: init sequence: ${steps.joinToString("→") { it.name }}（无 Reset 步）")
        log("start entered，steps=${steps.size}")
        commandThreadRunning = true
        commandThread = Thread({ commandLoop() }, "usb-bt-commands").apply {
            isDaemon = true
            start()
        }
        // 历史缺陷修复（2026-11-03）：步骤表构建后必须派发首步入队，否则 commandLoop 永远空转——
        // 此前初始化一步命令都未真正发出（usbbt 栈自 vc181 落地起未在任何设备完成过初始化）。
        steps.firstOrNull()?.let {
            log("enqueue first command 0x%04x".format(it.opcode))
            enqueueCommand(it.opcode, it.build())
        }
    }

    // 初始化序列（2026-11-03 K60 CSR8510 定性后对齐 FreeAuto 1.2.4 实测序列）：以
    // ReadLocalVersion 起手，无 HCI_Reset —— 该固件（hci 0x22bb）不回 Reset 的
    // Command Complete，死等必超时。每条命令经 hciCommand 打点，start() 打完整序列自证。
    private fun buildSteps(): List<Step> = listOf(
        Step("ReadLocalVersion", HciCommands.READ_LOCAL_VERSION_INFORMATION, false) { ByteArray(0) },
        Step("ReadLocalFeatures", HciCommands.READ_LOCAL_SUPPORTED_FEATURES, false) { ByteArray(0) },
        Step("ReadBdAddr", HciCommands.READ_BD_ADDR, true) { ByteArray(0) },
        Step("ReadBufferSize", HciCommands.READ_BUFFER_SIZE, true) { ByteArray(0) },
        Step("SetEventMask", HciCommands.SET_EVENT_MASK, true) { UsbBtIdentity.eventMask() },
        Step("WriteSimplePairingMode", HciCommands.WRITE_SIMPLE_PAIRING_MODE, true) { byteArrayOf(1) },
        Step("WriteInquiryMode", HciCommands.WRITE_INQUIRY_MODE, false) { byteArrayOf(2) },
        Step(
            "WriteDefaultLinkPolicy",
            HciCommands.WRITE_DEFAULT_LINK_POLICY_SETTINGS,
            false,
        ) { HciCommands.le16(5) },
        Step("WriteClassOfDevice", HciCommands.WRITE_CLASS_OF_DEVICE, true) {
            byteArrayOf(
                (identity.classOfDevice and 0xff).toByte(),
                ((identity.classOfDevice ushr 8) and 0xff).toByte(),
                ((identity.classOfDevice ushr 16) and 0xff).toByte(),
            )
        },
        Step("WriteLocalName", HciCommands.WRITE_LOCAL_NAME, true) {
            (HciCommands.utf8Prefix(247, identity.name) + ByteArray(248)).copyOf(248)
        },
        Step("ReadInquiryResponseTxPower", HciCommands.READ_INQUIRY_RESPONSE_TX_POWER, false) { ByteArray(0) },
        Step("WriteExtendedInquiryResponse", HciCommands.WRITE_EXTENDED_INQUIRY_RESPONSE, false) {
            val eir = identity.extendedInquiryResponse(inquiryTxPower)
            byteArrayOf(1) + eir.copyOf(240)
        },
        Step("WriteInquiryScanType", HciCommands.WRITE_INQUIRY_SCAN_TYPE, false) { byteArrayOf(1) },
        Step("WritePageScanType", HciCommands.WRITE_PAGE_SCAN_TYPE, false) { byteArrayOf(1) },
        // vc206：末步默认仅 page scan（2）——可连接但不可被发现；「可发现」改为配对模式
        // 10 分钟窗口内才开（onTick 拉到 3），配对成功/超时/取消即回落。
        Step("WriteScanEnable", HciCommands.WRITE_SCAN_ENABLE, true) { byteArrayOf(2) },
    )

    /** 命令线程：逐条同步执行；结果回投推进状态机（本线程内）。 */
    private fun commandLoop() {
        while (commandThreadRunning) {
            val pending = synchronized(commandQueue) { commandQueue.removeFirstOrNull() }
            if (pending == null) {
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                continue
            }
            var status = -1
            var payload: ByteArray? = null
            try {
                val ret = driver.hciCommand(pending.opcode, pending.params, STEP_TIMEOUT_MILLIS)
                if (ret != null) {
                    status = 0
                    payload = ret
                }
            } catch (e: UsbBtDriver.HciStatusException) {
                status = e.status
            } catch (e: Throwable) {
                log("command 0x%04x failed: $e".format(pending.opcode))
            }
            onCommandResult(pending.opcode, status, payload)
        }
    }

    private fun enqueueCommand(opcode: Int, params: ByteArray) {
        synchronized(commandQueue) { commandQueue.addLast(PendingCommand(opcode, params)) }
    }

    /** 命令完成（命令线程）：STARTING 态推进步骤，其余直接忽略。 */
    private fun onCommandResult(opcode: Int, status: Int, payload: ByteArray?) {
        if (phase != Phase.STARTING) return
        val step = steps.getOrNull(stepIndex) ?: return
        if (step.opcode != opcode) return // 迟到/异源完成，丢弃
        if (status == 0) {
            absorb(opcode, payload)
        } else if (step.required) {
            val reason = if (status < 0) "timeout:${step.name}" else "${step.name} status 0x%02x".format(status)
            failWith(reason)
            return
        }
        stepIndex++
        if (stepIndex >= steps.size) {
            phase = Phase.DISCOVERABLE
            currentScanMode = 2 // init 末步 WriteScanEnable(2) 已生效
            aclCredits = aclPackets
            activeHost = this // vc207：注册活实例（UI 发起配对/inquiry 状态入口）
            setStatus("dongle 就绪（${localAddr ?: "地址未知"}），等待 iPhone 连接")
            onReady?.invoke()
        } else {
            // 派发下一步（本函数仅由 commandLoop 线程调用，stepIndex 单线程推进，无并发；越界已被上分支拦截）
            val next = steps[stepIndex]
            enqueueCommand(next.opcode, next.build())
        }
    }

    /** 从 Command Complete 返回参数吸收控制器信息（payload 已去 status 字节）。 */
    private fun absorb(opcode: Int, payload: ByteArray?) {
        if (payload == null) return
        when (opcode) {
            HciCommands.READ_BD_ADDR ->
                if (payload.size >= 6) {
                    localAddr = BdAddr.of(payload, 0)
                    localAddressSnapshot = localAddr?.toString()
                }
            HciCommands.READ_BUFFER_SIZE ->
                if (payload.size >= 4) {
                    aclPackets = maxOf(1, le16(payload, 0))
                    aclDataLength = maxOf(27, le16(payload, 2))
                    log("buffer size: aclPackets=$aclPackets aclDataLength=$aclDataLength")
                }
            HciCommands.READ_INQUIRY_RESPONSE_TX_POWER ->
                if (payload.isNotEmpty()) {
                    inquiryTxPower = payload[0].toInt()
                }
            // vc207：芯片/固件信息（payload 已去 status：hci_ver(1) hci_rev(2) lmp_ver(1) mfr(2) lmp_sub(2)）
            HciCommands.READ_LOCAL_VERSION_INFORMATION -> recordDongleInfo(payload)
        }
    }

    private fun le16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)

    fun failWith(reason: String) {
        if (phase == Phase.STOPPED) return
        phase = Phase.FAILED
        statusText = reason
        log("host failed: $reason")
        // vc219：有持久化 link key 时，host 瞬态失败（dongle  wedge/端口复位兜底中等）不再把
        // PAIRED 打回 UNPAIRED——配对关系在 SharedPreferences 里，跟 host 进程态无关。
        if (sharedLinkKeys.isEmpty()) {
            updatePairState(PairState.UNPAIRED, null)
        } else {
            log("host: 已有持久化 link key，host 失败不回退 pair state")
        }
        iap2Link.reset()
    }

    /** 停机：断全部 ACL 链路 → 关扫描 → 关 iAP2 → 关驱动。幂等。 */
    fun stop() {
        if (phase == Phase.STOPPED) return
        phase = Phase.STOPPED
        statusText = "已停止"
        inquiryRunning = false // vc207
        pageRetryAddr = null // vc215：停机清主动回连重试
        pageRetryLeft = 0
        pageRetryAt = 0
        if (activeHost === this) activeHost = null // vc207：注销活实例（standby 换轮）
        // vc206：正常交接轮次（standby 换轮/流关闭）不复位配对态——link key 进程级，
        // 复位只发生在异常 failWith / 显式 clearPairing，否则 UI 会被锁回未配对。
        commandThreadRunning = false
        synchronized(commandQueue) { commandQueue.clear() }
        for (handle in connections.keys.toList()) {
            try {
                // vc236（BT-235-02）：DISCONNECT 是异步命令（控制器只回 Command Status + 后续
                // DisconnectComplete，**不回** Command Complete）。此前按同步命令等 500ms，
                // vc235 实测 3/3 白等（`0x0406 await timeout 500ms`）。改为只发不等；
                // 拆除正确性由 DisconnectComplete 事件与后续 driver.close 兜底。
                driver.hciSend(HciCommands.DISCONNECT, HciCommands.le16(handle and 0xfff) + byteArrayOf(0x13))
            } catch (_: Throwable) {
            }
        }
        try {
            // vc205：收尾命令只发不等久——CSR8510 对这两条本就不回 CC，500ms 是纯等待浪费；
            // 150ms 仍够健康控制器应答，病 dongle 不再拖住 teardown。
            driver.hciCommand(HciCommands.WRITE_SCAN_ENABLE, byteArrayOf(0), 150)
        } catch (_: Throwable) {
        }
        // vc192：收尾必须补一次 HCI Reset。Realtek 系 dongle（CM390/RTL8761BUV）灌完固件后
        // 控制器会回到「已 patch、未改名」态（本地名回落到出厂值，如 CM390）。此前 stop() 只发
        // Disconnect + ScanEnable(0)，一旦发不出去（bulk 已死/中途失败）dongle 就停在那态继续对外
        // 可发现 —— 这正是外测「iPhone 蓝牙里看到的是 CM390 而不是我们的广播名」的来源。
        // legacy 0.3.1 日志里 handoff shutdown 的最后一步也是 `stage=HCI reset`，此处对齐。
        try {
            driver.hciCommand(HciCommands.RESET, ByteArray(0), 150)
        } catch (_: Throwable) {
        }
        iap2Link.reset()
        driver.close()
        commandThread?.let {
            if (Thread.currentThread() !== it) {
                try {
                    it.join(3500)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    // ---- 对外建链动作 ----

    /** 主动 page 指定对端（须已有 link key 才会真正发 CREATE_CONNECTION）。 */
    fun connectTo(addr: BdAddr): Boolean {
        if (phase != Phase.DISCOVERABLE) return false
        // vc232：已有链路 / page 已在飞就不重复发起——分别是「被叫方」与「主叫方」两种角色，
        // 同时建链会让双方的 ConnectionComplete 互相打断（vc231 实测：iPhone incoming 建立后
        // 我们再补 page，iPhone 侧立即断链，观感就是「卡在网络链路中」）。
        if (hasAclLink || isPaging) {
            log(
                "host: 跳过 page（角色=" +
                    (if (hasAclLink) "被叫方：链路已在" else "主叫方：page 在飞") + "）",
            )
            return true
        }
        if (!linkKeys.containsKey(addr.key())) {
            setStatus("未与 ${addr.redacted()} 配对过，等待对端回连")
            return false
        }
        if (!paging.compareAndSet(null, addr)) return false
        // vc215：page 与 inquiry 互斥（同 pageForPairing，射频让给寻呼）+ 失败重试武装
        if (inquiryRunning) {
            inquiryRunning = false
            driver.hciSend(HciCommands.INQUIRY_CANCEL)
            log("inquiry cancel（主动回连，射频让给 page）")
        }
        pageRetryAddr = addr
        pageRetryLeft = PAGE_RETRY_MAX
        pageRetryAt = 0
        pagingSince = System.currentTimeMillis()
        setStatus("正在连接 ${addr.redacted()}…")
        // vc211：CREATE_CONNECTION 是异步命令（CommandStatus + ConnectionComplete 闭环），
        // 走 enqueueCommand/hciCommand 会白等 3s 卡死命令线程——改 hciSend 只发不等。
        driver.hciSend(
            HciCommands.CREATE_CONNECTION,
            addr.toLittleEndian() + HciCommands.le16(52248) + byteArrayOf(1, 0) + HciCommands.le16(0) + byteArrayOf(1),
        )
        return true
    }

    /** 在既有链路上启动 iAP2（未加密则先认证）。 */
    fun openIap2OnLink(handle: Int) {
        if (!connections.containsKey(handle)) return
        if (secured.contains(handle)) {
            startIap2(handle)
        } else {
            securing.add(handle)
            // vc211：AUTHENTICATION_REQUESTED 异步（CommandStatus + AuthenticationComplete），只发不等。
            driver.hciSend(HciCommands.AUTHENTICATION_REQUESTED, HciCommands.le16(handle and 0xfff))
        }
    }

    /** SDP 查 RFCOMM 信道 → 建 RFCOMM → DLC 交 iap2Link。可重入防护 + vc226 失败重试。 */
    private fun startIap2(handle: Int) {
        if (!startedIap2.add(handle)) return
        iap2RetryAt = 0
        setStatus("正在查询 iAP2 服务…")
        val onFailed = { code: Int ->
            startedIap2.remove(handle)
            setStatus("iAP2 服务查询失败（l2cap result=$code）")
            scheduleIap2Retry(handle, "l2cap refused result=$code")
        }
        val query = SdpRfcommChannelQuery(UsbBtIdentity.IAP2_IPHONE_UUID) { channel ->
            if (channel == null) {
                startedIap2.remove(handle)
                setStatus("对端未提供 iAP2 RFCOMM 服务")
                scheduleIap2Retry(handle, "SDP 未返回信道")
                return@SdpRfcommChannelQuery
            }
            if (!iap2Link.isOpen()) {
                setStatus("正在建立 RFCOMM（信道 $channel）…")
                synchronized(l2capLock) {
                    l2cap.connect(handle, PSM_RFCOMM, newRfcommService(channel), onFailed)
                }
            }
        }
        synchronized(l2capLock) {
            l2cap.connect(handle, PSM_SDP, query, onFailed)
        }
    }

    /**
     * vc226：iAP2 建链失败后排一次重试（1.5s 后由 onTick 触发）。
     * 依据 FreeAuto 实测：SDP 会被 iPhone 回 ErrorResponse/无应答若干次，它靠 4~8 次重试拿到信道；
     * 我们原来一次失败就放弃 → 表现为「配对成功但连不上」。
     */
    private fun scheduleIap2Retry(handle: Int, reason: String) {
        if (iap2RetryCount >= IAP2_RETRY_MAX) {
            log("iAP2 放弃：$reason（已重试 $iap2RetryCount 次）")
            iap2RetryHandle = -1
            iap2RetryAt = 0
            iap2RetryCount = 0
            setStatus("iAP2 建链失败（$reason）")
            return
        }
        iap2RetryCount++
        iap2RetryHandle = handle
        iap2RetryAt = System.currentTimeMillis() + IAP2_RETRY_GAP_MILLIS
        log("iAP2 retry 排程 $iap2RetryCount/$IAP2_RETRY_MAX：$reason → ${IAP2_RETRY_GAP_MILLIS}ms 后重试")
    }

    /** vc226：链路建立/断开时复位 iAP2 重试状态。 */
    private fun resetIap2Retry() {
        iap2RetryHandle = -1
        iap2RetryAt = 0
        iap2RetryCount = 0
    }

    /** vc227：武装一次自动回连（配对恢复后 / 对端断链后）。 */
    internal fun armAutoReconnect(addr: BdAddr?, delayMillis: Long) {
        if (addr == null || phase != Phase.DISCOVERABLE) return
        // vc238：总闸关闭 = 只保留被叫（page scan），不主动 page。
        if (!discoverableEnabled) {
            log("host: 总闸关闭，跳过自动回连排程 ${addr.redacted()}")
            return
        }
        if (!linkKeys.containsKey(addr.key())) return
        pageRetryAddr = addr
        pageRetryLeft = PAGE_RETRY_MAX
        pageRetryAt = System.currentTimeMillis() + delayMillis
        log("host: 自动回连排程 ${addr.redacted()}（${delayMillis}ms 后，最多 $PAGE_RETRY_MAX 次）")
    }

    /**
     * vc238：立刻按总闸状态重发 Write_Scan_Enable（开=3 可发现 / 关=2 仅 page scan）。
     * 只走命令队列，不触碰链路；调用方（standby）已确认控制器未被占用。
     */
    internal fun pushScanModeNow(): Boolean {
        if (phase != Phase.DISCOVERABLE || currentScanMode < 0) return false
        val now = System.currentTimeMillis()
        val want = if (!discoverableEnabled) {
            2
        } else if (now < pairingModeUntil || sharedLinkKeys.isNotEmpty()) {
            3
        } else {
            2
        }
        if (want == currentScanMode) return false
        currentScanMode = want
        enqueueCommand(HciCommands.WRITE_SCAN_ENABLE, byteArrayOf(want.toByte()))
        log("host: 扫描模式立即切换 -> $want（总闸${if (discoverableEnabled) "开" else "关"}）")
        return true
    }

    // ---- ACL 发送（l2capLock 内被调用；锁序 l2capLock → aclLock） ----

    private fun sendAclFlow(handle: Int, payload: ByteArray) {
        synchronized(aclLock) {
            aclQueue.addLast(handle to payload)
            flushAclLocked()
        }
    }

    private fun flushAclLocked() {
        while (aclQueue.isNotEmpty() && aclCredits > 0) {
            val (handle, payload) = aclQueue.first()
            val segments = ArrayList<ByteArray>()
            var off = 0
            do {
                val len = minOf(aclDataLength, payload.size - off)
                segments.add(payload.copyOfRange(off, off + len))
                off += len
            } while (off < payload.size)
            if (segments.size > aclCredits) break
            aclQueue.removeFirst()
            segments.forEachIndexed { index, segment ->
                // PB：首包 0b10（起点），续包 0b01
                driver.sendAcl(handle, segment, if (index == 0) 0x2 else 0x1)
                aclCredits--
                aclOutstanding[handle] = (aclOutstanding[handle] ?: 0) + 1
                // vc214：ACL 线上取证（SDP 静默定位：请求是否真上線）
                if (index == 0 && segment.size >= 4) {
                    val cid = ((segment[3].toInt() and 0xff) shl 8) or (segment[2].toInt() and 0xff)
                    log("acl tx handle=0x%03x cid=%d len=%d credits=%d".format(handle, cid, segment.size, aclCredits))
                }
            }
        }
    }

    // ---- UsbBtDriver.Listener ----

    override fun onHciEvent(event: HciEvent) {
        // CommandComplete 由命令线程的同步 hciCommand 闭环处理（driver 双投递），此处忽略。
        if (event is HciEvent.CommandComplete) return
        when (event) {
            is HciEvent.CommandStatus -> {
                if (event.opcode == HciCommands.CREATE_CONNECTION && event.status != 0) {
                    paging.set(null)
                    pagingSince = 0
                    pairTarget = null // vc207：主动配对 page 被拒，清目标
                    setStatus("连接请求被控制器拒绝（status 0x%02x）".format(event.status))
                }
            }
            is HciEvent.ConnectionComplete -> onConnectionComplete(event)
            is HciEvent.ConnectionRequest -> onConnectionRequest(event)
            is HciEvent.DisconnectionComplete -> onDisconnectionComplete(event)
            is HciEvent.LinkKeyRequest -> {
                val key = linkKeys[event.addr.key()]
                if (key != null && key.size == 16) {
                    // 已有 link key 的回连：视为已配对设备归来
                    updatePairState(PairState.PAIRED, event.addr)
                    enqueueCommand(HciCommands.LINK_KEY_REQUEST_REPLY, event.addr.toLittleEndian() + key)
                } else {
                    updatePairState(PairState.PAIRING, event.addr)
                    enqueueCommand(HciCommands.LINK_KEY_REQUEST_NEGATIVE_REPLY, event.addr.toLittleEndian())
                    setStatus("${event.addr.redacted()} 请求配对…")
                }
            }
            is HciEvent.LinkKeyNotification -> {
                if (event.key.size == 16) {
                    linkKeys[event.addr.key()] = event.key
                    // vc213：落盘持久化（下次启动 restore 免重配对；名字可能后到，由 NameComplete 补写）
                    try {
                        pairingStore?.save(event.addr.key(), event.key, pairedNames[event.addr.key()])
                    } catch (_: Throwable) {
                    }
                    pairingModeUntil = 0L // vc206：配对成功即关可发现窗口（下个 tick 回落 scan=2）
                    if (event.addr.key() == pairTarget) pairTarget = null // vc207：主动配对目标达成
                    updatePairState(PairState.PAIRED, event.addr)
                    log("link key stored for ${event.addr.redacted()} (type ${event.keyType})")
                }
            }
            is HciEvent.PinCodeRequest ->
                enqueueCommand(HciCommands.PIN_CODE_REQUEST_NEGATIVE_REPLY, event.addr.toLittleEndian())
            is HciEvent.UserPasskeyRequest ->
                enqueueCommand(HciCommands.USER_PASSKEY_REQUEST_NEGATIVE_REPLY, event.addr.toLittleEndian())
            is HciEvent.IoCapabilityRequest -> {
                val auth = if (identity.ioCapability == 3) 4 else 5
                enqueueCommand(
                    HciCommands.IO_CAPABILITY_REQUEST_REPLY,
                    event.addr.toLittleEndian() + byteArrayOf(
                        identity.ioCapability.toByte(),
                        0,
                        auth.toByte(),
                    ),
                )
            }
            is HciEvent.UserConfirmationRequest -> {
                // vc212：弹码确认（DisplayYesNo + MITM）——不再 Just-Works 自动确认。
                // 6 位数值上交 UI 弹窗，用户点「确认」才回 USER_CONFIRMATION_REQUEST_REPLY，
                // 取消/超时/无 UI 回 NEGATIVE 并回 UNPAIRED。
                // 注意：Reply 参数只有 BD_ADDR（little-endian，toLittleEndian 保证）——
                // 数值由控制器自存校验，无需（也不能）随回复回传。
                val handler = pairConfirmHandler
                if (handler == null) {
                    enqueueCommand(
                        HciCommands.USER_CONFIRMATION_REQUEST_NEGATIVE_REPLY,
                        event.addr.toLittleEndian(),
                    )
                    log("pair confirm cancel（无 UI 接管，拒绝对端 ${event.addr.redacted()}）")
                } else {
                    pendingConfirm = event.addr
                    pendingConfirmSince = System.currentTimeMillis()
                    val digits = "%06d".format(event.passkey)
                    log("pair confirm request ${event.addr.redacted()} digits=$digits（等待用户确认，30s 超时）")
                    handler.invoke(event.addr.toString(), digits) { ok -> answerPairConfirm(event.addr, ok) }
                }
            }
            is HciEvent.SimplePairingComplete -> {
                pendingConfirm = null // vc212：无论成败，弹码请求到此终结
                pendingConfirmSince = 0
                if (event.status != 0) {
                    if (event.addr.key() == pairTarget) pairTarget = null // vc213：失败清目标（UI 行态回可配对）
                    settlePairStateAfterFailure(event.addr) // vc213：有其他已存 key 不回退 UNPAIRED
                    setStatus("与 ${event.addr.redacted()} 配对失败（status ${event.status}）")
                }
            }
            is HciEvent.AuthenticationComplete -> onAuthenticationComplete(event)
            is HciEvent.EncryptionChange -> {
                // 不分方向：v1 主路径是 iPhone 回连（incoming），加密成功即可启 iAP2。
                if (event.status == 0 && event.enabled) {
                    securing.remove(event.handle)
                    secured.add(event.handle)
                    startIap2(event.handle)
                }
            }
            is HciEvent.HardwareError -> failWith("controller hardware error 0x%02x".format(event.errorCode))
            is HciEvent.NumberOfCompletedPackets -> onNumberOfCompletedPackets(event.params)
            is HciEvent.RemoteNameRequestComplete ->
                if (event.status == 0) {
                    pairedNames[event.addr.key()] = event.name
                    // vc213：名字补写进持久化（link key 落盘时可能还没名字）
                    try {
                        if (linkKeys.containsKey(event.addr.key())) {
                            pairingStore?.saveName(event.addr.key(), event.name)
                        }
                    } catch (_: Throwable) {
                    }
                    log("peer name: ${event.name}")
                }
            // vc207：一轮 inquiry 结束。vc209：不立刻续轮——记录完成时刻，
            // 由 onTick 隔 INQUIRY_LISTEN_GAP_MILLIS 纯监听窗后再启动下一轮。
            is HciEvent.InquiryComplete -> {
                inquiryRunning = false
                lastInquiryCompleteAt = System.currentTimeMillis()
                log("inquiry complete status=${event.status}（累计 ${discoveredDevices.size} 台，监听 6s 后续探）")
            }
            is HciEvent.InquiryResult -> onInquiryResult(event)
            else -> log("hci event 0x%02x ignored".format(event.code))
        }
    }

    private fun onConnectionComplete(event: HciEvent.ConnectionComplete) {
        if (event.linkType != 1) return // 只关心 ACL
        if (event.status != 0) {
            paging.set(null)
            pagingSince = 0
            if (event.addr.key() == pairTarget) pairTarget = null // vc207：主动配对 page 失败，清目标
            // vc215：主动回连 page 未应答（实测 iPhone 首轮 status=4）→ 2s 后重试，最多 PAGE_RETRY_MAX 次
            val retryAddr = pageRetryAddr
            if (retryAddr != null && retryAddr.value == event.addr.value && pageRetryLeft > 0) {
                pageRetryLeft--
                pageRetryAt = System.currentTimeMillis() + PAGE_RETRY_GAP_MILLIS
                setStatus("回连未应答（status ${event.status}），2s 后重试（剩 $pageRetryLeft 次）")
                log("host: active reconnect page failed status=${event.status} addr=${event.addr.redacted()}（待重试，剩 $pageRetryLeft）")
                return
            }
            pageRetryAddr = null
            pageRetryLeft = 0
            pageRetryAt = 0
            setStatus("连接失败（status ${event.status}）")
            return
        }
        connections[event.handle] = event.addr
        pageRetryAddr = null // vc215：连上了，重试作废
        pageRetryLeft = 0
        pageRetryAt = 0
        // connectTo/pageForPairing 目标命中即消费 paging，标记为主动发起方。
        // vc211 根因修复：AtomicReference.compareAndSet 是「引用比较」——paging 里存的是
        // parseColon 的实例，event.addr 是 of() 新建的实例，值相等引用不同，CAS 必败
        // → outgoing 分支从未进入 → AUTHENTICATION_REQUESTED 从未发出 → SSP 不开始，
        // iPhone 等 4s 断链报「配对不成功」（vc210 实测日志实证）。改为按值比较+CAS 原实例。
        val pending = paging.get()
        if (pending != null && pending.value == event.addr.value && paging.compareAndSet(pending, null)) {
            pagingSince = 0
            outgoing.add(event.handle)
            driver.hciSend(HciCommands.AUTHENTICATION_REQUESTED, HciCommands.le16(event.handle and 0xfff))
            log("host: 主动链路已起，发 AuthenticationRequested 触发 SSP")
        }
        if (event.encryptionEnabled) {
            secured.add(event.handle)
            startIap2(event.handle)
        } else {
            setStatus("已连接 ${event.addr.redacted()}，等待认证/加密…")
        }
    }

    private fun onConnectionRequest(event: HciEvent.ConnectionRequest) {
        // vc211：Accept/Reject 异步（CommandStatus + ConnectionComplete/失败事件），无 CommandComplete；
        // 走 enqueueCommand 会白等 3s 卡命令线程（实测 `0x0409 await timeout 3000ms`）→ 只发不等。
        if (phase != Phase.DISCOVERABLE || event.linkType != 1) {
            driver.hciSend(
                HciCommands.REJECT_CONNECTION_REQUEST,
                event.addr.toLittleEndian() + byteArrayOf(13),
            )
            return
        }
        // vc232：对端主动连进来的瞬间就把在飞 page 让掉（同 addr 才算同一个对端）。
        // page 与 incoming 互斥：两条建链请求同时在飞时，谁先完成都可能导致对方被拆。
        // 让掉后 ConnectionComplete 走 incoming 分支（不发 AuthenticationRequested，
        // 由 iPhone 主叫方发起认证），我们只做被叫方。
        val pending = paging.get()
        if (pending != null && pending.value == event.addr.value && paging.compareAndSet(pending, null)) {
            pagingSince = 0
            pageRetryAddr = null
            pageRetryLeft = 0
            pageRetryAt = 0
            driver.hciSend(HciCommands.CREATE_CONNECTION_CANCEL, event.addr.toLittleEndian())
            log("host: incoming 抢占在飞 page（角色=被叫方）→ 已发 CreateConnectionCancel 让给 iPhone")
        } else {
            log("host: incoming connection request from ${event.addr.redacted()}（角色=被叫方，我方未发起 page）")
        }
        driver.hciSend(HciCommands.ACCEPT_CONNECTION_REQUEST, event.addr.toLittleEndian() + byteArrayOf(1))
        updatePairState(PairState.PAIRING, event.addr)
        // vc205 配对引导（UI 文案待演示稿确认后接线，本轮仅日志）：
        // iPhone 蓝牙列表里请选择 dongle 广播名「DiPlay CarPlay」，勿选本机系统蓝牙名。
        log("配对引导：请在 iPhone 上选择「${identity.name}」而非本机蓝牙名")
        setStatus("接受 ${event.addr.redacted()} 的连接请求")
    }

    private fun onDisconnectionComplete(event: HciEvent.DisconnectionComplete) {
        val addr = connections.remove(event.handle)
        outgoing.remove(event.handle)
        // vc212：链路断了，在飞的弹码请求一并作废（UI 侧 dismiss 回调走 answerPairConfirm 幂等空过）
        if (addr != null && pendingConfirm?.value == addr.value) {
            pendingConfirm = null
            pendingConfirmSince = 0
        }
        securing.remove(event.handle)
        secured.remove(event.handle)
        startedIap2.remove(event.handle)
        authRetries.remove(event.handle)
        aclOutstanding.remove(event.handle)
        // vc226：链路断了，iAP2 建链重试作废（等下一次链路就绪重新计数）
        if (iap2RetryHandle == event.handle) resetIap2Retry()
        synchronized(l2capLock) { l2cap.onLinkDisconnected(event.handle) }
        log("link 0x%03x disconnected (reason 0x%02x)".format(event.handle, event.reason))
        // vc227：对端主动断链（实测 iPhone reason 0x13 = Remote User Terminated）后自动回连，
        // 避免「配对成功后链路一闪就掉、随后主页一直待配对」的观感。
        armAutoReconnect(addr, AUTO_RECONNECT_AFTER_DROP_MILLIS)
    }

    private fun onAuthenticationComplete(event: HciEvent.AuthenticationComplete) {
        val addr = connections[event.handle] ?: return
        when {
            event.status == 0 -> {
                authRetries.remove(event.handle)
                // 不分方向主动请求加密：incoming 链路到此也已认证，加密成功会回
                // EncryptionChange → startIap2；已加密则控制器立即回报，无副作用。
                // vc212：SET_CONNECTION_ENCRYPTION 异步（CommandStatus + EncryptionChange，
                // 无 CommandComplete），只发不等——此前 enqueueCommand 白等 3s 卡命令线程。
                driver.hciSend(
                    HciCommands.SET_CONNECTION_ENCRYPTION,
                    HciCommands.le16(event.handle and 0xfff) + byteArrayOf(1),
                )
            }
            event.status == 6 -> { // PIN 或 Key Missing：清 key 重配对
                linkKeys.remove(addr.key())
                updatePairState(PairState.UNPAIRED, addr)
                if (securing.contains(event.handle)) {
                    enqueueCommand(HciCommands.DISCONNECT, HciCommands.le16(event.handle and 0xfff) + byteArrayOf(0x13))
                }
            }
            else -> {
                val attempts = (authRetries[event.handle]?.attempts ?: 0) + 1
                val delay = minOf(AUTH_RETRY_BASE_MILLIS shl (attempts - 1), AUTH_RETRY_MAX_MILLIS)
                authRetries[event.handle] = AuthRetry(attempts, System.currentTimeMillis() + delay)
                log("auth failed on 0x%03x (status ${event.status}); retry in ${delay}ms".format(event.handle))
            }
        }
    }

    private fun onNumberOfCompletedPackets(params: ByteArray) {
        if (params.isEmpty()) return
        val count = params[0].toInt() and 0xff
        var off = 1
        var freed = 0
        synchronized(aclLock) {
            repeat(count) {
                if (off + 4 > params.size) return
                val handle = le16(params, off) and 0xfff
                val completed = le16(params, off + 2)
                off += 4
                freed += completed
                aclOutstanding[handle] = maxOf(0, (aclOutstanding[handle] ?: 0) - completed)
            }
            aclCredits = minOf(ACL_CREDITS_MAX, aclCredits + freed)
            flushAclLocked()
        }
    }

    override fun onAclPacket(packet: UsbBtDriver.AclPacket) {
        val frame = reassembler.accept(packet) ?: return
        // vc214：ACL 线上取证（SDP 静默定位：对端应答是否到达）
        if (frame.size >= 4) {
            val cid = ((frame[3].toInt() and 0xff) shl 8) or (frame[2].toInt() and 0xff)
            log("acl rx handle=0x%03x cid=%d len=%d".format(packet.handle, cid, frame.size))
        }
        synchronized(l2capLock) { l2cap.onFrame(packet.handle, frame) }
    }

    override fun onTick() {
        // 认证重试到期重发；paging 超时清理（事件线程串行调用，无并发问题）
        val now = System.currentTimeMillis()
        // vc206：配对模式窗口收敛 —— 窗口内 WriteScanEnable(3) 可发现，窗外回 2（仅可连接）。
        // 配对成功（LinkKeyNotification）时窗口已清零，下一个 tick 自动回落。
        // vc217：已配对（有 link key）时常驻可发现（对齐 legacy 0.3.1 常驻广播）——实测 iPhone
        // 不应答我方 page（status 4 连满 4 轮），自动回连只能靠 iPhone 后台 inquiry 发现我们后
        // 主动发起；scan=2（不可发现）时 iPhone 永远看不到「DiPlay CarPlay」，回连无从谈起。
        if (phase == Phase.DISCOVERABLE && currentScanMode >= 0) {
            // vc238：总闸关闭 → 强制 scan=2：inquiry scan 关（iPhone 搜不到）、page scan 保留
            // （已配对 iPhone 仍可主动 page 过来走 CarPlay）。开且配对窗口/已配对 → 3。
            val want = if (!discoverableEnabled) {
                2
            } else if (now < pairingModeUntil || sharedLinkKeys.isNotEmpty()) {
                3
            } else {
                2
            }
            if (want != currentScanMode) {
                currentScanMode = want
                enqueueCommand(HciCommands.WRITE_SCAN_ENABLE, byteArrayOf(want.toByte()))
                log(
                    "scan mode -> $want（" + when {
                        !discoverableEnabled -> "总闸关闭·不可被搜索（仅 page scan 可回连）"
                        now < pairingModeUntil -> "配对模式·可发现"
                        sharedLinkKeys.isNotEmpty() -> "已配对·常驻可发现（等 iPhone 回连）"
                        else -> "可连接·不可发现"
                    } + "）",
                )
            }
        }
        // vc207：配对窗口内持续 inquiry（附近设备发现）；窗口关闭即取消。
        // vc209：轮间留 6s 纯监听窗（lastInquiryCompleteAt=0 时窗口一开立即首探）。
        // vc211：page/配对进行中不续新一轮 inquiry——实测 page 在飞 1s 后又 inquiry start
        // 抢射频，挤占寻呼时隙。pageForPairing 侧已先发 INQUIRY_CANCEL 停当轮。
        if (phase == Phase.DISCOVERABLE) {
            // vc238：总闸关闭时不再 inquiry（关闭后设备不可被搜索）；开着才按配对窗口探。
            val wantInquiry = discoverableEnabled && now < pairingModeUntil
            // vc215：主动回连重试窗口内同样压住 inquiry（射频让给寻呼）
            val pairingBusy = paging.get() != null || pairTarget != null || pageRetryAddr != null
            if (wantInquiry && !inquiryRunning && !pairingBusy && now - lastInquiryCompleteAt >= INQUIRY_LISTEN_GAP_MILLIS) {
                startInquiry()
            } else if (!wantInquiry && inquiryRunning) {
                inquiryRunning = false
                driver.hciSend(HciCommands.INQUIRY_CANCEL)
                log("inquiry cancel（配对窗口关闭）")
            }
        }
        // vc215：主动回连 page 重试到点重发（等 inquiry 空窗，避免射频互抢）
        // vc238：总闸关闭时丢弃尚未发完的重试（关 = 不主动 page）。
        val retryTarget = pageRetryAddr
        if (!discoverableEnabled) {
            if (retryTarget != null) {
                pageRetryAddr = null
                log("host: 总闸关闭，停止主动回连重试 ${retryTarget.redacted()}")
            }
        } else if (
            retryTarget != null && pageRetryAt > 0 && now >= pageRetryAt &&
            paging.get() == null && !inquiryRunning && phase == Phase.DISCOVERABLE
        ) {
            pageRetryAt = 0
            if (paging.compareAndSet(null, retryTarget)) {
                pagingSince = now
                setStatus("正在重试连接 ${retryTarget.redacted()}…")
                driver.hciSend(
                    HciCommands.CREATE_CONNECTION,
                    retryTarget.toLittleEndian() + HciCommands.le16(52248) + byteArrayOf(1, 0) + HciCommands.le16(0) + byteArrayOf(1),
                )
                log("host: active reconnect page retry ${retryTarget.redacted()}（剩 $pageRetryLeft 次）")
            }
        }
        // vc226：iAP2 建链重试（对照 FreeAuto 实测：它也先失败、靠 "retry 4 of SDP + RFCOMM
        // on the kept link" 才拿到信道，SDP 计数爬到 sdp=8；RFCOMM 还被 refused result=4 后重试成功）
        val iap2Handle = iap2RetryHandle
        if (iap2Handle >= 0 && iap2RetryAt > 0 && now >= iap2RetryAt) {
            iap2RetryAt = 0
            if (connections.containsKey(iap2Handle) && secured.contains(iap2Handle)) {
                log("iAP2 retry ${iap2RetryCount}/${IAP2_RETRY_MAX}：重新查 SDP + 建 RFCOMM")
                startIap2(iap2Handle)
            } else {
                log("iAP2 retry 作废：链路已不在（handle=0x%03x）".format(iap2Handle))
                iap2RetryHandle = -1
                iap2RetryCount = 0
            }
        }
        for ((handle, retry) in authRetries) {
            if (now >= retry.nextAt && connections.containsKey(handle)) {
                // vc211：AUTH 异步（CommandStatus + AuthenticationComplete），只发不等。
                driver.hciSend(HciCommands.AUTHENTICATION_REQUESTED, HciCommands.le16(handle and 0xfff))
                log("auth retry #${retry.attempts + 1} on 0x%03x".format(handle))
                return
            }
        }
        val pageStart = pagingSince
        if (pageStart > 0 && now - pageStart > PAGE_TIMEOUT_MILLIS) {
            val timedOut = paging.getAndSet(null)
            pagingSince = 0
            if (pairTarget != null) {
                log("host: pair target page 超时")
                pairTarget = null // vc207：主动配对 page 超时，清目标（UI 行态回可配对）
            }
            // vc215：主动回连 page 事件都没等到（30s）→ 同样走重试
            val retryAddr = pageRetryAddr
            if (timedOut != null && retryAddr != null && retryAddr.value == timedOut.value && pageRetryLeft > 0) {
                pageRetryLeft--
                pageRetryAt = now + PAGE_RETRY_GAP_MILLIS
                setStatus("回连 page 超时，2s 后重试（剩 $pageRetryLeft 次）")
                log("host: active reconnect page timeout ${timedOut.redacted()}（待重试，剩 $pageRetryLeft）")
            } else {
                pageRetryAddr = null
                pageRetryLeft = 0
                pageRetryAt = 0
                setStatus("连接超时")
            }
        }
        // vc212：弹码确认超时 → NEGATIVE + UNPAIRED（弹窗还在也会被 UI 侧 dismiss 回调幂等收口）。
        val confirmStart = pendingConfirmSince
        val confirmAddr = pendingConfirm
        if (confirmAddr != null && confirmStart > 0 && now - confirmStart > PAIR_CONFIRM_TIMEOUT_MILLIS) {
            pendingConfirm = null
            pendingConfirmSince = 0
            enqueueCommand(
                HciCommands.USER_CONFIRMATION_REQUEST_NEGATIVE_REPLY,
                confirmAddr.toLittleEndian(),
            )
            settlePairStateAfterFailure(confirmAddr) // vc213
            log("pair confirm timeout（30s 未确认）${confirmAddr.redacted()}")
        }
    }

    // ---- vc207：inquiry（附近设备发现）与主动配对 ----

    /** 配对窗口内启动一轮 inquiry（GIAC，约 10s；完成后 InquiryComplete 事件里视窗口续期再启动）。 */
    private fun startInquiry() {
        if (phase != Phase.DISCOVERABLE) return
        inquiryRunning = true
        // LAP=GIAC 0x9E8B33（线上 LSB first），时长 0x08×1.28s≈10.24s，响应数不限（0）
        driver.hciSend(
            HciCommands.INQUIRY,
            byteArrayOf(0x33, 0x8B.toByte(), 0x9E.toByte(), 0x08, 0x00),
        )
        log("inquiry start（发现附近设备，约 10s 一轮）")
    }

    /** inquiry 结果入快照（进程级；EIR 里取设备名，顺便记入 pairedNames 供配对列表用）。 */
    private fun onInquiryResult(event: HciEvent.InquiryResult) {
        val now = System.currentTimeMillis()
        for (dev in event.devices) {
            val key = dev.addr.key()
            val name = parseEirName(dev.eir) ?: pairedNames[key] ?: discoveredDevices[key]?.name
            discoveredDevices[key] = DiscoveredDevice(
                address = dev.addr.toString(),
                name = name,
                rssi = dev.rssi,
                classOfDevice = dev.classOfDevice,
                lastSeenMillis = now,
            )
            if (name != null) pairedNames[key] = name
        }
        // vc209：结果带设备明细（脱敏 addr），否则无法判断发现的是不是目标 iPhone。
        val detail = event.devices.joinToString("；") { dev ->
            val shown = discoveredDevices[dev.addr.key()]?.name ?: "?"
            "name=$shown addr=${dev.addr.redacted()} rssi=${dev.rssi ?: "-"}"
        }
        log("inquiry 结果 +${event.devices.size}（累计 ${discoveredDevices.size} 台）$detail")
    }

    /** EIR 里取设备名（type 0x09 全名 / 0x08 短名）；无则 null。 */
    private fun parseEirName(eir: ByteArray?): String? {
        if (eir == null) return null
        var off = 0
        while (off + 1 < eir.size) {
            val len = eir[off].toInt() and 0xff
            if (len == 0) break
            val type = eir[off + 1].toInt() and 0xff
            val dataOff = off + 2
            val dataLen = len - 1
            if (dataOff + dataLen > eir.size) break
            if ((type == 0x09 || type == 0x08) && dataLen > 0) {
                val text = String(eir, dataOff, dataLen, Charsets.UTF_8).trim()
                if (text.isNotEmpty()) return text
            }
            off += 1 + len
        }
        return null
    }

    /**
     * vc207：点击已发现设备 → 发起配对（dedicated bonding）。
     * 与 connectTo 不同：不要求已有 link key——无 key 时连上后由
     * [onConnectionComplete] 的统一 outgoing 分支发 AuthenticationRequested 触发 SSP。
     * 返回 false=当前不可发起（未广播/地址非法/已有 page 在飞）。
     */
    fun pageForPairing(address: String): Boolean {
        if (phase != Phase.DISCOVERABLE) return false
        val target = BdAddr.parseColon(address) ?: return false
        val hasKey = linkKeys[target.key()] != null
        if (!paging.compareAndSet(null, target)) return false
        // vc209：page 与 inquiry 互斥——发起配对前把在飞 inquiry 撤了，射频让给寻呼。
        if (inquiryRunning) {
            inquiryRunning = false
            driver.hciSend(HciCommands.INQUIRY_CANCEL)
            log("inquiry cancel（发起配对，射频让给 page）")
        }
        pairTarget = target.key()
        pagingSince = System.currentTimeMillis()
        // pkt=0xcc18 psrm=R1 role=allow（与 connectTo 同参）
        // vc212：CREATE_CONNECTION 异步（CommandStatus + ConnectionComplete），只发不等——
        // 此前 enqueueCommand 白等 3s（实测 `0x0405 await timeout`），恰好卡在 SSP 应答窗内。
        driver.hciSend(
            HciCommands.CREATE_CONNECTION,
            target.toLittleEndian() + byteArrayOf(0x18, 0xcc.toByte(), 0x01, 0x00, 0x00, 0x00, 0x01),
        )
        updatePairState(PairState.PAIRING, target)
        log(
            "host: pair connectTo: addr=${target.redacted()}（" +
                (if (hasKey) "有 link key，将快速回连" else "无 key，连上后触发 SSP 配对") + "）",
        )
        return true
    }

    override fun onTransportFailure(reason: String) {
        failWith(reason)
    }
}
