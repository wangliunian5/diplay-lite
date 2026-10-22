package com.shilapi.diplay.lite

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * M6k26：方向盘按键学习页（1.0.29，设置页同款骨架的独立页面）。
 *
 * 两代布局教训（同一页面在 t3/3e 上顶栏可见、主体全黑）：
 *   v1 全屏 Dialog + 左右分栏 weight → EMUI/api19 内容区不渲染；
 *   v2 页面路由 + 左右分栏 weight → 同样黑屏。
 * v3 彻底放弃分栏与 weight，完全复刻设置页已验证的渲染骨架：
 *   ScrollView > column（垂直卡片堆叠，无 weight、无嵌套滚动），逻辑不变。
 */
internal class SteeringPage(private val activity: MainActivity) {

    private val learningOwner = Any()
    private var state = "home"
    private var operation = ""

    private var localKeyHook: ((keyCode: Int, action: Int) -> Unit)? = null

    // 状态卡片引用
    private var stTitle: TextView? = null
    private var stSub: TextView? = null
    private var conflictNote: TextView? = null
    private var keySummaryText: TextView? = null
    private var confirmRow: LinearLayout? = null
    private var cancelRow: LinearLayout? = null
    private var barFill: View? = null
    private var barTrack: FrameLayout? = null
    private var point: View? = null
    private var watchText: TextView? = null
    private var listContainer: LinearLayout? = null

    private val watchLines = ArrayDeque<String>()
    private val uiHandler = Handler(Looper.getMainLooper())
    private var countdownRemain = 0
    private var countdownTask: Runnable? = null

    // 待确认的候选绑定（confirm 态）
    private var pendingCandidate: SteeringBinding? = null
    private var pendingConflictOperation: String? = null
    private var pendingLabel = ""

    // 本地按键学习白名单：媒体键 + 语音助手 + 音量键（24/25，部分 ROM 会先消费，抓不到时靠管线源）
    private val localLearnableKeys = setOf(
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_VOICE_ASSIST,
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
    )

    private val opLabels = listOf(
        "volume_up" to "音量+",
        "volume_down" to "音量-",
        "play_pause" to "播放/暂停",
        "previous" to "上一曲",
        "next" to "下一曲",
        "siri" to "Siri",
    )

    // ---------- 页面构建（设置页同款骨架） ----------

    /** 由 MainActivity.render() 调用：ScrollView + 垂直 column，与设置页完全同构。 */
    fun buildRoot(): View {
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dip(16), dip(8), dip(16), dip(32))
        }

        // 顶栏：返回箭头 + 标题（复刻设置页 buildHeader）
        column.addView(
            LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    android.widget.ImageView(activity).apply {
                        setImageDrawable(IconPainter.back(activity, 22, Ui.TEXT))
                        setPadding(0, dip(8), dip(12), dip(8))
                        setOnClickListener { activity.setPage("settings") }
                    },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT),
                )
                addView(
                    TextView(activity).apply {
                        text = "方向盘按键学习"
                        setTextColor(Ui.TEXT)
                        textSize = 20f
                        setTypeface(typeface, Typeface.BOLD)
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
            },
            block(),
        )

        // 学习状态卡片（垂直堆叠：状态/副文/进度条/键值摘要/冲突警示/取消行/确认行）
        val statusPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.card(activity, 12)
            setPadding(dip(20), dip(18), dip(20), dip(18))
        }
        stTitle = TextView(activity).apply {
            text = "点击下方键位的「学习」开始"
            setTextColor(Ui.SUB)
            textSize = 17f
        }
        statusPanel.addView(stTitle, block())
        stSub = TextView(activity).apply {
            text = "学习期间方控不触发实际操作；绑定在重新连接 CarPlay 后生效。一个按键值只能绑定一个键位。"
            setTextColor(Ui.SUB)
            textSize = 13f
            setLineSpacing(0f, 1.2f)
        }
        statusPanel.addView(stSub, block(top = dip(6)))

        // 进度条：外槽圆角 + 内层 ACCENT 按宽度收缩（API16 安全）
        barTrack = FrameLayout(activity).apply {
            background = GradientDrawable().apply {
                setColor(Ui.TRACK_OFF)
                cornerRadius = dip(4).toFloat()
            }
            visibility = View.GONE
        }
        barFill = View(activity).apply {
            background = GradientDrawable().apply {
                setColor(Ui.ACCENT)
                cornerRadius = dip(4).toFloat()
            }
        }
        barTrack!!.addView(
            barFill,
            FrameLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dip(8)),
        )
        statusPanel.addView(barTrack, block(top = dip(14)))

        keySummaryText = TextView(activity).apply {
            setTextColor(Ui.ACCENT)
            textSize = 15f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            visibility = View.GONE
        }
        statusPanel.addView(keySummaryText, block(top = dip(12)))

        conflictNote = TextView(activity).apply {
            setTextColor(Ui.WARNING)
            textSize = 13f
            background = GradientDrawable().apply {
                setColor(0x1FFFC480.toInt())
                cornerRadius = dip(8).toFloat()
            }
            setPadding(dip(12), dip(8), dip(12), dip(8))
            setLineSpacing(0f, 1.3f)
            visibility = View.GONE
        }
        statusPanel.addView(conflictNote, block(top = dip(12)))

        // 取消学习行（listening 态显示）
        cancelRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            addView(outlineButton("取消学习") {
                appendWatch("已取消学习")
                resetIdle()
            })
        }
        statusPanel.addView(cancelRow, block(top = dip(14)))

        // 确认行（confirm 态显示）：取消=重新监听 / 确认=绑定落盘
        confirmRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            addView(
                Button(activity).apply {
                    text = "取消"
                    setTextColor(Ui.TEXT)
                    textSize = 15f
                    setAllCaps(false)
                    background = Ui.pillOutline(activity, Ui.OUTLINE)
                    setOnClickListener {
                        state = "listening"
                        showConfirmRow(false)
                        beginListen(operation)
                    }
                },
                LinearLayout.LayoutParams(0, dip(44), 1f).apply { rightMargin = dip(10) },
            )
            addView(
                Button(activity).apply {
                    text = "确认绑定"
                    setTextColor(Ui.PRIMARY_ON)
                    textSize = 15f
                    setAllCaps(false)
                    setTypeface(typeface, Typeface.BOLD)
                    background = Ui.pillSolid(activity, Ui.PRIMARY)
                    setOnClickListener {
                        val candidate = pendingCandidate
                        if (candidate != null) {
                            applyBinding(candidate, pendingConflictOperation ?: candidate.operation)
                            appendWatch("已绑定：【$pendingLabel】← ${keySummaryText?.text ?: ""}")
                            toast("学习成功：$pendingLabel，重新连接 CarPlay 生效")
                            resetIdle()
                        }
                    }
                },
                LinearLayout.LayoutParams(0, dip(44), 1f),
            )
        }
        statusPanel.addView(confirmRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dip(44)).apply {
            topMargin = dip(16)
        })

        column.addView(sectionLabel("学习状态"), block(top = dip(12), bottom = dip(6)))
        column.addView(statusPanel, block(bottom = dip(14)))

        // 6 个键位行（entryRow 同款行式卡片）
        listContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        column.addView(sectionLabel("学习键位"), block(bottom = dip(6)))
        column.addView(listContainer, block(bottom = dip(14)))

        // 清除全部
        column.addView(
            LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    TextView(activity).apply {
                        text = "清除全部已学配置"
                        setTextColor(Ui.TEXT)
                        textSize = 16f
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(outlineButton("清除") { confirmClearConfig() })
            },
            block(bottom = dip(14)),
        )

        // 观察记录卡（多行等宽文本）
        point = View(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Ui.SUB)
            }
        }
        watchText = TextView(activity).apply {
            setTextColor(Ui.SUB)
            textSize = 12f
            setTypeface(Typeface.MONOSPACE)
            setLineSpacing(0f, 1.25f)
        }
        column.addView(
            LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    TextView(activity).apply {
                        text = "按键观察记录"
                        setTextColor(Ui.TEXT)
                        textSize = 16f
                        setTypeface(typeface, Typeface.BOLD)
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(point!!, LinearLayout.LayoutParams(dip(8), dip(8)))
            },
            block(bottom = dip(6)),
        )
        column.addView(
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = Ui.card(activity, 12)
                setPadding(dip(16), dip(12), dip(16), dip(12))
                addView(watchText)
            },
            block(bottom = dip(8)),
        )

        renderList()
        return ScrollView(activity).apply {
            // 1.1.1 UI：背景与其他页统一为 Ui.BG（此前裸用窗口黑底，观感偏黑不一致）。
            setBackgroundColor(Ui.BG)
            addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    // ---------- 状态机 ----------

    private fun resetIdle() {
        state = "home"
        localKeyHook = null
        LiteSteeringKeys.stopLearning(learningOwner)
        stTitle?.apply {
            setTextColor(Ui.SUB)
            textSize = 17f
            text = "点击下方键位的「学习」开始"
        }
        stSub?.text = "学习期间方控不触发实际操作；绑定在重新连接 CarPlay 后生效。一个按键值只能绑定一个键位。"
        barTrack?.visibility = View.GONE
        setPoint(false)
        cancelRow?.visibility = View.GONE
        showConfirmRow(false)
        renderList()
    }

    /** 重画 6 个键位行（entryRow 同款：操作名+绑定摘要 ｜ 清除 ｜ 学习/重学）。 */
    private fun renderList() {
        val container = listContainer ?: return
        container.removeAllViews()
        val bindings = SteeringStore.loadEnabled(activity)?.bindings.orEmpty()
        opLabels.forEach { (op, label) ->
            val binding = bindings.firstOrNull { it.operation == op }
            container.addView(
                LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dip(16), dip(12), dip(16), dip(12))
                    background = Ui.card(activity, 12)
                    addView(
                        LinearLayout(activity).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(
                                TextView(activity).apply {
                                    text = label
                                    setTextColor(Ui.TEXT)
                                    textSize = 16f
                                },
                            )
                            addView(
                                TextView(activity).apply {
                                    text = binding?.let { "已绑定 · ${bindingSummary(it)}" } ?: "未学习"
                                    setTextColor(if (binding != null) Ui.GREEN else Ui.SUB)
                                    textSize = 12f
                                },
                                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                                    topMargin = dip(2)
                                },
                            )
                        },
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    // 1 对 1 清除：只删该键位的绑定（学习错了随时复位）。
                    if (binding != null) {
                        addView(
                            outlineButton("清除") { confirmClearOne(op, label) },
                            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dip(8) },
                        )
                    }
                    addView(outlineButton(if (binding == null) "学习" else "重学") { beginListen(op) })
                },
                block(bottom = dip(8)),
            )
        }
    }

    private fun confirmClearConfig() {
        AppDialog.Builder(activity)
            .setTitle("清除已学配置")
            .setMessage("将清除全部已学习的方控按键绑定，确定吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("清除") { _, _ ->
                SteeringStore.clear(activity)
                LiteSteeringKeys.reloadProfile(activity)
                LiteLog.log("steering", "方控配置已清除（学习页）")
                toast("已清除")
                appendWatch("已清除全部绑定")
                renderList()
            }
            .show()
    }

    /** 1 对 1 清除单个键位的绑定。 */
    private fun confirmClearOne(op: String, label: String) {
        AppDialog.Builder(activity)
            .setTitle("清除绑定")
            .setMessage("将清除【$label】的按键绑定，确定吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("清除") { _, _ ->
                val kept = SteeringStore.loadEnabled(activity)?.bindings
                    ?.filter { it.operation != op }.orEmpty()
                val model = Build.MODEL.orEmpty().ifBlank { "unknown" }
                try {
                    SteeringStore.save(
                        activity,
                        SteeringProfile(carModel = model, headUnitModel = model, bindings = kept),
                    )
                    LiteSteeringKeys.reloadProfile(activity)
                    LiteLog.log("steering", "方控绑定已清除 op=$op")
                    toast("已清除【$label】")
                    appendWatch("已清除：【$label】")
                } catch (error: Exception) {
                    toast("清除失败：${error.message}")
                }
                renderList()
            }
            .show()
    }

    private fun setPoint(live: Boolean) {
        point?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (live) Ui.GREEN else Ui.SUB)
        }
    }

    private fun showConfirmRow(show: Boolean) {
        confirmRow?.visibility = if (show) View.VISIBLE else View.GONE
        keySummaryText?.visibility = if (show) View.VISIBLE else View.GONE
        conflictNote?.visibility = if (show && pendingConflictOperation != null) View.VISIBLE else View.GONE
        cancelRow?.visibility = if (!show && state == "listening") View.VISIBLE else View.GONE
    }

    /** 监听态：状态卡片大字+进度条+取消按钮；两路抓取（管线 + 本地 KeyEvent），先到先得。 */
    private fun beginListen(op: String) {
        val label = labelOf(op)
        stopCountdown()
        showConfirmRow(false)
        state = "listening"
        operation = op
        pendingLabel = label
        stTitle?.apply {
            setTextColor(Ui.TEXT)
            textSize = 20f
            text = "当前学习按键：【$label】，持续监听，请按键…"
        }
        stSub?.text = "剩余 20 秒 · 按下的按键将显示在下方观察记录"
        barTrack?.visibility = View.VISIBLE
        setPoint(true)
        cancelRow?.visibility = View.VISIBLE
        appendWatch("开始学习：【$label】")
        startCountdown(label)
        localKeyHook = { keyCode, action ->
            if (action == KeyEvent.ACTION_DOWN) {
                onCaptured(SteeringObservedKey(keyCode, action, "key_event"))
            }
        }
        LiteSteeringKeys.startLearning(activity, learningOwner) { key ->
            activity.runOnUiThread {
                if (state != "listening") return@runOnUiThread
                if (key == null) return@runOnUiThread
                onCaptured(key)
            }
        }
        renderList()
    }

    /** 20 秒学习倒计时：进度条逐步收缩；到点停监听回待机。 */
    private fun startCountdown(label: String) {
        countdownRemain = 20
        val task = object : Runnable {
            override fun run() {
                if (state != "listening") return
                if (countdownRemain <= 0) {
                    stTitle?.apply {
                        setTextColor(Ui.SUB)
                        textSize = 17f
                        text = "【$label】学习超时：20 秒内未检测到按键"
                    }
                    stSub?.text =
                        "若该键被系统直接消费（如部分车机的音量键），将无法学习，请换其他按键验证。"
                    barTrack?.visibility = View.GONE
                    setPoint(false)
                    cancelRow?.visibility = View.GONE
                    appendWatch("学习超时：【$label】未观察到按键")
                    state = "home"
                    localKeyHook = null
                    LiteSteeringKeys.stopLearning(learningOwner)
                    countdownTask = null
                    renderList()
                    return
                }
                stSub?.text = "剩余 $countdownRemain 秒 · 按下的按键将显示在下方观察记录"
                val fill = barFill
                if (fill != null) {
                    fill.layoutParams = fill.layoutParams.apply {
                        width = (dip(280) * countdownRemain / 20)
                        if (width <= 0) width = 1
                    }
                }
                countdownRemain--
                uiHandler.postDelayed(this, 1000)
            }
        }
        countdownTask = task
        uiHandler.post(task)
    }

    private fun stopCountdown() {
        countdownTask?.let(uiHandler::removeCallbacks)
        countdownTask = null
    }

    /** 监视区观察记录：保留最近 6 行。 */
    private fun appendWatch(line: String) {
        if (watchLines.size >= 6) watchLines.removeFirst()
        watchLines.addLast(line)
        watchText?.text = watchLines.joinToString("\n")
    }

    /**
     * 捕获到按键（两路先到先得）；keyCode=0 是广播订阅提示不算按键。
     * confirm 态页内确认：状态卡片显示键位摘要 + 冲突警示 + 取消/确认按钮。
     */
    private fun onCaptured(key: SteeringObservedKey) {
        if (key.keyCode == 0) return
        if (state != "listening") return
        stopCountdown()
        LiteSteeringKeys.stopLearning(learningOwner)
        localKeyHook = null
        state = "confirm"
        val op = operation
        val label = labelOf(op)
        pendingLabel = label
        val actionLabel = if (key.event == KeyEvent.ACTION_DOWN) "down" else "up"
        val keySummary = "键值 ${key.keyCode}（$actionLabel · ${sourceLabel(key.source)}）"
        appendWatch("捕获：$keySummary")
        val candidate = SteeringBinding(
            op, key.keyCode, key.event, key.source,
            key.logTag, key.broadcastAction, key.keyExtra, key.eventExtra, key.logContains,
        )
        val conflict = SteeringStore.loadEnabled(activity)?.bindings
            ?.firstOrNull { it.inputId == candidate.inputId && it.operation != op }
        pendingCandidate = candidate
        pendingConflictOperation = conflict?.operation
        keySummaryText?.text = keySummary
        conflictNote?.text =
            "注意：该按键已绑定到【${labelOf(conflict?.operation ?: "")}】（一个键值只能绑定一个键位），确认后将覆盖原绑定。"
        stTitle?.apply {
            setTextColor(if (conflict != null) Ui.WARNING else Ui.TEXT)
            textSize = 17f
            text = if (conflict != null) "按键冲突" else "确认学习"
        }
        stSub?.text = "已读取到以下键位，是否学习到【$label】？"
        barTrack?.visibility = View.GONE
        setPoint(false)
        showConfirmRow(true)
        renderList()
    }

    private fun sourceLabel(source: String): String = when (source) {
        "key_event" -> "本地按键"
        "broadcast" -> "广播"
        "session" -> "媒体会话"
        else -> "日志"
    }

    /** 落盘：替换同键位旧绑定（冲突覆盖时另删被冲突键位），JSON 结构与上游同构。 */
    private fun applyBinding(candidate: SteeringBinding, removeOperation: String) {
        val kept = SteeringStore.loadEnabled(activity)?.bindings
            ?.filter { it.operation != removeOperation && it.operation != candidate.operation }
            .orEmpty()
        val model = Build.MODEL.orEmpty().ifBlank { "unknown" }
        try {
            SteeringStore.save(
                activity,
                SteeringProfile(carModel = model, headUnitModel = model, bindings = kept + candidate),
            )
            LiteSteeringKeys.reloadProfile(activity)
            LiteLog.log(
                "steering",
                "学习保存 op=${candidate.operation} key=${candidate.keyCode} event=${candidate.event} source=${candidate.source}",
            )
        } catch (error: Exception) {
            toast("保存失败：${error.message}")
        }
    }

    // ---------- 生命周期 ----------

    /** 本地 KeyEvent 捕获（MainActivity.onKeyDown 分发）：仅监听态消费。 */
    fun consumeKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val hook = localKeyHook
        if (hook != null && event != null && event.repeatCount == 0 && keyCode in localLearnableKeys) {
            hook(keyCode, event.action)
            return true
        }
        return false
    }

    /** 页面离开（切页/返回键）时调用：停倒计时/停监听/复位抑制。 */
    fun cleanup() {
        stopCountdown()
        localKeyHook = null
        LiteSteeringKeys.stopLearning(learningOwner)
        LiteSteeringKeys.setUiSuppressed(false)
    }

    // ---------- UI 辅助（与设置页同款） ----------

    private fun dip(value: Int): Int = Ui.dip(activity, value)

    private fun block(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = top
            bottomMargin = bottom
        }

    private fun sectionLabel(text: String): TextView = TextView(activity).apply {
        setText(text)
        setTextColor(Ui.TEXT)
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun outlineButton(text: String, onClick: () -> Unit): Button = Button(activity).apply {
        setText(text)
        textSize = 14f
        minHeight = dip(38)
        setAllCaps(false)
        background = Ui.pillOutline(activity, Ui.OUTLINE)
        setTextColor(Ui.OUTLINE)
        setPadding(dip(16), dip(5), dip(16), dip(5))
        setOnClickListener { onClick() }
    }

    private fun labelOf(operation: String): String =
        opLabels.firstOrNull { it.first == operation }?.second ?: operation

    private fun bindingSummary(binding: SteeringBinding): String = when (binding.source) {
        "key_event" -> "键值 ${binding.keyCode} · 本地按键"
        "broadcast" -> "键值 ${binding.keyCode} · 广播"
        "session" -> "键值 ${binding.keyCode} · 媒体会话"
        else -> "键值 ${binding.keyCode} · 日志"
    }

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }
}
