package com.shilapi.xcertplay.media

import android.util.Log

/** Five-second video counters that separate network/iPhone gaps from decoder throughput. */
internal class VideoStats(
    private val label: String = "",
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var windowStartNs = nanoTime()
    private var lastArrivalNs = 0L
    private var received = 0
    private var rendered = 0
    private var recoveries = 0
    // M6k：把"我们主动丢帧/等输入"与"清解码器"分开计数，用于判断花屏是否由丢帧残留引起。
    private var backlogs = 0
    private var inputStalled = 0
    private var flushes = 0
    private var bytes = 0L
    private var maxArrivalGapNs = 0L
    private var touchSamples = 0
    private var touchLatencySumNs = 0L
    private var maxTouchLatencyNs = 0L
    // M6a：弱机优化的量化依据（CPU 占用 / 线程数）。每个 stats 各持一个实例，窗口独立。
    private val processLoad = ProcessLoadSampler()

    @Synchronized fun onReceived(size: Int) {
        val now = nanoTime()
        val gap = now - lastArrivalNs
        if (lastArrivalNs != 0L && gap < IDLE_GAP_NS) maxArrivalGapNs = maxOf(maxArrivalGapNs, gap)
        lastArrivalNs = now
        // Touches only change the main screen; a second stream must not consume their samples.
        val touchLatency = if (label.isEmpty()) TouchLatencyProbe.onFrame(now) else -1L
        if (touchLatency >= 0) {
            touchSamples++
            touchLatencySumNs += touchLatency
            maxTouchLatencyNs = maxOf(maxTouchLatencyNs, touchLatency)
        }
        received++
        bytes += size
    }

    @Synchronized fun onRendered() { rendered++ }

    @Synchronized fun onRecovery() { recoveries++ }

    @Synchronized fun onBacklog() { backlogs++ }

    @Synchronized fun onInputStalled() { inputStalled++ }

    @Synchronized fun onFlush() { flushes++ }

    /** 1.1.6(vc76)：最近一次收帧时刻（媒体断供看门狗判「视频面死亡」用）。 */
    @Synchronized fun lastArrivalNanos(): Long = lastArrivalNs

    @Synchronized fun logIfDue(): String? {
        val now = nanoTime()
        val elapsedNs = now - windowStartNs
        if (elapsedNs < WINDOW_NS) return null
        val seconds = elapsedNs / 1e9
        if (received == 0 && touchSamples == 0) { windowStartNs = now; return null }
        val touchAvgMs = if (touchSamples == 0) -1 else touchLatencySumNs / touchSamples / 1_000_000
        val rx = received / seconds
        val shown = rendered / seconds
        val maxGapMs = (maxArrivalGapNs / 1_000_000).toInt()
        BandwidthMonitor.setVideoMetrics(rx.toFloat(), shown.toFloat(), maxGapMs, recoveries)
        // M4k：带上堆占用。用户反馈"视频流突然变大后闪退"，而闪退零日志——
        // 若是 OutOfMemoryError（catch(Exception) 兜不住 → 线程死 → 进程被杀），
        // 这行数字会在崩溃前一路爬升，下一次测试即可确认或排除。
        val runtime = Runtime.getRuntime()
        val heapUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val heapMaxMb = runtime.maxMemory() / (1024 * 1024)
        val line = ("video stats$label rx=%.1ffps shown=%.1ffps maxGap=%dms kbps=%d recoveries=%d " +
            "backlog=%d stalled=%d flush=%d " +
            "heap=%d/%dMB touch2frame avg=%dms max=%dms n=%d touchSendMax=%dms").format(
            rx, shown, maxGapMs,
            (bytes * 8 / 1000 / seconds).toLong(), recoveries,
            backlogs, inputStalled, flushes,
            heapUsedMb, heapMaxMb,
            touchAvgMs, maxTouchLatencyNs / 1_000_000, touchSamples, TouchLatencyProbe.maxSendNs / 1_000_000,
        ) + " " + (processLoad.sample(now) ?: "cpu=? threads=?")
        TouchLatencyProbe.maxSendNs = 0
        Log.i(TAG, line)
        windowStartNs = now
        received = 0; rendered = 0; recoveries = 0; backlogs = 0; inputStalled = 0; flushes = 0
        bytes = 0; maxArrivalGapNs = 0
        touchSamples = 0; touchLatencySumNs = 0; maxTouchLatencyNs = 0
        return line
    }

    private companion object {
        const val TAG = "DiPlay-VideoStats"
        const val WINDOW_NS = 5_000_000_000L
        const val IDLE_GAP_NS = 2_000_000_000L // longer gaps are a static screen, not lag
    }
}
