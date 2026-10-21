package com.shilapi.xcertplay.media

import java.util.concurrent.LinkedBlockingQueue

/**
 * 检测「持续的」压缩媒体包积压（移植自上游 0.2.21 MediaPacketLagGuard，#814）。
 *
 * 判据独立于包数/编码帧长，直接用 RTP 时间戳跨度算积压时长：短暂的 Wi-Fi 追包突发
 * （几百 ms 的堆积瞬间到位）绝不能被丢弃；只有积压 >1.5s（CONGESTION_THRESHOLD_NS）且
 * 再持续 1.5s（CONGESTION_GRACE_NS）未消化，才认定是「解码跟不上/远端超前发送」的
 * 真积压，把过期未解码包丢到剩 0.5s（RECOVERY_TARGET_NS），追平到正在唱的位置。
 *
 * 纯 Kotlin + java.util，无任何 Android API —— API16 安全，也可在 JVM 单测。
 */
internal class MediaPacketLagGuard(private val clockRate: Int) {
    private var congestionSinceNs = 0L
    private var recovering = false

    /** 队首第一个未投递 RTP 包到最新包的时间跨度（毫秒），跨 32 位回绕安全。 */
    fun <T> pendingFrames(queue: LinkedBlockingQueue<T>, newestSample: Int, timestampOf: (T) -> Int): Long {
        if (clockRate <= 0) return 0
        val first = queue.peek() ?: return 0
        val frames = (newestSample.toLong() - timestampOf(first).toLong()) and 0xffff_ffffL
        // iPhone 在切歌/流重启时会把 RTP 时间戳归零重计：巨大的无符号差值不是积压，
        // 不能当成拥塞（跨回绕后 (newest - first) and 0xffffffff 会变成巨大值）。
        // 上限 16s：正常积压不会超过它，超过即视为时间戳重启，按 0 处理。
        return if (frames <= clockRate * MAX_VALID_SPAN_SECONDS.toLong()) frames else 0
    }

    /**
     * 每个新包到达时调用。处于恢复态且回到 0.2s（RECOVERY_END_NS）以内即退出恢复；
     * 恢复中把积压丢到 0.5s 为止。返回本次丢弃的包数（0 = 无动作）。
     */
    fun <T> recover(
        queue: LinkedBlockingQueue<T>,
        newestSample: Int,
        timestampOf: (T) -> Int,
        nowNs: Long,
    ): Int {
        val durationFrames = pendingFrames(queue, newestSample, timestampOf)
        val delayNs = durationFrames * 1_000_000_000L / clockRate.coerceAtLeast(1)
        if (!recovering) {
            // 两段式判定：先要求积压本身超过 1.5s，再要求这个状态持续 1.5s ——
            // 一过性突发在 grace 期内自愈，绝不丢帧。
            if (delayNs <= CONGESTION_THRESHOLD_NS) {
                congestionSinceNs = 0
                return 0
            }
            if (congestionSinceNs == 0L) {
                congestionSinceNs = nowNs
                return 0
            }
            if (nowNs - congestionSinceNs < CONGESTION_GRACE_NS) return 0
            recovering = true
        }
        if (delayNs <= RECOVERY_END_NS) {
            recovering = false
            congestionSinceNs = 0
            return 0
        }
        var removed = 0
        while (queue.isNotEmpty() &&
            pendingFrames(queue, newestSample, timestampOf) * 1_000_000_000L /
            clockRate.coerceAtLeast(1) > RECOVERY_TARGET_NS) {
            if (queue.poll() == null) break
            removed++
        }
        return removed
    }

    internal companion object {
        const val MAX_VALID_SPAN_SECONDS = 16
        /** 积压拥塞判定阈值：1.5s（#814 判据）。 */
        const val CONGESTION_THRESHOLD_NS = 1_500_000_000L
        /** 拥塞须持续 1.5s 才动手，盖住一过性追包突发。 */
        const val CONGESTION_GRACE_NS = 1_500_000_000L
        /** 恢复目标：丢到只剩 0.5s 积压为止。 */
        const val RECOVERY_TARGET_NS = 500_000_000L
        /** 积压回到 0.2s 以内退出恢复态。 */
        const val RECOVERY_END_NS = 200_000_000L
    }
}
