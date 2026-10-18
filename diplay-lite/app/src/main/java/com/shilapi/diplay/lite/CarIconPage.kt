package com.shilapi.diplay.lite

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 自定义 CarPlay 返回桌面图标（M6k23 页，迁移自设置页；M6k25 单 Activity 化为 page="caricon"）：
 * 预览 + 选择图库照片 → 自动进入裁剪页（拖动/双指缩放 + 方形取景框）→ 确认保存。
 * 裁剪输出 256×256 PNG，经 CarIconStore 落盘；品牌 logo 预置行与「默认图标」也在此页。
 * 零 XML、无 androidx、API16 安全（fork ImageCropActivity 的 Lite 适配版）。
 *
 * M6k25 迁移要点：图库选择改由 MainActivity.startActivityForResult（requestCode=1001）发起，
 * 结果经 MainActivity.onActivityResult → [onGalleryResult] 回流；原 Activity 的
 * setContentView 切视图改为 cropping 标记 + MainActivity.render() 重建；原 onDestroy 的
 * 线程池/位图清理收口到 [cleanup]（页面离开时调用）。返回（原 finish()）回到设置页。
 */
class CarIconPage(private val activity: MainActivity) {

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var cropView: SquareCropView
    private lateinit var cropStatus: TextView
    private lateinit var preview: ImageView
    private var pendingUri: Uri? = null

    /** true = 处于裁剪视图（选图后进入），buildRoot 据此分发主视图/裁剪视图。 */
    private var cropping = false

    // 1.1.6：全品牌车标库（assets/car-logos，141 品牌）。
    // vc95：原「全部品牌」独立网格页在部分车机无法预览清单（用户裁定），改为图标主页
    // 内联 5×2 横向滑动栏直选；独立网格页机制（brandPicker/BrandAdapter）整体移除。
    private var brandIndex: List<BrandEntry> = emptyList()
    private val brandIconCache = HashMap<String, Bitmap>()

    /** 车标索引行：assets 文件名 key / 英文名 / 中文名。 */
    private data class BrandEntry(val key: String, val en: String, val cn: String)

    /** 滑动栏单元：raw 预置（rawResId 非空）或 assets 品牌（entry 非空）。 */
    private data class BrandTile(val en: String, val cn: String, val rawResId: Int?, val entry: BrandEntry?)

    private companion object {
        const val MAX_DECODE_EDGE = 2048
        const val BRAND_ASSET_DIR = "car-logos"
    }

    fun buildRoot(): View = when {
        cropping -> buildCropRoot()
        else -> buildMainRoot()
    }

    /** 页面离开时调用（原 onDestroy）：停解码线程并回收裁剪位图。 */
    fun cleanup() {
        executor.shutdownNow()
        if (::cropView.isInitialized) cropView.bitmap?.recycle()
        synchronized(brandIconCache) {
            for (bmp in brandIconCache.values) bmp.recycle()
            brandIconCache.clear()
        }
    }

    /** MainActivity.onActivityResult（requestCode=1001）转发：选图成功即进入裁剪页。 */
    fun onGalleryResult(resultCode: Int, data: Intent?) {
        if (resultCode != android.app.Activity.RESULT_OK || data?.data == null) return
        enterCrop(data.data!!)
    }

    /**
     * 系统返回键（1.1.1）：裁剪态先回图标主页（消费事件），主页态返回
     * false 交由 MainActivity 走「三级页 → 设置页」层级。
     */
    fun onBackKey(): Boolean {
        when {
            cropping -> {
                backToMain()
                return true
            }
            else -> return false
        }
    }

    // ---------- 主页（预览 + 选图 + 品牌 + 默认） ----------

    private fun buildMainRoot(): View {
        applyOrientationMode(activity, LiteConfig.load(activity).orientationMode)
        val root = FrameLayout(activity).apply { setBackgroundColor(Ui.BG) }
        root.addView(buildMainView(), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // 悬浮球所有画面常驻：点击返回设置页。
        attachPageBall(activity, root) { activity.setPage("settings") }
        return root
    }

    private fun buildMainView(): View {
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dip(activity, 16), Ui.dip(activity, 16), Ui.dip(activity, 16), Ui.dip(activity, 32))
        }
        // M6k24：左上角返回按钮（回设置页）+ 标题同行。
        // 1.1.1 UI：顶栏样式与设置页统一（白色返回图标 + 同字号标题），替换原 accent「‹ 返回」。
        val backRow = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        backRow.addView(
            ImageView(activity).apply {
                setImageDrawable(IconPainter.back(activity, 22, Ui.TEXT))
                setPadding(0, Ui.dip(activity, 8), Ui.dip(activity, 12), Ui.dip(activity, 8))
                setOnClickListener { activity.setPage("settings") }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        backRow.addView(TextView(activity).apply {
            text = "自定义返回图标"
            setTextColor(Ui.TEXT)
            textSize = 20f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        })
        column.addView(backRow)
        column.addView(TextView(activity).apply {
            text = "选择照片后自动进入裁剪，裁剪确认即保存。更改在重新连接后生效。"
            setTextColor(Ui.SUB)
            textSize = 12f
            setPadding(0, Ui.dip(activity, 6), 0, 0)
        })

        // 当前图标预览
        preview = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        preview.layoutParams = LinearLayout.LayoutParams(Ui.dip(activity, 72), Ui.dip(activity, 72)).apply {
            topMargin = Ui.dip(activity, 16)
        }
        column.addView(preview)
        refreshPreview()

        column.addView(outlineButton("选择图片（进入裁剪）") { pickImage() }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = Ui.dip(activity, 12)
        })
        column.addView(outlineButton("恢复默认图标") {
            CarIconStore.clear(activity)
            refreshPreview()
            toast("已恢复默认图标，重新连接后生效")
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = Ui.dip(activity, 8)
        })

        // 1.0.17：名称自定义（/info name + Bonjour 服务名，重连后生效）。
        column.addView(TextView(activity).apply {
            text = "显示名称"
            setTextColor(Ui.TEXT)
            textSize = 16f
            setPadding(0, Ui.dip(activity, 20), 0, Ui.dip(activity, 6))
        })
        column.addView(TextView(activity).apply {
            text = "CarPlay 桌面图标名称与车载页显示的名称；选择厂家预置图标会自动替换。"
            setTextColor(Ui.SUB)
            textSize = 12f
            setPadding(0, 0, 0, Ui.dip(activity, 6))
        })
        val nameInput = android.widget.EditText(activity).apply {
            setText(LiteConfig.load(activity).carIconName)
            setSingleLine(true)
            textSize = 15f
            setTextColor(Ui.TEXT)
            hint = "DiPlay"
            background = Ui.pillOutline(activity, Ui.OUTLINE)
            setPadding(Ui.dip(activity, 14), Ui.dip(activity, 10), Ui.dip(activity, 14), Ui.dip(activity, 10))
        }
        column.addView(nameInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        column.addView(outlineButton("保存名称") {
            val name = nameInput.text.toString().trim().ifBlank { "DiPlay" }
            val cfg = LiteConfig.load(activity)
            LiteConfig.save(activity, cfg.copy(carIconName = name))
            nameInput.setText(name)
            LiteLog.log("ui", "CarPlay 显示名称已保存：$name")
            toast("名称已保存：$name（重新连接后生效）")
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = Ui.dip(activity, 8)
        })

        // 品牌车标滑动栏（vc95）：5 列×2 行横向滑动直选，取代原单行厂家预置 + 「全部品牌」
        // 跳页——部分车机网格页无法预览清单（用户裁定改内联）。BEI JING / Stellantis 为
        // raw 独有（assets 索引无此二品牌）置顶，其余取 assets/car-logos 索引（141 品牌，
        // 原预置的 BMW/BYD/Toyota 等直接用 assets 版本，不重复出牌）。
        // 1.0.17 语义保留：选中即替换显示名称（用户手动改过也可再次覆盖）。
        column.addView(TextView(activity).apply {
            text = "品牌车标（左右滑动，点按应用）"
            setTextColor(Ui.TEXT)
            textSize = 16f
            setPadding(0, Ui.dip(activity, 20), 0, Ui.dip(activity, 6))
        })
        if (brandIndex.isEmpty()) {
            brandIndex = loadBrandIndex()
            if (brandIndex.isEmpty()) {
                // 取证点：车机报「无法预览清单」时优先核对这条——索引加载失败即资源缺失。
                LiteLog.log("ui", "车标资源缺失：assets/car-logos 索引为空，滑动栏仅显示 raw 预置")
            }
        }
        val tiles = mutableListOf(
            BrandTile("BEI JING", "北京", R.raw.logo_beijing, null),
            BrandTile("Stellantis", "Stellantis", R.raw.logo_stellantis, null),
        )
        for (entry in brandIndex) tiles.add(BrandTile(entry.en, entry.cn, null, entry))
        val tileRows = 2
        val tileWidth = activity.windowManager.defaultDisplay.width / 5
        val tileHeight = Ui.dip(activity, 72)
        val tileColumns = ceil(tiles.size / tileRows.toDouble()).toInt()
        val strip = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        for (c in 0 until tileColumns) {
            val colBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            for (r in 0 until tileRows) {
                val index = c * tileRows + r
                val cell = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(tileWidth, tileHeight)
                }
                if (index < tiles.size) {
                    val tile = tiles[index]
                    val icon = ImageView(activity).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        layoutParams = LinearLayout.LayoutParams(Ui.dip(activity, 44), Ui.dip(activity, 44))
                    }
                    val label = TextView(activity).apply {
                        text = tile.cn
                        setTextColor(Ui.SUB)
                        textSize = 10f
                        maxLines = 1
                    }
                    cell.addView(icon)
                    cell.addView(label)
                    val rawResId = tile.rawResId
                    val entry = tile.entry
                    if (rawResId != null) {
                        icon.setImageResource(rawResId)
                        cell.setOnClickListener { applyRawPreset(rawResId, tile.en, nameInput) }
                    } else if (entry != null) {
                        icon.setImageBitmap(brandIcon(entry))
                        cell.setOnClickListener { applyBrand(entry) { nameInput.setText(tile.en) } }
                    }
                }
                colBox.addView(cell)
            }
            strip.addView(colBox)
        }
        column.addView(HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        })
        return android.widget.ScrollView(activity).apply {
            isFillViewport = true
            addView(column)
        }
    }

    // ---------- 品牌车标滑动栏（vc95，取代 1.1.6 独立网格页） ----------

    /** 应用 raw 预置车标：读 raw 资源 → CarIconStore 校验落盘 + 名称替换（1.0.17 配对语义）。 */
    private fun applyRawPreset(rawResId: Int, brandName: String, nameInput: EditText) {
        val bytes = try {
            activity.resources.openRawResource(rawResId).use { it.readBytes() }
        } catch (_: Exception) {
            ByteArray(0)
        }
        if (bytes.isEmpty() || !CarIconStore.save(activity, bytes)) {
            toast("该图标不合规：需正方形 PNG 且 ≤1024px")
        } else {
            val cfg = LiteConfig.load(activity)
            LiteConfig.save(activity, cfg.copy(carIconName = brandName))
            nameInput.setText(brandName)
            refreshPreview()
            toast("已应用：$brandName（重新连接后生效）")
        }
    }

    /** assets 车标解码（带缓存；滑动栏直接复用，避免每次滑动重解码）。 */
    private fun brandIcon(entry: BrandEntry): Bitmap? {
        synchronized(brandIconCache) { brandIconCache[entry.key] }?.let { return it }
        return try {
            val bitmap = BitmapFactory.decodeStream(activity.assets.open("$BRAND_ASSET_DIR/${entry.key}.png"))
            synchronized(brandIconCache) { brandIconCache[entry.key] = bitmap }
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    /** 读 assets/car-logos/index.tsv（key\ten\tcn，UTF-8）；容错跳过畸形行。 */
    private fun loadBrandIndex(): List<BrandEntry> {
        return try {
            activity.assets.open("$BRAND_ASSET_DIR/index.tsv").bufferedReader(Charsets.UTF_8).readLines()
                .mapNotNull { line ->
                    val cols = line.split('\t')
                    if (cols.size < 3) null else BrandEntry(cols[0].trim(), cols[1].trim(), cols[2].trim())
                }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 应用品牌车标：assets 解码 → 中心补方 256×256 → CarIconStore 落盘 + 名称替换；onApplied 在应用成功后回调（UI 线程）。 */
    private fun applyBrand(entry: BrandEntry, onApplied: () -> Unit = {}) {
        executor.execute {
            val encoded = try {
                val bytes = activity.assets.open("$BRAND_ASSET_DIR/${entry.key}.png").use { it.readBytes() }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return@execute
                val side = max(bitmap.width, bitmap.height)
                val padded = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
                Canvas(padded).drawBitmap(
                    bitmap,
                    ((side - bitmap.width) / 2f),
                    ((side - bitmap.height) / 2f),
                    null,
                )
                bitmap.recycle()
                val output = ByteArrayOutputStream().use { stream ->
                    padded.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    stream.toByteArray()
                }
                padded.recycle()
                output
            } catch (_: Exception) {
                ByteArray(0)
            }
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                if (encoded.isEmpty() || !CarIconStore.save(activity, encoded)) {
                    toast("应用失败：${entry.en}")
                } else {
                    val cfg = LiteConfig.load(activity)
                    LiteConfig.save(activity, cfg.copy(carIconName = entry.en))
                    refreshPreview()
                    toast("已应用：${entry.en} / ${entry.cn}（重新连接后生效）")
                    onApplied()
                }
            }
        }
    }

    private fun outlineButton(text: String, onClick: () -> Unit): Button = Button(activity).apply {
        this.text = text
        isAllCaps = false
        textSize = 15f
        setTextColor(Ui.TEXT)
        background = Ui.pillOutline(activity, Ui.OUTLINE)
        minHeight = Ui.dip(activity, 46)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dip(activity, 46))
    }

    private fun refreshPreview() {
        val bmp = try {
            CarIconStore.loadPreview(activity, 144)
        } catch (_: Exception) {
            null
        }
        preview.setImageBitmap(bmp)
    }

    private fun pickImage() {
        activity.launchGalleryPicker()
    }

    // ---------- 裁剪页 ----------

    private fun enterCrop(uri: Uri) {
        pendingUri = uri
        cropping = true
        // 切换到裁剪视图：整页重建（原 setContentView）。
        activity.render()
    }

    private fun buildCropRoot(): View {
        val uri = pendingUri
        val root = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
        cropView = SquareCropView(activity)
        cropStatus = TextView(activity).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(Ui.dip(activity, 12), Ui.dip(activity, 8), Ui.dip(activity, 12), Ui.dip(activity, 8))
            text = "正在加载图片…"
        }
        val controls = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(Ui.dip(activity, 16), Ui.dip(activity, 10), Ui.dip(activity, 16), Ui.dip(activity, 16))
        }
        controls.addView(Button(activity).apply {
            text = "取消"; isAllCaps = false
            setOnClickListener { backToMain() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(Button(activity).apply {
            text = "确认保存"; isAllCaps = false
            setOnClickListener { saveCrop() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = Ui.dip(activity, 12)
        })
        root.addView(cropView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(
            cropStatus,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = Ui.dip(activity, 12)
            },
        )
        root.addView(
            controls,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
        )
        val view = cropView
        val status = cropStatus
        if (uri != null) {
            executor.execute {
                val bitmap = try {
                    decodeBitmap(uri)
                } catch (_: Exception) {
                    null
                }
                activity.runOnUiThread {
                    if (activity.isFinishing) {
                        bitmap?.recycle()
                        return@runOnUiThread
                    }
                    // 期间页面被重建（旋转/切页）：丢弃本次结果，新一轮 buildCropRoot 会重新解码
                    if (!cropping || this@CarIconPage.cropView !== view) {
                        bitmap?.recycle()
                        return@runOnUiThread
                    }
                    if (bitmap == null) {
                        status.text = "图片解码失败，请换一张试试"
                    } else {
                        view.setBitmap(bitmap)
                        status.text = "拖动或双指缩放，把图标放进方框"
                    }
                }
            }
        }
        return root
    }

    private fun backToMain() {
        if (::cropView.isInitialized) cropView.bitmap?.recycle()
        cropping = false
        pendingUri = null
        activity.render()
    }

    private fun saveCrop() {
        val cropped = cropView.cropToSquare() ?: run {
            cropStatus.text = "图片还没加载好"
            return
        }
        val encoded = ByteArrayOutputStream().use { output ->
            if (!cropped.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                cropped.recycle()
                null
            } else {
                cropped.recycle()
                output.toByteArray()
            }
        }
        if (encoded == null || !CarIconStore.save(activity, encoded)) {
            cropStatus.text = "保存失败，请重试"
            return
        }
        LiteLog.log("ui", "自定义返回图标已更新（裁剪页保存）")
        toast("已保存，下次连接生效")
        backToMain()
    }

    private fun decodeBitmap(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        activity.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (max(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_DECODE_EDGE) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return activity.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * 方形取景裁剪视图（fork SquareCropView 的 Lite 适配，输出 256×256）：
     * 单指拖动、双指缩放，图片被约束在取景框内；裁剪按取景框映射回原图取方形。
     */
    private class SquareCropView(context: android.content.Context) : View(context) {
        private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val dimPaint = Paint().apply { color = Color.argb(145, 0, 0, 0) }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2f * resources.displayMetrics.density
        }
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 1f * resources.displayMetrics.density
        }
        private val matrix = Matrix()
        private val viewport = RectF()
        private val mappedBitmap = RectF()
        private var minimumScale = 1f
        private var maximumScale = 1f
        private var lastX = 0f
        private var lastY = 0f
        private var lastDistance = 0f

        var bitmap: Bitmap? = null
            private set

        fun setBitmap(value: Bitmap) {
            bitmap?.recycle()
            bitmap = value
            if (width > 0 && height > 0) configure()
            invalidate()
        }

        override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
            super.onSizeChanged(width, height, oldWidth, oldHeight)
            configure()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val source = bitmap ?: return
            canvas.drawColor(Color.BLACK)
            canvas.drawRect(0f, 0f, width.toFloat(), viewport.top, dimPaint)
            canvas.drawRect(0f, viewport.bottom, width.toFloat(), height.toFloat(), dimPaint)
            canvas.drawRect(0f, viewport.top, viewport.left, viewport.bottom, dimPaint)
            canvas.drawRect(viewport.right, viewport.top, width.toFloat(), viewport.bottom, dimPaint)
            val save = canvas.save()
            canvas.clipRect(viewport)
            canvas.drawBitmap(source, matrix, imagePaint)
            canvas.restoreToCount(save)
            canvas.drawRect(viewport, borderPaint)
            val thirdWidth = viewport.width() / 3f
            val thirdHeight = viewport.height() / 3f
            canvas.drawLine(viewport.left + thirdWidth, viewport.top, viewport.left + thirdWidth, viewport.bottom, gridPaint)
            canvas.drawLine(viewport.right - thirdWidth, viewport.top, viewport.right - thirdWidth, viewport.bottom, gridPaint)
            canvas.drawLine(viewport.left, viewport.top + thirdHeight, viewport.right, viewport.top + thirdHeight, gridPaint)
            canvas.drawLine(viewport.left, viewport.bottom - thirdHeight, viewport.right, viewport.bottom - thirdHeight, gridPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (bitmap == null) return true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    return true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    lastDistance = pointerDistance(event)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount >= 2) {
                        val distance = pointerDistance(event)
                        if (lastDistance > 0f) {
                            val factor = distance / lastDistance
                            matrix.postScale(factor, factor, (event.getX(0) + event.getX(1)) / 2f, (event.getY(0) + event.getY(1)) / 2f)
                        }
                        lastDistance = distance
                    } else {
                        matrix.postTranslate(event.x - lastX, event.y - lastY)
                    }
                    lastX = event.x
                    lastY = event.y
                    constrainMatrix()
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    lastDistance = 0f
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    lastDistance = 0f
                    performClick()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        fun cropToSquare(): Bitmap? {
            val source = bitmap ?: return null
            if (viewport.width() <= 0f || viewport.height() <= 0f) return null
            val inverse = Matrix()
            if (!matrix.invert(inverse)) return null
            val sourceRect = RectF()
            inverse.mapRect(sourceRect, viewport)
            val left = floor(sourceRect.left).toInt().coerceIn(0, source.width - 1)
            val top = floor(sourceRect.top).toInt().coerceIn(0, source.height - 1)
            val right = ceil(sourceRect.right).toInt().coerceIn(left + 1, source.width)
            val bottom = ceil(sourceRect.bottom).toInt().coerceIn(top + 1, source.height)
            val square = min(right - left, bottom - top)
            val centerX = (left + right) / 2
            val centerY = (top + bottom) / 2
            val cropLeft = (centerX - square / 2).coerceIn(0, source.width - square)
            val cropTop = (centerY - square / 2).coerceIn(0, source.height - square)
            val cropped = Bitmap.createBitmap(source, cropLeft, cropTop, square, square)
            val output = Bitmap.createBitmap(OUTPUT_PIXELS, OUTPUT_PIXELS, Bitmap.Config.ARGB_8888)
            Canvas(output).drawBitmap(
                cropped,
                null,
                RectF(0f, 0f, OUTPUT_PIXELS.toFloat(), OUTPUT_PIXELS.toFloat()),
                imagePaint,
            )
            if (cropped !== source) cropped.recycle()
            return output
        }

        private fun configure() {
            val source = bitmap ?: return
            if (width <= 0 || height <= 0) return
            val side = min(width, height).toFloat() - 2f * CROP_PADDING_DP * resources.displayMetrics.density
            if (side <= 0f) return
            viewport.set((width - side) / 2f, (height - side) / 2f, (width + side) / 2f, (height + side) / 2f)
            minimumScale = max(side / source.width, side / source.height)
            maximumScale = minimumScale * MAX_ZOOM
            matrix.reset()
            matrix.postScale(minimumScale, minimumScale)
            matrix.postTranslate(
                viewport.centerX() - source.width * minimumScale / 2f,
                viewport.centerY() - source.height * minimumScale / 2f,
            )
            constrainMatrix()
            invalidate()
        }

        private fun constrainMatrix() {
            val source = bitmap ?: return
            val values = FloatArray(9)
            matrix.getValues(values)
            val scale = values[Matrix.MSCALE_X]
            if (scale < minimumScale) {
                matrix.postScale(minimumScale / scale, minimumScale / scale, viewport.centerX(), viewport.centerY())
            } else if (scale > maximumScale) {
                matrix.postScale(maximumScale / scale, maximumScale / scale, viewport.centerX(), viewport.centerY())
            }
            mappedBitmap.set(0f, 0f, source.width.toFloat(), source.height.toFloat())
            matrix.mapRect(mappedBitmap)
            var dx = 0f
            var dy = 0f
            if (mappedBitmap.left > viewport.left) dx = viewport.left - mappedBitmap.left
            if (mappedBitmap.right < viewport.right) dx = viewport.right - mappedBitmap.right
            if (mappedBitmap.top > viewport.top) dy = viewport.top - mappedBitmap.top
            if (mappedBitmap.bottom < viewport.bottom) dy = viewport.bottom - mappedBitmap.bottom
            if (dx != 0f || dy != 0f) matrix.postTranslate(dx, dy)
        }

        private fun pointerDistance(event: MotionEvent): Float {
            if (event.pointerCount < 2) return 0f
            val dx = event.getX(0) - event.getX(1)
            val dy = event.getY(0) - event.getY(1)
            return sqrt(dx * dx + dy * dy)
        }

        private companion object {
            const val CROP_PADDING_DP = 24f
            const val MAX_ZOOM = 8f
            const val OUTPUT_PIXELS = 256
        }
    }
}
