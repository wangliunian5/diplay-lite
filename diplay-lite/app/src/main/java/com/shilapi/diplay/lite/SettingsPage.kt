package com.shilapi.diplay.lite

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.graphics.Typeface
import android.os.Build
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import org.json.JSONObject

/**
 * 横竖屏模式落到窗口上：land/portrait 强制。
 * M6k24：自动旋转取消（8815 实测 Display/传感器漂移导致横竖屏抖动），历史 auto 值归一为横屏。
 * M6k25：单 Activity 化后由 MainActivity（onCreate/onResume/进入子页时）调用。
 */
fun applyOrientationMode(activity: Activity, mode: String) {
    activity.requestedOrientation = when (mode) {
        "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }
}

/**
 * M6k24k：切页"先竖屏再横屏"诊断——记录 requestedOrientation（运行时值）、configuration
 * 方向、Display 实际宽高。setContentView 后调用，用于钉死新窗口创建时的真实方向来源。
 * req 映射：0=LANDSCAPE 1=PORTRAIT 14=FULL_SENSOR -1=UNSPECIFIED。
 * M6k25：调用点移到 MainActivity.render()（tag 用当前 page）。
 */
fun logOrientation(activity: Activity, tag: String) {
    val display = activity.windowManager.defaultDisplay
    val req = when (activity.requestedOrientation) {
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> "LANDSCAPE"
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> "PORTRAIT"
        else -> activity.requestedOrientation.toString()
    }
    val cfg = if (activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) "PORTRAIT" else "LANDSCAPE"
    LiteLog.log(
        "ui",
        "方向诊断[$tag] req=$req cfg=$cfg display=${display.width}x${display.height} mode=${LiteConfig.load(activity).orientationMode}",
    )
}

/**
 * 设置页（M6k14，渲染图 2；M6k25 单 Activity 化为 MainActivity 的 page="settings"）：
 * 零 XML 程序化构建，无卡片、分组 + 分割线编排。
 *
 * 数值框回显当前值，框内 hint 展示允许范围；分辨率改动 >100% 需二次确认（放大=超采样）。
 * 蓝牙/信道表单已迁到热点配置页；日志预览/导出从主页迁入。
 * 即改即存：所有开关/单选变动立即持久化（服务侧现读 LiteConfig，改完即生效）。
 *
 * M6k25 迁移要点：原 SettingsActivity 的 this（Activity 上下文）改为构造传入的 MainActivity；
 * 有状态字段（isLoading/音频流编号/Gitee 上传/方向盘向导状态机）全部挂在本页对象上；
 * 页面离开（切回 home / onConfigurationChanged 重建 / Activity 销毁）时调 [cleanup]：
 * persistFromForm + 注销学习向导（含 UI 抑制复位）+ 试听 AudioChannelPreview.close()。
 * 本地 KeyEvent 钩子经 [consumeKeyDown] 由 MainActivity.onKeyDown 消费。
 */
class SettingsPage(private val activity: MainActivity) {

    private lateinit var inputScale: EditText
    private lateinit var inputDuck: EditText
    // M6k19：音乐缓存毫秒输入
    private lateinit var inputBuffer: EditText
    // M6k19：默认自动连接方式三选一（从主页 toggle 迁移）
    private lateinit var rgAutoChannel: RadioGroup
    private lateinit var rbAutoCar: RadioButton
    private lateinit var rbAutoRelay: RadioButton
    private lateinit var rbAutoUsb: RadioButton
    private lateinit var rgOrientation: RadioGroup
    private lateinit var rbOrientLand: RadioButton
    private lateinit var rbOrientPortrait: RadioButton
    private lateinit var rbAvFull: RadioButton
    private lateinit var rbAvBt: RadioButton
    // M6k21：音频流编号当前值（弹窗选定时先存内存，点保存才落盘）
    private var mediaAudioStreamValue = 0
    private var navAudioStreamValue = 0
    private lateinit var mediaStreamValueText: TextView
    private lateinit var navStreamValueText: TextView
    private lateinit var cbBoot: CheckBox
    private lateinit var cbBootBluetooth: CheckBox
    private lateinit var cbBootHotspot: CheckBox
    private lateinit var cbAutoConnect: CheckBox
    private lateinit var cbSoftDecode: CheckBox
    private lateinit var cbFps30: CheckBox
    private lateinit var cbSpeaker: CheckBox
    private lateinit var tvVersion: TextView

    // loadIntoForm 期间静默：setChecked 会触发监听，避免半加载状态写盘
    private var isLoading = false

    /** 页面离开时调用（原 onPause + 向导 dismiss 复位，M6k25 收口为一个清理点）。 */
    fun cleanup() {
        runCatching { persistFromForm() }
        // 学习向导若在开：dismiss 触发 setOnDismissListener（复位抑制/钩子/stopLearning）
        steeringWizardDialog?.dismiss()
        steeringWizardDialog = null
        AudioChannelPreview.close()
        AudioChannelPreview.currentActivity = null
    }

    /** 本地 KeyEvent 捕获（原 SettingsActivity.onKeyDown）：仅向导监听/验证态消费。 */
    fun consumeKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val hook = steeringLocalKeyHook
        if (hook != null && event != null && event.repeatCount == 0 && keyCode in steeringLocalLearnableKeys) {
            hook(keyCode, event.action)
            return true
        }
        return false
    }

    /** 进入设置页：构建根视图（含悬浮球）并完成表单回显。由 MainActivity.render() 调用。 */
    fun buildRoot(): FrameLayout {
        applyOrientationMode(activity, LiteConfig.load(activity).orientationMode)
        // M6k19：进设置页不自动弹软键盘 —— 点击输入框时才弹（8815 实测反馈）。
        activity.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        val root = FrameLayout(activity).apply { setBackgroundColor(Ui.BG) }
        root.addView(
            buildContent(),
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        // M6k23：悬浮球所有画面常驻 —— 右上角球点击返回主页。
        attachPageBall(activity, root) { activity.goHome() }
        loadIntoForm()
        refreshAllDrawables()
        tvVersion.text = "版本 ${activity.readVersionName()}"
        LiteLog.log("ui", "设置页打开")
        return root
    }

    private fun dip(value: Int): Int = Ui.dip(activity, value)

    // ---------- 程序化视图构建 ----------

    private fun buildContent(): View {
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dip(16), dip(8), dip(16), dip(32))
        }
        column.addView(buildHeader(), block())

        // 数值行：左标题 ｜ 右数值框（回显当前值）+ 应用
        inputScale = buildValueRow(column, "分辨率自定义", "${LiteConfig.MIN_SCALE_PERCENT}% - ${LiteConfig.MAX_SCALE_PERCENT}%") { applyScale(it) }
        inputDuck = buildValueRow(column, "导航音频触发音量降低", "${LiteConfig.MIN_DUCK_PERCENT}% - ${LiteConfig.MAX_DUCK_PERCENT}%") { applyDuck(it) }
        // M6k19：音乐缓存毫秒数（跟随导航音量降低之后），范围 10-4000ms。
        inputBuffer = buildValueRow(column, "音乐缓存", "${LiteConfig.MIN_MEDIA_BUFFER_MILLIS} - ${LiteConfig.MAX_MEDIA_BUFFER_MILLIS} (ms)") { applyBuffer(it) }

        // M6k21：连接配置（从主页通道卡迁入）——车机热点/中继热点的网络配置入口。
        column.addView(sectionLabel("连接配置"), block(top = dip(12), bottom = dip(4)))
        column.addView(entryRow("车机热点网络配置") { activity.openHotspotPage("car") }, block(top = dip(4)))
        column.addView(entryRow("中继热点网络配置") { activity.openHotspotPage("relay") }, block(top = dip(8)))

        // 横竖屏切换（M6k24：自动旋转取消，只留强制横屏/强制竖屏；画面声明方向跟随此设置）
        column.addView(sectionLabel("横竖屏切换"), block(top = dip(12), bottom = dip(4)))
        rgOrientation = RadioGroup(activity).apply { orientation = LinearLayout.VERTICAL }
        rbOrientLand = radio("强制横屏")
        rbOrientPortrait = radio("强制竖屏")
        rgOrientation.addView(rbOrientLand)
        rgOrientation.addView(rbOrientPortrait)
        rgOrientation.setOnCheckedChangeListener { _, _ ->
            if (isLoading) return@setOnCheckedChangeListener
            refreshRadioDrawables(rgOrientation)
            val previous = LiteConfig.load(activity).orientationMode
            val target = currentOrientationMode()
            if (target == previous) return@setOnCheckedChangeListener
            // M6k24g：改方向改为「重启后全局生效」——重启从冷启动即按新方向渲染。
            AlertDialog.Builder(activity)
                .setTitle("应用横竖屏设置")
                .setMessage(
                    "原方向：${if (previous == "portrait") "强制竖屏" else "强制横屏"}，" +
                        "确认修改为${if (target == "portrait") "强制竖屏" else "强制横屏"}吗？\n\n" +
                        "点击「应用」后将自动重启软件，重启后全局应用新方向；点击主页连接即按新方向声明画面。",
                )
                .setNegativeButton("取消") { _, _ ->
                    isLoading = true
                    if (previous == "portrait") rbOrientPortrait.isChecked = true else rbOrientLand.isChecked = true
                    refreshRadioDrawables(rgOrientation)
                    isLoading = false
                }
                .setPositiveButton("应用并重启") { _, _ ->
                    persistFromForm()
                    activity.restartAppForVideoChange()
                }
                .show()
        }
        column.addView(rgOrientation, block())

        // M6k19：默认自动连接方式（三选一，从主页 toggle 迁移至此）。
        // 打开 App 时按此通道自动连接：car=车机热点 / relay=中继热点 / usb=USB（USB 通道待接入时仅记录）。
        column.addView(sectionLabel("默认自动连接方式"), block(top = dip(12), bottom = dip(4)))
        rgAutoChannel = RadioGroup(activity).apply { orientation = LinearLayout.HORIZONTAL }
        rbAutoCar = radio("车机热点")
        rbAutoRelay = radio("中继热点")
        rbAutoUsb = radio("USB连接")
        rgAutoChannel.addView(rbAutoCar)
        rgAutoChannel.addView(rbAutoRelay)
        rgAutoChannel.addView(rbAutoUsb)
        rgAutoChannel.setOnCheckedChangeListener { _, _ ->
            if (isLoading) return@setOnCheckedChangeListener
            refreshRadioDrawables(rgAutoChannel)
            persistFromForm()
        }
        column.addView(rgAutoChannel, block())

        // 音视频链路（横排二选）
        column.addView(sectionLabel("音视频链路选择"), block(top = dip(12), bottom = dip(4)))
        val avGroup = RadioGroup(activity).apply { orientation = LinearLayout.HORIZONTAL }
        rbAvFull = radio("全CarPlay")
        rbAvBt = radio("蓝牙音频+CarPlay")
        avGroup.addView(rbAvFull)
        avGroup.addView(rbAvBt)
        avGroup.setOnCheckedChangeListener { _, _ ->
            if (isLoading) return@setOnCheckedChangeListener
            refreshRadioDrawables(avGroup)
            persistFromForm()
        }
        column.addView(avGroup, block())

        // 音频流选择（M6k21，参考 fork carlito12345 版 0~20 音频流编号覆盖）：
        // 媒体/导航各自独立；0=自动路由（usage），1~20=传统音频流编号（含车机厂商扩展流）。
        // 点选数值即试听，保存后重新连接生效。
        column.addView(sectionLabel("音频流选择"), block(top = dip(12), bottom = dip(4)))
        column.addView(audioStreamRow("媒体音频流", isMedia = true), block())
        column.addView(audioStreamRow("导航音频流", isMedia = false), block())
        column.addView(
            TextView(activity).apply {
                text = "0 表示自动路由。1~20 用于选择传统音频流编号，包括车机支持的厂商扩展输出。点选数值可试听。更改在重新连接后生效。"
                setTextColor(Ui.SUB)
                textSize = 12f
            },
            block(bottom = dip(4)),
        )

        // 体验优化：渲染图画了 3 项，30帧/扬声器两项既有功能保留（图未画出）
        column.addView(sectionLabel("体验优化"), block(top = dip(12), bottom = dip(4)))
        cbBoot = checkbox("开机自启")
        // 1.0.11：开机自动开启蓝牙/热点（独立于"开机自启"，任一勾选 BootReceiver 即工作）
        cbBootBluetooth = checkbox("开机自动开启蓝牙")
        cbBootHotspot = checkbox("开机自动开启热点")
        cbAutoConnect = checkbox("启动后自动连接")
        cbSoftDecode = checkbox("强制软解")
        cbFps30 = checkbox("限制 30 帧")
        cbSpeaker = checkbox("通话声音走扬声器")
        column.addView(cbBoot, block(bottom = dip(2)))
        column.addView(cbBootBluetooth, block(bottom = dip(2)))
        column.addView(cbBootHotspot, block(bottom = dip(2)))
        column.addView(cbAutoConnect, block(bottom = dip(2)))
        column.addView(cbSoftDecode, block(bottom = dip(2)))
        column.addView(cbFps30, block(bottom = dip(2)))
        column.addView(cbSpeaker, block())

        // M6k23：自定义返回图标页（选图→裁剪→确认保存）。
        column.addView(entryRow("自定义返回图标") { activity.setPage("caricon") }, block(top = dip(14)))
        // 功能入口行
        column.addView(entryRow("方向盘按键学习") { showSteeringLearningDialog() }, block(top = dip(8)))

        // 运行日志：预览（对话框）+ 导出（文件快照）+ Gitee 上传配置/上传（M6k25）
        val logRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                TextView(activity).apply {
                    text = "运行日志"
                    setTextColor(Ui.TEXT)
                    textSize = 16f
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(smallOutlineButton("预览") { showLogPreview() }, sideMargin())
            addView(smallOutlineButton("导出") { exportLog() }, sideMargin())
            addView(smallOutlineButton("上传") { showGiteeUploadDialog() }.also { giteeUploadButton = it }, sideMargin())
        }
        column.addView(logRow, block(top = dip(14)))

        // 关于：M15 纪律，真实 versionName
        column.addView(divider(), block(top = dip(14)))
        column.addView(
            TextView(activity).apply {
                text = "关于"
                setTextColor(Ui.TEXT)
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
            },
            block(top = dip(12), bottom = dip(2)),
        )
        tvVersion = TextView(activity).apply {
            setTextColor(Ui.SUB)
            textSize = 13f
        }
        column.addView(tvVersion, block())
        return ScrollView(activity).apply {
            addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun buildHeader(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            ImageView(activity).apply {
                setImageDrawable(IconPainter.back(activity, 22, Ui.TEXT))
                setPadding(0, dip(8), dip(12), dip(8))
                setOnClickListener { activity.goHome() }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        addView(
            TextView(activity).apply {
                text = "设置"
                setTextColor(Ui.TEXT)
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
    }

    /** 音乐缓存毫秒应用：范围内才落盘（10-4000ms），越界忽略本次输入。 */
    private fun applyBuffer(input: EditText) {
        if (isLoading) return
        val value = input.text.toString().trim().toIntOrNull() ?: return
        if (value in LiteConfig.MIN_MEDIA_BUFFER_MILLIS..LiteConfig.MAX_MEDIA_BUFFER_MILLIS) {
            persistFromForm()
            LiteLog.log("ui", "设置：音乐缓存 ${value}ms")
        }
    }

    /**
     * 数值行：左标题 ｜ 右数值框（hint=允许范围，text=当前值回显）+ 应用按钮。
     */
    private fun buildValueRow(parent: LinearLayout, title: String, hint: String, onApply: (EditText) -> Unit): EditText {
        val input = EditText(activity).apply {
            background = Ui.fillInput(activity)
            setHintTextColor(Ui.SUB)
            setTextColor(Ui.TEXT)
            textSize = 13f
            setSingleLine()
            gravity = Gravity.CENTER
            this.hint = hint
            setPadding(dip(10), dip(7), dip(10), dip(7))
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                TextView(activity).apply {
                    text = title
                    setTextColor(Ui.TEXT)
                    textSize = 16f
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                input,
                LinearLayout.LayoutParams(dip(110), LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dip(10) },
            )
            addView(
                Button(activity).apply {
                    text = "应用"
                    textSize = 14f
                    minHeight = dip(38)
                    setAllCaps(false)
                    background = Ui.pillSolid(activity, Ui.PRIMARY)
                    setTextColor(Ui.PRIMARY_ON)
                    setPadding(dip(14), dip(5), dip(14), dip(5))
                    setOnClickListener { onApply(input) }
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
        }
        parent.addView(row, block(top = dip(4), bottom = dip(4)))
        return input
    }

    private fun radio(text: String): RadioButton = RadioButton(activity).apply {
        this.text = text
        setTextColor(Ui.TEXT)
        textSize = 15f
        setButtonDrawable(Ui.radio(activity, false))
        setPadding(dip(8), dip(4), dip(8), dip(4))
        // M6k19：自定义 buttonDrawable 偏小时文字基线与圈错位（8815 实测），强制垂直居中。
        gravity = Gravity.CENTER_VERTICAL
    }

    /** M6k21：「标签 · 当前值 [进入]」一行，进入弹 0~20 单选对话框（点选即试听）。 */
    private fun audioStreamRow(label: String, isMedia: Boolean): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            TextView(activity).apply {
                text = label
                setTextColor(Ui.TEXT)
                textSize = 16f
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        val value = TextView(activity).apply {
            setTextColor(Ui.SUB)
            textSize = 14f
        }
        if (isMedia) mediaStreamValueText = value else navStreamValueText = value
        addView(
            value,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dip(10) },
        )
        addView(
            Button(activity).apply {
                text = "进入"
                textSize = 14f
                minHeight = dip(38)
                setAllCaps(false)
                background = Ui.pillOutline(activity, Ui.OUTLINE)
                setTextColor(Ui.OUTLINE)
                setPadding(dip(16), dip(5), dip(16), dip(5))
                setOnClickListener { showAudioStreamDialog(label, isMedia) }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
    }

    private fun showAudioStreamDialog(label: String, isMedia: Boolean) {
        val current = if (isMedia) mediaAudioStreamValue else navAudioStreamValue
        val names = (0..20).map { if (it == 0) "0 · 自动路由" else it.toString() }.toTypedArray()
        val dialog = AlertDialog.Builder(activity)
            .setTitle(label)
            .setSingleChoiceItems(names, current) { _, which ->
                // 点选即试听：与实际播放同路径（0=usage，1~20=传统 stream type）
                AudioChannelPreview.play(activity, which, isMedia)
            }
            .setPositiveButton("保存") { d, _ ->
                val selected = (d as AlertDialog).listView.checkedItemPosition
                if (isMedia) mediaAudioStreamValue = selected else navAudioStreamValue = selected
                updateStreamValueTexts()
                persistFromForm()
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnDismissListener {
            AudioChannelPreview.close()
            AudioChannelPreview.currentActivity = null
        }
        dialog.show()
    }

    private fun updateStreamValueTexts() {
        mediaStreamValueText.text = "· $mediaAudioStreamValue"
        navStreamValueText.text = "· $navAudioStreamValue"
    }

    private fun checkbox(text: String): CheckBox = CheckBox(activity).apply {
        this.text = text
        setTextColor(Ui.TEXT)
        textSize = 15f
        setButtonDrawable(Ui.checkbox(activity, false))
        setPadding(dip(8), dip(4), dip(8), dip(4))
        setOnCheckedChangeListener { buttonView, _ ->
            buttonView.setButtonDrawable(Ui.checkbox(activity, buttonView.isChecked))
            if (!isLoading) persistFromForm()
        }
    }

    private fun entryRow(title: String, onClick: () -> Unit): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            TextView(activity).apply {
                text = title
                setTextColor(Ui.TEXT)
                textSize = 16f
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            Button(activity).apply {
                text = "进入"
                textSize = 14f
                minHeight = dip(38)
                setAllCaps(false)
                background = Ui.pillOutline(activity, Ui.OUTLINE)
                setTextColor(Ui.OUTLINE)
                setPadding(dip(18), dip(5), dip(18), dip(5))
                setOnClickListener { onClick() }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
    }

    private fun smallOutlineButton(text: String, onClick: () -> Unit): Button = Button(activity).apply {
        setText(text)
        textSize = 14f
        minHeight = dip(38)
        setAllCaps(false)
        background = Ui.pillOutline(activity, Ui.OUTLINE)
        setTextColor(Ui.OUTLINE)
        setPadding(dip(16), dip(5), dip(16), dip(5))
        setOnClickListener { onClick() }
    }

    private fun sideMargin(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dip(8) }

    private fun sectionLabel(text: String): TextView = TextView(activity).apply {
        this.text = text
        setTextColor(Ui.TEXT)
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun divider(): View = View(activity).apply { setBackgroundColor(Ui.LINE) }

    private fun block(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = top
            bottomMargin = bottom
        }

    private fun refreshRadioDrawables(group: RadioGroup) {
        for (i in 0 until group.childCount) {
            val rb = group.getChildAt(i) as RadioButton
            rb.setButtonDrawable(Ui.radio(activity, rb.isChecked))
        }
    }

    /** 三组单选全部刷新选中态染色（自绘 drawable 不感知 checked 状态变化，必须手动重设）。 */
    private fun refreshAllDrawables() {
        (rbOrientLand.parent as? RadioGroup)?.let { refreshRadioDrawables(it) }
        (rbAutoCar.parent as? RadioGroup)?.let { refreshRadioDrawables(it) }
        (rbAvFull.parent as? RadioGroup)?.let { refreshRadioDrawables(it) }
    }

    // ---------- 表单读写 ----------

    private fun loadIntoForm() {
        isLoading = true
        val config = LiteConfig.load(activity)
        inputScale.setText(config.videoScalePercent.toString())
        inputDuck.setText(config.navDuckPercent.toString())
        inputBuffer.setText(config.mediaBufferMillis.toString())
        when (config.activeChannel) {
            "relay" -> rbAutoRelay.isChecked = true
            "usb" -> rbAutoUsb.isChecked = true
            else -> rbAutoCar.isChecked = true
        }
        when (config.orientationMode) {
            // M6k24：auto 已取消，历史值归一显示为强制横屏。
            "portrait" -> rbOrientPortrait.isChecked = true
            else -> rbOrientLand.isChecked = true
        }
        rbAvFull.isChecked = config.avPathMode != "bt_audio"
        rbAvBt.isChecked = config.avPathMode == "bt_audio"
        mediaAudioStreamValue = config.mediaAudioStream
        navAudioStreamValue = config.navAudioStream
        updateStreamValueTexts()
        cbBoot.isChecked = config.bootAutostart
        cbBootBluetooth.isChecked = config.bootAutoBluetooth
        cbBootHotspot.isChecked = config.bootAutoHotspot
        cbAutoConnect.isChecked = config.autoConnect
        cbSoftDecode.isChecked = config.softwareVideoDecoder
        cbFps30.isChecked = config.limit30Fps
        cbSpeaker.isChecked = config.speakerForCalls
        isLoading = false
    }

    /** 全量读控件合并落盘（copy 保底；信道/加密归热点配置页，这里不触碰）。 */
    private fun persistFromForm() {
        val current = LiteConfig.load(activity)
        val scale = inputScale.text.toString().trim().toIntOrNull()
            ?.coerceIn(LiteConfig.MIN_SCALE_PERCENT, LiteConfig.MAX_SCALE_PERCENT)
            ?: current.videoScalePercent
        val duck = inputDuck.text.toString().trim().toIntOrNull()
            ?.coerceIn(LiteConfig.MIN_DUCK_PERCENT, LiteConfig.MAX_DUCK_PERCENT)
            ?: current.navDuckPercent
        val buffer = inputBuffer.text.toString().trim().toIntOrNull()
            ?.coerceIn(LiteConfig.MIN_MEDIA_BUFFER_MILLIS, LiteConfig.MAX_MEDIA_BUFFER_MILLIS)
            ?: current.mediaBufferMillis
        LiteConfig.save(
            activity,
            current.copy(
                videoScalePercent = scale,
                navDuckPercent = duck,
                mediaBufferMillis = buffer,
                activeChannel = when {
                    rbAutoRelay.isChecked -> "relay"
                    rbAutoUsb.isChecked -> "usb"
                    else -> "car"
                },
                limit30Fps = cbFps30.isChecked,
                speakerForCalls = cbSpeaker.isChecked,
                softwareVideoDecoder = cbSoftDecode.isChecked,
                orientationMode = currentOrientationMode(),
                avPathMode = if (rbAvBt.isChecked) "bt_audio" else "full",
                mediaAudioStream = mediaAudioStreamValue,
                navAudioStream = navAudioStreamValue,
                bootAutostart = cbBoot.isChecked,
                bootAutoBluetooth = cbBootBluetooth.isChecked,
                bootAutoHotspot = cbBootHotspot.isChecked,
                autoConnect = cbAutoConnect.isChecked,
            ),
        )
    }

    private fun currentOrientationMode(): String = when {
        rbOrientPortrait.isChecked -> "portrait"
        else -> "land"
    }

    /** 分辨率显式提交：10-150 钳位；与当前值不同时弹确认（含 >100 超采样警告），确认后重启软件生效。 */
    private fun applyScale(input: EditText) {
        val raw = input.text.toString().trim().toIntOrNull()
        if (raw == null) {
            toast("请先输入数字")
            return
        }
        val clamped = raw.coerceIn(LiteConfig.MIN_SCALE_PERCENT, LiteConfig.MAX_SCALE_PERCENT)
        val current = LiteConfig.load(activity).videoScalePercent
        input.setText(clamped.toString())
        if (clamped == current) {
            toast("分辨率未变化：$clamped%")
            return
        }
        val message = buildString {
            append("原分辨率 $current%，确认修改为 $clamped% 吗？\n\n")
            if (clamped > 100) {
                append("注意：高于 100% 会放大视频画面（超采样），弱硬件可能卡顿发热。\n\n")
            }
            append("点击「应用」后将自动重启软件，重启后应用新的分辨率；点击主页连接即按新参数连接。")
        }
        AlertDialog.Builder(activity)
            .setTitle(if (clamped > 100) "放大超采样确认" else "确认修改分辨率")
            .setMessage(message)
            .setNegativeButton("取消") { _, _ -> input.setText(current.toString()) }
            .setPositiveButton("应用并重启") { _, _ ->
                persistFromForm()
                toast("分辨率已应用：$clamped%，软件即将重启")
                activity.restartAppForVideoChange()
            }
            .show()
    }

    /** 压低音量显式提交：30-100 钳位，100 = 不压低。 */
    private fun applyDuck(input: EditText) {
        val raw = input.text.toString().trim().toIntOrNull()
        if (raw == null) {
            toast("请先输入数字")
            return
        }
        val clamped = raw.coerceIn(LiteConfig.MIN_DUCK_PERCENT, LiteConfig.MAX_DUCK_PERCENT)
        input.setText(clamped.toString())
        persistFromForm()
        toast(if (clamped == raw) "音量降低已应用：$clamped%" else "已钳到 $clamped%")
    }

    // ---------- 运行日志 ----------

    /** 预览：内存环形缓冲尾部（长按正文清空由对话框按钮承担）。 */
    private fun showLogPreview() {
        val sb = StringBuilder()
        LiteLog.currentFile()?.let { sb.append("日志文件: ").append(it.absolutePath).append("\n\n") }
        for (line in LiteLog.snapshot()) sb.append(line).append('\n')
        val body = TextView(activity).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Ui.TEXT)
            text = sb.toString()
            setPadding(dip(12), dip(8), dip(12), dip(8))
        }
        val scroll = ScrollView(activity).apply { addView(body) }
        AlertDialog.Builder(activity)
            .setTitle("运行日志")
            .setView(scroll)
            .setNeutralButton("清空") { _, _ -> LiteLog.clear() }
            .setPositiveButton("关闭", null)
            .show()
    }

    /** 导出当前日志到同目录下的新文件（带时间戳）；避免 MTP/文件管理器对同名文件的缓存。 */
    private fun exportLog() {
        if (LiteLog.directory() == null) {
            toast("导出失败：日志未挂载")
            return
        }
        val exported = LiteLog.exportSnapshot()
        if (exported == null) {
            toast("导出失败：日志为空")
            return
        }
        LiteLog.log("ui", "日志已导出：${exported.name}")
        toast("已导出：${exported.name}")
    }

    // ---------- M6k25：运行日志上传到 Gitee（无自有服务器，用户给的固定仓库+令牌） ----------

    private var giteeUploadButton: Button? = null
    @Volatile private var giteeUploading = false

    private fun giteeLabel(text: String): TextView = TextView(activity).apply {
        this.text = text
        setTextColor(Ui.SUB)
        textSize = 12f
    }

    private fun giteeInput(text: String, password: Boolean): EditText = EditText(activity).apply {
        background = Ui.fillInput(activity)
        setHintTextColor(Ui.SUB)
        setTextColor(Ui.TEXT)
        textSize = 13f
        setSingleLine()
        setPadding(dip(10), dip(8), dip(10), dip(8))
        if (password) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        setText(text)
    }

    /** 上传弹窗：用户填问题描述（必填）。 */
    private fun showGiteeUploadDialog() {
        if (giteeUploading) return
        val descInput = EditText(activity).apply {
            background = Ui.fillInput(activity)
            setHintTextColor(Ui.SUB)
            setTextColor(Ui.TEXT)
            textSize = 13f
            gravity = Gravity.TOP
            minLines = 3
            hint = "请描述遇到的问题（必填），例如：连接后闪退 / 画面卡顿…"
            setPadding(dip(10), dip(8), dip(10), dip(8))
        }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dip(20), dip(12), dip(20), dip(4))
            addView(giteeLabel("问题描述（必填，将随日志一起上传）"))
            addView(descInput, block(top = dip(6)))
        }
        AlertDialog.Builder(activity)
            .setTitle("上传运行日志")
            .setView(column)
            .setNegativeButton("取消", null)
            .setPositiveButton("上传") { _, _ ->
                val desc = descInput.text.toString().trim()
                if (desc.isEmpty()) {
                    toast("请先填写问题描述")
                    return@setPositiveButton
                }
                uploadLogToGitee(desc)
            }
            .show()
    }

    /** 组装日志文本 → 本地落盘重命名副本 → worker 线程上传 Gitee。 */
    private fun uploadLogToGitee(desc: String) {
        if (giteeUploading) return
        val logFile = LiteLog.currentFile()
        if (logFile == null || !logFile.isFile || logFile.length() == 0L) {
            toast("上传失败：日志为空")
            return
        }
        val versionName = try {
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
        } catch (_: Exception) {
            "unknown"
        }
        val systemVersion = "Android${Build.VERSION.RELEASE}-api${Build.VERSION.SDK_INT}"
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        // 文件名 = 年月日时间 + 系统版本 + 软件版本（去除文件系统不友好字符）
        val safeModel = Build.MODEL.trim().replace(Regex("[^0-9A-Za-z._-]"), "-")
        val fileName =
            "diplay-$stamp-$systemVersion-v$versionName-$safeModel.txt"
                .replace(Regex("[\\s]+"), "")
        val maxBytes = 800 * 1024
        val raw = logFile.readBytes()
        val logPart = if (raw.size > maxBytes) raw.copyOfRange(raw.size - maxBytes, raw.size) else raw
        val header = buildString {
            append("问题描述：").append(desc).append("\n\n")
            append("设备：").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n")
            val metrics = android.content.res.Resources.getSystem().displayMetrics
            append("分辨率：").append(metrics.widthPixels).append("x").append(metrics.heightPixels)
                .append(" density=").append(metrics.density).append("\n")
            append("系统版本：").append(systemVersion).append("（release ").append(Build.VERSION.RELEASE).append("）\n")
            append("软件版本：").append(versionName).append("\n")
            append("上传时间：").append(stamp).append("\n")
            append("──── 运行日志 ────\n")
        }
        // 先在本地生成重命名副本（同一目录），网络不通时用户可从这里取
        val localCopy = File(logFile.parentFile, fileName)
        try {
            localCopy.outputStream().use { it.write(header.toByteArray(Charsets.UTF_8)); it.write(logPart) }
        } catch (error: Exception) {
            LiteLog.log("log", "Gitee 上传：本地副本写入失败 ${error.message.orEmpty()}")
        }
        giteeUploading = true
        giteeUploadButton?.isEnabled = false
        Thread({
            val outcome = try {
                val headerEncoded = Base64.encodeToString(
                    header.toByteArray(Charsets.UTF_8) + logPart,
                    Base64.NO_WRAP,
                )
                uploadLogToGitee(fileName, stamp, headerEncoded)
                // 成功
                "上传成功"
            } catch (error: UploadException) {
                LiteLog.log("log", "Gitee 上传失败：HTTP ${error.code} ${error.sanitized}")
                "上传失败：网络不通或服务异常（HTTP ${error.code}）。\n已生成「${fileName}」，可在 DiPlayLite 目录获取后手动发送。"
            } catch (error: Exception) {
                LiteLog.log("log", "Gitee 上传异常：${error.javaClass.simpleName} ${error.message.orEmpty()}")
                "上传失败：网络不通。\n已生成「${fileName}」，可在 DiPlayLite 目录获取后手动发送。"
            }
            activity.runOnUiThread {
                giteeUploading = false
                giteeUploadButton?.isEnabled = true
                toast(outcome)
            }
        }, "diplay-gitee-upload").start()
    }

    private class UploadException(val code: Int, val sanitized: String) : Exception("HTTP $code")

    /** 上传到固定仓库（worker 线程调用）；同名冲突加 -1 后缀重试一次，失败抛 UploadException。 */
    private fun uploadLogToGitee(name: String, stamp: String, encoded: String) {
        val owner = "wangliunian"
        val repo = "display--log-collection"
        val token = "27a79346e0946691779eaee5647d86e4"
        var attemptName = name
        var lastCode = 0
        var lastBody = ""
        for (attempt in 0..1) {
            val (code, body) = postGiteeContents(owner, repo, token, attemptName, stamp, encoded)
            lastCode = code
            lastBody = body.replace(token, "***").let { if (it.length > 1200) it.substring(0, 1200) + "…" else it }
            if (code in 200..299) {
                val path = runCatching {
                    JSONObject(body).optJSONObject("content")?.optString("path").orEmpty()
                }.getOrDefault("")
                LiteLog.log("log", "Gitee 上传成功：${path.ifEmpty { attemptName }}")
                return
            }
            if ((code == 409 || body.contains("already exists", ignoreCase = true)) && attempt == 0) {
                attemptName = attemptName.removeSuffix(".txt") + "-1.txt"
                continue
            }
            throw UploadException(code, lastBody)
        }
        throw UploadException(lastCode, lastBody)
    }

    /** POST Gitee contents API（worker 线程调用）：15s 超时，成功 2xx 且响应含 content.path。 */
    private fun postGiteeContents(
        owner: String,
        repo: String,
        token: String,
        name: String,
        stamp: String,
        encoded: String,
    ): Pair<Int, String> {
        val url = URL(
            "https://gitee.com/api/v5/repos/$owner/$repo/contents/" + URLEncoder.encode(name, "UTF-8") +
                "?access_token=" + URLEncoder.encode(token, "UTF-8"),
        )
        val body = JSONObject()
            .put("content", encoded)
            .put("branch", "master")
            .put("message", "DiPlay Lite 运行日志 $stamp")
            .toString()
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json;charset=UTF-8")
            enableTls12OnKitKat(connection)
            connection.outputStream.use { out -> out.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to text
        } finally {
            connection.disconnect()
        }
    }

    /** Android 4.x（API16-20）默认不启用 TLSv1.2，Gitee 需要：显式换 SSLContext 兜底。 */
    private fun enableTls12OnKitKat(connection: HttpURLConnection) {
        if (Build.VERSION.SDK_INT > 20 || connection !is HttpsURLConnection) return
        try {
            val context = SSLContext.getInstance("TLSv1.2")
            context.init(null, null, null)
            connection.sslSocketFactory = context.socketFactory
        } catch (_: Exception) {
        }
    }

    // ---------- M6k25：方向盘按键学习向导（逐键学习 + 冲突覆盖 + 验证，替代旧的顺序流程） ----------

    private val steeringLearningOwner = Any()
    private var steeringWizardDialog: AlertDialog? = null
    private var steeringWizardBody: LinearLayout? = null
    /** home / listening / success / verify / verify_result */
    private var steeringWizardState = "home"
    private var steeringWizardOperation = ""
    /** 本地 KeyEvent 捕获钩子（仅监听/验证态非空），MainActivity.onKeyDown 经 consumeKeyDown 消费 */
    private var steeringLocalKeyHook: ((keyCode: Int, action: Int) -> Unit)? = null
    // 本地 KeyEvent 学习范围：媒体 播放/暂停(86)/播放(126)/暂停(127)/下一曲(87)/上一曲(88)
    // + 语音助手（API21 常量，编译期内联，运行时不涉新 API）
    private val steeringLocalLearnableKeys = setOf(
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_VOICE_ASSIST,
    )
    private val steeringOpLabels = listOf(
        "play_pause" to "播放/暂停",
        "previous" to "上一曲",
        "next" to "下一曲",
        "siri" to "语音助手(Siri)",
    )

    private fun steeringLabelOf(operation: String): String =
        steeringOpLabels.firstOrNull { it.first == operation }?.second ?: operation

    /** 已绑定键位的来源/键值摘要（首页状态展示用）。 */
    private fun steeringBindingSummary(binding: SteeringBinding): String = when (binding.source) {
        "key_event" -> "本地按键 · 键值 ${binding.keyCode}"
        "broadcast" -> "广播 ${binding.broadcastAction} · 键值 ${binding.keyCode}"
        "session" -> "媒体会话 · 键值 ${binding.keyCode}"
        else -> "日志 tag=${binding.logTag} · 键值 ${binding.keyCode}"
    }

    private fun showSteeringLearningDialog() {
        if (steeringWizardDialog != null) return
        // 向导打开期间整体抑制方控动作（避免误触发媒体操作），关闭时复位
        LiteSteeringKeys.setUiSuppressed(true)
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dip(20), dip(12), dip(20), dip(8))
        }
        steeringWizardBody = body
        val dialog = AlertDialog.Builder(activity)
            .setTitle("方向盘按键学习")
            .setView(body)
            .setNegativeButton("关闭", null)
            .create()
        dialog.setOnDismissListener {
            // 注销全部临时监听：本地按键钩子 + 学习管线（monitor/receiver 一并关闭）
            steeringWizardDialog = null
            steeringWizardBody = null
            steeringWizardState = "home"
            steeringLocalKeyHook = null
            LiteSteeringKeys.stopLearning(steeringLearningOwner)
            LiteSteeringKeys.setUiSuppressed(false)
        }
        steeringWizardDialog = dialog
        renderSteeringHome(body)
        dialog.show()
    }

    /** 首页：列出 4 个可学键位与当前绑定状态。 */
    private fun renderSteeringHome(body: LinearLayout) {
        if (steeringWizardBody !== body) return
        steeringWizardState = "home"
        steeringLocalKeyHook = null
        LiteSteeringKeys.stopLearning(steeringLearningOwner)
        body.removeAllViews()
        body.addView(
            TextView(activity).apply {
                text = "点按键位开始学习：进入监听后按一次方向盘按键即可。学习期间方控不会触发实际操作，保存后重新连接 CarPlay 生效。"
                setTextColor(Ui.SUB)
                textSize = 12f
            },
            block(bottom = dip(6)),
        )
        val bindings = SteeringStore.loadEnabled(activity)?.bindings.orEmpty()
        steeringOpLabels.forEachIndexed { index, (operation, label) ->
            val binding = bindings.firstOrNull { it.operation == operation }
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dip(10), 0, dip(10))
                setOnClickListener { beginSteeringListen(operation, label) }
                addView(
                    LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(
                            TextView(activity).apply {
                                text = label
                                setTextColor(Ui.TEXT)
                                textSize = 15f
                            },
                        )
                        addView(
                            TextView(activity).apply {
                                text = binding?.let { "已绑定 · ${steeringBindingSummary(it)}" } ?: "未学习"
                                setTextColor(Ui.SUB)
                                textSize = 12f
                            },
                        )
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(smallOutlineButton(if (binding == null) "学习" else "重学") { beginSteeringListen(operation, label) })
            }
            body.addView(row)
            if (index != steeringOpLabels.lastIndex) body.addView(divider(), block(bottom = dip(2)))
        }
        body.addView(
            smallOutlineButton("清除全部已学配置") { confirmClearSteeringConfig(body) },
            block(top = dip(14)),
        )
    }

    private fun confirmClearSteeringConfig(body: LinearLayout) {
        AlertDialog.Builder(activity)
            .setTitle("清除已学配置")
            .setMessage("将清除全部已学习的方控按键绑定，确定吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("清除") { _, _ ->
                SteeringStore.clear(activity)
                LiteSteeringKeys.reloadProfile(activity)
                LiteLog.log("steering", "方控配置已清除（学习向导）")
                toast("已清除")
                renderSteeringHome(body)
            }
            .show()
    }

    /** 监听态：SteeringKeyMonitor 管线 + 本地 KeyEvent 两路并行抓取，先到先得。 */
    private fun beginSteeringListen(operation: String, label: String) {
        if (steeringWizardBody == null || steeringWizardDialog == null) return
        steeringWizardState = "listening"
        steeringWizardOperation = operation
        val body = steeringWizardBody ?: return
        body.removeAllViews()
        body.addView(
            TextView(activity).apply {
                text = "监听中，请按方向盘上的按键…\n\n正在学习：【$label】\n25 秒内按下即可；也可按本机的媒体键/语音键学习。"
                setTextColor(Ui.TEXT)
                textSize = 14f
            },
            block(bottom = dip(12)),
        )
        body.addView(smallOutlineButton("取消") { renderSteeringHome(body) })
        // 路径 b：本地 KeyEvent（MainActivity.onKeyDown 捕获 ACTION_DOWN）
        steeringLocalKeyHook = { keyCode, action ->
            if (action == KeyEvent.ACTION_DOWN) {
                onSteeringCaptured(SteeringObservedKey(keyCode, action, "key_event"))
            }
        }
        // 路径 a：车机按键管线（logcat/广播/MediaSession 观测，DiPlay- 自环 tag 已在管线内排除）
        LiteSteeringKeys.startLearning(activity, steeringLearningOwner) { key ->
            activity.runOnUiThread {
                if (steeringWizardState != "listening" || steeringWizardDialog == null) return@runOnUiThread
                if (key == null) {
                    toast("学习超时，请重试")
                    renderSteeringHome(body)
                    return@runOnUiThread
                }
                onSteeringCaptured(key)
            }
        }
    }

    /** 捕获到按键（两路先到先得）；keyCode=0 是广播订阅提示不算按键。 */
    private fun onSteeringCaptured(key: SteeringObservedKey) {
        if (key.keyCode == 0) return
        if (steeringWizardState != "listening") return
        val body = steeringWizardBody ?: return
        val operation = steeringWizardOperation
        LiteSteeringKeys.stopLearning(steeringLearningOwner)
        steeringLocalKeyHook = null
        steeringWizardState = "success"
        val candidate = SteeringBinding(
            operation, key.keyCode, key.event, key.source,
            key.logTag, key.broadcastAction, key.keyExtra, key.eventExtra, key.logContains,
        )
        val conflict = SteeringStore.loadEnabled(activity)?.bindings
            ?.firstOrNull { it.inputId == candidate.inputId && it.operation != operation }
        if (conflict != null) {
            renderSteeringHome(body)
            AlertDialog.Builder(activity)
                .setTitle("按键冲突")
                .setMessage("该按键已绑定到【${steeringLabelOf(conflict.operation)}】，覆盖吗？")
                .setNegativeButton("取消") { _, _ -> beginSteeringListen(operation, steeringLabelOf(operation)) }
                .setPositiveButton("覆盖") { _, _ ->
                    applySteeringBinding(candidate, conflict.operation)
                    showSteeringSuccess(operation)
                }
                .show()
            return
        }
        applySteeringBinding(candidate, operation)
        showSteeringSuccess(operation)
    }

    /** 落盘：替换同键位旧绑定（冲突覆盖时另删被冲突键位），JSON 结构与上游同构。 */
    private fun applySteeringBinding(candidate: SteeringBinding, removeOperation: String) {
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

    private fun showSteeringSuccess(operation: String) {
        val body = steeringWizardBody ?: return
        if (steeringWizardDialog == null) return
        steeringWizardState = "success"
        steeringWizardOperation = operation
        val label = steeringLabelOf(operation)
        body.removeAllViews()
        body.addView(
            TextView(activity).apply {
                text = "学习成功：$label"
                setTextColor(Ui.TEXT)
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
            },
            block(bottom = dip(6)),
        )
        body.addView(
            TextView(activity).apply {
                text = "建议再按一次同一物理键验证能否命中；验证不通过也可重新学习或直接返回列表。"
                setTextColor(Ui.SUB)
                textSize = 12f
            },
            block(bottom = dip(12)),
        )
        body.addView(smallOutlineButton("再试一次验证") { beginSteeringVerify(operation) }, block(bottom = dip(8)))
        body.addView(smallOutlineButton("返回列表") { renderSteeringHome(body) })
    }

    /** 验证态：再按一次同一物理键，回显命中结果。 */
    private fun beginSteeringVerify(operation: String) {
        if (steeringWizardBody == null || steeringWizardDialog == null) return
        steeringWizardState = "verify"
        steeringWizardOperation = operation
        val body = steeringWizardBody ?: return
        val label = steeringLabelOf(operation)
        body.removeAllViews()
        body.addView(
            TextView(activity).apply {
                text = "验证中：请再按一次【$label】对应的按键…\n（25 秒内）"
                setTextColor(Ui.TEXT)
                textSize = 14f
            },
            block(bottom = dip(12)),
        )
        body.addView(smallOutlineButton("取消") { showSteeringSuccess(operation) })
        steeringLocalKeyHook = { keyCode, action ->
            if (action == KeyEvent.ACTION_DOWN) onSteeringVerified(keyCode, "key_event")
        }
        LiteSteeringKeys.startLearning(activity, steeringLearningOwner) { key ->
            activity.runOnUiThread {
                if (steeringWizardState != "verify" || steeringWizardDialog == null) return@runOnUiThread
                if (key == null) {
                    toast("验证超时，请重试")
                    showSteeringSuccess(operation)
                    return@runOnUiThread
                }
                if (key.keyCode != 0) onSteeringVerified(key.keyCode, key.source)
            }
        }
    }

    private fun onSteeringVerified(keyCode: Int, source: String) {
        if (steeringWizardState != "verify") return
        val body = steeringWizardBody ?: return
        val operation = steeringWizardOperation
        LiteSteeringKeys.stopLearning(steeringLearningOwner)
        steeringLocalKeyHook = null
        steeringWizardState = "verify_result"
        val label = steeringLabelOf(operation)
        val binding = SteeringStore.loadEnabled(activity)?.bindings?.firstOrNull { it.operation == operation }
        val hit = binding != null && binding.keyCode == keyCode
        body.removeAllViews()
        if (hit) {
            LiteLog.log("steering", "学习验证通过 op=$operation key=$keyCode source=$source")
            body.addView(
                TextView(activity).apply {
                    text = "验证通过：$label\n\n命中键值 $keyCode"
                    setTextColor(Ui.TEXT)
                    textSize = 15f
                },
                block(bottom = dip(12)),
            )
            body.addView(smallOutlineButton("完成") { steeringWizardDialog?.dismiss() }, block(bottom = dip(8)))
            body.addView(smallOutlineButton("返回列表") { renderSteeringHome(body) })
        } else {
            body.addView(
                TextView(activity).apply {
                    text = "验证未命中：本次抓到键值 $keyCode，与已学的【$label】（键值 ${binding?.keyCode ?: "-"}）不一致。\n\n请确认按的是同一个物理键，或重新学习。"
                    setTextColor(Ui.TEXT)
                    textSize = 14f
                },
                block(bottom = dip(12)),
            )
            body.addView(smallOutlineButton("再试一次验证") { beginSteeringVerify(operation) }, block(bottom = dip(8)))
            body.addView(smallOutlineButton("返回列表") { renderSteeringHome(body) })
        }
    }

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }
}
