package com.shilapi.xcertplay.airplay

/**
 * Minimal Apple binary property list (bplist00) codec for the CarPlay control channel.
 *
 * It covers the subset the stack emits and reads: dictionaries, arrays, ASCII and UTF-16
 * strings, raw data, non-negative integers, 32/64-bit reals, and booleans. Dictionary keys are
 * serialized in insertion order, matching the reference implementation this stack targets.
 */
object BplistCodec {
    private val magic = "bplist00".toByteArray(Charsets.US_ASCII)

    // vc178（网络可控输入边界校验）：bplist 全部来自 AirPlay 控制通道的对端字节，
    // 校验前直接拿字段寻址会把对端数据变成 NegativeArraySizeException / AIOOBE /
    // StackOverflowError / OOM——JVM 内存安全不会破内存，但会让解码线程当场崩掉，
    // 且崩溃点离真正原因很远。以下上限都远大于合法载荷（真实 plist 一般几十个对象）：
    // 超限一律 IllegalArgumentException，由调用方归类为对端协议错误。
    private const val MAX_OBJECTS = 1 shl 16 // 65536 个对象，合法载荷的两个数量级以上
    private const val MAX_DEPTH = 32 // 嵌套深度上限，防递归爆栈
    private const val MAX_INT_WIDTH = 8 // 整数/引用宽度上限（u64）

    fun decode(bytes: ByteArray): Any? {
        require(bytes.size >= 40) { "bplist: too short" }
        require(bytes.copyOfRange(0, magic.size).contentEquals(magic)) { "bplist: bad magic" }

        val trailer = bytes.size - 32
        val offsetSize = bytes[trailer + 6].toInt() and 0xff
        val refSize = bytes[trailer + 7].toInt() and 0xff
        require(offsetSize in 1..MAX_INT_WIDTH) { "bplist: bad offsetSize $offsetSize" }
        require(refSize in 1..4) { "bplist: bad refSize $refSize" }
        val numObjects = readBigEndianLong(bytes, (trailer + 8).toLong(), 8)
        val topObject = readBigEndianLong(bytes, (trailer + 16).toLong(), 8).toInt()
        val offsetTable = readBigEndianLong(bytes, (trailer + 24).toLong(), 8)
        // numObjects 原为 u64 截断成 Int —— 截断后可能变小甚至变负，必须先判范围再 LongArray。
        require(numObjects >= 0 && numObjects <= MAX_OBJECTS) { "bplist: numObjects out of range ($numObjects)" }
        require(offsetTable >= 0) { "bplist: negative offset table ($offsetTable)" }
        require(
            offsetTable + numObjects * offsetSize.toLong() <= bytes.size.toLong(),
        ) { "bplist: offset table out of range" }
        require(topObject in 0 until numObjects.toInt()) { "bplist: topObject out of range ($topObject)" }

        val offsets = LongArray(numObjects.toInt())
        for (index in offsets.indices) {
            offsets[index] = readBigEndianLong(bytes, offsetTable + index.toLong() * offsetSize, offsetSize)
        }
        return readObject(bytes, offsets, refSize, topObject, 0)
    }

    fun encode(root: Any?): ByteArray {
        val nodes = ArrayList<Node>()

        fun add(value: Any?): Int {
            val index = nodes.size
            nodes.add(Leaf(ByteArray(0)))
            when (value) {
                is Boolean -> nodes[index] = Leaf(byteArrayOf(if (value) 0x09 else 0x08))
                is java.math.BigInteger -> {
                    require(value.signum() >= 0) { "bplist: negative integers are not supported" }
                    require(value.bitLength() <= 64) { "bplist: integer exceeds 64 bits" }
                    nodes[index] = Leaf(encodeUnsignedInt(value))
                }
                is Byte, is Short, is Int, is Long -> {
                    val number = (value as Number).toLong()
                    nodes[index] = if (number >= 0) {
                        Leaf(encodeInt(number))
                    } else {
                        Leaf(byteArrayOf(0x23) + bigEndian(number.toDouble().toRawBits(), 8))
                    }
                }
                is Float, is Double -> {
                    val number = (value as Number).toDouble()
                    nodes[index] = Leaf(byteArrayOf(0x23) + bigEndian(number.toRawBits(), 8))
                }
                is String -> nodes[index] = Leaf(encodeString(value))
                is ByteArray -> nodes[index] = Leaf(marker(0x4, value.size) + value)
                is List<*> -> {
                    val refs = value.map { add(it) }.toIntArray()
                    nodes[index] = Container(marker(0xa, value.size), refs)
                }
                is Map<*, *> -> {
                    val entries = value.entries.toList()
                    val keyRefs = entries.map { add(it.key.toString()) }.toIntArray()
                    val valueRefs = entries.map { add(it.value) }.toIntArray()
                    nodes[index] = Container(marker(0xd, entries.size), keyRefs + valueRefs)
                }
                else -> throw IllegalArgumentException("bplist: unsupported value $value")
            }
            return index
        }

        val topIndex = add(root)
        val refSize = when {
            nodes.size > 0xffff -> 4
            nodes.size > 0xff -> 2
            else -> 1
        }

        val parts = ArrayList<ByteArray>(nodes.size + 2)
        parts.add(magic)
        var cursor = magic.size
        val offsets = ArrayList<Int>(nodes.size)
        for (node in nodes) {
            offsets.add(cursor)
            val serialized = when (node) {
                is Leaf -> node.body
                is Container -> node.head + refBytes(node.refs, refSize)
            }
            parts.add(serialized)
            cursor += serialized.size
        }

        val offsetTableOffset = cursor
        val offsetSize = when {
            cursor > 0xffff -> 4
            cursor > 0xff -> 2
            else -> 1
        }
        for (offset in offsets) parts.add(bigEndian(offset.toLong(), offsetSize))

        val trailer = ByteArray(32)
        trailer[6] = offsetSize.toByte()
        trailer[7] = refSize.toByte()
        writeBigEndian(trailer, 8, nodes.size.toLong(), 8)
        writeBigEndian(trailer, 16, topIndex.toLong(), 8)
        writeBigEndian(trailer, 24, offsetTableOffset.toLong(), 8)
        parts.add(trailer)
        return concatBytes(*parts.toTypedArray())
    }

    private fun readObject(
        bytes: ByteArray,
        offsets: LongArray,
        refSize: Int,
        index: Int,
        depth: Int,
    ): Any? {
        require(depth <= MAX_DEPTH) { "bplist: nesting deeper than $MAX_DEPTH" }
        require(index in offsets.indices) { "bplist: object reference out of range ($index)" }
        var position = offsets[index].toInt()
        require(position in 0 until bytes.size) { "bplist: object offset out of range ($position)" }
        val markerByte = bytes[position].toInt() and 0xff
        val type = markerByte ushr 4
        val nibble = markerByte and 0x0f
        position++

        // count 一律按「剩余字节数」封顶：既防负值（copyOfRange 抛 NegativeArraySizeException），
        // 也防超大计数把内存吃光（ArrayList(capacity) / LinkedHashMap(capacity) 同理）。
        fun readCount(): Int {
            if (nibble != 0x0f) return nibble
            require(position < bytes.size) { "bplist: truncated count marker" }
            val sizeMarker = bytes[position].toInt() and 0xff
            position++
            val intBytes = 1 shl (sizeMarker and 0x0f)
            require(intBytes <= MAX_INT_WIDTH) { "bplist: count width too large ($intBytes)" }
            require(position + intBytes <= bytes.size) { "bplist: truncated count" }
            val count = readBigEndianLong(bytes, position.toLong(), intBytes)
            position += intBytes
            require(count >= 0 && count <= bytes.size.toLong()) { "bplist: count out of range ($count)" }
            return count.toInt()
        }

        fun requireSpan(span: Int) {
            require(span >= 0 && position + span <= bytes.size) {
                "bplist: truncated payload (need $span at $position)"
            }
        }

        return when (type) {
            0x0 -> when (nibble) {
                0x08 -> false
                0x09 -> true
                else -> throw IllegalArgumentException("bplist: unsupported primitive 0x0$nibble")
            }
            0x1 -> {
                val size = 1 shl nibble
                require(size <= MAX_INT_WIDTH) { "bplist: bad integer width $size" }
                requireSpan(size)
                readBigEndianLong(bytes, position.toLong(), size)
            }
            0x2 -> {
                val size = 1 shl nibble
                when (size) {
                    4 -> {
                        requireSpan(size)
                        Float.fromBits(readBigEndianLong(bytes, position.toLong(), size).toInt())
                    }
                    8 -> {
                        requireSpan(size)
                        Double.fromBits(readBigEndianLong(bytes, position.toLong(), size))
                    }
                    else -> throw IllegalArgumentException("bplist: unsupported real size $size")
                }
            }
            0x4 -> {
                val count = readCount()
                requireSpan(count)
                bytes.copyOfRange(position, position + count)
            }
            0x5 -> {
                val count = readCount()
                requireSpan(count)
                String(bytes, position, count, Charsets.US_ASCII)
            }
            0x6 -> {
                val count = readCount()
                requireSpan(count * 2)
                String(bytes, position, count * 2, Charsets.UTF_16BE)
            }
            0xa -> {
                val count = readCount()
                requireSpan(count * refSize)
                val array = ArrayList<Any?>(count)
                for (i in 0 until count) {
                    val reference = readBigEndianLong(bytes, position + i.toLong() * refSize, refSize).toInt()
                    array.add(readObject(bytes, offsets, refSize, reference, depth + 1))
                }
                array
            }
            0xd -> {
                val count = readCount()
                // 字典是 key 段 + value 段两段引用，各 count 个
                requireSpan(count * 2 * refSize)
                val dict = LinkedHashMap<String, Any?>(count)
                for (i in 0 until count) {
                    val keyReference = readBigEndianLong(bytes, position + i.toLong() * refSize, refSize).toInt()
                    val valueReference =
                        readBigEndianLong(bytes, position + (count + i).toLong() * refSize, refSize).toInt()
                    dict[readObject(bytes, offsets, refSize, keyReference, depth + 1).toString()] =
                        readObject(bytes, offsets, refSize, valueReference, depth + 1)
                }
                dict
            }
            else -> throw IllegalArgumentException("bplist: unsupported object type 0x${type.toString(16)}")
        }
    }

    private fun encodeString(value: String): ByteArray {
        val ascii = value.all { it.code <= 0x7f }
        return if (ascii) {
            marker(0x5, value.length) + value.toByteArray(Charsets.US_ASCII)
        } else {
            marker(0x6, value.length) + value.toByteArray(Charsets.UTF_16BE)
        }
    }

    private fun encodeInt(number: Long): ByteArray {
        val size = when {
            number > 0xffffffffL -> 8
            number > 0xffffL -> 4
            number > 0xffL -> 2
            else -> 1
        }
        val log = when (size) {
            8 -> 3
            4 -> 2
            2 -> 1
            else -> 0
        }
        return byteArrayOf((0x10 or log).toByte()) + bigEndian(number, size)
    }

    private fun encodeUnsignedInt(number: java.math.BigInteger): ByteArray {
        val size = when {
            number.bitLength() > 32 -> 8
            number.bitLength() > 16 -> 4
            number.bitLength() > 8 -> 2
            else -> 1
        }
        val log = when (size) {
            8 -> 3
            4 -> 2
            2 -> 1
            else -> 0
        }
        val raw = number.toByteArray()
        return byteArrayOf((0x10 or log).toByte()) + raw.copyOfRange(raw.size - size, raw.size)
    }

    private fun marker(type: Int, count: Int): ByteArray {
        if (count < 0x0f) return byteArrayOf(((type shl 4) or count).toByte())
        val size = when {
            count > 0xffff -> 4
            count > 0xff -> 2
            else -> 1
        }
        val log = when (size) {
            4 -> 2
            2 -> 1
            else -> 0
        }
        return byteArrayOf(((type shl 4) or 0x0f).toByte(), (0x10 or log).toByte()) +
            bigEndian(count.toLong(), size)
    }

    private fun refBytes(refs: IntArray, refSize: Int): ByteArray {
        val output = ByteArray(refs.size * refSize)
        for (index in refs.indices) writeBigEndian(output, index * refSize, refs[index].toLong(), refSize)
        return output
    }

    private fun bigEndian(value: Long, size: Int): ByteArray {
        val output = ByteArray(size)
        writeBigEndian(output, 0, value, size)
        return output
    }

    private fun writeBigEndian(target: ByteArray, offset: Int, value: Long, size: Int) {
        var current = value
        for (index in size - 1 downTo 0) {
            target[offset + index] = (current and 0xff).toByte()
            current = current ushr 8
        }
    }

    private fun readBigEndianLong(bytes: ByteArray, offset: Long, size: Int): Long {
        // vc178：所有读取统一在这里做边界校验（原先直接下标访问，越界即 AIOOBE）。
        require(size >= 0 && size <= MAX_INT_WIDTH) { "bplist: bad read width $size" }
        val base = offset.toInt()
        require(offset >= 0 && base <= bytes.size - size) { "bplist: read out of range ($offset+$size)" }
        var value = 0L
        for (index in 0 until size) {
            value = (value shl 8) or (bytes[base + index].toLong() and 0xffL)
        }
        return value
    }

    private sealed class Node
    private class Leaf(val body: ByteArray) : Node()
    private class Container(val head: ByteArray, val refs: IntArray) : Node()
}
