package com.shilapi.xcertplay.usbbt

import android.content.Context
import android.util.Log
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

    /** 单轮常驻广播里「等 iPhone 回连」的窗口（长窗口，避免反复重开 dongle）。 */
    private const val LINK_WINDOW_MILLIS = 10 * 60_000L

    /** 拿到流后等控制器取用的窗口；超时则释放 dongle 重新广播。 */
    const val HANDOFF_WAIT_MILLIS = 60_000L

    /** 失败重试间隔（dongle 重枚举/授权未完成等瞬时故障）。 */
    private const val RETRY_DELAY_MILLIS = 2_000L

    // 用 java.lang.Object 显式类型：Kotlin 的 Any 上取不到 wait/notifyAll。
    private val lock = java.lang.Object()

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    private var thread: Thread? = null

    @Volatile
    private var linkName: String = "DiPlay 车机"

    /** 状态行（设置页展示；空串表示未启动）。 */
    @Volatile
    var statusText: String = ""
        private set

    @Volatile
    private var onLinkReady: (() -> Unit)? = null

    /** 控制器待取的流（同一时刻最多一条）。 */
    private var slot: BlockingDuplexByteStream? = null

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
    ) {
        linkName = name.ifBlank { "DiPlay 车机" }
        this.onLinkReady = onLinkReady
        synchronized(lock) {
            if (isRunning) {
                lock.notifyAll()
                return
            }
            isRunning = true
        }
        val appContext = context.applicationContext ?: context
        val worker = Thread({ loop(appContext, onDiagnostic) }, "usbbt-standby").apply {
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
            val pending = slot
            slot = null
            runCatching { pending?.close() }
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

    // ---------- 保活主循环 ----------

    private fun loop(context: Context, onDiagnostic: ((String) -> Unit)?) {
        var failures = 0
        while (isRunning) {
            try {
                val stream = UsbBtBootstrapStream.openDirect(
                    context,
                    linkName,
                    onDiagnostic ?: {},
                    LINK_WINDOW_MILLIS,
                )
                failures = 0
                if (!publish(stream)) {
                    runCatching { stream.close() }
                    continue
                }
                diag(onDiagnostic, "usbbt-standby: iPhone 链路已就绪，等待控制器取用")
                runCatching { onLinkReady?.invoke() }
                if (!awaitTaken(HANDOFF_WAIT_MILLIS)) {
                    // 没人在用（用户没点连接）⇒ 释放并重新广播，让 iPhone 下次还能连上。
                    synchronized(lock) {
                        if (slot === stream) slot = null
                    }
                    runCatching { stream.close() }
                    diag(onDiagnostic, "usbbt-standby: iPhone 链路无人取用，已释放并重新广播")
                }
            } catch (error: Throwable) {
                failures++
                statusText = error.message ?: "常驻广播失败"
                diag(onDiagnostic, "usbbt-standby: 本轮失败（第 $failures 次）: ${error.message}")
                sleepQuietly(if (failures >= 3) RETRY_DELAY_MILLIS * 2 else RETRY_DELAY_MILLIS)
            }
        }
        statusText = "常驻广播已停止"
        diag(onDiagnostic, "usbbt-standby: 线程退出")
    }

    private fun publish(stream: BlockingDuplexByteStream): Boolean {
        synchronized(lock) {
            if (!isRunning) return false
            slot = stream
            lock.notifyAll()
            return true
        }
    }

    /** 等控制器把流取走；被取走返回 true。 */
    private fun awaitTaken(maxMillis: Long): Boolean {
        val deadline = System.currentTimeMillis() + maxMillis
        synchronized(lock) {
            while (slot != null && isRunning) {
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

    private fun sleepQuietly(millis: Long) {
        val deadline = System.currentTimeMillis() + millis
        while (isRunning) {
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
}
