package com.shilapi.xcertplay.network

import android.content.Intent
import android.net.VpnService
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * USB 有线模式的最小 VpnService：只负责建立 fe80:: 链路本地 TUN 并把 fd 交给控制器。
 *
 * NCM 数据面不产生内核网络接口，必须经 TUN 中转（Ipv6NcmBridge 双向泵）。
 * 相对上游 CarPlayVpnService（AirPlay 监听、Binder、service 内编排全部裁掉）：
 * Lite 的 AirPlay 监听仍走进程内 AirPlayTcpServer(bindAddress=linkLocal)，
 * 本 Service 仅承担 establish() 这个需要系统权限的动作。
 *
 * 使用顺序：MainActivity 先 VpnService.prepare() 征得用户同意 → startService →
 * 控制器线程 awaitTun() 取 fd → 用完 shutdown() 关 fd 并停服务。
 */
class WiredVpnService : VpnService() {
    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val linkLocal = intent?.getStringExtra(EXTRA_LINK_LOCAL) ?: DEFAULT_LINK_LOCAL
        val tun = try {
            val builder = Builder()
                .addAddress(linkLocal, 64)
                .addRoute("fe80::", 64)
                .setSession("DiPlay Wired CarPlay")
                .setMtu(MTU)
            // 1.0.17（FE-6 实证）：Android 5.1 上 VpnService 网络建立后会抢占系统默认网络，
            // 而 TUN 只有 fe80::/64 路由 → IPv4 全部落进无 internet 的 VPN，车机侧报
            // ENETUNREACH（12:41:54 日志上传失败实证），断开后若 ROM 未正确撤销则持续断网。
            // allowFamily(AF_INET) 让 IPv4 流量绕过 VPN，IPv4 默认网络不再被抢占。
            // T57 教训：白牌 ROM 的 SDK_INT 可能谎报，「方法存在性」用反射探测而非直接调用。
            try {
                VpnService.Builder::class.java
                    .getMethod("allowFamily", Int::class.javaPrimitiveType)
                    .invoke(builder, 2 /* AF_INET */)
                Log.i(TAG, "wired vpn allowFamily(AF_INET) applied")
            } catch (error: Throwable) {
                Log.w(TAG, "wired vpn allowFamily unavailable: ${error.message}")
            }
            builder.establish()
        } catch (error: Throwable) {
            Log.w(TAG, "wired vpn establish failed: ${error.message}")
            null
        }
        if (tun != null) {
            ensureFe80Route()
            ensureTunMulticast()
        }
        synchronized(lock) {
            // vc94：快速重连二次 startService 而 request() 未再调用时（或并发竞态），旧 fd 会被
            // 直接覆盖泄漏——establish 前先关存量 fd（request() 路径此时已置 null，no-op）。
            established?.let { runCatching { it.close() } }
            established = tun
            latch.countDown()
        }
        // 不常驻：fd 交付后即可停止；控制器持有 fd 的所有权。
        stopSelf(startId)
        return START_NOT_STICKY
    }

    /**
     * vc109（T3/KK 3.10 实测定案，pcap+ipv6_route 双证据）：KK 的 ConnectivityService
     * 会把 addRoute("fe80::",64) **静默丢弃**（/proc/net/ipv6_route 无任何 tun 条目，
     * API29 上正常）——后果链：iPhone SYN 到达 TUN → 内核 TCP 回 SYN-ACK 查不到去往
     * 对端 fe80:: 的路由 → 落到 wlan0 出真实网络 → AirPlay 永不建链（零 TCP 出向帧）。
     * 建立 TUN 后校验内核路由表，缺失且有 root（T3 有 /system/xbin/su）时补挂；
     * 无 root 仅记录不阻塞（FE-6 等 5.1+ 机型 addRoute 生效，不会走到这里）。
     */
    private fun ensureFe80Route() {
        try {
            val dev = findTunInterface()
            if (dev == null) {
                Log.w(TAG, "wired vpn route check: no tun interface found")
                return
            }
            if (hasFe80Route(dev)) {
                Log.i(TAG, "wired vpn fe80 route present on $dev")
                return
            }
            val add = "ip -6 route add fe80::/64 dev $dev"
            var repaired = false
            for (shell in arrayOf(arrayOf("su", "-c", add), arrayOf("/system/xbin/su", "-c", add))) {
                try {
                    val proc = Runtime.getRuntime().exec(shell)
                    val exit = proc.waitFor()
                    val out = proc.inputStream.bufferedReader().use { it.readText() }.trim()
                    proc.destroy()
                    if (hasFe80Route(dev)) {
                        Log.i(TAG, "wired vpn fe80 route repaired via $shell exit=$exit out=$out")
                        repaired = true
                        break
                    }
                } catch (error: Throwable) {
                    Log.w(TAG, "wired vpn route repair attempt failed: ${error.message}")
                }
            }
            if (!repaired) {
                Log.w(TAG, "wired vpn fe80 route still missing on $dev; AirPlay connect will stall")
            }
        } catch (error: Throwable) {
            Log.w(TAG, "wired vpn route check failed: ${error.message}")
        }
    }

    /**
     * vc123（T3/KK 实测定案）：VpnService 建出的 tun 接口**缺 MULTICAST 标志**
     * （<POINTOPOINT,UP,LOWER_UP>，igmp6 组成员已 join 但内核不向成员投递组播），
     * iPhone 的 mDNS 组播查询永远到不了应答器 socket（pcap 实锤查询刷屏零应答）。
     * root 补 `ip link set <dev> multicast on`；无 root 仅记录（FE-6 等 5.1+ 正常）。
     */
    private fun ensureTunMulticast() {
        try {
            val dev = findTunInterface()
            if (dev == null) return
            if (hasMulticastFlag(dev)) {
                Log.i(TAG, "wired vpn multicast flag present on $dev")
                return
            }
            for (shell in arrayOf(
                arrayOf("su", "-c", "ip link set $dev multicast on"),
                arrayOf("/system/xbin/su", "-c", "ip link set $dev multicast on"),
            )) {
                try {
                    val proc = Runtime.getRuntime().exec(shell)
                    proc.waitFor()
                    proc.destroy()
                    if (hasMulticastFlag(dev)) {
                        Log.i(TAG, "wired vpn multicast flag repaired on $dev")
                        return
                    }
                } catch (_: Throwable) {
                }
            }
            Log.w(TAG, "wired vpn multicast flag still missing on $dev; mdns queries undeliverable")
        } catch (error: Throwable) {
            Log.w(TAG, "wired vpn multicast check failed: ${error.message}")
        }
    }

    private fun hasMulticastFlag(dev: String): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("ip", "link", "show", dev))
            val out = proc.inputStream.bufferedReader().use { it.readText() }
            proc.waitFor()
            out.contains("MULTICAST")
        } catch (_: Throwable) {
            false
        }
    }

    private fun findTunInterface(): String? {
        val dev = java.io.File("/proc/net/dev")
        return dev.takeIf { it.canRead() }?.bufferedReader()?.useLines { lines ->
            lines.map { it.substringBefore(':').trim() }
                .firstOrNull { it.startsWith("tun") }
        }
    }

    private fun hasFe80Route(dev: String): Boolean {
        val route = java.io.File("/proc/net/ipv6_route")
        return route.takeIf { it.canRead() }?.bufferedReader()?.useLines { lines ->
            lines.any { line ->
                val fields = line.trim().split(Regex("\\s+"))
                fields.size >= 10 &&
                    fields[0] == "fe800000000000000000000000000000" &&
                    fields[1] == "40" &&
                    fields[9] == dev
            }
        } ?: false
    }

    companion object {
        private const val TAG = "xcertplay-usb"
        const val EXTRA_LINK_LOCAL = "link_local"
        const val DEFAULT_LINK_LOCAL = "fe80::2"
        const val MTU = 1500

        private val lock = Any()
        private var established: ParcelFileDescriptor? = null
        private var latch = CountDownLatch(0)

        /** 拉起服务并请求建立 TUN；返回等待句柄。 */
        fun request(linkLocal: String): CountDownLatch {
            synchronized(lock) {
                established?.let { old ->
                    runCatching { old.close() }
                    established = null
                }
                latch = CountDownLatch(1)
                return latch
            }
        }

        /** 控制器线程等待 fd（establish 在主线程 onStartCommand 里完成）。 */
        fun awaitTun(timeoutMillis: Long): ParcelFileDescriptor? {
            latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
            synchronized(lock) { return established }
        }

        /** 关闭当前 TUN fd（控制器 teardown 时调用）。 */
        fun shutdownTun() {
            synchronized(lock) {
                established?.let { runCatching { it.close() } }
                established = null
            }
        }
    }
}
