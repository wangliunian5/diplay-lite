package com.shilapi.xcertplay.usbbt

/**
 * vc181（外置 USB 蓝牙 C/D 批）：RFCOMM 帧编解码。
 * 移植自上游参考实现 usbbt.RfcommFrames.java。
 *
 * 帧布局：addr(EA=1,CR,DLCI<<2) + control(type,PF=0x10) + len(EA 变长) +
 * [credits(UIH+PF)] + info + FCS。UIH 的 FCS 只算前 2 字节，其余算整个头部。
 * 帧类型：SABM=0x2F / UA=0x63 / DM=0x0F / DISC=0x43 / UIH=0xEF。
 */
internal object RfcommFrames {
    const val TYPE_SABM = 0x2f // 47
    const val TYPE_UA = 0x63 // 99
    const val TYPE_DM = 0x0f // 15
    const val TYPE_DISC = 0x43 // 67
    const val TYPE_UIH = 0xef // 239

    /** 解析出的帧。 */
    class Frame(
        val dlci: Int,
        val type: Int,
        val credits: Int?,
        val cr: Boolean,
        val pf: Boolean,
        val info: ByteArray,
    )

    /** 复用命令（MCC）体。 */
    class Mcc(val type: Int, val command: Boolean, val value: ByteArray)

    /** 参数协商（PN 0x20）体。 */
    class Pn(
        val dlci: Int,
        val convergence: Int,
        val priority: Int,
        val frameSize: Int,
        val credits: Int,
    )

    private val CRC_TABLE = IntArray(256).also { table ->
        for (i in 0 until 256) {
            var v = i
            repeat(8) {
                val odd = v and 1
                v = v ushr 1
                if (odd != 0) v = v xor 224 // 反射多项式 0x07 → 反转 0xE0
            }
            table[i] = v
        }
    }

    /** RFCOMM FCS（CRC-8，初值 0xFF，结果取反）。 */
    fun fcs(len: Int, data: ByteArray): Int {
        var crc = 0xff
        for (i in 0 until len) {
            crc = CRC_TABLE[crc xor (data[i].toInt() and 0xff)]
        }
        return 0xff - crc
    }

    /**
     * 组一帧。FCS 口径与上游一致：UIH 只覆盖前 2 字节（addr+control），
     * 其余类型覆盖整个帧头（addr+control+len，len 变长时 3 字节）。
     */
    fun build(dlci: Int, type: Int, credits: Int?, cr: Boolean, pf: Boolean, info: ByteArray): ByteArray {
        require(dlci in 0 until 64) { "DLCI" }
        require(info.size <= 32767) { "RFCOMM information field too long" }
        if (credits != null && (type != TYPE_UIH || credits !in 0..255)) {
            throw IllegalArgumentException("credits only on UIH")
        }
        val head = ArrayList<Byte>(3)
        head.add(((dlci shl 2) or (if (cr) 2 else 0) or 1).toByte())
        head.add((((if (pf || credits != null) 16 else 0) or type).toByte()))
        if (info.size <= 127) {
            head.add(((info.size shl 1) or 1).toByte())
        } else {
            head.add(((info.size and 127) shl 1).toByte())
            head.add((info.size ushr 7).toByte())
        }
        val header = head.toByteArray()
        val fcs = fcs(if (type != TYPE_UIH) header.size else 2, header)
        return header + (credits?.toByte()?.let { byteArrayOf(it) } ?: ByteArray(0)) + info + byteArrayOf(fcs.toByte())
    }

    /** 组 MCC 体（MCC 值最长 127 字节，EA 恒单字节）。 */
    fun mcc(type: Int, command: Boolean, value: ByteArray): ByteArray {
        require(value.size <= 127) { "MCC value too long" }
        return byteArrayOf(
            ((type shl 2) or (if (command) 2 else 0) or 1).toByte(),
            ((value.size shl 1) or 1).toByte(),
        ) + value
    }
}
