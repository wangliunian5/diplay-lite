package com.shilapi.xcertplay.usbbt

import android.content.Context
import android.content.res.AssetManager
import android.hardware.usb.UsbConstants
import android.os.Build
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
import java.util.concurrent.atomic.AtomicBoolean

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
    /** vc186：诊断出口（接 CarPlayController.debugLog → LiteLog 上云）。默认丢弃。 */
    private val onDiagnostic: (String) -> Unit = {},
    /** vc190：固件下载按芯片身份选固件，需记住 dongle 的 USB VID/PID。 */
    private val vendorId: Int,
    private val productId: Int,
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
         * vc205：idle 探测超时。广播空闲期读超时是常态，达阈值后先发一条最省的
         * ReadLocalVersion 探测；控制器正常应答在 ms 级，1500ms 仅兜底封顶。
         */
        private const val IDLE_PROBE_TIMEOUT_MILLIS = 1500L

        /** vc207：即时错误退避——bulkTransfer 立即返回失败（<10ms）时 sleep 50ms 再重试。 */
        private const val INSTANT_FAIL_ELAPSED_MILLIS = 10L
        private const val INSTANT_FAIL_BACKOFF_MILLIS = 50L

        /**
         * 打开 dongle 并占用 HCI 接口。
         * - claim 先走 framework（force=false），KK 上还须补 [LegacyUsbHostNative.forceClaim]
         *   踢掉内核 btusb 驱动（KK 的 claimInterface(false) 只抢 usbfs、不摘内核驱动）；
         * - framework / forceClaim 都失败再返回 null（等价打开被拒）。
         * - **vc183（t3 / sunxi KK 实测）**：全志 KK 内核有「描述符半解析」怪癖，HCI 接口端点表
         *   会残缺，事件端点 bulkTransfer 持续 n<0（≥20 次）→ 初始化超时（见 t3 日志
         *   diplay-20261023-225656）。修复照搬有线路径 [NcmUsbBridge] 的 USBDEVFS_RESET：
         *   open 后先 portReset 强制内核完全重枚举，RESET 使原 fd 失效，故 RESET 后必须
         *   close + 重开连接再 claim。KK 常态做；非 KK 仅在 [claimPortResetFallback] 放行
         *   的一次性兜底（初始化 Reset 不应答，2026-11-03 K60 CSR8510 定性）时经
         *   `portResetFirst=true` 进入，正常机型路径不受影响。
         */
        fun open(
            context: Context,
            device: UsbDevice,
            listener: Listener = NO_OP,
            onDiagnostic: (String) -> Unit = {},
            portResetFirst: Boolean = false,
        ): UsbBtDriver? {
            val usb = UsbBtSession.manager(context) as? UsbManager ?: return null
            val iface = UsbBtSession.hciInterface(device) ?: return null
            var connection = usb.openDevice(device) ?: return null
            val kkNative = Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT &&
                LegacyUsbHostNative.available
            // vc186 P0：usbbt 此前全程只有 system logcat（Log.i），而日志上云只带 LiteLog，
            // 导致 vc183~vc185 三份 t3 日志里 usbbt 过程 0 条、无法二分。诊断改走回调进 LiteLog。
            onDiagnostic(
                "usbbt: open ${UsbBtSession.deviceLabel(device)} iface=${iface.id}" +
                    " class=${iface.interfaceClass}/${iface.interfaceSubclass}/${iface.interfaceProtocol}" +
                    " nativeAvailable=${LegacyUsbHostNative.available} api=${Build.VERSION.SDK_INT}",
            )
            // vc183：sunxi KK 描述符半解析 —— 先 RESET 强制重枚举（0/19 都表示重置完成、节点失效）。
            // vc190fix：RESET 后内核完全重枚举，原 fd 失效且设备会短暂从总线消失/重现，内核重建
            // 「非默认端点表」需要时间。若同步立即 openDevice+claim，拿到的端点表残缺 → 控制端点0
            // 可用(故固件下载成功)但 bulk/interrupt 端点 ENOENT=-1（t3 实测：首灌/二次跳过重灌均复现）。
            // 必须先 close 旧连接，循环等待重枚举完成（同 VID/PID 兜底 deviceName 随重枚举变化）再重开。
            // 2026-11-03（K60 CSR8510 定性后）：同一序列抽为 portResetAndReopen()，KK 常态走；
            // 非 KK 仅在调用方显式 portResetFirst=true（初始化 Reset 无应答的一次性兜底）时走。
            if (kkNative || portResetFirst) {
                val reopened = portResetAndReopen(usb, device, connection, onDiagnostic)
                    ?: return null
                connection = reopened
            }
            // vc186 P1：照搬有线 NcmUsbBridge vc103n/vc103o「配置舞」——当年 T3 无 root 跑通的关键
            // 不是硬 claim，而是「内核活动配置 ≠ framework 缓存布局」⇒ 先用 native SET_CONFIGURATION
            // 对齐内核（native 内部自带 GETDRIVER+DISCONNECT 踢 btusb 兜底），再 claim。
            if (kkNative) {
                val topology = parseHciTopology(connection.rawDescriptors)
                val targetConfig = topology?.configValue ?: -1
                val active = kernelActiveConfiguration(connection)
                onDiagnostic(
                    "usbbt: topology=${topology?.describe() ?: "unparsed"}" +
                        " activeConfig=$active targetConfig=$targetConfig",
                )
                if (targetConfig > 0 && active != targetConfig) {
                    val fd = runCatching { connection.fileDescriptor }.getOrDefault(-1)
                    // vc103l：KK proc_setconfig 在本 fd 已有 usbfs claim 时 EBUSY（此处尚未 claim，
                    // 但 RESET 后重开可能残留状态，保险先 release 一次）。
                    val relErrno = if (fd >= 0 && topology != null) {
                        runCatching {
                            LegacyUsbHostNative.releaseInterface(fd, topology.interfaceNumber)
                        }.getOrDefault(-1)
                    } else {
                        -1
                    }
                    val cfgErrno = if (fd >= 0) {
                        runCatching {
                            LegacyUsbHostNative.setConfiguration(fd, targetConfig)
                        }.getOrDefault(-1)
                    } else {
                        -1
                    }
                    // vc103r：errno=0 不代表内核真切过去（KK proc_setconfig 有静默失败先例），回读为准。
                    val after = kernelActiveConfiguration(connection)
                    onDiagnostic(
                        "usbbt: setConfiguration($targetConfig) errno=$cfgErrno readback=$after" +
                            " (releaseErrno=$relErrno)",
                    )
                } else {
                    // vc103o 护栏：已对齐就不要再切（切了会把内核从对齐好的配置切走，反而有害）。
                    onDiagnostic("usbbt: config already aligned; skip setConfiguration (vc103o guard)")
                }
            }
            val claimOk = connection.claimInterface(iface, false)
            onDiagnostic("usbbt: claimInterface(${iface.id}) ok=$claimOk")
            if (!claimOk) {
                var forced = false
                var forceErrno = -1
                if (LegacyUsbHostNative.available) {
                    forceErrno = runCatching {
                        LegacyUsbHostNative.forceClaim(connection.fileDescriptor, iface.id)
                    }.getOrDefault(-1)
                    forced = forceErrno == 0
                }
                // vc186：errno 此前被丢弃 ⇒ 无法分辨「forceClaim 没执行(so 未加载)」还是
                // 「执行了但对 built-in btusb 无效」。
                onDiagnostic("usbbt: forceClaim(${iface.id}) errno=$forceErrno forced=$forced")
                if (!forced) {
                    try {
                        connection.close()
                    } catch (_: Throwable) {
                    }
                    return null
                }
            } else if (kkNative) {
                val forceErrno = runCatching {
                    LegacyUsbHostNative.forceClaim(connection.fileDescriptor, iface.id)
                }.getOrDefault(-1)
                onDiagnostic(
                    "usbbt: post-claim forceClaim(${iface.id}) errno=$forceErrno" +
                        " (KK claim ok != kernel btusb detached)",
                )
            }
            // vc190fix：KK usbfs 端点激活 —— claim 后须显式 SET_INTERFACE(alt=0) 让内核同步 ep 表，
            // 否则 HCI 接口 bulk/interrupt 端点处于 disable 态 ⇒ bulkTransfer ENOENT=-1（t3 实测）。
            // 照搬 NcmUsbBridge 的 KK 兼容法：L+ 先 framework，退化走 native USBDEVFS_SETINTERFACE。
            if (kkNative) {
                val setIfErrno = applySetInterface(connection, iface, onDiagnostic)
                onDiagnostic(
                    "usbbt: setInterface(${iface.id},0) errno=$setIfErrno" +
                        " (KK ep-table sync; see std-request line above)",
                )
            }
            // 调试埋点（2026-11-03 K60 CSR8510 定性，不改行为，全平台只读回读）：
            // open 后读内核活动配置——1=已 configured；≤0/-99=未配置或回读失败。
            val activeCfg = runCatching { kernelActiveConfiguration(connection) }.getOrDefault(-99)
            onDiagnostic("usbbt: post-open activeConfig=$activeCfg（期望 1；-1/-99=回读失败）")
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
            val events = findEvents(iface)
            onDiagnostic(
                "usbbt: endpoints events=0x${hexOf(events.address)}(type=${events.type},mps=${events.maxPacketSize})" +
                    " aclIn=${aclIn?.let { "0x${hexOf(it.address)}(mps=${it.maxPacketSize})" } ?: "none"}" +
                    " aclOut=${aclOut?.let { "0x${hexOf(it.address)}(mps=${it.maxPacketSize})" } ?: "none"}",
            )
            val driver = UsbBtDriver(
                connection, iface, events, aclIn, aclOut, listener, onDiagnostic,
                device.vendorId, device.productId,
            )
            // vc207：注册进程级活实例并顶掉上一个（若有）。close 在锁外执行，避免
            // close 内的 join 阻塞后续 open；被顶掉一方的上游结果由 standby 代际守卫丢弃。
            val previous: UsbBtDriver?
            synchronized(activeDriverLock) {
                previous = activeDriver
                activeDriver = driver
            }
            if (previous != null) {
                onDiagnostic("usbbt: open 抢占：顶掉上一个活实例（vc207 进程级互斥）")
                runCatching { previous.close() }
            }
            return driver
        }

        /** 描述符字节 → 两位十六进制（诊断用）。 */
        private fun hexOf(value: Int): String =
            (value and 0xff).toString(16).padStart(2, '0')

        /** rawDescriptors 解析出的 HCI 拓扑（配置值 / 接口号 / 三端点地址）。 */
        private class HciTopology(
            val configValue: Int,
            val interfaceNumber: Int,
            val interruptIn: Int?,
            val bulkIn: Int?,
            val bulkOut: Int?,
        ) {
            fun describe(): String =
                "cfg=$configValue if=$interfaceNumber intIn=${interruptIn?.let { "0x" + hexOf(it) } ?: "-"}" +
                    " bulkIn=${bulkIn?.let { "0x" + hexOf(it) } ?: "-"}" +
                    " bulkOut=${bulkOut?.let { "0x" + hexOf(it) } ?: "-"}"
        }

        /**
         * vc186 P1：从 [connection] 的 rawDescriptors 按拓扑匹配出 HCI 接口所在配置与端点——
         * 不信任 framework 缓存的 UsbDevice（当年 T3 有线「内核活动配置停在 PTP 形态而 framework
         * 缓存 CarPlay 布局」就是这么翻车的；本函数用来判断该不该 SET_CONFIGURATION）。
         * 描述符：配置(9B,type=2,bConfigurationValue@5)、接口(9B,type=4,bInterfaceNumber@2,
         * bNumEndpoints@4,bInterfaceClass@5/@6/@7)、端点(7B,type=5,bEndpointAddress@2,bmAttributes@3)。
         */
        private fun parseHciTopology(raw: ByteArray?): HciTopology? {
            if (raw == null || raw.size < 9) return null
            var i = 0
            var configValue = -1
            var ifaceNumber = -1
            var ifaceIsHci = false
            var endpointsLeft = 0
            var intIn: Int? = null
            var bulkIn: Int? = null
            var bulkOut: Int? = null
            while (i + 1 < raw.size) {
                val len = raw[i].toInt() and 0xff
                val type = raw[i + 1].toInt() and 0xff
                if (len < 2 || i + len > raw.size) break
                when (type) {
                    2 -> { // configuration descriptor
                        if (len >= 6) configValue = raw[i + 5].toInt() and 0xff
                        ifaceNumber = -1
                        ifaceIsHci = false
                        endpointsLeft = 0
                    }
                    4 -> { // interface descriptor
                        if (len >= 8) {
                            ifaceNumber = raw[i + 2].toInt() and 0xff
                            val cls = raw[i + 5].toInt() and 0xff
                            val sub = raw[i + 6].toInt() and 0xff
                            val proto = raw[i + 7].toInt() and 0xff
                            ifaceIsHci = cls == 0xe0 && sub == 1 && proto == 1
                            endpointsLeft = raw[i + 4].toInt() and 0xff
                        }
                    }
                    5 -> { // endpoint descriptor
                        if (len >= 4 && endpointsLeft > 0 && ifaceIsHci) {
                            val addr = raw[i + 2].toInt() and 0xff
                            val attr = raw[i + 3].toInt() and 0xff
                            val xfer = attr and 0x03
                            val dirIn = (addr and 0x80) != 0
                            when {
                                xfer == 3 && dirIn -> if (intIn == null) intIn = addr
                                xfer == 2 && dirIn -> if (bulkIn == null) bulkIn = addr
                                xfer == 2 && !dirIn -> if (bulkOut == null) bulkOut = addr
                            }
                            endpointsLeft--
                        }
                    }
                }
                i += len
                // 首个 HCI 接口的端点解析完即定案（单配置 dongle 常态）。
                if (ifaceIsHci && endpointsLeft == 0 && intIn != null && configValue > 0) {
                    return HciTopology(configValue, ifaceNumber, intIn, bulkIn, bulkOut)
                }
            }
            return if (configValue > 0 && ifaceNumber >= 0 && intIn != null) {
                HciTopology(configValue, ifaceNumber, intIn, bulkIn, bulkOut)
            } else {
                null
            }
        }

        /** GET_CONFIGURATION 回读内核当前活动配置（0=未配置）；失败返回 -1。 */
        private fun kernelActiveConfiguration(connection: UsbDeviceConnection): Int {
            val buf = ByteArray(1)
            val n = runCatching {
                connection.controlTransfer(0x80, 8, 0, 0, buf, 1, 1000)
            }.getOrDefault(-1)
            return if (n > 0) buf[0].toInt() and 0xff else -1
        }

        /** vc190fix：portReset 后按原 device 或同 VID/PID 在列设备重开连接（兜底 deviceName 随重枚举变化）。 */
        private fun tryOpenDongle(usb: UsbManager, device: UsbDevice): UsbDeviceConnection? {
            runCatching { usb.openDevice(device) }.getOrNull()?.let { return it }
            for (cand in usb.deviceList.values) {
                if (cand.vendorId == device.vendorId && cand.productId == device.productId) {
                    runCatching { usb.openDevice(cand) }.getOrNull()?.let { return it }
                }
            }
            return null
        }

        /**
         * vc183/vc190fix 的 portReset 序列抽块：USBDEVFS_RESET → close → 循环等重枚举重开。
         * 返回重开后的新连接；RESET 失败或 4s 内未重枚举返回 null（调用方按 open 失败处理）。
         */
        private fun portResetAndReopen(
            usb: UsbManager,
            device: UsbDevice,
            connection: UsbDeviceConnection,
            onDiagnostic: (String) -> Unit,
        ): UsbDeviceConnection? {
            val resetErrno = try {
                LegacyUsbHostNative.portReset(connection.fileDescriptor)
            } catch (_: Throwable) {
                -1
            }
            onDiagnostic("usbbt: portReset errno=$resetErrno (0/19=reset done, fd invalidated)")
            if (resetErrno != 0 && resetErrno != 19) return null
            try {
                connection.close()
            } catch (_: Throwable) {
            }
            var reopened: UsbDeviceConnection? = null
            var attempt = 0
            while (attempt < 40 && reopened == null) {
                reopened = tryOpenDongle(usb, device)
                if (reopened == null) {
                    try {
                        Thread.sleep(100)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    attempt++
                }
            }
            if (reopened == null) {
                onDiagnostic("usbbt: reopen after portReset failed after $attempt attempts")
                return null
            }
            onDiagnostic("usbbt: reopened after portReset (attempts=$attempt)")
            return reopened
        }

        /**
         * vc207：进程级单实例互斥（K60 实测根因：两个 standby 循环并发 open 同一 dongle，
         * 互相 forceClaim 夺接口 → 端点读 n=-1、配对事件进不来、日志炸弹）。语义=**抢占**：
         * 新 open 生效、旧实例被 close（配合 UsbBtStandby 代际守卫，被顶掉一方的 openDirect
         * 结果会被丢弃并 close；forceClaim 从此只对外部占用者/内核 btusb，抢不到自己人）。
         */
        private val activeDriverLock = Any()
        private var activeDriver: UsbBtDriver? = null

        /** 2026-11-03：portReset 兜底每进程只放行一次（K60 CSR8510「写通读空」修复），防重入/循环。 */
        private val portResetFallbackUsed = java.util.concurrent.atomic.AtomicBoolean(false)

        /** 初始化首轮超时（首命令无应答）时由 bootstrap 调用：true=本次放行兜底（之后不再放）。 */
        fun claimPortResetFallback(): Boolean =
            portResetFallbackUsed.compareAndSet(false, true)

        /**
         * vc190fix：KK usbfs 端点激活 —— claim 后须显式 SET_INTERFACE(alt=0) 让内核同步 ep 表，
         * 否则 HCI 接口 bulk/interrupt 端点处于 disable 态 ⇒ bulkTransfer ENOENT=-1（t3 实测）。
         * 照搬 NcmUsbBridge 的 KK 兼容法：L+ 先 framework，退化走 native USBDEVFS_SETINTERFACE。
         *
         * vc191fix：**B 兜底优先**——先发 SET_INTERFACE 标准设备请求（bmRequestType=0x01,
         * bRequest=0x0B, wValue=alt=0, wIndex=接口号, wLength=0）强制内核重枚举接口端点。该请求走
         * 纯 framework [UsbDeviceConnection.controlTransfer]，不依赖 [LegacyUsbHostNative] 的
         * USBDEVFS_SETINTERFACE ioctl。t3(KK/API19) 实测该 native 调用 `unavailable=-1`、退化
         * framework [connection.setInterface] 又未真正同步 ep 表 ⇒ bulk 恒 n=-1。B 方案直接发标准
         * 请求，与 legacy v0.2.98 在 KK 上可连的路径对齐（legacy 即靠标准请求激活端点）。
         *
         * @return 0=成功（任一手段让内核 ep 表激活），-1=全部退化路径失败。
         */
        private fun applySetInterface(
            connection: UsbDeviceConnection,
            iface: UsbInterface,
            onDiagnostic: (String) -> Unit,
        ): Int {
            // vc191 B 兜底：SET_INTERFACE 标准请求（纯 framework，绕过 native unavailable=-1 死穴）。
            val stdRet = runCatching {
                connection.controlTransfer(0x01, 0x0B, 0, iface.id, null, 0, 5000)
            }.getOrDefault(-1)
            onDiagnostic(
                "usbbt: setInterface(alt=0) std-request ret=$stdRet" +
                    " (vc191 B fallback; bmReq=0x01 bReq=0x0B wIndex=${iface.id})",
            )
            if (stdRet >= 0) return 0
            // 退化路径（B 失败时）：L+ framework，再 native；KK 上若 B 失败且 native 也不可用则 -1。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
                runCatching { connection.setInterface(iface) }.getOrDefault(false)
            ) {
                return 0
            }
            return if (LegacyUsbHostNative.available) {
                runCatching {
                    LegacyUsbHostNative.setInterface(connection.fileDescriptor, iface.id, 0)
                }.getOrDefault(-1)
            } else {
                -1
            }
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

    /** vc205：idle 探测在飞守卫（event/ACL 双读线程共享，CAS 防并发探测）。 */
    private val idleProbeInFlight = AtomicBoolean(false)

    /** vc205：探测成功代际（仅探测线程自增）——读线程据此清零本地失败计数。 */
    @Volatile
    private var idleProbeGeneration = 0L

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
     * vc207：只发不等。异步命令（INQUIRY 等无 Command Complete、只回 CommandStatus + 后续事件）
     * 若走 hciCommand 会白等满 timeout 才返回；此入口仅做控制写，完成与否由事件流上报给 host。
     */
    fun hciSend(opcode: Int, params: ByteArray = ByteArray(0)): Boolean {
        if (!running) return false
        val packet = HciCommands.command(opcode, params)
        val t0 = System.currentTimeMillis()
        val sent = synchronized(writeLock) {
            connection.controlTransfer(0x20, 0, 0, 0, packet, packet.size, 1000)
        }
        val writeElapsedMs = System.currentTimeMillis() - t0
        onDiagnostic("usbbt: hci cmd 0x%04x sent=$sent writeElapsedMs=$writeElapsedMs（只发不等）".format(opcode))
        return sent >= 0
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
            // 调试埋点（同批，不改行为）：控制写返回值+耗时+opcode——定性「写方向是否生效」。
            val t0 = System.currentTimeMillis()
            val sent = synchronized(writeLock) {
                connection.controlTransfer(
                    0x20, // CLASS | HOST_TO_DEVICE（上游 stop() 同款）
                    0, 0, 0,
                    packet, packet.size,
                    1000,
                )
            }
            val writeElapsedMs = System.currentTimeMillis() - t0
            onDiagnostic("usbbt: hci cmd 0x%04x sent=$sent writeElapsedMs=$writeElapsedMs".format(opcode))
            if (sent < 0) return null // 控制传输错误
            if (!waiter.latch.await(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                onDiagnostic("usbbt: hci cmd 0x%04x await timeout %dms（无 Command Complete）".format(opcode, timeoutMillis))
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

    /**
     * vc190（CM390 / Realtek ROM 模式 dongle）：打开后、host 初始化前按芯片身份下载固件。
     * 仅对 Realtek/Broadcom 等 ROM 模式芯片生效；CSR8510 等固件片内芯片走
     * [UsbBtFirmwareLoader.LoadResult.NoFirmwareNeeded] 空过，现有路径不受影响。
     * 失败/缺失不抛异常——交由后续 host 初始化给出明确诊断（如 BD_ADDR 全零）。
     */
    fun loadFirmware(assetManager: AssetManager): UsbBtFirmwareLoader.LoadResult {
        val loader = UsbBtFirmwareLoader(
            command = { opcode, params, timeout -> hciToFirmwareResult(opcode, params, timeout.toLong()) },
            assets = assetManager,
        )
        return runCatching { loader.loadForDevice(vendorId, productId) }
            .getOrDefault(UsbBtFirmwareLoader.LoadResult.Failed("firmware loader crashed"))
            .also { onDiagnostic("usbbt: firmware load $it (vid=0x${vendorId.toString(16)} pid=0x${productId.toString(16)})") }
    }

    /** 把本仓 [hciCommand]（剥掉 status 字节）桥接成固件加载器期望的「含 status 完整 reply」。 */
    private fun hciToFirmwareResult(
        opcode: Int,
        params: ByteArray,
        timeoutMs: Long,
    ): UsbBtFirmwareLoader.HciResult<ByteArray> = try {
        val ret = hciCommand(opcode, params, timeoutMs)
        if (ret == null) {
            UsbBtFirmwareLoader.HciResult.Failed("no reply / timeout")
        } else {
            // hciCommand 已剥掉 status 字节；固件代码要求 reply[0]==0，这里补回 0 状态字节。
            UsbBtFirmwareLoader.HciResult.Ok(byteArrayOf(0) + ret)
        }
    } catch (e: HciStatusException) {
        UsbBtFirmwareLoader.HciResult.Failed("status 0x%02x".format(e.status))
    } catch (e: Throwable) {
        UsbBtFirmwareLoader.HciResult.Failed(e.message ?: e.javaClass.simpleName)
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
     * 跨 bulk 包拼接；连续失败超阈值先走 idle 探测（[startIdleProbe]），
     * 探测也无应答才触发 [Listener.onTransportFailure] 并退出。
     */
    private fun readLoop(endpoint: UsbEndpoint, headerBytes: Int, isEvent: Boolean) {
        var maxPacketSize = endpoint.maxPacketSize
        if (maxPacketSize < 16) maxPacketSize = 16
        val buffer = ByteArray(maxPacketSize)
        var acc = ByteArray(0)
        var failures = 0
        var lastTickMillis = 0L
        var probeGenerationSeen = 0L
        while (running) {
            // 调试埋点（2026-11-03 K60 CSR8510 定性，不改行为）：记录单次 bulkTransfer 耗时——
            // ≈timeout 返回 = 读超时（端点通、dongle 不应答）；立即返回 = 端点错误（ENOENT 等）。
            val t0 = System.currentTimeMillis()
            val n = connection.bulkTransfer(endpoint, buffer, maxPacketSize, 500)
            val readElapsedMs = System.currentTimeMillis() - t0
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
                // vc186：errno/失败计数采样（前 3 次 + 每 5 次一次），既留证据又不刷屏——
                // 用于区分「端点表残缺(ENOENT)」/「被内核驱动占用(EBUSY/EPIPE)」/「超时」。
                if (failures <= 3 || failures % 5 == 0) {
                    val line = "usbbt: bulk read fail ep=0x${hexOf(endpoint.address)} n=$n" +
                        " elapsedMs=$readElapsedMs failures=$failures (${if (isEvent) "event" else "ACL"})"
                    Log.w(TAG, line)
                    onDiagnostic(line)
                }
                // 同埋点批：失败累积到 10 次时打一行端点状态快照（能读到什么记什么，读不到写 unavailable）。
                if (failures == 10) {
                    val cfg = runCatching { kernelActiveConfiguration(connection) }
                        .map { it.toString() }.getOrElse { "unavailable" }
                    val fd = runCatching { connection.fileDescriptor.toString() }.getOrElse { "unavailable" }
                    val state = "usbbt: ep state @failures=10 ep=0x${hexOf(endpoint.address)}" +
                        " type=${endpoint.type} mps=${endpoint.maxPacketSize}" +
                        " activeConfig=$cfg fd=$fd"
                    Log.w(TAG, state)
                    onDiagnostic(state)
                }
                if (failures > MAX_CONSECUTIVE_TRANSFER_FAILURES) {
                    // vc205（K60 CSR8510 定性）：广播空闲期 read 超时是常态，达阈值 ≠ 掉线。
                    // 先探测再判死；探测在飞/有命令在飞时不判死，下轮再评估。
                    if (probeGenerationSeen != idleProbeGeneration) {
                        // 探测已确认 dongle 存活：清零本轮计数，继续广播
                        probeGenerationSeen = idleProbeGeneration
                        failures = 0
                    } else if (pendingCommands.isEmpty() && idleProbeInFlight.compareAndSet(false, true)) {
                        startIdleProbe()
                    }
                }
                // vc207：端点被抢占/释放时 bulkTransfer 立即返回 n<0（elapsedMs≈0），
                // 不退避会空转烧 CPU 并把 logcat 缓冲冲爆（K60 实测 49s 灌 23 万行，
                // failures 冲到 2 万+），配对证据全被冲掉。即时错误 → 退避 50ms。
                if (readElapsedMs < INSTANT_FAIL_ELAPSED_MILLIS && running) {
                    try {
                        Thread.sleep(INSTANT_FAIL_BACKOFF_MILLIS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
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

    /**
     * vc205（K60 CSR8510 定性）：idle 探测。连续读超时达阈值时，在独立线程发一条最省的
     * ReadLocalVersion —— 收到 Command Complete（哪怕 status 拒绝）即证明控制通道存活，
     * 递增 [idleProbeGeneration] 让读线程清零失败计数、继续广播；探测本身也无应答才判
     * 「dongle 掉线」走 [Listener.onTransportFailure]。
     * 节流与隔离：探测成功后 failures 清零 ⇒ 下次探测至少再隔一轮阈值（≥20×500ms≈10s）；
     * 探测走控制端点 ep0（非 bulk/interrupt），且仅查询本地版本，不打断进行中的广播/链路状态。
     */
    private fun startIdleProbe() {
        Thread({
            var alive = false
            try {
                alive = hciCommand(
                    HciCommands.READ_LOCAL_VERSION_INFORMATION,
                    ByteArray(0),
                    IDLE_PROBE_TIMEOUT_MILLIS,
                ) != null
            } catch (_: HciStatusException) {
                alive = true // 控制器拒绝也是应答：控制通道存活
            } catch (_: Throwable) {
            }
            if (alive) {
                idleProbeGeneration++
                Log.i(TAG, "host: idle probe ok (cleared)")
                onDiagnostic("host: idle probe ok (cleared)")
            } else if (running) {
                Log.w(TAG, "host: idle probe failed -> offline")
                onDiagnostic("host: idle probe failed -> offline")
                listener.onTransportFailure("idle 探测无应答（dongle 掉线）")
                running = false
            }
            idleProbeInFlight.set(false)
        }, "usb-bt-idle-probe").apply { isDaemon = true }.start()
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
        // vc207：注销进程级活实例（仅在仍登记时；被抢占时登记已属新实例）。
        synchronized(activeDriverLock) {
            if (activeDriver === this) activeDriver = null
        }
        Log.i(TAG, "driver stopped; interface #${iface.id} released")
    }
}

private const val EVENT_HEADER_BYTES = 2
private const val ACL_HEADER_BYTES = 4
