package com.shilapi.xcertplay.usbbt

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream

/**
 * vc167（外置 USB 蓝牙 dongle 第 2 步 B 批）/ vc181（外置 USB 蓝牙 C/D 批）：usbbt 接入缝。
 *
 * 接入缝（唯一）：CarPlayController 无线建链处 `UsbBtBootstrapStream.open(context)`，
 * 该文件一行不改。C/D 批起 [open] 走完整建链：
 * 枚举 dongle → 权限 → 打开驱动 → Host 初始化（HCI Reset→…→ScanEnable）
 * → 等待 iPhone 回连（SSP Just-Works 自动配对）→ 认证/加密 → SDP 查 RFCOMM 信道
 * → RFCOMM 建链 → 返回跑在 iAP2 RFCOMM DLC 上的 [UsbBtRfcommStream]。
 * 状态沿途写 [lastStatusText]（设置页显示 + LiteLog 落日志）；失败抛带状态的异常。
 */
class UsbBtBootstrapStream private constructor() : BlockingDuplexByteStream {
    override fun send(data: ByteArray) {
        throw UnsupportedOperationException("usbbt 接入缝不可实例化（仅用 open() 静态入口）")
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        throw UnsupportedOperationException("usbbt 接入缝不可实例化（仅用 open() 静态入口）")
    }

    override fun close() = Unit

    companion object {
        private const val TAG = UsbBtSession.TAG

        /** 建链各阶段超时（毫秒）：初始化 / 等 iPhone 回连+配对+建 RFCOMM 的总窗口。 */
        private const val INIT_TIMEOUT_MILLIS = 15_000L
        private const val LINK_TIMEOUT_MILLIS = 90_000L
        private const val TAKE_SLICE_MILLIS = 1000L

        /** dongle 身份（上游 UsbBtSettings.name 动态名落死；COD/信道/IO 能力对照上游）。 */
        private val IDENTITY = UsbBtIdentity(
            name = "DiPlay CarPlay",
            classOfDevice = 2098184,
            iap2RfcommChannel = 1,
            ioCapability = 1, // NoInputNoOutput（Just-Works）
        )

        /** 最近一次探测/建链的逐级状态（设置页状态行直接显示；未探测过 = 空串）。 */
        @Volatile
        var lastStatusText: String = ""
            private set

        /** 探活成功的标志：dongle 本机地址（初始化完成后可读）。 */
        @Volatile
        var lastBdAddr: String? = null
            private set

        /**
         * usbbt 建链入口（开关开启时由 CarPlayController 调用，工作线程）。
         * 成功返回跑在 iAP2 RFCOMM DLC 上的流；任何一步失败抛带状态文本的异常。
         */
        fun open(context: Context): BlockingDuplexByteStream {
            val usb = UsbBtSession.manager(context) ?: fail("USB 服务不可用")
            val dongle = UsbBtSession.findDongle(usb)
                ?: fail("未检测到 USB 蓝牙 dongle（请确认已插入）")
            status("已检测到 dongle（${UsbBtSession.deviceLabel(dongle)}）")
            if (!UsbBtSession.hasPermission(context, dongle)) {
                UsbBtSession.requestPermission(context, dongle)
                fail("无 USB 权限，已请求系统授权（允许后重试连接）")
            }
            // host 与 driver 互需引用：先开 driver（转发 listener 兜住早期事件），再建 host 回接
            val hostRef = arrayOfNulls<UsbBtHost>(1)
            val forward = object : UsbBtDriver.Listener {
                override fun onHciEvent(event: HciEvent) {
                    hostRef[0]?.onHciEvent(event)
                }

                override fun onAclPacket(packet: UsbBtDriver.AclPacket) {
                    hostRef[0]?.onAclPacket(packet)
                }

                override fun onTick() {
                    hostRef[0]?.onTick()
                }

                override fun onTransportFailure(reason: String) {
                    status("传输中断：$reason")
                    hostRef[0]?.onTransportFailure(reason)
                }
            }
            var host: UsbBtHost? = null
            var driver: UsbBtDriver? = null
            try {
                val ready = java.util.concurrent.CountDownLatch(1)
                driver = UsbBtDriver.open(context, dongle, forward)
                    ?: fail("打开 dongle 失败：设备刚重枚举或打开被拒")
                status("已打开 dongle（${UsbBtSession.deviceLabel(dongle)}）")
                host = UsbBtHost(IDENTITY, driver, { msg -> Log.i(TAG, msg) }).also {
                    it.onStatus = { text -> status(text) }
                    it.onReady = { ready.countDown() }
                    hostRef[0] = it
                }
                host.start()
                if (!ready.await(INIT_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    fail("dongle 初始化超时（${host.statusText}）")
                }
                host.localAddress()?.let { lastBdAddr = it.toString() }
                // 等 iPhone 回连 → Just-Works 配对 → 加密 → SDP/RFCOMM → DLC
                status("等待 iPhone 连接（首次需在手机蓝牙设置中配对）…")
                val deadline = System.currentTimeMillis() + LINK_TIMEOUT_MILLIS
                while (true) {
                    when (host.phase) {
                        UsbBtHost.Phase.FAILED -> fail("建链失败：${host.statusText}")
                        UsbBtHost.Phase.STOPPED -> fail("dongle 已停止")
                        else -> Unit
                    }
                    val remain = deadline - System.currentTimeMillis()
                    if (remain <= 0) fail("等待 iPhone 连接超时（${host.statusText}）")
                    val dlc = host.awaitIap2Dlc(minOf(remain, TAKE_SLICE_MILLIS))
                    if (dlc != null) {
                        return UsbBtRfcommStream(dlc, host)
                    }
                }
            } catch (e: Throwable) {
                try {
                    host?.stop()
                } catch (_: Throwable) {
                }
                driver?.let {
                    try {
                        it.close()
                    } catch (_: Throwable) {
                    }
                }
                throw e
            }
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

        private fun fail(reason: String): Nothing {
            status(reason)
            throw java.io.IOException(reason)
        }
    }
}
