package com.shilapi.diplay.lite

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.util.Log

/**
 * 开机自启：
 * - 勾选"开机自启" → 拉起主页（连接与否交给主页的"自动连接"逻辑）；
 * - 1.0.11：勾选"开机自动开启蓝牙" → BluetoothAdapter.enable()（API16-23 公开方法，静默执行），
 *   轮询等待 STATE_ON；
 * - 1.0.11：勾选"开机自动开启热点" → 蓝牙就绪后反射 setWifiApEnabled(null, true)
 *   （沿用系统已有 AP 配置；STA/AP 互斥，先关 Wi-Fi 再起 AP）。
 *
 * 实测注意：部分 ROM（EMUI 等）自启动管理可能拦截 BOOT_COMPLETED，属系统行为，本程序无法绕过。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val config = LiteConfig.load(context)
        if (!config.bootAutostart && !config.bootAutoBluetooth && !config.bootAutoHotspot) return
        LiteLog.log(
            "boot",
            "BOOT_COMPLETED autostart=${config.bootAutostart} autoBt=${config.bootAutoBluetooth} " +
                "autoAp=${config.bootAutoHotspot} autoConnect=${config.autoConnect}",
        )
        // enable()/setWifiApEnabled() + 轮询等待耗时超过 onReceive 上限，投后台线程 + goAsync。
        // 1.0.16（方案四②）：主页先拉起并弹「等待勾选项启动」弹窗（等待/滤波/超时都在 UI 层），
        // 再在后台跑勾选项启动——避免蓝牙轮询 20s 期间主页迟迟不出现。
        val pending = goAsync()
        Thread {
            try {
                if (config.bootAutostart || config.autoConnect) {
                    context.startActivity(
                        Intent(context, MainActivity::class.java)
                            .putExtra(MainActivity.EXTRA_AUTOSTART, true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    LiteLog.log("boot", "开机自启：拉起主页（自启等待弹窗由主页接管）")
                }
                if (config.bootAutoBluetooth) ensureBluetoothOn()
                if (config.bootAutoHotspot) ensureHotspotOn(context)
                LiteLog.log("boot", "开机自启流程完成")
            } catch (error: Throwable) {
                LiteLog.log("boot", "开机自启流程失败: ${Log.getStackTraceString(error)}")
            } finally {
                pending.finish()
            }
        }.start()
    }

    /** 开启蓝牙并等待就绪（最长 20 秒）。部分 ROM 拒绝静默 enable()，记日志继续。 */
    private fun ensureBluetoothOn() {
        val adapter = try {
            BluetoothAdapter.getDefaultAdapter()
        } catch (error: Throwable) {
            LiteLog.log("boot", "蓝牙适配器获取失败: ${error.message}")
            return
        }
        if (adapter == null) {
            LiteLog.log("boot", "蓝牙适配器不可用")
            return
        }
        if (adapter.isEnabled) {
            LiteLog.log("boot", "蓝牙已开启")
            return
        }
        val requested = try {
            adapter.enable()
        } catch (error: Throwable) {
            LiteLog.log("boot", "蓝牙 enable() 调用失败: ${error.message}")
            false
        }
        if (!requested) {
            LiteLog.log("boot", "蓝牙 enable() 被拒绝（ROM 自启动管理/权限策略），跳过等待")
            return
        }
        var waited = 0
        while (waited < 20_000 && !adapter.isEnabled) {
            try {
                Thread.sleep(1_000)
            } catch (_: InterruptedException) {
                break
            }
            waited += 1_000
        }
        LiteLog.log("boot", "蓝牙开启${if (adapter.isEnabled) "就绪" else "超时"} waited=${waited}ms")
    }

    /**
     * 开启热点（沿用系统已有 AP 配置）。反射调用隐藏 API setWifiApEnabled（API16-25 可用）。
     * T57 教训：低版本兼容对方法存在性一律反射 + runCatching，不信任白牌 ROM。
     */
    private fun ensureHotspotOn(context: Context) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi == null) {
            LiteLog.log("boot", "WifiManager 不可用，跳过热点自启")
            return
        }
        val apEnabled = runCatching {
            // WIFI_AP_STATE_ENABLED = 13（API 16-25 各版本常量一致）
            WifiManager::class.java.getMethod("getWifiApState").invoke(wifi) as Int == 13
        }.getOrDefault(false)
        if (apEnabled) {
            LiteLog.log("boot", "热点已开启")
            return
        }
        // STA/AP 模式互斥：先关 Wi-Fi 再起 AP
        runCatching {
            WifiManager::class.java
                .getMethod("setWifiEnabled", Boolean::class.javaPrimitiveType)
                .invoke(wifi, false)
        }.onFailure { LiteLog.log("boot", "关闭 Wi-Fi 失败: ${it.message}") }
        try {
            Thread.sleep(800)
        } catch (_: InterruptedException) {
        }
        val started = runCatching {
            WifiManager::class.java
                .getMethod("setWifiApEnabled", WifiConfiguration::class.java, Boolean::class.javaPrimitiveType)
                .invoke(wifi, null, true) as Boolean
        }.getOrDefault(false)
        LiteLog.log("boot", "热点自启 setWifiApEnabled=$started")
    }
}
