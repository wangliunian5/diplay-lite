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
    /** vc176（K60 实测）：controller 正处 0x52 重枚举等待期。此时旧 USB 实例的
     *  DETACHED 是重枚举的必经步骤、不是用户拔线——MainActivity 的拔线监听据此
     *  忽略，避免 stopService 杀掉重枚举兜底、误弹「控制通道已结束」。 */
    @Volatile var reenumerating = false

    val active: Boolean get() = phase == Phase.ACTIVE
}
