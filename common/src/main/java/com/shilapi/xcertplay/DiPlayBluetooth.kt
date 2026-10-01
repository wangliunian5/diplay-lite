package com.shilapi.xcertplay

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.provider.Settings

internal object DiPlayBluetooth {
    fun localAddress(context: Context): String? {
        // On Android 12+ (API 31) BluetoothAdapter.getAddress() throws SecurityException
        // without android.permission.BLUETOOTH_CONNECT. The caller provides a fallback MAC, so
        // any failure here must return null, never propagate. The whole adapter read is wrapped
        // because the Address getter itself throws. Keep the returned value verbatim: filtering
        // out 02:00:.. / 00:00:.. here would silently change btMac into the deviceId fallback and
        // break wireless CarPlay identity matching.
        val adapter = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                context.getSystemService(BluetoothManager::class.java)?.adapter?.address
            } else {
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothAdapter)?.address
            }
        } catch (_: SecurityException) {
            null
        } catch (_: NullPointerException) {
            null
        } catch (_: Exception) {
            null
        }
        val setting = runCatching { Settings.Secure.getString(context.contentResolver, "bluetooth_address") }.getOrNull()
        return listOfNotNull(adapter, setting).firstOrNull {
            Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}").matches(it) &&
                !it.startsWith("02:00:00:00:00:") && it != "00:00:00:00:00:00"
        }
    }
}
