package com.shilapi.xcertplay.usbbt

/**
 * vc167（外置 USB 蓝牙 dongle 第 2 步 B 批）：HCI 命令构造。
 * 移植自竞品 usbbt.HciCommands.java（仅保留 B 批所需：Reset / Read BD_ADDR；
 * 建链/扫描/配对类命令随 C/D 批补全）。
 *
 * HCI 命令包格式：opcode(2 LE) + 参数总长(1) + 参数。
 * 经 USB 控制端点 0 发送（bmRequestType=0x20 class/host-to-device）。
 */
internal object HciCommands {
    // RESET = opcode(3,3) = 0x0C03；READ_BD_ADDR = opcode(4,9) = 0x1009
    //（const val 不能调函数，直接落字面量）
    const val RESET: Int = 0x0c03
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
}
