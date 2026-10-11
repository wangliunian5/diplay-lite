package com.shilapi.xcertplay.network

import android.content.Context

/**
 * 1.0.15：热点「成功配置记忆」（借鉴 carlito12345/DiPlay 上游 PR #175 的
 * P2pConfigurationMemory 思想，适配 SoftAP/中继形态）。
 *
 * 语义：仅在一次 AirPlay 会话真正建立（SESSION_ACTIVE，含 degraded 收口）后，把本次
 * 0x5703 实际下发的信道记住；下次 bring-up 时，若 ROM 读不到信道且用户也没手填
 * （两者都为 0），优先复用上次「验证过能连上」的信道，而不是盲猜 6。
 *
 * 只存单值（按角色 car/relay 各一条），新成功覆盖旧值；不做 band 校验——使用场景本身
 * 就是「什么都读不到」，且车机/热点配置变更后第一次成功会立即覆盖。
 */
object ConfirmedChannelMemory {
    private const val PREFS = "diplay_confirmed_channel"
    private const val KEY_CAR = "car_channel"
    private const val KEY_RELAY = "relay_channel"

    const val ROLE_CAR = "car"
    const val ROLE_RELAY = "relay"

    fun recall(context: Context, role: String): Int =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(if (role == ROLE_RELAY) KEY_RELAY else KEY_CAR, 0)
            .takeIf { it in 1..177 } ?: 0

    fun remember(context: Context, role: String, channel: Int) {
        if (channel !in 1..177) return
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(if (role == ROLE_RELAY) KEY_RELAY else KEY_CAR, channel)
            .apply()
    }
}
