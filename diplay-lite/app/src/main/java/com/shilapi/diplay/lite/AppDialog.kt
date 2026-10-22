package com.shilapi.diplay.lite

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * vc161：自绘弹窗 —— **应用内覆盖层，不新建 Window**。
 *
 * ## 为什么不用 AlertDialog
 *
 * Manifest 里 MainActivity 是 `android:screenOrientation="landscape"` +
 * `configChanges="orientation|screenSize|…"` 自处理，运行时再由 `applyOrientationMode()`
 * 强制竖屏。此时 **Activity 自己的视图树是竖屏口径**，但 **Dialog 是独立 Window
 * （TYPE_APPLICATION 子窗口），由 WMS 按 display 坐标系定位** —— 它会被居中于横屏
 * frame（3080），中心点落在竖屏视口之外，表现为「弹窗右侧被裁、正文截断、按钮半露」。
 *
 * 两次修复都失败，因为攻错了方向：
 * - vc158 锚点 `displayMetrics` 短边 ×0.9 → 仍右溢
 * - vc160 锚点 `decorView.width` ×0.9 + `setGravity(CENTER)` → **同条件仍右溢**
 *
 * 宽度两次都算对了；问题在**定位参照系**。`setGravity(CENTER)` 无效正是因为它居中的
 * 参照系本身就是错的。
 *
 * ## 本组件的解法
 *
 * 与 `MainActivity.errorCardView` 同一思路：卡片直接挂到 Activity 的 content frame，
 * 活在 Activity 自己的视图树里，边界天然继承正确口径，彻底绕开 WMS frame。
 *
 * API 刻意与 `AlertDialog.Builder` 对齐（setTitle / setMessage / setView / setItems /
 * setSingleChoiceItems / setPositiveButton / setNegativeButton / setNeutralButton /
 * setCancelable / setOnCancelListener / setOnDismissListener / create / show），
 * 便于调用点机械替换。**回调首参直接给 AppDialog**（不是 DialogInterface），
 * 因此 `{ d, _ -> d.dismiss() }` 这类写法照旧可用。
 */
class AppDialog private constructor(
    private val activity: Activity,
    private val overlay: FrameLayout,
    private val card: LinearLayout,
    private val scroller: MaxHeightScrollView,
    private val messageView: TextView?,
    private val buttonBar: LinearLayout,
    private var spec: Spec,
) {
    private var dismissListener: ((AppDialog) -> Unit)? = null
    private var cancelListener: ((AppDialog) -> Unit)? = null
    private var showing = false
    private var cancelled = false

    // 单选列表（setSingleChoiceItems）
    internal var singleItems: Array<out String>? = null
    internal var singleChecked = -1
    internal var onSingleChoice: ((AppDialog, Int) -> Unit)? = null
    internal var singleContainer: LinearLayout? = null

    fun setMessage(text: CharSequence) {
        messageView?.text = text
    }

    /** 当前单选选中项（对应 AlertDialog.listView.checkedItemPosition）。 */
    fun getCheckedItemPosition(): Int = singleChecked

    /** 实例级换主按钮（自启等待框超时后把主按钮换成「确定」）。 */
    fun setPositiveButton(text: String, onClick: ((AppDialog) -> Unit)? = null) {
        spec = spec.copy(
            positive = if (onClick == null) {
                ButtonSpec(text) { it.dismiss() }
            } else {
                ButtonSpec(text, onClick)
            },
        )
        renderButtons()
    }

    fun setOnDismissListener(listener: ((AppDialog) -> Unit)?) {
        dismissListener = listener
    }

    fun setOnCancelListener(listener: ((AppDialog) -> Unit)?) {
        cancelListener = listener
    }

    fun show() {
        if (showing) return
        val content = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        if (overlay.parent != null) (overlay.parent as? ViewGroup)?.removeView(overlay)

        val hostW = content.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        val hostH = content.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
        card.layoutParams = FrameLayout.LayoutParams(
            (hostW * 0.9f).toInt().coerceAtMost(hostW),
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER }
        // 内容区「包内容、限高 70% 视口」——不能写死高度，否则短内容（如退出确认）
        // 会在正文与按钮之间留出一大段空白（K60 实测：固定 70% 高时中间空 ~900px）
        scroller.maxHeight = (hostH * 0.7f).toInt()
        scroller.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )

        content.addView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        showing = true
    }

    /** 由返回键 / 点遮罩触发的关闭（会额外回调 cancelListener）。 */
    internal fun cancel() {
        cancelled = true
        dismiss()
    }

    fun dismiss() {
        if (!showing) return
        (overlay.parent as? ViewGroup)?.removeView(overlay)
        showing = false
        if (cancelled) {
            cancelled = false
            cancelListener?.invoke(this)
        }
        dismissListener?.invoke(this)
    }

    fun isShowing(): Boolean = showing

    internal fun wire(cancelable: Boolean) {
        overlay.setOnClickListener { if (cancelable) cancel() }
        overlay.isFocusableInTouchMode = true
        overlay.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                if (cancelable) cancel()
                true
            } else {
                false
            }
        }
    }

    internal fun renderSingleChoice() {
        val box = singleContainer ?: return
        val list = singleItems ?: return
        box.removeAllViews()
        list.forEachIndexed { index, label ->
            val picked = index == singleChecked
            box.addView(TextView(activity).apply {
                text = (if (picked) "● " else "○ ") + label
                textSize = 15f
                setTextColor(if (picked) Ui.ACCENT else Ui.TEXT)
                setPadding(dp(8), dp(10), dp(8), dp(10))
                setOnClickListener {
                    singleChecked = index
                    renderSingleChoice()
                    onSingleChoice?.invoke(this@AppDialog, index)
                }
            })
        }
    }

    internal fun renderButtons() {
        buttonBar.removeAllViews()
        val specs = listOfNotNull(spec.negative, spec.neutral, spec.positive)
        if (specs.isEmpty()) {
            buttonBar.visibility = View.GONE
            return
        }
        buttonBar.visibility = View.VISIBLE
        specs.forEachIndexed { index, bs ->
            if (index > 0) {
                buttonBar.addView(View(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(10), 1)
                })
            }
            buttonBar.addView(
                pillButton(bs.text) { bs.onClick(this) },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(46),
                ),
            )
        }
    }

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    private fun pillButton(text: String, onClick: () -> Unit): TextView =
        TextView(activity).apply {
            this.text = text
            textSize = 15f
            setTextColor(Ui.PRIMARY_ON)
            gravity = Gravity.CENTER
            setPadding(dp(18), 0, dp(18), 0)
            background = GradientDrawable().apply {
                setColor(Ui.ACCENT)
                cornerRadius = dp(6).toFloat()
            }
            setOnClickListener { onClick() }
        }

    internal data class ButtonSpec(val text: String, val onClick: (AppDialog) -> Unit)

    internal data class Spec(
        val negative: ButtonSpec?,
        val neutral: ButtonSpec?,
        val positive: ButtonSpec?,
    )

    class Builder(private val activity: Activity) {
        private var title: String? = null
        private var message: CharSequence? = null
        private var customView: View? = null
        private var items: Array<out String>? = null
        private var onItem: ((AppDialog, Int) -> Unit)? = null
        private var singleItems: Array<out String>? = null
        private var singleChecked = -1
        private var onSingleChoice: ((AppDialog, Int) -> Unit)? = null
        private var positive: ButtonSpec? = null
        private var negative: ButtonSpec? = null
        private var neutral: ButtonSpec? = null
        private var cancelable = true
        private var onDismiss: ((AppDialog) -> Unit)? = null
        private var onCancel: ((AppDialog) -> Unit)? = null

        fun setTitle(text: String) = apply { title = text }
        fun setMessage(text: CharSequence) = apply { message = text }
        fun setView(v: View) = apply { customView = v }

        fun setItems(list: Array<out String>, listener: (AppDialog, Int) -> Unit) = apply {
            items = list
            onItem = listener
        }

        fun setSingleChoiceItems(
            list: Array<out String>,
            checkedItem: Int,
            listener: (AppDialog, Int) -> Unit,
        ) = apply {
            singleItems = list
            singleChecked = checkedItem
            onSingleChoice = listener
        }

        fun setPositiveButton(text: String, listener: ((AppDialog, Int) -> Unit)? = null) = apply {
            positive = ButtonSpec(text) { d -> listener?.invoke(d, 0) ?: d.dismiss() }
        }

        fun setNegativeButton(text: String, listener: ((AppDialog, Int) -> Unit)? = null) = apply {
            negative = ButtonSpec(text) { d -> listener?.invoke(d, 0) ?: d.dismiss() }
        }

        fun setNeutralButton(text: String, listener: ((AppDialog, Int) -> Unit)? = null) = apply {
            neutral = ButtonSpec(text) { d -> listener?.invoke(d, 0) ?: d.dismiss() }
        }

        fun setCancelable(flag: Boolean) = apply { cancelable = flag }
        fun setOnDismissListener(listener: ((AppDialog) -> Unit)?) = apply { onDismiss = listener }
        fun setOnCancelListener(listener: ((AppDialog) -> Unit)?) = apply { onCancel = listener }

        fun create(): AppDialog = build()
        fun show(): AppDialog = build().also { it.show() }

        private fun build(): AppDialog {
            val density = activity.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()

            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(Ui.CARD)
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1), Ui.BORDER)
                }
                setPadding(dp(20), dp(18), dp(20), dp(14))
            }

            if (!title.isNullOrBlank()) {
                card.addView(TextView(activity).apply {
                    text = title
                    textSize = 17f
                    setTextColor(Ui.TEXT)
                })
            }

            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(12), 0, 0)
            }
            var messageView: TextView? = null
            if (!message.isNullOrEmpty()) {
                messageView = TextView(activity).apply {
                    text = message
                    textSize = 14f
                    setTextColor(Ui.SUB)
                    setLineSpacing(dp(2).toFloat(), 1f)
                }
                content.addView(messageView)
            }
            customView?.let { content.addView(it) }
            // 单选列表容器（build() 末尾回填数据与回调后重绘）
            val singleBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            content.addView(singleBox)
            val itemCb = onItem
            items?.let { list ->
                list.forEachIndexed { index, label ->
                    content.addView(TextView(activity).apply {
                        text = label
                        textSize = 15f
                        setTextColor(Ui.TEXT)
                        setPadding(dp(8), dp(12), dp(8), dp(12))
                        setOnClickListener { pending?.let { d -> itemCb?.invoke(d, index) } }
                    })
                }
            }

            val scroller = MaxHeightScrollView(activity).apply {
                addView(content)
                isFillViewport = false
            }
            card.addView(
                scroller,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )

            val buttonBar = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setPadding(0, dp(14), 0, 0)
            }
            card.addView(
                buttonBar,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )

            val overlay = FrameLayout(activity).apply {
                setBackgroundColor(0xAA000000.toInt())
                addView(
                    card,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { gravity = Gravity.CENTER },
                )
            }

            val dialog = AppDialog(
                activity = activity,
                overlay = overlay,
                card = card,
                scroller = scroller,
                messageView = messageView,
                buttonBar = buttonBar,
                spec = Spec(negative, neutral, positive),
            )
            // setItems 的行需要在 dialog 构造后才能拿到自身引用
            pending = dialog
            dialog.singleContainer = singleBox
            dialog.singleItems = singleItems
            dialog.singleChecked = singleChecked
            dialog.onSingleChoice = onSingleChoice
            dialog.renderSingleChoice()
            dialog.wire(cancelable)
            dialog.setOnDismissListener(onDismiss)
            dialog.setOnCancelListener(onCancel)
            dialog.renderButtons()
            return dialog
        }

        /** inflate 期间行点击需要 dialog 自身；build() 里回填。 */
        private var pending: AppDialog? = null
    }
}

/** 包内容、限高的滚动区：短内容不留白，长内容可滚（按钮不被顶出屏幕）。 */
private class MaxHeightScrollView(context: android.content.Context) : ScrollView(context) {
    var maxHeight = Int.MAX_VALUE

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            widthMeasureSpec,
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST),
        )
    }
}
