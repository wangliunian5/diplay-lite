package com.shilapi.xcertplay.usbbt

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import com.shilapi.xcertplay.transport.LegacyUsbHostNative
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch

/**
 * vc167（外置 USB 蓝牙 dongle 第 2 步 B 批）/ vc181（外置 USB 蓝牙 C/D 批）：USB 传输层。
 * 移植自上游参考实现 usbbt.UsbBtDriver.java：claim 接口（framework 失败走
 * [LegacyUsbHostNative.forceClaim] 兜底）+ 事件/ACL 双读 daemon + 单写锁 + 同步 HCI 命令。
 *
 * C 批扩展（对照上游 readLoop/stop）：
 * - events（interrupt IN）读 daemon：持续解帧事件包 → [Listener.onHciEvent]，每约 1s 回调
 *   [Listener.onTick]（供 host 超时推进）；
 * - aclIn（bulk IN）读 daemon：ACL 包解帧 → [Listener.onAclPacket]；
 * - 同步 [hciCommand] 改为「事件线程收 Command Complete + 调用方闩锁等待」（上游无同步
 *   命令，本仓 B 批形态保留：超时 null、被拒抛 [HciStatusException]）；
 * - [sendAcl]：bulk OUT 单写（writeLock 串行化）；
 * - 连续传输失败超过阈值判 dongle 掉线 → [Listener.onTransportFailure]；
 * - [close] 幂等：停线程 → 尽力发 RESET → release/close（上游 stop 还有内核驱动重挂，
 *   本仓 LegacyUsbHostNative 无对应入口，降级为仅释放，见交付说明）。
 *
 * 三端点形态（上游 UsbBluetoothHci.endpointsOf 同款判据）：
 * events = interrupt IN；aclIn/aclOut = bulk IN/OUT。
 */
internal class UsbBtDriver private constructor(
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val events: UsbEndpoint,
    private val aclIn: UsbEndpoint?,
    private val aclOut: UsbEndpoint?,
    private val listener: Listener,
) : Closeable {
    /** 驱动事件出口（host 在 C5 实现；B 批探活用 [NO_OP]）。 */
    interface Listener {
        fun onHciEvent(event: HciEvent)

        fun onAclPacket(packet: AclPacket)

        /** 事件线程每约 1 秒回调一次（host 用它推进超时/重试）。 */
        fun onTick()

        /** 传输面持续失败（dongle 拔出/复位），驱动已停止读线程。 */
        fun onTransportFailure(reason: String)
    }

    /** ACL 面数据包（上游 HciAcl$Packet 移植）。 */
    class AclPacket(val handle: Int, val pbFlag: Int, val data: ByteArray)

    companion object {
        private const val TAG = UsbBtSession.TAG

        /** 无监听者（B 批探活）。 */
        val NO_OP: Listener = object : Listener {
            override fun onHciEvent(event: HciEvent) {}
            override fun onAclPacket(packet: AclPacket) {}
            override fun onTick() {}
            override fun onTransportFailure(reason: String) {}
        }

        /** 连续 bulk 传输失败阈值（上游 readLoop 同款：>20 次判 dongle 掉线）。 */
        private const val MAX_CONSECUTIVE_TRANSFER_FAILURES = 20

        /**
         * 打开 dongle 并占用 HCI 接口。claim 先走 framework（force=false），
         * 失败再试 LegacyUsbHostNative.forceClaim（vc101 自编译 usbfs 兜底，
         * 等价上游 UsbDefaultGrant/usbfs_compat 路径，不新增 native 依赖）。
         */
        fun open(
            context: Context,
            device: UsbDevice,
            listener: Listener = NO_OP,
        ): UsbBtDriver? {
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
            // 批量端点按形态识别（无则置 null：个别 dongle ACL 面挂在别的接口）
            var aclIn: UsbEndpoint? = null
            var aclOut: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN && aclIn == null) aclIn = ep
                    if (ep.direction == UsbConstants.USB_DIR_OUT && aclOut == null) aclOut = ep
                }
            }
            return UsbBtDriver(connection, iface, findEvents(iface), aclIn, aclOut, listener)
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

    @Volatile
    private var running = true

    @Volatile
    private var closed = false

    /** 单写锁：控制端点命令与 ACL bulk OUT 串行化（上游 writeLock 同款）。 */
    private val writeLock = Object()

    /** 同步命令等待表：opcode → 闩锁（事件线程收到匹配 Command Complete 后放行）。 */
    private val pendingCommands = ConcurrentHashMap<Int, PendingCommand>()

    private class PendingCommand {
        val latch = CountDownLatch(1)

        @Volatile
        var complete: HciEvent.CommandComplete? = null
    }

    private val eventThread = Thread({ readLoop(events, EVENT_HEADER_BYTES, true) }, "usb-bt-events").apply {
        isDaemon = true
    }

    private val aclThread = aclIn?.let {
        Thread({ readLoop(it, ACL_HEADER_BYTES, false) }, "usb-bt-acl").apply { isDaemon = true }
    }

    init {
        eventThread.start()
        aclThread?.start()
    }

    /**
     * 同步 HCI 命令/应答：控制端点发命令，事件线程等匹配 opcode 的 Command Complete。
     * 返回其返回参数（status 已确认为 0）；控制传输错误 / 超时返回 null；
     * 命令被控制器拒绝抛 [HciStatusException]。
     */
    fun hciCommand(opcode: Int, params: ByteArray = ByteArray(0), timeoutMillis: Long = 2000L): ByteArray? {
        val packet = HciCommands.command(opcode, params)
        val pending = PendingCommand()
        val waiter = pendingCommands.putIfAbsent(opcode, pending) ?: pending
        if (waiter !== pending) {
            // 同 opcode 并发命令（本实现不会出现，防御）：直接拒绝
            Log.w(TAG, "concurrent hci command 0x%04x rejected".format(opcode))
            return null
        }
        try {
            val sent = synchronized(writeLock) {
                connection.controlTransfer(
                    0x20, // CLASS | HOST_TO_DEVICE（上游 stop() 同款）
                    0, 0, 0,
                    packet, packet.size,
                    1000,
                )
            }
            if (sent < 0) return null // 控制传输错误
            if (!waiter.latch.await(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                return null // HCI 超时
            }
            val complete = waiter.complete ?: return null
            val ret = complete.returnParams
            if (ret.isEmpty()) return null
            if (ret[0].toInt() != 0) throw HciStatusException("0x%04x".format(opcode), ret[0].toInt() and 0xff)
            return ret.copyOfRange(1, ret.size)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } finally {
            pendingCommands.remove(opcode, pending)
        }
    }

    /** B 批验证出口：Reset 成功后读本机 BD_ADDR，返回 "xx:xx:xx:xx:xx:xx"。 */
    fun readBdAddr(): String? {
        hciCommand(HciCommands.RESET) ?: return null
        val params = hciCommand(HciCommands.READ_BD_ADDR) ?: return null
        return HciReturnParameters.bdAddrOfReadBdAddr(params)
    }

    /** ACL bulk OUT 单写（writeLock 串行化）；失败返回 false。[pbFlag]：0x2 起点/0x1 续传。 */
    fun sendAcl(handle: Int, data: ByteArray, pbFlag: Int = 0x2): Boolean {
        val aclOut = aclOut ?: return false
        if (data.size > 0xffff) return false
        // ACL 包头：handle | PB <<12，LE16 数据长度
        val header = ByteArray(4)
        val flags = (handle and 0xfff) or (pbFlag shl 12)
        header[0] = (flags and 0xff).toByte()
        header[1] = ((flags ushr 8) and 0xff).toByte()
        header[2] = (data.size and 0xff).toByte()
        header[3] = ((data.size ushr 8) and 0xff).toByte()
        val packet = header + data
        return synchronized(writeLock) {
            connection.bulkTransfer(aclOut, packet, packet.size, 1000) >= 0
        }
    }

    /**
     * 读循环（上游 readLoop 移植）：按 [headerBytes] 头长解帧（事件=2、ACL=4），
     * 跨 bulk 包拼接；连续失败超阈值触发 [Listener.onTransportFailure] 并退出。
     */
    private fun readLoop(endpoint: UsbEndpoint, headerBytes: Int, isEvent: Boolean) {
        var maxPacketSize = endpoint.maxPacketSize
        if (maxPacketSize < 16) maxPacketSize = 16
        val buffer = ByteArray(maxPacketSize)
        var acc = ByteArray(0)
        var failures = 0
        var lastTickMillis = 0L
        while (running) {
            val n = connection.bulkTransfer(endpoint, buffer, maxPacketSize, 500)
            if (!running) return
            if (n > 0) {
                failures = 0
                acc += buffer.copyOfRange(0, n)
                while (acc.size >= headerBytes) {
                    val total = when (headerBytes) {
                        EVENT_HEADER_BYTES -> (acc[1].toInt() and 0xff) + 2
                        else -> le16Of(acc, 2) + 4
                    }
                    if (total < headerBytes) {
                        acc = ByteArray(0) // 帧长非法，丢弃缓冲
                        break
                    }
                    if (acc.size < total) break // 半包，等下个 bulk 包
                    val frame = acc.copyOfRange(0, total)
                    acc = acc.copyOfRange(total, acc.size)
                    if (isEvent) {
                        dispatchEvent(frame)
                    } else {
                        dispatchAcl(frame)
                    }
                }
            } else if (n < 0) {
                failures++
                if (failures > MAX_CONSECUTIVE_TRANSFER_FAILURES) {
                    Log.w(TAG, (if (isEvent) "event" else "ACL") + " endpoint keeps failing; dongle gone?")
                    listener.onTransportFailure("USB ${if (isEvent) "事件" else "ACL"}端点持续失败（dongle 掉线?）")
                    running = false
                    return
                }
            }
            if (isEvent) {
                val now = System.currentTimeMillis()
                if (now - lastTickMillis >= 1000) {
                    lastTickMillis = now
                    if (running) listener.onTick()
                }
            }
        }
    }

    private fun dispatchEvent(frame: ByteArray) {
        val event = HciEvents.parse(frame) ?: return
        if (event is HciEvent.CommandComplete) {
            val pending = pendingCommands[event.opcode]
            if (pending != null) {
                pending.complete = event
                pending.latch.countDown()
            }
            if (event.returnParams.isNotEmpty() && event.returnParams[0].toInt() != 0) {
                Log.i(
                    TAG,
                    "command 0x%04x status 0x%02x".format(event.opcode, event.returnParams[0].toInt() and 0xff),
                )
            }
        } else if (event is HciEvent.CommandStatus && event.status != 0) {
            Log.i(TAG, "command 0x%04x status 0x%02x".format(event.opcode, event.status))
        }
        try {
            listener.onHciEvent(event)
        } catch (e: Throwable) {
            Log.w(TAG, "hci event dispatch failed: $e")
        }
    }

    private fun dispatchAcl(frame: ByteArray) {
        if (frame.size < ACL_HEADER_BYTES) return
        val flags = le16Of(frame, 0)
        val dataLen = le16Of(frame, 2)
        if (frame.size < dataLen + ACL_HEADER_BYTES) return
        val packet = AclPacket(flags and 0xfff, (flags ushr 12) and 0x3, frame.copyOfRange(4, dataLen + 4))
        try {
            listener.onAclPacket(packet)
        } catch (e: Throwable) {
            Log.w(TAG, "acl dispatch failed: $e")
        }
    }

    private fun le16Of(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)

    class HciStatusException(step: String, val status: Int) :
        Exception("$step status 0x%02x".format(status))

    /**
     * 幂等关闭：停读线程 → 尽力发 RESET（让控制器清链路）→ release/close。
     * 上游 stop() 还会经 native 重挂内核蓝牙驱动（LegacyUsbfs.connectKernelDriver），
     * 本仓 LegacyUsbHostNative 无对应入口，降级为仅释放——dongle 拔插即可恢复系统可用。
     */
    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            running = false
        }
        if (Thread.currentThread() !== eventThread) {
            try {
                eventThread.join(1500L)
            } catch (_: Throwable) {
            }
        }
        aclThread?.let {
            if (Thread.currentThread() !== it) {
                try {
                    it.join(1500L)
                } catch (_: Throwable) {
                }
            }
        }
        synchronized(writeLock) {
            try {
                val packet = HciCommands.command(HciCommands.RESET)
                connection.controlTransfer(0x20, 0, 0, 0, packet, packet.size, 1000)
            } catch (_: Throwable) {
            }
        }
        try {
            connection.releaseInterface(iface)
        } catch (_: Throwable) {
        }
        try {
            connection.close()
        } catch (_: Throwable) {
        }
        Log.i(TAG, "driver stopped; interface #${iface.id} released")
    }
}

private const val EVENT_HEADER_BYTES = 2
private const val ACL_HEADER_BYTES = 4
