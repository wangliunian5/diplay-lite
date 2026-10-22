package com.shilapi.xcertplay.usbbt

/**
 * vc167（外置 USB 蓝牙 dongle 第 2 步 B 批）：HCI 事件/返回参数解析。
 * 移植自竞品 usbbt.HciReturnParameters.java（仅保留 B 批所需：事件包解帧 +
 * Command Complete + Read BD_ADDR 返回参数；其余事件随 C/D 批补全）。
 *
 * 事件端点原始包格式：事件码(1) + 参数总长(1) + 参数。
 * Command Complete（0x0E）参数：numPackets(1) + opcode(2 LE) + status(1) + 返回参数。
 */
internal object HciReturnParameters {
    const val EVENT_COMMAND_COMPLETE: Int = 0x0e

    data class CommandComplete(val opcode: Int, val status: Int, val returnParams: ByteArray)

    /**
     * 从事件端点读到的原始包解析 Command Complete；非该事件或长度不足返回 null。
     * [packet] 为完整事件包（含事件码与长度字节），对应竞品 readLoop 的解帧结果。
     */
    fun parseCommandComplete(packet: ByteArray): CommandComplete? {
        if (packet.size < 3 || (packet[0].toInt() and 0xff) != EVENT_COMMAND_COMPLETE) return null
        val paramLen = packet[1].toInt() and 0xff
        if (packet.size < 2 + paramLen || paramLen < 4) return null
        // params = numPackets(1) + opcode(2 LE) + status(1) + return...
        val opcode = (packet[3].toInt() and 0xff) or ((packet[4].toInt() and 0xff) shl 8)
        val status = packet[5].toInt() and 0xff
        val returnParams = packet.copyOfRange(6, 2 + paramLen)
        return CommandComplete(opcode, status, returnParams)
    }

    /**
     * Read BD_ADDR 的返回参数 → "xx:xx:xx:xx:xx:xx"。
     * BD_ADDR 线上 LSB 在前（params[0]=地址低字节），显示按 MSB 在前反转。
     * params[0] 是 status，必须为 0。
     */
    fun bdAddrOfReadBdAddr(returnParams: ByteArray): String? {
        if (returnParams.size < 7) return null
        if (returnParams[0].toInt() != 0) return null
        val bytes = returnParams
        return (5 downTo 0).joinToString(":") { i -> "%02x".format(bytes[1 + i].toInt() and 0xff) }
    }
}
