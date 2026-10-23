package com.shilapi.xcertplay.usbbt

/**
 * vc181（外置 USB 蓝牙 C/D 批）：RFCOMM 挂接 L2CAP 的服务。
 * 移植自上游参考实现 usbbt.RfcommL2capService.java（onData 帧解析被上游 R8
 * 合并进合成类，此处还原为独立解析函数，逐字段对照上游口径）。
 *
 * [connectTo] 非空 = 主动方（对目标 server channel 发 SABM）；
 * 空 = 被动方（等对端 SABM）。
 */
internal class RfcommL2capService(
    private val serverChannels: Set<Int>,
    private val log: (String) -> Unit,
    private val onOpen: (RfcommDlc) -> Unit,
    private val connectTo: Int?,
) : L2capService {
    override fun open(channel: L2capLayer.Channel): L2capChannelHandler {
        val session = RfcommSession(
            initiator = connectTo != null,
            serverChannels = serverChannels,
            remoteL2capMtu = { channel.remoteMtu },
            transmit = { channel.write(it) },
            onOpen = onOpen,
            log = log,
        )
        log(
            "rfcomm: L2CAP channel open (peer MTU ${channel.remoteMtu}" +
                (if (connectTo != null) ", opened by this side" else "") + ")",
        )
        val target = connectTo
        if (target != null) {
            session.start(target)
        }
        return object : L2capChannelHandler {
            override fun onClosed(channel: L2capLayer.Channel) {
                log("rfcomm: L2CAP channel closed")
                session.onL2capClosed()
            }

            override fun onData(channel: L2capLayer.Channel, data: ByteArray) {
                val frame = parseFrame(data)
                if (frame == null) {
                    log("rfcomm: dropped a malformed frame (${data.size} bytes)")
                    return
                }
                val replies = ArrayList<ByteArray>()
                val opened = ArrayList<RfcommDlc>()
                synchronized(session.lock) {
                    if (session.closed) return
                    if (frame.dlci == 0) {
                        session.onControlFrame(frame, replies)
                    } else {
                        session.onDlcFrame(frame, replies, opened)
                    }
                }
                replies.forEach { channel.write(it) }
                opened.forEach { onOpen(it) }
            }
        }
    }

    companion object {
        /**
         * 解析一帧（上游 WNafUtil 合并段还原）：
         * addr EA=1、control 屏蔽 PF=0x10 后必须是 5 种已知类型、FCS 校验通过。
         * UIH 且 PF 且非 DLCI 0 时 info 前多一个 credits 字节。
         */
        fun parseFrame(data: ByteArray): RfcommFrames.Frame? {
            if (data.size < 4) return null
            val addr = data[0].toInt() and 0xff
            if (addr and 1 == 0) return null
            val control = data[1].toInt() and 0xff
            val type = control and 0xef // 掩去 PF 位
            val pf = control and 0x10 != 0
            val lenByte = data[2].toInt() and 0xff
            var headerBytes = 4 // addr + control + len(EA1) + fcs
            var length: Int
            if (lenByte and 1 != 0) {
                length = lenByte ushr 1
                headerBytes = 3
            } else {
                if (data.size < 5) return null
                length = (lenByte ushr 1) or ((data[3].toInt() and 0xff) shl 7)
            }
            if (RfcommFrames.fcs(if (type == RfcommFrames.TYPE_UIH) 2 else headerBytes, data) !=
                (data[data.size - 1].toInt() and 0xff)
            ) {
                return null
            }
            val dlci = addr ushr 2
            var credits: Int? = null
            var infoOffset = headerBytes
            if (data.size - headerBytes - 1 == length + 1 && type == RfcommFrames.TYPE_UIH &&
                pf && dlci != 0
            ) {
                credits = data[headerBytes].toInt() and 0xff
                infoOffset++
            }
            if (type != RfcommFrames.TYPE_SABM && type != RfcommFrames.TYPE_UA &&
                type != RfcommFrames.TYPE_DM && type != RfcommFrames.TYPE_DISC &&
                type != RfcommFrames.TYPE_UIH
            ) {
                return null
            }
            if (infoOffset + length > data.size - 1) return null
            return RfcommFrames.Frame(
                dlci,
                type,
                credits,
                cr = addr and 2 != 0,
                pf = pf,
                info = data.copyOfRange(infoOffset, infoOffset + length),
            )
        }
    }
}
