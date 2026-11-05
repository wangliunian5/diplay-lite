package com.shilapi.xcertplay.diag

import java.util.concurrent.atomic.AtomicInteger

/**
 * vc239（LOG-238-02 / LOG-238-03）：锚点归属上下文。**纯打点，不参与任何业务决策**。
 *
 * vc238 实测两个硬问题：
 * 1. 同一次连接观测到 89 ms 内两条 `ANCHOR connect-begin`（重复点击/双入口）；
 * 2. 7/7 有锚点但不可定位——`connect-begin` 比同轮 `link-ready` 还晚 2.6 s，
 *    9 轮重连无法归到具体某一代。
 *
 * 解决：给每条 ANCHOR 行统一追加 `session=<短id> gen=<n>`；gen 由本对象维护：
 * - 常驻广播先拿到链路（`link-ready` 先到）→ 该代记为「等连接消费」；
 * - 随后 `connect-begin` 若命中这条待消费链路，**沿用那一代**（顺序错配由此可读）；
 * - `connect-begin` 先到时先开新代，`link-ready` 归属这一代；
 * - 同一轮内 1.5 s 内的重复 `connect-begin` 只打第一次（同代只打一次）。
 *
 * API16 安全：只用 `AtomicInteger` 与 `System.currentTimeMillis()`。
 */
object AnchorLog {
    /** 同代 connect-begin 去重窗口（vc238 实测重复间隔 89 ms）。 */
    private const val DEDUPE_WINDOW_MS = 1_500L

    /** 常驻广播 link-ready 领先于用户点连接的归属复用窗口（vc238 实测 2.6 s，留足余量）。 */
    private const val LINK_READY_REUSE_MS = 120_000L

    /** 进程级短 id：用启动时刻毫秒数的低 6 位，单份日志内足以区分进程。 */
    private val sessionShortId: String = (System.currentTimeMillis() % 1_000_000L).toString()

    private val generation = AtomicInteger(0)

    /** 已连接链路（link-ready 已到）但还没有对应的 connect-begin 来消费它。 */
    private var pendingLinkReadyGen = 0
    private var pendingLinkReadyAtMs = 0L

    /** connect-begin 已发生、还在等同代 link-ready。 */
    private var connectWaitingForLink = false
    private var connectBeginAtMs = 0L

    /**
     * 连接开始锚点。返回 null = 同一轮的重复调用（1.5 s 内），调用方不再打第二条。
     * 有未消费的 link-ready 时沿用其代次，保证 connect-begin 与先到的 link-ready 同代。
     */
    @Synchronized
    fun beginConnect(): String? {
        val now = System.currentTimeMillis()
        if (connectBeginAtMs != 0L && now - connectBeginAtMs < DEDUPE_WINDOW_MS) return null
        connectBeginAtMs = now
        val pendingGen = pendingLinkReadyGen
        val adoptPending = pendingGen > 0 && now - pendingLinkReadyAtMs <= LINK_READY_REUSE_MS
        if (adoptPending) {
            generation.set(pendingGen)
        } else if (generation.get() == 0) {
            generation.set(1)
        } else {
            generation.incrementAndGet()
        }
        pendingLinkReadyGen = 0
        pendingLinkReadyAtMs = 0
        // 已消费先到的链路 → 本次连接不再等新的 link-ready；否则等本轮链路。
        connectWaitingForLink = !adoptPending
        return line("connect-begin")
    }

    /**
     * 常驻广播拿到 iPhone 链路。若本轮 connect-begin 还在等同代链路，沿用其代次；
     * 否则开新代并记为「待连接消费」，供稍后到达的 connect-begin 沿用。
     */
    @Synchronized
    fun linkReady(): String {
        val now = System.currentTimeMillis()
        if (connectWaitingForLink && connectBeginAtMs != 0L &&
            now - connectBeginAtMs <= LINK_READY_REUSE_MS
        ) {
            connectWaitingForLink = false
            pendingLinkReadyGen = 0
            pendingLinkReadyAtMs = 0
            return line("link-ready")
        }
        if (generation.get() == 0) generation.set(1) else generation.incrementAndGet()
        pendingLinkReadyGen = generation.get()
        pendingLinkReadyAtMs = now
        return line("link-ready")
    }

    /** 首帧上屏锚点：只归属当前代，不新开代（会话可能先有 link-ready，也可能先有 connect-begin）。 */
    @Synchronized
    fun firstFrame(): String = line("first-frame")

    /** 会话结束：清「等链路」状态，避免下一次连接误沿用上一代。 */
    @Synchronized
    fun onSessionEnded() {
        connectWaitingForLink = false
        connectBeginAtMs = 0L
    }

    /** 纯标记锚点（总闸/配对窗/交接/dongle 初始化等）：只归属当前代，不做状态迁移。 */
    @Synchronized
    fun mark(name: String): String = line(name)

    private fun line(name: String): String {
        if (generation.get() == 0) generation.set(1) // 初始化期锚点从第 1 代起，避免 gen=0
        return "ANCHOR $name session=$sessionShortId gen=${generation.get()}"
    }
}
