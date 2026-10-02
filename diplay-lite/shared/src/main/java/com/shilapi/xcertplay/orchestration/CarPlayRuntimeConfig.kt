package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.net.Inet6Address
import java.net.InetAddress

enum class CarPlayTransport {
    WIRELESS,
}

enum class MfiTarget {
    LOCAL,
}

enum class WirelessHotspotMode {
    MANUAL,
    MANUAL_IPV4,
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
    val linkLocal: String = "fe80::2",
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
) {
    init {
        require(hostMac.size == 6) { "hostMac must be 6 bytes" }
        require(isLinkLocalIpv6(linkLocal)) { "linkLocal must be a link-local IPv6 literal" }
        require(availableCurrentMilliAmps in 0..0xffff) {
            "availableCurrentMilliAmps must be in 0..65535"
        }
        require(label.isNotBlank()) { "label must not be blank" }
        require(hostName.isNotBlank()) { "hostName must not be blank" }
        // Lite: 只保留 MANUAL 热点配置校验
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
        private fun isLinkLocalIpv6(value: String): Boolean {
            if (value.contains('%') || '\u0000' in value || !value.contains(':')) return false
            return try {
                val address = InetAddress.getByName(value)
                address is Inet6Address && address.isLinkLocalAddress
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
