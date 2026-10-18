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
    // vc96（FE-6 断链分析）：wedge fatal 判定阈值可调——no_audio 会话无音频恒流垫底，
    // 画面静止时入向断流属正常形态，构造方按变体放宽；默认维持 6s。
    private val stallFatalMillis: Long = STALL_FATAL_MILLIS,
    // vc97③：wedge 判别器探针——NCM 数据面断流时，控制面（usbmux）是否仍活着。
    // 控制口活而数据口死=端点级真楔死→按 stallFatalMillis 快拆（clear halt 已救过一次）；
    // 控制口同停=iPhone 整体停发型（FE-6 形态，数秒后自愈）→宽限 20s 防误拆好桥。
    private val usbmuxAlive: () -> Boolean = { false },
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
    // vc94：close 时写哑包唤醒阻塞在 TUN read 的出站线程（部分内核/ROM 上 tun fd close
    // 不唤醒阻塞中的 read，僵尸线程随重连累积）。
    private var tunOutput: FileOutputStream? = null

    /**
     * vc125（KK 实测）：内核对 tun 设备的 IPv6 组播不投递给应用 socket（snmp6 计数
     * 持续增长但 mDNS 应答器 socket 零收包）——进程内旁路：入向以太网帧在写 tun 前
     * 喂给观察者（mDNS 应答器直取 UDP 5353 查询），绕开内核组播路径。
     */
    @Volatile
    var inboundFrameTap: ((ByteArray) -> Unit)? = null

    fun start() {
        check(running.compareAndSet(false, true)) { "bridge is already started" }
        tunOutput = FileOutputStream(tun.fileDescriptor)
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
        // vc94：先写哑包再关 fd——TUN 只有 fe80::/64 路由，目的地址 ff02::1（链路本地组播）
        // 的包会被内核从 TUN 路由出去，read 侧必然醒来；reader 在 read 返回后检查 running
        // 已 false 即退出，哑包不会被转发到 NCM。随后关 fd 并 join，防僵尸线程累积。
        runCatching { tunOutput?.write(DUMMY_TUN_PACKET) }
        runCatching { ncm.close() }
        runCatching { tunOutput?.close() }
        runCatching { tun.close() }
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
                        // vc97③：wedge 判别器——控制面死则放宽到 20s（iPhone 停发型自愈窗），
                        // 控制面活则维持变体阈值（真端点楔死快拆）。no_audio 15s 与 20s 取 max。
                        val fatalThreshold =
                            if (usbmuxAlive()) stallFatalMillis
                            else maxOf(stallFatalMillis, STALL_GRACE_CONTROL_DEAD_MILLIS)
                        if (idleMs > fatalThreshold) {
                            onDiagnostic(
                                "ncm inbound stalled ${idleMs}ms mid-stream; closing bridge " +
                                    "(usbmuxAlive=${usbmuxAlive()} threshold=${fatalThreshold}ms)",
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
                inboundFrameTap?.invoke(frame)
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
                // vc94：close() 写哑包唤醒本线程——read 返回后 running 已 false，直接退出，
                // 哑包不进入编解码与 NCM 发送路径。
                if (!running.get()) return
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

        // vc94：close 唤醒哑包——最小 IPv6 头（40B），next header=59(No Next Header)、
        // hop limit=1、src=fe80::2、dst=ff02::1。TUN 仅 fe80::/64 路由，链路本地组播包
        // 内核必然从 TUN 发出 → 阻塞在 read 的线程醒来（只用于唤醒，reader 不转发）。
        val DUMMY_TUN_PACKET = ByteArray(40).apply {
            this[0] = 0x60
            this[6] = 0x3b
            this[7] = 0x01
            this[8] = 0xfe.toByte() // src fe80::2
            this[9] = 0x80.toByte()
            this[23] = 0x02
            this[24] = 0xff.toByte() // dst ff02::1
            this[25] = 0x02
            this[39] = 0x01
        }

        // 1.0.26 入向楔死看门狗：armed 后静默先试 clear halt，仍死关桥重建。
        // 阈值依据：会话内音频 50pkt/s 恒流 + RTSP 秒级轮询，零入向远超正常空闲。
        // 1.0.27 提速：3e 1.0.26 实测用户在楔死后 ~5.3s 就手动断开（21:35:30.7 楔死 →
        // 21:35:36 断开），10s 兜底来不及生效——clear halt 提前到 2.5s，关桥提前到 6s，
        // 务必在用户放弃之前完成自愈动作。Siri/通话的音频流切换间隙实测 <100ms（1.0.26
        // 21:34:56.9 TEARDOWN→SETUP），2.5s 不会误触发。
        const val STREAMING_FRAMES_PER_WINDOW = 40
        const val STALL_CLEAR_HALT_MILLIS = 2_500L
        const val STALL_FATAL_MILLIS = 6_000L
        // vc97③：控制面（usbmux）同停时的宽限上限——iPhone 停发型断流数秒自愈（FE-6 实锤），
        // 20s 给足自愈窗；超过即升级为会话级拆桥（此时大概率不是停发而是链路级死亡）。
        const val STALL_GRACE_CONTROL_DEAD_MILLIS = 20_000L
    }
}
