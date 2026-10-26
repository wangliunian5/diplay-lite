package com.shilapi.xcertplay.network

import android.os.Build
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import java.net.DatagramPacket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * vc110（T3 KK 域）：自研有线 mDNS 应答器，替换 JmDNS。
 *
 * 根因（20261018 T3 pcap 取证）：JmDNS 在 KK 3.4 内核的 tun0 上周期通告能发
 * （pcap 可见完整 TXT 通告），但 ff02::fb 查询零应答——iPhone 重复查询
 * `_airplay`/`_raop` 拿不到回应，拒绝建 RTSP，会话卡「有线链路建立中」。
 * 同代码在 ONEPLUS5（API29）正常，判定为 JmDNS 在老内核 tun 组播接收失效。
 *
 * 本应答器直接在 tun0 上 MulticastSocket join ff02::fb 收查询，解析问题段后
 * 按查询类型回 PTR/SRV/TXT/AAAA（单播回查询源，RFC 6762 QU 风格），并保留
 * 开局 3 次周期通告。不依赖内核组播回环之外的任何机制。
 */
class WiredMdnsResponder(
    private val bindAddress: InetAddress,
    private val airPlayConfig: AirPlayConfig,
    private val identity: AirPlayIdentity,
    private val onDiagnostic: (String) -> Unit = {},
) {
    private val running = AtomicBoolean(false)
    private var socket: MulticastSocket? = null
    private var thread: Thread? = null
    @Volatile private var scopeId = 0
    @Volatile private var currentTxt: Map<String, String>? = null
    private var inlineSeen = 0
    private var replySeen = 0
    private var unmatchedSeen = 0

    /**
     * vc127（KK 实测）：socket send 到 link-local 目标抛 SocketException（KK sendto
     * 坑）——进程内出向旁路：手工组完整以太网帧（dst MAC=查询者源 MAC，由 bridge
     * 入向帧头取）经 NcmUsbBridge.send 直发 USB，绕开内核与 socket 层。
     */
    @Volatile
    var inlineFrameSender: ((ByteArray) -> Unit)? = null
    private var hostMacCache: ByteArray? = null

    /** 出向帧源 MAC：取开局通告帧的源（我们自己 fd00::2 的通告经 bridge 出向，无 MAC 可抄）——
     *  改由首个入向帧带出的本端 MAC（CarPlayController 从 hostMac 注入）。 */
    fun setHostMac(mac: ByteArray) {
        hostMacCache = mac.copyOf()
    }

    /** 最近一条查询的源 MAC（inline 组帧用）。 */
    private var lastQueryMac: ByteArray? = null
    /** 最近一条查询的真实源端口（inline 组帧用）。 */
    private var lastQueryPort = SOCKET_PORT

    /**
     * vc132（长 pcap 定案）：iPhone 收到 SRV(AAAA=fd00::2) 后不 SYN——fd00::/64
     * 在 iPhone 侧无 on-link 路由（它发的 RS=组播 ICMPv6 一直在要 RA）。补齐
     * IPv6 邻居面：RS→RA（宣告 fd00::/64 on-link+auto）、NS→NA（fd00::2 MAC 解析）、
     * 周期 RA。全部走 inline 出向直发 USB。
     */
    fun acceptIcmpv6Frame(frame: ByteArray, icmpOff: Int, icmpType: Int) {
        val sender = inlineFrameSender ?: return
        val srcIp = frame.copyOfRange(14 + 8, 14 + 24)
        val srcMac = frame.copyOfRange(6, 12)
        val hostMac = hostMacCache ?: return
        // RFC 4861：RA/NA 源地址必须是 link-local，否则 iPhone 内核直接丢弃
        val llSrc = byteArrayOf(0xfe.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2)
        when (icmpType) {
            133 -> { // Router Solicitation → 单播 RA + 组播 RA
                val mcastMac = byteArrayOf(0x33, 0x33, 0, 0, 0, 1)
                val allNodes = byteArrayOf(0xff.toByte(), 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
                sendIcmpv6(sender, hostMac, srcMac, llSrc, srcIp, buildRa())
                sendIcmpv6(sender, hostMac, mcastMac, llSrc, allNodes, buildRa())
                onDiagnostic("wired icmpv6 RA replied to RS")
            }
            135 -> { // Neighbor Solicitation → NA(target, 我们 MAC)
                if (icmpOff + 24 <= frame.size) {
                    val target = frame.copyOfRange(icmpOff + 4, icmpOff + 20)
                    val na = ByteArray(24)
                    na[0] = 136.toByte(); na[1] = 0
                    na[4] = 0x20 // Solicited
                    target.copyInto(na, 8)
                    na[20] = 2; na[21] = 1 // option: target link-layer address
                    hostMac.copyInto(na, 22)
                    sendIcmpv6(sender, hostMac, srcMac, llSrc, srcIp, na)
                    onDiagnostic("wired icmpv6 NA replied")
                }
            }
        }
    }

    private fun buildRa(): ByteArray {
        // RA 头 16B：type(0) code(1) cksum(2-3) cur_hop(4) flags(5) lifetime(6-7) reachable(8-11) retrans(12-15)
        val ra = ByteArray(16 + 32)
        ra[0] = 134.toByte(); ra[1] = 0
        ra[4] = 64 // cur hop limit（注意：cksum 在 2-3，vc134 曾错位写到这里）
        ra[6] = 0; ra[7] = 0 // router lifetime=0（非默认路由器，仅 on-link prefix）
        // option: Prefix Information fd00::/64, L=1 A=1（offset 16 起）
        val p = 16
        ra[p] = 3; ra[p + 1] = 4 // type=3, len=4*8=32B
        ra[p + 2] = 64 // prefix length
        ra[p + 3] = 0xC0.toByte() // L=1, A=1
        // valid=86400 / preferred=14400（手写，writeU32 只有流版本）
        for ((idx, v) in listOf(p + 4 to 86400, p + 8 to 14400)) {
            ra[idx] = ((v ushr 24) and 0xff).toByte(); ra[idx + 1] = ((v ushr 16) and 0xff).toByte()
            ra[idx + 2] = ((v ushr 8) and 0xff).toByte(); ra[idx + 3] = (v and 0xff).toByte()
        }
        bindAddress.address.copyInto(ra, p + 16)
        return ra
    }

    private fun sendIcmpv6(
        sender: (ByteArray) -> Unit,
        srcMac: ByteArray,
        dstMac: ByteArray,
        srcIp: ByteArray,
        dstIp: ByteArray,
        icmpPayload: ByteArray,
    ) {
        try {
            val icmpLen = icmpPayload.size
            val frame = ByteArray(14 + 40 + icmpLen)
            dstMac.copyInto(frame, 0); srcMac.copyInto(frame, 6)
            frame[12] = 0x86.toByte(); frame[13] = 0xdd.toByte()
            frame[14] = 0x60
            frame[18] = ((icmpLen ushr 8) and 0xff).toByte(); frame[19] = (icmpLen and 0xff).toByte()
            frame[20] = 58 // ICMPv6
            frame[21] = 255.toByte()
            srcIp.copyInto(frame, 22); dstIp.copyInto(frame, 38)
            icmpPayload.copyInto(frame, 54)
            // ICMPv6 checksum（IPv6 伪头）
            var sum = 0L
            fun feed(b: ByteArray, off: Int, len: Int) {
                var i = off
                while (i + 1 < off + len) {
                    sum += ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff); i += 2
                }
                if (i < off + len) sum += (b[i].toInt() and 0xff) shl 8
            }
            feed(srcIp, 0, 16); feed(dstIp, 0, 16)
            sum += icmpLen + 58
            feed(frame, 54, icmpLen)
            while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
            val cks = (sum.toInt() and 0xffff).inv() and 0xffff
            frame[56] = (cks ushr 8).toByte(); frame[57] = cks.toByte()
            sender(frame)
        } catch (_: Throwable) {
        }
    }

    /** 开局周期组播 RA（iPhone 上电晚于我们也能收到）。 */
    fun sendPeriodicRa() {
        val sender = inlineFrameSender ?: return
        val hostMac = hostMacCache ?: return
        val llSrc = byteArrayOf(0xfe.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2)
        sendIcmpv6(sender, hostMac, byteArrayOf(0x33, 0x33, 0, 0, 0, 1),
            llSrc, byteArrayOf(0xff.toByte(), 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1), buildRa())
    }

    /**
     * vc125（KK 实测）：内核对 tun 组播不投递应用 socket（snmp6 InMcastPkts 增长但
     * socket 零收）——进程内旁路入口：NCM bridge 把入向以太网帧直喂本方法，绕开内核。
     * 解析 IPv6/UDP(dst=5353) 取查询 payload，按源地址单播应答（与 receiveLoop 同路径）。
     */
    fun acceptFrame(frame: ByteArray) {
        // vc136 审查修复：ICMPv6（RA/NA）处理不依赖 mDNS txt——responder 未起
        // （JmDNS 回退）时 RS/NS 也要应答，否则 iPhone 永远装不上 on-link 路由
        try {
            if (frame.size < 62 || frame[12] != 0x86.toByte() || frame[13] != 0xdd.toByte()) return
            if (frame[14 + 6] == 58.toByte()) {
                acceptIcmpv6Frame(frame, 14 + 40, frame[54].toInt() and 0xff)
                return
            }
        } catch (_: Throwable) {
        }
        val txt = currentTxt ?: return
        try {
            if (frame.size < 62 || frame[12] != 0x86.toByte() || frame[13] != 0xdd.toByte()) return
            // IPv6 头：next header @14+6 == UDP(17)
            if (frame[14 + 6] != 17.toByte()) return
            val src = frame.copyOfRange(14 + 8, 14 + 24)
            val udpOff = 14 + 40
            if (frame.size < udpOff + 8) return
            val dstPort = ((frame[udpOff + 2].toInt() and 0xff) shl 8) or (frame[udpOff + 3].toInt() and 0xff)
            if (dstPort != SOCKET_PORT) return
            // 端口对不上=发给别人的；我们自己 socket 5353 端口，源端口也是 5353 正常。
            val udpLen = ((frame[udpOff + 4].toInt() and 0xff) shl 8) or (frame[udpOff + 5].toInt() and 0xff)
            val payloadLen = udpLen - 8
            if (payloadLen < 12) return // 最短 DNS 头
            if (frame.size < udpOff + 8 + payloadLen) return
            val payload = frame.copyOfRange(udpOff + 8, udpOff + 8 + payloadLen)
            val srcAddr = java.net.Inet6Address.getByAddress(null, src, scopeId)
            // 以太网帧头前 6 字节是目的 MAC（组播查询=33:33:...:fb），源 MAC 在 6-12
            lastQueryMac = frame.copyOfRange(6, 12)
            lastQueryPort = (frame[udpOff].toInt() and 0xff shl 8) or (frame[udpOff + 1].toInt() and 0xff)
            val packet = java.net.DatagramPacket(payload, payload.size, srcAddr, lastQueryPort)
            inlineSeen++
            if (inlineSeen <= 6 || inlineSeen % 16 == 0) {
                onDiagnostic("wired mdns inline query #$inlineSeen len=$payloadLen")
            }
            // vc136 审查修复：应答（含 ncm.send bulkTransfer，超时 2s）异步执行——
            // iPhone 查询风暴时同步发会阻塞 NCM 入向泵（bridge 热线程），
            // 触发 stall 误判拆桥；串行单线程同时保证 lastQueryMac/Port 一致性。
            inlineWorker.execute { answer(packet, txt) }
        } catch (error: Throwable) {
            onDiagnostic("wired mdns inline answer error: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private val inlineWorker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "wired-mdns-inline").apply { isDaemon = true }
    }

    private val hostName = "diplay-wired"
    private val airplayInstance = airPlayConfig.deviceName.replace(".", "_")
    private val raopInstance = airPlayConfig.deviceId.replace(":", "") + "@" +
        airPlayConfig.deviceName.replace(".", "_")
    private val servicePort = airPlayConfig.port

    /** @return true = 应答器已运行；false = 启动失败（调用方回退 JmDNS 通告）。 */
    fun start(): Boolean {
        check(running.compareAndSet(false, true)) { "wired mdns responder already started" }
        return try {
            val netIf = findInterface() ?: throw IllegalStateException("no iface for $bindAddress")
            scopeId = netIf.index
            // vc111（KK 实测）：MulticastSocket(port) 双栈默认 bind 在 KK sunxi 内核报
            // BindException EINVAL；改为未绑定构造 + 显式按 fallback 链 bind：
            // IPv6 通配 :: → IPv4/双栈通配 → tun 地址本身。
            val sock = MulticastSocket(null as java.net.SocketAddress?)
            sock.reuseAddress = true
            var bound = false
            val bindCandidates = listOf(
                // vc124（KK 实测）：:: 通配（getByName 与原始字节构造均）被 KK
                // InetSocketAddress 误判 "link-local without scope id"，fallback 0.0.0.0
                // 得到 AF_INET socket 收不到 v6 组播。绑 ULA tun 地址本身（fd00::2，
                // 全局 scope 不触发 link-local 检查）即得 AF_INET6 socket。
                bindAddress,
                InetAddress.getByName("0.0.0.0"),
            )
            for (candidate in bindCandidates) {
                if (candidate == null) continue
                try {
                    sock.bind(InetSocketAddress(candidate, SOCKET_PORT))
                    bound = true
                    onDiagnostic("wired mdns bound to $candidate:$SOCKET_PORT")
                    break
                } catch (bindError: Exception) {
                    onDiagnostic("wired mdns bind $candidate failed: " +
                        "${bindError.javaClass.simpleName}: ${bindError.message}")
                }
            }
            if (!bound) throw IllegalStateException("all mdns bind candidates failed (EINVAL)")
            sock.networkInterface = netIf
            sock.timeToLive = 255
            val group = InetSocketAddress(InetAddress.getByName(MDNS_GROUP_V6), SOCKET_PORT)
            sock.joinGroup(group, netIf)
            socket = sock
            val txt = CarPlayBonjourProtocol.airPlayTxtRecords(airPlayConfig, identity)
            currentTxt = txt
            val worker = Thread({
                announce(sock, txt)
                receiveLoop(sock, txt)
            }, "wired-mdns")
            worker.isDaemon = true
            worker.start()
            thread = worker
            // vc187：这里原本打的是 servicePort（AirPlay 端口 7100），与 mDNS 组播组实际
            // 端口 5353 无关，排障时极易被误读成「应答器占着 7100 抢了 AirPlay 的口」。
            // 改成打真实的 mDNS 监听端口。
            onDiagnostic(
                "wired mdns responder joined ${MDNS_GROUP_V6}%$scopeId if=${netIf.name} " +
                    "mdnsPort=$SOCKET_PORT airPlayPort=$servicePort",
            )
            true
        } catch (error: Exception) {
            onDiagnostic("wired mdns responder start failed: " +
                "${error.javaClass.simpleName}: ${error.message}")
            runCatching { socket?.close() }
            socket = null
            running.set(false)
            false
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        inlineWorker.shutdownNow()
        runCatching { socket?.close() }
        socket = null
        currentTxt = null
        thread?.interrupt()
        thread = null
    }

    private fun findInterface(): NetworkInterface? {
        val all = NetworkInterface.getNetworkInterfaces() ?: return null
        while (all.hasMoreElements()) {
            val nif = all.nextElement()
            val addrs = nif.interfaceAddresses
            for (a in addrs) {
                if (a.address is Inet6Address && a.address == bindAddress) return nif
            }
        }
        return null
    }

    private fun receiveLoop(sock: MulticastSocket, txt: Map<String, String>) {
        val buf = ByteArray(9000)
        while (running.get()) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                sock.receive(packet)
            } catch (error: Exception) {
                if (running.get()) continue else break
            }
            try {
                answer(packet, txt)
            } catch (error: Throwable) {
                onDiagnostic("wired mdns answer error: ${error.javaClass.simpleName}")
            }
        }
    }

    /** 解析查询问题段，命中服务即构造应答单播回源。 */
    private fun answer(packet: DatagramPacket, txt: Map<String, String>) {
        val data = packet.data
        if (packet.length < 12 || data[2].toInt() and 0x80 != 0) return // 忽略应答包
        val qd = readU16(data, 4)
        var off = 12
        var matchedService: String? = null // "airplay" | "raop" | "host"
        val echoed = ArrayList<Pair<List<String>, Int>>()
        for (i in 0 until qd) {
            if (off >= packet.length) return
            val (labels, next) = readName(data, off) ?: return
            off = next
            if (off + 4 > packet.length) return
            val qtype = readU16(data, off)
            off += 4
            echoed.add(labels to qtype)
            val name = labels.joinToString(".").lowercase()
            // vc136（Legacy 字节码对照定案）：精确匹配才应答。iOS 对含无关记录的
            // 应答整包丢弃——此前「未命中回全家桶」把 _companion-link/_mobdev2 查询
            // 全毒化了（应答含 _airplay 记录与查询无关）。Legacy 对这些查询沉默。
            if (name.endsWith("_airplay._tcp.local")) {
                if (matchedService == null) matchedService = "airplay"
            } else if (name.endsWith("_raop._tcp.local")) {
                if (matchedService == null) matchedService = "raop"
            } else if (name == "_services._dns-sd._udp.local") {
                if (matchedService == null) matchedService = "enum"
            } else if (name == "$hostName.local" || name.startsWith("$airplayInstance._airplay") ||
                name.startsWith("$raopInstance._raop")
            ) {
                if (matchedService == null) matchedService = "host"
            }
        }
        val service = matchedService ?: run {
            // vc187（t3 实测）：v1.2.20 两条日志都是「查询 69~72 次、应答 0 次」，
            // 而成功与失败的建链轮次里查询量一致 ⇒ 沉默本身不是建链失败判别因素，
            // 但「收了却不答」长期无据可查。把问到的名字记下来（节流），
            // 下一轮据此判断该放行应答还是该继续沉默。
            if (unmatchedSeen++ < 8 || unmatchedSeen % 24 == 0) {
                onDiagnostic(
                    "wired mdns query unmatched #$unmatchedSeen qnames=" +
                        echoed.joinToString(",") { (labels, _) -> labels.joinToString(".") },
                )
            }
            return
        }
        // 先编答案段拿记录数，再拼完整包：头(id/flags/qd/an/ns/ar)→问题→答案
        val answerBuf = java.io.ByteArrayOutputStream(512)
        var answers = 0
        when (service) {
            "airplay" -> {
                answers += writeServiceRecords(
                    answerBuf, "_airplay._tcp", airplayInstance, txt, listOf(12, 33, 16),
                )
            }
            "enum" -> {
                // _services._dns-sd._udp 枚举（Legacy 同款）：告知存在 _airplay/_raop
                answers += writeServiceEnumRecords(answerBuf)
            }
            "raop" -> {
                answers += writeServiceRecords(
                    answerBuf, "_raop._tcp", raopInstance, txt, listOf(12, 33, 16),
                )
            }
            "host" -> {
                answers += writeAaaa(answerBuf, "$hostName.local")
            }
        }
        val out = java.io.ByteArrayOutputStream(512)
        writeU16(out, 0)
        writeU16(out, 0x8400)
        writeU16(out, echoed.size)
        writeU16(out, answers)
        writeU16(out, 0)
        writeU16(out, 0)
        for ((labels, qtype) in echoed) {
            writeName(out, labels)
            writeU16(out, qtype)
            writeU16(out, 1)
        }
        out.write(answerBuf.toByteArray())
        val payload = out.toByteArray()
        val src = packet.address
        val target = if (src is Inet6Address && scopeId > 0) {
            java.net.Inet6Address.getByAddress(null, src.address, scopeId)
        } else {
            src
        }
        val reply = DatagramPacket(payload, payload.size, target, packet.port)
        try {
            socket?.send(reply)
            // vc136 审查节流：iPhone 高频查询时逐条打日志会拖垮文件 IO（每条 flush）
            if (replySeen++ < 4 || replySeen % 24 == 0) {
                onDiagnostic("wired mdns replied #$replySeen ${payload.size}B svc=$service")
            }
        } catch (error: Exception) {
            // vc127：KK sendto 对 link-local 目标抛 SocketException——手工组以太网帧
            // 经 USB bridge 直发（dst MAC=查询者 MAC，UDP checksum 全算）。
            if (sendInlineFrame(payload, packet.address)) {
                if (replySeen++ < 4 || replySeen % 24 == 0) {
                    onDiagnostic("wired mdns inline replied #$replySeen ${payload.size}B svc=$service")
                }
            } else {
                onDiagnostic("wired mdns reply send failed: ${error.javaClass.simpleName}")
            }
        }
    }

    /** 手工组完整以太网帧（IPv6+UDP+DNS）经 inlineFrameSender 直发 USB。 */
    private fun sendInlineFrame(dnsPayload: ByteArray, dstIp: InetAddress): Boolean {
        val sender = inlineFrameSender ?: return false
        val dstMac = lastQueryMac ?: return false
        val srcMac = hostMacCache ?: return false
        val srcIp = bindAddress.address
        val dst = dstIp.address
        if (srcIp.size != 16 || dst.size != 16) return false
        val udpLen = 8 + dnsPayload.size
        val frame = ByteArray(14 + 40 + udpLen)
        // 以太网头
        dstMac.copyInto(frame, 0)
        srcMac.copyInto(frame, 6)
        frame[12] = 0x86.toByte(); frame[13] = 0xdd.toByte()
        // IPv6 头
        val vtf = (6 shl 28) // version=6, no flow label
        frame[14] = (vtf ushr 24).toByte(); frame[15] = (vtf ushr 16).toByte()
        frame[16] = (vtf ushr 8).toByte(); frame[17] = vtf.toByte()
        frame[18] = ((udpLen ushr 8) and 0xff).toByte(); frame[19] = (udpLen and 0xff).toByte()
        frame[20] = 17 // next header = UDP
        frame[21] = 64 // hop limit
        srcIp.copyInto(frame, 22)
        dst.copyInto(frame, 38)
        // UDP 头
        val udpOff = 54
        frame[udpOff] = (SOCKET_PORT ushr 8).toByte(); frame[udpOff + 1] = SOCKET_PORT.toByte()
        frame[udpOff + 2] = (lastQueryPort ushr 8).toByte(); frame[udpOff + 3] = lastQueryPort.toByte()
        frame[udpOff + 4] = (udpLen ushr 8).toByte(); frame[udpOff + 5] = (udpLen and 0xff).toByte()
        dnsPayload.copyInto(frame, udpOff + 8)
        // UDP checksum（IPv6 伪头，必须校验通过）
        var sum = 0L
        fun feed(b: ByteArray, off: Int, len: Int) {
            var i = off
            while (i + 1 < off + len) {
                sum += ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
                i += 2
            }
            if (i < off + len) {
                // 奇数长度：末字节按高字节补零（RFC 768 padding）——漏算必错
                sum += (b[i].toInt() and 0xff) shl 8
            }
        }
        feed(srcIp, 0, 16); feed(dst, 0, 16)
        sum += udpLen + 17
        feed(frame, udpOff, udpLen)
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        val checksum = (sum.toInt() and 0xffff).inv() and 0xffff
        frame[udpOff + 6] = (checksum ushr 8).toByte(); frame[udpOff + 7] = checksum.toByte()
        return try {
            sender(frame)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** PTR+SRV+TXT 三记录（requestedTypes 为空 = 全发）。返回记录数。 */
    private fun writeServiceRecords(
        out: java.io.ByteArrayOutputStream,
        serviceType: String,
        instance: String,
        txt: Map<String, String>,
        requested: List<Int>,
    ): Int {
        var count = 0
        val svcName = "$serviceType.local"
        val instName = "$instance.$svcName"
        if (requested.contains(12)) {
            writeRecordName(out, svcName)
            writeU16(out, 12)
            writeU16(out, 1)
            writeU32(out, TTL_120)
            val body = java.io.ByteArrayOutputStream()
            writeName(body, instName.split("."))
            writeLenPrefixed(out, body.toByteArray())
            count++
        }
        if (requested.contains(33)) {
            writeRecordName(out, instName)
            writeU16(out, 33)
            writeU16(out, 1)
            writeU32(out, TTL_4500)
            val body = java.io.ByteArrayOutputStream()
            writeU16(body, 0)
            writeU16(body, 0)
            writeU16(body, servicePort)
            writeName(body, listOf(hostName, "local"))
            writeLenPrefixed(out, body.toByteArray())
            count++
        }
        if (requested.contains(16)) {
            writeRecordName(out, instName)
            writeU16(out, 16)
            writeU16(out, 1)
            writeU32(out, TTL_4500)
            writeLenPrefixed(out, encodeTxt(txt))
            count++
        }
        return count
    }

    private fun writeAaaa(out: java.io.ByteArrayOutputStream, name: String): Int {
        writeRecordName(out, name)
        writeU16(out, 28)
        writeU16(out, 1)
        writeU32(out, TTL_120)
        val addr = bindAddress.address
        writeLenPrefixed(out, addr)
        return 1
    }

    /**
     * vc130（T3 pcap 实锤）：iPhone(iOS16+) 有线发现不查 _airplay——先查
     * _companion-link._tcp（PTR）与 _apple-mobdev2（ANY），无 companion 记录则
     * 永不进入 AirPlay 建链。补 companion 服务记录：PTR+SRV+TXT（rpMac 等
     * CompanionLink 基础字段），端口暂复用 AirPlay 监听口观察 iPhone 行为。
     */
    private fun writeCompanionRecords(out: java.io.ByteArrayOutputStream): Int {
        var count = 0
        val svc = "_companion-link._tcp"
        // Apple 格式：实例名 = 显示名-随机后缀（6 hex，取 MAC 后 3 字节），非 MAC@名
        val macHex = deviceIdMac.replace(":", "")
        val inst = "${airPlayConfig.deviceName}-${macHex.takeLast(6)}"
        val instName = "$inst.$svc.local"
        // PTR
        writeRecordName(out, "$svc.local")
        writeU16(out, 12); writeU16(out, 1); writeU32(out, TTL_120)
        val ptrBuf = java.io.ByteArrayOutputStream(80)
        writeName(ptrBuf, instName.split("."))
        writeLenPrefixed(out, ptrBuf.toByteArray()); count++
        // SRV
        writeRecordName(out, instName)
        writeU16(out, 33); writeU16(out, 1); writeU32(out, TTL_120)
        val srv = java.io.ByteArrayOutputStream(64)
        writeU16(srv, 0); writeU16(srv, 0); writeU16(srv, servicePort)
        writeName(srv, listOf(hostName, "local"))
        writeLenPrefixed(out, srv.toByteArray()); count++
        // TXT
        writeRecordName(out, instName)
        writeU16(out, 16); writeU16(out, 1); writeU32(out, TTL_4500)
        writeLenPrefixed(
            out, encodeTxt(
                mapOf(
                    "rv" to "2",
                    "rpMac" to deviceIdMac,
                    "rpFl" to "64",
                    "rpHk" to "0",
                ),
            ),
        ); count++
        return count
    }

    /** _services._dns-sd._udp.local 服务枚举（Legacy 同款）：告知 iPhone 存在 _airplay/_raop。 */
    private fun writeServiceEnumRecords(out: java.io.ByteArrayOutputStream): Int {
        var count = 0
        for (svc in listOf("_airplay._tcp", "_raop._tcp", "_companion-link._tcp")) {
            writeRecordName(out, "_services._dns-sd._udp.local")
            writeU16(out, 12); writeU16(out, 1); writeU32(out, TTL_120)
            val ptrBuf = java.io.ByteArrayOutputStream(60)
            writeName(ptrBuf, "$svc.local".split("."))
            writeLenPrefixed(out, ptrBuf.toByteArray())
            count++
        }
        return count
    }

    private val deviceIdMac: String
        get() = airPlayConfig.deviceId

    private fun announce(sock: MulticastSocket, txt: Map<String, String>) {
        val group = InetAddress.getByName(MDNS_GROUP_V6)
        repeat(3) {
            if (!running.get()) return
            sendPeriodicRa() // vc132：开局 RA，宣告 fd00::/64 on-link
            val answerBuf = java.io.ByteArrayOutputStream(768)
            val answers = writeServiceRecords(
                answerBuf, "_airplay._tcp", airplayInstance, txt, listOf(12, 33, 16),
            ) + writeServiceRecords(
                answerBuf, "_raop._tcp", raopInstance, txt, listOf(12, 33, 16),
            ) + writeAaaa(answerBuf, "$hostName.local")
            val out = java.io.ByteArrayOutputStream(768)
            writeU16(out, 0)
            writeU16(out, 0x8400)
            writeU16(out, 0)
            writeU16(out, answers)
            writeU16(out, 0)
            writeU16(out, 0)
            out.write(answerBuf.toByteArray())
            val payload = out.toByteArray()
            try {
                sock.send(DatagramPacket(payload, payload.size, group, SOCKET_PORT))
            } catch (_: Exception) {
            }
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun encodeTxt(txt: Map<String, String>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for ((key, value) in txt) {
            val entry = "$key=$value".toByteArray(Charsets.UTF_8)
            if (entry.size > 255) continue
            out.write(entry.size)
            out.write(entry)
        }
        if (out.size() == 0) out.write(0)
        return out.toByteArray()
    }

    /** 名字记录的 owner 字段——完整写出（不压缩）。 */
    private fun writeRecordName(out: java.io.ByteArrayOutputStream, name: String) {
        writeName(out, name.trim('.').split("."))
    }

    private fun writeName(out: java.io.ByteArrayOutputStream, labels: List<String>) {
        for (label in labels) {
            val bytes = label.toByteArray(Charsets.UTF_8)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
    }

    private fun writeLenPrefixed(out: java.io.ByteArrayOutputStream, data: ByteArray) {
        writeU16(out, data.size)
        out.write(data)
    }

    private fun writeU16(out: java.io.ByteArrayOutputStream, v: Int) {
        out.write(v shr 8 and 0xff)
        out.write(v and 0xff)
    }

    private fun writeU32(out: java.io.ByteArrayOutputStream, v: Int) {
        out.write(v shr 24 and 0xff)
        out.write(v shr 16 and 0xff)
        out.write(v shr 8 and 0xff)
        out.write(v and 0xff)
    }

    private fun readU16(data: ByteArray, off: Int): Int =
        data[off].toInt() and 0xff shl 8 or (data[off + 1].toInt() and 0xff)

    /** 带压缩指针的名字解析；返回 (标签列表, 名字结束后的偏移)。 */
    private fun readName(data: ByteArray, start: Int): Pair<List<String>, Int>? {
        val labels = ArrayList<String>()
        var off = start
        var jumped = false
        var next = -1
        var hops = 0
        while (hops++ < 64) {
            if (off >= data.size) return null
            val len = data[off].toInt() and 0xff
            if (len == 0) {
                off += 1
                if (!jumped) next = off
                break
            }
            if (len and 0xc0 == 0xc0) {
                if (off + 1 >= data.size) return null
                val ptr = (len and 0x3f shl 8) or (data[off + 1].toInt() and 0xff)
                if (!jumped) next = off + 2
                off = ptr
                jumped = true
                continue
            }
            if (off + 1 + len > data.size) return null
            labels.add(String(data, off + 1, len, Charsets.UTF_8))
            off += 1 + len
        }
        if (next < 0) return null
        return labels to next
    }

    private companion object {
        const val SOCKET_PORT = 5353
        const val MDNS_GROUP_V6 = "ff02::fb"
        const val TTL_120 = 120
        const val TTL_4500 = 4500
    }
}
