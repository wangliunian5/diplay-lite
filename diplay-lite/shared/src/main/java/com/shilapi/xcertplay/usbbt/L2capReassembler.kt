package com.shilapi.xcertplay.usbbt

/**
 * vc181（外置 USB 蓝牙 C/D 批）：ACL 分片重组器。
 * 移植自上游参考实现 usbbt.L2capReassembler（上游文件尾部混入的
 * RendererRestartGate.allow 与本类无关，系反编译噪声，不移植）。
 *
 * PB 标志：1 = 续传分片（拼到 partial 尾部）；其余（起点/完整）作为新 L2CAP 帧头。
 * L2CAP 帧长（LE16@0）+ 4 字节头不足则继续等；总长超 65539 丢弃。
 */
internal class L2capReassembler {
    private val partial = HashMap<Int, ByteArray>()

    /** 喂入一个 ACL 包；重组出完整 L2CAP 帧时返回（含帧头），否则 null。 */
    fun accept(packet: UsbBtDriver.AclPacket): ByteArray? {
        var data = packet.data
        val handle = packet.handle
        if (packet.pbFlag == 1) {
            val head = partial[handle] ?: return null
            data = head + data
        }
        if (data.size < 4) {
            partial[handle] = data
            return null
        }
        val total = ((data[0].toInt() and 0xff) or ((data[1].toInt() and 0xff) shl 8)) + 4
        if (total > 65539) {
            partial.remove(handle)
            return null
        }
        if (data.size < total) {
            partial[handle] = data
            return null
        }
        partial.remove(handle)
        return data.copyOf(total)
    }
}
