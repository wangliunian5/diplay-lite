package com.shilapi.xcertplay.hud

import android.content.Context

/**
 * One user switch for BYD navigation output. On the tested car the windshield HUD mirrors what the
 * instrument cluster receives, so separate HUD/cluster switches cannot behave independently.
 */
object BydOutputSettings {
    private const val PREFS = "diplay_byd_outputs"
    private const val KEY_ENABLED = "navigation_enabled"

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()

    /** Whether the head unit has a BYD navigation receiver, so settings can hide a switch that cannot work. */
    fun available(context: Context): Boolean =
        BydStandaloneHudOutput.available(context) || installed(context, "com.byd.amapservice") || installed(context, "com.ts.car.someip.service")

    private fun installed(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
