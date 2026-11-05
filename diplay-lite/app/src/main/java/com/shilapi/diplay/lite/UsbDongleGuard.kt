package com.shilapi.diplay.lite

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.shilapi.xcertplay.usbbt.UsbBinderGate
import com.shilapi.xcertplay.usbbt.UsbBtBootstrapStream
import com.shilapi.xcertplay.usbbt.UsbBtStandby
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 外置 USB 蓝牙（dongle）兜底：有界自检 / 掉线感知 / root 软复位。
 *
 * vc245 背景（T3 2026-11-05 现场）：dongle 掉线后重新拔插无反应。旧代码有两处盲区：
 * ① MainActivity 的 DETACHED 接收器只处理 Apple 设备（0x05ac），dongle（0x0a12）被忽略；
 * ② openDevice 没有超时，system_server 一旦 D 态卡在 usbdev_open，App 会无限等、也无日志。
 *
 * 本类只做三件可交付的事：
 * 1. 有界自检：openDevice 放独立线程 + 3s 看门狗，超时判 OPEN_TIMEOUT，不再无限等；
 * 2. 三态可区分：设备不在 / 未授权 / 打开超时 / 打开失败，各落一行可判别日志；
 * 3. 软复位（仅 root、仅手动入口触发）：对 dongle 自己的 sysfs 节点写 authorized 0→1。
 *
 * 天花板（实测结论，别宣称已解决）：若 system_server 线程已经 D 态卡在 usbdev_open，
 * openDevice 与 authorized 写都会跟着卡住，App 层无解，只能重启 system_server 或整机。
 */
object UsbDongleGuard {
    const val VENDOR_ID = 0x0a12
    const val PRODUCT_ID = 0x0001

    /** openDevice 看门狗：T3 正常 <300ms，3s 足够区分「慢」与「内核卡死」。 */
    const val OPEN_TIMEOUT_MS = 3_000L

    /**
     * vc246：整段自检看门狗。binder 段仍按 [OPEN_TIMEOUT_MS]=3s 单独判定（超时即置
     * D 态粘性门禁）；整段 25s 是为了容纳「等常驻广播 host 初始化」与 HCI 探针。
     */
    const val CHECK_TIMEOUT_MS = 25_000L

    /** vc246：等常驻广播 host 初始化完成的上限（实测 dongle 冷启 3–10s）。 */
    private const val STANDBY_READY_WAIT_MS = 15_000L

    /** vc246：等 su 软复位的最长时间（超时 destroy，避免 waitFor 永久阻塞）。 */
    private const val SOFT_RESET_WAIT_MS = 8_000L

    // vc246（USB-246-03）：自检成功判据升级——新增「控制器不应答 HCI」。
    enum class Status { OK, ABSENT, NO_PERMISSION, OPEN_TIMEOUT, OPEN_FAILED, HCI_NO_RESPONSE }

    data class Report(val status: Status, val detail: String)

    /** vc246（USB-246-04）：sysfs 旁证是否仍能看到 0a12（只读、不走 binder，D 态下仍可读）。 */
    val sysfsPresent: Boolean
        get() = runCatching { sysfsNode() != null }.getOrDefault(false)

    fun findDongle(manager: UsbManager): UsbDevice? {
        val list = manager.deviceList?.values ?: return null
        return list.firstOrNull { it.vendorId == VENDOR_ID && it.productId == PRODUCT_ID }
            ?: list.firstOrNull { it.vendorId == VENDOR_ID }
    }

    /**
     * 有界自检：**整段**（deviceList / hasPermission / openDevice / close）放独立线程，
     * [timeoutMs] 内未返回 → OPEN_TIMEOUT。
     *
     * vc245：deviceList 与 hasPermission 都是到 system_server 的 binder 调用；system_server
     * 一旦 D 态卡在 usbdev_open，这两步本身就会无限阻塞（此前它们放在看门狗外面，
     * 等于没有超时）。因此整段移入 worker，看门狗只看 latch。
     *
     * 必须在后台线程调用（内部会阻塞最多 timeoutMs）。
     */
    fun check(
        context: Context,
        tag: String,
        timeoutMs: Long = CHECK_TIMEOUT_MS,
        expectStandby: Boolean = false,
    ): Report {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Report?>(null)
        val abandoned = AtomicReference(false)
        val worker = Thread {
            val report = try {
                boundedOpen(context.applicationContext, abandoned, expectStandby)
            } catch (error: Throwable) {
                Report(Status.OPEN_FAILED, "自检异常 ${error.javaClass.simpleName}: ${error.message}")
            }
            result.set(report)
            latch.countDown()
        }
        worker.isDaemon = true
        worker.start()
        val finished = runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!finished) {
            abandoned.set(true)
            val report = Report(
                Status.OPEN_TIMEOUT,
                "自检 ${timeoutMs}ms 未返回：可能内核态 usbdev_open 卡住，App 层无解，需重启车机",
            )
            log(tag, report)
            return report
        }
        val report = result.get() ?: Report(Status.OPEN_FAILED, "自检未产出结果")
        log(tag, report)
        return report
    }

    private fun boundedOpen(
        context: Context,
        abandoned: AtomicReference<Boolean>,
        expectStandby: Boolean,
    ): Report {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return Report(Status.ABSENT, "UsbManager 不可用")
        // vc246（USB-246-03）：三段 binder 分别计时，>3s 即置进程级粘性 D 态门禁。
        val listStarted = System.currentTimeMillis()
        val device = findDongle(manager)
            ?: return Report(Status.ABSENT, "设备列表无 0a12:0001")
        noteBinderSpan("getDeviceList", listStarted)
        val permissionStarted = System.currentTimeMillis()
        val permitted = manager.hasPermission(device)
        noteBinderSpan("hasPermission", permissionStarted)
        if (!permitted) return Report(Status.NO_PERMISSION, "设备在列表但无 USB 授权")
        // vc246：成功判据从「openDevice 非 null」升级为「HCI 真实应答」。
        // **铁律：绝不与常驻广播抢端口。** 常驻广播在跑（或即将启动，如开机自检）时，
        // 另开一个驱动 forceClaim 会把正在初始化的 host 顶掉（vc246 K60 实测：自检探针
        // 把刚启动的 host 打断，20s 后才恢复）。此路径只等 host 初始化结果，不 openDevice。
        if (expectStandby || UsbBtStandby.isRunning) {
            val deadline = System.currentTimeMillis() + STANDBY_READY_WAIT_MS
            while (System.currentTimeMillis() < deadline && !UsbBtStandby.isHostReady) {
                if (abandoned.get()) return Report(Status.OPEN_TIMEOUT, "自检超时（已放弃）")
                if (!UsbBtStandby.isRunning && !expectStandby) break
                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            return if (UsbBtStandby.isHostReady) {
                Report(Status.OK, "常驻广播 host 已初始化（HCI 已应答）${device.deviceName}")
            } else {
                Report(
                    Status.HCI_NO_RESPONSE,
                    "常驻广播 ${STANDBY_READY_WAIT_MS / 1000}s 内未完成 HCI 初始化（${UsbBtStandby.describe()}）",
                )
            }
        }
        val openStarted = System.currentTimeMillis()
        val connection = manager.openDevice(device)
        noteBinderSpan("openDevice", openStarted)
        if (connection == null) return Report(Status.OPEN_FAILED, "openDevice 返回 null")
        runCatching { connection.close() }
        // 超时后 worker 才返回：结果作废（已 close），由看门狗那侧报 OPEN_TIMEOUT。
        if (abandoned.get()) return Report(Status.OPEN_TIMEOUT, "open 在超时后才返回（已 close）")

        val hciError = UsbBtBootstrapStream.probeHci(context)
        return if (hciError == null) {
            Report(Status.OK, "HCI 初始化成功（ReadLocalVersion 有应答）${device.deviceName}")
        } else {
            Report(Status.HCI_NO_RESPONSE, hciError)
        }
    }

    /** vc246（USB-246-03）：binder 调用耗时采样，超 3s 置粘性门禁并落六字段日志。 */
    private fun noteBinderSpan(source: String, startedMs: Long) {
        val elapsed = System.currentTimeMillis() - startedMs
        if (UsbBinderGate.noteCall(source, elapsed)) {
            val line = "usb_246_03: layer=usb trigger=binder-slow holder=none" +
                " gen=${UsbBtStandby.generationSnapshot} present=$sysfsPresent" +
                " next=forbid-open detail=$source ${elapsed}ms"
            LiteLog.log("usbdongle", line)
            Log.w("usbdongle", line)
        }
    }

    /** dongle 对应的 sysfs 节点（只读扫描，不需要 root 通常也能读 idVendor/idProduct）。 */
    fun sysfsNode(): File? {
        val entries = File("/sys/bus/usb/devices").listFiles() ?: return null
        for (dir in entries) {
            val vendor = readHex(File(dir, "idVendor"))
            val product = readHex(File(dir, "idProduct"))
            if (vendor == VENDOR_ID && product == PRODUCT_ID) return dir
        }
        return null
    }

    /**
     * root 软复位：只动 dongle 自己节点的 authorized（0 → 1，等价软拔插）。
     * 仅手动入口触发，调用前后各落一行日志；执行后 1.5s 重枚举再复检。
     * 若 system_server 已 D 态卡住，本调用也会卡住——不做额外超时（su 进程由调用方线程承担）。
     */
    fun softReset(context: Context): Report {
        val node = sysfsNode()
        if (node == null) {
            val report = Report(Status.ABSENT, "/sys 未找到 dongle 节点，无法软复位")
            log("软复位", report)
            return report
        }
        val authorized = File(node, "authorized")
        log("软复位", Report(Status.OK, "节点 ${node.name} authorized=${readText(authorized)} → 写 0 再写 1"))
        val exit = runCatching {
            val process = Runtime.getRuntime().exec(
                arrayOf(
                    "su", "-c",
                    "echo 0 > ${authorized.absolutePath}; sleep 1; echo 1 > ${authorized.absolutePath}",
                ),
            )
            // vc246：Process.waitFor(timeout) 是 API26+，KK 不可用；用看门狗 destroy 兜底。
            val guard = Thread {
                try {
                    Thread.sleep(SOFT_RESET_WAIT_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                runCatching { process.destroy() }
            }
            guard.isDaemon = true
            guard.start()
            val code = process.waitFor()
            guard.interrupt()
            if (code != 0) {
                LiteLog.log("usbdongle", "软复位：su 退出码 $code（可能超时被 destroy）")
            }
            code
        }.getOrDefault(-1)
        log("软复位", Report(if (exit == 0) Status.OK else Status.OPEN_FAILED, "su 退出码 $exit"))
        runCatching { Thread.sleep(1500) }
        return check(context, "软复位后复检")
    }

    private fun log(tag: String, report: Report) {
        val line = "usbdongle [$tag] ${report.status} ${report.detail}"
        LiteLog.log("usbdongle", line)
        Log.i("usbdongle", line)
    }

    private fun readHex(file: File): Int? = runCatching {
        file.readText().trim().toInt(16)
    }.getOrNull()

    private fun readText(file: File): String = runCatching { file.readText().trim() }.getOrDefault("?")
}
