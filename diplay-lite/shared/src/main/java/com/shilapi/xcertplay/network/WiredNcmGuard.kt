package com.shilapi.xcertplay.network

import android.util.Log
import com.shilapi.xcertplay.transport.IphoneCarPlayConfiguration
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * vc74（TS7 鼎微 MUSB 平台专用）：内核 cdc_ncm 与 App 抢 NCM 接口的解绑守护。
 *
 * 背景（20261013 TS7 实测）：iPhone 重枚举到 CarPlay 配置 6 后，内核 cdc_ncm 驱动会
 * 周期性绑定 NCM 控制接口（sysfs 1-1:6.3，netdev 反复 up），与 App 的 usbfs claim
 * 打架，内核侧的额外控制传输曾是「iPhone 收到帧但不回」的成因之一。电脑侧外置守护
 * （每 2s adb 检查 + su 解绑）实测能让会话长期稳定，本类将其内置到 App：
 *
 * 每 [POLL_MILLIS] 扫描 /sys/bus/usb/devices，发现配置 6 的 NCM 控制口（6.3）被
 * 内核绑定即通过 su 写 driver/unbind。无 root / 无 sysfs / 非此类平台时静默
 * 降级（首次探测失败即停止轮询，非致命，仅诊断日志）。
 *
 * 生命周期：有线栈 bring-up 时 [start]，closeWiredStack 时 [close]。
 */
class WiredNcmGuard(private val onDiagnostic: (String) -> Unit = {}) : Closeable {
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var rootAvailable = true

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread(::runGuard, "ncm-guard").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        val thread = worker
        worker = null
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(JOIN_TIMEOUT_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun runGuard() {
        while (running.get()) {
            try {
                if (rootAvailable) scanOnce()
            } catch (error: Throwable) {
                // sysfs 缺失（非车机平台）或 su 不可用：置停轮询，降级为无守护（非致命）。
                rootAvailable = false
                Log.w(TAG, "ncm guard degraded: ${error.javaClass.simpleName}: ${error.message}")
                onDiagnostic("ncm guard degraded: ${error.message}")
            }
            try {
                Thread.sleep(POLL_MILLIS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun scanOnce() {
        val root = File(SYS_USB_DEVICES)
        val entries = root.listFiles() ?: throw IllegalStateException("sysfs usb devices unreadable")
        for (entry in entries) {
            if (!CONFIG6_INTERFACE.matches(entry.name)) continue
            val driverDir = File(entry, "driver")
            if (!driverDir.exists()) continue
            val unbind = File(driverDir, "unbind")
            if (!unbind.isFile) continue
            val sh = ProcessBuilder("su", "-c", "echo -n '${entry.name}' > '${unbind.absolutePath}'")
                .redirectErrorStream(true)
                .start()
            val output = sh.inputStream.bufferedReader().readText().trim()
            val exit = sh.waitFor()
            if (exit == 0) {
                Log.i(TAG, "ncm guard unbound kernel driver from ${entry.name}")
                onDiagnostic("ncm guard unbound ${entry.name}")
            } else {
                Log.w(TAG, "ncm guard unbind ${entry.name} failed exit=$exit $output")
            }
        }
    }

    private companion object {
        const val TAG = IphoneCarPlayConfiguration.TAG
        const val SYS_USB_DEVICES = "/sys/bus/usb/devices"
        const val POLL_MILLIS = 2_000L
        const val JOIN_TIMEOUT_MILLIS = 2_000L
        // 配置 6 的接口名形如 1-1:6.0 … 1-1:6.6（冒号后 6 = bConfigurationValue）。
        // vc75 收窄：只解绑 6.3（3/4 组的 NCM 控制口）——外置守护实测成功的行为就是只动
        // 6.3。vc74 曾解绑全部 6.x，导致 App 已 claim 的数据口 6.4 也被解绑，内核
        // usb_unbind_interface 会复位 alt setting → bulk-out 失效（"bulk-out not ready"，
        // 两个建立窗口全超时，20:56:57 实测）。6.1 是 MUX 口，同样不许动。
        val CONFIG6_INTERFACE = Regex(".+:6\\.3$")
    }
}
