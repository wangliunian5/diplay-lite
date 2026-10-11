package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.WifiManager

/**
 * Reads whether the head unit's own Wi-Fi hotspot is on, for the "Car hotspot" link.
 *
 * DiPlay does not turn the hotspot on itself: that needs a permission Android only grants over
 * ADB. The user turns it on in the car settings, or automates it with a tool such as BYDMate.
 */
object CarHotspotStatus {
    private const val WIFI_AP_STATE_ENABLED = 13

    /**
     * True/false from the Wi-Fi AP state, or null when the firmware hides it (then callers must
     * not block the connection). Interface flags are not used: BYD keeps wlan1 up with an address
     * while tethering is off.
     */
    fun isEnabled(context: Context): Boolean? {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        return runCatching {
            WifiManager::class.java.getMethod("getWifiApState").invoke(wifi) as Int == WIFI_AP_STATE_ENABLED
        }.recoverCatching {
            WifiManager::class.java.getMethod("isWifiApEnabled").invoke(wifi) as Boolean
        }.getOrNull()
    }

    /** 原始 Wi-Fi AP 状态值（反射）；AOSP 取值 10..14（13=已启用），供 OEM 魔改诊断。 */
    fun rawState(context: Context): Int? {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        return runCatching {
            WifiManager::class.java.getMethod("getWifiApState").invoke(wifi) as Int
        }.getOrNull()
    }
}
