package com.shilapi.diplay.lite

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton

// vc161 说明：vc158 / vc160 曾在此提供 Dialog.fitWidthToScreen() 与
// AlertDialog.Builder.showFitted()，试图靠「收敛宽度」解决竖屏弹窗被裁。
// 两次实测均未解决（宽度值算对了，问题在 Dialog 独立 Window 由 WMS 按 display
// 坐标系定位，居中于横屏 frame）。该方案已废弃，弹窗改为应用内覆盖层
// AppDialog（见 AppDialog.kt 头注释），这两个 helper 随之删除，避免留下死代码。

/**
 * vc161：可自动换行的单选容器。
 *
 * RadioGroup 本质是 LinearLayout，**只能单行**。竖屏（窄）下四个选项（如
 * 「自动连接方式」= 车机热点/中继热点/Wi-Fi Direct/USB连接）挤在一行放不下，
 * 会被压缩成文字折行 → 表现为选项错位、对不齐（K60 竖屏实测）。
 *
 * 本容器按可用宽度贪心折行（放得下就一行，放不下自动多行），
 * 并自行维护互斥选中（RadioGroup 的单选逻辑依赖直接父子关系，折行后需手写）。
 */
class WrapRadioGroup(context: Context) : LinearLayout(context) {
    init {
        orientation = VERTICAL
    }

    private val items = mutableListOf<RadioButton>()
    private var onChange: ((Int) -> Unit)? = null
    private var suppress = false
    private var lastWidth = -1

    fun addButtons(vararg buttons: RadioButton) {
        for (b in buttons) {
            items.add(b)
            b.setOnCheckedChangeListener { _, checked ->
                if (suppress) return@setOnCheckedChangeListener
                if (checked) {
                    suppress = true
                    items.forEach { if (it !== b) it.isChecked = false }
                    suppress = false
                }
                onChange?.invoke(items.indexOf(b))
            }
        }
        relayout(lastWidth)
    }

    fun setOnCheckedChangeListener(listener: (Int) -> Unit) {
        onChange = listener
    }

    /** 选中某项（其余自动取消）。 */
    fun check(button: RadioButton?) {
        suppress = true
        items.forEach { it.isChecked = (it === button) }
        suppress = false
    }

    /**
     * 折行必须在 **onMeasure** 里做：此时宽度从 MeasureSpec 一定拿得到。
     * 不能挂在 onSizeChanged——addButtons 时容器尚未挂到层级（宽=0，relayout 直接 return），
     * 挂上后若尺寸不再变化 onSizeChanged 不会再来 → 按钮永远进不了视图树，
     * 表现为「选项整组空白」（K60 竖屏实测）。lastWidth 守卫保证只在宽度变化时重建，
     * relayout 内部 addView 触发的二次 measure 会在此收敛。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        if (w > 0 && w != lastWidth) relayout(w)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun relayout(w: Int) {
        lastWidth = w
        if (w <= 0 || items.isEmpty()) return
        // 先把按钮从旧行里摘出来，再重建行
        for (i in 0 until childCount) (getChildAt(i) as? ViewGroup)?.removeAllViews()
        removeAllViews()

        val gap = (10 * resources.displayMetrics.density).toInt()
        var row = LinearLayout(context).apply { orientation = HORIZONTAL }
        var used = 0
        for (b in items) {
            b.measure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            val bw = b.measuredWidth
            if (used > 0 && used + gap + bw > w) {
                addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                row = LinearLayout(context).apply { orientation = HORIZONTAL }
                used = 0
            }
            row.addView(b)
            used += bw + gap
        }
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
}

/**
 * M6k14 深色 UI 工具箱：回归零 XML 纪律后，全部视觉常量与背景件收敛在此。
 * 色板取自用户渲染图（深海军蓝三通道版），API16 安全（只用 GradientDrawable/Canvas）。
 */
object Ui {

    // ---------- 色板（对齐上游原版 DiPlayActivity / DiAuto 视觉语言） ----------

    val BG = 0xFF0C111B.toInt()          // 原 BG rgb(12,17,27)：深底
    val CARD = 0xFF151E2C.toInt()        // 原 SURFACE rgb(21,30,44)：卡片面
    val BORDER = 0xFF2A384B.toInt()      // 原 BORDER rgb(42,56,75)：卡片描边/次级按钮描边
    val ACCENT = 0xFFA6C8FF.toInt()      // 原 ACCENT rgb(166,200,255)：主按钮底/强调字
    val PRIMARY = ACCENT                 // pill 主按钮底色跟随原版 ACCENT（设置/热点页复用）
    val PRIMARY_ON = BG                  // 主按钮文字色 = BG（原版 primary 按钮文字）
    val OUTLINE = ACCENT                 // 描边按钮描边与文字跟随 ACCENT
    val GRAYBTN = 0xFF2E3B52.toInt()     // 灰实心：断开/系统跳转
    val TEXT = 0xFFF1F5FC.toInt()        // 原 TEXT rgb(241,245,252)：主文字
    val SUB = 0xFFA8B6CA.toInt()         // 原 MUTED rgb(168,182,202)：说明/次要文字
    val MUTED = SUB                      // 原版命名别名（主页新代码用）
    val WARNING = 0xFFFFC480.toInt()     // 原 WARNING rgb(255,196,128)：警示
    val LINE = 0xFF22304A.toInt()        // 分割线（设置页组间距/横屏列分隔）
    val INPUT_FILL = 0xFF1E2C4A.toInt() // 设置页数值框填充
    val TRACK_OFF = 0xFF39435A.toInt()  // toggle 关底槽
    val GREEN = 0xFF34C759.toInt()      // 状态点-连接成功

    fun dip(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density + 0.5f).toInt()

    // ---------- 背景件 ----------

    /** 原版式卡片背景：SURFACE 面 + BORDER 细描边（对齐 DiPlayActivity.card()）。 */
    fun card(context: Context, radiusDp: Int = 20): GradientDrawable = GradientDrawable().apply {
        setColor(CARD)
        setStroke(dip(context, 1), BORDER)
        cornerRadius = dip(context, radiusDp).toFloat()
    }

    /** 胶囊实心按钮背景（连接/保存/蓝牙选择）。 */
    fun pillSolid(context: Context, color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dip(context, 22).toFloat()
    }

    /** 胶囊描边按钮背景（配置/进入/预览/导出）。 */
    fun pillOutline(context: Context, color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        setStroke(dip(context, 1), color)
        cornerRadius = dip(context, 22).toFloat()
    }

    /** 设置页数值框：深色填充圆角矩形。 */
    fun fillInput(context: Context): GradientDrawable = GradientDrawable().apply {
        setColor(INPUT_FILL)
        cornerRadius = dip(context, 8).toFloat()
    }

    /** RadioButton 圆圈：选中实心主蓝，未选中灰描边。自绘必须有显式尺寸，否则无 intrinsic 不可见。
     *  1.1.1 UI：18dp → 23dp（×1.3），描边同步 2→3dp，配合全局字体 1.2 倍。 */
    fun radio(context: Context, checked: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (checked) PRIMARY else Color.TRANSPARENT)
        setStroke(dip(context, 3), if (checked) PRIMARY else SUB)
        setSize(dip(context, 23), dip(context, 23))
    }

    /** CheckBox 方框：选中实心主蓝，未选中灰描边。 */
    fun checkbox(context: Context, checked: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dip(context, 3).toFloat()
        setColor(if (checked) PRIMARY else Color.TRANSPARENT)
        setStroke(dip(context, 2), if (checked) PRIMARY else SUB)
        setSize(dip(context, 16), dip(context, 16))
    }

    // ---------- 组合件 ----------

    /**
     * 下划线输入框（图3/4 样式）：无背景 EditText + 底部 1dp 线。
     * 返回竖排容器，EditText 放在 tag 上 —— 取用：`(container.tag as EditText)`。
     */
    fun lineInput(context: Context, hint: String, numeric: Boolean = false): LinearLayout {
        val input = EditText(context).apply {
            background = null
            setSingleLine()
            if (numeric) setRawInputType(android.text.InputType.TYPE_CLASS_NUMBER)
            setHintTextColor(SUB)
            setTextColor(TEXT)
            textSize = 14f
            this.hint = hint
            setPadding(0, dip(context, 6), 0, dip(context, 6))
        }
        val line = View(context).apply { setBackgroundColor(LINE) }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dip(context, 1)))
            tag = input
        }
    }
}

/**
 * M6k23：悬浮球所有画面常驻 —— 二级页（设置/热点配置/图标）右上角悬浮球。
 * 与主页悬浮球同视觉（半透明胶囊 + 双箭头图标）；点击执行 [onClick]（二级页=返回）。
 * 用法：activity 的根必须是 FrameLayout，先 addView 页面内容再调本函数盖在最上层。
 */
fun attachPageBall(activity: Activity, root: FrameLayout, onClick: () -> Unit) {
    val ball = FrameLayout(activity).apply {
        background = Ui.pillSolid(activity, 0x66FFFFFF)
        // setClipToOutline 是 API 18+；部分白牌 ROM 谎报 SDK_INT 但 framework 缺方法，改反射探测（与主页同因）。
        runCatching {
            FrameLayout::class.java
                .getMethod("setClipToOutline", Boolean::class.javaPrimitiveType)
                .invoke(this, true)
        }
    }
    val icon = ImageView(activity)
    icon.setImageDrawable(IconPainter.arrows(activity, 18, Ui.TEXT))
    ball.addView(
        icon,
        FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
    )
    val size = Ui.dip(activity, 34)
    val params = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END).apply {
        topMargin = Ui.dip(activity, 10)
        rightMargin = Ui.dip(activity, 10)
    }
    ball.layoutParams = params
    ball.setOnClickListener { onClick() }
    root.addView(ball, params)
}

/**
 * M6k14 自绘开关（API16 无 Material Switch，原生 Switch 样式不符设计图）。
 * 底槽圆角矩形（开=主蓝/关=深灰）+ 白色圆形滑块；点击切换并回调。
 * 三选一互斥场景用 [setCheckedSilent] 程序化同步，避免回调风暴。
 */
class ToggleView(context: Context) : View(context) {

    var checked: Boolean = false
        private set

    /** 用户点击导致的切换回调；程序化 setCheckedSilent 不触发。 */
    var onCheckedChange: ((Boolean) -> Unit)? = null

    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val rect = RectF()
    private val inset = Ui.dip(context, 3).toFloat()

    fun setCheckedSilent(value: Boolean) {
        checked = value
        invalidate()
    }

    fun setChecked(value: Boolean) {
        if (checked == value) return
        checked = value
        invalidate()
        onCheckedChange?.invoke(value)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(Ui.dip(context, 44), Ui.dip(context, 26))
    }

    override fun onDraw(canvas: Canvas) {
        val w = measuredWidth.toFloat()
        val h = measuredHeight.toFloat()
        val r = h / 2f
        track.color = if (checked) Ui.PRIMARY else Ui.TRACK_OFF
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, track)
        val cx = if (checked) w - r else r
        canvas.drawCircle(cx, r, r - inset, thumb)
    }

    override fun performClick(): Boolean {
        setChecked(!checked)
        return super.performClick()
    }
}
