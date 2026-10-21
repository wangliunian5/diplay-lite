package com.shilapi.xcertplay.network

// vc156（移植自上游 0.2.21 P2pOwnership.kt）：重装稳定的组归属命名空间，绝不把通用 DIRECT-* 名
// 认作自己的组；连同 P2pResetRequiredException（检测到外来 P2P 组需先复位）。

import java.io.IOException
import java.security.MessageDigest

/** Reinstall-stable namespace; never treats a generic DIRECT-* name as ours. */
internal object P2pOwnership {
    fun prefix(packageName: String, scopedId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$packageName:$scopedId".toByteArray(Charsets.UTF_8))
        return "DIRECT-dp" + digest.take(6).joinToString("") { "%02x".format(it.toInt() and 255) } + "-"
    }

    fun canReclaim(isOwner: Boolean, ssid: String?, recorded: String?, prefix: String): Boolean =
        isOwner && !ssid.isNullOrBlank() &&
            (ssid == recorded || (ssid.startsWith(prefix) &&
                ssid.removePrefix(prefix).matches(Regex("[A-Za-z0-9]{4}"))))
}

class P2pResetRequiredException : IOException("An existing Wi-Fi Direct connection needs a reset")
