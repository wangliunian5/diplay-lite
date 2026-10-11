package com.shilapi.xcertplay.media

import android.view.Surface
import com.shilapi.xcertplay.airplay.VideoCodec
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal sealed interface VideoJob {
    data class Config(val codec: VideoCodec, val codecData: ByteArray) : VideoJob
    data class Frame(val nalus: ByteArray, val receivedNs: Long = System.nanoTime()) : VideoJob
    data class SurfaceChanged(val surface: Surface?) : VideoJob
    object Resync : VideoJob { override fun toString() = "Resync" }
}

/** Do not resume dependent pictures after losing a reference frame. */
internal class VideoReferenceChain {
    var needsKeyFrame = true
        private set
    fun reset() { needsKeyFrame = true }
    fun accepts(isRandomAccess: Boolean): Boolean = !needsKeyFrame || isRandomAccess
    fun onQueued() { needsKeyFrame = false }
}

/** Limit latency and memory without ever dropping a reference frame silently. */
internal class VideoDecodeQueue(
    // Wi-Fi delivers frames in bursts after a radio gap; the decoder's 250 ms age check bounds latency.
    private val maxFrames: Int = 60,
    private val maxBytes: Int = 8 * 1024 * 1024,
) {
    private val jobs = LinkedBlockingQueue<VideoJob>()
    // 1.1.3（性能）：帧到达速率最高 60fps，原实现每次 offer 都 filterIsInstance 全队列扫一遍
    // （O(n) 扫描 + 临时集合分配，且与解码线程共用一把锁）→ 改为运行计数，检查 O(1)。
    private var frameCount = 0
    private var frameBytes = 0L

    @Synchronized fun offer(job: VideoJob) {
        if (job is VideoJob.Frame) {
            if (frameCount >= maxFrames || frameBytes + job.nalus.size > maxBytes) {
                discardFrames()
                jobs.offer(VideoJob.Resync)
            }
            // A single oversized frame is also a lost reference chain.
            if (job.nalus.size > maxBytes) return
            frameCount++
            frameBytes += job.nalus.size
        }
        jobs.offer(job)
    }

    @Synchronized fun discardFrames() {
        // API16 适配：LinkedBlockingQueue.removeIf 是 API 24+，改手动迭代移除。
        val iterator = jobs.iterator()
        while (iterator.hasNext()) {
            val job = iterator.next()
            if (job is VideoJob.Frame || job is VideoJob.Resync) {
                if (job is VideoJob.Frame) {
                    frameCount--
                    frameBytes -= job.nalus.size
                }
                iterator.remove()
            }
        }
    }

    fun poll(timeoutMillis: Long): VideoJob? {
        val job = jobs.poll(timeoutMillis, TimeUnit.MILLISECONDS) ?: return null
        // 计数修正只在解码线程发生（单消费者），加锁只为与 offer 的检查互斥。
        if (job is VideoJob.Frame) synchronized(this) {
            frameCount--
            frameBytes -= job.nalus.size
        }
        return job
    }
}

/** Drain output while waiting for input: full output buffers can otherwise starve input forever. */
internal object VideoInputPump {
    fun acquire(
        running: () -> Boolean,
        drain: () -> Unit,
        dequeue: () -> Int,
        nanoTime: () -> Long = System::nanoTime,
        timeoutNs: Long = TimeUnit.MILLISECONDS.toNanos(500),
    ): Int {
        val start = nanoTime()
        while (running()) {
            drain()
            val index = dequeue()
            if (index >= 0) return index
            if (nanoTime() - start >= timeoutNs) break
        }
        return -1
    }
}
