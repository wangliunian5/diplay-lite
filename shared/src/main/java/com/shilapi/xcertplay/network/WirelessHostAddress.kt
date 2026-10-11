package com.shilapi.xcertplay.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Use the same scoped, link-local path for manual APs as for Wi-Fi Direct.
 * With [preferIpv4] (manual IPv4 sessions) a usable IPv4 address wins over the IPv6 path.
 */
internal fun wirelessHostAddress(
    addresses: List<InetAddress>,
    interfaceIndex: Int,
    preferIpv4: Boolean = false,
): InetAddress? {
    if (preferIpv4) {
        addresses.firstOrNull(::isUsableIpv4)?.let { return it }
    }
    if (interfaceIndex > 0) {
        addresses.filterIsInstance<Inet6Address>().firstOrNull { it.isLinkLocalAddress }?.let {
            return Inet6Address.getByAddress(null, it.address, interfaceIndex)
        }
    }
    return addresses.firstOrNull(::isUsableIpv4)
}

private fun isUsableIpv4(address: InetAddress): Boolean =
    address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress &&
        !address.isAnyLocalAddress && !address.isMulticastAddress
