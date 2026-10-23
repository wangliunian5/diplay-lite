package com.shilapi.xcertplay.usbbt

import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * vc181（外置 USB 蓝牙 C/D 批）：SDP 数据元（encode/decode）。
 * 移植自上游参考实现 usbbt.DataElement.java + Sdp$Malformed.java
 * （encodeTo/decode 被上游内联进合成类，此处按 SDP 规范与上游字段口径还原）。
 *
 * 类型字节：type = b >>> 5（0..7），size index = b and 0x1f。
 * 短 UUID（2/4 字节）按 Bluetooth Base UUID 扩展为 128 位：
 * 0000xxxx-0000-1000-8000-00805F9B34FB。
 */
internal sealed class DataElement {
    object Nil : DataElement()

    data class Bool(val value: Boolean) : DataElement()

    data class UInt(val bytes: Int, val value: Long) : DataElement()

    data class IntElement(val bytes: Int, val value: Long) : DataElement()

    data class Uuid(val uuid: UUID, val bytes: Int) : DataElement()

    data class Text(val value: ByteArray) : DataElement()

    data class Sequence(val items: List<DataElement>) : DataElement() {
        constructor(vararg items: DataElement) : this(items.toList())
    }

    data class Alternative(val items: List<DataElement>) : DataElement()

    /** RFCOMM 协议 UUID（0x0003 扩展），SDP 查询结果比对用。 */
    companion object {
        val UUID_RFCOMM: UUID = uuidOfShort(3L, 2).uuid
        private const val BASE_LSB = -9223371485494954757L // 0x800000805F9B34FB

        /** 短 UUID 扩展：0x0003 → 00000003-0000-1000-8000-00805F9B34FB。 */
        fun uuidOfShort(shortValue: Long, bytes: Int): Uuid {
            val msb = (shortValue shl 32) or 0x1000L
            return Uuid(UUID(msb, BASE_LSB), bytes)
        }

        /** 编码为线上字节（SDP 数据元序列）。 */
        private fun encodeTo(out: ByteArrayOutputStream, element: DataElement) {
            when (element) {
                is Nil -> out.write(byteArrayOf(0x00))
                is Bool -> out.write(byteArrayOf(0x28, if (element.value) 1 else 0))
                is UInt -> {
                    out.write((1 shl 5) or sizeIndexOf(element.bytes))
                    writeBigEndian(out, element.bytes, element.value)
                }
                is IntElement -> {
                    out.write((2 shl 5) or sizeIndexOf(element.bytes))
                    writeBigEndian(out, element.bytes, element.value)
                }
                is Uuid -> when (element.bytes) {
                    2, 4 -> {
                        out.write((3 shl 5) or sizeIndexOf(element.bytes))
                        writeBigEndian(out, element.bytes, element.uuid.mostSignificantBits ushr 32)
                    }
                    else -> {
                        out.write((3 shl 5) or 0x1c) // size index 4 = 16 字节
                        val bb = java.nio.ByteBuffer.allocate(16)
                        bb.putLong(element.uuid.mostSignificantBits)
                        bb.putLong(element.uuid.leastSignificantBits)
                        out.write(bb.array())
                    }
                }
                is Text -> {
                    out.write((4 shl 5) or sizeIndexForLength(element.value.size))
                    writeLength(out, element.value.size)
                    out.write(element.value)
                }
                is Sequence -> {
                    val len = encodedSizeOfItems(element.items)
                    out.write((6 shl 5) or sizeIndexForLength(len))
                    writeLength(out, len)
                    element.items.forEach { encodeTo(out, it) }
                }
                is Alternative -> {
                    val len = encodedSizeOfItems(element.items)
                    out.write((7 shl 5) or sizeIndexForLength(len))
                    writeLength(out, len)
                    element.items.forEach { encodeTo(out, it) }
                }
            }
        }

        /** 递归收集全部 UUID（上游 uuids() 移植）。 */
        fun uuidsOf(element: DataElement): List<UUID> = when (element) {
            is Uuid -> listOf(element.uuid)
            is Sequence -> element.items.flatMap { uuidsOf(it) }
            is Alternative -> element.items.flatMap { uuidsOf(it) }
            else -> emptyList()
        }

        /**
         * 从 [bytes] 的 [offset] 起解码一个数据元。
         * 返回 (元素, 下一个偏移)；格式非法抛 [SdpMalformed]。
         */
        fun decode(bytes: ByteArray, offset: Int): Pair<DataElement, Int> {
            if (offset >= bytes.size) throw SdpMalformed("truncated element header")
            val header = bytes[offset].toInt() and 0xff
            val type = header ushr 5
            val sizeIdx = header and 0x1f
            var off = offset + 1
            fun readBE(n: Int): Long {
                if (off + n > bytes.size) throw SdpMalformed("truncated element body")
                var v = 0L
                for (i in 0 until n) {
                    v = (v shl 8) or (bytes[off + i].toLong() and 0xff)
                }
                off += n
                return v
            }
            return when (type) {
                0 -> {
                    if (sizeIdx != 0) throw SdpMalformed("nil with size $sizeIdx")
                    Nil to off
                }
                1 -> {
                    val w = widthOf(sizeIdx)
                    UInt(w, readBE(w)) to off
                }
                2 -> {
                    val w = widthOf(sizeIdx)
                    IntElement(w, readBE(w)) to off
                }
                3 -> when (sizeIdx) {
                    1 -> uuidOfShort(readBE(2), 2) to off
                    2 -> uuidOfShort(readBE(4), 4) to off
                    4 -> {
                        val msb = readBE(8)
                        val lsb = readBE(8)
                        Uuid(UUID(msb, lsb), 16) to off
                    }
                    else -> throw SdpMalformed("uuid size $sizeIdx")
                }
                4, 6, 7 -> {
                    val len = lengthOf(sizeIdx, bytes, off)
                    off += when (sizeIdx) {
                        5 -> 1
                        6 -> 2
                        7 -> 4
                        else -> 0 // size index 0..4：长度即 sizeIdx 本身，无额外长度字节
                    }
                    if (len < 0 || off + len > bytes.size) throw SdpMalformed("bad aggregate length $len")
                    val end = off + len
                    if (type == 4) {
                        val text = bytes.copyOfRange(off, end)
                        off = end
                        Text(text) to off
                    } else {
                        val items = ArrayList<DataElement>()
                        while (off < end) {
                            val (element, next) = decode(bytes, off)
                            items.add(element)
                            off = next
                        }
                        if (off != end) throw SdpMalformed("aggregate overrun")
                        (if (type == 6) Sequence(items) else Alternative(items)) to off
                    }
                }
                else -> throw SdpMalformed("bad type $type")
            }
        }

        private fun sizeIndexOf(bytes: Int): Int = when (bytes) {
            1 -> 0
            2 -> 1
            4 -> 2
            8 -> 3
            16 -> 4
            else -> throw IllegalArgumentException("bad element width: $bytes")
        }

        private fun sizeIndexForLength(len: Int): Int = when {
            len < 0 -> throw IllegalArgumentException("negative length")
            len <= 4 -> len // size index 0..4 = 长度本身（0..4 字节）
            len <= 0xff -> 5
            len <= 0xffff -> 6
            else -> 7
        }

        /**
         * 追加的长度字节：size index 0..4 时长度已编码在类型字节里，**不再写长度字节**
         *（与 [sizeIndexForLength] 及 decode 中 `off += 0` 的口径必须一致）。
         */
        private fun writeLength(out: ByteArrayOutputStream, len: Int) {
            when {
                len <= 4 -> Unit
                len <= 0xff -> out.write(byteArrayOf(len.toByte()))
                len <= 0xffff -> out.write(byteArrayOf((len ushr 8).toByte(), len.toByte()))
                else -> out.write(
                    byteArrayOf(
                        (len ushr 24).toByte(),
                        ((len ushr 16) and 0xff).toByte(),
                        ((len ushr 8) and 0xff).toByte(),
                        len.toByte(),
                    ),
                )
            }
        }

        private fun writeBigEndian(out: ByteArrayOutputStream, bytes: Int, value: Long) {
            for (i in bytes - 1 downTo 0) {
                out.write(((value ushr (i * 8)) and 0xff).toInt())
            }
        }

        private fun encodedSizeOfItems(items: List<DataElement>): Int {
            val out = ByteArrayOutputStream()
            items.forEach { encodeTo(out, it) }
            return out.size()
        }

        private fun widthOf(sizeIdx: Int): Int = when (sizeIdx) {
            0 -> 1
            1 -> 2
            2 -> 4
            3 -> 8
            4 -> 16
            else -> throw SdpMalformed("int size $sizeIdx")
        }

        private fun lengthOf(sizeIdx: Int, bytes: ByteArray, off: Int): Int = when (sizeIdx) {
            in 0..4 -> sizeIdx
            5 -> bytes[off].toInt() and 0xff
            6 -> ((bytes[off].toInt() and 0xff) shl 8) or (bytes[off + 1].toInt() and 0xff)
            7 ->
                ((bytes[off].toInt() and 0xff) shl 24) or ((bytes[off + 1].toInt() and 0xff) shl 16) or
                    ((bytes[off + 2].toInt() and 0xff) shl 8) or (bytes[off + 3].toInt() and 0xff)
            else -> throw SdpMalformed("size $sizeIdx")
        }
    }

    class SdpMalformed(message: String) : Exception(message)

    /** 对外编码入口（上游 encode()）。 */
    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        encodeTo(out, this)
        return out.toByteArray()
    }
}
