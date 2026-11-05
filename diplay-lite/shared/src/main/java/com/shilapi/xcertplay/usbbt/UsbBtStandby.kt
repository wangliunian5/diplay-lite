package com.shilapi.xcertplay.usbbt

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.diag.AnchorLog
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream

/**
 * vc192：外置 USB 蓝牙「常驻广播」保活。
 *
 * 为什么需要它 —— legacy 0.3.1 实测日志（2026-10-27 19:54~20:10，同车同 CM390）的基准流程：
 * ```
 * 19:54:09  [UsbBtBluetoothAdapter] USB Bluetooth enabled: bdAddr=… advertising=DiPlay-1A21
 * 19:58:10  [UsbBtBluetoothAdapter] incoming connection request from … CoD=0x7a020c   ← iPhone 主动连
 * 19:58:15  HCI authentication/encryption complete                                     ← SSP 加密
 * 20:00:58  SDP: iAP2 RFCOMM channel=1 / openRfcommStream: connected successfully
 * 19:58:26  handoff shutdown stage=HCI reset                                           ← 交接后关掉蓝牙
 * ```
 * 也就是说：**dongle 在 App 一起来就以自己的广播名常驻可发现，由 iPhone 主动发起连接**。
 *
 * 我们旧实现是「点了连接才 open dongle，然后 90s 窗口内等 iPhone 回连」，由此产生两个现象：
 * 1. 没点连接时 dongle 处于出厂/上次残留态，iPhone 蓝牙列表里看到的是 **CM390** 这种出厂名，
 *    而不是设置页里配的那个广播名；
 * 2. 每轮只有 90s 窗口，错过就要重新点连接。
 *
 * 本对象在「外置蓝牙」被选中时把 dongle 拉成常驻广播态（初始化 → Write Local Name → Scan Enable），
 * iPhone 回连并拿到 iAP2 RFCOMM DLC 后，把流交给 [UsbBtBootstrapStream.open]（控制器侧取用）；
 * 无人取用（用户没点连接）时通知 [onLinkReady]，由 App 自动发起一次无线连接，尽量对齐 legacy。
 */
object UsbBtStandby {
    private const val TAG = UsbBtSession.TAG

    /** 单轮常驻广播里「等 iPhone 回连」的窗口（长窗口，避免反复重开 dongle）。vc206 起 public：弹层倒计时总时长读它。 */
    const val LINK_WINDOW_MILLIS = 10 * 60_000L

    /** 拿到流后等控制器取用的窗口；超时后**不主动关 stream**，slot 保持主线程随时可取。
     *  实测（vc230）：iPhone 手动连过来后 standby publish 了流，但主线程可能已经 teardown 不在等待；
     *  之前 60s 超时就关 stream、重新 openDirect → 导致下一次主线程 awaitStream 拿到的是重开的新流，
     *  或者重开时 page/SDP 失败。现在 slot 保持住，主线程下一次 bring-up 调 awaitStream 能立即取到。 */
    const val HANDOFF_WAIT_MILLIS = 120_000L

    /** 失败重试间隔（dongle 重枚举/授权未完成等瞬时故障）。 */
    private const val RETRY_DELAY_MILLIS = 2_000L

    /** vc232：交接后轮询「控制器是否已 close」的间隔（close 时会 notifyAll 立即唤醒）。 */
    private const val HANDOFF_POLL_MILLIS = 1_000L

    /**
     * vc236（BT-235-01）：新一代 loop 开播前，等上一代交出的流 close 的最长时间。
     * 典型场景：Activity 销毁 → 旧 loop 先退出，但控制器（CarPlay 会话）仍持链路；
     * 若此时立刻 openDirect，新实例会抢在旧 driver 释放前拿接口（vc235 实测新 open
     * 早 66/68ms → `open 抢占` + `0x1001` 白等 3s）。超时则本轮跳过重开、稍后重试，
     * 避免死等。
     */
    private const val PREV_HANDOFF_CLOSE_WAIT_MILLIS = 15_000L

    /**
     * vc233（BT-232-02）：控制器 close 之后、重新 openDirect 之前的静默窗。
     *
     * 实测（vc232 `15:09:39.608~.613`）：旧 driver 的 `interface #0 released` 与新实例的
     * `forceClaim(0)` 只隔 14~17 ms —— 新 driver 抢在旧实例收尾前拿到接口，首命令
     * `ReadLocalVersion(0x1001)` 拿不到应答，白等 3000 ms 后才继续 15 步初始化。
     * 留 500 ms 让内核与旧 driver 收尾，可消除这次无谓等待与 `open 抢占` 告警。
     * 只作用于「交接后重开」节奏，正常首启（无交接）不受影响。
     */
    private const val RECLAIM_SETTLE_MILLIS = 500L

    // 用 java.lang.Object 显式类型：Kotlin 的 Any 上取不到 wait/notifyAll。
    private val lock = java.lang.Object()

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    private var thread: Thread? = null

    /**
     * vc207：代际令牌（防僵尸循环）。实测根因：stop() 的 join(1000) 等不完阻塞在
     * openDirect 里的旧循环，随后 start() 把 isRunning 重新置 true，旧循环醒来后把
     * 自己当活实例继续跑 → 两个 loop 互抢 dongle（forceClaim 互夺、配对事件进不来）。
     * start/stop 各 bump 一次；loop/publish/awaitTaken/sleepQuietly 全部校验自己那一代，
     * 失配即退出/拒绝，僵尸循环自愈。
     */
    @Volatile
    private var generation = 0

    @Volatile
    private var linkName: String = "DiPlay 车机"

    /** 状态行（设置页展示；空串表示未启动）。 */
    @Volatile
    var statusText: String = ""
        private set

    @Volatile
    private var onLinkReady: (() -> Unit)? = null

    /** vc207：保持常驻广播（默认开；关=空闲一轮后退出循环，省电/防老车机拖慢）。 */
    @Volatile
    private var keepAliveBroadcast = true

    /** 控制器待取的流（同一时刻最多一条）。 */
    private var slot: BlockingDuplexByteStream? = null

    /**
     * vc232：已交给控制器的流（非 null = 所有权已转移，loop 暂停迭代）。
     * 只做「谁持有」的记账，close 由控制器负责（close 后 loop 恢复常驻广播）。
     */
    @Volatile
    private var handedOff: HandoffStream? = null

    /**
     * 启动常驻广播（幂等）。仅在设置里选中「外置蓝牙」时调用。
     * [onDiagnostic] 会拿到全过程逐级状态（进 LiteLog 上云）；
     * [onLinkReady] 在 iPhone 已回连但无人取流时回调（用于自动发起无线连接）。
     */
    fun start(
        context: Context,
        name: String,
        onDiagnostic: ((String) -> Unit)? = null,
        onLinkReady: (() -> Unit)? = null,
        keepAlive: Boolean = true,
    ) {
        linkName = name.ifBlank { "DiPlay 车机" }
        this.onLinkReady = onLinkReady
        this.keepAliveBroadcast = keepAlive // vc207：配对窗「保持常驻广播」开关（关=空闲一轮后停广播）
        // vc213：注入配对持久化（SharedPreferences）并恢复已保存 link key —— 已配对则直接
        // PAIRED/isUsable=true，不再重新配对；清除配对走 clearPairing（含持久化删除）。
        UsbBtHost.pairingStore = SharedPrefsPairingStore(context.applicationContext)
        UsbBtHost.restorePairing()
        val myGeneration: Int
        synchronized(lock) {
            if (isRunning) {
                lock.notifyAll()
                return
            }
            isRunning = true
            generation++
            myGeneration = generation
        }
        val appContext = context.applicationContext ?: context
        val worker = Thread({ loop(appContext, onDiagnostic, myGeneration) }, "usbbt-standby").apply {
            isDaemon = true
        }
        thread = worker
        worker.start()
        diag(onDiagnostic, "usbbt-standby: 常驻广播启动，广播名「$linkName」")
    }

    /** 停止常驻广播并释放 dongle（切回「车机蓝牙」时调用）。 */
    fun stop() {
        val worker: Thread?
        synchronized(lock) {
            if (!isRunning) return
            isRunning = false
            generation++ // vc207：使仍阻塞在 openDirect 里的旧循环失效（ABA 自愈）
            val pending = slot
            slot = null
            runCatching { pending?.close() }
            // vc232：已交出的流归控制器所有，不在这里强关（否则会腰斩正在跑的 CarPlay 会话）；
            // loop 会被 generation 变更唤醒后自己退出，链路随控制器 teardown 自然释放。
            handedOff?.let {
                Log.i(TAG, "usbbt-standby: 停止时链路仍归控制器所有（close 由控制器负责）")
            }
            lock.notifyAll()
            worker = thread
            thread = null
        }
        statusText = "常驻广播已停止"
        runCatching { worker?.join(1000) }
    }

    /**
     * 控制器侧取流：等常驻广播交出 iPhone 链路，超时返回 null（调用方决定如何报错）。
     * 取走后由控制器负责 [BlockingDuplexByteStream.close]（close 会连带停 host 释放 dongle，
     * 本对象随后自动重新广播）。
     */
    fun awaitStream(timeoutMillis: Long): BlockingDuplexByteStream? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        synchronized(lock) {
            while (true) {
                slot?.let {
                    slot = null
                    lock.notifyAll()
                    return it
                }
                val remain = deadline - System.currentTimeMillis()
                if (remain <= 0 || !isRunning) return null
                try {
                    lock.wait(minOf(remain, 1_000L))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
    }

    /** 设置页状态行：未启动 / 当前逐级状态。 */
    fun describe(): String = when {
        !isRunning -> "常驻广播未启动（需在设置里选「外置蓝牙」）"
        statusText.isNotBlank() -> statusText
        else -> "常驻广播启动中…"
    }

    /**
     * vc205：外置蓝牙是否可用 = 常驻广播在跑 且 已与 iPhone 配对成功（host 侧状态机：
     * 未配对 / 配对中 / 已配对，日志 `host: pair state=… remote=…`）。
     * 未配对成功前外置链路不对外宣告可用。vc206 起由主页门槛消费（主流程灰化+去配对）。
     * 配对引导：iPhone 蓝牙列表里请选「DiPlay CarPlay」而非本机系统蓝牙名。
     */
    val isUsable: Boolean
        get() = isRunning && UsbBtHost.pairStateSnapshot == PairState.PAIRED

    /**
     * vc212：SSP 弹码确认出口转发（UsbBtHost 为 internal，app 侧经这里注册）。
     * UI 回调三参 = 对端冒号 MAC、6 位确认数字、答案回调（true=确认 / false=取消，
     * 任意线程可调）。不注册（null）= 无 UI 接管，host 一律拒绝。
     */
    // 转发属性（底层 UsbBtHost.pairConfirmHandler 已 @Volatile；计算属性不能再加注解）
    var pairConfirmHandler: ((addr: String, digits: String, answer: (Boolean) -> Unit) -> Unit)?
        get() = UsbBtHost.pairConfirmHandler
        set(value) {
            UsbBtHost.pairConfirmHandler = value
        }

    /** vc206：配对状态快照（UNPAIRED/PAIRING/PAIRED，进程级；UI 配对窗数据源）。 */
    val pairState: PairState
        get() = UsbBtHost.pairStateSnapshot

    /** vc206：已配对对端（redacted MAC；null=无）。 */
    val pairedRemote: String?
        get() = UsbBtHost.pairedRemoteSnapshot

    /** vc206：进入配对模式 —— 可发现开 10 分钟窗口（D3）；配对成功/取消/超时自动关。 */
    fun enterPairingMode() = UsbBtHost.requestPairingMode(LINK_WINDOW_MILLIS)

    /** vc206：退出配对模式（可发现立即关闭）。 */
    fun exitPairingMode() = UsbBtHost.cancelPairingMode()

    /**
     * vc238：「保持常驻广播」升级为外置蓝牙总闸。
     *
     * - false（关）：立即发 Write_Scan_Enable(0x02) —— inquiry scan 关（iPhone 搜索列表看不到本机），
     *   **page scan 保留**（已配对 iPhone 仍能从系统蓝牙设置里主动连上）；host 侧此后不再 inquiry、
     *   不再自动 page（armAutoReconnect / page 重试全部跳过）。
     * - true（开）：恢复 Write_Scan_Enable(0x03)（配对窗口/已配对时），常驻广播循环照旧。
     *
     * 返回 false = 控制器正被占用（控制器持有交接流 / 正在配对 / page 在飞），本次未改任何状态。
     * 调用方负责提示并把开关弹回原状，避免出现「界面显示关了、实际还开着」。
     */
    fun setDiscoverable(enabled: Boolean): Boolean {
        if (controllerHoldsLink()) {
            Log.i(TAG, "usbbt standby: discoverable switch refused（控制器占用中，保持原状态）")
            return false
        }
        UsbBtHost.setDiscoverableEnabled(enabled)
        val applied = UsbBtHost.applyScanModeNow()
        Log.i(
            TAG,
            "usbbt standby: discoverable ${if (enabled) "on" else "off"}" +
                "（Write_Scan_Enable=0x${if (enabled) "03" else "02"}，立即下发=$applied）",
        )
        return true
    }

    /**
     * vc238：App 启动 / 常驻广播重建时按配置恢复总闸状态（不发命令、不做占用检查）。
     * 初始化照常执行（init 末步本身就是 scan=2），只是不再自动 inquiry / 自动 page。
     */
    fun restoreDiscoverable(enabled: Boolean) {
        UsbBtHost.setDiscoverableEnabled(enabled)
        Log.i(TAG, "usbbt standby: restore discoverable=${if (enabled) "on" else "off"}（按配置）")
    }

    /** vc238：总闸切换的占用判据（交接流未 close / 待取流 / 配对中 / ACL 链路或 page 在飞）。 */
    private fun controllerHoldsLink(): Boolean {
        val hold = handedOff
        if (hold != null && !hold.isClosed) return true
        // slot 已 publish 未取走 = iPhone 的 ACL 链路已在，同样算占用。
        val pending = synchronized(lock) { slot != null }
        if (pending) return true
        if (UsbBtHost.pairStateSnapshot == PairState.PAIRING) return true
        return UsbBtHost.isLinkBusy()
    }

    /** vc206：配对模式剩余毫秒（<=0 = 未开启/已结束；弹层倒计时读它）。 */
    fun pairingRemainingMillis(): Long = UsbBtHost.pairingRemainingMillis()

    /** vc206：已配对设备只读快照（UI 列表数据源；不暴露可变 map）。 */
    fun pairedDevices(): List<PairedDevice> = UsbBtHost.pairedDevicesSnapshot()

    /** vc206：清除配对 —— 删全部 link key + PairState 回 UNPAIRED。 */
    fun clearPairing() = UsbBtHost.clearPairing()

    /** vc206：dongle 本机地址（冒号 MAC；初始化完成后非空）。 */
    fun dongleAddress(): String? = UsbBtHost.localAddressSnapshot

    /** vc207：附近已发现设备（inquiry 进程级快照，最近见到的排前面）。 */
    fun discoveredDevices(): List<DiscoveredDevice> = UsbBtHost.discoveredDevicesSnapshot()

    /** vc207：inquiry 进行中（配对窗口开着时为 true）。 */
    val inquiryActive: Boolean get() = UsbBtHost.isInquiryActive()

    /** vc207：点击已发现设备 → 发起配对。false=不可发起（未广播/已有 page 在飞/地址非法）。 */
    fun startPairing(address: String): Boolean = UsbBtHost.startPairing(address)

    /** vc207：我方主动配对目标 addr.key()（UI 行态「配对中」；null=未发起）。 */
    val pairTarget: String? get() = UsbBtHost.pairTargetSnapshot()

    /** vc207：dongle 芯片/固件信息（ReadLocalVersion 实读，如「CSR · HCI 4.0 · LMP 4.0」）。 */
    fun dongleInfo(): String? = UsbBtHost.dongleInfoSnapshot

    // ---------- 保活主循环 ----------

    private fun loop(context: Context, onDiagnostic: ((String) -> Unit)?, myGeneration: Int) {
        var failures = 0
        while (isRunning && myGeneration == generation) {
            // vc236（BT-235-01）：上一代交出的流可能还没关（Activity 销毁让旧 loop 先退出，
            // 而控制器仍持链路）。先等它 close + 静默窗，再 openDirect；超时则本轮跳过，
            // 避免新实例抢在旧 driver 释放前 forceClaim 接口。
            val stale = handedOff
            if (stale != null) {
                if (stale.isClosed) {
                    handedOff = null
                } else {
                    // vc237（BT-235-01 观测）：等待起点/终点/超时都带 elapsed，便于下轮判断余量。
                    val waitStartMs = System.currentTimeMillis()
                    diag(onDiagnostic, "usbbt-standby: 上一代交接流未释放，先等其 close 再重开（limit=${PREV_HANDOFF_CLOSE_WAIT_MILLIS}ms）")
                    awaitPreviousHandoffClosed(stale, myGeneration)
                    val waitedMs = System.currentTimeMillis() - waitStartMs
                    if (!isRunning || myGeneration != generation) break
                    if (!stale.isClosed) {
                        diag(onDiagnostic, "usbbt-standby: 等待超时，交接流仍未释放（elapsed=${waitedMs}ms，limit=${PREV_HANDOFF_CLOSE_WAIT_MILLIS}ms），本轮不重开广播")
                        sleepQuietly(RETRY_DELAY_MILLIS, myGeneration)
                        continue
                    }
                    handedOff = null
                    diag(onDiagnostic, "usbbt-standby: 上一代交接流已 close（elapsed=${waitedMs}ms）")
                    // 释放后同样留静默窗，让内核与旧 driver 收尾干净。
                    sleepQuietly(RECLAIM_SETTLE_MILLIS, myGeneration)
                    if (!isRunning || myGeneration != generation) break
                    diag(onDiagnostic, "usbbt-standby: 上一代链路已释放，重新开播")
                }
            }
            try {
                val raw = UsbBtBootstrapStream.openDirect(
                    context,
                    linkName,
                    onDiagnostic ?: {},
                    LINK_WINDOW_MILLIS,
                )
                failures = 0
                // vc232：包一层只为记 close —— 控制器取走这条流后 loop 必须停迭代等它 close；
                // 否则下一轮 openDirect 的 forceClaim 会把正在跑 RFCOMM/iAP2 的 driver 顶掉
                // （vc231 实测：publish 后 33ms loop 就重开 dongle → ACL 立刻断，主线程卡在网链路）。
                val stream = HandoffStream(raw) { synchronized(lock) { lock.notifyAll() } }
                if (!publish(stream, myGeneration)) {
                    runCatching { stream.close() }
                    continue
                }
                diag(onDiagnostic, "usbbt-standby: iPhone 链路已就绪，等待控制器取用")
                // 日志头段锚点：首次连上（固定文案，可 grep）。直接走诊断出口，不覆盖 UI 状态行。
                // vc239（LOG-238-02）：带 session/gen；若本轮 connect-begin 已在等链路则归属同一代。
                val linkReadyAnchor = AnchorLog.linkReady()
                Log.i(TAG, linkReadyAnchor)
                onDiagnostic?.let { runCatching { it(linkReadyAnchor) } }
                runCatching { onLinkReady?.invoke() }

                // 1) 等控制器取走（窗口 HANDOFF_WAIT_MILLIS）。
                val taken = awaitTaken(HANDOFF_WAIT_MILLIS, myGeneration)
                if (!isRunning || myGeneration != generation) {
                    closeSlot(stream) // stop()：slot 可能仍指向这条流
                    break
                }
                if (!taken) {
                    // 超时仍无人取。
                    if (!keepAliveBroadcast) {
                        diag(onDiagnostic, "usbbt-standby: 「保持常驻广播」已关，链路放弃")
                        closeSlot(stream)
                        isRunning = false
                        break
                    }
                    // vc231 语义保留：不关 stream、不清 slot，主线程下一次 bring-up 能直接取到。
                    diag(onDiagnostic, "usbbt-standby: iPhone 链路暂无人取，保持就绪（slot 不释放）")
                    synchronized(lock) {
                        while (isRunning && myGeneration == generation && slot === stream) {
                            try {
                                lock.wait(HANDOFF_POLL_MILLIS)
                            } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                                break
                            }
                        }
                    }
                    if (!isRunning || myGeneration != generation) {
                        closeSlot(stream)
                        break
                    }
                }

                // 2) 控制器已取走（slot 已空）→ 所有权转移：loop 暂停迭代，等控制器 close 再恢复广播。
                handedOff = stream
                diag(onDiagnostic, "usbbt-standby: 流已交出，暂停迭代（等控制器 close 后恢复常驻广播）")
                // vc239（LOG-238-02）：所有权交接开始锚点（带 session/gen）。
                val handoffBeginAnchor = AnchorLog.mark("handoff-begin")
                Log.i(TAG, handoffBeginAnchor)
                onDiagnostic?.let { runCatching { it(handoffBeginAnchor) } }
                awaitHandoffClosed(stream, myGeneration)
                // vc236（BT-235-01）：只有真正 close 才清引用。若是本代先被 stop（Activity
                // 销毁）而控制器还没关，保留 handedOff 给下一代 loop 先等它释放。
                if (stream.isClosed) handedOff = null
                if (!isRunning || myGeneration != generation) {
                    Log.i(TAG, "usbbt-standby: 交接期间已停止（g=$myGeneration/当前 g=$generation），不再重开广播")
                    break
                }
                diag(onDiagnostic, "usbbt-standby: 流 close，恢复常驻广播")
                // vc239（LOG-238-02）：所有权交接结束锚点（控制器已 close，链路归还）。
                val handoffEndAnchor = AnchorLog.mark("handoff-end")
                Log.i(TAG, handoffEndAnchor)
                onDiagnostic?.let { runCatching { it(handoffEndAnchor) } }
                // vc233（BT-232-02）：重开前静默 500ms，让内核与旧 driver 把接口释放干净，
                // 避免 forceClaim 抢在 release 之前 → 首命令 ReadLocalVersion 白等 3s。
                // sleepQuietly 内部每 200ms 校验 isRunning 与代际，stop()/换轮即刻退出，
                // 不会产生僵尸循环（vc206 教训）。
                sleepQuietly(RECLAIM_SETTLE_MILLIS, myGeneration)
                if (!isRunning || myGeneration != generation) {
                    Log.i(TAG, "usbbt-standby: 静默窗期间已停止（g=$myGeneration/当前 g=$generation），不再重开广播")
                    break
                }
            } catch (error: Throwable) {
                failures++
                statusText = error.message ?: "常驻广播失败"
                diag(onDiagnostic, "usbbt-standby: 本轮失败（第 $failures 次）: ${error.message}")
                sleepQuietly(
                    if (failures >= 3) RETRY_DELAY_MILLIS * 2 else RETRY_DELAY_MILLIS,
                    myGeneration,
                )
            }
        }
        if (myGeneration != generation) {
            Log.i(TAG, "usbbt-standby: 旧代际（g=$myGeneration）循环退出（当前 g=$generation）")
        }
        statusText = "常驻广播已停止"
        diag(onDiagnostic, "usbbt-standby: 线程退出")
    }

    /**
     * vc232：关闭并清掉槽位（幂等）。关闭动作放在锁外——host.stop() 会 join 命令线程（最多 3.5s），
     * 不能持锁做。
     */
    private fun closeSlot(stream: BlockingDuplexByteStream) {
        synchronized(lock) {
            if (slot === stream) slot = null
        }
        runCatching { stream.close() }
        synchronized(lock) { lock.notifyAll() }
    }

    /**
     * vc232：所有权已转移，阻塞等控制器 close。close 会 notifyAll 立刻唤醒；
     * 兜底每秒校验一次代际/停止标记，防僵尸循环（vc206 双实例教训）。
     */
    private fun awaitHandoffClosed(stream: HandoffStream, myGeneration: Int) {
        synchronized(lock) {
            while (isRunning && myGeneration == generation && !stream.isClosed) {
                try {
                    lock.wait(HANDOFF_POLL_MILLIS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    /**
     * vc236（BT-235-01）：跨代等「上一代交出的流」close。与 [awaitHandoffClosed] 的区别是
     * 带总时长上限，且代际/停止校验照旧——超时返回后由调用方跳过本轮重开，不死等。
     */
    private fun awaitPreviousHandoffClosed(stream: HandoffStream, myGeneration: Int) {
        val deadline = System.currentTimeMillis() + PREV_HANDOFF_CLOSE_WAIT_MILLIS
        synchronized(lock) {
            while (isRunning && myGeneration == generation && !stream.isClosed) {
                val remain = deadline - System.currentTimeMillis()
                if (remain <= 0) return
                try {
                    lock.wait(minOf(remain, HANDOFF_POLL_MILLIS))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    private fun publish(stream: BlockingDuplexByteStream, myGeneration: Int): Boolean {
        synchronized(lock) {
            if (!isRunning || myGeneration != generation) return false
            slot = stream
            lock.notifyAll()
            return true
        }
    }

    /** 等控制器把流取走；被取走返回 true。 */
    private fun awaitTaken(maxMillis: Long, myGeneration: Int): Boolean {
        val deadline = System.currentTimeMillis() + maxMillis
        synchronized(lock) {
            while (slot != null && isRunning && myGeneration == generation) {
                val remain = deadline - System.currentTimeMillis()
                if (remain <= 0) return false
                try {
                    lock.wait(minOf(remain, 1_000L))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            return slot == null
        }
    }

    private fun sleepQuietly(millis: Long, myGeneration: Int) {
        val deadline = System.currentTimeMillis() + millis
        while (isRunning && myGeneration == generation) {
            val remain = deadline - System.currentTimeMillis()
            if (remain <= 0) return
            try {
                Thread.sleep(minOf(remain, 200L))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun diag(onDiagnostic: ((String) -> Unit)?, line: String) {
        statusText = line
        Log.i(TAG, line)
        if (onDiagnostic != null) runCatching { onDiagnostic(line) }
    }

    /**
     * vc232：交给控制器后的所有权包装。
     * 语义完全转发给下层流（[UsbBtRfcommStream]：send/recv/close 行为不变），
     * 只多做一件事：close 结束（下层**真正释放**）后置 [isClosed] 并唤醒 standby loop，
     * 让它在控制器释放链路后恢复常驻广播（若此时已 stop/换轮，循环按代际自行退出）。
     *
     * vc234（BT-232-02 真修）：[isClosed] 此前在 `delegate.close()` **之前**置位，导致
     * standby 一看到它就结束等待、把 500ms 静默与下层 teardown 并行——而
     * `host.stop()`（Disconnect→ScanEnable(0)→HCI Reset→driver.close→join）实测要 ~520ms，
     * 静默被 teardown 吃掉，新 open 仍与旧 `driver stopped; interface #0 released` 重叠 8~57ms，
     * 从而复现 `open 抢占` + `ReadLocalVersion(0x1001) await timeout 3000ms`（vc233 实测 5/6 轮）。
     * 现在：幂等用独立 [closing] CAS，[isClosed] 只在 `delegate.close()` 返回后（finally）置位，
     * 静默窗从此「真正释放」起算。
     */
    private class HandoffStream(
        private val delegate: BlockingDuplexByteStream,
        private val onClosed: () -> Unit,
    ) : BlockingDuplexByteStream {
        @Volatile
        var isClosed: Boolean = false
            private set

        // vc234：close 入口幂等（防并发双关）；与 isClosed（已完成）区分。
        private val closing = java.util.concurrent.atomic.AtomicBoolean(false)

        override fun send(data: ByteArray) = delegate.send(data)

        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? =
            delegate.recv(maxBytes, timeoutMillis)

        override fun close() {
            if (!closing.compareAndSet(false, true)) return
            try {
                delegate.close()
            } finally {
                // 只有下层释放完才置位：standby 的 awaitHandoffClosed 以此为「可重开」信号。
                isClosed = true
                runCatching { onClosed() }
            }
        }
    }
}

/**
 * vc213：配对持久化（SharedPreferences；API16 安全）。
 * 布局：prefs "usbbt_pairing"，`key_<12hexAddr>` = 32 位 hex link key，`name_<12hexAddr>` = 对端名。
 */
private class SharedPrefsPairingStore(context: Context) : UsbBtPairingStore {
    private val prefs = context.getSharedPreferences("usbbt_pairing", Context.MODE_PRIVATE)

    override fun loadAll(): Map<String, Pair<ByteArray, String?>> {
        val out = LinkedHashMap<String, Pair<ByteArray, String?>>()
        for ((k, v) in prefs.all) {
            if (!k.startsWith("key_") || v !is String) continue
            val key = hexToBytes(v) ?: continue
            if (key.size != 16) continue
            val addrKey = k.substring(4)
            out[addrKey] = key to prefs.getString("name_$addrKey", null)
        }
        return out
    }

    override fun save(addrKey: String, linkKey: ByteArray, name: String?) {
        val e = prefs.edit().putString("key_$addrKey", toHex(linkKey))
        if (name != null) e.putString("name_$addrKey", name)
        e.apply()
    }

    override fun saveName(addrKey: String, name: String) {
        prefs.edit().putString("name_$addrKey", name).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private fun toHex(data: ByteArray): String =
        data.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return try {
            ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (_: Throwable) {
            null
        }
    }
}
