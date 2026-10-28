package com.shilapi.xcertplay.usbbt

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * vc181（外置 USB 蓝牙 C/D 批）：dongle 建链状态机（精简重写）。
 * 参照上游参考实现 usbbt.UsbBtHost.java（1717 行），按指导书 §4 简化：
 * 只保留「初始化 → 可连接 →（配对/认证/加密）→ SDP 查 RFCOMM 信道 → RFCOMM → iAP2 DLC」
 * 这一条主链路；PeerStatus/发布/扫描列表/重连器/UI 配对确认全部裁掉，
 * SSP 走 Just-Works 自动确认，link key 存内存 map（进程内有效，断电重配对可接受，TODO 持久化）。
 *
 * 线程模型：
 * - driver 事件/ACL 读 daemon → [onHciEvent]/[onAclPacket]；
 * - 命令 daemon 线程逐条同步执行 [commandQueue]（一次一条，天然无需命令 credits 流控）；
 * - ACL 发送经 [aclLock] 队列 + credits（初值 1，初始化完成后取 Read_Buffer_Size）；
 * - 锁顺序固定：l2capLock → aclLock，禁止反向。
 */
internal class UsbBtHost(
    private val identity: UsbBtIdentity,
    private val driver: UsbBtDriver,
    private val log: (String) -> Unit,
) : UsbBtDriver.Listener {
    /** 生命周期：STOPPED 是正常关停，FAILED 是异常（差异写入 [statusText]）。 */
    enum class Phase { IDLE, STARTING, DISCOVERABLE, FAILED, STOPPED }

    /** 初始化单步：超时 3s；required 失败即整体失败，optional 跳过。 */
    private class Step(
        val name: String,
        val opcode: Int,
        val required: Boolean,
        val build: () -> ByteArray,
    )

    private class AuthRetry(val attempts: Int, val nextAt: Long)

    companion object {
        private const val STEP_TIMEOUT_MILLIS = 3000L
        private const val AUTH_RETRY_BASE_MILLIS = 1000L
        private const val AUTH_RETRY_MAX_MILLIS = 10000L
        private const val ACL_CREDITS_MAX = 64
        private const val PAGE_TIMEOUT_MILLIS = 30000L

        private const val PSM_SDP = 1
        private const val PSM_RFCOMM = 3
    }

    // ---- 并发面：跨线程字段全部无锁/并发容器；锁只护本线程私有序列 ----

    @Volatile
    var phase: Phase = Phase.IDLE
        private set

    /** 状态文本（D2 沿途搬进 UsbBtBootstrapStream.lastStatusText）。 */
    @Volatile
    var statusText: String = "未启动"
        private set

    private fun setStatus(text: String) {
        statusText = text
        log(text)
        onStatus?.invoke(text)
    }

    /** 状态行出口（D2 搬进 UsbBtBootstrapStream.lastStatusText 供设置页显示）。 */
    @Volatile
    var onStatus: ((String) -> Unit)? = null

    /** dongle 本机地址（初始化 ReadBdAddr 完成后非空）。 */
    fun localAddress(): BdAddr? = localAddr

    /** 等一条就绪的 iAP2 DLC（D2 主循环轮询）；超时返回 null。 */
    fun awaitIap2Dlc(timeoutMillis: Long): RfcommDlc? = iap2Link.take(timeoutMillis)

    /** 初始化步骤序列：仅命令线程读写。 */
    private var steps: List<Step> = emptyList()
    private var stepIndex = 0

    /** 命令队列元素（命令线程消费）。 */
    private class PendingCommand(val opcode: Int, val params: ByteArray)

    private val commandQueue = ArrayDeque<PendingCommand>()

    @Volatile
    private var commandThreadRunning = false
    private var commandThread: Thread? = null

    /** 控制器信息（Read_BdAddr / Read_Buffer_Size / TxPower）。 */
    @Volatile
    private var localAddr: BdAddr? = null
    @Volatile
    private var aclPackets = 1
    @Volatile
    private var aclDataLength = 27
    @Volatile
    private var inquiryTxPower: Int? = null

    /** link key 内存表（TODO vc182+：持久化，参照上游 PreferencesLinkKeyStore）。 */
    private val linkKeys = ConcurrentHashMap<String, ByteArray>()

    private val connections = ConcurrentHashMap<Int, BdAddr>()
    // v1 无主动扫描：链路可能由 iPhone 发起（incoming），故加密成功即启动 iAP2，
    // 不做方向假设；outgoing 仅用于本机主动 page 的记账。
    // 用 Collections.newSetFromMap：minSdk16 上 ConcurrentHashMap.newKeySet() 是 API21+，会炸。
    private val outgoing = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
    private val securing = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
    private val secured = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
    private val startedIap2 = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
    private val authRetries = ConcurrentHashMap<Int, AuthRetry>()

    /** 主动 connectTo 后等 ConnectionComplete 的对端（单飞）。 */
    private val paging = AtomicReference<BdAddr?>(null)
    @Volatile
    private var pagingSince = 0L

    // ---- ACL 发送面 ----
    private val aclLock = Object()
    private val aclQueue = ArrayDeque<Pair<Int, ByteArray>>()

    @Volatile
    private var aclCredits = 1
    private val aclOutstanding = ConcurrentHashMap<Int, Int>()

    // ---- L2CAP / RFCOMM / iAP2 ----
    private val l2capLock = Object()
    private val reassembler = L2capReassembler()
    private val iap2Link = UsbBtIap2Link()

    private fun newRfcommService(connectTo: Int?): RfcommL2capService =
        RfcommL2capService(
            serverChannels = setOf(identity.iap2RfcommChannel),
            log = log,
            onOpen = { dlc ->
                setStatus("iAP2 流已就绪")
                iap2Link.offer(dlc)
            },
            connectTo = connectTo,
        )

    private val l2cap = L2capLayer(
        // 本机不注册 SDP server（只做客户端查询）；RFCOMM 服务端信道保留（对端可主动连）。
        services = mapOf(PSM_RFCOMM to newRfcommService(connectTo = null)),
        send = { handle, payload -> sendAclFlow(handle, payload) },
        observer = object : L2capLayer.Observer {
            override fun onPeerService(handle: Int, addr: BdAddr?, psm: Int, supported: Boolean) {
                log("l2cap: peer uses psm=$psm supported=$supported")
            }
        },
    )

    /** 建链全部就绪（初始化完成进入 DISCOVERABLE）时回调一次。 */
    @Volatile
    var onReady: (() -> Unit)? = null

    // ---- 生命周期 ----

    /** 启动初始化序列（幂等：仅 IDLE 态有效）。 */
    fun start() {
        if (phase != Phase.IDLE) return
        phase = Phase.STARTING
        setStatus("正在初始化 USB 蓝牙 dongle…")
        steps = buildSteps()
        stepIndex = 0
        commandThreadRunning = true
        commandThread = Thread({ commandLoop() }, "usb-bt-commands").apply {
            isDaemon = true
            start()
        }
    }

    private fun buildSteps(): List<Step> = listOf(
        Step("Reset", HciCommands.RESET, true) { ByteArray(0) },
        Step("ReadLocalVersion", HciCommands.READ_LOCAL_VERSION_INFORMATION, false) { ByteArray(0) },
        Step("ReadLocalFeatures", HciCommands.READ_LOCAL_SUPPORTED_FEATURES, false) { ByteArray(0) },
        Step("ReadBdAddr", HciCommands.READ_BD_ADDR, true) { ByteArray(0) },
        Step("ReadBufferSize", HciCommands.READ_BUFFER_SIZE, true) { ByteArray(0) },
        Step("SetEventMask", HciCommands.SET_EVENT_MASK, true) { UsbBtIdentity.eventMask() },
        Step("WriteSimplePairingMode", HciCommands.WRITE_SIMPLE_PAIRING_MODE, true) { byteArrayOf(1) },
        Step("WriteInquiryMode", HciCommands.WRITE_INQUIRY_MODE, false) { byteArrayOf(2) },
        Step(
            "WriteDefaultLinkPolicy",
            HciCommands.WRITE_DEFAULT_LINK_POLICY_SETTINGS,
            false,
        ) { HciCommands.le16(5) },
        Step("WriteClassOfDevice", HciCommands.WRITE_CLASS_OF_DEVICE, true) {
            byteArrayOf(
                (identity.classOfDevice and 0xff).toByte(),
                ((identity.classOfDevice ushr 8) and 0xff).toByte(),
                ((identity.classOfDevice ushr 16) and 0xff).toByte(),
            )
        },
        Step("WriteLocalName", HciCommands.WRITE_LOCAL_NAME, true) {
            (HciCommands.utf8Prefix(247, identity.name) + ByteArray(248)).copyOf(248)
        },
        Step("ReadInquiryResponseTxPower", HciCommands.READ_INQUIRY_RESPONSE_TX_POWER, false) { ByteArray(0) },
        Step("WriteExtendedInquiryResponse", HciCommands.WRITE_EXTENDED_INQUIRY_RESPONSE, false) {
            val eir = identity.extendedInquiryResponse(inquiryTxPower)
            byteArrayOf(1) + eir.copyOf(240)
        },
        Step("WriteInquiryScanType", HciCommands.WRITE_INQUIRY_SCAN_TYPE, false) { byteArrayOf(1) },
        Step("WritePageScanType", HciCommands.WRITE_PAGE_SCAN_TYPE, false) { byteArrayOf(1) },
        Step("WriteScanEnable", HciCommands.WRITE_SCAN_ENABLE, true) { byteArrayOf(3) },
    )

    /** 命令线程：逐条同步执行；结果回投推进状态机（本线程内）。 */
    private fun commandLoop() {
        while (commandThreadRunning) {
            val pending = synchronized(commandQueue) { commandQueue.removeFirstOrNull() }
            if (pending == null) {
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                continue
            }
            var status = -1
            var payload: ByteArray? = null
            try {
                val ret = driver.hciCommand(pending.opcode, pending.params, STEP_TIMEOUT_MILLIS)
                if (ret != null) {
                    status = 0
                    payload = ret
                }
            } catch (e: UsbBtDriver.HciStatusException) {
                status = e.status
            } catch (e: Throwable) {
                log("command 0x%04x failed: $e".format(pending.opcode))
            }
            onCommandResult(pending.opcode, status, payload)
        }
    }

    private fun enqueueCommand(opcode: Int, params: ByteArray) {
        synchronized(commandQueue) { commandQueue.addLast(PendingCommand(opcode, params)) }
    }

    /** 命令完成（命令线程）：STARTING 态推进步骤，其余直接忽略。 */
    private fun onCommandResult(opcode: Int, status: Int, payload: ByteArray?) {
        if (phase != Phase.STARTING) return
        val step = steps.getOrNull(stepIndex) ?: return
        if (step.opcode != opcode) return // 迟到/异源完成，丢弃
        if (status == 0) {
            absorb(opcode, payload)
        } else if (step.required) {
            val reason = if (status < 0) "timeout:${step.name}" else "${step.name} status 0x%02x".format(status)
            failWith(reason)
            return
        }
        stepIndex++
        if (stepIndex >= steps.size) {
            phase = Phase.DISCOVERABLE
            aclCredits = aclPackets
            setStatus("dongle 就绪（${localAddr ?: "地址未知"}），等待 iPhone 连接")
            onReady?.invoke()
        }
    }

    /** 从 Command Complete 返回参数吸收控制器信息（payload 已去 status 字节）。 */
    private fun absorb(opcode: Int, payload: ByteArray?) {
        if (payload == null) return
        when (opcode) {
            HciCommands.READ_BD_ADDR ->
                if (payload.size >= 6) {
                    localAddr = BdAddr.of(payload, 0)
                }
            HciCommands.READ_BUFFER_SIZE ->
                if (payload.size >= 4) {
                    aclPackets = maxOf(1, le16(payload, 0))
                    aclDataLength = maxOf(27, le16(payload, 2))
                    log("buffer size: aclPackets=$aclPackets aclDataLength=$aclDataLength")
                }
            HciCommands.READ_INQUIRY_RESPONSE_TX_POWER ->
                if (payload.isNotEmpty()) {
                    inquiryTxPower = payload[0].toInt()
                }
        }
    }

    private fun le16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)

    fun failWith(reason: String) {
        if (phase == Phase.STOPPED) return
        phase = Phase.FAILED
        statusText = reason
        log("host failed: $reason")
        iap2Link.reset()
    }

    /** 停机：断全部 ACL 链路 → 关扫描 → 关 iAP2 → 关驱动。幂等。 */
    fun stop() {
        if (phase == Phase.STOPPED) return
        phase = Phase.STOPPED
        statusText = "已停止"
        commandThreadRunning = false
        synchronized(commandQueue) { commandQueue.clear() }
        for (handle in connections.keys.toList()) {
            try {
                driver.hciCommand(HciCommands.DISCONNECT, HciCommands.le16(handle and 0xfff) + byteArrayOf(0x13), 500)
            } catch (_: Throwable) {
            }
        }
        try {
            driver.hciCommand(HciCommands.WRITE_SCAN_ENABLE, byteArrayOf(0), 500)
        } catch (_: Throwable) {
        }
        // vc192：收尾必须补一次 HCI Reset。Realtek 系 dongle（CM390/RTL8761BUV）灌完固件后
        // 控制器会回到「已 patch、未改名」态（本地名回落到出厂值，如 CM390）。此前 stop() 只发
        // Disconnect + ScanEnable(0)，一旦发不出去（bulk 已死/中途失败）dongle 就停在那态继续对外
        // 可发现 —— 这正是外测「iPhone 蓝牙里看到的是 CM390 而不是我们的广播名」的来源。
        // legacy 0.3.1 日志里 handoff shutdown 的最后一步也是 `stage=HCI reset`，此处对齐。
        try {
            driver.hciCommand(HciCommands.RESET, ByteArray(0), 500)
        } catch (_: Throwable) {
        }
        iap2Link.reset()
        driver.close()
        commandThread?.let {
            if (Thread.currentThread() !== it) {
                try {
                    it.join(3500)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    // ---- 对外建链动作 ----

    /** 主动 page 指定对端（须已有 link key 才会真正发 CREATE_CONNECTION）。 */
    fun connectTo(addr: BdAddr): Boolean {
        if (phase != Phase.DISCOVERABLE) return false
        if (!linkKeys.containsKey(addr.key())) {
            setStatus("未与 ${addr.redacted()} 配对过，等待对端回连")
            return false
        }
        if (!paging.compareAndSet(null, addr)) return false
        pagingSince = System.currentTimeMillis()
        setStatus("正在连接 ${addr.redacted()}…")
        enqueueCommand(
            HciCommands.CREATE_CONNECTION,
            addr.toLittleEndian() + HciCommands.le16(52248) + byteArrayOf(1, 0) + HciCommands.le16(0) + byteArrayOf(1),
        )
        return true
    }

    /** 在既有链路上启动 iAP2（未加密则先认证）。 */
    fun openIap2OnLink(handle: Int) {
        if (!connections.containsKey(handle)) return
        if (secured.contains(handle)) {
            startIap2(handle)
        } else {
            securing.add(handle)
            enqueueCommand(HciCommands.AUTHENTICATION_REQUESTED, HciCommands.le16(handle and 0xfff))
        }
    }

    /** SDP 查 RFCOMM 信道 → 建 RFCOMM → DLC 交 iap2Link。可重入防护。 */
    private fun startIap2(handle: Int) {
        if (!startedIap2.add(handle)) return
        setStatus("正在查询 iAP2 服务…")
        val onFailed = { code: Int ->
            startedIap2.remove(handle)
            setStatus("iAP2 服务查询失败（l2cap result=$code）")
        }
        val query = SdpRfcommChannelQuery(UsbBtIdentity.IAP2_IPHONE_UUID) { channel ->
            if (channel == null) {
                startedIap2.remove(handle)
                setStatus("对端未提供 iAP2 RFCOMM 服务")
                return@SdpRfcommChannelQuery
            }
            if (!iap2Link.isOpen()) {
                setStatus("正在建立 RFCOMM（信道 $channel）…")
                synchronized(l2capLock) {
                    l2cap.connect(handle, PSM_RFCOMM, newRfcommService(channel), onFailed)
                }
            }
        }
        synchronized(l2capLock) {
            l2cap.connect(handle, PSM_SDP, query, onFailed)
        }
    }

    // ---- ACL 发送（l2capLock 内被调用；锁序 l2capLock → aclLock） ----

    private fun sendAclFlow(handle: Int, payload: ByteArray) {
        synchronized(aclLock) {
            aclQueue.addLast(handle to payload)
            flushAclLocked()
        }
    }

    private fun flushAclLocked() {
        while (aclQueue.isNotEmpty() && aclCredits > 0) {
            val (handle, payload) = aclQueue.first()
            val segments = ArrayList<ByteArray>()
            var off = 0
            do {
                val len = minOf(aclDataLength, payload.size - off)
                segments.add(payload.copyOfRange(off, off + len))
                off += len
            } while (off < payload.size)
            if (segments.size > aclCredits) break
            aclQueue.removeFirst()
            segments.forEachIndexed { index, segment ->
                // PB：首包 0b10（起点），续包 0b01
                driver.sendAcl(handle, segment, if (index == 0) 0x2 else 0x1)
                aclCredits--
                aclOutstanding[handle] = (aclOutstanding[handle] ?: 0) + 1
            }
        }
    }

    // ---- UsbBtDriver.Listener ----

    override fun onHciEvent(event: HciEvent) {
        // CommandComplete 由命令线程的同步 hciCommand 闭环处理（driver 双投递），此处忽略。
        if (event is HciEvent.CommandComplete) return
        when (event) {
            is HciEvent.CommandStatus -> {
                if (event.opcode == HciCommands.CREATE_CONNECTION && event.status != 0) {
                    paging.set(null)
                    setStatus("连接请求被控制器拒绝（status 0x%02x）".format(event.status))
                }
            }
            is HciEvent.ConnectionComplete -> onConnectionComplete(event)
            is HciEvent.ConnectionRequest -> onConnectionRequest(event)
            is HciEvent.DisconnectionComplete -> onDisconnectionComplete(event)
            is HciEvent.LinkKeyRequest -> {
                val key = linkKeys[event.addr.key()]
                if (key != null && key.size == 16) {
                    enqueueCommand(HciCommands.LINK_KEY_REQUEST_REPLY, event.addr.toLittleEndian() + key)
                } else {
                    enqueueCommand(HciCommands.LINK_KEY_REQUEST_NEGATIVE_REPLY, event.addr.toLittleEndian())
                    setStatus("${event.addr.redacted()} 请求配对…")
                }
            }
            is HciEvent.LinkKeyNotification -> {
                if (event.key.size == 16) {
                    linkKeys[event.addr.key()] = event.key
                    log("link key stored for ${event.addr.redacted()} (type ${event.keyType})")
                }
            }
            is HciEvent.PinCodeRequest ->
                enqueueCommand(HciCommands.PIN_CODE_REQUEST_NEGATIVE_REPLY, event.addr.toLittleEndian())
            is HciEvent.UserPasskeyRequest ->
                enqueueCommand(HciCommands.USER_PASSKEY_REQUEST_NEGATIVE_REPLY, event.addr.toLittleEndian())
            is HciEvent.IoCapabilityRequest -> {
                val auth = if (identity.ioCapability == 3) 4 else 5
                enqueueCommand(
                    HciCommands.IO_CAPABILITY_REQUEST_REPLY,
                    event.addr.toLittleEndian() + byteArrayOf(
                        identity.ioCapability.toByte(),
                        0,
                        auth.toByte(),
                    ),
                )
            }
            is HciEvent.UserConfirmationRequest -> {
                // SSP Just-Works：直接确认（简化决策，见指导书 §4/§8）
                enqueueCommand(HciCommands.USER_CONFIRMATION_REQUEST_REPLY, event.addr.toLittleEndian())
                log("ssp just-works confirm for ${event.addr.redacted()}")
            }
            is HciEvent.SimplePairingComplete -> {
                if (event.status != 0) {
                    setStatus("与 ${event.addr.redacted()} 配对失败（status ${event.status}）")
                }
            }
            is HciEvent.AuthenticationComplete -> onAuthenticationComplete(event)
            is HciEvent.EncryptionChange -> {
                // 不分方向：v1 主路径是 iPhone 回连（incoming），加密成功即可启 iAP2。
                if (event.status == 0 && event.enabled) {
                    securing.remove(event.handle)
                    secured.add(event.handle)
                    startIap2(event.handle)
                }
            }
            is HciEvent.HardwareError -> failWith("controller hardware error 0x%02x".format(event.errorCode))
            is HciEvent.NumberOfCompletedPackets -> onNumberOfCompletedPackets(event.params)
            is HciEvent.RemoteNameRequestComplete ->
                if (event.status == 0) log("peer name: ${event.name}")
            else -> log("hci event 0x%02x ignored".format(event.code))
        }
    }

    private fun onConnectionComplete(event: HciEvent.ConnectionComplete) {
        if (event.linkType != 1) return // 只关心 ACL
        if (event.status != 0) {
            paging.set(null)
            setStatus("连接失败（status ${event.status}）")
            return
        }
        connections[event.handle] = event.addr
        // connectTo 目标命中（CAS 成功）即消费 paging，标记为主动发起方
        if (paging.compareAndSet(event.addr, null)) {
            outgoing.add(event.handle)
            enqueueCommand(HciCommands.AUTHENTICATION_REQUESTED, HciCommands.le16(event.handle and 0xfff))
        }
        if (event.encryptionEnabled) {
            secured.add(event.handle)
            startIap2(event.handle)
        } else {
            setStatus("已连接 ${event.addr.redacted()}，等待认证/加密…")
        }
    }

    private fun onConnectionRequest(event: HciEvent.ConnectionRequest) {
        if (phase != Phase.DISCOVERABLE || event.linkType != 1) {
            enqueueCommand(
                HciCommands.REJECT_CONNECTION_REQUEST,
                event.addr.toLittleEndian() + byteArrayOf(13),
            )
            return
        }
        enqueueCommand(HciCommands.ACCEPT_CONNECTION_REQUEST, event.addr.toLittleEndian() + byteArrayOf(1))
        setStatus("接受 ${event.addr.redacted()} 的连接请求")
    }

    private fun onDisconnectionComplete(event: HciEvent.DisconnectionComplete) {
        connections.remove(event.handle)
        outgoing.remove(event.handle)
        securing.remove(event.handle)
        secured.remove(event.handle)
        startedIap2.remove(event.handle)
        authRetries.remove(event.handle)
        aclOutstanding.remove(event.handle)
        synchronized(l2capLock) { l2cap.onLinkDisconnected(event.handle) }
        log("link 0x%03x disconnected (reason 0x%02x)".format(event.handle, event.reason))
    }

    private fun onAuthenticationComplete(event: HciEvent.AuthenticationComplete) {
        val addr = connections[event.handle] ?: return
        when {
            event.status == 0 -> {
                authRetries.remove(event.handle)
                // 不分方向主动请求加密：incoming 链路到此也已认证，加密成功会回
                // EncryptionChange → startIap2；已加密则控制器立即回报，无副作用。
                enqueueCommand(
                    HciCommands.SET_CONNECTION_ENCRYPTION,
                    HciCommands.le16(event.handle and 0xfff) + byteArrayOf(1),
                )
            }
            event.status == 6 -> { // PIN 或 Key Missing：清 key 重配对
                linkKeys.remove(addr.key())
                if (securing.contains(event.handle)) {
                    enqueueCommand(HciCommands.DISCONNECT, HciCommands.le16(event.handle and 0xfff) + byteArrayOf(0x13))
                }
            }
            else -> {
                val attempts = (authRetries[event.handle]?.attempts ?: 0) + 1
                val delay = minOf(AUTH_RETRY_BASE_MILLIS shl (attempts - 1), AUTH_RETRY_MAX_MILLIS)
                authRetries[event.handle] = AuthRetry(attempts, System.currentTimeMillis() + delay)
                log("auth failed on 0x%03x (status ${event.status}); retry in ${delay}ms".format(event.handle))
            }
        }
    }

    private fun onNumberOfCompletedPackets(params: ByteArray) {
        if (params.isEmpty()) return
        val count = params[0].toInt() and 0xff
        var off = 1
        var freed = 0
        synchronized(aclLock) {
            repeat(count) {
                if (off + 4 > params.size) return
                val handle = le16(params, off) and 0xfff
                val completed = le16(params, off + 2)
                off += 4
                freed += completed
                aclOutstanding[handle] = maxOf(0, (aclOutstanding[handle] ?: 0) - completed)
            }
            aclCredits = minOf(ACL_CREDITS_MAX, aclCredits + freed)
            flushAclLocked()
        }
    }

    override fun onAclPacket(packet: UsbBtDriver.AclPacket) {
        val frame = reassembler.accept(packet) ?: return
        synchronized(l2capLock) { l2cap.onFrame(packet.handle, frame) }
    }

    override fun onTick() {
        // 认证重试到期重发；paging 超时清理（事件线程串行调用，无并发问题）
        val now = System.currentTimeMillis()
        for ((handle, retry) in authRetries) {
            if (now >= retry.nextAt && connections.containsKey(handle)) {
                enqueueCommand(HciCommands.AUTHENTICATION_REQUESTED, HciCommands.le16(handle and 0xfff))
                log("auth retry #${retry.attempts + 1} on 0x%03x".format(handle))
                return
            }
        }
        val pageStart = pagingSince
        if (pageStart > 0 && now - pageStart > PAGE_TIMEOUT_MILLIS) {
            paging.set(null)
            pagingSince = 0
            setStatus("连接超时")
        }
    }

    override fun onTransportFailure(reason: String) {
        failWith(reason)
    }
}
