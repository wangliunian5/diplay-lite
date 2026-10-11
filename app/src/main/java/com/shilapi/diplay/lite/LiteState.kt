package com.shilapi.diplay.lite

/**
 * 进程内共享状态：前台服务与 UI 之间的最轻量通道（不用广播/不用 AndroidX）。
 * M2 接入 CarPlayController 后，状态源改为其 onStatus 回调。
 */
object LiteState {
    enum class Phase { IDLE, CONNECTING, ACTIVE, FAILED }

    @Volatile var running = false
    @Volatile var phase = Phase.IDLE
    @Volatile var statusText = "未连接"

    val active: Boolean get() = phase == Phase.ACTIVE
}
