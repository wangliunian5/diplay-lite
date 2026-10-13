package com.shilapi.diplay.lite

import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.graphics.Typeface
import android.net.wifi.WifiManager
import android.os.Build
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 系统诊断（1.1.4 重做）：面向用户的中文硬件检测 + 连接方式建议。
 *
 * 两档流程：
 *   ① 首启快检（quickCheckIfDue）：新装无检测记录时自动触发——纯自动零副作用项
 *      （系统版本/ROOT/ADB/蓝牙角色/Wi-Fi 热点只读/USB 静态），人工项在报告标「待复测」。
 *   ② 设置页复测（run）：确认框 → 逐项检测弹窗（自动项超时保护；人工项不计时等用户点
 *      「下一步」）→ 总报告（规则矩阵出连接建议 + 上传诊断日志入口）。
 *
 * 判定矩阵（无线 CarPlay 前提 = 热点可开 + 蓝牙可连；Wi-Fi STA 只有中继模式才需要）：
 *   热点可 + 蓝牙可        → 支持无线 CarPlay（推荐）+ USB 直连
 *   热点可 + 蓝牙不可      → USB 直连；无线需检修/加装外置蓝牙模块
 *   热点不可 + Wi-Fi 可    → USB 直连（热点或因互斥被 Wi-Fi 占用，关 Wi-Fi 后可复测）
 *   热点不可 + Wi-Fi 不可  → 仅 USB 直连
 * 附加：蓝牙无 A2DP sink → 「蓝牙音频+CarPlay 仅支持手动切换，不建议首选」；
 *       cdc_ncm 常驻且无 root → USB 可能被系统驱动占用，提示需 ROOT。
 *
 * 原始取证数据（getprop/ps/USB 拓扑/su 指纹/su-b64）仅写入 LiteLog 不上屏。
 */
internal object Diagnostics {

    private const val PREFS = "diplay_diag"
    private const val KEY_REPORTED = "reported"
    private const val BT_PROFILE_A2DP_SINK = 10 // BluetoothProfile.A2DP_SINK（API 11+ 常量值）
    private const val AP_STATE_ENABLED = 13
    private const val AP_STATE_ENABLING = 12

    private val running = AtomicBoolean(false)

    // ---------- 对外入口 ----------

    /** 设置页复测：完整流程。onUpload = 报告页「上传诊断日志」回调（设置页接 Gitee 上传）。 */
    fun run(activity: Activity, onUpload: (() -> Unit)? = null) {
        if (!running.compareAndSet(false, true)) {
            toast(activity, "系统诊断进行中，请等待完成")
            return
        }
        if (LiteState.phase != LiteState.Phase.IDLE) {
            running.set(false)
            toast(activity, "CarPlay 会话进行中，请断开连接后再执行系统诊断")
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("系统诊断")
            .setMessage(
                "将对车机硬件逐项检测（蓝牙 / Wi-Fi / 热点 / USB / ROOT），" +
                    "全程约 30~60 秒。\n\n注意：检测热点时会短暂关闭 Wi-Fi，" +
                    "结束后自动恢复原状。检测过程中请勿进行连接操作。",
            )
            .setNegativeButton("取消") { _, _ -> running.set(false) }
            .setPositiveButton("开始检测") { _, _ -> fullCheck(activity, onUpload) }
            .show()
    }

    /** 首启快检：无检测记录 → 主界面就绪且无会话时延迟 5 秒弹开始确认（用户可跳过）。 */
    fun quickCheckIfDue(activity: Activity) {
        if (running.get()) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_REPORTED, false)) return
        activity.window.decorView.postDelayed({
            if (running.get()) return@postDelayed
            // 会话进行中不触发：保留未检测标志，下次 onResume 再试。
            if (LiteState.phase != LiteState.Phase.IDLE) return@postDelayed
            // isDestroyed 需 API 17（Lite 目标 API 16 起步），加版本护栏。
            if (activity.isFinishing || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed)) return@postDelayed
            quickCheckConfirm(activity)
        }, 5000)
    }

    private fun quickCheckConfirm(activity: Activity) {
        if (!running.compareAndSet(false, true)) return
        val skip = Runnable { markReported(activity); running.set(false) }
        AlertDialog.Builder(activity)
            .setTitle("首次使用建议")
            .setMessage("建议先对车机硬件做一次快速检测（约 10 秒，零副作用），\n" +
                "了解本机支持哪些连接方式。\n\n若检测期间弹出 ROOT 授权请求，请选择允许。\n\n" +
                "随时可在 设置 → 系统诊断 完整复测。")
            .setNegativeButton("跳过") { _, _ -> skip.run() }
            .setPositiveButton("开始检测") { _, _ -> Thread { quickCheck(activity) }.start() }
            .setOnCancelListener { skip.run() }
            .show()
    }

    // ---------- 流程主体 ----------

    /** 首启快检：纯自动零副作用项 → 报告（人工项标「待复测」）。 */
    private fun quickCheck(activity: Activity) {
        val steps = ArrayList<Pair<String, String>>()
        try {
            steps.add("设备系统" to detectDevice(activity))
            val root = detectRoot(activity)
            steps.add("ROOT 权限" to root.summary)
            steps.add("网络调试" to detectAdb(activity, root.suOk))
            val bt = detectBluetooth(activity)
            steps.add("蓝牙" to bt.summary)
            val net = detectNetworkReadOnly(activity)
            steps.add("Wi-Fi / 热点" to net.summary)
            val usb = detectUsbStatic()
            steps.add("USB" to usb.summary)
            val verdict = buildVerdict(
                hotspotUsable = net.hotspotUsable, btUsable = bt.usable, btSink = bt.sinkOk,
                hotspotMutexHint = net.mutexHint, hotspotUntested = true,
                rootOk = root.suOk, ncmResident = usb.ncmResident,
            )
            markReported(activity)
            showReport(activity, steps, verdict, onUpload = null, quickMode = true)
        } catch (error: Throwable) {
            markReported(activity)
            LiteLog.log("diag", "快检异常: ${error.javaClass.simpleName} ${error.message}")
            toast(activity, "快速检测异常，可到设置页复测")
        } finally {
            running.set(false)
        }
    }

    /** 设置页复测：逐项检测弹窗（人工项等用户），完成后报告 + 后台静默取证。 */
    private fun fullCheck(activity: Activity, onUpload: (() -> Unit)?) {
        val panel = ProgressPanel(activity)
        Thread {
            val steps = ArrayList<Pair<String, String>>()
            try {
                panel.mark("设备系统", "检测中…")
                steps.add("设备系统" to detectDevice(activity).also { panel.mark("设备系统", it) })

                panel.mark("ROOT 权限", "检测中…")
                val root = detectRoot(activity)
                steps.add("ROOT 权限" to root.summary.also { panel.mark("ROOT 权限", it) })

                panel.mark("网络调试", "检测中…")
                steps.add("网络调试" to detectAdb(activity, root.suOk).also { panel.mark("网络调试", it) })

                panel.mark("蓝牙", "检测中（约 5 秒）…")
                val bt = detectBluetooth(activity)
                steps.add("蓝牙" to bt.summary.also { panel.mark("蓝牙", it) })

                panel.mark("Wi-Fi", "检测中…")
                val netRead = detectNetworkReadOnly(activity)
                steps.add("Wi-Fi" to netRead.wifiSummary.also { panel.mark("Wi-Fi", it) })

                // USB：静态秒出；动态需要 iPhone 插入——人工步骤不计时，等用户点下一步/跳过。
                panel.mark("USB", "检测中…")
                val usb = detectUsbStatic()
                var usbDynamic = "未检测（跳过）"
                if (askManual(panel, "USB 占用检测", "请插入 iPhone 到车机 USB 口，完成后点「下一步」。" +
                        "（没有 iPhone 可跳过，仅影响 USB 占用判定）")) {
                    usbDynamic = detectUsbDynamic(root.suOk)
                }
                steps.add("USB" to (usb.summary + "\n　动态检测：" + usbDynamic)
                    .also { panel.mark("USB", it) })

                // 热点实测：最后一项（会动 Wi-Fi/热点，测完恢复原状，约 15 秒）。
                panel.mark("热点实测", "检测中（约 15 秒，期间 Wi-Fi 会短暂关闭）…")
                val hotspot = detectHotspotMeasured(activity)
                steps.add("热点实测" to hotspot.summary.also { panel.mark("热点实测", it) })

                val verdict = buildVerdict(
                    hotspotUsable = hotspot.usable, btUsable = bt.usable, btSink = bt.sinkOk,
                    hotspotMutexHint = hotspot.mutexHint, hotspotUntested = false,
                    rootOk = root.suOk, ncmResident = usb.ncmResident,
                )
                panel.close()
                showReport(activity, steps, verdict, onUpload, quickMode = false)
                collectForensics(activity, root.suOk) // 静默后台取证，只写日志不上屏
            } catch (error: Throwable) {
                LiteLog.log("diag", "诊断异常: ${error.javaClass.simpleName} ${error.message}")
                panel.close()
                activity.runOnUiThread {
                    toast(activity, "诊断异常中止，结果已写入日志")
                    showReport(activity, steps, "检测未完整完成（已写入日志），可重试复测。", onUpload, false)
                }
            } finally {
                running.set(false)
            }
        }.start()
    }

    // ---------- 检测项 ----------

    private fun detectDevice(context: Context): String {
        // vc60（用户要求）：报告展示当前设备实际分辨率（真实像素，含系统缩放后的显示值）。
        val metrics = context.resources.displayMetrics
        return "Android ${apiToRelease(Build.VERSION.SDK_INT)}（API ${Build.VERSION.SDK_INT}）" +
            "\n　型号：${Build.MODEL.trim()}" +
            "\n　分辨率：${metrics.widthPixels}×${metrics.heightPixels}" +
            "\n　软件：${appVersionName(context)}"
    }

    private class RootResult(val suOk: Boolean, val summary: String)

    private fun detectRoot(activity: Activity): RootResult {
        val suPaths = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su")
            .filter { File(it).exists() }
        val suVariants = listOf("su -c id", "su 0 id", "/system/xbin/su -c id", "daemonsu -c id")
        val suResults = suVariants.associateWith { cmd -> exec(cmd).trim().take(120) }
        val suOk = suResults.values.any { it.contains("uid=0") }
        val summary = when {
            suOk -> "✅ 已获取 ROOT（su 授权可用）"
            suPaths.isNotEmpty() -> "⚠️ 存在 su（${suPaths.joinToString()}），但本软件未被授权——" +
                "打开车机上的授权管理（SuperSU 等）放行本软件后复测"
            else -> "❌ 未检测到 ROOT"
        }
        LiteLog.log("diag", "diag-root: suPaths=$suPaths results=$suResults ok=$suOk")
        return RootResult(suOk, summary)
    }

    /** root 可用时顺带开启网络 ADB（5555），便于 PC 远程排查。 */
    private fun detectAdb(activity: Activity, suOk: Boolean): String {
        val port = exec("getprop service.adb.tcp.port").trim()
        val already = port == "5555"
        var enabled = false
        if (suOk && !already) {
            val out = exec(
                "su -c 'setprop persist.adb.tcp.port 5555; setprop service.adb.tcp.port 5555; " +
                    "stop adbd; start adbd; sleep 1; getprop service.adb.tcp.port'",
            ).trim()
            enabled = out.contains("5555")
            LiteLog.log("diag", "diag-adb: su 开启结果=[$out] ok=$enabled")
        }
        return when {
            already || enabled -> "✅ 网络调试已开启（PC 执行 adb connect 车机IP:5555）"
            suOk -> "⚠️ 有 ROOT 但开启失败"
            else -> "－ 未开启（ROOT 可用后可远程排查）"
        }
    }

    private class BtResult(val usable: Boolean, val sinkOk: Boolean, val summary: String)

    private fun detectBluetooth(activity: Activity): BtResult {
        val adapter = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
            ?: return BtResult(false, false, "❌ 系统无蓝牙服务")
        if (!adapter.isEnabled) {
            return BtResult(false, false, "❌ 蓝牙未开启（无线 CarPlay 需要蓝牙建立控制通道，请开启后复测）")
        }
        val stackOk = adapter.state != BluetoothAdapter.STATE_OFF
        // A2DP sink 角色探测：异步 getProfileProxy，栈残缺的 ROM 回调可能永不来 → 5 秒超时判不可用。
        var sinkOk = false
        var proxy: BluetoothProfile? = null
        val latch = CountDownLatch(1)
        val requested = runCatching {
            adapter.getProfileProxy(activity.applicationContext, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
                    if (profile == BT_PROFILE_A2DP_SINK) { sinkOk = true; proxy = p }
                    latch.countDown()
                }

                override fun onServiceDisconnected(profile: Int) {}
            }, BT_PROFILE_A2DP_SINK)
        }.getOrDefault(false)
        latch.await(5, TimeUnit.SECONDS)
        if (requested && proxy != null) runCatching { adapter.closeProfileProxy(BT_PROFILE_A2DP_SINK, proxy) }
        LiteLog.log("diag", "diag-bt: state=${adapter.state} sinkRequested=$requested sinkOk=$sinkOk")
        val summary = buildString {
            append(if (stackOk) "✅ 可用（${adapter.name ?: "蓝牙"}）" else "⚠️ 状态异常")
            append("\n　蓝牙接收（A2DP sink）：")
            append(if (sinkOk) "✅ 可接收 iPhone 蓝牙音频" else "❌ 不可用（仅外发类型）")
        }
        return BtResult(stackOk, sinkOk, summary)
    }

    private class NetReadResult(
        val hotspotUsable: Boolean, val mutexHint: String?, val summary: String, val wifiSummary: String,
    )

    /** Wi-Fi / 热点只读（快检档，不实测）。 */
    private fun detectNetworkReadOnly(activity: Activity): NetReadResult {
        val wm = wifiManager(activity)
            ?: return NetReadResult(false, null, "❌ 系统无 Wi-Fi 服务", "❌ 系统无 Wi-Fi 服务")
        val wifiOn = runCatching { wm.isWifiEnabled }.getOrDefault(false)
        val apState = wifiApState(wm)
        val apEnabled = apState == AP_STATE_ENABLED || apState == AP_STATE_ENABLING
        val mutexHint = if (!apEnabled && wifiOn) "热点与 Wi-Fi 互斥：当前 Wi-Fi 已开启，关闭 Wi-Fi 后热点才能开启" else null
        val apLine = when {
            apEnabled -> "✅ 热点已开启"
            mutexHint != null -> "－ 未开启（疑似被 Wi-Fi 占用，可关闭 Wi-Fi 后复测）"
            else -> "－ 未开启（快检未实测，复测将实测能否开启）"
        }
        val summary = "Wi-Fi：${if (wifiOn) "已开启" else "已关闭"}\n　热点：$apLine"
        val wifiSummary = "Wi-Fi：${if (wifiOn) "✅ 已开启" else "－ 已关闭"}"
        return NetReadResult(apEnabled, mutexHint, summary, wifiSummary)
    }

    private class HotspotResult(val usable: Boolean, val mutexHint: String?, val summary: String)

    /** 热点实测（复测档）：关 Wi-Fi → 开热点 → 轮询 15s → 恢复原状。 */
    private fun detectHotspotMeasured(activity: Activity): HotspotResult {
        val wm = wifiManager(activity)
            ?: return HotspotResult(false, null, "❌ 系统无 Wi-Fi 服务")
        val origWifi = runCatching { wm.isWifiEnabled }.getOrDefault(false)
        var usable = false
        var ssid: String? = null
        try {
            runCatching { wm.setWifiEnabled(false) }
            Thread.sleep(1500)
            val apOk = setWifiApEnabled(wm, true)
            if (apOk) {
                // 15 秒轮询，ENABLED 即成功。
                var waited = 0
                while (waited < 15) {
                    if (wifiApState(wm) == AP_STATE_ENABLED) break
                    Thread.sleep(1000)
                    waited++
                }
                usable = wifiApState(wm) == AP_STATE_ENABLED
                ssid = wifiApSsid(wm)
            }
            LiteLog.log("diag", "diag-hotspot: apOk=$apOk usable=$usable ssid=$ssid")
        } finally {
            setWifiApEnabled(wm, false)
            Thread.sleep(800)
            if (origWifi) runCatching { wm.setWifiEnabled(true) }
        }
        val summary = if (usable) {
            "✅ 可开启${if (ssid != null) "（SSID: $ssid）" else ""}"
        } else {
            "❌ 实测无法开启（无线 CarPlay 不可用）"
        }
        return HotspotResult(usable, null, summary)
    }

    private class UsbResult(val ncmResident: Boolean, val summary: String)

    /** USB 静态：host 控制器 + cdc_ncm 驱动常驻检测（零副作用）。 */
    private fun detectUsbStatic(): UsbResult {
        val ncmResident = File("/sys/bus/usb/drivers/cdc_ncm").isDirectory
        val hostCtl = runCatching {
            File("/sys/bus/usb/devices").listFiles()
                ?.any { it.name.startsWith("usb") } == true
        }.getOrDefault(false)
        val summary = buildString {
            append(if (hostCtl) "✅ USB host 就绪" else "⚠️ 未发现 USB host 控制器")
            if (ncmResident) {
                append("\n　⚠️ NCM 驱动常驻：系统内核可能抢占 iPhone 网络接口")
                append("（静态判定，插入 iPhone 后动态检测可确认）")
            }
        }
        return UsbResult(ncmResident, summary)
    }

    /** USB 动态：iPhone 插入时扫描 /dev/bus/usb 节点持有者（需 root 才有结果）。 */
    private fun detectUsbDynamic(suOk: Boolean): String {
        val fdScanCmd =
            "for p in /proc/[0-9]*/fd; do " +
                "if ls -l \"\$p\" 2>/dev/null | grep -q 'bus/usb'; then " +
                "d=\${p%/fd}; " +
                "echo \"\$d|\$(tr '\\0' ' ' < \"\$d/cmdline\" 2>/dev/null)\"; " +
                "fi; done"
        val holders = if (suOk) exec("su -c '$fdScanCmd'") else exec(fdScanCmd)
        val lines = holders.lines().filter { it.isNotBlank() }
        LiteLog.log("diag", "diag-usb-holders: ${lines.joinToString(" | ")}")
        return when {
            !suOk -> "无 ROOT，无法判定占用进程"
            lines.isEmpty() -> "未发现占用进程（无系统抢占）"
            else -> "发现持有进程：${lines.take(3).joinToString("；") { it.substringAfter('|', "?") }}"
        }
    }

    // ---------- 规则矩阵 ----------

    private fun buildVerdict(
        hotspotUsable: Boolean, btUsable: Boolean, btSink: Boolean,
        hotspotMutexHint: String?, hotspotUntested: Boolean,
        rootOk: Boolean, ncmResident: Boolean,
    ): String = buildString {
        append("【连接建议】\n")
        when {
            hotspotUsable && btUsable -> {
                append("✅ 支持无线 CarPlay（推荐首选），同时支持 USB 直连\n")
            }
            hotspotUsable && !btUsable -> {
                append("✅ 支持 USB 直连\n")
                append("⚠️ 无线 CarPlay 不可用：蓝牙是无线控制通道，当前蓝牙不可用，" +
                    "若需无线连接请检修蓝牙或加装外置蓝牙模块\n")
            }
            !hotspotUsable && btUsable -> {
                append("✅ 支持 USB 直连\n")
                if (hotspotUntested) {
                    append("⚠️ 热点未实测（快检只读），设置页复测将实测能否开启\n")
                } else {
                    append("❌ 热点无法开启，无线 CarPlay 不可用\n")
                }
                if (hotspotMutexHint != null) append("💡 $hotspotMutexHint\n")
            }
            else -> {
                append("✅ 仅支持 USB 直连（热点与蓝牙均不可用，无线通道无法建立）\n")
            }
        }
        if (!btSink) {
            append("\n• 蓝牙音频+CarPlay：本机不支持蓝牙音频接收，" +
                "如需音乐走车机蓝牙，请在连接后于 iPhone 控制中心 → 音乐卡片 → " +
                "AirPlay 图标 → 选择车机蓝牙手动切换（不建议首选）\n")
        }
        if (ncmResident && !rootOk) {
            append("\n• USB 提示：系统 NCM 驱动常驻且无 ROOT，USB 连接可能被系统抢占——" +
                "若插入 iPhone 后无法连接，建议 ROOT 车机后重试\n")
        }
        if (rootOk) {
            append("\n• ROOT 可用：网络调试已就绪，遇问题可 PC 远程排查\n")
        }
        append("\n（复测入口：设置 → 系统诊断）")
    }

    // ---------- UI ----------

    /** 逐项检测过程弹窗：文本区逐项更新 + 人工步骤按钮行。 */
    private class ProgressPanel(activity: Activity) {
        private val dialog: AlertDialog
        private val text = TextView(activity)
        private val buttonsRow = LinearLayout(activity)
        private val body = StringBuilder()
        private val gate = LinkedBlockingQueue<Boolean>()

        init {
            text.setTextColor(0xFFDDDDDD.toInt())
            text.textSize = 12f
            text.typeface = Typeface.MONOSPACE
            text.setPadding(36, 24, 36, 12)
            buttonsRow.orientation = LinearLayout.HORIZONTAL
            buttonsRow.gravity = Gravity.END
            buttonsRow.setPadding(36, 4, 36, 16)
            buttonsRow.visibility = android.view.View.GONE
            val column = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                // vc58 修复（8815 实测）：ScrollView 不限高会把按钮行挤出弹窗可视区
                //（横屏 540px 高度下「下一步/跳过」不可见）——weight=1 让文本区占剩余空间，
                // 按钮行恒定钉在弹窗底部。
                addView(
                    ScrollView(activity).apply { addView(text) },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
                    ),
                )
                addView(buttonsRow, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ))
            }
            dialog = AlertDialog.Builder(activity)
                .setTitle("系统检测中")
                .setView(column)
                .setCancelable(false)
                .show()
        }

        fun mark(title: String, result: String) {
            body.append('☑').append(' ').append(title).append("  ").append(result).append("\n\n")
            val ui = text
            ui.post { ui.text = body.toString() }
        }

        /** 人工步骤：显示按钮（下一步=true / 跳过=false），阻塞等用户选择，不计时。 */
        fun ask(title: String, message: String): Boolean {
            val uiText = text
            val row = buttonsRow
            uiText.post {
                body.append('➤').append(' ').append(title).append("\n").append(message).append("\n\n")
                uiText.text = body.toString()
                row.removeAllViews()
                val skip = Button(row.context).apply { text = "跳过" }
                val next = Button(row.context).apply { text = "下一步" }
                skip.setOnClickListener { gate.offer(false); row.visibility = android.view.View.GONE }
                next.setOnClickListener { gate.offer(true); row.visibility = android.view.View.GONE }
                row.addView(skip); row.addView(next)
                row.visibility = android.view.View.VISIBLE
            }
            return try { gate.take() } catch (_: InterruptedException) { false }
        }

        fun close() {
            text.post { runCatching { dialog.dismiss() } }
        }
    }

    /** 过程窗内的人工步骤便捷调用。 */
    private fun askManual(panel: ProgressPanel, title: String, message: String): Boolean =
        panel.ask(title, message)

    private fun showReport(
        activity: Activity, steps: List<Pair<String, String>>, verdict: String,
        onUpload: (() -> Unit)?, quickMode: Boolean,
    ) {
        val body = StringBuilder()
        for ((title, result) in steps) body.append('【').append(title).append("】\n").append(result).append("\n\n")
        body.append(verdict)
        if (quickMode) {
            body.append("\n\n提示：USB 占用与热点开启能力未做完整实测（快检零副作用），" +
                "需要时到 设置 → 系统诊断 完整复测；结果页上传日志走 设置 → 运行日志 → 上传。")
        }
        activity.runOnUiThread {
            if (activity.isFinishing || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed)) return@runOnUiThread
            val scroll = ScrollView(activity).apply {
                addView(TextView(activity).apply {
                    text = body.toString()
                    setTextColor(0xFFDDDDDD.toInt())
                    textSize = 12f
                    setPadding(36, 24, 36, 24)
                })
            }
            val builder = AlertDialog.Builder(activity)
                .setTitle("诊断报告")
                .setView(scroll)
                .setPositiveButton("关闭", null)
            if (onUpload != null) {
                builder.setNeutralButton("上传诊断日志") { _, _ -> onUpload() }
            }
            builder.show()
        }
    }

    // ---------- 后台取证（只写日志不上屏） ----------

    /** 原始系统取证采集（1.0.29 起）：蓝牙栈属性/进程/USB 拓扑/su 指纹/网络 ADB 直试。 */
    private fun collectForensics(activity: Activity, suOk: Boolean) {
        fun log(line: String) = LiteLog.log("diag", line)
        try {
            log("=== 诊断取证开始 device=${Build.MODEL} api=${Build.VERSION.SDK_INT} ===")
            val adapter = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
            if (adapter != null) runCatching {
                log("bt: state=${adapter.state} enabled=${adapter.isEnabled} " +
                    "address=${adapter.address} name=${adapter.name} scanMode=${adapter.scanMode}")
                val scan = runCatching {
                    adapter.javaClass.getMethod("setScanMode", Int::class.javaPrimitiveType)
                        .invoke(adapter, 3)
                    true
                }.getOrDefault(false)
                log("bt: setScanMode(DISCOVERABLE)=$scan afterScanMode=${adapter.scanMode}")
            }
            val propKeys = listOf(
                "ro.build.version", "ro.product", "ro.board", "ro.hardware",
                "bt", "BT", "bluetooth", "wifi.mac", "wlan.mac",
                "adb.tcp", "sys.usb.config", "persist.sys.usb", "ro.serialno",
            )
            exec("getprop").lines()
                .filter { line -> propKeys.any { line.contains(it, ignoreCase = true) } }
                .take(40).forEach { log("prop: ${it.trim()}") }
            exec("ps | grep -iE 'blue|bt'").lines().filter { it.isNotBlank() }.take(10)
                .forEach { log("ps: ${it.trim()}") }
            exec("service list | grep -iE 'blue|bt'").lines().filter { it.isNotBlank() }.take(6)
                .forEach { log("svc: ${it.trim()}") }
            // USB 接口驱动绑定 + 拓扑 + 网络接口 + 全量进程
            runCatching {
                File("/sys/bus/usb/devices").listFiles()?.sortedBy { it.name }?.forEach { dev ->
                    val uevent = runCatching { dev.resolve("uevent").readText().trim() }.getOrDefault("")
                    if (uevent.isNotEmpty()) log("usb-uevent: ${dev.name} ${uevent.replace('\n', ' ')}")
                }
            }.onFailure { log("usb-uevent: 读取失败 ${it.message}") }
            for (usbPath in listOf("/sys/kernel/debug/usb/devices", "/proc/bus/usb/devices")) {
                val text = runCatching { File(usbPath).readText() }.getOrNull() ?: continue
                log("usb-devices: source=$usbPath")
                text.lines()
                    .filter { it.startsWith("T:") || it.startsWith("I:") || it.startsWith("S:") }
                    .take(150).forEach { log("usb-devices: ${it.trim()}") }
                break
            }
            exec("cat /proc/net/dev").lines().take(20).forEach { log("net-dev: ${it.trim()}") }
            exec("ps").lines().filter { it.isNotBlank() }.take(200).forEach { log("ps-all: ${it.trim()}") }
            // root 属性探测 + su 指纹/内嵌字符串 + 全量 base64（PC 端逆向白名单）
            listOf("persist.sys.root_access", "ro.sys.root_access", "persist.adb.tcp.port", "service.adb.tcp.port")
                .forEach { log("root: prop $it=${exec("getprop $it").trim()}") }
            val suBin = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su")
                .firstOrNull { File(it).exists() }
            if (suBin != null) {
                val f = File(suBin)
                log("su: binary=$suBin size=${f.length()}")
                runCatching {
                    val bytes = f.inputStream().use { input ->
                        val buf = ByteArray(256 * 1024)
                        val n = input.read(buf)
                        buf.copyOf(if (n > 0) n else buf.size)
                    }
                    val words = ArrayList<String>()
                    var start = -1
                    for ((i, b) in bytes.withIndex()) {
                        val printable = b in 0x20..0x7e
                        if (printable && start < 0) start = i
                        if (!printable && start >= 0) {
                            if (i - start >= 5) words.add(String(bytes, start, i - start, Charsets.US_ASCII))
                            start = -1
                        }
                    }
                    val keys = listOf("/data", "/system", ".db", "super", "allow", "whitelist", "conf", "xml", "adb", "uid")
                    words.filter { w -> keys.any { w.contains(it, ignoreCase = true) } }
                        .distinct().take(30).forEach { log("su: str=$it") }
                }.onFailure { log("su: strings 分析失败 ${it.message}") }
                runCatching {
                    val data = f.readBytes()
                    log("su-b64: begin size=${data.size}")
                    val b64 = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
                    b64.chunked(96).forEachIndexed { i, line -> log("su-b64: $i $line") }
                    log("su-b64: end")
                }.onFailure { log("su-b64: 导出失败 ${it.message}") }
            }
            runCatching {
                File("/proc").listFiles { d -> d.name.matches(Regex("\\d+")) }?.forEach { p ->
                    runCatching {
                        val cmd = p.resolve("cmdline").readBytes().toString(Charsets.UTF_8)
                            .replace('\u0000', ' ').trim()
                        if (cmd.contains("su") || cmd.contains("daemon")) log("proc: ${p.name} $cmd")
                    }
                }
            }
            // 无 root 也直试 setprop（testkey 系统签名 ROM 可成功）
            val enable = exec(
                "setprop persist.adb.tcp.port 5555; setprop service.adb.tcp.port 5555; " +
                    "stop adbd; start adbd; sleep 1; getprop service.adb.tcp.port",
            ).trim()
            log("root: 直试 setprop 开网络 ADB port=[$enable]${if (enable.contains("5555")) "（成功）" else "（无权限，属正常 uid）"}")
            // FE-6（ECARX）NCM 楔死取证：root 下抓内核环形缓冲尾部，定位 USB host
            // 控制器端点报错（chipidea/dwc3 halt 类）；无 root 时输出为空属正常。
            runCatching {
                val dmesg = exec("su -c 'dmesg | tail -n 400'").trim()
                if (dmesg.isNotEmpty()) {
                    log("dmesg: begin lines=${dmesg.lines().size}")
                    dmesg.lines().take(400).forEach { log("dmesg: $it") }
                    log("dmesg: end")
                } else {
                    log("dmesg: 无输出（无 root 或内核 dmesg_restrict）")
                }
            }.onFailure { log("dmesg: 采集失败 ${it.message}") }
            log("=== 诊断取证结束 ===")
        } catch (error: Throwable) {
            log("取证异常: ${error.javaClass.simpleName} ${error.message}")
        }
    }

    // ---------- 工具 ----------

    private fun markReported(activity: Activity) {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_REPORTED, true).apply()
    }

    private fun wifiManager(activity: Activity): WifiManager? =
        runCatching {
            activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        }.getOrNull()

    // 热点 API（getWifiApState/setWifiApEnabled/getWifiApConfiguration）在新 SDK stubs 已隐藏，
    // 目标车机（API 16-23）运行时仍可用，统一反射调用，ROM 缺失时返回默认值。
    private fun wifiApState(wm: WifiManager): Int =
        (runCatching { wm.javaClass.getMethod("getWifiApState").invoke(wm) }.getOrNull() as? Int) ?: 0

    private fun setWifiApEnabled(wm: WifiManager, enabled: Boolean): Boolean = runCatching {
        wm.javaClass.getMethod(
            "setWifiApEnabled", android.net.wifi.WifiConfiguration::class.java, Boolean::class.javaPrimitiveType,
        ).invoke(wm, null, enabled) as Boolean
    }.getOrDefault(false)

    private fun wifiApSsid(wm: WifiManager): String? = runCatching {
        val config = wm.javaClass.getMethod("getWifiApConfiguration").invoke(wm) ?: return@runCatching null
        // ssid 字段同样走反射，规避新 SDK stubs 的可见性差异。
        val ssid = config.javaClass.getField("ssid").get(config) as? String
        ssid?.removeSurrounding("\"")
    }.getOrNull()

    private fun apiToRelease(api: Int): String = when (api) {
        16 -> "4.1.x"; 17 -> "4.2.x"; 18 -> "4.3"; 19 -> "4.4.x"; 20 -> "4.4W"
        21 -> "5.0"; 22 -> "5.1"; 23 -> "6.0"; 24 -> "7.0"; 25 -> "7.1"
        26 -> "8.0"; 27 -> "8.1"; 28 -> "9"; 29 -> "10"; 30 -> "11"
        31, 32 -> "12"; 33 -> "13"; 34 -> "14"
        else -> Build.VERSION.RELEASE
    }

    private fun appVersionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (_: Exception) {
        "unknown"
    }

    private fun toast(context: Context, msg: String) =
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    /** sh -c 执行（支持管道），失败返回空串。 */
    private fun exec(command: String): String = runCatching {
        val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        out
    }.getOrDefault("")
}
