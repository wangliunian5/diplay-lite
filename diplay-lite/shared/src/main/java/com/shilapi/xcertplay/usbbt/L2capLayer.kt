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
 * 与上游的两处偏差（反编译常量复用疑似错位，按标准 L2CAP 布局落，见交付说明）：
 * - CONFIG_RESPONSE：通道按 destCid@2 查、result 取 @6（上游反编译为 @0/@4）；
 * - DISCONNECT_RESPONSE：通道按 destCid@0 查（上游反编译为 @2）。
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
                when (result) {
                    0 -> {
                        outgoing.remove(k)
                        val channel = Channel(handle, pending.psm, scid, destCid)
                        channel.service = pending.service
                        channels[k] = channel
                        sendSignal(handle, HciCommands.le16(destCid) + HciCommands.le16(0), SIGNAL_CONFIG_REQUEST)
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
                // 标准 L2CAP：Source CID@0 / Dest CID@2 / Flags@4 / Result@6；按 Dest CID 查本地通道
                val destCid = le16(data, 2)
                val result = le16(data, 6)
                channels[key(handle, destCid)]?.let { channel ->
                    if (result == 0) {
                        channel.localConfigured = true
                        maybeOpened(channel)
                    } else {
                        channel.close()
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
    }
}
