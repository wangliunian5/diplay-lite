package com.shilapi.diplay.lite

import android.content.Context

/**
 * MANUAL（车机自带热点）配置的持久化。
 * 对应低版本分支 AirPlayPersistence 的 MANUAL 子集，只存凭据，不存任何手机标识。
 */
data class LiteConfig(
    val ssid: String,
    val password: String,
    val channel: Int,          // 0 = 未知/自动（0x5703 下发前应尽量实测填充，难点 B3）
    val security: String,      // WPA2 / WPA2-WPA3 / WPA3 / OPEN
    val bluetoothAddress: String, // 空 = 自动选择已配对 iPhone
) {
    companion object {
        private const val PREFS = "diplay_lite"

        fun load(context: Context): LiteConfig {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return LiteConfig(
                ssid = prefs.getString("ssid", "").orEmpty(),
                password = prefs.getString("password", "").orEmpty(),
                channel = prefs.getInt("channel", 0),
                security = prefs.getString("security", "WPA2") ?: "WPA2",
                bluetoothAddress = prefs.getString("bt_address", "").orEmpty(),
            )
        }

        fun save(context: Context, config: LiteConfig) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("ssid", config.ssid)
                .putString("password", config.password)
                .putInt("channel", config.channel)
                .putString("security", config.security)
                .putString("bt_address", config.bluetoothAddress)
                .apply()
        }
    }
}
