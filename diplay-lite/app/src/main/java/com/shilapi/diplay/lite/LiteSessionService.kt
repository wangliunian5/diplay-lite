package com.shilapi.diplay.lite

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * 前台会话服务：无线 CarPlay 期间保活，避免 4.1 后台回收。
 *
 * M0 骨架阶段：仅前台化 + 日志；
 * M2 接入点：在此构造 CarPlayController（裁剪后的 shared 模块），按 MANUAL 配置发起无线流水线。
 *
 * 注意 4.1 兼容：startForeground(id, Notification) 为 API 5；
 * Notification.Builder(Context) 单参构造为 API 11；build() 是 API 24 才有，须用 getNotification()。
 */
@Suppress("DEPRECATION")
class LiteSessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        LiteState.running = true
        LiteState.phase = LiteState.Phase.CONNECTING
        LiteState.statusText = "M0 骨架：前台服务运行中（连接逻辑待 M2 接入）"
        LiteLog.log("service", "前台会话服务已启动")
        // TODO(M2): 读取 LiteConfig.load(this)，构造 CarPlayController 并 runWireless()
        return START_STICKY
    }

    override fun onDestroy() {
        // TODO(M2): 先按序拆除 CarPlayController（AirPlay -> 隧道 -> 蓝牙），再退前台
        LiteState.running = false
        LiteState.phase = LiteState.Phase.IDLE
        LiteState.statusText = "未连接"
        stopForeground(true)
        LiteLog.log("service", "会话服务已停止")
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this)
            .setSmallIcon(android.R.drawable.ic_media_play) // 零图片资源策略：借用系统图标
            .setContentTitle("DiPlay Lite")
            .setContentText("无线 CarPlay 会话保持中")
            .setOngoing(true)
            .getNotification()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
    }
}
