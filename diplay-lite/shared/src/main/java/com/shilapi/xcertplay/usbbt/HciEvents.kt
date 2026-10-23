package com.shilapi.xcertplay.usbbt

/**
 * vc181（外置 USB 蓝牙 C/D 批）：HCI 事件解析与分发模型。
 * 移植自上游参考实现 usbbt.HciEvent$*（14+ 个事件类）；上游把解析内联在
 * UsbBtDriver.readLoop 的巨型 switch 里，此处还原为独立解析函数 + sealed class。
 *
 * 事件端点原始包：事件码(1) + 参数总长(1) + 参数（与 B 批 HciReturnParameters 同一布局，
 * 解析口径逐字段对照上游 readLoop，长度不足一律返回 null 丢弃）。
 */
internal sealed class HciEvent {
    /** 事件码（0x0E Command Complete / 0x0F Command Status 等）。 */
    abstract val code: Int

    /** 0x03：ACL 链路建立完成。params: status(1) handle(2 LE) bdaddr(6) linkType(1) encryption(1)。 */
    data class ConnectionComplete(
        val status: Int,
        val handle: Int,
        val addr: BdAddr,
        val linkType: Int,
        val encryptionEnabled: Boolean,
    ) : HciEvent() {
        override val code get() = 0x03
    }

    /** 0x04：对端发起连接请求。params: bdaddr(6) cod(4 LE) linkType(1)。 */
    data class ConnectionRequest(
        val addr: BdAddr,
        val classOfDevice: Int,
        val linkType: Int,
    ) : HciEvent() {
        override val code get() = 0x04
    }

    /** 0x05：断链完成。params: status(1) handle(2 LE) reason(1)。 */
    data class DisconnectionComplete(
        val status: Int,
        val handle: Int,
        val reason: Int,
    ) : HciEvent() {
        override val code get() = 0x05
    }

    /** 0x06：认证完成。params: status(1) handle(2 LE)。 */
    data class AuthenticationComplete(
        val status: Int,
        val handle: Int,
    ) : HciEvent() {
        override val code get() = 0x06
    }

    /** 0x07：远端名字读取完成。params: status(1) bdaddr(6) name(z 终止 UTF-8)。 */
    data class RemoteNameRequestComplete(
        val status: Int,
        val addr: BdAddr,
        val name: String,
    ) : HciEvent() {
        override val code get() = 0x07
    }

    /** 0x08：加密状态变更。params: status(1) handle(2 LE) enabled(1)。 */
    data class EncryptionChange(
        val status: Int,
        val handle: Int,
        val enabled: Boolean,
    ) : HciEvent() {
        override val code get() = 0x08
    }

    /** 0x0E：命令完成。returnParams 与上游一致从 status 字节起（ret[0]=status）。 */
    data class CommandComplete(
        val opcode: Int,
        val numPackets: Int,
        val returnParams: ByteArray,
    ) : HciEvent() {
        override val code get() = 0x0e
    }

    /** 0x0F：命令状态（异步执行的命令立即回报）。params: status(1) numPackets(1) opcode(2 LE)。 */
    data class CommandStatus(
        val status: Int,
        val numPackets: Int,
        val opcode: Int,
    ) : HciEvent() {
        override val code get() = 0x0f
    }

    /** 0x10：硬件故障。params: code(1)。 */
    data class HardwareError(val errorCode: Int) : HciEvent() {
        override val code get() = 0x10
    }

    /** 0x16：Legacy PIN 请求（本实现固定走 negative 回绝）。 */
    data class PinCodeRequest(val addr: BdAddr) : HciEvent() {
        override val code get() = 0x16
    }

    /** 0x17：link key 请求（有 key 回 key，无 key negative 触发配对）。 */
    data class LinkKeyRequest(val addr: BdAddr) : HciEvent() {
        override val code get() = 0x17
    }

    /** 0x18：配对产出 link key（存内存 map）。 */
    data class LinkKeyNotification(
        val addr: BdAddr,
        val key: ByteArray,
        val keyType: Int,
    ) : HciEvent() {
        override val code get() = 0x18
    }

    /** 0x31：SSP IO 能力请求（回 NoInputNoOutput）。 */
    data class IoCapabilityRequest(val addr: BdAddr) : HciEvent() {
        override val code get() = 0x31
    }

    /** 0x32：对端 IO 能力。params: bdaddr(6) io(1) oob(1) auth(1)。 */
    data class IoCapabilityResponse(
        val addr: BdAddr,
        val ioCapability: Int,
        val oobDataPresent: Boolean,
        val authRequirements: Int,
    ) : HciEvent() {
        override val code get() = 0x32
    }

    /** 0x33：SSP 数字确认请求（Just-Works 直接 positive）。 */
    data class UserConfirmationRequest(
        val addr: BdAddr,
        val passkey: Int,
    ) : HciEvent() {
        override val code get() = 0x33
    }

    /** 0x34：passkey 输入请求（无输入能力，固定 negative）。 */
    data class UserPasskeyRequest(val addr: BdAddr) : HciEvent() {
        override val code get() = 0x34
    }

    /** 0x39：passkey 展示通知。 */
    data class UserPasskeyNotification(
        val addr: BdAddr,
        val passkey: Int,
    ) : HciEvent() {
        override val code get() = 0x39
    }

    /** 0x36：简单配对完成。params: status(1) bdaddr(6)。 */
    data class SimplePairingComplete(
        val status: Int,
        val addr: BdAddr,
    ) : HciEvent() {
        override val code get() = 0x36
    }

    /** 0x13：控制器缓冲区回收计数（流量控制参照，仅日志）。 */
    data class NumberOfCompletedPackets(val params: ByteArray) : HciEvent() {
        override val code get() = 0x13
    }

    /** 其余事件：保留原始参数（不解析，直接透传给 host 记日志）。 */
    data class Other(
        override val code: Int,
        val params: ByteArray,
    ) : HciEvent()
}

internal object HciEvents {
    /** 解析一个完整事件包（含事件码与长度字节）；不认识/长度不足返回 null。 */
    fun parse(packet: ByteArray): HciEvent? {
        if (packet.size < 2) return null
        val code = packet[0].toInt() and 0xff
        val paramLen = packet[1].toInt() and 0xff
        if (packet.size < 2 + paramLen) return null
        val p = packet.copyOfRange(2, 2 + paramLen)
        return when (code) {
            0x03 ->
                if (p.size >= 11) {
                    HciEvent.ConnectionComplete(
                        p[0].toInt() and 0xff,
                        le16(p, 1) and 0xfff,
                        BdAddr.of(p, 3),
                        p[9].toInt() and 0xff,
                        (p[10].toInt() and 0xff) != 0,
                    )
                } else {
                    null
                }
            0x04 ->
                if (p.size >= 10) {
                    HciEvent.ConnectionRequest(
                        BdAddr.of(p, 0),
                        le16(p, 6) or ((p[8].toInt() and 0xff) shl 16),
                        p[9].toInt() and 0xff,
                    )
                } else {
                    null
                }
            0x05 ->
                if (p.size >= 4) {
                    HciEvent.DisconnectionComplete(
                        p[0].toInt() and 0xff,
                        le16(p, 1) and 0xfff,
                        p[3].toInt() and 0xff,
                    )
                } else {
                    null
                }
            0x06 ->
                if (p.size >= 3) {
                    HciEvent.AuthenticationComplete(p[0].toInt() and 0xff, le16(p, 1) and 0xfff)
                } else {
                    null
                }
            0x07 ->
                if (p.size >= 7) {
                    var end = 7
                    while (end < p.size && p[end].toInt() != 0) end++
                    HciEvent.RemoteNameRequestComplete(
                        p[0].toInt() and 0xff,
                        BdAddr.of(p, 1),
                        String(p, 7, end - 7, Charsets.UTF_8),
                    )
                } else {
                    null
                }
            0x08 ->
                if (p.size >= 4) {
                    HciEvent.EncryptionChange(
                        p[0].toInt() and 0xff,
                        le16(p, 1) and 0xfff,
                        (p[3].toInt() and 0xff) != 0,
                    )
                } else {
                    null
                }
            0x0e ->
                if (p.size >= 3) {
                    HciEvent.CommandComplete(
                        le16(p, 1),
                        p[0].toInt() and 0xff,
                        p.copyOfRange(3, p.size),
                    )
                } else {
                    null
                }
            0x0f ->
                if (p.size >= 4) {
                    HciEvent.CommandStatus(
                        p[0].toInt() and 0xff,
                        p[1].toInt() and 0xff,
                        le16(p, 2),
                    )
                } else {
                    null
                }
            0x10 -> if (p.isNotEmpty()) HciEvent.HardwareError(p[0].toInt() and 0xff) else null
            0x13 -> HciEvent.NumberOfCompletedPackets(p)
            0x16 -> if (p.size >= 6) HciEvent.PinCodeRequest(BdAddr.of(p, 0)) else null
            0x17 -> if (p.size >= 6) HciEvent.LinkKeyRequest(BdAddr.of(p, 0)) else null
            0x18 ->
                if (p.size >= 23) {
                    HciEvent.LinkKeyNotification(
                        BdAddr.of(p, 0),
                        p.copyOfRange(6, 22),
                        p[22].toInt() and 0xff,
                    )
                } else {
                    null
                }
            0x31 -> if (p.size >= 6) HciEvent.IoCapabilityRequest(BdAddr.of(p, 0)) else null
            0x32 ->
                if (p.size >= 9) {
                    HciEvent.IoCapabilityResponse(
                        BdAddr.of(p, 0),
                        p[6].toInt() and 0xff,
                        (p[7].toInt() and 0xff) != 0,
                        p[8].toInt() and 0xff,
                    )
                } else {
                    null
                }
            0x33 ->
                if (p.size >= 10) {
                    HciEvent.UserConfirmationRequest(
                        BdAddr.of(p, 0),
                        le16(p, 6) or (le16(p, 8) shl 16),
                    )
                } else {
                    null
                }
            0x34 -> if (p.size >= 6) HciEvent.UserPasskeyRequest(BdAddr.of(p, 0)) else null
            0x36 ->
                if (p.size >= 7) {
                    HciEvent.SimplePairingComplete(p[0].toInt() and 0xff, BdAddr.of(p, 1))
                } else {
                    null
                }
            0x39 ->
                if (p.size >= 10) {
                    HciEvent.UserPasskeyNotification(
                        BdAddr.of(p, 0),
                        le16(p, 6) or (le16(p, 8) shl 16),
                    )
                } else {
                    null
                }
            else -> HciEvent.Other(code, p)
        }
    }

    private fun le16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)
}
