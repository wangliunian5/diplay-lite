package com.shilapi.xcertplay.usbbt

import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * vc181（外置 USB 蓝牙 C/D 批）：L2CAP 信令与通道复用。
 * 移植自上游参考实现 usbbt.L2capLayer.java。
 *
 * 关键参数（对照上游，勿"顺手改好看"）：信令通道 CID=1；本地 CID 自 64 起递增
 * （到 65535 回绕）；信令 identifier 自 1 起递增（到 255 回绕 1）；默认对端 MTU=672；
 * 本地+对端双向 configured 齐了才回调 service.open。
 *
 * 上游通过 host 闭包取 connections/send 并回写 setPeer；此处改为构造注入：
 * [send] = host 的 ACL 发送；[observer] = host 兴趣点（对端用了哪个 PSM）。
 *
 * 与上游的偏差（反编译常量复用疑似错位，按标准 L2CAP 布局落，见交付说明）：
 * - DISCONNECT_RESPONSE：通道按 destCid@0 查（上游反编译为 @2）。
 * （vc218 修正：CONFIG_RESPONSE 恢复按 DCID@0 查通道 —— @2 是 SCID（对端 CID），
 * 按它查永远 miss，曾导致 config 应答被丢、SDP 静默；此前误标为"上游错位"。）
 */
internal class L2capLayer(
    private val services: Map<Int, L2capService>,
    private val send: (handle: Int, payload: ByteArray) -> Unit,
    private val observer: Observer,
) {
    /** host 侧兴趣点（上游直接摸 UsbBtHost 字段，此处收敛为显式回调）。 */
    interface Observer {
        /** 对端在该 handle 链路上发起/使用了某 PSM（incoming request 或 SDP 通道数据）。 */
        fun onPeerService(handle: Int, addr: BdAddr?, psm: Int, supported: Boolean)
    }

    private val channels = HashMap<Pair<Int, Int>, Channel>()
    private val outgoing = HashMap<Pair<Int, Int>, Outgoing>()
    private var nextCid = 64
    private var nextIdentifier = 1

    private fun key(handle: Int, cid: Int): Pair<Int, Int> = handle to cid

    inner class Channel internal constructor(
        val handle: Int,
        val psm: Int,
        val localCid: Int,
        val remoteCid: Int,
    ) {
        var remoteMtu = 672
        var localConfigured = false
        var remoteConfigured = false
        /** vc219：config 协商重试次数（对端回 unacceptable/unknown-options 时按 hint 重发）。 */
        var configRetries = 0
        var service: L2capService? = null
        var handler: L2capChannelHandler? = null

        /** 向对端发 DISCONNECT（code 6）。 */
        fun close() {
            sendSignal(
                handle,
                HciCommands.le16(remoteCid) + HciCommands.le16(localCid),
                SIGNAL_DISCONNECT_REQUEST,
            )
        }

        /** 发 SDU（对端 MTU 限制；外层再包 L2CAP 帧头 + ACL）。 */
        fun write(payload: ByteArray) {
            if (payload.size > remoteMtu) {
                throw IllegalArgumentException("SDU larger than the peer's MTU")
            }
            send(
                handle,
                HciCommands.le16(payload.size) + HciCommands.le16(remoteCid) + payload,
            )
        }
    }

    private inner class Outgoing internal constructor(
        val handle: Int,
        val psm: Int,
        val service: L2capService?,
        val onFailed: (Int) -> Unit,
    )

    private fun allocateCid(handle: Int): Int? {
        for (i in 0 until 65471) {
            nextCid = if (nextCid >= 65535) 64 else nextCid + 1
            val k = key(handle, nextCid)
            if (!channels.containsKey(k) && !outgoing.containsKey(k)) return nextCid
        }
        return null
    }

    /**
     * ACL 链路断开：关闭该 handle 上全部通道（回调 handler.onClosed）并清表。
     * 调用方须持 host 的 l2capLock（与 onFrame/connect 同一把）。
     */
    fun onLinkDisconnected(handle: Int) {
        val dead = channels.values.filter { it.handle == handle }
        channels.keys.removeAll { it.first == handle }
        outgoing.keys.removeAll { it.first == handle }
        dead.forEach { it.handler?.onClosed(it) }
    }

    /** 主动发起 L2CAP 通道（发 CONNECTION_REQUEST）。无 CID 可用时 onFailed(4)。 */
    fun connect(handle: Int, psm: Int, service: L2capService?, onFailed: (Int) -> Unit) {
        val cid = allocateCid(handle)
        if (cid == null) {
            onFailed(4)
            return
        }
        outgoing[key(handle, cid)] = Outgoing(handle, psm, service, onFailed)
        // vc212：SDP 静默取证——connect 请求/应答打点，定位「正在查询 iAP2 服务…」后无下文卡在哪段
        Log.i(UsbBtSession.TAG, "l2cap: connect req handle=0x%03x psm=0x%02x cid=%d".format(handle, psm, cid))
        sendSignal(handle, HciCommands.le16(psm) + HciCommands.le16(cid), SIGNAL_CONNECTION_REQUEST)
    }

    private fun maybeOpened(channel: Channel) {
        if (channel.localConfigured && channel.remoteConfigured && channel.handler == null) {
            val service = channel.service ?: services[channel.psm]
            val handler = service?.open(channel)
            channel.handler = handler
            if (handler == null) {
                Log.i(UsbBtSession.TAG, "l2cap channel psm=0x%02x refused".format(channel.psm))
                channel.close()
            }
        }
    }

    /** 收到一条重组完的 L2CAP 帧（含 4 字节头）。 */
    fun onFrame(handle: Int, frame: ByteArray) {
        if (frame.size < 4) return
        val payloadLen = le16(frame, 0)
        val destCid = le16(frame, 2)
        val total = payloadLen + 4
        if (frame.size < total) return
        val payload = frame.copyOfRange(4, total)
        if (destCid != SIGNAL_CID) {
            // 数据通道：交给已开通道的 handler
            channels[key(handle, destCid)]?.let { channel ->
                channel.handler?.onData(channel, payload)
            }
            return
        }
        // 信令通道：可含多条信令，逐条解析
        var off = 0
        while (off + 4 <= payload.size) {
            val code = payload[off].toInt() and 0xff
            val id = payload[off + 1].toInt() and 0xff
            val sigLen = le16(payload, off + 2)
            val sigEnd = off + 4 + sigLen
            if (sigEnd > payload.size) {
                reject(handle, id)
                return
            }
            val data = payload.copyOfRange(off + 4, sigEnd)
            onSignal(handle, code, id, data)
            off = sigEnd
        }
    }

    private fun onSignal(handle: Int, code: Int, id: Int, data: ByteArray) {
        when (code) {
            SIGNAL_CONNECTION_REQUEST -> {
                if (data.size < 4) {
                    reject(handle, id)
                    return
                }
                val psm = le16(data, 0)
                val scid = le16(data, 2)
                val service = services[psm]
                observer.onPeerService(handle, null, psm, service != null)
                if (service == null) {
                    // result 0x0002：PSM 不支持
                    reply(handle, SIGNAL_CONNECTION_RESPONSE, HciCommands.le16(0) + HciCommands.le16(scid) + HciCommands.le16(2) + HciCommands.le16(0), id)
                } else if (scid < 64 || channels.values.any { it.handle == handle && it.remoteCid == scid }) {
                    // result 0x0006：非法/重复 SCID
                    reply(handle, SIGNAL_CONNECTION_RESPONSE, HciCommands.le16(0) + HciCommands.le16(scid) + HciCommands.le16(6) + HciCommands.le16(0), id)
                } else {
                    val cid = allocateCid(handle)
                    if (cid == null) {
                        // result 0x0004：无资源
                        reply(handle, SIGNAL_CONNECTION_RESPONSE, HciCommands.le16(0) + HciCommands.le16(scid) + HciCommands.le16(4) + HciCommands.le16(0), id)
                    } else {
                        channels[key(handle, cid)] = Channel(handle, psm, cid, scid)
                        reply(handle, SIGNAL_CONNECTION_RESPONSE, HciCommands.le16(cid) + HciCommands.le16(scid) + HciCommands.le16(0) + HciCommands.le16(0), id)
                        // 随即发无参 CONFIG_REQUEST（flags=0）
                        sendSignal(handle, HciCommands.le16(scid) + HciCommands.le16(0), SIGNAL_CONFIG_REQUEST)
                    }
                }
            }
            SIGNAL_CONNECTION_RESPONSE -> {
                if (data.size < 8) return
                val destCid = le16(data, 0)
                val scid = le16(data, 2)
                val result = le16(data, 4)
                val k = key(handle, scid)
                val pending = outgoing[k] ?: return
                // vc212：SDP 静默取证
                Log.i(UsbBtSession.TAG, "l2cap: connect rsp psm=0x%02x result=%d".format(pending.psm, result))
                when (result) {
                    0 -> {
                        outgoing.remove(k)
                        val channel = Channel(handle, pending.psm, scid, destCid)
                        channel.service = pending.service
                        channels[k] = channel
                        // vc219：CONFIG_REQUEST 显式携带 MTU=672 选项（实测 iPhone 对空选项
                        // config 回非零 result；显式 MTU 是常规做法且与默认 MTU 一致，无副作用）
                        sendSignal(handle, HciCommands.le16(destCid) + HciCommands.le16(0) + MTU_OPTION_672, SIGNAL_CONFIG_REQUEST)
                    }
                    1 -> Unit // pending：等对端下一条 response
                    else -> {
                        outgoing.remove(k)
                        pending.onFailed(result)
                    }
                }
            }
            SIGNAL_CONFIG_REQUEST -> {
                if (data.size < 4) {
                    reject(handle, id)
                    return
                }
                val destCid = le16(data, 0)
                val flags = le16(data, 2)
                val options = data.copyOfRange(4, data.size)
                val channel = channels[key(handle, destCid)]
                if (channel == null) {
                    // COMMAND_REJECT(code 1) reason 2 = 非法 CID；上游同款字节布局
                    reply(handle, SIGNAL_COMMAND_REJECT, HciCommands.le16(2) + HciCommands.le16(destCid) + HciCommands.le16(0), id)
                    return
                }
                val echoed = ByteArrayOutputStream()
                var unknownHint = false
                var off = 0
                while (off + 2 <= options.size) {
                    val type = options[off].toInt()
                    val len = options[off + 1].toInt() and 0xff
                    if (off + 2 + len > options.size) break
                    when (type.toInt() and 0x7f) {
                        1 -> if (len >= 2) {
                            channel.remoteMtu = maxOf(48, le16(options, off + 2))
                        }
                        4 -> if (len >= 1 && options[off + 2].toInt() != 0) {
                            unknownHint = true
                        }
                        else -> if (type and 0x80 == 0) {
                            echoed.write(options, off, len + 2)
                        }
                    }
                    off += len + 2
                }
                when {
                    echoed.size() > 0 -> {
                        // result 3：未知选项回显
                        reply(
                            handle, SIGNAL_CONFIG_RESPONSE,
                            HciCommands.le16(channel.remoteCid) + HciCommands.le16(0) + HciCommands.le16(3) + echoed.toByteArray(),
                            id,
                        )
                    }
                    unknownHint -> {
                        // result 1 + flush-timeout fallback（上游字节布局照搬）
                        reply(
                            handle, SIGNAL_CONFIG_RESPONSE,
                            HciCommands.le16(channel.remoteCid) + HciCommands.le16(0) + HciCommands.le16(1) +
                                byteArrayOf(4, 9) + ByteArray(9),
                            id,
                        )
                    }
                    else -> {
                        val continuation = flags and 1
                        reply(
                            handle, SIGNAL_CONFIG_RESPONSE,
                            HciCommands.le16(channel.remoteCid) + HciCommands.le16(continuation) + HciCommands.le16(0),
                            id,
                        )
                        if (continuation == 0) {
                            channel.remoteConfigured = true
                            maybeOpened(channel)
                        }
                    }
                }
            }
            SIGNAL_CONFIG_RESPONSE -> {
                if (data.size < 8) return
                // 标准 L2CAP CONFIG_RESPONSE：DCID@0 / SCID@2 / Flags@4 / Result@6。
                // vc218 修复：DCID@0 才是「收到此应答的本机端点」= 我们的 localCid，channels 按它查。
                // 之前按 @2（SCID=对端 CID）查 → 永远 miss → localConfigured 永不置位 →
                // maybeOpened 不触发 → SDP 查询发不出去 → iPhone 等 25s 断开（实测 0x13）。
                val destCid = le16(data, 0)
                var result = le16(data, 6)
                var optionsOff = 8
                // vc220：实测 iPhone 侧 CONFIG_RESPONSE 无 Flags 字段（raw=4100000000000102a002：
                // DCID=65 + SCID=0 + @4 Result=0x0000 + @6 起 MTU echo）。标准解析 @6 会得到
                // 0x0201 非法值 → 回落无-Flags 布局按 @4 重解。
                if (result > 5 && data.size >= 6) {
                    val alt = le16(data, 4)
                    if (alt <= 5) {
                        Log.i(UsbBtSession.TAG, "l2cap: config rsp 无 Flags 布局，按 @4 重解 result=$alt")
                        result = alt
                        optionsOff = 6
                    }
                }
                channels[key(handle, destCid)]?.let { channel ->
                    // vc219：原始 payload 打点（result 解析是否错位的最后证据）
                    Log.i(UsbBtSession.TAG, "l2cap: config rsp cid=%d result=%d raw=%s".format(destCid, result, data.toHex()))
                    when {
                        result == 0 -> {
                            channel.localConfigured = true
                            maybeOpened(channel)
                        }
                        (result == 1 || result == 3) && channel.configRetries < 2 -> {
                            // unacceptable/unknown options：对端在 options 段给了可接受值 hint，
                            // 按规范采纳 hint 重发 CONFIG_REQUEST（最多 2 次，防死循环）
                            channel.configRetries++
                            val hints = if (data.size > optionsOff) data.copyOfRange(optionsOff, data.size) else MTU_OPTION_672
                            Log.i(UsbBtSession.TAG, "l2cap: config rsp result=$result，采纳 hint 重发（第 ${channel.configRetries} 次）")
                            sendSignal(handle, HciCommands.le16(channel.remoteCid) + HciCommands.le16(0) + hints, SIGNAL_CONFIG_REQUEST)
                        }
                        else -> channel.close()
                    }
                }
            }
            SIGNAL_DISCONNECT_REQUEST -> {
                if (data.size < 4) {
                    reject(handle, id)
                    return
                }
                val destCid = le16(data, 0)
                val sourceCid = le16(data, 2)
                val channel = channels[key(handle, destCid)]
                if (channel == null || channel.remoteCid != sourceCid) {
                    // COMMAND_REJECT(code 1) reason 2 = 非法 CID；上游同款字节布局
                    reply(handle, SIGNAL_COMMAND_REJECT, HciCommands.le16(2) + HciCommands.le16(destCid) + HciCommands.le16(sourceCid), id)
                } else {
                    reply(handle, SIGNAL_DISCONNECT_RESPONSE, HciCommands.le16(destCid) + HciCommands.le16(sourceCid), id)
                    channels.remove(key(handle, destCid))?.handler?.let { it.onClosed(channel) }
                }
            }
            SIGNAL_DISCONNECT_RESPONSE -> {
                if (data.size < 4) return
                // 标准 L2CAP：Dest CID@0（= 本地）；上游反编译取 @2 疑似错位
                val destCid = le16(data, 0)
                channels.remove(key(handle, destCid))?.let { removed ->
                    removed.handler?.onClosed(removed)
                }
            }
            SIGNAL_ECHO_REQUEST -> reply(handle, SIGNAL_ECHO_RESPONSE, data, id)
            SIGNAL_INFORMATION_REQUEST -> {
                if (data.size < 2) {
                    reject(handle, id)
                    return
                }
                val type = le16(data, 0)
                val body = when (type) {
                    1 -> HciCommands.le16(type) + HciCommands.le16(0) + HciCommands.le16(672)
                    2 -> HciCommands.le16(type) + HciCommands.le16(0) + le32(128)
                    3 -> HciCommands.le16(type) + HciCommands.le16(0) + le32(2) + le32(0)
                    else -> HciCommands.le16(type) + HciCommands.le16(1)
                }
                reply(handle, SIGNAL_INFORMATION_RESPONSE, body, id)
            }
            SIGNAL_ECHO_RESPONSE, SIGNAL_INFORMATION_RESPONSE -> Unit // 主动查询未用，忽略
            else -> reject(handle, id)
        }
    }

    /** 信令应答（identifier 回抄对端）。 */
    private fun reply(handle: Int, code: Int, payload: ByteArray, id: Int) {
        val signal = byteArrayOf(code.toByte(), id.toByte()) + HciCommands.le16(payload.size) + payload
        send(handle, HciCommands.le16(signal.size) + HciCommands.le16(SIGNAL_CID) + signal)
    }

    /** 主动信令（identifier 自增 1..255 回绕）。 */
    private fun sendSignal(handle: Int, payload: ByteArray, code: Int) {
        val id = nextIdentifier
        nextIdentifier = if (id >= 255) 1 else id + 1
        val signal = byteArrayOf(code.toByte(), id.toByte()) + HciCommands.le16(payload.size) + payload
        send(handle, HciCommands.le16(signal.size) + HciCommands.le16(SIGNAL_CID) + signal)
    }

    private fun reject(handle: Int, id: Int) {
        reply(handle, SIGNAL_COMMAND_REJECT, HciCommands.le16(0), id)
    }

    /** vc219：config 取证用 hex dump。 */
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun le16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)

    private fun le32(v: Int): ByteArray =
        byteArrayOf(
            (v and 0xff).toByte(),
            ((v ushr 8) and 0xff).toByte(),
            ((v ushr 16) and 0xff).toByte(),
            ((v ushr 24) and 0xff).toByte(),
        )

    companion object {
        private const val SIGNAL_CID = 1
        private const val SIGNAL_COMMAND_REJECT = 1
        private const val SIGNAL_CONNECTION_REQUEST = 2
        private const val SIGNAL_CONNECTION_RESPONSE = 3
        private const val SIGNAL_CONFIG_REQUEST = 4
        private const val SIGNAL_CONFIG_RESPONSE = 5
        private const val SIGNAL_DISCONNECT_REQUEST = 6
        private const val SIGNAL_DISCONNECT_RESPONSE = 7
        private const val SIGNAL_ECHO_REQUEST = 8
        private const val SIGNAL_ECHO_RESPONSE = 9
        private const val SIGNAL_INFORMATION_REQUEST = 10
        private const val SIGNAL_INFORMATION_RESPONSE = 11

        /** vc219：MTU 配置选项（type=1, len=2, value=672/0x02A0），CONFIG_REQUEST 显式携带。 */
        private val MTU_OPTION_672 = byteArrayOf(1, 2, 0xA0.toByte(), 0x02)
    }
}
