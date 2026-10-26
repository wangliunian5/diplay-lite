package com.shilapi.xcertplay.airplay

import android.util.Log
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.math.BigInteger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class AirPlayDeviceInfo(
    val name: String,
    val deviceId: String,
    val wifiMac: String,
    val model: String,
)

/** Session lifecycle and command callbacks for the driver/UI layer. */
interface AirPlaySessionListener {
    fun onSessionActive(session: AirPlaySession) {}
    fun onSessionEnded(session: AirPlaySession) {}
    fun onVideoFrameRendered(session: AirPlaySession) {}
    fun onTransportError(message: String) {}
    fun onDeviceInfo(session: AirPlaySession, info: AirPlayDeviceInfo) {}
    fun onHostUiRequested(session: AirPlaySession) {}
    fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {}
    fun onDebugLog(message: String) {}

    /**
     * vc84：Siri 后无声恢复回调。speechrecognition 音频流被 TEARDOWN 后
     * [AirPlaySession.SIRI_AUDIO_RESTORE_TIMEOUT_MS] 内无任何音频 SETUP 重来时触发
     * （FE-6 fe6t2 实证：iPhone 进 Siri 拆媒体音频流、Siri 结束后不再重发 SETUP，
     * 音乐静音到用户手动断开）。编排层据此收会话走既有重试链全量重建。
     */
    fun onSiriAudioStall(session: AirPlaySession) {}
}

/** Stream transport seam; media decode/render is supplied by a later layer. */
interface AirPlayMediaHandler {
    fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Int? = null
    fun onAudio(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Map<String, Any?>? = null
    fun onDataStream(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?>? = null
    fun onFeedback(session: AirPlaySession): Map<String, Any?>? = null
    fun onTeardown(session: AirPlaySession, type: Int) {}
    fun onSessionClosed(session: AirPlaySession) {}
    fun setIapTunnelHandler(handler: ((BlockingDuplexByteStream) -> Boolean)?) {}
    fun onSetupResponseSent(session: AirPlaySession) {}
}

/**
 * One CarPlay AirPlay control connection.
 *
 * It owns the RTSP framing, pairing/auth/info routing, the encrypted event channel used for HID
 * input, stream SETUP/TEARDOWN routing, the NTP timing exchange, and the keep-alive socket.
 */
class AirPlaySession(
    private val socket: Socket,
    private val config: AirPlayConfig,
    private val identity: AirPlayIdentity,
    private val pairings: PairingStore,
    private val mfi: MfiAuthenticator?,
    private val listener: AirPlaySessionListener,
    private val media: AirPlayMediaHandler,
) : Closeable {
    internal val pairSetup = PairSetup(identity, pairings)
    internal val pairVerify = PairVerify(identity, pairings)
    internal var cipher: ControlCipher? = null
    internal var encBuf = ByteArray(0)
    internal var deviceBtMac = ""
    internal val activeStreams = linkedSetOf<Int>()

    private val closed = AtomicBoolean(false)
    private val notified = AtomicBoolean(false)
    private var eventServer: ServerSocket? = null
    private var eventSocket: Socket? = null
    private var eventCipher: ControlCipher? = null
    private var eventCseq = 0
    // M6d：断开前要能主动给 iPhone 发一条 TEARDOWN —— 需要控制通道的输出流与自增 CSeq。
    @Volatile private var controlOutput: BufferedOutputStream? = null
    private var controlCseq = 0
    @Volatile private var presentationUri: String? = null
    private var pendingNightMode: Boolean? = null
    private val firstTouchSendLogged = AtomicBoolean(false)
    private val touchSendFailureLogged = AtomicBoolean(false)
    // vc84：Siri 后无声恢复看门狗（FE-6 fe6t2 日志 diplay-20261015-110554 实证）。iPhone 进
    // Siri 时拆媒体音频流（TEARDOWN type=100 ended=true）→ SETUP speechrecognition 上行 →
    // Siri 结束后第二个 TEARDOWN，此后 iPhone 不再重发媒体音频 SETUP，音乐静音到用户手动
    // 断开（会话其余全健康，enhancedSiri 会话特有，TS7/8815 无复现）。检测：本会话出现过
    // speechrecognition 音频流、其音频流被 TEARDOWN 且会话内再无 media 音频流 → 装看门狗；期间
    // 任一音频 SETUP 到来即撤销；到期仍无 media 音频 → onSiriAudioStall 回调编排层收会话重建。
    @Volatile private var siriAudioSeen = false
    private val siriWatchdogGeneration = AtomicInteger(0)
    // vc86（fe6t4）：活跃音频流 type→audioType 映射。100 的类型码可为 media 或
    // speechrecognition，无法从类型码区分；fe6t3 实测（diplay-20261015-185536）Siri
    // TEARDOWN 时 101/default 残存导致「无任何音频流」判据永不满足、看门狗从未装弹，
    // 判据改为「无 media 音频流」。
    private val activeAudioKinds = ConcurrentHashMap<Int, String>()
    private val ntp = NtpClock { message -> debugLog("airplay ntp $message") }
    private var keepAliveSocket: DatagramSocket? = null
    private var keepAliveThread: Thread? = null
    private val eventWriteLock = Any()
    private val eventThreads = CopyOnWriteArrayList<Thread>()

    val host: String = socket.inetAddress?.hostAddress ?: ""
    val localAddress: InetAddress? = socket.localAddress
    private val peerAddress: InetAddress? = socket.inetAddress
    internal val remoteAddress: InetAddress?
        get() = (socket.remoteSocketAddress as? InetSocketAddress)?.address
    val controllerId: String? get() = pairVerify.verifiedControllerId
    val sharedSecret: ByteArray? get() = pairVerify.shared?.copyOf()

    fun syncedNtp(): BigInteger = ntp.syncedNtp()

    internal fun logDebug(message: String) = debugLog(message)

    /**
     * M6k23：控制连接结束原因（peer EOF / control decrypt failed / control I/O failed 等）。
     * 上层（CarPlayController）用它判定「未建流阶段的异常断开」并触发整代重启。
     */
    var lastCloseReason = "session closed"
        private set

    internal fun videoFrameRendered() {
        if (!closed.get()) listener.onVideoFrameRendered(this)
    }

    internal fun logTrace(message: String) = trace(message)

    fun start() {
        Thread(::runControl, "airplay-control").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // M6d：先礼貌地通知 iPhone 结束会话，再关连接。否则对端只看到 TCP 被砸掉
        // （日志里就是 "control I/O failed: Socket closed"），CarPlay 界面不退出、Wi-Fi 也不放开。
        sendTeardownBestEffort()
        safeClose(socket)
        try {
            media.onSessionClosed(this)
        } catch (error: Exception) {
            Log.w(TAG, "airplay media stream teardown failed", error)
        }
        teardown()
        if (notified.compareAndSet(false, true)) listener.onSessionEnded(this)
    }

    fun sendCommand(command: Map<String, Any?>): Boolean = synchronized(eventWriteLock) {
        sendCommandLocked(command)
    }

    private fun sendCommandLocked(command: Map<String, Any?>): Boolean {
        val socket = eventSocket ?: return false
        val cipher = eventCipher ?: return false
        eventCseq++
        val body = BplistCodec.encode(command)
        val head = "POST /command RTSP/1.0\r\n" +
            "Content-Type: $PLIST_CONTENT_TYPE\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "CSeq: $eventCseq\r\n\r\n"
        trace("airplay event tx headers=$head bodyHex=${body.toHex()}")
        return try {
            val bytes = cipher.encrypt(head.toByteArray(Charsets.US_ASCII) + body)
            val output = socket.getOutputStream()
            output.write(bytes)
            output.flush()
            true
        } catch (error: Exception) {
            Log.w(TAG, "airplay event command failed type=${command["type"]}", error)
            close()
            false
        }
    }

    fun sendTouch(contacts: List<AirPlayContact>): Boolean {
        val scaled = contacts.map {
            it.copy(x = it.x * config.main.widthPixels, y = it.y * config.main.heightPixels)
        }
        val report = AirPlayHid.touchReport(scaled)
        val sendStartNs = System.nanoTime()
        val sent = sendHidReport(AirPlayHid.TOUCH_HID_UID, report)
        if (sent) com.shilapi.xcertplay.media.TouchLatencyProbe.onTouchSent(sendStartNs, System.nanoTime() - sendStartNs)
        if (sent && firstTouchSendLogged.compareAndSet(false, true)) {
            val first = scaled.firstOrNull()
            Log.i(
                TAG,
                "airplay touch report sent contacts=${scaled.size} first=" +
                    "(${first?.x},${first?.y},down=${first?.down}) report=${report.toHexString()}",
            )
        } else if (!sent && touchSendFailureLogged.compareAndSet(false, true)) {
            Log.w(TAG, "airplay touch dropped: event channel is not ready")
        }
        return sent
    }

    fun sendKnob(state: AirPlayKnobState, momentary: Boolean = true) {
        sendHidReport(AirPlayHid.KNOB_HID_UID, AirPlayHid.knobReport(state))
        if (momentary) sendHidReport(AirPlayHid.KNOB_HID_UID, AirPlayHid.knobReport(AirPlayKnobState()))
    }

    fun sendKnobSelect(down: Boolean) =
        sendHidReport(AirPlayHid.KNOB_HID_UID, AirPlayHid.knobReport(AirPlayKnobState(select = down)))

    fun sendMedia(index: Int) {
        sendHidReport(AirPlayHid.MEDIA_HID_UID, AirPlayHid.mediaReport(index))
        sendHidReport(AirPlayHid.MEDIA_HID_UID, AirPlayHid.mediaReport(0))
    }

    fun sendTelephony(index: Int) {
        sendHidReport(AirPlayHid.TELEPHONY_HID_UID, AirPlayHid.telephonyReport(index))
        sendHidReport(AirPlayHid.TELEPHONY_HID_UID, AirPlayHid.telephonyReport(0))
    }

    fun invokeSiri() {
        sendCommand(linkedMapOf("type" to "requestSiri", "params" to linkedMapOf("siriAction" to 2)))
        sendCommand(linkedMapOf("type" to "requestSiri", "params" to linkedMapOf("siriAction" to 3)))
    }

    fun sendIapMessage(data: ByteArray, timeoutMillis: Long = 0L): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        val command = linkedMapOf<String, Any?>(
            "type" to "iAPSendMessage",
            "params" to linkedMapOf("data" to data),
        )
        if (timeoutMillis == 0L) return sendCommand(command)

        val deadlineNanos = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
        while (true) {
            val sent = synchronized(eventWriteLock) {
                if (eventSocket == null || eventCipher == null) {
                    null
                } else {
                    sendCommandLocked(command)
                }
            }
            if (sent != null) return sent
            if (closed.get()) return false

            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0) return false
            val sleepMillis = minOf(
                EVENT_READY_POLL_MILLIS,
                (remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND,
            ).coerceAtLeast(1)
            try {
                Thread.sleep(sleepMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    fun setNightMode(night: Boolean): Boolean = synchronized(eventWriteLock) {
        pendingNightMode = night
        sendPendingNightModeLocked()
    }

    private fun sendPendingNightModeLocked(): Boolean {
        val night = pendingNightMode ?: return true
        val sent = sendCommandLocked(
            linkedMapOf("type" to "setNightMode", "params" to linkedMapOf("nightMode" to night)),
        )
        if (sent) pendingNightMode = null
        return sent
    }

    private fun sendHidReport(uid: Int, report: ByteArray): Boolean =
        sendCommand(
            linkedMapOf(
                "type" to "hidSendReport",
                "uuid" to uid.toString(16),
                "hidReport" to report,
            ),
        )

    private fun runControl() {
        com.shilapi.xcertplay.network.TcpLiveness.configure(socket) { debugLog(it) }
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        // M6d：留给 close() 用 —— 断开前要在这条加密通道上补发 TEARDOWN。
        controlOutput = output
        var accumulated = ByteArray(0)
        val buffer = ByteArray(READ_CHUNK_BYTES)
        try {
            while (!closed.get()) {
                val count = input.read(buffer)
                if (count < 0) {
                    lastCloseReason = "peer EOF"
                    break
                }
                var plaintext = buffer.copyOf(count)
                val activeCipher = cipher
                if (activeCipher != null) {
                    encBuf += plaintext
                    val decrypted = try {
                        activeCipher.decrypt(encBuf)
                    } catch (error: Exception) {
                        lastCloseReason = "control decrypt failed: ${error.message ?: error.javaClass.simpleName}"
                        Log.e(TAG, "airplay $lastCloseReason encrypted=${encBuf.size}", error)
                        break
                    }
                    encBuf = decrypted.rest
                    plaintext = decrypted.data
                }
                accumulated += plaintext
                val parsed = RtspMessage.parseMessages(accumulated)
                accumulated = parsed.rest
                for (request in parsed.messages) {
                    val cseq = request.headers["cseq"] ?: "-"
                    val path = request.path.lowercase()
                    val showInDebugOverlay =
                        !path.endsWith("/feedback") &&
                            !(request.method == "POST" && path.endsWith("/command"))
                    debugLog(
                        "airplay rx ${request.method} ${request.path} cseq=$cseq body=${request.body.size}",
                        showInDebugOverlay,
                    )
                    trace(
                        "airplay control rx headers=${request.headers} " +
                            "bodyHex=${request.body.toHex()}",
                    )
                    val response = try {
                        handle(request)
                    } catch (error: Exception) {
                        Log.e(
                            TAG,
                            "airplay handler failed ${request.method} ${request.path} cseq=$cseq",
                            error,
                        )
                        // 实机无 adb：异常此前只进 logcat，文件日志里看不到根因，此处补写
                        debugLog(
                            "airplay handler failed ${request.method} ${request.path} cseq=$cseq: " +
                                Log.getStackTraceString(error),
                        )
                        RtspMessage.Response(status = 500)
                    }
                    debugLog(
                        "airplay tx status=${response.status ?: 200} cseq=$cseq body=${response.body.size}",
                        showInDebugOverlay,
                    )
                    val wire = RtspMessage.buildResponse(request, response)
                    trace("airplay control tx wireHex=${wire.toHex()}")
                    output.write(cipher?.encrypt(wire) ?: wire)
                    if (cipher == null && pairVerify.controlKeys != null) {
                        val keys = pairVerify.controlKeys!!
                        cipher = ControlCipher(keys.readKey, keys.writeKey)
                        debugLog("airplay control encryption enabled")
                    }
                }
                output.flush()
                notifySetupResponseSent()
            }
        } catch (error: Exception) {
            lastCloseReason = "control I/O failed: ${error.message ?: error.javaClass.simpleName}"
            if (!closed.get()) Log.e(TAG, "airplay $lastCloseReason", error)
        } finally {
            debugLog("airplay control closing reason=$lastCloseReason activeStreams=$activeStreams")
            close()
        }
    }

    private fun handle(request: RtspMessage.Request): RtspMessage.Response {
        when (request.method) {
            "SETUP" -> {
                // M6d：记住播放会话 URI，断开时补发的 TEARDOWN 用它做请求目标。
                if (presentationUri == null) presentationUri = request.path
                return handleSetup(request)
            }
            "RECORD" -> {
                listener.onSessionActive(this)
                return RtspMessage.Response(status = 200)
            }
            "TEARDOWN" -> return handleTeardown(request)
        }

        val path = request.path.lowercase()
        return when {
            path.endsWith("/pair-setup") -> RtspMessage.Response(
                headers = mapOf("Content-Type" to PAIRING_CONTENT_TYPE),
                body = pairSetup.handle(request.body),
            )
            path.endsWith("/pair-verify") -> RtspMessage.Response(
                headers = mapOf("Content-Type" to PAIRING_CONTENT_TYPE),
                body = pairVerify.handle(request.body),
            )
            path.endsWith("/auth-setup") -> {
                val body = mfi?.let { MfiSapAuthSetup.handle(request.body, it) }
                if (body == null) RtspMessage.Response(status = 400)
                else RtspMessage.Response(headers = mapOf("Content-Type" to OCTET_CONTENT_TYPE), body = body)
            }
            path.endsWith("/info") -> {
                val info = AirPlayInfoPlist.build(config)
                if (request.body.isNotEmpty()) {
                    val requestInfo = try {
                        BplistCodec.decode(request.body).toString()
                    } catch (_: Exception) {
                        "<unparseable ${request.body.size} bytes>"
                    }
                    debugLog("airplay /info request=$requestInfo")
                }
                Log.i(
                    TAG,
                    "airplay /info features=${info["features"]} " +
                        "audioFormats=${(info["audioFormats"] as? List<*>)?.size ?: 0} " +
                        "audioLatencies=${(info["audioLatencies"] as? List<*>)?.size ?: 0}",
                )
                debugLog("airplay /info displays=${info["displays"]}")
                // M4h 对照：把 audioFormats 逐条写进导出日志，直接核对下行有没有带 OPUS 位（0x70000000）。
                val audioFormatDump = (info["audioFormats"] as? List<*>)?.joinToString(separator = " ") { raw ->
                    val entry = raw as? Map<*, *> ?: return@joinToString "?"
                    val out = (entry["audioOutputFormats"] as? Int)?.toUInt()?.toString(16) ?: "?"
                    val input = (entry["audioInputFormats"] as? Int)?.toUInt()?.toString(16)
                    val audioType = entry["audioType"]
                    "type=${entry["type"]}" + (if (audioType != null) "/$audioType" else "") +
                        " out=0x$out" + (if (input != null) " in=0x$input" else "")
                }
                debugLog("airplay /info audioFormats=$audioFormatDump")
                RtspMessage.Response(
                    headers = mapOf("Content-Type" to PLIST_CONTENT_TYPE),
                    body = BplistCodec.encode(info),
                )
            }
            request.method == "POST" && path.endsWith("/command") -> handleCommand(request)
            request.method == "POST" && path.endsWith("/feedback") -> {
                val body = media.onFeedback(this)
                if (body == null) {
                    RtspMessage.Response(status = 200)
                } else {
                    RtspMessage.Response(
                        headers = mapOf("Content-Type" to PLIST_CONTENT_TYPE),
                        body = BplistCodec.encode(body),
                    )
                }
            }
            else -> RtspMessage.Response(status = 200)
        }
    }

    private fun notifySetupResponseSent() {
        try {
            media.onSetupResponseSent(this)
        } catch (error: Exception) {
            Log.w(TAG, "airplay SETUP response callback failed", error)
        }
    }

    private fun debugLog(message: String, uiVisible: Boolean = true) {
        Log.i(TAG, message)
        if (!uiVisible) return
        try {
            listener.onDebugLog(message)
        } catch (error: Exception) {
            Log.w(TAG, "debug log callback failed", error)
        }
    }

    private fun trace(message: String) {
        try {
            listener.onDebugLog("TRACE $message")
        } catch (error: Exception) {
            Log.w(TAG, "trace log callback failed", error)
        }
    }

    private fun handleSetup(request: RtspMessage.Request): RtspMessage.Response {
        val dict = try {
            asMap(BplistCodec.decode(request.body)) ?: return RtspMessage.Response(status = 400)
        } catch (error: Exception) {
            Log.e(TAG, "airplay SETUP plist decode failed body=${request.body.size}", error)
            return RtspMessage.Response(status = 400)
        }
        debugLog("airplay SETUP keys=${dict.keys.sorted()}")
        val streams = dict["streams"] as? List<*>
        if (streams != null) {
            val responseStreams = handleStreams(streams)
            debugLog("airplay SETUP response streams=$responseStreams")
            val body = BplistCodec.encode(linkedMapOf("streams" to responseStreams))
            trace("airplay SETUP response bplistHex=${body.toHex()}")
            return RtspMessage.Response(headers = mapOf("Content-Type" to PLIST_CONTENT_TYPE), body = body)
        }

        val name = string(dict["name"])
        val deviceId = string(dict["deviceID"])
        val wifiMac = string(dict["macAddress"]).lowercase()
        val model = string(dict["model"])
        debugLog(AirPlayPeerDiagnostics.summary(model, string(dict["osVersion"]), string(dict["sourceVersion"])))
        if (deviceId.isNotEmpty()) deviceBtMac = deviceId
        if (name.isNotEmpty() || deviceId.isNotEmpty() || wifiMac.isNotEmpty()) {
            listener.onDeviceInfo(this, AirPlayDeviceInfo(name, deviceId, wifiMac, model))
        }

        val peerTimingPort = long(dict["timingPort"])?.toInt() ?: 0
        val response = linkedMapOf<String, Any?>(
            "timingPort" to openTiming(peerTimingPort),
            "eventPort" to openEvent(),
        )
        if (dict["keepAliveLowPower"] == true || dict["keepAliveLowPower"] == 1L) {
            response["keepAlivePort"] = openKeepAlive()
        }
        val requested = (dict["features"] as? List<*>).orEmpty().mapNotNull { it as? String }
        // 车机基础能力（只回自己真正支持的能力，不回显 iPhone 请求的能力！）
        // T16 实证：曾尝试 <基础能力 ∪ iPhone 请求>，一旦回显 altScreen/uiContext，
        // iPhone 随即在 /info 里改问 altScreenURLs/uiContextURLs；而车机 /info 只提供
        // 一个 type=110 display、无 cluster/altScreen/uiContext 支撑 → iPhone 在 RECORD 后
        // 立即发空 TEARDOWN 并 peer EOF，画面完全出不来。故必须回退为仅基础能力。
        // 保留下方 requested/enabledFeatures 双列表日志（既是诊断、也兼作 build 标识）。
        val features = linkedSetOf<String>()
        if (config.hevc) features.add("hevc")
        features.add("iAPChannel")
        features.add("viewAreas")
        if (config.cluster != null) features.add("altScreen")
        response["enabledFeatures"] = features.toList()
        debugLog(
            "airplay SETUP enabledFeatures=${features.joinToString(",")} " +
                "requested=${requested.joinToString(",")}",
        )
        return RtspMessage.Response(
            headers = mapOf("Content-Type" to PLIST_CONTENT_TYPE),
            body = BplistCodec.encode(response),
        )
    }

    private fun handleStreams(streams: List<*>): List<Any?> {
        val result = arrayListOf<Any?>()
        for (entry in streams) {
            val stream = asMap(entry) ?: continue
            val type = long(stream["type"])?.toInt() ?: continue
            debugLog("airplay SETUP stream type=$type payload=$stream")
            when (type) {
                STREAM_TYPE_MAIN_SCREEN, STREAM_TYPE_ALT_SCREEN -> {
                    val port = media.onScreen(this, type, stream)
                    debugLog("airplay screen stream type=$type dataPort=${port ?: "rejected"}")
                    if (port != null) {
                        activeStreams.add(type)
                        result.add(linkedMapOf("type" to type, "dataPort" to port))
                    }
                }
                STREAM_TYPE_MAIN_AUDIO, STREAM_TYPE_ALT_AUDIO, STREAM_TYPE_MAIN_HIGH_AUDIO -> {
                    val audioKind = stream["audioType"]?.toString()?.lowercase().orEmpty()
                    if (audioKind == "speechrecognition") {
                        siriAudioSeen = true
                    }
                    // 任一音频 SETUP 到来即视为音频已恢复，撤销 Siri 后无声看门狗。
                    siriWatchdogGeneration.incrementAndGet()
                    val streamResponse = media.onAudio(this, type, stream)
                    debugLog(
                        "airplay audio stream type=$type accepted=${streamResponse != null} " +
                            "dataPort=${streamResponse?.get("dataPort") ?: "none"} " +
                            "controlPort=${streamResponse?.get("controlPort") ?: "none"}",
                    )
                    if (streamResponse != null) {
                        activeStreams.add(type)
                        activeAudioKinds[type] = audioKind
                        result.add(streamResponse)
                    }
                }
                STREAM_TYPE_DATA -> {
                    val streamResponse = media.onDataStream(this, stream)
                    debugLog(
                        "airplay data stream type=$type accepted=${streamResponse != null} " +
                            "dataPort=${streamResponse?.get("dataPort") ?: "none"}",
                    )
                    if (streamResponse != null) {
                        activeStreams.add(type)
                        result.add(streamResponse)
                    }
                }
                else -> debugLog("airplay unsupported stream type=$type")
            }
        }
        return result
    }

    private fun handleCommand(request: RtspMessage.Request): RtspMessage.Response {
        val body = try {
            asMap(BplistCodec.decode(request.body)) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap<String, Any?>()
        }
        val type = string(body["type"])
        val params = asMap(body["params"]) ?: emptyMap()
        debugLog("airplay command type=$type keys=${params.keys.sorted()}")
        if (type == "requestUI") listener.onHostUiRequested(this)
        listener.onCommand(this, type, params)
        val response = RtspMessage.Response(status = 200)
        debugLog("airplay command response type=$type status=200 body=${response.body.size} params=${params.keys.sorted()}")
        return response
    }

    private fun handleTeardown(request: RtspMessage.Request): RtspMessage.Response {
        var decodedBody: Any? = null
        val types = try {
            val decoded = BplistCodec.decode(request.body)
            decodedBody = decoded
            val dict = asMap(decoded)
            (dict?.get("streams") as? List<*>)
                ?.mapNotNull { entry -> long(asMap(entry)?.get("type"))?.toInt() }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        debugLog(
            "airplay TEARDOWN types=$types activeBefore=$activeStreams " +
                "body=${request.body.size} bytes payload=$decodedBody",
        )
        trace("airplay TEARDOWN raw${request.body.size}Hex=${request.body.toHex()}")

        if (types.isEmpty()) {
            activeStreams.toList().forEach { media.onTeardown(this, it) }
            activeStreams.clear()
            activeAudioKinds.clear()
        } else {
            val removed = types.filter { activeStreams.remove(it) }
            removed.forEach { type -> media.onTeardown(this, type) }
            removed.forEach { type -> activeAudioKinds.remove(type) }
            maybeArmSiriRestoreWatchdog(removed)
        }
        return RtspMessage.Response(status = 200)
    }

    /**
     * vc84：Siri 后无声看门狗装弹。本会话出现过 speechrecognition 音频流（Siri 已用过）
     * 且本次 TEARDOWN 移除的正是音频流、会话内已无 media 音频流时，启动
     * [SIRI_AUDIO_RESTORE_TIMEOUT_MS] 定时检查；期间任一音频 SETUP（generation 递增）或
     * 会话关闭即失效；到期仍无 media 音频 → onSiriAudioStall（见字段注释）。
     */
    private fun maybeArmSiriRestoreWatchdog(removedTypes: List<Int>) {
        if (!siriAudioSeen) return
        if (removedTypes.none { it in AUDIO_STREAM_TYPES }) return
        // vc86：101/default 等控制音频流常残存且反复 flap，不能作为「音频还在」判据；
        // 只有 media 音频仍活跃才意味着音乐在放。
        if (activeAudioKinds.containsValue("media")) return
        val generation = siriWatchdogGeneration.incrementAndGet()
        debugLog(
            "airplay Siri audio torn down; media-restore watchdog armed " +
                "${SIRI_AUDIO_RESTORE_TIMEOUT_MS}ms",
        )
        Thread(
            {
                try {
                    Thread.sleep(SIRI_AUDIO_RESTORE_TIMEOUT_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (closed.get() || generation != siriWatchdogGeneration.get()) return@Thread
                if (activeAudioKinds.containsValue("media")) return@Thread
                // vc187（t3 实测 20261025）：视频流仍在就**不许拿整条会话陪葬**。
                // 实证链条：Siri 四连发 → 第 4 次后 10s 无 audio SETUP → 本看门狗 session.close()
                // → NCM 入向端点随之楔死 3s→20s → Ipv6NcmBridge 判 usb wedge → wired teardown
                // 全链断（t3 单份日志 48 次 wired teardown、3 次完整楔死）。同期视频仍在收包
                //（21:22:24 video packets=5）＝链路健康，代价却是「音频慢」被升级成「连不上」。
                // 只给语音提示让用户自己处理，不动链路。
                if (activeStreams.contains(STREAM_TYPE_MAIN_SCREEN)) {
                    debugLog(
                        "airplay Siri audio stall: no audio SETUP after " +
                            "${SIRI_AUDIO_RESTORE_TIMEOUT_MS}ms; video stream still active " +
                            "— session kept (teardown costs the whole link)",
                    )
                    return@Thread
                }
                debugLog(
                    "airplay Siri audio stall: no audio SETUP after " +
                        "${SIRI_AUDIO_RESTORE_TIMEOUT_MS}ms; requesting session recovery",
                )
                try {
                    listener.onSiriAudioStall(this)
                } catch (error: Exception) {
                    Log.w(TAG, "siri audio stall callback failed", error)
                }
            },
            "airplay-siri-restore",
        ).apply { isDaemon = true; start() }
    }

    private fun openTiming(peerPort: Int): Int {
        val port = ntp.listen()
        if (peerPort > 0) peerAddress?.let { ntp.start(it, peerPort) }
        return port
    }

    private fun openKeepAlive(): Int {
        // vc187：原绑 `InetAddress.getByName("::")`，在 IPv6 link-local 链路（NCM）上抛
        // `Can't bind to a link-local address without a scope id`；改用通配 InetSocketAddress(0)。
        val socket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(0))
            }
        } catch (error: Exception) {
            debugLog("airplay keepalive 端口通配绑定失败，回退 IPv4: ${error.message}")
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0))
            }
        }
        keepAliveSocket = socket
        keepAliveThread = Thread({ runKeepAlive(socket) }, "airplay-keepalive").apply {
            isDaemon = true
            start()
        }
        return socket.localPort
    }

    private fun runKeepAlive(socket: DatagramSocket) {
        val buffer = ByteArray(512)
        while (!closed.get()) {
            try {
                socket.receive(DatagramPacket(buffer, buffer.size))
            } catch (_: Exception) {
                if (closed.get()) return
            }
        }
    }

    private fun openEvent(): Int {
        // vc187：同 openKeepAlive——不指名 `::`，用通配地址（双栈行为不变，避免 scope id 报错）。
        val server = try {
            ServerSocket(0, 50)
        } catch (error: Exception) {
            debugLog("airplay event 端口通配绑定失败，回退 IPv4: ${error.message}")
            ServerSocket(0, 50, InetAddress.getByName("0.0.0.0"))
        }
        eventServer = server
        spawnEvent("airplay-event-accept") { acceptEvent(server) }
        return server.localPort
    }

    /**
     * M6d：best-effort 会话级 TEARDOWN。iOS 收到后才会把"车机主动结束了会话"当真，
     * 从而退出 CarPlay 界面（并更倾向于放开热点）。发不出去/对方不理会都不影响拆除流程。
     */
    private fun sendTeardownBestEffort() {
        val output = controlOutput ?: return
        try {
            controlCseq++
            val uri = presentationUri ?: "rtsp://$host/"
            val head = "TEARDOWN $uri RTSP/1.0\r\n" +
                "Content-Type: $PLIST_CONTENT_TYPE\r\n" +
                "Content-Length: 0\r\n" +
                "CSeq: $controlCseq\r\n\r\n"
            val wire = head.toByteArray(Charsets.US_ASCII)
            output.write(cipher?.encrypt(wire) ?: wire)
            output.flush()
            debugLog("airplay control sent TEARDOWN to phone (best effort) cseq=$controlCseq uri=$uri")
        } catch (error: Exception) {
            debugLog("airplay control TEARDOWN send failed: ${error.message}")
        }
    }

    private fun teardown() {
        ntp.close()
        safeClose(keepAliveSocket)
        keepAliveSocket = null
        keepAliveThread?.interrupt()
        keepAliveThread = null
        safeClose(eventServer)
        eventServer = null
        safeClose(eventSocket)
        eventSocket = null
        eventCipher = null
        eventThreads.forEach { it.interrupt() }
        eventThreads.clear()
    }

    private fun acceptEvent(server: ServerSocket) {
        try {
            val socket = server.accept()
            socket.setSoLinger(true, 0)
            debugLog("airplay event connection accepted from ${socket.remoteSocketAddress}")
            // vc77：触摸/控制通道 TCP_NODELAY（对齐上游 0.2.13 触摸修复；小包即时下发，
            // Nagle 聚包会叠加触摸2frame延迟；AirPlayTcpServer accept 侧已设，此处补齐 event 侧）
            socket.tcpNoDelay = true
            socket.keepAlive = true
            eventSocket = socket
            val shared = pairVerify.shared
            if (shared == null) {
                Log.e(TAG, "airplay event rejected: pair-verify shared secret unavailable")
                safeClose(socket)
                close()
                return
            }
            val writeKey = AirPlayCrypto.hkdfSha512(
                shared,
                "Events-Salt".asciiBytes(),
                "Events-Write-Encryption-Key".asciiBytes(),
                32,
            )
            val readKey = AirPlayCrypto.hkdfSha512(
                shared,
                "Events-Salt".asciiBytes(),
                "Events-Read-Encryption-Key".asciiBytes(),
                32,
            )
            eventCipher = ControlCipher(readKey, writeKey)
            synchronized(eventWriteLock) {
                sendPendingNightModeLocked()
            }
            runEventRead(socket)
        } catch (error: Exception) {
            if (!closed.get()) {
                Log.e(TAG, "airplay event accept failed", error)
                close()
            }
        }
    }

    private fun runEventRead(socket: Socket) {
        // vc87（fe6t5）掉线取证：fe6t3 日志实锤 FE-6 会话死亡首发点是 event 连接
        // 被对端关闭（18:51:15.674，恰为 Siri 结束后整 60s，随后才连锁 wedge 误判）。
        // 记录最后入站时刻，区分「对端 EOF/空闲多久」vs「本地异常」。
        var lastInboundAt = System.currentTimeMillis()
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            var encrypted = ByteArray(0)
            var plaintext = ByteArray(0)
            val buffer = ByteArray(READ_CHUNK_BYTES)
            while (!closed.get()) {
                val count = input.read(buffer)
                if (count < 0) {
                    debugLog(
                        "airplay event connection closed reason=peer-eof " +
                            "idleMs=${System.currentTimeMillis() - lastInboundAt}",
                    )
                    break
                }
                lastInboundAt = System.currentTimeMillis()
                val cipher = eventCipher ?: break
                encrypted += buffer.copyOf(count)
                val decrypted = try {
                    cipher.decrypt(encrypted)
                } catch (error: Exception) {
                    Log.e(TAG, "airplay event decrypt failed encrypted=${encrypted.size}", error)
                    break
                }
                encrypted = decrypted.rest
                plaintext += decrypted.data
                val parsed = RtspMessage.parseMessages(plaintext)
                plaintext = parsed.rest
                for (message in parsed.messages) {
                    if (message.method.startsWith("RTSP/") || message.method.startsWith("HTTP/")) continue
                    debugLog(
                        "airplay event rx ${message.method} ${message.path} cseq=${message.headers["cseq"] ?: "-"} body=${message.body.size}",
                    )
                    val response = RtspMessage.buildResponse(message, RtspMessage.Response(status = 200))
                    trace(
                        "airplay event rx headers=${message.headers} " +
                            "bodyHex=${message.body.toHex()}",
                    )
                    trace("airplay event tx wireHex=${response.toHex()}")
                    synchronized(eventWriteLock) {
                        output.write(cipher.encrypt(response))
                        output.flush()
                    }
                }
            }
        } catch (error: Exception) {
            if (!closed.get()) Log.e(TAG, "airplay event read failed", error)
        } finally {
            debugLog("airplay event connection closed idleMs=${System.currentTimeMillis() - lastInboundAt}")
            if (eventSocket === socket) eventSocket = null
            eventCipher = null
            safeClose(socket)
            if (!closed.get()) close()
        }
    }

    private fun spawnEvent(name: String, body: () -> Unit) {
        val thread = Thread(body, name).apply { isDaemon = true }
        eventThreads.add(thread)
        thread.start()
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val PLIST_CONTENT_TYPE = "application/x-apple-binary-plist"
        const val PAIRING_CONTENT_TYPE = "application/pairing+tlv8"
        const val OCTET_CONTENT_TYPE = "application/octet-stream"

        const val STREAM_TYPE_MAIN_SCREEN = 110
        const val STREAM_TYPE_ALT_SCREEN = 111
        const val STREAM_TYPE_MAIN_AUDIO = 100
        const val STREAM_TYPE_ALT_AUDIO = 101
        const val STREAM_TYPE_MAIN_HIGH_AUDIO = 102
        const val STREAM_TYPE_DATA = 130

        private val AUDIO_STREAM_TYPES =
            setOf(STREAM_TYPE_MAIN_AUDIO, STREAM_TYPE_ALT_AUDIO, STREAM_TYPE_MAIN_HIGH_AUDIO)

        // vc84：Siri 结束后等 iPhone 重发媒体音频 SETUP 的窗口。fe6t2 实测 Siri 结束后
        // iPhone 侧若健康会在秒级重来；10s 足够覆盖慢协商，又不至于让静音悬置太久。
        // vc187：10s → 20s。t3（KK/NCM）上语音流压力会把整条链路拖慢（video 收包间隔
        // readMaxMs 已达 1256ms），10s 会把「音频恢复慢」误判成「音频死了」。配合上方
        // 「视频仍在则不拆会话」闸门，超时只影响真正无视频的音频-only 场景。
        const val SIRI_AUDIO_RESTORE_TIMEOUT_MS = 20_000L

        const val READ_CHUNK_BYTES = 16 * 1024
        const val EVENT_READY_POLL_MILLIS = 25L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

internal fun safeClose(closeable: Closeable?) {
    try {
        closeable?.close()
    } catch (_: Exception) {
        // Best-effort close.
    }
}

// API16 适配：Socket/ServerSocket/DatagramSocket 在 API 19 之前未实现 Closeable
// 接口（仅 AutoCloseable 于 API 19+），经 Closeable 强转会抛 ClassCastException，
// 故按具体类型重载，直接调用 close()。
internal fun safeClose(socket: Socket?) {
    try {
        socket?.close()
    } catch (_: Exception) {
        // Best-effort close.
    }
}

internal fun safeClose(server: ServerSocket?) {
    try {
        server?.close()
    } catch (_: Exception) {
        // Best-effort close.
    }
}

internal fun safeClose(datagramSocket: DatagramSocket?) {
    try {
        datagramSocket?.close()
    } catch (_: Exception) {
        // Best-effort close.
    }
}

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun asMap(value: Any?): Map<String, Any?>? {
    val map = value as? Map<*, *> ?: return null
    val result = LinkedHashMap<String, Any?>(map.size)
    for ((key, entry) in map) result[key.toString()] = entry
    return result
}

private fun string(value: Any?): String = value as? String ?: ""

private fun long(value: Any?): Long? = (value as? Number)?.toLong()
