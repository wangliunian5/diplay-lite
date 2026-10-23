package com.shilapi.xcertplay.usbbt

/**
 * vc167（外置 USB 蓝牙 dongle 第 2 步 B 批）/ vc181（外置 USB 蓝牙 C/D 批）：HCI 命令构造。
 * 移植自上游参考实现 usbbt.HciCommands.java（B 批仅 Reset / Read BD_ADDR；
 * C 批补全建链/断链/扫描/配对类命令常量与参数工具）。
 *
 * HCI 命令包格式：opcode(2 LE) + 参数总长(1) + 参数。
 * 经 USB 控制端点 0 发送（bmRequestType=0x20 class/host-to-device）。
 */
internal object HciCommands {
    // opcode(ogf, ocf) 现算；const val 不能调函数，落字面量
    const val CREATE_CONNECTION: Int = 0x0405 // opcode(1, 5)
    const val DISCONNECT: Int = 0x0406 // opcode(1, 6)
    const val ACCEPT_CONNECTION_REQUEST: Int = 0x0409 // opcode(1, 9)
    const val REJECT_CONNECTION_REQUEST: Int = 0x040a // opcode(1, 10)
    const val LINK_KEY_REQUEST_REPLY: Int = 0x040b // opcode(1, 11)
    const val LINK_KEY_REQUEST_NEGATIVE_REPLY: Int = 0x040c // opcode(1, 12)
    const val PIN_CODE_REQUEST_NEGATIVE_REPLY: Int = 0x040e // opcode(1, 14)
    const val AUTHENTICATION_REQUESTED: Int = 0x0411 // opcode(1, 17)
    const val SET_CONNECTION_ENCRYPTION: Int = 0x0413 // opcode(1, 19)
    const val REMOTE_NAME_REQUEST: Int = 0x0419 // opcode(1, 25)
    const val IO_CAPABILITY_REQUEST_REPLY: Int = 0x042b // opcode(1, 43)
    const val USER_CONFIRMATION_REQUEST_REPLY: Int = 0x042c // opcode(1, 44)
    const val USER_CONFIRMATION_REQUEST_NEGATIVE_REPLY: Int = 0x042d // opcode(1, 45)
    const val USER_PASSKEY_REQUEST_NEGATIVE_REPLY: Int = 0x042f // opcode(1, 47)
    const val WRITE_DEFAULT_LINK_POLICY_SETTINGS: Int = 0x080f // opcode(2, 15)
    const val SET_EVENT_MASK: Int = 0x0c01 // opcode(3, 1)
    const val RESET: Int = 0x0c03
    const val WRITE_LOCAL_NAME: Int = 0x0c13 // opcode(3, 19)
    const val WRITE_SCAN_ENABLE: Int = 0x0c1a // opcode(3, 26)
    const val WRITE_CLASS_OF_DEVICE: Int = 0x0c24 // opcode(3, 36)
    const val WRITE_INQUIRY_SCAN_TYPE: Int = 0x0c43 // opcode(3, 67)
    const val WRITE_INQUIRY_MODE: Int = 0x0c45 // opcode(3, 69)
    const val WRITE_PAGE_SCAN_TYPE: Int = 0x0c47 // opcode(3, 71)
    const val WRITE_EXTENDED_INQUIRY_RESPONSE: Int = 0x0c52 // opcode(3, 82)
    const val WRITE_SIMPLE_PAIRING_MODE: Int = 0x0c56 // opcode(3, 86)
    const val READ_INQUIRY_RESPONSE_TX_POWER: Int = 0x0c58 // opcode(3, 88)
    const val READ_LOCAL_VERSION_INFORMATION: Int = 0x1001 // opcode(4, 1)
    const val READ_LOCAL_SUPPORTED_FEATURES: Int = 0x1003 // opcode(4, 3)
    const val READ_BUFFER_SIZE: Int = 0x1005 // opcode(4, 5)
    const val READ_BD_ADDR: Int = 0x1009

    fun opcode(ogf: Int, ocf: Int): Int = (ogf shl 10) or ocf

    /** 构造一条完整 HCI 命令包（含 3 字节头）。 */
    fun command(opcode: Int, params: ByteArray = ByteArray(0)): ByteArray {
        require(opcode in 0 until 0x10000) { "opcode out of range: $opcode" }
        require(params.size <= 255) { "HCI command parameters are at most 255 bytes" }
        val packet = ByteArray(3 + params.size)
        packet[0] = (opcode and 0xff).toByte()
        packet[1] = (opcode ushr 8).toByte()
        packet[2] = params.size.toByte()
        params.copyInto(packet, 3)
        return packet
    }

    /** 16 位小端两字节（命令参数通用）。 */
    fun le16(value: Int): ByteArray =
        byteArrayOf((value and 0xff).toByte(), ((value ushr 8) and 0xff).toByte())

    /**
     * UTF-8 文本截断到至多 [limit] 字节（按码点整截，不切半个字符）。
     * 上游 utf8Prefix 移植，供 Write_Local_Name 等使用。
     */
    fun utf8Prefix(limit: Int, text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            val bytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
            if (out.size() + bytes.size > limit) break
            out.write(bytes)
            i += Character.charCount(codePoint)
        }
        return out.toByteArray()
    }
}
