package com.shilapi.xcertplay.usbbt

/**
 * vc181（外置 USB 蓝牙 C/D 批）：RFCOMM 会话（一条 L2CAP 通道上的多路复用）。
 * 移植自上游参考实现 usbbt.RfcommSession.java。
 *
 * 控制帧（DLCI 0）处理 UA/SABM-应答/DISC/DM/UIH(MCC)；MCC 覆盖
 * PN(0x20)/MSC(0x38)/RPN(0x24)/Test(0x08)/FCon/FCoff/NSC(0x04)。
 * 所有状态在 [lock] 下变更；协议帧经 [transmit]（L2CAP write）发出。
 */
internal class RfcommSession(
    val initiator: Boolean,
    private val serverChannels: Set<Int>,
    private val remoteL2capMtu: () -> Int,
    val transmit: (ByteArray) -> Unit,
    val onOpen: (RfcommDlc) -> Unit,
    private val log: (String) -> Unit,
) {
    val lock = Object()
    val dlcs = HashMap<Int, RfcommDlc>()
    var sessionOpen = false
    var closed = false
    var opening: RfcommDlc? = null
    var targetDlci: Int? = null
    private val negotiated = HashMap<Int, RfcommFrames.Pn>()

    /** 单帧最大 info 长度：min(672, L2CAP 对端 MTU) - 6，下限 23。 */
    fun maxFrameSize(): Int = maxOf(minOf(672, remoteL2capMtu()) - 6, 23)

    /** L2CAP 通道已开：initiator 时立即对 DLCI 0 发 SABM 建控制通道。 */
    fun start(serverChannel: Int) {
        synchronized(lock) {
            targetDlci = (serverChannel and 31) shl 1
            transmit(RfcommFrames.build(0, RfcommFrames.TYPE_SABM, null, initiator, true, ByteArray(0)))
        }
        log("rfcomm: opening the peer's server channel $serverChannel")
    }

    /** 收到 DLCI 0 控制帧（调用方持 session.lock）。 */
    fun onControlFrame(frame: RfcommFrames.Frame, replies: ArrayList<ByteArray>) {
        when (frame.type) {
            RfcommFrames.TYPE_DM -> {
                sessionOpen = false
                log("rfcomm: peer answered DM on DLCI 0")
            }
            RfcommFrames.TYPE_SABM -> {
                sessionOpen = true
                log("rfcomm: session opened by the peer")
                replies.add(RfcommFrames.build(0, RfcommFrames.TYPE_UA, null, !initiator, true, ByteArray(0)))
            }
            RfcommFrames.TYPE_DISC -> {
                replies.add(RfcommFrames.build(0, RfcommFrames.TYPE_UA, null, !initiator, true, ByteArray(0)))
                log("rfcomm: session closed by the peer")
                sessionOpen = false
                val all = dlcs.values.toList()
                dlcs.clear()
                all.forEach { it.endedLocked("session closed") }
            }
            RfcommFrames.TYPE_UA -> {
                sessionOpen = true
                val target = targetDlci ?: return
                if (opening == null && dlcs[target] == null) {
                    // UA 后发起 PN 协商（credit-based，帧长我方上限）
                    val max = maxFrameSize()
                    replies.add(
                        RfcommFrames.build(
                            0, RfcommFrames.TYPE_UIH, null, initiator, false,
                            RfcommFrames.mcc(
                                MCC_PN,
                                true,
                                byteArrayOf(
                                    (target and 63).toByte(),
                                    0xf0.toByte(), // convergence=15（要 credit）<<4 | reserved
                                    7.toByte(), // priority
                                    0, // ack timer
                                    (max and 0xff).toByte(),
                                    ((max ushr 8) and 0xff).toByte(),
                                    0, // max retries
                                    7.toByte(), // initial credits
                                ),
                            ),
                        ),
                    )
                }
            }
            RfcommFrames.TYPE_UIH -> onMccUiH(frame.info, replies)
        }
    }

    /** UIH 控制帧：拆 MCC 列表逐条处理。 */
    private fun onMccUiH(info: ByteArray, replies: ArrayList<ByteArray>) {
        val commands = ArrayList<RfcommFrames.Mcc>()
        var off = 0
        while (off + 2 <= info.size) {
            val header = info[off].toInt() and 0xff
            if (header and 1 == 0) break
            val type = header ushr 2
            val command = header and 2 != 0
            // 变长（EA）读长度
            var len = 0
            var shift = 0
            var cursor = off + 1
            var ok = false
            while (cursor < info.size) {
                val b = info[cursor].toInt() and 0xff
                len = len or ((b ushr 1) shl shift)
                cursor++
                if (b and 1 != 0) {
                    ok = true
                    break
                }
                shift += 7
                if (shift > 14) break
            }
            if (!ok) break
            off = cursor + len
            if (off > info.size) break
            commands.add(RfcommFrames.Mcc(type, command, info.copyOfRange(cursor, off)))
        }
        for (cmd in commands) {
            val value = cmd.value
            if (cmd.command) {
                when (cmd.type) {
                    MCC_PN -> {
                        val pn = pnOf(value)
                        if (pn != null) {
                            val max = maxFrameSize()
                            val negotiatedFrame = minOf(if (pn.frameSize > 0) pn.frameSize else max, max)
                            val creditBased = pn.convergence == 15
                            negotiated[pn.dlci] = RfcommFrames.Pn(
                                pn.dlci, pn.convergence, pn.priority, negotiatedFrame, pn.credits,
                            )
                            log(
                                "rfcomm: PN dlci=${pn.dlci} frame=${pn.frameSize}->$negotiatedFrame " +
                                    "credits=${if (creditBased) "on (peer gives ${pn.credits})" else "off"}",
                            )
                            // 回应答帧：credit-based 时 convergence=14、credits=7
                            replies.add(
                                RfcommFrames.build(
                                    0, RfcommFrames.TYPE_UIH, null, initiator, false,
                                    RfcommFrames.mcc(
                                        MCC_PN,
                                        true,
                                        byteArrayOf(
                                            (pn.dlci and 63).toByte(),
                                            (((if (creditBased) 14 else 0) and 15) shl 4).toByte(),
                                            (pn.priority and 63).toByte(),
                                            0,
                                            (negotiatedFrame and 0xff).toByte(),
                                            ((negotiatedFrame ushr 8) and 0xff).toByte(),
                                            0,
                                            (if (creditBased) 7 else 0).toByte(),
                                        ),
                                    ),
                                ),
                            )
                        }
                    }
                    MCC_MSC -> {
                        replies.add(RfcommFrames.build(0, RfcommFrames.TYPE_UIH, null, initiator, false, RfcommFrames.mcc(MCC_MSC, true, value)))
                        if (value.size >= 2) {
                            val dlci = (value[0].toInt() and 0xff) ushr 2
                            val dlc = dlcs[dlci]
                            if (dlc != null) {
                                dlc.peerFlowStopped = value[1].toInt() and 2 != 0
                                synchronized(lock) { lock.notifyAll() }
                                if (!dlc.mscSent) {
                                    dlc.mscSent = true
                                    replies.add(
                                        RfcommFrames.build(
                                            0, RfcommFrames.TYPE_UIH, null, initiator, false,
                                            RfcommFrames.mcc(MCC_MSC, true, byteArrayOf(((dlc.dlci shl 2) or 3).toByte(), 0x8d.toByte())),
                                        ),
                                    )
                                }
                            }
                        }
                    }
                    MCC_RPN -> {
                        // 远端端口协商：原样回（参数不足时用上游同款默认八字节）
                        val reply = if (value.size < 8) {
                            val first = value.firstOrNull()?.toByte() ?: 3.toByte()
                            byteArrayOf(first, 3, 3, 0, 17, 19, 127, 63)
                        } else {
                            value
                        }
                        replies.add(RfcommFrames.build(0, RfcommFrames.TYPE_UIH, null, initiator, false, RfcommFrames.mcc(MCC_RPN, true, reply)))
                    }
                    MCC_TEST -> {
                        // 上游对 Test 原样回显
                        replies.add(RfcommFrames.build(0, RfcommFrames.TYPE_UIH, null, initiator, false, RfcommFrames.mcc(MCC_TEST, true, value)))
                    }
                    MCC_FCON, MCC_FCOFF -> {
                        val stopped = cmd.type == MCC_FCOFF
                        dlcs.values.forEach {
                            it.aggregateFlowStopped = stopped
                        }
                        synchronized(lock) { lock.notifyAll() }
                        replies.add(RfcommFrames.build(0, RfcommFrames.TYPE_UIH, null, initiator, false, RfcommFrames.mcc(cmd.type, true, ByteArray(0))))
                    }
                    MCC_RLS -> replies.add(RfcommFrames.build(0, RfcommFrames.TYPE_UIH, null, initiator, false, RfcommFrames.mcc(MCC_RLS, true, value)))
                    else -> replies.add(nscReply(cmd.type))
                }
            } else {
                when (cmd.type) {
                    MCC_NSC -> {
                        val unsupported = value.firstOrNull()?.toInt()?.and(0xff) ?: 0
                        log("rfcomm: peer does not support command 0x%02x".format(unsupported))
                        if (initiator && (unsupported ushr 2) == MCC_PN && targetDlci != null) {
                            // 对端不认 PN：退回直接 SABM 建通道（无 credit 流控）
                            sendSabmLocked(targetDlci!!, null, replies)
                        }
                    }
                    MCC_PN -> {
                        val pn = pnOf(value)
                        if (pn != null) {
                            log(
                                "rfcomm: PN answer dlci=${pn.dlci} frame=${pn.frameSize} " +
                                    "credits=${if (pn.convergence == 14) "on (${pn.credits})" else "off"}",
                            )
                            if (initiator && pn.dlci == targetDlci) {
                                sendSabmLocked(pn.dlci, pn, replies)
                            } else {
                                dlcs[pn.dlci]?.applyPnLocked(pn, maxFrameSize(), false)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun nscReply(unsupportedType: Int): ByteArray =
        RfcommFrames.build(
            0, RfcommFrames.TYPE_UIH, null, initiator, false,
            RfcommFrames.mcc(MCC_NSC, true, byteArrayOf(((unsupportedType shl 2) or 3).toByte())),
        )

    private fun pnOf(value: ByteArray): RfcommFrames.Pn? {
        if (value.size < 8) return null
        return RfcommFrames.Pn(
            value[0].toInt() and 63,
            (value[1].toInt() and 0xf0) ushr 4,
            value[2].toInt() and 63,
            (value[4].toInt() and 0xff) or ((value[5].toInt() and 0xff) shl 8),
            value[7].toInt() and 7,
        )
    }

    /** 收到非 0 DLCI 帧。replies=回帧；opened=本帧新建成的 DLC（回调 host）。 */
    fun onDlcFrame(
        frame: RfcommFrames.Frame,
        replies: ArrayList<ByteArray>,
        opened: ArrayList<RfcommDlc>,
    ) {
        val dlci = frame.dlci
        when (frame.type) {
            RfcommFrames.TYPE_DM -> {
                val pending = opening
                if (pending != null && pending.dlci == dlci) {
                    opening = null
                    log("rfcomm: the peer refused DLCI $dlci (DM)")
                    pending.endedLocked("refused by the peer")
                } else {
                    dlcs.remove(dlci)?.endedLocked("peer answered DM")
                }
            }
            RfcommFrames.TYPE_SABM -> {
                // 对端主动开我们的 server channel（本实现为纯客户端，一律拒）
                val serverChannel = dlci ushr 1
                if (!sessionOpen || !serverChannels.contains(serverChannel)) {
                    log("rfcomm: refused DLCI $dlci (server channel $serverChannel)")
                    replies.add(RfcommFrames.build(dlci, RfcommFrames.TYPE_DM, null, !initiator, true, ByteArray(0)))
                    return
                }
                replies.add(RfcommFrames.build(dlci, RfcommFrames.TYPE_UA, null, !initiator, true, ByteArray(0)))
                if (dlcs[dlci] != null) return
                val dlc = RfcommDlc(this, dlci)
                negotiated.remove(dlci)?.let { dlc.applyPnLocked(it, maxFrameSize(), true) }
                    ?: run { dlc.frameSize = minOf(RfcommDlc.DEFAULT_FRAME_SIZE, maxFrameSize()) }
                dlcs[dlci] = dlc
                log("rfcomm: DLCI $dlci open (server channel $serverChannel) frame=${dlc.frameSize} credits=${dlc.creditBased}")
                opened.add(dlc)
            }
            RfcommFrames.TYPE_DISC -> {
                val dlc = dlcs[dlci]
                if (dlc == null) {
                    replies.add(RfcommFrames.build(dlci, RfcommFrames.TYPE_DM, null, !initiator, true, ByteArray(0)))
                    return
                }
                replies.add(RfcommFrames.build(dlci, RfcommFrames.TYPE_UA, null, !initiator, true, ByteArray(0)))
                dlcs.remove(dlci)
                log("rfcomm: DLCI $dlci closed by the peer")
                dlc.endedLocked("closed by the peer")
            }
            RfcommFrames.TYPE_UA -> {
                val pending = opening
                if (pending != null && pending.dlci == dlci) {
                    opening = null
                    dlcs[dlci] = pending
                    pending.mscSent = true
                    replies.add(
                        RfcommFrames.build(
                            0, RfcommFrames.TYPE_UIH, null, initiator, false,
                            RfcommFrames.mcc(MCC_MSC, true, byteArrayOf(((dlci shl 2) or 3).toByte(), 0x8d.toByte())),
                        ),
                    )
                    log("rfcomm: DLCI $dlci open (peer's server channel ${dlci ushr 1}) frame=${pending.frameSize} credits=${pending.creditBased}")
                    opened.add(pending)
                } else {
                    val dlc = dlcs[dlci]
                    if (dlc != null && dlc.closing) {
                        dlcs.remove(dlci)
                        dlc.endedLocked("closed")
                    }
                }
            }
            RfcommFrames.TYPE_UIH -> {
                val dlc = dlcs[dlci] ?: return
                if (dlc.creditBased && frame.credits != null) {
                    dlc.txCredits += frame.credits
                    synchronized(lock) { lock.notifyAll() }
                }
                val info = frame.info
                if (info.isEmpty()) return
                if (!dlc.ended) {
                    dlc.pending.addLast(info)
                    dlc.pendingBytes += info.size
                    if (dlc.creditBased) {
                        dlc.rxCredits = maxOf(dlc.rxCredits - 1, 0)
                    }
                    synchronized(lock) { lock.notifyAll() }
                    dlc.creditTopUpLocked()?.let { replies.add(it) }
                }
            }
        }
    }

    /** 发 SABM 建 DLC（PN 应答后或 PN 被拒的兜底）。 */
    fun sendSabmLocked(dlci: Int, pn: RfcommFrames.Pn?, replies: ArrayList<ByteArray>) {
        if (opening == null && dlcs[dlci] == null) {
            val dlc = RfcommDlc(this, dlci)
            if (pn != null) {
                dlc.applyPnLocked(pn, maxFrameSize(), false)
            } else {
                dlc.frameSize = minOf(RfcommDlc.DEFAULT_FRAME_SIZE, maxFrameSize())
            }
            opening = dlc
            replies.add(RfcommFrames.build(dlci, RfcommFrames.TYPE_SABM, null, initiator, true, ByteArray(0)))
        }
    }

    /** L2CAP 通道关闭：全会话拆除（调用方持锁外触发 ended 回调）。 */
    fun onL2capClosed() {
        val all: List<RfcommDlc>
        synchronized(lock) {
            closed = true
            sessionOpen = false
            opening?.endedLocked("l2cap closed")
            opening = null
            all = dlcs.values.toList()
            dlcs.clear()
        }
        all.forEach { it.endedLocked("l2cap closed") }
    }

    companion object {
        private const val MCC_NSC = 4 // 0x04
        private const val MCC_TEST = 8 // 0x08
        private const val MCC_FCOFF = 24 // 上游字节行为（0x18 口径）
        private const val MCC_RPN = 36 // 0x24
        private const val MCC_RLS = 20 // 0x14
        private const val MCC_PN = 32 // 0x20
        private const val MCC_MSC = 56 // 0x38
        private const val MCC_FCON = 40 // 0x28
    }
}
