package com.shilapi.xcertplay.usbbt

import java.util.Locale

/**
 * vc181（外置 USB 蓝牙 C/D 批）：蓝牙设备地址基础类型。
 * 移植自上游参考实现 usbbt.BdAddr：地址以 48bit 无符号整数承载，
 * 线上（HCI 参数）低字节在前（LSB first），[toLittleEndian] 供命令参数拼装。
 */
class BdAddr(val value: Long) {
    init {
        require(value in 0 until 0xFFFFFFFFFFFFL) { "BD_ADDR out of range" }
    }

    /** 12 位十六进制大写（无冒号），作 link key 内存 map 的键。 */
    fun key(): String = String.format(Locale.US, "%012X", value)

    /** 隐去真实地址的日志表示（避免把用户车机地址打进日志）。 */
    fun redacted(): String {
        val v = value xor (value ushr 24)
        val mixed = (v xor (v ushr 32)).toInt() and 0xffff
        return String.format(Locale.US, "bdaddr#%04x", mixed)
    }

    /** 线上字节序（LSB 在前），直接作 HCI 命令参数。 */
    fun toLittleEndian(): ByteArray = ByteArray(6) { i -> ((value ushr (i * 8)) and 0xff).toByte() }

    /** "xx:xx:xx:xx:xx:xx"（MSB 在前）。 */
    override fun toString(): String =
        (5 downTo 0).joinToString(":") { i -> "%02x".format((value ushr (i * 8)) and 0xff) }

    override fun equals(other: Any?): Boolean = other is BdAddr && other.value == value

    override fun hashCode(): Int {
        val v = value
        return (v xor (v ushr 32)).toInt()
    }

    companion object {
        /** 从 HCI 参数 [params] 的 [offset] 起取 6 字节（LSB 在前）构造。 */
        fun of(params: ByteArray, offset: Int): BdAddr {
            require(params.size >= offset + 6) { "BD_ADDR needs 6 bytes" }
            var v = 0L
            for (i in 5 downTo 0) {
                v = (v shl 8) or (params[offset + i].toLong() and 0xff)
            }
            return BdAddr(v)
        }
    }
}
