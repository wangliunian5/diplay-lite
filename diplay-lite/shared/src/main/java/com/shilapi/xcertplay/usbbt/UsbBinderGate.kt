package com.shilapi.xcertplay.usbbt

/**
 * vc246（USB-246-03）：USB binder 调用超时的进程级粘性门禁。
 *
 * 背景（T3 2026-11-05 现场）：`system_server` 一旦 D 态卡在 `usbdev_open`，App 侧任何
 * `UsbManager` 调用（getDeviceList / hasPermission / openDevice）都会无限阻塞，
 * 继续 open 只会把更多线程堆进内核 D 态。
 *
 * 语义：
 * - 任一 USB binder 调用耗时超过 [SLOW_CALL_MILLIS] → 置粘性 `suspect`；
 * - `suspect` 一旦置位**不再自动清除**（进程内永久禁止再 open），只能重启 App/车机；
 * - 调用方负责结合 sysfs 旁证（6-1 节点的 idVendor 仍为 0a12）判「D 态疑似」
 *   并向用户提示重启车机。
 *
 * 放在 shared 模块：USB 传输层与 App 层都要在同一进程内共享这一状态。
 */
object UsbBinderGate {
    /** 判据阈值：正常 USB binder 调用 <300ms，3s 足够区分「慢」与「内核卡死」。 */
    const val SLOW_CALL_MILLIS = 3_000L

    @Volatile
    var suspect: Boolean = false
        private set

    /** 触发来源（首条命中即固定，便于日志分诊）。 */
    @Volatile
    var reason: String = ""
        private set

    /**
     * 记录一次 USB 调用耗时；超过阈值即置粘性门禁。
     * 返回 true = **本次**首次置位（调用方据此只提示一次）。
     */
    fun noteCall(source: String, elapsedMillis: Long): Boolean {
        if (suspect) return false
        if (elapsedMillis < SLOW_CALL_MILLIS) return false
        synchronized(this) {
            if (suspect) return false
            suspect = true
            reason = "$source ${elapsedMillis}ms"
            return true
        }
    }

    fun describe(): String =
        if (suspect) "D 态疑似（$reason），已禁止后续 open" else "USB binder 调用正常"
}
