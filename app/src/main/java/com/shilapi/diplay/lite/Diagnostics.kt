package com.shilapi.diplay.lite

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.os.Build
import java.io.File

/**
 * 1.0.29 系统诊断采集（t3-p1 排查用，无 ADB 环境的替代通道）。
 *
 * 背景：t3-p1 全志蓝牙栈半死（22:22 伪 MAC、visibility 切不动、iAP2 无响应），且
 * USB-A 母口 host 固定 + 网络 ADB 未启用 + 工厂菜单缺失，常规排查通道全堵。
 * 本对象在 app 内 exec 采集系统信息写入 LiteLog（随日志上传通道拉取）：
 *   1. BluetoothAdapter 状态（state/scanMode/address/name）+ 可见性切换实测
 *   2. 关键系统属性（蓝牙 MAC 来源 / USB 配置 / adb tcp 开关）
 *   3. 蓝牙相关进程与服务
 *   4. root 探测；探测到 root 则直接开启网络 ADB（5555）——解锁全量排查通道
 */
internal object Diagnostics {

    fun run(context: Context) {
        Thread {
            val logs = ArrayList<String>()
            fun log(line: String) {
                logs.add(line)
                LiteLog.log("diag", line)
            }
            try {
                log("=== 系统诊断开始 device=${Build.MODEL} api=${Build.VERSION.SDK_INT} ===")

                // ---- 1. BluetoothAdapter 状态 ----
                val adapter = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
                if (adapter == null) {
                    log("bt: getDefaultAdapter=null（系统无蓝牙服务）")
                } else {
                    runCatching {
                        log("bt: state=${adapter.getState()} enabled=${adapter.isEnabled}")
                        log("bt: address=${adapter.address} name=${adapter.name}")
                        log("bt: scanMode=${adapter.scanMode}")
                        // 可见性切换实测（t3-p1 系统设置里切不动，验证是栈半死还是 UI 问题）。
                        // 反射调用：setScanMode 在部分 SDK 层级不可直接解析。
                        val scan = runCatching {
                            adapter.javaClass.getMethod(
                                "setScanMode", Int::class.javaPrimitiveType,
                            ).invoke(adapter, 3) // 3 = SCAN_MODE_CONNECTABLE_DISCOVERABLE
                            true
                        }.getOrDefault(false)
                        log("bt: setScanMode(DISCOVERABLE)=$scan afterScanMode=${adapter.scanMode}")
                    }
                }

                // ---- 2. 关键系统属性 ----
                exec("getprop").let { props ->
                    val keys = listOf(
                        "ro.build.version", "ro.product", "ro.board", "ro.hardware",
                        "bt", "BT", "bluetooth", "wifi.mac", "wlan.mac",
                        "adb.tcp", "sys.usb.config", "persist.sys.usb", "ro.serialno",
                    )
                    props.lines().filter { line -> keys.any { line.contains(it, ignoreCase = true) } }
                        .take(40)
                        .forEach { log("prop: ${it.trim()}") }
                }

                // ---- 3. 蓝牙进程/服务 ----
                exec("ps | grep -iE 'blue|bt'")
                    .lines().filter { it.isNotBlank() }.take(10)
                    .forEach { log("ps: ${it.trim()}") }
                exec("service list | grep -iE 'blue|bt'")
                    .lines().filter { it.isNotBlank() }.take(6)
                    .forEach { log("svc: ${it.trim()}") }

                // ---- 3b. USB 取证段（1.1.3：T3L usbmux 占用 / at8317 NCM 占用裁决）----
                // a) 每个 USB 接口当前绑定的内核驱动——cdc_ncm 等抢占看 DRIVER= 字段。
                runCatching {
                    File("/sys/bus/usb/devices").listFiles()
                        ?.sortedBy { it.name }
                        ?.forEach { dev ->
                            val uevent = runCatching { dev.resolve("uevent").readText().trim() }.getOrDefault("")
                            if (uevent.isNotEmpty()) log("usb-uevent: ${dev.name} ${uevent.replace('\n', ' ')}")
                        }
                }.onFailure { log("usb-uevent: 读取失败 ${it.message}") }
                // b) USB 拓扑（T/I/S 行：配置/接口/class/驱动/厂商字符串，一手证据）。
                for (usbPath in listOf("/sys/kernel/debug/usb/devices", "/proc/bus/usb/devices")) {
                    val text = runCatching { File(usbPath).readText() }.getOrNull() ?: continue
                    log("usb-devices: source=$usbPath")
                    text.lines()
                        .filter { it.startsWith("T:") || it.startsWith("I:") || it.startsWith("S:") }
                        .take(150)
                        .forEach { log("usb-devices: ${it.trim()}") }
                    break
                }
                // c) 网络接口一览——NCM 桥若被内核建出会有 ncm*/usb0。
                exec("cat /proc/net/dev").lines().take(20)
                    .forEach { log("net-dev: ${it.trim()}") }
                // d) 全量 ps——原厂占用服务现形（iPod/iAP2/CarPlay/USB 守护进程）。
                exec("ps").lines().filter { it.isNotBlank() }.take(200)
                    .forEach { log("ps-all: ${it.trim()}") }

                // ---- 4. root 探测 + su 穷举 + 开网络 ADB ----
                val suPaths = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su")
                    .filter { File(it).exists() }
                val suVariants = listOf("su -c id", "su 0 id", "/system/xbin/su -c id", "daemonsu -c id")
                val suResults = suVariants.associateWith { cmd -> exec(cmd).trim().take(120) }
                suResults.forEach { (cmd, out) -> log("root: [$cmd] => ${if (out.isBlank()) "<空>" else out}") }
                val suOk = suResults.values.any { it.contains("uid=0") }

                // e) USB 设备节点持有者扫描——谁 open 了 /dev/bus/usb/*（原厂服务占 usbmux 的直接证据）。
                //    读他进程 fd 需要 root：su 可用走 su，无 root 直试（大概率空结果）。
                //    两种路径下循环体都只经过一层 shell 展开，同一字符串两用。
                val fdScanCmd =
                    "for p in /proc/[0-9]*/fd; do " +
                        "if ls -l \"\$p\" 2>/dev/null | grep -q 'bus/usb'; then " +
                        "d=\${p%/fd}; " +
                        "echo \"\$d|\$(tr '\\0' ' ' < \"\$d/cmdline\" 2>/dev/null)\"; " +
                        "fi; done"
                val holders = if (suOk) exec("su -c '$fdScanCmd'") else exec(fdScanCmd)
                holders.lines().filter { it.isNotBlank() }.take(40)
                    .forEach { log("usb-holder: ${it.trim()}") }
                if (holders.isBlank()) log("usb-holder: <无结果（无 root 或无进程持有 USB 节点）>")

                // su 二进制指纹 + 内嵌字符串（找白名单配置文件路径）
                val suBin = suPaths.firstOrNull()
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
                                val len = i - start
                                if (len >= 5) words.add(String(bytes, start, len, Charsets.US_ASCII))
                                start = -1
                            }
                        }
                        val keys = listOf("/data", "/system", ".db", "super", "allow", "whitelist", "conf", "xml", "adb", "uid")
                        words.filter { w -> keys.any { w.contains(it, ignoreCase = true) } }
                            .distinct().take(30)
                            .forEach { log("su: str=$it") }
                    }.onFailure { log("su: strings 分析失败 ${it.message}") }
                }

                // daemonsu 运行参数（/proc 对同 uid 不可见的进程 cmdline 也可能可读）
                exec("ls -l /proc/*/cmdline 2>/dev/null | head -1")
                runCatching {
                    File("/proc").listFiles { d -> d.name.matches(Regex("\\d+")) }?.forEach { p ->
                        runCatching {
                            val cmd = p.resolve("cmdline").readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ').trim()
                            if (cmd.contains("su") || cmd.contains("daemon")) log("proc: ${p.name} $cmd")
                        }
                    }
                }

                // su 二进制全量 base64 进日志（PC 端逆向允许 uid 列表）
                if (suBin != null) {
                    runCatching {
                        val data = File(suBin).readBytes()
                        log("su-b64: begin size=${data.size}")
                        val b64 = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
                        b64.chunked(96).forEachIndexed { i, line -> log("su-b64: $i $line") }
                        log("su-b64: end")
                    }.onFailure { log("su-b64: 导出失败 ${it.message}") }
                }

                // 全志 root 开关属性（只读探测，不动系统）
                listOf("persist.sys.root_access", "ro.sys.root_access", "persist.adb.tcp.port", "service.adb.tcp.port")
                    .forEach { log("root: prop $it=${exec("getprop $it").trim()}") }

                // 4a. system uid（testkey sharedUserId 版）或 root：直接 setprop（不经过 su）
                var enable = exec(
                    "setprop persist.adb.tcp.port 5555; setprop service.adb.tcp.port 5555; " +
                        "stop adbd; start adbd; sleep 1; getprop service.adb.tcp.port",
                ).trim()
                val directOk = enable.contains("5555")
                log("root: 直试 setprop 开网络 ADB port=[$enable]${if (directOk) "（成功）" else "（无权限，属正常 uid）"}")
                // 4b. su 可用（白名单放行）则走 su 开 ADB
                if (!directOk && suOk) {
                    enable = exec(
                        "su -c 'setprop persist.adb.tcp.port 5555; setprop service.adb.tcp.port 5555; " +
                            "stop adbd; start adbd; sleep 1; getprop service.adb.tcp.port'",
                    ).trim()
                    log("root: su 开启网络 ADB 结果 port=[$enable]（5555=成功，PC 可 adb connect）")
                }

                log("=== 系统诊断结束 ===")
            } catch (error: Throwable) {
                log("诊断异常: ${error.javaClass.simpleName} ${error.message}")
            }
            if (context is android.app.Activity) {
                context.runOnUiThread {
                    val opened = logs.any {
                        it.startsWith("root: 直试 setprop 开网络 ADB port=[5555") ||
                            it.startsWith("root: su 开启网络 ADB 结果 port=[5555")
                    }
                    val headline = if (opened) "诊断完成：已开启网络 ADB（root），PC 执行 adb connect 车机IP:5555"
                    else "诊断完成，结果已写入日志（上传后可查）"
                    // 1.1.3：诊断结果弹窗展示，用户确认后关闭。su-b64 全量导出行太长（1000+ 行）不进弹窗，
                    // 其余诊断行全部展示（结果已同步写入日志文件）。
                    val body = StringBuilder(headline).append("\n\n")
                    logs.filterNot { it.startsWith("su-b64:") }.forEach { body.append(it).append('\n') }
                    body.append("\n（su 二进制 base64 导出仅写入日志，不上屏）")
                    val scroll = android.widget.ScrollView(context).apply {
                        addView(
                            android.widget.TextView(context).apply {
                                text = body.toString()
                                setTextColor(0xFFDDDDDD.toInt())
                                textSize = 11f
                                typeface = android.graphics.Typeface.MONOSPACE
                                setPadding(36, 24, 36, 24)
                            },
                        )
                    }
                    android.app.AlertDialog.Builder(context)
                        .setTitle("系统诊断结果")
                        .setView(scroll)
                        .setPositiveButton("确认", null)
                        .show()
                }
            }
        }.start()
    }

    /** sh -c 执行（支持管道），超时保护 8 秒，失败返回空串。 */
    private fun exec(command: String): String = runCatching {
        val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        out
    }.getOrDefault("")
}
