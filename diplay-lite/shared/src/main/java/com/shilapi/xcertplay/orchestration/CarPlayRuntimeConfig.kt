package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.net.Inet6Address
import java.net.InetAddress

enum class CarPlayTransport {
    WIRELESS,

    /** USB 有线：USBMUX/lockdown 控制面 + NCM 数据面，不走路机热点/蓝牙/Bonjour。 */
    WIRED,
}

enum class MfiTarget {
    LOCAL,
}

enum class WirelessHotspotMode {
    MANUAL,
    MANUAL_IPV4,
    /** 双 client 中继：本机接入外部网络（随身WiFi），复用 manualHotspotSsid/Passphrase 字段作凭据。 */
    RELAY,
    ;

    /** Both manual modes attach to the car's own hotspot; IPV4 only changes the address family. */
    val isManualHotspot: Boolean get() = this == MANUAL || this == MANUAL_IPV4
}

enum class ManualHotspotBand {
    AUTO,
    GHZ_2_4,
    GHZ_5,
}

enum class ManualHotspotSecurity {
    OPEN,
    WPA2,
    WPA3_TRANSITION,
    WPA3,
}

/**
 * Deployment-owned constants for one head unit (Lite: 仅无线 + MANUAL 热点 + 本地软件 MFi)。
 * 原 USB/CH341/I2C/REMOTE MFi 与 P2P/LocalOnly 热点字段已随有线路径一并裁剪。
 */
class CarPlayRuntimeConfig(
    val hostMac: ByteArray = DEFAULT_HOST_MAC,
    val linkLocal: String = "fd00::2",
    val identification: Iap2IdentificationConfig,
    val availableCurrentMilliAmps: Int = 2400,
    val label: String = "xcertplay",
    val hostName: String = "xcertplay",
    val transport: CarPlayTransport = CarPlayTransport.WIRELESS,
    val wirelessHotspotMode: WirelessHotspotMode = WirelessHotspotMode.MANUAL,
    val manualHotspotSsid: String? = null,
    val manualHotspotPassphrase: String? = null,
    val manualHotspotBand: ManualHotspotBand = ManualHotspotBand.AUTO,
    val manualHotspotChannel: Int = 0,
    val manualHotspotSecurity: ManualHotspotSecurity = ManualHotspotSecurity.WPA2,
    val wirelessBluetoothDeviceAddress: String? = null,
    val locationReportingEnabled: Boolean = false,
    // 1.1.6(vc76)：有线 cdc_ncm 解绑守护开关（TS7 无 root 裁决实验）。默认 true=正式路径；
    // false 时不启动 WiredNcmGuard，验证「仅 NTB16 重同步、无 root 无守护」能否稳住会话。
    val wiredNcmGuardEnabled: Boolean = true,
    // vc156（B 级补充路径，默认关）：车机热点建链失败（SoftAP 判死/附着超时/权限受限机型）时，
    // 改用 Wi-Fi Direct P2P GO 作为替代 AP（WifiP2pGroupManager，移植自上游 0.2.21）。
    // 默认关闭理由：P2P 建组会切换无线电角色、可能扰动正常的主路径，补充路径必须显式 opt-in；
    // 仅 MANUAL/MANUAL_IPV4 车机热点模式生效（RELAY 加入的是外部网络，无「替代 AP」语义）。
    val wifiP2pFallbackEnabled: Boolean = false,
    // vc102 USB bootstrap 无线升级（实验）：有线 CSM 建好后不走 NCM/有线控制环，改走
    // 「开热点 + AirPlay 监听 + mDNS + 识别帧 group16+24 + 无线控制环（0x5703 经 USB 隧道）」。
    // Apple 官方「插 USB 弹提醒可无线」流程的配件侧实现；热点参数复用 wirelessHotspotMode 组。
    val usbWirelessUpgrade: Boolean = false,
) {
    init {
        require(hostMac.size == 6) { "hostMac must be 6 bytes" }
        // vc111（T3 KK 实测）：link-local（fe80）在 KK sunxi 内核的 tun 本地投递是坏的
        // （dst=fe80::2 的包被当转发丢回出向，ICMP/TCP 全黑洞；本机 ping 实锤），
        // ULA 全局地址 fd00::2 本地投递正常（echo reply 0% loss）。AirPlay 栈迁到
        // ULA：校验放宽为 link-local 或 fd00::/8 ULA 皆可。
        require(isValidTunIpv6(linkLocal)) { "linkLocal must be a link-local or ULA IPv6 literal" }
        require(availableCurrentMilliAmps in 0..0xffff) {
            "availableCurrentMilliAmps must be in 0..65535"
        }
        require(label.isNotBlank()) { "label must not be blank" }
        require(hostName.isNotBlank()) { "hostName must not be blank" }
        // Lite: 只保留 MANUAL 热点配置校验
        if (transport == CarPlayTransport.WIRELESS && wirelessHotspotMode == WirelessHotspotMode.RELAY) {
            require(!manualHotspotSsid.isNullOrBlank()) {
                "relaySsid is required in relay mode"
            }
            val relayPassphrase = manualHotspotPassphrase.orEmpty()
            require('\u0000' !in relayPassphrase) {
                "relayPassphrase must not contain U+0000"
            }
            require(relayPassphrase.isEmpty() || relayPassphrase.length in 8..63) {
                "relayPassphrase must be empty or between 8 and 63 characters"
            }
        }
        if (transport == CarPlayTransport.WIRELESS && wirelessHotspotMode.isManualHotspot) {
            val ssid = manualHotspotSsid
            require(!ssid.isNullOrBlank()) {
                "manualHotspotSsid is required in manual hotspot mode"
            }
            require('\u0000' !in ssid) {
                "manualHotspotSsid must not contain U+0000"
            }
            val passphrase = manualHotspotPassphrase.orEmpty()
            require('\u0000' !in passphrase) {
                "manualHotspotPassphrase must not contain U+0000"
            }
            require(passphrase.isEmpty() || passphrase.length in 8..63) {
                "manualHotspotPassphrase must be empty or between 8 and 63 characters"
            }
            require(manualHotspotChannel in 0..196) {
                "manualHotspotChannel must be 0 or in 1..196"
            }
            require(
                manualHotspotSecurity == ManualHotspotSecurity.OPEN ||
                    passphrase.length in 8..63,
            ) {
                "A passphrase between 8 and 63 characters is required for secured manual hotspots"
            }
            require(
                manualHotspotChannel == 0 ||
                    isManualHotspotChannelCompatible(manualHotspotBand, manualHotspotChannel),
            ) {
                "manualHotspotChannel is not valid for the selected manual hotspot band"
            }
            require(
                manualHotspotSecurity == ManualHotspotSecurity.OPEN || passphrase.isNotEmpty(),
            ) {
                "manualHotspotPassphrase is required for secured manual hotspots"
            }
        }
    }

    companion object {
        val DEFAULT_HOST_MAC = byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x00, 0x02)
        private fun isValidTunIpv6(value: String): Boolean {
            if (value.contains('%') || '\u0000' in value || !value.contains(':')) return false
            return try {
                val address = InetAddress.getByName(value)
                // fd00::/8 ULA 不属于 Java 的 isSiteLocalAddress（那是 fec0::/10），按首字节判
                address is Inet6Address &&
                    (address.isLinkLocalAddress || address.address[0] == 0xfd.toByte())
            } catch (_: Exception) {
                false
            }
        }
    }
}

fun isManualHotspotChannelCompatible(band: ManualHotspotBand, channel: Int): Boolean = when (band) {
    ManualHotspotBand.AUTO -> channel in 1..196
    ManualHotspotBand.GHZ_2_4 -> channel in 1..14
    ManualHotspotBand.GHZ_5 -> channel in 32..177
}
