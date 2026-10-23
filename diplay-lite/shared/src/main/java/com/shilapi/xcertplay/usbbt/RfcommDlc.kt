package com.shilapi.xcertplay.usbbt

import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.IOException
import java.util.ArrayDeque

/**
 * vc181（外置 USB 蓝牙 C/D 批）：RFCOMM 数据链路通道（DLC）。
 * 移植自上游参考实现 usbbt.RfcommDlc.java（本身即 BlockingDuplexByteStream）。
 *
 * 语义对齐 BluetoothRfcommDuplexStream：recv 超时 null、对端结束空数组、
 * send 阻塞等 credit/流控、close 幂等；切分保留（一次最多取 maxBytes）。
 * 所有状态在 session.lock 下变更；credit-based 流控收发双向（rx 起 7、tx 来自对端）。
 */
internal class RfcommDlc internal constructor(
    val session: RfcommSession,
    val dlci: Int,
) : BlockingDuplexByteStream {
    var frameSize = DEFAULT_FRAME_SIZE
    var creditBased = false
    var txCredits = 0
    var rxCredits = 0
    var peerFlowStopped = false
    var aggregateFlowStopped = false
    var mscSent = false
    var closing = false
    var ended = false
    var endReason: String? = null
    val pending = ArrayDeque<ByteArray>()
    var pendingBytes = 0
    private val sendLock = Object()

    /** PN 协商落地（incoming=true 表示对端主动开通道）。 */
    fun applyPnLocked(pn: RfcommFrames.Pn, maxFrameSize: Int, incoming: Boolean) {
        frameSize = minOf(if (pn.frameSize > 0) pn.frameSize else DEFAULT_FRAME_SIZE, maxFrameSize)
        creditBased = if (incoming) pn.convergence == 15 else pn.convergence == 14
        if (creditBased) {
            txCredits = pn.credits
            rxCredits = 7
        }
    }

    fun isOpen(): Boolean = synchronized(session.lock) { !ended && !closing }

    fun getMaxFrameSize(): Int = synchronized(session.lock) { frameSize }

    fun getTransmitCredits(): Int = synchronized(session.lock) { txCredits }

    fun isCreditBased(): Boolean = synchronized(session.lock) { creditBased }

    /** 收帧后的 credit 补充帧（欠账 >3 且缓冲 <64KB 时补到 7）；无则 null。 */
    fun creditTopUpLocked(): ByteArray? {
        val rx = rxCredits
        if (!creditBased || ended || closing || rx > 3 || pendingBytes >= 65536) return null
        val topUp = 7 - rx
        if (topUp <= 0) return null
        rxCredits = rx + topUp
        return RfcommFrames.build(dlci, RfcommFrames.TYPE_UIH, topUp, session.initiator, false, ByteArray(0))
    }

    fun endedLocked(reason: String) {
        if (ended) return
        ended = true
        endReason = reason
        synchronized(session.lock) { session.lock.notifyAll() }
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val deadline = timeoutMillis * 1_000_000 + System.nanoTime()
        synchronized(session.lock) {
            while (pending.isEmpty() && !ended && !closing) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return null
                try {
                    val waitMillis = remaining / 1_000_000
                    session.lock.wait(if (waitMillis < 1) 1 else waitMillis)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
            if (pending.isEmpty()) return EMPTY
            var chunk = pending.removeFirst()
            if (chunk.size > maxBytes) {
                pending.addFirst(chunk.copyOfRange(maxBytes, chunk.size))
                chunk = chunk.copyOf(maxBytes)
            }
            pendingBytes -= chunk.size
            creditTopUpLocked()?.let { session.transmit(it) }
            return chunk
        }
    }

    override fun send(data: ByteArray) {
        synchronized(sendLock) {
            var offset = 0
            while (offset < data.size) {
                val chunk: ByteArray
                synchronized(session.lock) {
                    while (!ended && !closing && !aggregateFlowStopped &&
                        (if (creditBased) txCredits <= 0 else peerFlowStopped)
                    ) {
                        try {
                            session.lock.wait(500L)
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw IOException("interrupted waiting for RFCOMM credits", e)
                        }
                    }
                    if (ended || closing) {
                        throw IOException("RFCOMM channel is closed (${endReason ?: "closed here"})")
                    }
                    val end = minOf(data.size, frameSize + offset)
                    chunk = data.copyOfRange(offset, end)
                    if (creditBased) txCredits--
                    val rx = rxCredits
                    val topUp = if (creditBased && rx <= 3 && pendingBytes < 65536) 7 - rx else 0
                    if (topUp > 0) rxCredits += topUp
                    val frame = RfcommFrames.build(
                        dlci,
                        RfcommFrames.TYPE_UIH,
                        if (topUp > 0) topUp else null,
                        session.initiator,
                        false,
                        chunk,
                    )
                    session.transmit(frame)
                }
                offset += chunk.size
            }
        }
    }

    override fun close() {
        synchronized(session.lock) {
            if (ended || closing) return
            closing = true
            session.lock.notifyAll()
            val frame = if (session.closed || session.dlcs[dlci] == null) {
                null
            } else {
                RfcommFrames.build(dlci, RfcommFrames.TYPE_DISC, null, session.initiator, true, ByteArray(0))
            }
            if (frame == null) {
                ended = true
                session.dlcs.remove(dlci)
            } else {
                try {
                    session.transmit(frame)
                } catch (_: Throwable) {
                }
            }
        }
    }

    companion object {
        private val EMPTY = ByteArray(0)

        /** 默认帧长 127（上游 KNOB_NUDGE 常量，即经典 RFCOMM 默认）。 */
        const val DEFAULT_FRAME_SIZE = 127
    }
}
