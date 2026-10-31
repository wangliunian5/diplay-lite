package com.shilapi.diplay.lite

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display

/**
 * 副屏探测（vc102）：仪表/HUD 投射路线的「判生死」检测，用户定案做成默认日志检测（无 UI 按钮）。
 *
 * 每次应用启动 + 每次会话服务启动各探测一次（幂等零副作用），结果写 LiteLog（cluster 标签）。
 * 判定形态（对齐 carlito fork 三级路由的准入前提）：
 *   - 仅主屏 → 无副屏可投（Presentation/overlay/独立窗口全部不可用）
 *   - 存在 FLAG_PRESENTATION 副屏 → android.app.Presentation 直投候选（DiLink5 式公开屏）
 *   - 普通逻辑副屏 → overlay/独立窗口形态候选（需确认可见性与权限，实车验证）
 * 附加 dumpsys display 尽力取证（无 DUMP 权限的 ROM 失败属预期，失败只记一行）。
 *
 * API 护栏：getDisplays/getName/getRealSize 均 API 17+，低于 17 记日志跳过。
 * ⚠️ `Display#getState()` 是 **API 20+**（不是 17）——API17~19 上直调会抛 NoSuchMethodError，
 * 这里靠 runCatching（捕 Throwable）吞成 -1，属**既定降级**：低版本副屏只报 id/name/尺寸，
 * 不报 state。别把它当「读不到状态」的业务结论。
 */
internal object ClusterDisplayProbe {

    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    fun probe(context: Context) {
        if (!running.compareAndSet(false, true)) return
        Thread {
            try {
                if (Build.VERSION.SDK_INT < 17) {
                    LiteLog.log("cluster", "副屏探测：API ${Build.VERSION.SDK_INT} < 17，系统无 Display 枚举能力")
                    return@Thread
                }
                val dm = context.applicationContext
                    .getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                if (dm == null) {
                    LiteLog.log("cluster", "副屏探测：系统无 DISPLAY_SERVICE")
                    return@Thread
                }
                val displays = runCatching { dm.displays.toList() }.getOrDefault(emptyList())
                LiteLog.log(
                    "cluster",
                    "副屏探测 begin model=${Build.MODEL.trim()} api=${Build.VERSION.SDK_INT} count=${displays.size}",
                )
                for (d in displays) {
                    val size = Point().also { runCatching { d.getRealSize(it) } }
                    val name = runCatching { d.name?.toString() }.getOrNull()
                    // getState 是 **API 20+**；API17~19 直接记 -1，不走异常路径
                    // （否则 NoSuchMethodError 被 runCatching 吞掉，会和「真读不到状态」混淆）。
                    val state = if (Build.VERSION.SDK_INT >= 20) {
                        runCatching { d.state }.getOrDefault(-1)
                    } else {
                        -1
                    }
                    val presentation = d.flags and Display.FLAG_PRESENTATION != 0
                    val primary = d.displayId == Display.DEFAULT_DISPLAY
                    LiteLog.log(
                        "cluster",
                        "display id=${d.displayId} name=$name ${size.x}x${size.y} " +
                            "flags=0x${Integer.toHexString(d.flags)} state=$state " +
                            "presentation=$presentation primary=$primary valid=${d.isValid}",
                    )
                }
                val secondaries = displays.filter { it.displayId != Display.DEFAULT_DISPLAY && it.isValid }
                val verdict = when {
                    secondaries.isEmpty() -> "仅主屏：无副屏可投（仪表/HUD 投射路线不可用）"
                    secondaries.any { it.flags and Display.FLAG_PRESENTATION != 0 } ->
                        "副屏可用：PRESENTATION 屏（Presentation 直投候选=" +
                            secondaries.filter { it.flags and Display.FLAG_PRESENTATION != 0 }
                                .joinToString { "${it.displayId}:${it.name}" } + "）"
                    else ->
                        "副屏可用：普通逻辑屏（overlay/独立窗口候选=" +
                            secondaries.joinToString { "${it.displayId}:${it.name}" } + "）"
                }
                LiteLog.log("cluster", "判定：$verdict")
                dumpsysSummary()
                LiteLog.log("cluster", "副屏探测 end")
            } catch (t: Throwable) {
                LiteLog.log("cluster", "副屏探测异常 ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                running.set(false)
            }
        }.start()
    }

    /** dumpsys display 摘要（mBaseDisplayInfo 行含 owner/真实分辨率，fork 路由凭此建静态表）。 */
    private fun dumpsysSummary() {
        runCatching {
            val process = ProcessBuilder("sh", "-c", "dumpsys display").redirectErrorStream(true).start()
            val out = StringBuilder()
            Thread({
                runCatching { process.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
            }, "cluster-dump-pump").apply { isDaemon = true; start() }
            var waited = 0
            while (waited < 6_000) {
                Thread.sleep(100); waited += 100
                try { process.exitValue(); break } catch (_: IllegalThreadStateException) {}
            }
            if (waited >= 6_000) process.destroy()
            val lines = out.toString().lines().filter {
                it.contains("mBaseDisplayInfo") || it.contains("DisplayDeviceInfo") || it.contains("Display #")
            }.take(24)
            if (lines.isEmpty()) {
                LiteLog.log("cluster", "dumpsys display：无匹配行（无 DUMP 权限属预期）")
            } else {
                LiteLog.log("cluster", "dumpsys 摘要 begin")
                lines.forEach { LiteLog.log("cluster", it.trim()) }
                LiteLog.log("cluster", "dumpsys 摘要 end")
            }
        }.onFailure { LiteLog.log("cluster", "dumpsys display：失败 ${it.message}") }
    }
}
