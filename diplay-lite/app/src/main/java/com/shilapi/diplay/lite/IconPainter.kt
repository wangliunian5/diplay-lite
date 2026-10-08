package com.shilapi.diplay.lite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/**
 * API16 没有 VectorDrawable（API21+），系统图标一律用 Canvas 现画现给。
 * 位图按调用方指定的 dp 尺寸 × 屏幕密度生成，颜色由调用方决定，同一函数可出深浅两版。
 * 生成的是独立 Drawable 实例，视图重建（旋转换布局）时重取一次即可，不做全局缓存。
 */
object IconPainter {

    private inline fun paint(
        context: Context,
        sizeDp: Int,
        color: Int,
        draw: (Canvas, Paint, Float) -> Unit,
    ): Drawable {
        val density = context.resources.displayMetrics.density
        val size = (sizeDp * density + 0.5f).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val stroke = (size * 0.10f).coerceAtLeast(1f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.strokeWidth = stroke
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        draw(canvas, p, size.toFloat())
        return BitmapDrawable(context.resources, bitmap)
    }

    /**
     * 设置入口齿轮：实心齿轮（8 梯形齿 + 主体圆 + 中心透明孔），比旧版线框更好认。
     * M6k19：主页反馈旧线框版"太丑而且小"，重画为实心款，调用尺寸同步放大。
     */
    fun gear(context: Context, sizeDp: Int, color: Int): Drawable =
        paint(context, sizeDp, color) { canvas, p, size ->
            val c = size / 2f
            val rBody = c * 0.60f
            val rOuter = c * 0.88f
            val rHole = c * 0.26f
            p.style = Paint.Style.FILL
            p.strokeCap = Paint.Cap.BUTT
            // 8 根齿：略外扩的矩形，旋转 45° 步进（偏移 22.5° 让齿落在对角上更均衡）。
            for (i in 0 until 8) {
                canvas.save()
                canvas.rotate(i * 45f + 22.5f, c, c)
                canvas.drawRect(c - rBody * 0.30f, c - rOuter, c + rBody * 0.30f, c - rBody * 0.60f, p)
                canvas.restore()
            }
            canvas.drawCircle(c, c, rBody, p)
            // 中心孔：位图初始全透明，直接 CLEAR 打穿即可，无需离屏 layer（API16 安全）。
            val eraser = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
            }
            canvas.drawCircle(c, c, rHole, eraser)
        }

    /** 返回箭头 ←（设置页/占位页标题栏）。 */
    fun back(context: Context, sizeDp: Int, color: Int): Drawable =
        paint(context, sizeDp, color) { canvas, p, size ->
            val c = size / 2f
            val r = c * 0.52f
            val path = Path()
            path.moveTo(c + r * 0.55f, c - r)
            path.lineTo(c - r, c)
            path.lineTo(c + r * 0.55f, c + r)
            canvas.drawPath(path, p)
        }

    /** 双向箭头 ↔（悬浮球：面板开/关两态共用一枚图标）。 */
    fun arrows(context: Context, sizeDp: Int, color: Int): Drawable =
        paint(context, sizeDp, color) { canvas, p, size ->
            val c = size / 2f
            val r = c * 0.55f
            val y = c
            canvas.drawLine(c - r, y, c + r, y, p)
            val head = r * 0.42f
            val left = Path()
            left.moveTo(c - r + head, y - head)
            left.lineTo(c - r, y)
            left.lineTo(c - r + head, y + head)
            canvas.drawPath(left, p)
            val right = Path()
            right.moveTo(c + r - head, y - head)
            right.lineTo(c + r, y)
            right.lineTo(c + r - head, y + head)
            canvas.drawPath(right, p)
        }

    /** 右上箭头 ↗（"系统跳转"类按钮的文字角标）。 */
    fun upRight(context: Context, sizeDp: Int, color: Int): Drawable =
        paint(context, sizeDp, color) { canvas, p, size ->
            val c = size / 2f
            val r = c * 0.5f
            canvas.drawLine(c - r, c + r, c + r * 0.6f, c - r * 0.6f, p)
            val path = Path()
            path.moveTo(c + r * 0.05f, c - r)
            path.lineTo(c + r, c - r)
            path.lineTo(c + r, c - r * 0.05f)
            canvas.drawPath(path, p)
        }

    /** 蓝牙符文 ᛒ（蓝牙选择按钮角标）：竖线 + 交叉双折线。 */
    fun bluetooth(context: Context, sizeDp: Int, color: Int): Drawable =
        paint(context, sizeDp, color) { canvas, p, size ->
            val c = size / 2f
            val r = c * 0.62f
            canvas.drawLine(c, c - r, c, c + r, p)
            val right = Path()
            right.moveTo(c, c - r)
            right.lineTo(c + r * 0.6f, c - r * 0.25f)
            right.lineTo(c - r * 0.6f, c + r * 0.25f)
            right.lineTo(c, c + r)
            canvas.drawPath(right, p)
            val left = Path()
            left.moveTo(c, c + r)
            left.lineTo(c - r * 0.6f, c + r * 0.25f)
            left.lineTo(c + r * 0.6f, c - r * 0.25f)
            canvas.drawPath(left, p)
        }
}
