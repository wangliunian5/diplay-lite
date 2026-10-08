package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress

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

    @Volatile
    private var closed = false

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: throw IOException("Wi-Fi service is unavailable on this device")
        if (!wifi.isWifiEnabled) {
            throw IOException("Wi-Fi is off. Turn on Wi-Fi to use the relay channel.")
        }

        val netId = attachNetwork(wifi)
        onDiagnostic("relay: enableNetwork id=$netId ssid='$ssid', waiting for DHCP")
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (closed) throw IOException("Relay client closed while connecting")
            val info = wifi.connectionInfo
            if (isConnectedTo(info, ssid) && info?.ipAddress != 0) {
                val dhcp = wifi.dhcpInfo
                val hostAddress = dhcp?.let { intToInetAddress(it.ipAddress) }
                if (hostAddress != null) {
                    onDiagnostic("relay: joined ssid='$ssid' host=$hostAddress")
                    verifyGatewayReachable(dhcp.gateway)
                    return buildInfo(wifi, info, hostAddress)
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
    }

    /** 已配置过的同名网络直接复用；否则新增一条（WPA2 或开放）。返回生效的 networkId。 */
    private fun attachNetwork(wifi: WifiManager): Int {
        val existing = try {
            wifi.configuredNetworks.orEmpty().firstOrNull { it.SSID == quoted(ssid) }
        } catch (_: Exception) {
            null
        }
        val netId = if (existing != null) {
            onDiagnostic("relay: reusing configured network id=${existing.networkId}")
            existing.networkId
        } else {
            val config = WifiConfiguration().apply {
                SSID = quoted(ssid)
                if (passphrase.isNotEmpty()) preSharedKey = quoted(passphrase)
            }
            val added = wifi.addNetwork(config)
            if (added < 0) {
                throw IOException(
                    "Adding relay network '$ssid' failed (code=$added); check the password",
                )
            }
            onDiagnostic("relay: added network id=$added")
            added
        }
        wifi.disconnect()
        if (!wifi.enableNetwork(netId, true)) {
            throw IOException("Enabling relay network '$ssid' failed")
        }
        if (!wifi.reconnect()) {
            onDiagnostic("relay: reconnect() returned false; still polling for DHCP")
        }
        return netId
    }

    /** AP 隔离/连错网自检：网关 2s×2 次 ping 不通 → 显性报错（归类确定性失败，不烧重试额度）。 */
    private fun verifyGatewayReachable(gateway: Int) {
        val gatewayAddress = intToInetAddress(gateway)
            ?: throw IOException("RELAY_UNREACHABLE: relay network returned no gateway")
        for (attempt in 1..2) {
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
        throw IOException(
            "RELAY_UNREACHABLE: gateway $gatewayAddress did not answer; " +
                "the relay router may block this device (AP isolation) or the iPhone is on another network",
        )
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
        private const val REACHABLE_TIMEOUT_MILLIS = 2_000
        private const val REACHABLE_RETRY_DELAY_MILLIS = 300L
    }
}
