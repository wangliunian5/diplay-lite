package com.shilapi.xcertplay

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.provider.Settings

internal object DiPlayBluetooth {
    fun localAddress(context: Context): String? {
        val adapter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.address
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothAdapter)?.address
        }?.let { runCatching { it }.getOrNull() }
        val setting = runCatching { Settings.Secure.getString(context.contentResolver, "bluetooth_address") }.getOrNull()
        return listOfNotNull(adapter, setting).firstOrNull {
            Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}").matches(it) &&
                !it.startsWith("02:00:00:00:00:") && it != "00:00:00:00:00:00"
        }
    }
}
