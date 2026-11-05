package com.shilapi.xcertplay.usbbt

/**
 * vc246（USB-246-01）：外置蓝牙重连的唯一节律。
 *
 * vc245 有三套互不相同的节律：常驻广播 2s/4s 无上限、手动重连 1/2/4/8s 五次、
 * DETACHED 500ms 立即重连。本文件把它们收口成同一条序列：
 *
 * `1s → 2s → 4s → 8s（封顶）`，连续 [MAX_ATTEMPTS] 次失败即停并上屏。
 *
 * 常驻广播、手动/掉线重连、DETACHED 既不再各自维护延迟常量，也不再无限重试。
 * 纯策略对象（无 Android 依赖），shared 与 app 两侧共用。
 */
object UsbBtRetryPolicy {
    /** 连续失败上限：达到即停止自动重试（由调用方上屏 + 状态可见）。 */
    const val MAX_ATTEMPTS = 5

    /** 第 n 次失败后的等待（n 从 1 起）：1s/2s/4s/8s/8s…（封顶）。 */
    const val MIN_DELAY_MILLIS = 1_000L
    const val MAX_DELAY_MILLIS = 8_000L

    /** 已失败 [attempt] 次后的退避时长（attempt<=0 按 0 处理）。 */
    fun delayAfterFailure(attempt: Int): Long {
        if (attempt <= 0) return 0L
        val shift = minOf(attempt - 1, 3)
        return (MIN_DELAY_MILLIS shl shift).coerceAtMost(MAX_DELAY_MILLIS)
    }
}
