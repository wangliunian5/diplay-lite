package com.shilapi.xcertplay.usbbt

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

/**
 * vc167（外置 USB 蓝牙 dongle 第 2 步 B 批）：dongle 枚举与 USB 权限。
 * 移植自竞品 usbbt.UsbBtSession.java 的 attachedDongle/hasPermission/requestPermission 部分
 * （会话生命周期/配对/重连逻辑随 C/D 批）。
 *
 * dongle 识别（UsbBluetoothHci.hciInterface 同款判据）：
 * 接口类 0xE0（Wireless Controller）/ 子类 1（Radio Frequency）/ 协议 1（Bluetooth），
 * 且带 interrupt IN 端点（events）。整设备级同判据兜底（单接口 dongle）。
 */
internal object UsbBtSession {
    const val TAG = "DiPlayUsbBt"
    const val PERMISSION_ACTION = "com.shilapi.xcertplay.usbbt.USB_PERMISSION"

    fun manager(context: Context): UsbManager? =
        context.applicationContext.getSystemService(Context.USB_SERVICE) as? UsbManager

    /** 枚举当前插入的蓝牙 dongle（首个 HCI 接口判据命中者）；无则 null。 */
    fun findDongle(manager: UsbManager): UsbDevice? =
        manager.deviceList.values.firstOrNull { hciInterface(it) != null }

    /** 设备是否带蓝牙 HCI 接口（竞品 UsbBluetoothHci.hciInterface 移植）。 */
    fun hciInterface(device: UsbDevice): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == 0xe0 && iface.interfaceSubclass == 1 && iface.interfaceProtocol == 1 &&
                interruptInEndpoint(iface) != null
            ) {
                return iface
            }
        }
        val wholeDevice =
            device.deviceClass == 0xe0 && device.deviceSubclass == 1 && device.deviceProtocol == 1
        if (wholeDevice && device.interfaceCount > 0 && interruptInEndpoint(device.getInterface(0)) != null) {
            return device.getInterface(0)
        }
        return null
    }

    /** HCI 接口的 events 端点（interrupt IN）；批量端点判据留给 C 批 UsbBtDriver。 */
    private fun interruptInEndpoint(iface: UsbInterface): UsbEndpoint? {
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT && ep.direction == UsbConstants.USB_DIR_IN) {
                return ep
            }
        }
        return null
    }

    /** "04x:%04x + 可选产品名" 形式的标识（getProductName 为 API21+，须守卫）。 */
    fun deviceLabel(device: UsbDevice): String {
        val base = "%04x:%04x".format(device.vendorId, device.productId)
        if (Build.VERSION.SDK_INT >= 21) {
            val name = device.productName
            if (!name.isNullOrBlank()) return "$base ($name)"
        }
        return base
    }

    fun hasPermission(context: Context, device: UsbDevice): Boolean =
        try {
            manager(context)?.hasPermission(device) == true
        } catch (_: Throwable) {
            false
        }

    /**
     * 触发系统 USB 权限弹窗并**同步等待**广播回报（t3 反馈 vc184：原地等授权，
     * 允许后本轮直接继续建链，免去「允许后再点一次连接」；ROM 不弹窗时也能靠超时
     * + 日志 granted= 行判别「没弹窗」还是「被拒绝」）。
     *
     * 返回 true = 广播回报 granted 且 hasPermission 复核为真（个别 ROM 广播 granted
     * 但授权未落地的兜底）；false = 被拒 / 超时 / 复核失败。
     */
    fun awaitPermission(context: Context, device: UsbDevice, timeoutMillis: Long): Boolean {
        val app = context.applicationContext
        val usb = manager(app) ?: return false
        val latch = java.util.concurrent.CountDownLatch(1)
        var granted = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                Log.i(TAG, "usb permission granted=$granted device=${deviceLabel(device)}")
                try {
                    app.unregisterReceiver(this)
                } catch (_: Throwable) {
                }
                latch.countDown()
            }
        }
        val filter = IntentFilter(PERMISSION_ACTION)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                app.registerReceiver(receiver, filter)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "permission receiver register failed: ${e.javaClass.simpleName}")
        }
        // API31+ 系统要往 intent 填 extras，必须 MUTABLE；老平台 FLAG_IMMUTABLE 常量本身是 API23+，
        // 用 SDK_INT 守卫常量引用（同 IphoneUsbHost.permissionPendingIntent 的写法）。
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
                }
            )
        val pi = PendingIntent.getBroadcast(
            app,
            0,
            Intent(PERMISSION_ACTION).setPackage(app.packageName),
            flags,
        )
        usb.requestPermission(device, pi)
        Log.i(TAG, "usb permission requested for ${deviceLabel(device)} (await ${timeoutMillis}ms)")
        val done = try {
            latch.await(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        if (!done) {
            Log.w(TAG, "usb permission wait timeout (${timeoutMillis}ms) — ROM 未弹窗或用户未响应")
            try {
                app.unregisterReceiver(receiver)
            } catch (_: Throwable) {
            }
            return false
        }
        // 广播 granted=true 后复核：防个别 ROM 广播与授权状态不同步
        val ok = granted && hasPermission(app, device)
        Log.i(TAG, "usb permission final ok=$ok (granted=$granted, hasPermission=${hasPermission(app, device)})")
        return ok
    }
}
