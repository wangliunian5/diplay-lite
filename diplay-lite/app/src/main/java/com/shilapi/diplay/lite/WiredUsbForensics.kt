package com.shilapi.diplay.lite

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * vc96：有线会话 root 诊断探针——内核级 USB 取证。
 *
 * 背景（FE-6 20261017 断链）：wedge 看门狗判死后重枚举发现不到 iPhone，Android 层
 * 只能看到「设备不在列表」，无法回答「谁先动手」（iPhone 侧复位退出 CarPlay 配置 vs
 * 车机 host 端口楔死）。内核 dmesg 有完整 USB 事件线（disconnect/reset/枚举错误/
 * MUSB wedge print），root 可读——这是区分两者的唯一铁证。
 *
 * 设计约束：
 *  - su 可用才启动（SessionService 已有 root 探测）；无 root 静默关闭，零成本
 *  - 常驻仅增量读 dmesg 的 USB 相关行（每 2s，su -c dmesg 全量拉取后本地过滤增量，
 *    4.x 内核 dmesg 环形缓冲一般 ≤64KB，全量拉取成本可接受；有的老 ROM 无 -c/--read-clear）
 *  - wedge/断链事件时抓 sysfs 设备树快照（挂起状态+驱动绑定）
 *  - 所有输出走 LiteLog（TAG=usbfx），进文件日志可被 Gitee 采集
 *  - su 调用复用 vc94 的超时模式：pump 线程 + exitValue 轮询，防 su 弹框挂死线程
 */
internal object WiredUsbForensics {

    private const val TAG = "usbfx"
    private const val POLL_INTERVAL_MILLIS = 2_000L
    private const val SU_TIMEOUT_MILLIS = 5_000L
    /** dmesg 增量过滤关键词：USB 事件 + NCM/以太 + MUSB/chipidea/dwc3 host 控制器。 */
    private const val DMESG_FILTER =
        "usb|USB|cdc_ncm|cdc_ether|musb|chipidea|dwc3|ehci|reset|disconnect"
    /** vc97⑤：内核时间戳提取（形如 [ 248.509207]，含 dmesg -t 无前缀格式兜底）。 */
    private val TS_PATTERN = Regex("\\[\\s*(\\d+\\.\\d+)\\]")

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var suOk = false

    /** 会话建立时调用：root 可用则启动增量探针；重复调用幂等。 */
    fun start(report: (String) -> Unit = { LiteLog.log(TAG, it) }) {
        if (!running.compareAndSet(false, true)) return
        // su 探测：5s 超时（防 su 弹框挂死），失败即静默关闭
        val id = suExec("id")
        suOk = id.contains("uid=0")
        if (!suOk) {
            report("probe disabled: su unavailable")
            running.set(false)
            return
        }
        report("probe started (dmesg usb filter every ${POLL_INTERVAL_MILLIS / 1000}s)")
        thread = Thread({
            // vc97⑤：内核时间戳增量去重——旧 lastLine 尾行匹配在部分 ROM（T3 实锤）上
            // 因输出顺序/格式抖动匹配失败，每拍全量重放启动期行（212K 日志中 263 行全是
            // 0.x 秒老行）。dmesg 时间戳单调递增，按 [sec.millis] 维护水位：ts ≤ 水位丢弃。
            var maxTsMillis = -1.0
            var polls = 0
            while (running.get()) {
                try {
                    val dmesg = suExec("dmesg")
                    if (dmesg.isNotEmpty()) {
                        val fresh = dmesg.lines()
                            .filter { it.isNotBlank() && Regex(DMESG_FILTER).containsMatchIn(it) }
                            .filter { line ->
                                val ts = TS_PATTERN.find(line)?.groupValues?.get(1)?.toDoubleOrNull()
                                if (ts == null) return@filter true
                                if (ts <= maxTsMillis) return@filter false
                                maxTsMillis = ts
                                true
                            }
                        if (fresh.isNotEmpty()) {
                            fresh.take(60).forEach { report("dmesg: ${it.trim()}") }
                            if (fresh.size > 60) report("dmesg: ... ${fresh.size - 60} more lines suppressed")
                        }
                    }
                    polls++
                    // 每 30 轮（约 1 分钟）心跳一次，确认探针活着（环形缓冲全 USB 静默时 dmesg 无新增行）
                    if (polls % 30 == 0) report("heartbeat polls=$polls")
                } catch (error: Throwable) {
                    report("poll error: ${error.javaClass.simpleName}: ${error.message}")
                }
                try {
                    Thread.sleep(POLL_INTERVAL_MILLIS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "usbfx-dmesg").apply { isDaemon = true; start() }
    }

    /** 断链/wedge 事件时调用：抓 sysfs 设备树快照（拓扑+挂起态+驱动绑定）+ dmesg 尾部。 */
    fun snapshot(reason: String, report: (String) -> Unit = { LiteLog.log(TAG, it) }) {
        if (!suOk) return
        try {
            report("snapshot begin reason=$reason")
            val devices = suExec(
                "for d in /sys/bus/usb/devices/*; do " +
                    "n=\${d##*/}; " +
                    "v=\$(cat \$d/idVendor 2>/dev/null); p=\$(cat \$d/idProduct 2>/dev/null); " +
                    "s=\$(cat \$d/power/runtime_status 2>/dev/null); " +
                    "drv=\$(readlink \$d/driver 2>/dev/null); " +
                    "echo \"\$n vid=\$v pid=\$p drv=\${drv##*/}\"; done",
            )
            devices.lines().filter { it.isNotBlank() }.take(30).forEach { report("sysfs: $it") }
            // vc97⑤：su -c 内管道在部分 ROM（T3 toybox）不可用（无 tail + 管道失效），
            // 改全量拉取后 Kotlin 侧截尾。
            val tail = suExec("dmesg").lines()
                .filter { it.isNotBlank() }
                .takeLast(120)
            val usbTail = tail.filter { Regex(DMESG_FILTER).containsMatchIn(it) }
            report("snapshot dmesg-tail lines=${usbTail.size}")
            usbTail.take(80).forEach { report("tail: ${it.trim()}") }
            report("snapshot end")
        } catch (error: Throwable) {
            report("snapshot error: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    /** 会话 teardown 时调用：停探针线程。 */
    fun stop() {
        if (!running.getAndSet(false)) return
        thread?.interrupt()
        thread = null
        LiteLog.log(TAG, "probe stopped")
    }

    private fun suExec(command: String): String = try {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val out = StringBuilder()
        val pump = Thread({
            runCatching { process.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
        }, "usbfx-su-pump").apply { isDaemon = true; start() }
        var exited = false
        var waited = 0
        while (!exited && waited < SU_TIMEOUT_MILLIS) {
            Thread.sleep(100)
            waited += 100
            try {
                process.exitValue()
                exited = true
            } catch (_: IllegalThreadStateException) {
            }
        }
        if (!exited) process.destroy()
        pump.join(500)
        out.toString()
    } catch (_: Exception) {
        ""
    }
}
