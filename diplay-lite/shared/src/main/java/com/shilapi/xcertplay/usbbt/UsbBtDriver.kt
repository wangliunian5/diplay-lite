package com.shilapi.xcertplay.usbbt

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.shilapi.xcertplay.transport.LegacyUsbHostNative
import java.io.Closeable

/**
 * vc167（外置 USB 蓝牙 dongle 第 2 步 B 批）：USB 传输层探活。
 * 移植自竞品 usbbt.UsbBtDriver.java 的 open/claim + HCI 命令收发骨架；
 * 双读 daemon + 单写线程（供 UsbBtHost/L2CAP 持续消费）随 C 批再落——
 * B 批只需「同步发一条命令、同步等 Command Complete」，因此用同步请求/应答。
 *
 * 三端点形态（竞品 UsbBluetoothHci.endpointsOf 同款判据）：
 * events = interrupt IN；aclIn/aclOut = bulk IN/OUT（B 批不读不写，仅校验存在）。
 */
internal class UsbBtDriver private constructor(
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val events: UsbEndpoint,
    private val aclIn: UsbEndpoint?,
    private val aclOut: UsbEndpoint?,
) : Closeable {
    companion object {
        /**
         * 打开 dongle 并占用 HCI 接口。claim 先走 framework（force=false），
         * 失败再试 LegacyUsbHostNative.forceClaim（vc101 自编译 usbfs 兜底，
         * 等价竞品 UsbDefaultGrant/usbfs_compat 路径，不新增 native 依赖）。
         */
        fun open(context: Context, device: UsbDevice): UsbBtDriver? {
            val usb = UsbBtSession.manager(context) as? UsbManager ?: return null
            val iface = UsbBtSession.hciInterface(device) ?: return null
            val connection = usb.openDevice(device) ?: return null
            if (!connection.claimInterface(iface, false)) {
                var forced = false
                if (LegacyUsbHostNative.available) {
                    try {
                        forced = LegacyUsbHostNative.forceClaim(connection.fileDescriptor, iface.id) == 0
                    } catch (_: Throwable) {
                    }
                }
                if (!forced) {
                    try {
                        connection.close()
                    } catch (_: Throwable) {
                    }
                    return null
                }
            }
            // 批量端点按形态识别（无则置 null：个别 dongle ACL 面挂在别的接口，B 批不依赖）
            var aclIn: UsbEndpoint? = null
            var aclOut: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN && aclIn == null) aclIn = ep
                    if (ep.direction == UsbConstants.USB_DIR_OUT && aclOut == null) aclOut = ep
                }
            }
            return UsbBtDriver(connection, iface, findEvents(iface), aclIn, aclOut)
        }

        private fun findEvents(iface: UsbInterface): UsbEndpoint {
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT &&
                    ep.direction == UsbConstants.USB_DIR_IN
                ) {
                    return ep
                }
            }
            throw IllegalStateException("HCI interface has no interrupt IN endpoint")
        }
    }

    /**
     * 同步 HCI 命令/应答：控制端点发命令，轮询 events 端点等匹配 opcode 的
     * Command Complete。返回其返回参数（status 一定为 0）；
     * 控制传输错误 / 超时返回 null；命令被控制器拒绝抛 [HciStatusException]。
     */
    fun hciCommand(opcode: Int, params: ByteArray = ByteArray(0), timeoutMillis: Long = 2000L): ByteArray? {
        val packet = HciCommands.command(opcode, params)
        val sent = connection.controlTransfer(
            0x20, // CLASS | HOST_TO_DEVICE（竞品 stop() 同款）
            0, 0, 0,
            packet, packet.size,
            1000,
        )
        if (sent < 0) return null // 控制传输错误
        val buffer = ByteArray(64)
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            val n = connection.bulkTransfer(events, buffer, buffer.size, 200)
            if (n <= 0) continue // 200ms 轮询窗口无事件，继续等到 deadline
            val complete =
                HciReturnParameters.parseCommandComplete(buffer.copyOfRange(0, n)) ?: continue
            if (complete.opcode != opcode) continue
            if (complete.status != 0) throw HciStatusException("0x%04x".format(opcode), complete.status)
            return complete.returnParams
        }
        return null // HCI 超时
    }

    /** B 批验证出口：Reset 成功后读本机 BD_ADDR，返回 "xx:xx:xx:xx:xx:xx"。 */
    fun readBdAddr(): String? {
        hciCommand(HciCommands.RESET) ?: return null
        val params = hciCommand(HciCommands.READ_BD_ADDR) ?: return null
        return HciReturnParameters.bdAddrOfReadBdAddr(params)
    }

    class HciStatusException(step: String, val status: Int) :
        Exception("$step status 0x%02x".format(status))

    override fun close() {
        try {
            connection.releaseInterface(iface)
        } catch (_: Throwable) {
        }
        try {
            connection.close()
        } catch (_: Throwable) {
        }
    }
}
