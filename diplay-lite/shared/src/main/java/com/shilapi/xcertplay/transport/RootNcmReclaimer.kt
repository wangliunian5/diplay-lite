// 1.1.3（root 抢回）：有线建链 claim NCM 接口失败时，若车机有 root（su 可用），
// 用 sysfs unbind 踢掉内核 cdc_ncm 驱动对 iPhone NCM 接口的抢占后重试。
// 依据：AC822X/t3/at8317 类 ROM 内核 CDC-NCM 驱动在枚举瞬间抓走 iPhone NCM 数据接口，
// 用户态 claimInterface 报 "could not claim the NCM data interface"，无 root 无解。
package com.shilapi.xcertplay.transport

import android.util.Log

internal object RootNcmReclaimer {
    private const val TAG = IphoneCarPlayConfiguration.TAG
    private const val DRIVER_DIR = "/sys/bus/usb/drivers/cdc_ncm"
    private val IFACE = Regex("""\d+-[\d.]+:\d+\.\d+""")

    /** 1.0.3 同款判定：无 su 的 ROM 上 exec 抛 IOException / 退出码非 0，直接判不可用。 */
    fun reclaim(reason: String): Boolean {
        Log.i(TAG, "root ncm reclaim attempt: $reason")
        if (!runSu("echo probe-ok").contains("probe-ok")) {
            Log.i(TAG, "root ncm reclaim: su unavailable; giving up")
            return false
        }
        val bound = runSu("ls $DRIVER_DIR/")
            .split(Regex("\\s+"))
            .filter { IFACE.matches(it) }
        Log.i(TAG, "root ncm reclaim: cdc_ncm bound interfaces=$bound")
        var unbound = 0
        for (entry in bound) {
            if (runSu("echo '$entry' > $DRIVER_DIR/unbind").isNotBlank()) {
                Log.w(TAG, "root ncm reclaim: unbind $entry rejected (kernel may refuse)")
            } else unbound++
        }
        // 驱动是模块时 rmmod 根治后续枚举抢占；编译进内核或被占用时失败无害。
        val rmmod = runSu("rmmod cdc_ncm")
        if (rmmod.isNotBlank()) Log.i(TAG, "root ncm reclaim: rmmod cdc_ncm: ${rmmod.trim()}")
        Log.i(TAG, "root ncm reclaim: unbound=$unbound/${bound.size}; caller will retry claim")
        return true
    }

    /** 返回 stdout+stderr（trim）；进程 6s 不退出强杀，输出视同失败。 */
    private fun runSu(command: String): String = try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val watchdog = Thread {
            try {
                Thread.sleep(6_000)
                process.destroy()
            } catch (_: InterruptedException) {
            }
        }
        watchdog.isDaemon = true
        watchdog.start()
        val output = process.inputStream.bufferedReader().use { it.readText() } +
            process.errorStream.bufferedReader().use { it.readText() }
        process.waitFor()
        watchdog.interrupt()
        output
    } catch (error: Exception) {
        Log.i(TAG, "root ncm reclaim: exec failed: ${error.javaClass.simpleName}")
        ""
    }
}
