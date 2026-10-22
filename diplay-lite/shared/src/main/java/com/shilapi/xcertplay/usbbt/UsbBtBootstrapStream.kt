package com.shilapi.xcertplay.usbbt

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream

/**
 * vc167（外置 USB 蓝牙 dongle 第 2 步）：usbbt 接入缝占位流。
 *
 * 接入缝（唯一）：CarPlayController 无线建链处 `val stream = BluetoothRfcommDuplexStream(socket)`。
 * 本开关开启时改由 [open] 供流——B 批只做「枚举 → openDevice → HCI Reset → 读 BD_ADDR」
 * 逐级探活（状态经 [lastStatusText] 供设置页显示 + LiteLog 同步落日志），
 * 尚无可用的 iAP2/RFCOMM 字节流（C/D 批），因此 open() 在探活后以明确异常结束。
 * send/recv/close 不会被触达（open 先抛），实现为防御性抛错。
 */
class UsbBtBootstrapStream private constructor() : BlockingDuplexByteStream {
    override fun send(data: ByteArray) {
        throw UnsupportedOperationException("usbbt 协议栈未就绪（C/D 批未移植）")
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        throw UnsupportedOperationException("usbbt 协议栈未就绪（C/D 批未移植）")
    }

    override fun close() = Unit

    companion object {
        private const val TAG = UsbBtSession.TAG

        /** 最近一次探测的逐级状态（设置页状态行直接显示；未探测过 = 空串）。 */
        @Volatile
        var lastStatusText: String = ""
            private set

        /** B 阶段成功的标志：HCI Reset 成功后的 dongle 本机地址。 */
        @Volatile
        var lastBdAddr: String? = null
            private set

        /**
         * usbbt 建链入口（开关开启时由 CarPlayController 调用，工作线程）。
         * 先跑一轮 B 批探活，随后固定抛「协议栈未就绪」——异常信息携带最新状态，
         * 便于现场从连接错误直接读到探测进度。
         */
        fun open(context: Context): BlockingDuplexByteStream {
            try {
                probe(context)
            } catch (e: Throwable) {
                Log.w(TAG, "probe aborted: $e")
                status("探测中止：${e.message ?: e.javaClass.simpleName}")
            }
            throw UnsupportedOperationException(
                "usbbt 协议栈未就绪（C/D 批未移植）；最新探测：$lastStatusText",
            )
        }

        /** 供设置页状态行：优先显示真实探测进度，未探测过则退回枚举结果。 */
        fun describe(context: Context): String {
            lastStatusText.takeIf { it.isNotBlank() }?.let { return it }
            val usb = UsbBtSession.manager(context)
                ?: return "USB 服务不可用"
            val dongle = UsbBtSession.findDongle(usb)
                ?: return "未检测到 USB 蓝牙 dongle（请确认已插入）"
            return "已检测到 dongle（${UsbBtSession.deviceLabel(dongle)}），待连接时探活"
        }

        private fun status(text: String) {
            lastStatusText = text
            Log.i(TAG, text)
        }

        private fun probe(context: Context) {
            val usb = UsbBtSession.manager(context) ?: run {
                status("USB 服务不可用")
                return
            }
            val dongle = UsbBtSession.findDongle(usb) ?: run {
                status("未检测到 USB 设备（无蓝牙 dongle，请确认已插入）")
                return
            }
            status("已检测到 dongle（${UsbBtSession.deviceLabel(dongle)}）")
            if (!UsbBtSession.hasPermission(context, dongle)) {
                UsbBtSession.requestPermission(context, dongle)
                status("无 USB 权限，已请求系统授权（允许后重试连接）")
                return
            }
            val driver = UsbBtDriver.open(context, dongle)
            if (driver == null) {
                status("打开 dongle 失败：设备刚重枚举或打开被拒")
                return
            }
            try {
                status("已打开 dongle（${UsbBtSession.deviceLabel(dongle)}）")
                val bdAddr = try {
                    driver.readBdAddr()
                } catch (e: UsbBtDriver.HciStatusException) {
                    status("HCI 命令被拒：${e.message}")
                    return
                }
                if (bdAddr == null) {
                    status("HCI 超时（事件端点无应答或 bulk 传输错误）")
                    return
                }
                lastBdAddr = bdAddr
                status("HCI Reset 成功，BD_ADDR=$bdAddr")
            } finally {
                try {
                    driver.close()
                } catch (_: Throwable) {
                }
            }
        }
    }
}
