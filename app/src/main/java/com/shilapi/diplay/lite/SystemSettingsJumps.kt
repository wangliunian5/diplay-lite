package com.shilapi.diplay.lite

import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.provider.Settings
import android.widget.EditText
import android.widget.Toast

/**
 * M6k24：系统设置直达 + iPhone 蓝牙选择，主页快捷入口与热点配置页共用。
 * 全部从 HotspotConfigActivity 原实现抽取（行为不变，三级降级保留）。
 */
object SystemSettingsJumps {

    /** Wi-Fi 设置：WLAN 页 → 无线和网络 → 失败 toast。 */
    fun openWifiSettings(activity: Activity) {
        val attempts = listOf(
            "WLAN 设置页" to Intent(Settings.ACTION_WIFI_SETTINGS),
            "无线和网络设置" to Intent(Settings.ACTION_WIRELESS_SETTINGS),
        )
        for ((label, intent) in attempts) {
            try {
                activity.startActivity(intent)
                LiteLog.log("ui", "Wi-Fi设置：已打开$label")
                return
            } catch (error: Exception) {
                LiteLog.log("ui", "Wi-Fi设置：$label 不可用（${error.javaClass.simpleName}）")
            }
        }
        toast(activity, "本机无法打开 Wi-Fi 设置，请手动连接")
    }

    /** 蓝牙设置：蓝牙页 → 系统设置首页 → 失败 toast。 */
    fun openBluetoothSettings(activity: Activity) {
        try {
            activity.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            LiteLog.log("ui", "蓝牙设置：已打开系统蓝牙页")
            return
        } catch (error: Exception) {
            LiteLog.log("ui", "蓝牙设置：直达失败（${error.javaClass.simpleName}）")
        }
        try {
            activity.startActivity(Intent(Settings.ACTION_SETTINGS))
        } catch (_: Exception) {
            toast(activity, "本机无法打开系统设置，请手动配对")
        }
    }

    /** 热点设置：TetherSettings 组件 → 隐式 TETHER_SETTINGS → 无线和网络 → 失败 toast。 */
    fun openHotspotSettings(activity: Activity) {
        val attempts = listOf(
            "热点/网络共享页" to Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
            "热点/网络共享页（隐式）" to Intent("android.settings.TETHER_SETTINGS"),
            "无线和网络设置" to Intent(Settings.ACTION_WIRELESS_SETTINGS),
        )
        for ((label, intent) in attempts) {
            try {
                activity.startActivity(intent)
                LiteLog.log("ui", "热点设置：已打开$label")
                return
            } catch (error: Exception) {
                LiteLog.log("ui", "热点设置：$label 不可用（${error.javaClass.simpleName}）")
            }
        }
        toast(activity, "本机没有可直达的热点设置，请在系统设置里手动开启")
    }

    /**
     * 选择 iPhone（蓝牙）：已配对设备列表 + 手动输入 + 清除选择。
     * 主页「选择 iPhone」与热点配置页「蓝牙选择」共用；选中经 [onPicked] 回调落盘
     * （LiteConfig.saveBluetoothPick，全局一份，两处实时一致）。
     */
    fun showIphonePickerDialog(activity: Activity, onPicked: (address: String, name: String) -> Unit) {
        val adapter: BluetoothAdapter? = try {
            BluetoothAdapter.getDefaultAdapter()
        } catch (_: Throwable) {
            null
        }
        if (adapter == null) {
            toast(activity, "本机没有蓝牙适配器，请手动输入地址")
            return
        }
        val devices = adapter.bondedDevices.orEmpty()
            .sortedWith(compareBy({ it.name?.lowercase() ?: "" }, { it.address }))
        if (devices.isEmpty()) {
            LiteLog.log("ui", "蓝牙选择：无已配对设备")
            toast(activity, "没有已配对的蓝牙设备，请先在系统设置里与 iPhone 配对")
        }
        val labels = ArrayList<String>(devices.size + 2)
        devices.forEach { labels.add("${it.name ?: "未知设备"}\n${it.address}") }
        labels.add("手动输入地址…")
        labels.add("清除选择（恢复自动）")
        AlertDialog.Builder(activity)
            .setTitle("选择 iPhone 蓝牙设备")
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which < devices.size -> {
                        val device = devices[which]
                        onPicked(device.address, device.name ?: "")
                    }
                    which == devices.size -> promptManualBluetoothAddress(activity, onPicked)
                    else -> onPicked("", "")
                }
            }
            .show()
    }

    private fun promptManualBluetoothAddress(activity: Activity, onPicked: (address: String, name: String) -> Unit) {
        val current = LiteConfig.load(activity).bluetoothAddress
        val input = EditText(activity).apply {
            setSingleLine()
            hint = "AA:BB:CC:DD:EE:FF"
            setText(current)
        }
        AlertDialog.Builder(activity)
            .setTitle("手动输入蓝牙地址")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                onPicked(input.text.toString().trim().uppercase(), "")
            }
            .show()
    }

    private fun toast(activity: Activity, message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }
}
