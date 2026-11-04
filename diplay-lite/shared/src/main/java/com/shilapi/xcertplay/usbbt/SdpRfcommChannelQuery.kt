package com.shilapi.xcertplay.usbbt

import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * vc181（外置 USB 蓝牙 C/D 批）：经 SDP 查询目标服务的 RFCOMM 信道号。
 * 移植自上游参考实现 usbbt.SdpRfcommChannelQuery.java（其 handler 被上游 R8
 * 错位合并进 media.CallAudioMode，语义已逐字段剥离还原）。
 *
 * 流程：通道打开后发 ServiceAttributeRequest（PDU 0x06，tid 自 1 起，attr 范围
 * 0x00010004 即 ProtocolDescriptorList）；解析 ServiceAttributeResponse（PDU 0x07，
 * 大端 PDU 头），有 continuation state 就带参续查（tid<16 封顶）；最后从
 * ProtocolDescriptorList 里找 RFCOMM(0x0003) 层，取其后的 UInt 为信道号。
 */
internal class SdpRfcommChannelQuery(
    private val uuid: UUID,
    private val onResult: (Int?) -> Unit,
) : L2capService {
    private var transactionId = 1
    private var done = false
    private var variant = 0
    private val accumulated = ByteArrayOutputStream()

    override fun open(channel: L2capLayer.Channel): L2capChannelHandler {
        val req = buildRequest(variant, transactionId, uuid, ByteArray(0))
        // vc224/vc225：SDP 取证——请求原文 hex + 变体号
        Log.i(UsbBtSession.TAG, "sdp: query sent uuid=$uuid variant=$variant tid=$transactionId req=${req.toHex()}")
        channel.write(req)
        return object : L2capChannelHandler {
            override fun onClosed(channel: L2capLayer.Channel) {
                finish(channel, null)
            }

            override fun onData(channel: L2capLayer.Channel, data: ByteArray) {
                if (done) return
                // vc224：响应原文 hex（ErrorResponse 时含 error code）
                Log.i(UsbBtSession.TAG, "sdp: rsp ${data.size}B pdu=0x%02x raw=%s".format(data.firstOrNull()?.toInt()?.and(0xff) ?: -1, data.toHex()))
                // vc225：iPhone 严格解析 SDP——上游那套 AttributeIDList（单个 4 字节 ID
                // 0x00010004）被回 ErrorResponse 0x0006（实测 vc223/vc224 两轮）。
                // 这里逐个换标准写法试探，日志直接给出哪种被接受。
                if (data.size >= 7 && (data[0].toInt() and 0xff) == PDU_ERROR_RESPONSE) {
                    val code = ((data[5].toInt() and 0xff) shl 8) or (data[6].toInt() and 0xff)
                    Log.w(UsbBtSession.TAG, "sdp: ErrorResponse code=0x%04x variant=$variant".format(code))
                    if (variant + 1 < VARIANT_COUNT) {
                        variant++
                        transactionId++
                        val next = buildRequest(variant, transactionId, uuid, ByteArray(0))
                        Log.i(UsbBtSession.TAG, "sdp: 换用变体 $variant req=${next.toHex()}")
                        channel.write(next)
                        return
                    }
                    finish(channel, null)
                    return
                }
                val parsed = parseServiceAttributeResponse(data)
                if (parsed == null) {
                    Log.w(UsbBtSession.TAG, "sdp response malformed")
                    finish(channel, null)
                    return
                }
                val (attributeBytes, continuation) = parsed
                accumulated.write(attributeBytes)
                if (continuation.isNotEmpty() && transactionId < 16) {
                    transactionId++
                    channel.write(buildRequest(variant, transactionId, uuid, continuation))
                    return
                }
                val serverChannel = try {
                    extractRfcommChannel(accumulated.toByteArray(), uuid)
                } catch (e: Throwable) {
                    Log.w(UsbBtSession.TAG, "sdp decode failed: $e")
                    null
                }
                finish(channel, serverChannel)
            }
        }
    }

    private fun finish(channel: L2capLayer.Channel, result: Int?) {
        if (done) return
        done = true
        Log.i(UsbBtSession.TAG, "sdp: result rfcommChannel=${result ?: "null"}") // vc212：SDP 静默取证
        try {
            channel.close()
        } catch (_: Throwable) {
        }
        onResult(result)
    }

    companion object {
        private const val PDU_SERVICE_ATTRIBUTE_REQUEST = 6
        private const val PDU_SERVICE_ATTRIBUTE_RESPONSE = 7
        private const val PDU_ERROR_RESPONSE = 1

        /** vc225：SDP 请求编码变体数（0=上游原样，1..3 标准写法）。 */
        private const val VARIANT_COUNT = 4

        /** vc224：hex dump（取证）。 */
        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        /**
         * vc225：按变体构造 ServiceSearchAttributeRequest。
         *
         * 背景：上游（FreeAuto 1.2.4）的 `UnsignedKt.searchAttributeRequest` 把
         * AttributeIDList 写成单元素 `UInt(4 字节, 0x00010004)`，iPhone 实测直接回
         * ErrorResponse 0x0006。按 SDP 规范属性 ID 是 2 字节元素、范围用「起、止」
         * 两个 ID 表达，故：
         *  - 变体 0：上游原样（raw 0xFFFF 最大属性字节数 + 单个 UInt32 0x00010004）
         *  - 变体 1：AttributeIDList = Seq{UInt16 0x0001, UInt16 0x0004}（即 1..4 全段）
         *  - 变体 2：最大属性字节数改数据元素 0x09 0xFFFF + Seq{UInt16 0x0001, 0x0004}
         *  - 变体 3：只需 ProtocolDescriptorList → Seq{UInt16 0x0004}
         */
        private fun buildRequest(variant: Int, tid: Int, uuid: UUID, continuation: ByteArray): ByteArray {
            val params = ByteArrayOutputStream()
            params.write(DataElement.Sequence(DataElement.Uuid(uuid, 16)).encode())
            when (variant) {
                0 -> {
                    params.write(0xff)
                    params.write(0xff)
                    params.write(DataElement.Sequence(DataElement.UInt(4, 0x10004L)).encode())
                }
                1 -> {
                    params.write(0xff)
                    params.write(0xff)
                    params.write(
                        DataElement.Sequence(
                            listOf(DataElement.UInt(2, 0x0001L), DataElement.UInt(2, 0x0004L)),
                        ).encode(),
                    )
                }
                2 -> {
                    params.write(0x09)
                    params.write(0xff)
                    params.write(0xff)
                    params.write(
                        DataElement.Sequence(
                            listOf(DataElement.UInt(2, 0x0001L), DataElement.UInt(2, 0x0004L)),
                        ).encode(),
                    )
                }
                else -> {
                    params.write(0xff)
                    params.write(0xff)
                    params.write(DataElement.Sequence(DataElement.UInt(2, 0x0004L)).encode())
                }
            }
            params.write(continuation.size)
            params.write(continuation)
            val body = params.toByteArray()
            return byteArrayOf(
                PDU_SERVICE_ATTRIBUTE_REQUEST.toByte(),
                (tid ushr 8).toByte(),
                tid.toByte(),
                (body.size ushr 8).toByte(),
                body.size.toByte(),
            ) + body
        }

        /** 保留原入口（上游语义）——等价变体 0。 */
        fun searchAttributeRequest(tid: Int, uuid: UUID, continuation: ByteArray): ByteArray =
            buildRequest(0, tid, uuid, continuation)

        /**
         * 解析 ServiceAttributeResponse：
         * b[0]=PDU(0x07)，attrListLen=BE16@3，attrList@5..，contLen@+，continuation 随后。
         * 非法/中途错误返回 null。
         */
        private fun parseServiceAttributeResponse(data: ByteArray): Pair<ByteArray, ByteArray>? {
            if (data.size < 5 || (data[0].toInt() and 0xff) != PDU_SERVICE_ATTRIBUTE_RESPONSE) return null
            val pduLen = ((data[3].toInt() and 0xff) shl 8) or (data[4].toInt() and 0xff)
            if (data.size < pduLen + 5 || pduLen < 3) return null
            val attrLen = ((data[5].toInt() and 0xff) shl 8) or (data[6].toInt() and 0xff)
            val attrEnd = attrLen + 7
            val contEnd = attrLen + 8
            if (contEnd > pduLen + 5) return null
            val attrBytes = data.copyOfRange(7, attrEnd)
            val contLen = data[attrEnd].toInt() and 0xff
            if (contLen > 16 || contLen + contEnd > pduLen + 5) return null
            val continuation = data.copyOfRange(contEnd, contLen + contEnd)
            return attrBytes to continuation
        }

        /**
         * 从累积的 attribute list 中取目标服务的 RFCOMM 信道号：
         * 每条记录 Sequence，元素按 (UInt attrId, value) 成对；attrId==0x0004 的
         * ProtocolDescriptorList 里逐层找首元素 UUID==RFCOMM 的层，取下一 UInt。
         */
        fun extractRfcommChannel(bytes: ByteArray, uuid: UUID): Int? {
            val (root, _) = DataElement.decode(bytes, 0)
            val records = (root as? DataElement.Sequence)?.items ?: return null
            for (record in records) {
                val fields = (record as? DataElement.Sequence)?.items ?: continue
                var i = 0
                while (i + 1 < fields.size) {
                    val attrId = fields[i] as? DataElement.UInt
                    val value = fields[i + 1]
                    if (attrId != null && attrId.value.toInt() == 4) {
                        val layers = (value as? DataElement.Sequence)?.items ?: continue
                        for (layer in layers) {
                            val items = (layer as? DataElement.Sequence)?.items ?: continue
                            val first = items.firstOrNull() as? DataElement.Uuid ?: continue
                            // 与上游一致：只认 RFCOMM(0x0003) 层，其下一 UInt 为 server channel
                            if (first.uuid == DataElement.UUID_RFCOMM) {
                                val channelElement = items.getOrNull(1) as? DataElement.UInt
                                if (channelElement != null) return channelElement.value.toInt()
                            }
                        }
                    }
                    i += 2
                }
            }
            return null
        }
    }
}
