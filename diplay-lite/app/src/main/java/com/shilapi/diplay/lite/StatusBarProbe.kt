package com.shilapi.diplay.lite

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.View
import android.view.WindowManager

/**
 * 状态栏探针（vc102）：AC822X 反馈 vc101 状态栏隐藏无效——取证「顶部那栏到底是什么」。
 *
 * 两段证据：
 *  ① UI 侧回读（需 Activity）：设置值 vs 系统回读值对比（若 ROM 忽略 systemUiVisibility，
 *     回读值与设置值不等或短暂相等后被改回——onCreate 与 t+3s 两次对比即可实锤回弹）；
 *     另记 window flags、status_bar_height 资源值、屏幕高 vs App 可用高差值（栏占多少 px）。
 *  ② 全局窗口清单（Context 即可）：root/权限可用时 dumpsys window 列出所有窗口的
 *     type/owner/package——判定顶栏是标准 StatusBar（com.android.systemui）还是厂商
 *     自绘 overlay（TYPE_APPLICATION_OVERLAY/TYPE_SYSTEM_OVERLAY 等第三方窗口），
 *     这是选下一轮隐藏手段（systemUi / addFlags / 覆盖窗口 / root 隐藏）的依据。
 *
 * 设计约束与 ClusterDisplayProbe 一致：零副作用、幂等、后台线程、失败只记一行、
 * 输出走 LiteLog（TAG=statusbar）。
 */
internal object StatusBarProbe {

    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    // vc103r（AC822X 方向3）：回弹实验——ROM 在 ~3s 内把 systemUiVisibility 清回 0x0
    // （11:55 日志实锤：onCreate 回读 0x7 → t+3s 回读 0x0），且 windowFlags 被挂上
    // FORCE_NOT_FULLSCREEN(0x800)。挂 ChangeListener 被清即重设：若重设后栏消失=
    // ROM 放行自动补设（转正迁 Activity 层）；若立即被清回=ROM 策略级强制，需换路线。
    private val listenerAttached = java.util.concurrent.atomic.AtomicBoolean(false)

    private const val HIDE_FLAGS =
        View.SYSTEM_UI_FLAG_LOW_PROFILE or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION

    /** Activity 版：UI 回读 + 全局窗口清单 + 回弹自动补设实验。reason 例："onCreate" / "t+3s"。 */
    fun probe(activity: Activity, reason: String) {
        if (!running.compareAndSet(false, true)) return
        Thread {
            try {
                attachRehideListener(activity)
                uiReadback(activity, reason)
                windowDump(activity, reason)
            } catch (t: Throwable) {
                LiteLog.log("statusbar", "状态栏探针异常 ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                running.set(false)
            }
        }.start()
    }

    /** vc103r：回弹自动补设（仅挂一次；被清→记日志+重设 0x7+顺手摘 FORCE_NOT_FULLSCREEN）。 */
    private fun attachRehideListener(activity: Activity) {
        if (!listenerAttached.compareAndSet(false, true)) return
        val decor = activity.window.decorView
        decor.setOnSystemUiVisibilityChangeListener { vis ->
            if (vis and HIDE_FLAGS != HIDE_FLAGS) {
                LiteLog.log(
                    "statusbar",
                    "vc103r 回弹捕获 systemUi=0x" + Integer.toHexString(vis) +
                        " -> 重设 0x" + Integer.toHexString(HIDE_FLAGS),
                )
                decor.systemUiVisibility = HIDE_FLAGS
                runCatching {
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
                }
                val after = decor.systemUiVisibility
                LiteLog.log("statusbar", "vc103r 重设后回读=0x" + Integer.toHexString(after))
            }
        }
        LiteLog.log("statusbar", "vc103r ChangeListener 已挂")
    }

    /** Context 版（会话服务）：仅全局窗口清单，无 decorView 可回读。 */
    fun probe(context: Context, reason: String) {
        if (!running.compareAndSet(false, true)) return
        Thread {
            try {
                windowDump(context, reason)
            } catch (t: Throwable) {
                LiteLog.log("statusbar", "状态栏探针异常 ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                running.set(false)
            }
        }.start()
    }

    private fun uiReadback(activity: Activity, reason: String) {
        val decor = activity.window.decorView
        val metrics = activity.resources.displayMetrics
        // 状态栏标准高度（ROM 可能返回 0 或无效——本身就是证据）。
        var statusBarHeight = -1
        runCatching {
            val id = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) statusBarHeight = activity.resources.getDimensionPixelSize(id)
        }
        LiteLog.log(
            "statusbar",
            "探针[$reason] screen=${metrics.widthPixels}x${metrics.heightPixels} " +
                "appVisible=${decor.width}x${decor.height} statusBarResH=$statusBarHeight " +
                "model=${Build.MODEL.trim()} api=${Build.VERSION.SDK_INT}",
        )
        LiteLog.log(
            "statusbar",
            "探针[$reason] 设置=LOW_PROFILE|FULLSCREEN|HIDE_NAVIGATION(0x" +
                Integer.toHexString(
                    View.SYSTEM_UI_FLAG_LOW_PROFILE or View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION,
                ) + ") " +
                "回读systemUi=0x" + Integer.toHexString(decor.systemUiVisibility) +
                " 回读windowUi=0x" + Integer.toHexString(decor.windowSystemUiVisibility),
        )
        val attrs = activity.window.attributes
        LiteLog.log(
            "statusbar",
            "探针[$reason] windowFlags=0x" + Integer.toHexString(attrs.flags) +
                " fullscreen=" + ((attrs.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0) +
                " layoutInset=" + Integer.toHexString(attrs.systemUiVisibility),
        )
    }

    /** dumpsys window 全窗口清单：窗口名/uid/包名/层级，判定顶栏归属。 */
    private fun windowDump(context: Context, reason: String) {
        val out = runDumpsys(context, "dumpsys window windows")
            .ifEmpty { runDumpsys(context, "dumpsys window") } // 4.x 老 ROM 无 windows 子命令兜底
        if (out.isEmpty()) {
            LiteLog.log("statusbar", "探针[$reason] dumpsys window：无输出（无 DUMP 权限属预期）")
            return
        }
        // 只收集窗口块的关键行：Window #/mOwnerUid/mBaseLayer/mHasSurface/mAttrs/type。
        val keys = listOf("Window #", "mOwnerUid", "mBaseLayer", "mHasSurface", "mAttrs={", "type=")
        val lines = out.lines().filter { line -> keys.any { it in line } }.take(40)
        LiteLog.log("statusbar", "探针[$reason] 窗口清单 begin lines=${lines.size}")
        if (lines.isEmpty()) {
            // vc103r（AC822X）：4.2 dumpsys window 输出无 "Window #" 关键字（11:55 日志
            // 实锤 lines=0），格式与 4.4+ 不同——直接落原始输出前 30 行拿格式证据。
            LiteLog.log("statusbar", "探针[$reason] 关键字零匹配，原始输出前 30 行：")
            out.lines().filter { it.isNotBlank() }.take(30).forEach { LiteLog.log("statusbar", it.trim().take(200)) }
        } else {
            lines.forEach { LiteLog.log("statusbar", it.trim().take(200)) }
        }
        LiteLog.log("statusbar", "探针[$reason] 窗口清单 end")
    }

    private fun runDumpsys(context: Context, command: String): String = runCatching {
        val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val out = StringBuilder()
        val pump = Thread({
            runCatching { process.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
        }, "statusbar-dump-pump").apply { isDaemon = true; start() }
        var waited = 0
        while (waited < 6_000) {
            Thread.sleep(100); waited += 100
            try { process.exitValue(); break } catch (_: IllegalThreadStateException) {}
        }
        if (waited >= 6_000) process.destroy()
        pump.join(500)
        out.toString()
    }.getOrDefault("")
}
