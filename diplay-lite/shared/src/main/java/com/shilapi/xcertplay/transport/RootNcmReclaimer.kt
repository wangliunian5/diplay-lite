// 1.1.3（root 抢回）：有线建链 claim NCM 接口失败时，若车机有 root（su 可用），
// 用 sysfs unbind 踢掉内核 cdc_ncm 驱动对 iPhone NCM 接口的抢占后重试。
// 依据：AC822X/t3/at8317 类 ROM 内核 CDC-NCM 驱动在枚举瞬间抓走 iPhone NCM 数据接口，
// 用户态 claimInterface 报 "could not claim the NCM data interface"，无 root 无解。
//
// vc100（T3 20261017 定案）：鼎微 T3 的 SuperSU 2.82 对 su -c <长命令> 有静默降权
// 缺陷——含重定向/分号的命令串授权仲裁失败时不报错、整体降为 shell 身份执行
// （uid=0 短命令 10 连测全过 vs 同命令串里 id 就变 2000，实锤）。旧逐条内联
// su -c "echo > sysfs" 在该类 ROM 上全部白跑。改脚本文件模式：App 把 reclaim 全
// 逻辑写成 sh 文件落私有 filesDir，单条短命令 su -c "sh <path>" 执行（deploy_su.sh
// 同款先例，T3 实测稳定 root），stdout 逐行回传判定。
package com.shilapi.xcertplay.transport

import android.content.Context
import android.util.Log
import java.io.File

internal object RootNcmReclaimer {
    private const val TAG = IphoneCarPlayConfiguration.TAG
    private const val DRIVER_DIR = "/sys/bus/usb/drivers/cdc_ncm"

    @Volatile
    private var appContext: Context? = null

    /** CarPlayController init 时注入；未注入时 reclaim 直接放弃（无脚本落点）。 */
    fun initialize(appContext: Context) {
        this.appContext = appContext.applicationContext
    }

    /** 1.0.3 同款判定：无 su 的 ROM 上 exec 抛 IOException / 退出码非 0，直接判不可用。 */
    fun reclaim(reason: String): Boolean {
        Log.i(TAG, "root ncm reclaim attempt: $reason")
        val context = appContext
        if (context == null) {
            Log.i(TAG, "root ncm reclaim: no app context; giving up")
            return false
        }
        val script = writeReclaimScript(context)
            ?: run {
                Log.w(TAG, "root ncm reclaim: write script failed; giving up")
                return false
            }
        // 单条短命令：T3 SuperSU 静默降权缺陷的唯一安全用法（vc100 定案）。
        val output = runSu("sh ${script.absolutePath}")
        var uidOk = false
        var removeIdOk = false
        var unbound = 0
        for (line in output.lineSequence()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("uid=") -> uidOk = trimmed == "uid=0"
                trimmed.startsWith("remove-id-ok") -> removeIdOk = true
                trimmed.startsWith("unbound=") -> unbound++
                trimmed.startsWith("unbind-fail=") ->
                    Log.w(TAG, "root ncm reclaim: ${trimmed.removePrefix("unbind-fail=")} rejected")
            }
        }
        if (!uidOk) {
            Log.w(TAG, "root ncm reclaim: script not running as root (SuperSU demotion?); output=$output")
            return false
        }
        Log.i(TAG, "root ncm reclaim: removeIdOk=$removeIdOk unbound=$unbound; caller will retry claim")
        return true
    }

    /** reclaim 脚本：自报 uid → 扫 Apple PID → remove_id → unbind，全程 stdout 回传。 */
    private fun writeReclaimScript(context: Context): File? = try {
        // 先摘 ID 再 unbind（顺序关键）：unbind 后内核 ~67ms re-probe 绑回（T3 dmesg 实锤），
        // remove_id 只挡新 probe 不拆旧绑定；摘 ID 本身会让内核同步解绑匹配设备。
        val body = """
            echo "uid=${'$'}(id -u)"
            pid=''
            for d in /sys/bus/usb/devices/*/idVendor; do
              v=${'$'}(cat ${'$'}d 2>/dev/null)
              if [ "${'$'}v" = "05ac" ]; then
                pid=${'$'}(cat ${'$'}{d%idVendor}idProduct 2>/dev/null)
                echo "pid=${'$'}pid"
              fi
            done
            if [ -n "${'$'}pid" ]; then
              echo "05ac ${'$'}pid" > $DRIVER_DIR/remove_id && echo "remove-id-ok" || echo "remove-id-fail"
            fi
            for e in ${'$'}(ls $DRIVER_DIR/); do
              case "${'$'}e" in
                *:*.*) echo "${'$'}e" > $DRIVER_DIR/unbind && echo "unbound=${'$'}e" || echo "unbind-fail=${'$'}e" ;;
              esac
            done
            echo "done"
        """.trimIndent()
        val script = File(context.filesDir, "ncm-reclaim.sh")
        script.writeText(body)
        script
    } catch (error: Exception) {
        Log.i(TAG, "root ncm reclaim: write script error: ${error.javaClass.simpleName}")
        null
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
