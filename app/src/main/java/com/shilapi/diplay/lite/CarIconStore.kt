package com.shilapi.diplay.lite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.shilapi.xcertplay.airplay.AirPlayIcon
import java.io.File
import java.io.FileOutputStream

/**
 * 自定义 CarPlay 返回桌面图标存储（M6k22）。
 *
 * 落盘：filesDir/car_icon.png 单文件；写入走「临时文件 + rename」保证原子性
 * （framework 的 AtomicFile 是 API 17+，Lite minSdk16 不能用）。
 * 校验：PNG 签名 + 正方形 + ≤1024px，不合格直接拒绝（调用方 toast）。
 * 归一：无论输入多大，统一中心裁方（输入本身已是方形，直接缩放）到 256×256 PNG。
 * 接线：LiteSessionService 构造 AirPlayConfig 时经 [loadIcon] 读出 → AirPlayInfoPlist
 * 的 oemIcons 下发，iPhone 端 CarPlay 桌面图标即用此图。
 */
object CarIconStore {

    private const val NAME = "car_icon.png"
    private const val SIZE = 256
    private const val MAX_INPUT = 1024
    private val PNG_SIGNATURE =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    fun file(context: Context): File = File(context.filesDir, NAME)

    fun has(context: Context): Boolean = file(context).isFile

    fun isPng(bytes: ByteArray): Boolean {
        if (bytes.size < PNG_SIGNATURE.size) return false
        for (i in PNG_SIGNATURE.indices) {
            if (bytes[i] != PNG_SIGNATURE[i]) return false
        }
        return true
    }

    /**
     * 保存自定义图标：校验（PNG 签名/方形/≤1024px）→ 缩放到 256×256 → PNG 落盘。
     * 成功返回 true；失败（非 PNG、非方形、超大、解码失败）返回 false，原文件保持不变。
     */
    fun save(context: Context, bytes: ByteArray): Boolean {
        if (!isPng(bytes)) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0 || w != h || w > MAX_INPUT) return false
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, null) ?: return false
        val scaled = if (w == SIZE && h == SIZE) src else Bitmap.createScaledBitmap(src, SIZE, SIZE, true)
        return try {
            val tmp = File(context.filesDir, "$NAME.tmp")
            FileOutputStream(tmp).use { out -> scaled.compress(Bitmap.CompressFormat.PNG, 100, out) }
            val dst = file(context)
            if (dst.exists()) dst.delete()
            tmp.renameTo(dst)
        } catch (_: Exception) {
            false
        } finally {
            if (scaled !== src) scaled.recycle()
            src.recycle()
        }
    }

    /** 删除自定义图标（恢复内置默认）。 */
    fun clear(context: Context) {
        file(context).delete()
    }

    /** 设置页预览：按目标尺寸降采样解码，避免大图吃内存。 */
    fun loadPreview(context: Context, targetPx: Int): Bitmap? {
        val f = file(context)
        if (!f.isFile) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetPx) sample *= 2
            BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 会话接线：读取已保存图标 → AirPlayIcon（统一 256×256）。
     * 文件缺失/签名不合法时返回 null，AirPlayConfig.icons 保持空列表 → /info 走内置默认。
     */
    fun loadIcon(context: Context): AirPlayIcon? {
        val f = file(context)
        if (!f.isFile) return null
        return try {
            val bytes = f.readBytes()
            if (!isPng(bytes)) null else AirPlayIcon(widthPixels = SIZE, heightPixels = SIZE, data = bytes)
        } catch (_: Exception) {
            null
        }
    }
}
