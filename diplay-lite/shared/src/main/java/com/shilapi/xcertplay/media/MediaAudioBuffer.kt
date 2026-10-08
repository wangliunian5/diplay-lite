package com.shilapi.xcertplay.media

/**
 * Jitter buffer for CarPlay music. Wireless CarPlay delivers audio over the same Wi-Fi link as
 * video; radio gaps of several hundred milliseconds are normal, so music needs a buffer that
 * outlasts them. Calls, Siri and navigation prompts keep the small low-latency buffer.
 */
object MediaAudioBuffer {
    // 2026-10-03（T29，C8815/API16 实测）：无线抖动可达 ~1.3s（`maxGapMs=1310`、累计补偿 `gapCompMs=1450`），
    // 而起播阈值 300ms + 200ms 余量只有 500ms 容量，盖不住突发。
    // 做法：**起播阈值保持不变**（切歌起播仍快），单独把**媒体缓冲池**加厚到 MEDIA_MIN_CAPACITY_MILLIS，
    // 让 play() 后持续写入把池子填满，用池深吸收突发抖动（MODE_STREAM 满时 write 阻塞，不会丢数据）。
    const val DEFAULT_MILLIS = 300
    val presets = listOf(DEFAULT_MILLIS, 500, 1000)

    /** 媒体（音乐）缓冲池下限：须大于实测最大抖动（~1.3s），否则突发期必然 underrun。 */
    private const val MEDIA_MIN_CAPACITY_MILLIS = 1500

    private const val HEADROOM_MILLIS = 200 // room above the start level so bursts after a gap fit
    private const val MIN_TRACK_BUFFER_BYTES = 16 * 1024
    private const val MIN_START_BUFFER_BYTES = 4 * 1024
    // P0: raise the low-latency start level for non-media streams (speech/Siri/nav).
    // Wireless logs show periodic gaps up to ~260 ms that previously underran an ~80 ms buffer.
    private const val NON_MEDIA_START_MILLIS = 200

    // M6k19：设置页开放 10-4000ms 自由输入，不再限于旧预设档位。
    fun sanitize(millis: Int): Int = millis.coerceIn(10, 4000)

    data class Plan(val trackBufferBytes: Int, val startBytes: Int)

    /** AudioTrack capacity and the amount to queue before play() for one output stream. */
    fun plan(audioType: String, sampleRate: Int, channels: Int, minBufferBytes: Int, mediaMillis: Int): Plan {
        val bytesPerSecond = sampleRate.toLong() * channels.coerceIn(1, 2) * 2
        val lowLatency = Plan(
            trackBufferBytes = maxOf(minBufferBytes * 4, MIN_TRACK_BUFFER_BYTES),
            startBytes = maxOf(minBufferBytes, MIN_START_BUFFER_BYTES),
        )
        if (audioType != "media") {
            // P0: non-media streams used to start after ~80 ms of buffering, so any wireless gap
            // above that caused an underrun. Pre-buffer to a fixed ~200 ms with headroom instead.
            val nonMediaStart = (bytesPerSecond * NON_MEDIA_START_MILLIS / 1000).toInt()
            val nonMediaCapacity = (bytesPerSecond * (NON_MEDIA_START_MILLIS + HEADROOM_MILLIS) / 1000).toInt()
            return Plan(
                trackBufferBytes = maxOf(nonMediaCapacity, lowLatency.trackBufferBytes),
                startBytes = maxOf(nonMediaStart, lowLatency.startBytes),
            )
        }
        val start = (bytesPerSecond * sanitize(mediaMillis) / 1000).toInt()
        // 池深取「起播 + 余量」与「实测抖动下限」的较大者：起播阈值可小（切歌快），池子必须够深。
        val capacity = maxOf(
            (bytesPerSecond * (sanitize(mediaMillis) + HEADROOM_MILLIS) / 1000).toInt(),
            (bytesPerSecond * MEDIA_MIN_CAPACITY_MILLIS / 1000).toInt(),
        )
        return Plan(
            trackBufferBytes = maxOf(capacity, lowLatency.trackBufferBytes),
            startBytes = maxOf(start, lowLatency.startBytes),
        )
    }

    /**
     * The device may grant a smaller AudioTrack than requested. Writes block while the track is
     * paused and full, so the start level must stay below the real capacity or play() never runs.
     */
    fun startBytesFor(plannedStartBytes: Int, actualCapacityBytes: Int, writeChunkBytes: Int): Int {
        if (actualCapacityBytes <= 0) return plannedStartBytes
        return minOf(plannedStartBytes, actualCapacityBytes - writeChunkBytes).coerceAtLeast(writeChunkBytes)
    }
}
