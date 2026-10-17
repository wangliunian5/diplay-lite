package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.message.Iap2CarPlayMessages
import com.shilapi.xcertplay.iap2.message.Iap2ControlMessages
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import java.net.Inet6Address
import java.net.InetAddress
import kotlin.math.min

/**
 * The wired LIVI control sequence after a CSM channel is ready:
 * Identification, MFi, power announcement, five update subscriptions, then CarPlay availability.
 *
 * The caller retains ownership of [session]. While [run] is active it is the only receiver and
 * forwards each non-availability CSM frame to [onIncoming]; it neither opens NCM nor implements
 * an AirPlay receiver.
 */
class Iap2WiredControlClient(
    private val session: Iap2Session,
    private val mfi: Iap2MfiAuthenticationClient,
) {
    fun run(
        identification: Iap2IdentificationConfig,
        endpoint: Iap2WiredCarPlayEndpoint,
        availableCurrentMilliAmps: Int,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        locationProvider: Iap2LocationProvider? = null,
        onIncoming: (Iap2Frame) -> Unit = {},
        onProgress: (String) -> Unit = {},
        onStage: (Iap2WiredControlStage) -> Unit = {},
    ): Iap2WiredControlResult {
        require(availableCurrentMilliAmps in 0..0xffff) {
            "availableCurrentMilliAmps must be in 0..65535"
        }
        require(timeoutMillis == NO_TIMEOUT_MILLIS || timeoutMillis in 1..MAX_TIMEOUT_MILLIS) {
            "timeoutMillis must be in 1..$MAX_TIMEOUT_MILLIS or NO_TIMEOUT_MILLIS"
        }

        val deadlineNanos = Iap2ControlDeadline(timeoutMillis)
        Iap2IdentificationClient(session).identify(identification, requireRemaining(deadlineNanos))
        onProgress("iap2 identification accepted")
        var stage = Iap2WiredControlStage.IDENTIFIED
        mfi.run(session, requireRemaining(deadlineNanos), onProgress)
        stage = Iap2WiredControlStage.AUTHENTICATED
        onProgress("iap2 authentication accepted")
        deadlineNanos.authenticated()

        send(powerSourceUpdate(availableCurrentMilliAmps), deadlineNanos)
        for (subscription in subscriptions()) send(subscription, deadlineNanos)
        stage = Iap2WiredControlStage.SUBSCRIBED
        onProgress("iap2 power/subscriptions sent")

        var forwardedFrames = 0
        var carPlayStartSessions = 0
        // vc93：0x4301 重建后重发预算（对齐无线模式多次重发思路）。iPhone 偶发忽略
        // 首个 0x4301（TS7 083438：重建后 0x4301 无响应），有界重发给 iPhone 二次
        // 响应窗口；建立期看门狗（controller 侧 8s）内按 2s 间隔补发。
        var carPlayStartResends = 0
        var lastCarPlayStartAtNanos = 0L
        var locationActive = false
        var locationSentLogged = false
        try {
            while (true) {
                val remaining = remainingMillis(deadlineNanos)
                if (remaining == 0L) {
                    return Iap2WiredControlResult(Iap2WiredControlTerminal.TIMED_OUT, stage, forwardedFrames, carPlayStartSessions)
                }
                if (locationActive && sendLatestLocation(locationProvider, deadlineNanos) && !locationSentLogged) {
                    locationSentLogged = true
                    onProgress("iap2 tx=0xfffb location-information")
                }
                // vc93：0x4301 已发且零流帧、重发预算未耗尽 → recv 短轮询，让重发有节拍。
                val startResendPending = stage == Iap2WiredControlStage.CARPLAY_START_SENT &&
                    forwardedFrames == 0 && carPlayStartResends < MAX_CARPLAY_START_RESENDS
                val pollTimeout = when {
                    locationActive -> min(remaining, LOCATION_POLL_INTERVAL_MILLIS)
                    startResendPending -> min(remaining, CARPLAY_START_RESEND_INTERVAL_MILLIS)
                    else -> remaining
                }
                val incoming = session.recv(pollTimeout)
                if (incoming == null) {
                    if (session.isClosed) {
                        return Iap2WiredControlResult(
                            Iap2WiredControlTerminal.CHANNEL_CLOSED,
                            stage,
                            forwardedFrames,
                            carPlayStartSessions,
                        )
                    }
                    if (remainingMillis(deadlineNanos) == 0L) {
                        return Iap2WiredControlResult(
                            Iap2WiredControlTerminal.TIMED_OUT,
                            stage,
                            forwardedFrames,
                            carPlayStartSessions,
                        )
                    }
                } else {
                    when (incoming.messageId) {
                        CARPLAY_AVAILABILITY -> {
                            onProgress("iap2 rx=0x4300 carplay-availability")
                            onProgress(carPlayAvailabilitySummary(incoming.payload))
                            // LIVI sends its wired answer on every availability notification; do not gate it on
                            // the phone's advertised availability boolean.
                            send(carPlayStartSession(endpoint), deadlineNanos)
                            stage = Iap2WiredControlStage.CARPLAY_START_SENT
                            onStage(stage)
                            carPlayStartSessions++
                            lastCarPlayStartAtNanos = System.nanoTime()
                            onProgress("iap2 tx=0x4301 carplay-start-session")
                        }

                        Iap2LocationMessages.START_LOCATION_INFORMATION -> {
                            onProgress("iap2 rx=0xfffa start-location-information")
                            locationActive = startLocationUpdates(locationProvider, onProgress)
                            locationSentLogged = false
                            if (locationActive && sendLatestLocation(locationProvider, deadlineNanos)) {
                                locationSentLogged = true
                                onProgress("iap2 tx=0xfffb location-information")
                            }
                        }

                        Iap2LocationMessages.STOP_LOCATION_INFORMATION -> {
                            onProgress("iap2 rx=0xfffc stop-location-information")
                            locationActive = false
                            locationSentLogged = false
                            locationProvider?.stop()
                        }

                        else -> {
                            onProgress("iap2 rx=0x${incoming.messageId.toString(16).padStart(4, '0')}")
                            onIncoming(incoming)
                            forwardedFrames++
                        }
                    }
                }
                // vc94：重发节拍检查移出 recv-null 分支——TS7 实锤形态是 iAP2 链路活着且心跳常驻
                //（CSM 帧不断浮出，recv 永不返回 null），vc93 把检查放在 null 分支内会被心跳
                // 饿死、一次都不触发。移到每轮循环尾按 elapsed 判定后，链路静默与心跳常驻两种
                // 形态均正确；有流帧后 forwardedFrames>0，startResendPending 自然转 false 停发。
                if (startResendPending &&
                    elapsedSinceNanos(lastCarPlayStartAtNanos) >= CARPLAY_START_RESEND_INTERVAL_MILLIS
                ) {
                    send(carPlayStartSession(endpoint), deadlineNanos)
                    carPlayStartResends += 1
                    lastCarPlayStartAtNanos = System.nanoTime()
                    onProgress(
                        "iap2 tx=0x4301 carplay-start-session " +
                            "resend=$carPlayStartResends/$MAX_CARPLAY_START_RESENDS",
                    )
                }
            }
        } finally {
            locationProvider?.stop()
        }
    }

    private fun send(frame: Iap2Frame, deadlineNanos: Iap2ControlDeadline) {
        session.send(frame, requireRemaining(deadlineNanos))
    }

    private fun sendLatestLocation(
        provider: Iap2LocationProvider?,
        deadlineNanos: Iap2ControlDeadline,
    ): Boolean {
        val sentence = provider?.latestNmea() ?: return false
        session.send(
            Iap2LocationMessages.locationInformation(sentence),
            requireRemaining(deadlineNanos),
        )
        return true
    }

    private fun startLocationUpdates(
        provider: Iap2LocationProvider?,
        onProgress: (String) -> Unit,
    ): Boolean {
        if (provider == null) return false
        return try {
            provider.start().also { started ->
                if (!started) onProgress("iap2 location provider did not start")
            }
        } catch (error: Exception) {
            onProgress("iap2 location provider start failed: ${error.message}")
            false
        }
    }

    companion object {
        const val NO_TIMEOUT_MILLIS = Long.MAX_VALUE
        private const val CARPLAY_AVAILABILITY = 0x4300
        private const val CARPLAY_START_SESSION = 0x4301
        private const val LOCATION_POLL_INTERVAL_MILLIS = 1_000L
        // vc93：0x4301 重发预算——2s 间隔、最多 6 次（覆盖 controller 侧建立期看门狗
        // 8s 窗口内约 3 次补发，重建代际内给足 iPhone 二次响应机会）。
        private const val CARPLAY_START_RESEND_INTERVAL_MILLIS = 2_000L
        private const val MAX_CARPLAY_START_RESENDS = 6
        private const val DEFAULT_TIMEOUT_MILLIS = 60_000L
        private const val MAX_TIMEOUT_MILLIS = 24 * 60 * 60 * 1_000L
        private const val MAX_RECV_TIMEOUT_MILLIS = 5 * 60 * 1_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        /** vc93：距 [startNanos] 的毫秒数；未发送过（0）返回 MAX_VALUE 阻止立即重发。 */
        private fun elapsedSinceNanos(startNanos: Long): Long =
            if (startNanos == 0L) {
                Long.MAX_VALUE
            } else {
                (System.nanoTime() - startNanos) / NANOS_PER_MILLISECOND
            }

        /** Exact LIVI wired PowerSourceUpdate encoding: current and the charge-if-powered flag. */
        fun powerSourceUpdate(availableCurrentMilliAmps: Int): Iap2Frame =
            Iap2ControlMessages.powerSourceUpdate(availableCurrentMilliAmps, charging = true)

        /** Exact five subscription requests emitted by LIVI's wired bring-up. */
        fun subscriptions(): List<Iap2Frame> = Iap2ControlMessages.subscriptions()

        /** Builds the wired-only CarPlayStartSession message; no NCM or AirPlay socket is opened. */
        fun carPlayStartSession(endpoint: Iap2WiredCarPlayEndpoint): Iap2Frame =
            Iap2CarPlayMessages.startSession(
                wiredIpv6Addresses = endpoint.ipv6Addresses,
                airPlayPort = endpoint.airPlayPort,
                deviceIdentifier = endpoint.deviceIdentifier,
                publicKey = endpoint.publicKey,
                sourceVersion = endpoint.sourceVersion,
            )

        fun carPlayAvailabilitySummary(payload: ByteArray): String {
            return try {
                val availability = Iap2CarPlayMessages.availability(payload).wired
                val available = availability?.available
                val transport = availability?.identifier
                "iap2 4300 wiredAvailable=$available usbTransport=${transport ?: "none"}"
            } catch (error: RuntimeException) {
                "iap2 4300 decode failed: ${error.message}"
            }
        }

        private fun requireRemaining(deadlineNanos: Iap2ControlDeadline): Long = remainingMillis(deadlineNanos).also {
            if (it == 0L) throw IphoneUsbException.TimedOut("Timed out during wired iAP2 control bring-up")
        }

        private fun remainingMillis(deadlineNanos: Iap2ControlDeadline): Long = deadlineNanos.remainingMillis()

    }
}

/** A configured wired AirPlay endpoint; configuration does not claim that either service is live. */
class Iap2WiredCarPlayEndpoint(
    ipv6Addresses: List<String>,
    val airPlayPort: Int,
    val publicKey: String,
    val sourceVersion: String,
    val deviceIdentifier: String? = null,
) {
    /** A stable copy so a caller cannot mutate a validated endpoint before it is encoded. */
    val ipv6Addresses: List<String> = ipv6Addresses.toList()

    init {
        require(ipv6Addresses.isNotEmpty()) { "At least one wired IPv6 address is required" }
        require(ipv6Addresses.all(::isIpv6Literal)) { "Every wired address must be an IPv6 text literal" }
        require(airPlayPort in 1..65535) { "airPlayPort must be in 1..65535" }
        require(publicKey.isNotEmpty()) { "publicKey is required and must not be empty" }
        require(sourceVersion.isNotEmpty()) { "sourceVersion is required and must not be empty" }
        require('\u0000' !in publicKey) { "publicKey must not contain U+0000" }
        require('\u0000' !in sourceVersion) { "sourceVersion must not contain U+0000" }
        deviceIdentifier?.let {
            require(it.isNotEmpty()) { "deviceIdentifier is optional, but must not be empty when provided" }
            require('\u0000' !in it) { "deviceIdentifier must not contain U+0000" }
        }
    }

    private companion object {
        fun isIpv6Literal(value: String): Boolean {
            if (value.contains('%') || '\u0000' in value || !value.contains(':')) return false
            return try {
                InetAddress.getByName(value) is Inet6Address
            } catch (_: Exception) {
                false
            }
        }
    }
}

enum class Iap2WiredControlStage {
    IDENTIFIED,
    AUTHENTICATED,
    SUBSCRIBED,
    CARPLAY_START_SENT,
}

enum class Iap2WiredControlTerminal { CHANNEL_CLOSED, TIMED_OUT }

/** End state of the control loop only; it is not evidence of NCM or AirPlay availability. */
data class Iap2WiredControlResult(
    val terminal: Iap2WiredControlTerminal,
    val stage: Iap2WiredControlStage,
    val forwardedFrames: Int,
    val carPlayStartSessionsSent: Int,
)
