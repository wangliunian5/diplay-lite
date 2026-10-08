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
        synchronized(lock) {
            established = tun
            latch.countDown()
        }
        // 不常驻：fd 交付后即可停止；控制器持有 fd 的所有权。
        stopSelf(startId)
        return START_NOT_STICKY
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
