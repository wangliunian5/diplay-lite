package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.message.Iap2CarPlayMessages
import com.shilapi.xcertplay.iap2.message.Iap2ControlMessages
import com.shilapi.xcertplay.iap2.message.Iap2WirelessMessages
import com.shilapi.xcertplay.iap2.message.Iap2WirelessSessionParameters
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import kotlin.math.min

/**
 * The wireless LIVI control sequence after a CSM channel is ready:
 * Identification, MFi, the five update subscriptions, then Wi-Fi credentials and the
 * 0x4E0D/0x4E0E Wireless CarPlay transport notifications.
 *
 * The caller retains ownership of [session]. While [run] is active it is the only receiver and
 * forwards each non-control CSM frame to [onIncoming].
 */
class Iap2WirelessControlClient(
    private val session: Iap2Session,
    private val mfi: Iap2MfiAuthenticationClient,
) {
    fun run(
        identification: Iap2IdentificationConfig,
        endpoint: Iap2WirelessCarPlayEndpoint,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        locationProvider: Iap2LocationProvider? = null,
        onReady: () -> Unit = {},
        onIncoming: (Iap2Frame) -> Unit = {},
        onProgress: (String) -> Unit = {},
        phoneOnAirPlay: () -> Boolean = { false },
        // [RELAY-TEST] 1.0.28-中继test 实验参数：(假SSID, 随机密码)。null = 实验关闭。
        relayTestFakeCredential: Pair<String, String>? = null,
        // vc83 中继踢出（攻「iPhone 提前连到中继 WiFi 后 CarPlay 永远连不上」）：
        // iPhone 已在目标网络时 0x5703 不触发 join 事件（no-op），重发全废，看门狗判死 →
        // 重试死循环。中继模式传 >0：真凭证重发达到该次数（2 次≈10s 处）仍 0 TCP 即进入
        // 踢出阶段（假 0x5703 → 1.5s → 真凭证恢复并持续重发 12×5s）。
        // vc80 的 8 次（40s）+10s 恢复实证太晚：踢出后凭证真空期 iPhone 无凭证可回，
        // 用户在恢复前就放弃（082530 日志 tap7：+47s 踢出、+53s 用户断开，恢复永远没到）。
        // 踢出越早、踢出后立即补真凭证，给 iPhone 留足重扫描入网窗口。-1/0 = 实验旧行为。
        relayKickAfterResends: Int = -1,
    ): Iap2WirelessControlResult {
        require(identification.wireless != null || identification.usbWirelessUpgradeSsid != null) {
            "Wireless control requires wireless (Bluetooth) or usbWirelessUpgradeSsid (USB bootstrap) transport"
        }
        require(timeoutMillis == NO_TIMEOUT_MILLIS || timeoutMillis in 1..MAX_TIMEOUT_MILLIS) {
            "timeoutMillis must be in 1..$MAX_TIMEOUT_MILLIS or NO_TIMEOUT_MILLIS"
        }

        // 这个截止时间只约束「建链窗口」：识别/鉴权/订阅必须在此窗口内完成，卡住就 TIMED_OUT。
        // 一旦 onReady()（订阅已发出，iAP2 进入稳态）就放开为无超时 —— 见下方 onReady() 之后。
        var deadlineNanos = if (timeoutMillis == NO_TIMEOUT_MILLIS) {
            Long.MAX_VALUE
        } else {
            deadlineAfter(timeoutMillis)
        }
        Iap2IdentificationClient(session).identify(identification, requireRemaining(deadlineNanos))
        onProgress("iap2 identification accepted")
        var stage = Iap2WirelessControlStage.IDENTIFIED

        mfi.run(session, requireRemaining(deadlineNanos), onProgress)
        stage = Iap2WirelessControlStage.AUTHENTICATED
        onProgress("iap2 authentication accepted")

        for (subscription in Iap2ControlMessages.subscriptions()) {
            send(subscription, deadlineNanos)
        }
        stage = Iap2WirelessControlStage.SUBSCRIBED
        onProgress("iap2 subscriptions sent")
        onReady()
        // M6g：进入稳态后不再设总超时。iAP2 是会话控制面（Now Playing / 导航 / 电话 / Siri）
        // 的唯一入口；控制环一退，就没人再读 RFCOMM，控制面全哑而音视频照常
        // （T43 实测：5 分钟到点后 `iap2 rx` 归零、`video/audio stats` 仍正常）。
        // 真交接的隧道路径本来就传 NO_TIMEOUT_MILLIS，这里让「蓝牙承载 iAP2」的降级态行为一致。
        // 退出仍由 socket 关闭（session.isClosed → CHANNEL_CLOSED）驱动，不会泄漏线程。
        deadlineNanos = Long.MAX_VALUE

        var forwardedFrames = 0
        var wifiConfigurationsSent = 0
        var carPlayStartSessionsSent = 0
        var preTransportWiFiConfigurationsSent = 0
        var postTransportWiFiConfigurationsSent = 0
        var transportNotificationSeen = false
        var wirelessCarPlayAvailableSeen = false
        var locationActive = false
        var locationSentLogged = false
        // T18 日志：0x4301 只发两次就停，iPhone 直到 55s 后才入网——期间没有第二次机会，
        // 用户只能手动断链重连来"再发一次"。这里在 iPhone 接入前周期性重发凭据 + 启动会话。
        var nextCredentialResendNanos: Long? = null
        var credentialResendsSent = 0
        // 1.0.29（AC8227 倒车闪屏，移植上游 #229 FirstTcpWatchdog 精神）：
        // 稳态下（onReady 后 deadline=∞）控制环 recv(∞) 阻塞，若 iPhone 端会话死亡
        // （倒车 teardown / 离网后不再回来）而 RFCOMM 仍活着，这里会永远挂住、永不收敛。
        // 看门狗：曾有 AirPlay TCP 连接 → 断流超过 WATCHDOG 时长 → 返回 AIRPLAY_STALLED，
        // 让上层走整代重启（scheduleWirelessRetry），而不是用户手点重连。
        var everOnAirPlay = false
        var airPlayLostAtNanos: Long? = null
        // [RELAY-TEST] 实验状态机：0=阶段1(真凭证重发60s) 1=待发假凭证 2=假凭证已发待恢复 3=已恢复真凭证 -1=实验取消/关闭。
        // 实验链：时序倒挂（iPhone 先连中继 AP）时 iOS 不产生 join 事件、状态机死锁 →
        // 阶段1 重发用尽后发假凭证（真实存在 SSID+随机密码）诱导 iPhone 切出当前 AP →
        // 10s 后恢复真凭证，使「真凭证落在 iPhone 离网窗口内」，回连即产生 join 事件。
        var relayTestPhase = if (relayTestFakeCredential != null) 0 else -1
        while (true) {
                val remaining = remainingMillis(deadlineNanos)
                if (remaining == 0L) {
                    return Iap2WirelessControlResult(
                        Iap2WirelessControlTerminal.TIMED_OUT,
                        stage,
                        forwardedFrames,
                        wifiConfigurationsSent,
                        carPlayStartSessionsSent,
                        transportNotificationSeen,
                        postTransportWiFiConfigurationsSent,
                        wirelessCarPlayAvailableSeen,
                    )
                }
                if (locationActive && sendLatestLocation(locationProvider, deadlineNanos) && !locationSentLogged) {
                    locationSentLogged = true
                    onProgress("iap2 tx=0xfffb location-information")
                }
                val pollTimeout = when {
                    locationActive -> min(remaining, LOCATION_POLL_INTERVAL_MILLIS)
                    nextCredentialResendNanos != null ->
                        min(remaining, CREDENTIAL_RESEND_POLL_MILLIS)
                    // 1.0.29：稳态下也周期醒来检查 AirPlay 连接存在性（看门狗），不再 recv(∞)。
                    else -> min(remaining, AIRPLAY_PRESENCE_POLL_MILLIS)
                }
                val incoming = session.recv(pollTimeout)
                // 1.0.29：AirPlay 断流看门狗——每轮迭代都检查（含有消息的迭代），
                // 仅在会话曾激活后生效：AirPlay TCP 断流超过 WATCHDOG 即判停滞。
                val onAirPlayNow = phoneOnAirPlay()
                if (onAirPlayNow) {
                    everOnAirPlay = true
                    airPlayLostAtNanos = null
                } else if (everOnAirPlay) {
                    val lostAt = airPlayLostAtNanos
                        ?: System.nanoTime().also { airPlayLostAtNanos = it }
                    val stalledMs = (System.nanoTime() - lostAt) / 1_000_000L
                    if (stalledMs >= AIRPLAY_STALL_WATCHDOG_MILLIS) {
                        onProgress(
                            "iap2 watchdog: AirPlay TCP absent for ${stalledMs}ms " +
                                "after an active session; declaring wireless stall",
                        )
                        return Iap2WirelessControlResult(
                            Iap2WirelessControlTerminal.AIRPLAY_STALLED,
                            stage,
                            forwardedFrames,
                            wifiConfigurationsSent,
                            carPlayStartSessionsSent,
                            transportNotificationSeen,
                            postTransportWiFiConfigurationsSent,
                            wirelessCarPlayAvailableSeen,
                        )
                    }
                }
                if (incoming == null) {
                    if (session.isClosed) {
                        return Iap2WirelessControlResult(
                            Iap2WirelessControlTerminal.CHANNEL_CLOSED,
                            stage,
                            forwardedFrames,
                            wifiConfigurationsSent,
                            carPlayStartSessionsSent,
                            transportNotificationSeen,
                            postTransportWiFiConfigurationsSent,
                            wirelessCarPlayAvailableSeen,
                        )
                    }
                    if (remainingMillis(deadlineNanos) == 0L) {
                        return Iap2WirelessControlResult(
                            Iap2WirelessControlTerminal.TIMED_OUT,
                            stage,
                            forwardedFrames,
                            wifiConfigurationsSent,
                            carPlayStartSessionsSent,
                            transportNotificationSeen,
                            postTransportWiFiConfigurationsSent,
                            wirelessCarPlayAvailableSeen,
                        )
                    }
                    val resendAt = nextCredentialResendNanos
                    // [RELAY-TEST] 诱导切出实验分支（只在 phase 1/2 生效，优先于普通重发判定）：
                    // phase1 到点 → 只发假 0x5703（不带 0x4301，避免真假参数混发）；
                    // phase2 到点 → 恢复真 0x5703+0x4301，重开一轮普通重发窗口。
                    if (
                        (relayTestPhase == 1 || relayTestPhase == 2) &&
                        resendAt != null &&
                        System.nanoTime() >= resendAt &&
                        !phoneOnAirPlay()
                    ) {
                        if (relayTestPhase == 1) {
                            val fake = relayTestFakeCredential!!
                            val fakeEndpoint = Iap2WirelessCarPlayEndpoint(
                                ssid = fake.first,
                                passphrase = fake.second,
                                channel = 1,
                                security = Iap2WirelessSecurity.WPA_WPA2,
                                ipAddresses = endpoint.ipAddresses,
                                airPlayPort = endpoint.airPlayPort,
                                deviceIdentifier = endpoint.deviceIdentifier,
                                publicKey = endpoint.publicKey,
                                sourceVersion = endpoint.sourceVersion,
                            )
                            send(accessoryWiFiConfiguration(fakeEndpoint), deadlineNanos)
                            relayTestPhase = 2
                            nextCredentialResendNanos =
                                System.nanoTime() + RELAY_TEST_RESTORE_DELAY_NANOS
                            onProgress(
                                "[RELAY-TEST] phase2 fake 0x5703 sent ssid=${fake.first} " +
                                    "(expect iPhone to leave current AP)",
                            )
                        } else {
                            send(accessoryWiFiConfiguration(endpoint), deadlineNanos)
                            send(carPlayStartSession(endpoint), deadlineNanos)
                            relayTestPhase = 3
                            credentialResendsSent = 0
                            nextCredentialResendNanos =
                                System.nanoTime() + CREDENTIAL_RESEND_INTERVAL_NANOS
                            onProgress(
                                "[RELAY-TEST] phase3 true credential restored; " +
                                    "normal resend window restarted",
                            )
                        }
                        continue
                    }
                    // M6k24 凭证重发闸门（A 方案）：iPhone 只要已经发起过任何 AirPlay TCP 连接，
                    // 就绝不再重发 0x5703/0x4301 —— 8815 实测（11:52 日志）：会话已建到 SETUP
                    // （cseq=6）后 100ms 又收到重发，iPhone 随即弃连。重发只服务于
                    // 「iPhone 还没收到凭证/还没入网」的场景。
                    // vc83：中继踢出模式阈值提前到 2 次（~10s）；踢出恢复（phase 3）后
                    // 恢复满额 12 次重发预算，让真凭证持续覆盖 iPhone 离网→重扫描的窗口，
                    // 不再出现 8 次阈值掐断重发的真空期。
                    val kickThreshold =
                        when {
                            relayTestPhase == 3 -> MAX_CREDENTIAL_RESENDS
                            relayKickAfterResends > 0 -> relayKickAfterResends
                            else -> MAX_CREDENTIAL_RESENDS
                        }
                    if (
                        resendAt != null &&
                        credentialResendsSent < kickThreshold &&
                        System.nanoTime() >= resendAt &&
                        !phoneOnAirPlay()
                    ) {
                        send(accessoryWiFiConfiguration(endpoint), deadlineNanos)
                        wifiConfigurationsSent++
                        send(carPlayStartSession(endpoint), deadlineNanos)
                        carPlayStartSessionsSent++
                        credentialResendsSent++
                        nextCredentialResendNanos =
                            if (credentialResendsSent >= kickThreshold) {
                                if (relayTestPhase == 0) {
                                    // 真凭证重发到达阈值且 iPhone 未上 AirPlay：
                                    // 进入踢出阶段（假凭证诱导），保持 1s 轮询节奏
                                    relayTestPhase = 1
                                    System.nanoTime() + RELAY_TEST_PHASE2_DELAY_NANOS
                                } else {
                                    null
                                }
                            } else {
                                System.nanoTime() + CREDENTIAL_RESEND_INTERVAL_NANOS
                            }
                        onProgress(
                            "iap2 tx=0x5703/0x4301 credential resend #$credentialResendsSent " +
                                "(iPhone not on Wi-Fi yet)",
                        )
                    }
                    continue
                }

                when (incoming.messageId) {
                    REQUEST_ACCESSORY_WIFI_CONFIGURATION -> {
                        // [RELAY-TEST] iPhone 侧状态机动了 → 取消诱导实验，走正常流程
                        if (relayTestPhase in 1..2) {
                            relayTestPhase = -1
                            onProgress("[RELAY-TEST] cancelled: iPhone sent 0x5702")
                        }
                        onProgress("iap2 rx=0x5702 request-wifi-configuration")
                        val postTransport = transportNotificationSeen
                        val sentCount = if (postTransport) {
                            postTransportWiFiConfigurationsSent
                        } else {
                            preTransportWiFiConfigurationsSent
                        }
                        val limit = if (postTransport) {
                            MAX_POST_TRANSPORT_WIFI_CONFIGURATION_SENDS
                        } else {
                            MAX_PRE_TRANSPORT_WIFI_CONFIGURATION_SENDS
                        }
                        if (sentCount >= limit) {
                            onProgress(
                                "iap2 0x5703 ignored: maximum Wi-Fi configuration sends reached",
                            )
                        } else {
                            send(accessoryWiFiConfiguration(endpoint), deadlineNanos)
                            stage = later(
                                stage,
                                if (postTransport) {
                                    Iap2WirelessControlStage.POST_TRANSPORT_WIFI_CONFIG_SENT
                                } else {
                                    Iap2WirelessControlStage.WIFI_CONFIG_SENT
                                },
                            )
                            wifiConfigurationsSent++
                            if (postTransport) {
                                postTransportWiFiConfigurationsSent++
                            } else {
                                preTransportWiFiConfigurationsSent++
                            }
                            onProgress("iap2 tx=0x5703 accessory-wifi-configuration")
                        }
                    }

                    CARPLAY_AVAILABILITY -> {
                        // [RELAY-TEST] iPhone 侧状态机动了 → 取消诱导实验，走正常流程
                        if (relayTestPhase in 1..2) {
                            relayTestPhase = -1
                            onProgress("[RELAY-TEST] cancelled: iPhone sent 0x4300")
                        }
                        onProgress("iap2 rx=0x4300 carplay-availability")
                        send(carPlayStartSession(endpoint), deadlineNanos)
                        stage = later(stage, Iap2WirelessControlStage.CARPLAY_START_SENT)
                        carPlayStartSessionsSent++
                        onProgress("iap2 tx=0x4301 carplay-start-session")
                        // iPhone 晚入网（T18：55s）时需要再给机会，见上方 credentialResendsSent。
                        if (nextCredentialResendNanos == null) {
                            nextCredentialResendNanos =
                                System.nanoTime() + CREDENTIAL_RESEND_INTERVAL_NANOS
                        }
                    }

                    WIRELESS_CARPLAY_UPDATE -> {
                        val available = Iap2WirelessMessages.wirelessCarPlayAvailability(incoming)
                        if (available) wirelessCarPlayAvailableSeen = true
                        onProgress("iap2 rx=0x4e0d wireless-carplay-update available=$available")
                    }

                    DEVICE_TRANSPORT_IDENTIFIER_NOTIFICATION -> {
                        // [RELAY-TEST] iPhone 侧状态机动了 → 取消诱导实验，走正常流程
                        if (relayTestPhase in 1..2) {
                            relayTestPhase = -1
                            onProgress("[RELAY-TEST] cancelled: iPhone sent 0x4E0E")
                        }
                        transportNotificationSeen = true
                        val identifiers = Iap2WirelessMessages.deviceTransportIdentifier(incoming)
                        stage = later(stage, Iap2WirelessControlStage.TRANSPORT_NOTIFIED)
                        onProgress(
                            "iap2 rx=0x4e0e device-transport-identifier " +
                                "bluetooth=${identifiers.bluetoothMac ?: "none"} " +
                                "usb=${identifiers.usbTransportIdentifier ?: "none"}; " +
                                "resending 0x5703",
                        )
                        if (
                            postTransportWiFiConfigurationsSent >=
                            MAX_POST_TRANSPORT_WIFI_CONFIGURATION_SENDS
                        ) {
                            onProgress(
                                "iap2 post-transport 0x5703 ignored: " +
                                    "maximum Wi-Fi configuration sends reached",
                            )
                        } else {
                            send(accessoryWiFiConfiguration(endpoint), deadlineNanos)
                            stage = later(
                                stage,
                                Iap2WirelessControlStage.POST_TRANSPORT_WIFI_CONFIG_SENT,
                            )
                            wifiConfigurationsSent++
                            postTransportWiFiConfigurationsSent++
                            onProgress(
                                "iap2 tx=0x5703 post-transport accessory-wifi-configuration",
                            )
                        }
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
    }

    private fun send(frame: Iap2Frame, deadlineNanos: Long) {
        session.send(frame, requireRemaining(deadlineNanos))
    }

    private fun sendLatestLocation(
        provider: Iap2LocationProvider?,
        deadlineNanos: Long,
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
        private const val REQUEST_ACCESSORY_WIFI_CONFIGURATION = 0x5702
        private const val ACCESSORY_WIFI_CONFIGURATION = 0x5703
        private const val CARPLAY_AVAILABILITY = 0x4300
        private const val CARPLAY_START_SESSION = 0x4301
        private const val WIRELESS_CARPLAY_UPDATE = 0x4e0d
        private const val DEVICE_TRANSPORT_IDENTIFIER_NOTIFICATION = 0x4e0e
        private const val LOCATION_POLL_INTERVAL_MILLIS = 1_000L
        /** iPhone 未入网时重发凭据的间隔与上限（12 × 5s ≈ 1 分钟）。 */
        private const val CREDENTIAL_RESEND_INTERVAL_MILLIS = 5_000L
        private const val CREDENTIAL_RESEND_INTERVAL_NANOS = 5_000L * 1_000_000L
        // 1.0.29：AirPlay 断流看门狗——稳态轮询间隔与停滞判定阈值。
        // 30s 的取值：足够容忍 iPhone 短暂断网重连（正常 <5s），又不至于让黑屏挂太久。
        private const val AIRPLAY_PRESENCE_POLL_MILLIS = 5_000L
        private const val AIRPLAY_STALL_WATCHDOG_MILLIS = 30_000L
        private const val CREDENTIAL_RESEND_POLL_MILLIS = 1_000L
        private const val MAX_CREDENTIAL_RESENDS = 12
        /** [RELAY-TEST] 1.0.28-中继test 实验总开关（CarPlayController 侧据此决定是否扫描选假 SSID）。正式包必须为 false。 */
        const val RELAY_TEST_ENABLED = false
        private const val RELAY_TEST_PHASE2_DELAY_NANOS = 2_000L * 1_000_000L
        // vc83：10s→1.5s。踢出后立刻恢复真凭证——iPhone 离网后需要尽快拿到真凭证才能回连；
        // 旧 10s 空窗实证用户在恢复前就放弃。恢复后的持续重发（12×5s）继续覆盖重扫描窗口。
        private const val RELAY_TEST_RESTORE_DELAY_NANOS = 1_500L * 1_000_000L
        const val NO_TIMEOUT_MILLIS = Long.MAX_VALUE
        private const val DEFAULT_TIMEOUT_MILLIS = 60_000L
        private const val MAX_TIMEOUT_MILLIS = 24 * 60 * 60 * 1_000L
        private const val MAX_RECV_TIMEOUT_MILLIS = 5 * 60 * 1_000L
        private const val MAX_PRE_TRANSPORT_WIFI_CONFIGURATION_SENDS = 5
        private const val MAX_POST_TRANSPORT_WIFI_CONFIGURATION_SENDS = 2
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        /** Reference-compatible 0x5703 body. BSSID is omitted when the platform does not expose it. */
        fun accessoryWiFiConfiguration(endpoint: Iap2WirelessCarPlayEndpoint): Iap2Frame =
            Iap2WirelessMessages.accessoryWiFiConfiguration(
                ssid = endpoint.ssid,
                passphrase = endpoint.passphrase,
                channel = endpoint.channel,
                securityType = endpoint.security.wireValue,
                bssid = endpoint.bssid?.let(::macAddressToBytes),
            )

        /** Wireless 0x4301 reply carrying the receiver address, port and pairing identity. */
        fun carPlayStartSession(endpoint: Iap2WirelessCarPlayEndpoint): Iap2Frame =
            Iap2CarPlayMessages.startSession(
                wireless = Iap2WirelessSessionParameters(
                    ssid = endpoint.ssid,
                    passphrase = endpoint.passphrase,
                    channel = endpoint.channel,
                    ipAddresses = endpoint.ipAddresses,
                    securityType = endpoint.security.wireValue,
                ),
                airPlayPort = endpoint.airPlayPort,
                deviceIdentifier = endpoint.deviceIdentifier,
                publicKey = endpoint.publicKey,
                sourceVersion = endpoint.sourceVersion,
            )

        private fun later(
            current: Iap2WirelessControlStage,
            next: Iap2WirelessControlStage,
        ): Iap2WirelessControlStage = if (current.ordinal >= next.ordinal) current else next

        private fun deadlineAfter(timeoutMillis: Long): Long {
            val now = System.nanoTime()
            val delta = timeoutMillis * NANOS_PER_MILLISECOND
            return if (Long.MAX_VALUE - now < delta) Long.MAX_VALUE else now + delta
        }

        private fun requireRemaining(deadlineNanos: Long): Long = remainingMillis(deadlineNanos).also {
            if (it == 0L) throw IphoneUsbException.TimedOut("Timed out during wireless iAP2 control bring-up")
        }

        private fun remainingMillis(deadlineNanos: Long): Long {
            val remaining = deadlineNanos - System.nanoTime()
            if (remaining <= 0) return 0L
            return min(
                MAX_RECV_TIMEOUT_MILLIS,
                (remaining + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND,
            )
        }
    }
}

/** Wireless 0x5703 security values. */
enum class Iap2WirelessSecurity(val wireValue: Int) {
    NONE(0),
    WEP(1),
    WPA_WPA2(2),
    WPA3_TRANSITION(3),
    WPA3_ONLY(4),
}

/** Wireless hotspot and AirPlay endpoint sent in 0x5703 and 0x4301. */
class Iap2WirelessCarPlayEndpoint(
    val ssid: String,
    val passphrase: String,
    val channel: Int,
    val security: Iap2WirelessSecurity,
    ipAddresses: List<String>,
    val airPlayPort: Int,
    val deviceIdentifier: String,
    val publicKey: String,
    val sourceVersion: String,
    val bssid: String? = null,
) {
    val ipAddresses: List<String> = ipAddresses.toList()

    init {
        require(ssid.isNotBlank()) { "ssid is required and must not be blank" }
        require('\u0000' !in ssid) { "ssid must not contain U+0000" }
        require('\u0000' !in passphrase) { "passphrase must not contain U+0000" }
        if (security != Iap2WirelessSecurity.NONE) {
            require(passphrase.isNotEmpty()) { "passphrase is required for secured Wi-Fi" }
        }
        require(channel in 0..0xff) { "channel must be in 0..255" }
        require(ipAddresses.isNotEmpty()) { "At least one wireless IP address is required" }
        require(ipAddresses.all { it.isNotBlank() && '\u0000' !in it }) {
            "Every wireless IP address must be non-blank and must not contain U+0000"
        }
        require(airPlayPort in 1..65535) { "airPlayPort must be in 1..65535" }
        require(deviceIdentifier.isNotBlank()) { "deviceIdentifier is required and must not be blank" }
        require('\u0000' !in deviceIdentifier) { "deviceIdentifier must not contain U+0000" }
        require(publicKey.isNotEmpty()) { "publicKey is required and must not be empty" }
        require('\u0000' !in publicKey) { "publicKey must not contain U+0000" }
        require(sourceVersion.isNotEmpty()) { "sourceVersion is required and must not be empty" }
        require('\u0000' !in sourceVersion) { "sourceVersion must not contain U+0000" }
    }
}

/** "78:6a:89:2a:db:8e" -> 6 bytes; null when the value is not a plain MAC address. */
private fun macAddressToBytes(value: String): ByteArray? {
    val parts = value.split(':')
    if (parts.size != 6) return null
    val bytes = ByteArray(6)
    for ((index, part) in parts.withIndex()) {
        val octet = part.toIntOrNull(16) ?: return null
        if (octet !in 0..0xff) return null
        bytes[index] = octet.toByte()
    }
    return bytes
}

enum class Iap2WirelessControlStage {
    IDENTIFIED,
    AUTHENTICATED,
    SUBSCRIBED,
    WIFI_CONFIG_SENT,
    CARPLAY_START_SENT,
    TRANSPORT_NOTIFIED,
    POST_TRANSPORT_WIFI_CONFIG_SENT,
}

enum class Iap2WirelessControlTerminal { CHANNEL_CLOSED, TIMED_OUT, AIRPLAY_STALLED }

/** End state of the control loop only; it is not evidence of a live Wi-Fi or AirPlay session. */
data class Iap2WirelessControlResult(
    val terminal: Iap2WirelessControlTerminal,
    val stage: Iap2WirelessControlStage,
    val forwardedFrames: Int,
    val wifiConfigurationsSent: Int,
    val carPlayStartSessionsSent: Int,
    val transportNotificationSeen: Boolean,
    val postTransportWiFiConfigurationsSent: Int,
    val wirelessCarPlayAvailableSeen: Boolean,
)
