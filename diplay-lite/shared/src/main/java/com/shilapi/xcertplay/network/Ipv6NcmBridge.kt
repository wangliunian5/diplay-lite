package com.shilapi.xcertplay.network

import android.os.ParcelFileDescriptor
import android.util.Log
import com.shilapi.xcertplay.transport.EthernetIpv6Codec
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/**
 * Moves IPv6 packets between an Android VpnService tun and the iPhone NCM Ethernet link.
 *
 * NCM carries Ethernet frames while the tun is a layer-3 device, so this bridge strips and
 * restores the Ethernet II header. Link-local neighbor discovery stays in the Android kernel,
 * mirroring LIVI's reliance on the host kernel for NDP on its TAP interface.
 */
class Ipv6NcmBridge(
    private val ncm: NcmUsbBridge,
    private val tun: ParcelFileDescriptor,
    private val hostMac: ByteArray,
    private val onError: (Throwable) -> Unit,
    // 1.0.27（3e 1.0.26 实测）：看门狗事件必须进 LiteLog 才能被日志采集捕获——
    // 1.0.26 的原生日志（xcertplay-usb TAG）在采集文件里 0 条，clear-halt 是否
    // 触发过完全无法取证。
    private val onDiagnostic: (String) -> Unit = {},
) : Closeable {
    init {
        require(hostMac.size == EthernetIpv6Codec.MAC_BYTES) { "hostMac must be 6 bytes" }
    }

    @Volatile
    private var peerMac: ByteArray? = null
    private var loggedInbound = false
    private var loggedOutbound = false
    private var loggedWaitingForPeer = false
    // vc67 诊断（TS7 0x4301 后 iPhone 零数据面）：入站日志预算放开到 4000，
    // 全量记录 iPhone 在 NCM 链路上的每个 IPv6 帧；非 IPv6 帧不再静默丢弃，记录帧头。
    private var inboundLogBudget = 4000
    private var nonIpv6DropCount = 0
    private var outboundLogBudget = 24
    private val running = AtomicBoolean(false)
    private lateinit var ncmToTunThread: Thread
    private lateinit var tunToNcmThread: Thread

    fun start() {
        check(running.compareAndSet(false, true)) { "bridge is already started" }
        ncmToTunThread = Thread(::runNcmToTun, "ncm-ipv6-in").apply {
            isDaemon = true
            start()
        }
        tunToNcmThread = Thread(::runTunToNcm, "ncm-ipv6-out").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        ncm.close()
        tun.close()
        join(ncmToTunThread)
        join(tunToNcmThread)
    }

    private fun runNcmToTun() {
        val output = FileOutputStream(tun.fileDescriptor)
        // 1.0.26（3e 1.0.25 实测 21:17:50/21:18:07）：EMUI USB IN 端点偶发整体楔死——
        // USBMUX/NCM queue 同刻失败，同步降级读也永远拿不到入向数据（音频/视频/RTSP
        // 响应/IAP2 全灭），出向触摸 POST 仍活，静默持续到用户手动断开。会话建立后
        // NCM 入向永远有流量（音频 50pkt/s 恒流 + RTSP 轮询），故以「检测到持续流量
        // 后静默」为楔死判据：先 CLEAR_FEATURE(HALT) 试救一次，仍死则关桥——共享
        // UsbDeviceConnection 随之失效 → 控制环结束 → 复用 1.0.10 peer 自动重试全量
        // 重建（重开 USB 连接治楔死）。armed 判据用单秒帧数 ≥40（NDP 间隙只有几个
        // 包不会误触发；RTSP 握手后音频视频立即满流）。
        var lastFrameAt = System.nanoTime()
        var windowStart = 0L
        var framesThisWindow = 0
        var streamingSeen = false
        var clearHaltSent = false
        try {
            while (running.get()) {
                val frame = ncm.recv(READ_TIMEOUT_MILLIS)
                if (frame == null) {
                    if (streamingSeen) {
                        val idleMs = (System.nanoTime() - lastFrameAt) / 1_000_000L
                        if (idleMs > STALL_CLEAR_HALT_MILLIS && !clearHaltSent) {
                            clearHaltSent = true
                            onDiagnostic("ncm inbound stalled ${idleMs}ms mid-stream; attempting clear halt")
                            ncm.clearHaltInEndpoints()
                        }
                        if (idleMs > STALL_FATAL_MILLIS) {
                            onDiagnostic(
                                "ncm inbound stalled ${idleMs}ms mid-stream; closing bridge for full reconnect",
                            )
                            onError(IOException("ncm inbound stalled ${idleMs}ms mid-stream (usb wedge)"))
                            close()
                            return
                        }
                    }
                    continue
                }
                lastFrameAt = System.nanoTime()
                val nowMs = System.nanoTime() / 1_000_000L
                if (nowMs - windowStart >= 1_000) {
                    windowStart = nowMs
                    framesThisWindow = 0
                }
                framesThisWindow++
                if (!streamingSeen && framesThisWindow >= STREAMING_FRAMES_PER_WINDOW) {
                    streamingSeen = true
                    onDiagnostic("ncm streaming detected; inbound stall watchdog armed")
                }
                val ipv6 = EthernetIpv6Codec.parseIpv6View(frame)
                if (ipv6 == null) {
                    // vc67：非 IPv6 帧（ARP/VLAN 0x8100 等）此前静默丢弃——TS7 上 iPhone
                    // 数据面零流量，先取证这些帧是否真实存在以及帧型是什么。
                    nonIpv6DropCount++
                    if (nonIpv6DropCount <= 64) {
                        val head = frame.take(20).joinToString("") { "%02x".format(it) }
                        Log.i(TAG, "ncm non-ipv6 frame #%d size=%d head=%s".format(nonIpv6DropCount, frame.size, head))
                    }
                    continue
                }
                peerMac = ipv6.sourceMac
                if (!loggedInbound) {
                    loggedInbound = true
                    Log.i(
                        TAG,
                        "ncm first inbound ipv6 bytes=${ipv6.payloadLength} " +
                            "peer=${ipv6.sourceMac.macString()}",
                    )
                }
                if (inboundLogBudget > 0) {
                    inboundLogBudget--
                    Log.i(TAG, "ncm inbound ${frame.summary(ipv6.payloadOffset)}")
                }
                output.write(frame, ipv6.payloadOffset, ipv6.payloadLength)
            }
        } catch (error: IOException) {
            if (running.get()) onError(error)
        } catch (error: RuntimeException) {
            if (running.get()) onError(error)
        }
    }

    private fun runTunToNcm() {
        val input = FileInputStream(tun.fileDescriptor)
        val buffer = ByteArray(TUN_READ_BYTES)
        try {
            while (running.get()) {
                val length = input.read(buffer)
                if (length == -1) {
                    if (running.get()) onError(IOException("NCM IPv6 tunnel closed"))
                    return
                }
                // Android's TUN fd may transiently report a zero-byte read while its network is
                // being registered. It is neither EOF (-1) nor an IPv6 packet.
                if (length == 0) {
                    LockSupport.parkNanos(ZERO_READ_BACKOFF_NANOS)
                    continue
                }
                val tunPacket = buffer.copyOf(length)
                val ipv6 = EthernetIpv6Codec.addNeighborAdvertisementTargetMac(tunPacket, hostMac)
                if (ipv6.size != tunPacket.size) {
                    Log.i(TAG, "ncm added target-link-layer option to neighbor advertisement")
                }
                if (outboundLogBudget > 0) {
                    outboundLogBudget--
                    Log.i(TAG, "ncm outbound ${ipv6.summary(0)}")
                }
                val multicastMac = EthernetIpv6Codec.multicastDestinationMac(ipv6)
                val mac = multicastMac ?: peerMac
                if (mac == null) {
                    if (!loggedWaitingForPeer) {
                        loggedWaitingForPeer = true
                        Log.i(TAG, "ncm deferred outbound unicast bytes=$length until peer MAC is learned")
                    }
                    continue
                }
                if (!loggedOutbound) {
                    loggedOutbound = true
                    Log.i(
                        TAG,
                        "ncm first outbound ipv6 bytes=$length destination=${mac.macString()} multicast=${multicastMac != null}",
                    )
                }
                val frame = EthernetIpv6Codec.build(hostMac, mac, ipv6)
                ncm.send(frame, WRITE_TIMEOUT_MILLIS)
            }
        } catch (error: IOException) {
            if (running.get()) onError(error)
        } catch (error: RuntimeException) {
            if (running.get()) onError(error)
        }
    }

    private fun join(thread: Thread) {
        if (thread === Thread.currentThread()) return
        try {
            thread.join(JOIN_TIMEOUT_MILLIS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (thread.isAlive) thread.interrupt()
    }

    private fun ByteArray.macString(): String =
        joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun ByteArray.summary(offset: Int): String {
        val payloadBytes = size - offset
        if (payloadBytes < 40) return "truncated bytes=$payloadBytes"
        val source = InetAddress.getByAddress(copyOfRange(offset + 8, offset + 24)).hostAddress
        val destination = InetAddress.getByAddress(copyOfRange(offset + 24, offset + 40)).hostAddress
        val nextHeader = this[offset + 6].toInt() and 0xff
        val detail = when {
            nextHeader == 6 && payloadBytes >= 44 -> " tcp=${u16(offset + 40)}->${u16(offset + 42)}"
            nextHeader == 17 && payloadBytes >= 44 -> " udp=${u16(offset + 40)}->${u16(offset + 42)}"
            nextHeader == 58 && payloadBytes >= 41 -> " icmp6=${this[offset + 40].toInt() and 0xff}"
            else -> ""
        }
        return "bytes=$payloadBytes src=$source dst=$destination next=$nextHeader$detail"
    }

    private fun ByteArray.u16(offset: Int): Int =
        ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)

    private companion object {
        const val TAG = "xcertplay-usb"
        const val READ_TIMEOUT_MILLIS = 1_000L
        const val WRITE_TIMEOUT_MILLIS = 2_000
        const val TUN_READ_BYTES = 4_096
        const val ZERO_READ_BACKOFF_NANOS = 1_000_000L
        const val JOIN_TIMEOUT_MILLIS = 2_000L

        // 1.0.26 入向楔死看门狗：armed 后静默先试 clear halt，仍死关桥重建。
        // 阈值依据：会话内音频 50pkt/s 恒流 + RTSP 秒级轮询，零入向远超正常空闲。
        // 1.0.27 提速：3e 1.0.26 实测用户在楔死后 ~5.3s 就手动断开（21:35:30.7 楔死 →
        // 21:35:36 断开），10s 兜底来不及生效——clear halt 提前到 2.5s，关桥提前到 6s，
        // 务必在用户放弃之前完成自愈动作。Siri/通话的音频流切换间隙实测 <100ms（1.0.26
        // 21:34:56.9 TEARDOWN→SETUP），2.5s 不会误触发。
        const val STREAMING_FRAMES_PER_WINDOW = 40
        const val STALL_CLEAR_HALT_MILLIS = 2_500L
        const val STALL_FATAL_MILLIS = 6_000L
    }
}
