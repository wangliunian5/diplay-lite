package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import com.shilapi.xcertplay.media.BandwidthMonitor

/**
 * On-screen debugging panel that draws the per-second video/audio bandwidth curves and jitter
 * stats captured by [BandwidthMonitor]. Tied to the "调试日志" (debug logs) switch via the host
 * activity; tapping it dismisses the panel.
 */
class BandwidthOverlayView(
    context: Context,
    private val onClose: () -> Unit,
) : View(context) {

    private val density = resources.displayMetrics.density
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(230, 238, 250); textSize = 11f * density
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 13f * density; isFakeBoldText = true
    }
    private val videoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(88, 220, 130); strokeWidth = 2f * density; style = Paint.Style.STROKE
    }
    private val audioPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(94, 150, 255); strokeWidth = 2f * density; style = Paint.Style.STROKE
    }
    private val gridPaint = Paint().apply {
        color = Color.argb(40, 255, 255, 255); strokeWidth = 1f; style = Paint.Style.STROKE
    }
    private val bgPaint = Paint().apply { color = Color.argb(150, 8, 14, 22) }

    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            BandwidthMonitor.tick()
            invalidate()
            handler.postDelayed(this, 1000L)
        }
    }

    private val sampleListener: (BandwidthMonitor.Sample) -> Unit = { invalidate() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(tickRunnable)
        BandwidthMonitor.addListener(sampleListener)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tickRunnable)
        BandwidthMonitor.removeListener(sampleListener)
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) onClose()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), gridPaint)

        val titleY = 16f * density
        canvas.drawText("带宽监测 (录屏/断流观察)", 8f * density, titleY, titlePaint)

        val samples = BandwidthMonitor.snapshots()
        val plotTop = titleY + 12f * density
        val plotBottom = height - 22f * density
        val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)
        val axisY = height - 10f * density

        if (samples.isEmpty()) {
            canvas.drawText("等待数据…", 8f * density, plotTop, textPaint)
            return
        }

        // Auto-range the video axis so the curve stays visible without zooming.
        val videoMax = (samples.maxOfOrNull { it.videoKbps } ?: 1).coerceAtLeast(1)
        val stepX = 1f
        val left = 8f * density
        val chartWidth = (width - left - 4f * density).coerceAtLeast(1f)

        fun kbpsY(kbps: Int): Float = plotBottom - (kbps.coerceAtLeast(0) / videoMax.toFloat()) * plotHeight
        fun draw(kbps: Int): Float = kbpsY(kbps)

        // Grid lines at 0 / half / max.
        for (fraction in listOf(0f, 0.5f, 1f)) {
            val y = plotBottom - fraction * plotHeight
            canvas.drawLine(left, y, left + chartWidth, y, gridPaint)
        }
        canvas.drawText("${videoMax}k", left, plotBottom - plotHeight - 2f * density, textPaint)

        // Video curve (green).
        var i = 0
        var prevX = 0f; var prevY = 0f; var hasPrev = false
        val firstX = samples.size
        for (s in samples) {
            val x = left + (firstX - (samples.size - i)) * stepX
            val y = draw(s.videoKbps)
            if (hasPrev) canvas.drawLine(prevX, prevY, x, y, videoPaint)
            prevX = x; prevY = y; hasPrev = true; i++
        }
        // Audio curve (blue), half scale overlay on top.
        hasPrev = false; i = 0
        for (s in samples) {
            val x = left + (firstX - (samples.size - i)) * stepX
            val y = plotBottom - (s.audioKbps.coerceAtLeast(0) / videoMax.toFloat()) * plotHeight
            if (hasPrev) canvas.drawLine(prevX, prevY, x, y, audioPaint)
            prevX = x; prevY = y; hasPrev = true; i++
        }

        val latest = samples.last()
        canvas.drawText(
            "V:${latest.videoKbps}k  A:${latest.audioKbps}k", left, axisY, textPaint,
        )
        canvas.drawText(
            "gap v:${latest.videoMaxGapMs}ms a:${latest.audioMaxGapMs}ms  " +
                "und:${latest.audioUnderruns}", left, axisY + 9f * density, textPaint,
        )
    }
}
