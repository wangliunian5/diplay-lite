package com.shilapi.xcertplay.network

import android.util.Log
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import java.io.Closeable
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * vc68（TS7 0x4301 后 iPhone 数据面停摆根因修复）：有线模式 mDNS 应答器。
 *
 * 实测（20261013 包级取证）：iPhone(iOS 16.x) 在 NCM 链路上 mDNS 查询
 * `_airplay._tcp`/`_raop._tcp`/`_companion-link._tcp`，无应答则拒绝起 CarPlay
 * 会话——0x4301 下发的 fe80::2:7100 不足以让 iOS 直接建链。Lite 有线路径此前
 * 不注册任何 Bonjour（无线靠热点接口上的 JmDNS/NSD 应答，有线 tun0 上无人应答）。
 *
 * 绑定 tun 地址（fe80::2）创建 JmDNS，注册 airplay+raop 两个服务；查询经
 * Ipv6NcmBridge→tun0→内核多播送达本应答器，应答反向走同一链路。
 */
class WiredBonjourAdvertiser(
    private val bindAddress: InetAddress,
    private val config: AirPlayConfig,
    private val identity: AirPlayIdentity,
    private val onDiagnostic: (String) -> Unit = {},
) : Closeable {
    private val started = AtomicBoolean(false)
    @Volatile
    private var dns: JmDNS? = null

    fun start() {
        check(started.compareAndSet(false, true)) { "wired bonjour already started" }
        Thread({
            try {
                val mdns = JmDNS.create(bindAddress, "carplay-wired-${config.deviceId.replace(":", "")}")
                dns = mdns
                val txt = CarPlayBonjourProtocol.airPlayTxtRecords(config, identity)
                mdns.registerService(
                    ServiceInfo.create(
                        "_airplay._tcp.local.", config.deviceName, config.port, 0, 0, txt,
                    ),
                )
                val raopName = "${config.deviceId.replace(":", "")}@${config.deviceName}"
                mdns.registerService(
                    ServiceInfo.create(
                        "_raop._tcp.local.", raopName, config.port, 0, 0, txt,
                    ),
                )
                onDiagnostic("wired bonjour registered on $bindAddress port=${config.port} (airplay+raop)")
            } catch (error: Exception) {
                // 非致命降级：应答器失败不阻塞有线栈，暴露 0x4301 直连路径（老 iOS 行为）。
                Log.w(TAG, "wired bonjour start failed", error)
                onDiagnostic(
                    "wired bonjour start failed (non-fatal): ${error.javaClass.simpleName}: ${error.message}",
                )
                runCatching { dns?.close() }
                dns = null
            }
        }, "wired-bonjour").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        val mdns = dns ?: return
        dns = null
        // JmDNS.close 发 goodbye 可能阻塞，异步收尾不拖慢 teardown。
        Thread({
            runCatching { mdns.unregisterAllServices() }
            runCatching { mdns.close() }
        }, "wired-bonjour-stop").apply {
            isDaemon = true
            start()
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
    }
}
