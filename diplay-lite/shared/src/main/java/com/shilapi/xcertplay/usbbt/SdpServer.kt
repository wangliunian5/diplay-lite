package com.shilapi.xcertplay.usbbt

import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * vc228：本机 SDP 服务端（L2CAP PSM 0x0001）。
 *
 * 实测根因（vc227 K60 日志）：iPhone 一连上就把自己的 SDP 客户端打到我们这侧 —
 * `13:36:22.258 l2cap: peer uses psm=1 supported=false` → 我们回
 * CONNECTION_RESPONSE(result=0x0002 PSM 不支持) → `13:36:23.347 link 0x047
 * disconnected (reason 0x13)`（iPhone 自己 1.1s 内放弃）→ iPhone UI 显示
 * 「连接失败 / 不在范围」。对照 FreeAuto 同源实现：它的 `sdp=` 计数在**配对阶段**
 * 就爬到 3（即它应答了 iPhone 的三次 SDP 查询），随后加密、SDP 客户端查询、
 * RFCOMM、iAP2 全通。我们此前明确不注册 SDP 服务端（只做客户端），所以永远停在
 * 「配对成功但连不上、传不了 iAP2」。
 *
 * 记录口径对齐上游（反编译 UsbBtHost 构造 services 处还原）：
 * handle 0x00010000；ServiceClassIDList = iAP2 Accessory UUID（…caff）；
 * ProtocolDescriptorList = [L2CAP(RFCOMM PSM 3), RFCOMM + channel]；
 * BrowseGroupList = PublicBrowseRoot(0x1002)；ProfileDescriptorList = SPP(0x1101) v1.0；
 * ServiceName(0x0100) = "Wireless iAPv2"。
 *
 * 只实现 iPhone 实际会用到的 4 类 PDU：ServiceSearch / ServiceAttribute /
 * ServiceSearchAttribute 三类请求 + ErrorResponse；记录很小，continuation 一律回 0。
 */
internal class SdpServer(
    private val rfcommChannel: Int,
    serviceName: String = "Wireless iAPv2",
    private val log: (String) -> Unit = {},
) : L2capService {

    /** 本记录属性（attrId → 值）；编码时按 attrId 升序拼进 AttributeList。 */
    private val attributes: List<Pair<Int, DataElement>> = listOf(
        0x0000 to DataElement.UInt(4, HANDLE.toLong()),
        0x0001 to DataElement.Sequence(
            DataElement.Uuid(UsbBtIdentity.IAP2_ACCESSORY_UUID, 16),
        ),
        // vc229：协议描述符里的 L2CAP(0x0100)/RFCOMM(0x0003) 是 **UUID16**，不是整数。
        0x0004 to DataElement.Sequence(
            DataElement.Sequence(
                DataElement.uuidOfShort(UUID_L2CAP, 2),
                DataElement.UInt(2, PSM_RFCOMM.toLong()),
            ),
            DataElement.Sequence(
                DataElement.uuidOfShort(UUID_RFCOMM, 2),
                DataElement.UInt(1, rfcommChannel.toLong()),
            ),
        ),
        0x0005 to DataElement.Sequence(DataElement.uuidOfShort(UUID_PUBLIC_BROWSE_ROOT, 2)),
        0x0009 to DataElement.Sequence(
            DataElement.Sequence(
                DataElement.uuidOfShort(UUID_SERIAL_PORT, 2),
                DataElement.UInt(2, 0x0100L),
            ),
        ),
        0x0100 to DataElement.Text(serviceName.toByteArray(Charsets.UTF_8)),
    )

    /** 记录对外声明的全部 UUID，作为对端 ServiceSearch 的命中判据。 */
    private val serviceUuids: Set<UUID> = attributes
        .filter { it.first != 0x0000 && it.first != 0x0100 }
        .flatMap { DataElement.uuidsOf(it.second) }
        .toSet()

    override fun open(channel: L2capLayer.Channel): L2capChannelHandler {
        log("sdp: server channel open（应答 iPhone 的 SDP 查询，rfcomm=$rfcommChannel）")
        return object : L2capChannelHandler {
            override fun onClosed(channel: L2capLayer.Channel) {
                log("sdp: server channel closed")
            }

            override fun onData(channel: L2capLayer.Channel, data: ByteArray) {
                if (data.size < 5) return
                val pduId = data[0].toInt() and 0xff
                val tid = be16(data, 1)
                val paramLen = be16(data, 3)
                if (data.size < 5 + paramLen) {
                    log("sdp: server 忽略不完整 PDU（len=${data.size}，期望 ${5 + paramLen}）")
                    return
                }
                val params = data.copyOfRange(5, 5 + paramLen)
                log("sdp: server pdu=0x%02x tid=%d len=%d req=%s".format(pduId, tid, paramLen, data.toHex()))
                when (pduId) {
                    PDU_SERVICE_SEARCH_REQUEST -> onServiceSearch(channel, tid, params)
                    PDU_SERVICE_ATTRIBUTE_REQUEST -> onServiceAttribute(channel, tid, params)
                    PDU_SERVICE_SEARCH_ATTRIBUTE_REQUEST -> onServiceSearchAttribute(channel, tid, params)
                    // 其余（含 ServiceSearchAttribute 以外的旧 PDU）按语法错误回，避免对端干等
                    else -> errorResponse(channel, tid, 0x0003)
                }
            }
        }
    }

    /** ServiceSearchRequest：ServiceSearchPattern + maxRecordCount + continuation。 */
    private fun onServiceSearch(channel: L2capLayer.Channel, tid: Int, params: ByteArray) {
        val decoded = runCatching { DataElement.decode(params, 0) }.getOrElse {
            errorResponse(channel, tid, 0x0003)
            return
        }
        val (pattern, next) = decoded
        val maxCount = if (params.size >= next + 2) be16(params, next) else 0xffff
        val matched = if (matches(pattern)) listOf(HANDLE) else emptyList()
        val total = matched.size
        val current = if (maxCount in 1 until total) maxCount else total
        val out = ByteArrayOutputStream()
        writeBe16(out, total)
        writeBe16(out, current)
        matched.take(current).forEach { writeBe32(out, it) }
        out.write(0) // continuation state：长度 0（无续查）
        send(channel, tid, PDU_SERVICE_SEARCH_RESPONSE, out.toByteArray())
    }

    /** ServiceAttributeRequest：handle(4) + maxAttributeByteCount(2) + AttributeIDList + continuation。 */
    private fun onServiceAttribute(channel: L2capLayer.Channel, tid: Int, params: ByteArray) {
        if (params.size < 6) {
            errorResponse(channel, tid, 0x0003)
            return
        }
        val handle = be32(params, 0)
        val ids = runCatching { DataElement.decode(params, 6) }.getOrElse {
            errorResponse(channel, tid, 0x0003)
            return
        }
        if (handle != HANDLE) {
            errorResponse(channel, tid, 0x0002) // 无效 record handle
            return
        }
        val list = attributeList(parseRanges(ids.first))
        val out = ByteArrayOutputStream()
        writeBe16(out, list.size)
        out.write(list)
        out.write(0) // continuation state
        send(channel, tid, PDU_SERVICE_ATTRIBUTE_RESPONSE, out.toByteArray())
    }

    /**
     * ServiceSearchAttributeRequest：ServiceSearchPattern + maxAttributeByteCount(2)
     * + AttributeIDList + continuation。
     */
    private fun onServiceSearchAttribute(channel: L2capLayer.Channel, tid: Int, params: ByteArray) {
        val pattern = runCatching { DataElement.decode(params, 0) }.getOrElse {
            errorResponse(channel, tid, 0x0003)
            return
        }
        val (patternElement, afterPattern) = pattern
        val idsOffset = afterPattern + 2 // 跳过 maxAttributeByteCount
        if (params.size <= idsOffset) {
            errorResponse(channel, tid, 0x0003)
            return
        }
        val ids = runCatching { DataElement.decode(params, idsOffset) }.getOrElse {
            errorResponse(channel, tid, 0x0003)
            return
        }
        if (!matches(patternElement)) {
            // 没有命中记录：AttributeLists 为空（仍回合法响应，iPhone 才会继续下一步）
            val empty = ByteArrayOutputStream()
            writeBe16(empty, 0)
            empty.write(0)
            send(channel, tid, PDU_SERVICE_SEARCH_ATTRIBUTE_RESPONSE, empty.toByteArray())
            return
        }
        val list = attributeList(parseRanges(ids.first))
        val out = ByteArrayOutputStream()
        writeBe16(out, list.size)
        out.write(list)
        out.write(0)
        send(channel, tid, PDU_SERVICE_SEARCH_ATTRIBUTE_RESPONSE, out.toByteArray())
    }

    /** ServiceSearchPattern 是否命中本记录（任一 UUID 交集）。 */
    private fun matches(pattern: DataElement): Boolean {
        val wanted = DataElement.uuidsOf(pattern)
        return wanted.any { it in serviceUuids }
    }

    /** AttributeIDList → 区间列表（bytes=2 单 ID；bytes=4 为 start/end 区间；空 = 全部）。 */
    private fun parseRanges(element: DataElement): List<IntRange> {
        val items = when (element) {
            is DataElement.Sequence -> element.items
            else -> listOf(element)
        }
        return items.mapNotNull { item ->
            if (item !is DataElement.UInt) return@mapNotNull null
            when (item.bytes) {
                2 -> {
                    val id = item.value.toInt()
                    id..id
                }
                else -> {
                    val start = ((item.value ushr 16) and 0xffff).toInt()
                    val end = (item.value and 0xffff).toInt()
                    start..end
                }
            }
        }
    }

    /** 按区间筛属性并编码成 AttributeList（Sequence(UInt16 attrId, 值)…）。 */
    private fun attributeList(ranges: List<IntRange>): ByteArray {
        val items = ArrayList<DataElement>()
        for ((id, value) in attributes) {
            if (ranges.isEmpty() || ranges.any { id in it }) {
                items.add(DataElement.UInt(2, id.toLong()))
                items.add(value)
            }
        }
        return DataElement.Sequence(items).encode()
    }

    private fun errorResponse(channel: L2capLayer.Channel, tid: Int, code: Int) {
        val params = be16(code)
        log("sdp: server ErrorResponse tid=$tid code=0x%04x".format(code))
        send(channel, tid, PDU_ERROR_RESPONSE, params)
    }

    private fun send(channel: L2capLayer.Channel, tid: Int, pduId: Int, params: ByteArray) {
        val body = byteArrayOf(pduId.toByte()) + be16(tid) + be16(params.size) + params
        log("sdp: server rsp pdu=0x%02x tid=%d size=${body.size} rsp=${body.toHex()}")
        try {
            channel.write(body)
        } catch (t: Throwable) {
            log("sdp: server 回包失败 ${t.message}")
        }
    }

    companion object {
        private const val PDU_ERROR_RESPONSE = 1
        private const val PDU_SERVICE_SEARCH_REQUEST = 2
        private const val PDU_SERVICE_SEARCH_RESPONSE = 3
        private const val PDU_SERVICE_ATTRIBUTE_REQUEST = 4
        private const val PDU_SERVICE_ATTRIBUTE_RESPONSE = 5
        private const val PDU_SERVICE_SEARCH_ATTRIBUTE_REQUEST = 6
        private const val PDU_SERVICE_SEARCH_ATTRIBUTE_RESPONSE = 7

        /** 本机唯一记录句柄（SDP 服务端自由分配的 32 位值，0x00010000 是常见起始值）。 */
        private const val HANDLE = 0x00010000

        /** RFCOMM 的 L2CAP PSM（固定 0x0003）。 */
        private const val PSM_RFCOMM = 3

        /** 协议/服务类 UUID16（Bluetooth Base UUID 的短形式）。 */
        private const val UUID_L2CAP = 0x0100L
        private const val UUID_RFCOMM = 0x0003L
        private const val UUID_PUBLIC_BROWSE_ROOT = 0x1002L
        private const val UUID_SERIAL_PORT = 0x1101L

        private fun be16(bytes: ByteArray, offset: Int): Int {
            if (offset + 2 > bytes.size) return 0
            return ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
        }

        private fun be32(bytes: ByteArray, offset: Int): Int {
            if (offset + 4 > bytes.size) return 0
            var v = 0
            for (i in 0 until 4) v = (v shl 8) or (bytes[offset + i].toInt() and 0xff)
            return v
        }

        private fun be16(value: Int): ByteArray =
            byteArrayOf((value ushr 8).toByte(), value.toByte())

        private fun writeBe16(out: ByteArrayOutputStream, value: Int) {
            out.write((value ushr 8) and 0xff)
            out.write(value and 0xff)
        }

        private fun writeBe32(out: ByteArrayOutputStream, value: Int) {
            out.write((value ushr 24) and 0xff)
            out.write((value ushr 16) and 0xff)
            out.write((value ushr 8) and 0xff)
            out.write(value and 0xff)
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
