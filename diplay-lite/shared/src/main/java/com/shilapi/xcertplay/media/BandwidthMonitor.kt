package com.shilapi.xcertplay.media

import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Per-second bandwidth & jitter probe. The A/V decoders feed raw byte counters and jitter metrics
 * in from background threads; the on-screen overlay ticks() once a second to flush a normalized
 * sample to subscribers (curve + the exported diplay.log "BW …" line). Pure Kotlin so it can live
 * in the shared module with the decoders.
 */
object BandwidthMonitor {

    data class Sample(
        val videoKbps: Int,
        val audioKbps: Int,
        val rx: Float,
        val shown: Float,
        val videoMaxGapMs: Int,
        val audioMaxGapMs: Int,
        val videoRecoveries: Int,
        val audioUnderruns: Int,
        val audioRebuffers: Int,
    ) {
        fun describe(): String = "video=${videoKbps}k audio=${audioKbps}k " +
            "rx=%.1f shown=%.1f vGap=%dms aGap=%dms vRec=%d aUnd=%d rebuf=%d"
            .format(rx, shown, videoMaxGapMs, audioMaxGapMs, videoRecoveries, audioUnderruns, audioRebuffers)
    }

    /** Persist the per-second line; set by the host activity to append into diplay.log. */
    @Volatile
    var onBwSample: ((String) -> Unit)? = null

    /**
     * Process-local switch armed by tapping the home-screen logo five times. Unlike the persisted
     * "调试日志" preference, it resets whenever the process is killed (cold start), so it must be
     * re-armed with five taps after each relaunch. The host activity ORs this in when deciding
     * whether to show the bandwidth panel / write BW logs.
     */
    @Volatile
    var overlayRequested = false

    private val listeners = CopyOnWriteArrayList<(Sample) -> Unit>()
    private val lock = Any()
    private var lastFlushNs = System.nanoTime()

    private var videoBytes = 0L
    private var audioBytes = 0L
    private var rxFrames = 0f
    private var shownFrames = 0f
    private var videoMaxGapMs = 0
    private var audioMaxGapMs = 0
    private var videoRecoveries = 0
    private var audioUnderruns = 0
    private var audioRebuffers = 0

    private val history = ArrayDeque<Sample>()

    val capacity: Int get() = 60

    fun addListener(listener: (Sample) -> Unit) { listeners += listener }
    fun removeListener(listener: (Sample) -> Unit) { listeners -= listener }

    fun onVideoBytes(bytes: Int) = synchronized(lock) { videoBytes += bytes }
    fun onAudioBytes(bytes: Int) = synchronized(lock) { audioBytes += bytes }

    fun setVideoMetrics(rx: Float, shown: Float, maxGapMs: Int, recoveries: Int) = synchronized(lock) {
        rxFrames = rx
        shownFrames = shown
        if (maxGapMs > videoMaxGapMs) videoMaxGapMs = maxGapMs
        if (recoveries > videoRecoveries) videoRecoveries = recoveries
    }

    fun setAudioMetrics(maxGapMs: Int, underruns: Int, rebuffers: Int) = synchronized(lock) {
        if (maxGapMs > audioMaxGapMs) audioMaxGapMs = maxGapMs
        if (underruns > audioUnderruns) audioUnderruns = underruns
        audioRebuffers = rebuffers
    }

    fun snapshots(): List<Sample> = synchronized(lock) { history.toList() }

    /**
     * Called once a second by the UI overlay. Flushes the accumulated byte/jitter counters into a
     * normalized per-second sample, fans it out to listeners, and persists a "BW …" line.
     */
    fun tick(): Sample? {
        val sample = synchronized(lock) {
            val now = System.nanoTime()
            val secs = (now - lastFlushNs) / 1e9
            if (secs < 0.9) return null
            val vKbps = (videoBytes * 8 / 1000 / secs).toInt()
            val aKbps = (audioBytes * 8 / 1000 / secs).toInt()
            val s = Sample(
                videoKbps = vKbps,
                audioKbps = aKbps,
                rx = rxFrames,
                shown = shownFrames,
                videoMaxGapMs = videoMaxGapMs,
                audioMaxGapMs = audioMaxGapMs,
                videoRecoveries = videoRecoveries,
                audioUnderruns = audioUnderruns,
                audioRebuffers = audioRebuffers,
            )
            history.addLast(s)
            while (history.size > capacity) history.removeFirst()
            videoBytes = 0; audioBytes = 0; rxFrames = 0f; shownFrames = 0f
            videoMaxGapMs = 0; audioMaxGapMs = 0; videoRecoveries = 0; audioUnderruns = 0; audioRebuffers = 0
            lastFlushNs = now
            s
        } ?: return null
        onBwSample?.invoke(sample.describe())
        listeners.forEach { runCatching { it(sample) } }
        return sample
    }

    fun reset() {
        synchronized(lock) {
            history.clear()
            lastFlushNs = System.nanoTime()
            videoBytes = 0; audioBytes = 0; rxFrames = 0f; shownFrames = 0f
            videoMaxGapMs = 0; audioMaxGapMs = 0; videoRecoveries = 0; audioUnderruns = 0; audioRebuffers = 0
        }
    }
}
