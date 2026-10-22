package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 中继热点（双 client 模式）：不建热点，而是把本机接入外部网络（随身WiFi），
 * 由用户手动让 iPhone 连同一个网络。之后 0x5703 下发的 SSID/凭证即随身WiFi 的凭据，
 * AirPlay 监听与 Bonjour advertisedHost 用 wlan0 的 DHCP 地址——下游链路全部复用 MANUAL 实现。
 *
 * 相比 [ManualHotspotManager] 的差异点：
 * - 连接外部网络用 legacy WifiConfiguration API（API 16-23 可用，targetSdk 19 下安装即授予）；
 * - WifiInfo.getIpAddress()/DhcpInfo 在 4.1 上可用，无需高版本 API；
 * - 连上后做一次网关可达性自检：网关都 ping 不通多半是 AP 隔离或连错网，
 *   此时 iPhone 必然也摸不到本机，直接抛 RELAY_UNREACHABLE（归类为不重试的确定性失败）。
 */
class RelayClientManager(
    private val context: Context,
    private val ssid: String,
    private val passphrase: String,
    private val onDiagnostic: (String) -> Unit,
) : WirelessHotspotManager {

    private val appContext = context.applicationContext

    // vc81 中继链路抗抖动：中继模式下本机是随身WiFi 的 STA 客户端，Android 默认省电轮询
    // 会造成 100~900ms 级突发断供（C8815 实测：视频 rx=6.8fps、音频输入 maxGap 389~934ms、
    // queuedMs 180~1427ms 震荡，听感一卡一卡）。持高性能 Wi-Fi 锁禁用省电，会话期间持有。
    @Volatile
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile
    private var closed = false

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: throw IOException("Wi-Fi service is unavailable on this device")
        // vc83：发起即自动开 WiFi——车机 WiFi 开关关闭时旧路径直接抛「Wi-Fi is off」，
        // 用户第一次点击永远白点、必须手开 WiFi 后点第二次（082530 日志 tap1 实证）。
        if (!wifi.isWifiEnabled) {
            onDiagnostic("relay: Wi-Fi is off, enabling automatically")
            @Suppress("DEPRECATION")
            wifi.setWifiEnabled(true)
            val enableDeadline = System.currentTimeMillis() + WIFI_ENABLE_WAIT_MILLIS
            while (!wifi.isWifiEnabled && System.currentTimeMillis() < enableDeadline) {
                try {
                    Thread.sleep(WIFI_ENABLE_POLL_MILLIS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            if (!wifi.isWifiEnabled) {
                throw IOException(
                    "Wi-Fi could not be enabled automatically. Turn on Wi-Fi to use the relay channel.",
                )
            }
            onDiagnostic("relay: Wi-Fi enabled")
        }

        val netId = attachNetwork(wifi)
        onDiagnostic("relay: enableNetwork id=$netId ssid='$ssid', waiting for DHCP")
        val deadline = System.currentTimeMillis() + timeoutMillis
        // 1.0.29（at8317 排查）：DHCP 卡住时旧日志只有「waiting for DHCP」一条，分不清
        // 「没关联上 AP」还是「关联了但没拿到 IP」；加一次性中间态日志区分两态，
        // joined 日志补网关字段，便于和 iPhone 侧网段对账。
        var loggedAssociated = false
        var loggedStuck = false
        var associatedAtMillis = 0L
        while (System.currentTimeMillis() < deadline) {
            if (closed) throw IOException("Relay client closed while connecting")
            val info = wifi.connectionInfo
            if (isConnectedTo(info, ssid)) {
                if (info?.ipAddress == 0) {
                    // 已关联到 AP 但 DHCP 还没出地址：只报一次，避免刷屏。
                    if (!loggedAssociated) {
                        loggedAssociated = true
                        associatedAtMillis = System.currentTimeMillis()
                        onDiagnostic("relay: associated ssid='$ssid', waiting for IP address (DHCP)")
                    } else if (!loggedStuck) {
                        // vc174（C8815 00:26 k602026 实证）：密码错时 Android 4.x 的 WifiInfo
                        // 照样报目标 SSID（4-way handshake 未完成也算 associated），于是界面
                        // 一直转圈、没有任何提示，用户只能干等超时。超时前主动提示一次：
                        // 长时间停在「已关联、无 IP」= 认证没过（密码/加密方式）或热点 DHCP 慢。
                        val stuckMs = System.currentTimeMillis() - associatedAtMillis
                        if (stuckMs >= ASSOCIATED_STALL_HINT_MILLIS) {
                            loggedStuck = true
                            onDiagnostic(
                                "relay: still no IP ${stuckMs}ms after association — most likely the " +
                                    "passphrase/encryption was rejected (4-way handshake incomplete) " +
                                    "or the hotspot DHCP is slow",
                            )
                        }
                    }
                } else {
                    val dhcp = wifi.dhcpInfo
                    val hostAddress = dhcp?.let { intToInetAddress(it.ipAddress) }
                    if (dhcp != null && hostAddress != null) {
                        val gw = intToInetAddress(dhcp.gateway)?.hostAddress ?: "none"
                        onDiagnostic("relay: joined ssid='$ssid' host=$hostAddress gw=$gw")
                        verifyGatewayReachable(dhcp.gateway)
                        acquireWifiLock(wifi)
                        return buildInfo(wifi, info, hostAddress)
                    }
                }
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Relay client interrupted while connecting")
            }
        }
        throw IOException(
            "Could not join relay network '$ssid' within timeout; check the SSID/password in relay settings",
        )
    }

    override fun close() {
        closed = true
        // 故意不断开 Wi-Fi：连接外部网络是用户可感知的系统状态，teardown 后保留连接，
        // 重试（ensure 幂等）与用户查看网络都不受影响。
        // WifiLock 同理随会话释放（失败重试 keepForReuse 时 ensure 幂等会重新走 start）。
        try {
            wifiLock?.release()
        } catch (_: Exception) {
        }
        wifiLock = null
    }

    /** 幂等持锁：WIFI_MODE_FULL_HIGH_PERF（API12+，本模块最低 API16 可用），失败降级不抛。 */
    private fun acquireWifiLock(wifi: WifiManager) {
        if (wifiLock != null) return
        try {
            val lock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "diplay-relay")
            lock.setReferenceCounted(false)
            lock.acquire()
            wifiLock = lock
            onDiagnostic("relay: WifiLock FULL_HIGH_PERF acquired")
        } catch (e: Exception) {
            onDiagnostic("relay: WifiLock acquire failed (non-fatal): ${e.message}")
        }
    }

    /** 已配置过的同名网络直接复用；否则新增一条（WPA2 或开放）。返回生效的 networkId。 */
    private fun attachNetwork(wifi: WifiManager): Int {
        val existing = try {
            wifi.configuredNetworks.orEmpty().firstOrNull { it.SSID == quoted(ssid) }
        } catch (_: Exception) {
            null
        }
        val netId = if (existing != null) {
            // vc94：命中同名网络时校验 PSK 一致性——用户改中继热点密码后，存量配置仍指向
            // 旧密码，无条件复用会让 join 恒超时（无法自愈）。不一致即就地 updateNetwork 覆盖。
            // vc174（C8815 00:28 日志实证）：Android 4.x 的 getConfiguredNetworks() 不返回
            // 明文 PSK，而是掩码 "*"（日志里 `PSK mismatch (stored=1 chars)` 就是它），
            // 于是这条比对**恒为真**——每次点连接都走一次 updateNetwork + disconnect +
            // reconnect，把已经连着的 Wi-Fi 先拆掉重连，DHCP 因此每次要 20s（Windows
            // 承载网络 192.168.137.x）。改为：掩码值视为「不可比对」，且本机已在该网并
            // 拿到 IP 时一律不动配置（自断重连没有任何收益）。
            val pskMasked = existing.preSharedKey == null || existing.preSharedKey == PSK_MASKED
            val pskChanged = if (pskMasked) {
                false
            } else if (passphrase.isNotEmpty()) {
                existing.preSharedKey != quoted(passphrase)
            } else {
                // 本次配置为开放网络，但存量带 PSK → 需清空才能转开放。
                existing.preSharedKey!!.isNotEmpty()
            }
            if (!pskChanged) {
                onDiagnostic(
                    "relay: reusing configured network id=${existing.networkId}" +
                        if (pskMasked) " (platform masks PSK as '$PSK_MASKED'; no update)" else "",
                )
            } else if (isJoinedWithIp(wifi)) {
                onDiagnostic(
                    "relay: PSK differs but already joined '$ssid' with an IP; " +
                        "keeping the live connection (no update/reconnect)",
                )
            } else {
                onDiagnostic(
                    "relay: configured network id=${existing.networkId} PSK mismatch " +
                        "(stored=${existing.preSharedKey?.length ?: 0} chars) — updating",
                )
                val config = relayConfiguration(existing.networkId)
                val updated = wifi.updateNetwork(config)
                if (updated < 0) {
                    onDiagnostic("relay: updateNetwork failed (code=$updated); falling back to add")
                    wifi.removeNetwork(existing.networkId)
                    val added = wifi.addNetwork(relayConfiguration(-1))
                    if (added < 0) {
                        throw IOException(
                            "Updating relay network '$ssid' password failed (code=$added)",
                        )
                    }
                    onDiagnostic("relay: re-added network id=$added with current password")
                    return finishJoin(wifi, added)
                }
            }
            existing.networkId
        } else {
            val config = relayConfiguration(-1)
            val added = wifi.addNetwork(config)
            if (added < 0) {
                throw IOException(
                    "Adding relay network '$ssid' failed (code=$added); check the password",
                )
            }
            onDiagnostic("relay: added network id=$added")
            added
        }
        return finishJoin(wifi, netId)
    }

    /**
     * 本会话的中继网络配置。vc174：显式写 key management——只给 preSharedKey 而不给
     * key_mgmt 时，老 wpa_supplicant 会落到开放网络语义（key_mgmt=NONE），去连 WPA2 热点
     * 必然 4-way handshake 失败，表象正是「密码明明是对的却报密码/认证错误」。
     */
    private fun relayConfiguration(networkId: Int): WifiConfiguration = WifiConfiguration().apply {
        SSID = quoted(ssid)
        if (networkId >= 0) this.networkId = networkId
        if (passphrase.isNotEmpty()) {
            preSharedKey = quoted(passphrase)
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
        } else {
            preSharedKey = ""
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
        }
    }

    /** 本机已经连在该 SSID 上并且拿到了 IP（用于避免无意义的 update/disconnect）。 */
    private fun isJoinedWithIp(wifi: WifiManager): Boolean {
        val info = wifi.connectionInfo ?: return false
        return info.ipAddress != 0 && isConnectedTo(info, ssid)
    }

    /** enableNetwork + reconnect + DHCP 轮询共用尾段（vc94 从 attachNetwork 拆出）。 */
    private fun finishJoin(wifi: WifiManager, netId: Int): Int {
        wifi.disconnect()
        if (!wifi.enableNetwork(netId, true)) {
            throw IOException("Enabling relay network '$ssid' failed")
        }
        if (!wifi.reconnect()) {
            onDiagnostic("relay: reconnect() returned false; still polling for DHCP")
        }
        return netId
    }

    /** AP 隔离/连错网自检：网关 3s×4 次 ping 不通 → 先做 TCP 探测，仍不活才报错（确定性失败，不烧重试额度）。 */
    private fun verifyGatewayReachable(gateway: Int) {
        val gatewayAddress = intToInetAddress(gateway)
            ?: throw IOException("RELAY_UNREACHABLE: relay network returned no gateway")
        for (attempt in 1..REACHABLE_ATTEMPTS) {
            if (closed) throw IOException("Relay client closed during reachability check")
            try {
                if (gatewayAddress.isReachable(REACHABLE_TIMEOUT_MILLIS)) {
                    onDiagnostic("relay: gateway $gatewayAddress reachable (attempt $attempt)")
                    return
                }
            } catch (_: Exception) {
                // isReachable 在部分 ROM 上抛异常，按不可达计
            }
            try {
                Thread.sleep(REACHABLE_RETRY_DELAY_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        // vc174（C8815 00:27~00:28 三轮实证）：ICMP 不通 ≠ 网络不通。Windows 承载网络
        // （192.168.137.x，本次中继热点就是它）与多数手机热点默认**不回 ping**（防火墙
        // 公用网络禁 ICMP 回显），此时旧代码直接抛 RELAY_UNREACHABLE，弹「网关无响应/
        // AP隔离」——纯误杀，车机其实已经拿到 192.168.137.35 且链路正常。
        // 改为：ICMP 全灭后补一次 TCP 探测（53/80/443），任一端口有应答即判网关存活、
        // 只记告警继续（真正的判据是 iPhone 能否建 AirPlay TCP，交给 first TCP watchdog）。
        onDiagnostic(
            "relay: gateway $gatewayAddress did not answer ICMP ($REACHABLE_ATTEMPTS probes); " +
                "probing TCP ports to tell 'ICMP blocked' from 'real AP isolation'",
        )
        val tcpPort = probeTcpPort(gatewayAddress)
        if (tcpPort > 0) {
            onDiagnostic(
                "relay: gateway answered TCP on port $tcpPort — ICMP is blocked by the hotspot " +
                    "firewall (common on Windows hosted network / phone tethering); continuing",
            )
            return
        }
        // 1.0.29（at8317 排查）：InetAddress.toString() 带 '/' 前缀（弹窗显示成 /192.168.200.1），
        // 会污染 localizeFailure 的 gateway 正则提取；改用 hostAddress 纯地址格式，英文原文照旧进日志。
        throw IOException(
            "RELAY_UNREACHABLE: gateway ${gatewayAddress.hostAddress} did not answer ICMP or TCP; " +
                "the relay router may block this device (AP isolation) or the iPhone is on another network",
        )
    }

    /**
     * vc174：ICMP 之外的存活判据——连一次网关的常见 TCP 端口（DNS/HTTP/HTTPS）。
     * 返回应答的端口号，全不通返回 0。只用于区分「禁 ping」与「真隔离」，不承载业务。
     */
    private fun probeTcpPort(gateway: InetAddress): Int {
        for (port in GATEWAY_TCP_PROBE_PORTS) {
            if (closed) return 0
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(gateway, port), TCP_PROBE_TIMEOUT_MILLIS)
                }
                return port
            } catch (_: Exception) {
                // 连接被拒/超时都按不可达计，继续下一个端口
            }
        }
        return 0
    }

    private fun buildInfo(wifi: WifiManager, info: WifiInfo, hostAddress: InetAddress): WirelessHotspotInfo {
        // WifiInfo.getFrequency 是 API 21+；API 16-19 拿不到就保持未知（0x5703 下发 0）。
        val frequencyMHz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) info.frequency else 0
        // 1.0.13：观测信道同样吸附标准信道（2.4G→1/6/11，5G→36/44/149/157）后随 0x5703 下发，
        // iPhone 据此定向扫描；未知（0）时复用上次成功确认的信道，仍无则保持 0。
        val channel = snapHotspotChannel(wifiFrequencyMhzToChannel(frequencyMHz) ?: 0)
            .takeIf { it > 0 }
            ?: ConfirmedChannelMemory.recall(appContext, ConfirmedChannelMemory.ROLE_RELAY)
        val bssid = info.bssid?.takeIf { it.isNotBlank() && it != "02:00:00:00:00:00" }
        return WirelessHotspotInfo(
            ssid = ssid,
            passphrase = passphrase,
            security = if (passphrase.isEmpty()) {
                com.shilapi.xcertplay.transport.Iap2WirelessSecurity.NONE
            } else {
                com.shilapi.xcertplay.transport.Iap2WirelessSecurity.WPA_WPA2
            },
            channel = channel,
            frequencyMHz = frequencyMHz.takeIf { it > 0 },
            bssid = bssid,
            interfaceName = "wlan0",
            hostAddress = hostAddress,
            bandLabel = when {
                frequencyMHz in 2_400..2_500 -> "2.4 GHz"
                frequencyMHz in 5_000..6_000 -> "5 GHz"
                else -> "relay"
            },
            backend = WirelessHotspotBackend.RELAY_CLIENT,
        )
    }

    private fun isConnectedTo(info: WifiInfo?, ssid: String): Boolean {
        val current = info?.ssid?.removeSurrounding("\"") ?: return false
        return current.equals(ssid, ignoreCase = false)
    }

    private fun quoted(value: String): String = "\"$value\""

    /** DhcpInfo 的 int 地址是小端序，需转成 InetAddress。 */
    private fun intToInetAddress(value: Int): InetAddress? {
        if (value == 0) return null
        val bytes = byteArrayOf(
            (value and 0xff).toByte(),
            ((value ushr 8) and 0xff).toByte(),
            ((value ushr 16) and 0xff).toByte(),
            ((value ushr 24) and 0xff).toByte(),
        )
        return try {
            Inet4Address.getByAddress(bytes)
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        private const val POLL_INTERVAL_MILLIS = 500L
        // vc95：网关探测放宽 2s×2 → 3s×4（间隔 1s）——手机热点（小米等）DHCP 出租后
        // AP 数据面有几秒到几十秒启动窗口，ICMP 不回属瞬时假阴性，2s×2（实际 ~4.3s
        // 全程）太短，用户被迫手动重试；放宽后覆盖窗口期，总上限 ~15s。
        private const val REACHABLE_TIMEOUT_MILLIS = 3_000
        private const val REACHABLE_RETRY_DELAY_MILLIS = 1_000L
        private const val REACHABLE_ATTEMPTS = 4
        // vc174：ICMP 不通后的 TCP 补刀探测（DNS / HTTP / HTTPS），单端口超时。
        private val GATEWAY_TCP_PROBE_PORTS = intArrayOf(53, 80, 443)
        private const val TCP_PROBE_TIMEOUT_MILLIS = 1_200
        /** vc174：停在「已关联但没 IP」多久后提示一次「多半是密码/加密被拒或 DHCP 慢」。 */
        private const val ASSOCIATED_STALL_HINT_MILLIS = 12_000L
        /**
         * vc174：Android 4.x（以及多数版本）的 getConfiguredNetworks() 不返回明文 PSK，
         * 一律掩码成 "*"；拿它做一致性比对会恒不相等（见 [attachNetwork]）。
         */
        private const val PSK_MASKED = "*"
        // vc83：自动开 WiFi 的等待上限与轮询步长。
        private const val WIFI_ENABLE_WAIT_MILLIS = 8_000L
        private const val WIFI_ENABLE_POLL_MILLIS = 200L
    }
}
