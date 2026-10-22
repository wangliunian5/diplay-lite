package com.shilapi.diplay.lite

import android.app.Activity
import android.app.Dialog
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.widget.Button
import android.view.ViewGroup
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
import com.shilapi.xcertplay.transport.PcapTap
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
    // vc161：改 WrapRadioGroup——竖屏下四个选项挤一行会错位（RadioGroup 只能单行）
    private lateinit var rgAutoChannel: WrapRadioGroup
    private lateinit var rbAutoCar: RadioButton
    private lateinit var rbAutoRelay: RadioButton
    // vc158：自动连接方式第四项（Wi-Fi Direct）。
    private lateinit var rbAutoP2p: RadioButton
    private lateinit var rbAutoUsb: RadioButton
    private lateinit var rgOrientation: RadioGroup
    private lateinit var rbOrientLand: RadioButton
    private lateinit var rbOrientPortrait: RadioButton
    private lateinit var rbAvFull: RadioButton
    private lateinit var rbAvBt: RadioButton
    // 1.0.29：分辨率设定（fit/stretch/manual 三选一 + 手动宽高输入行）。
    private lateinit var rgResolution: RadioGroup
    private lateinit var rbResFit: RadioButton
    private lateinit var rbResStretch: RadioButton
    private lateinit var rbResManual: RadioButton
    private lateinit var applyResModeButton: android.widget.Button
    private lateinit var inputResW: EditText
    private lateinit var inputResH: EditText
    // M6k21：音频流编号当前值（弹窗选定时先存内存，点保存才落盘）
    private var mediaAudioStreamValue = 0
    private var navAudioStreamValue = 0
    private lateinit var mediaStreamValueText: TextView
    private lateinit var navStreamValueText: TextView
    private lateinit var cbBoot: CheckBox
    private lateinit var cbBootBluetooth: CheckBox
    private lateinit var cbBootHotspot: CheckBox
    private lateinit var cbBootWifi: CheckBox
    private lateinit var cbAutoConnect: CheckBox
    private lateinit var cbSoftDecode: CheckBox
    // vc157：帧率四档单选（25/30/40/60），替代原「限制 30 帧」复选框。
    private lateinit var rgFps: WrapRadioGroup
    private lateinit var rbFps25: RadioButton
    private lateinit var rbFps30: RadioButton
    private lateinit var rbFps40: RadioButton
    private lateinit var rbFps60: RadioButton
    private lateinit var cbSpeaker: CheckBox
    private lateinit var cbVoiceMedia: CheckBox
    // vc156（上游 0.2.21 预缓冲移植）：媒体预缓冲开关。
    private lateinit var cbPrebuffer: CheckBox
    private lateinit var tvVersion: TextView

    // loadIntoForm 期间静默：setChecked 会触发监听，避免半加载状态写盘
    private var isLoading = false

    /** 页面离开时调用（原 onPause + 向导 dismiss 复位，M6k25 收口为一个清理点）。 */
    fun cleanup() {
        runCatching { persistFromForm() }
        AudioChannelPreview.close()
        AudioChannelPreview.currentActivity = null
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

        // 1.1.1 UI：设置页四组分类 —— 连接设置 / 画面设置 / 音频设置 / 体验优化。

        // ── 连接设置：热点网络配置入口 + 打开软件时的默认连接通道 ──
        // 1.1.3（UI 字号调整）：分组标题 +1dp；入口行改名缩号（16f→13f）。
        column.addView(sectionLabel("连接设置"), block(top = dip(8), bottom = dip(4)))
        column.addView(entryRow("车机热点配置") { activity.openHotspotPage("car") }, block(top = dip(4)))
        column.addView(entryRow("中继热点配置") { activity.openHotspotPage("relay") }, block(top = dip(8)))
        // M6k19：默认自动连接方式（原三选一，从主页 toggle 迁移至此）。
        // 打开 App 时按此通道自动连接：car=车机热点 / relay=中继热点 / p2p=Wi-Fi Direct /
        // usb=USB（USB 通道待接入时仅记录）。
        // vc158：Wi-Fi Direct 由「体验优化里的承载方式开关」改为本组第四项，承载方式单选删除。
        // 1.1.3（UI 字号调整）：加回「自动连接方式」小标题，四个单选归属其下。
        column.addView(subSectionLabel("自动连接方式"), block(top = dip(8), bottom = dip(2)))
        // vc161：宽度不够时自动折行（竖屏四项一行放不下 → 折成两行）
        rgAutoChannel = WrapRadioGroup(activity)
        rbAutoCar = radio("车机热点")
        rbAutoRelay = radio("中继热点")
        rbAutoP2p = radio("Wi-Fi Direct")
        rbAutoUsb = radio("USB连接")
        rgAutoChannel.addButtons(rbAutoCar, rbAutoRelay, rbAutoP2p, rbAutoUsb)
        rgAutoChannel.setOnCheckedChangeListener {
            if (isLoading) return@setOnCheckedChangeListener
            refreshRadioDrawables(rgAutoChannel)
            persistFromForm()
        }
        column.addView(rgAutoChannel, block(top = dip(6)))

        // ── 画面设置：分辨率自定义百分比 / 分辨率设定 / 横竖屏切换 ──
        column.addView(sectionLabel("画面设置"), block(top = dip(16), bottom = dip(4)))
        inputScale = buildValueRow(column, "分辨率自定义", "${LiteConfig.MIN_SCALE_PERCENT}% - ${LiteConfig.MAX_SCALE_PERCENT}%") { applyScale(it) }
        // 1.0.29：分辨率设定 —— 保持纵横比（等比 letterbox）/ 拉伸（铺满）/
        // 手动设定分辨率（回报给 iPhone = 手动宽高 ×「分辨率自定义」百分比，重启后生效）。
        // 1.1.3（UI 字号调整）：加回「纵横比设定」小标题；三选改名；选定后弹窗确认、应用并重启。
        column.addView(subSectionLabel("纵横比设定"), block(top = dip(8), bottom = dip(2)))
        rgResolution = RadioGroup(activity).apply { orientation = LinearLayout.VERTICAL }
        rbResFit = radio("保持纵横比")
        rbResStretch = radio("拉伸")
        rbResManual = radio("手动设定分辨率")
        rgResolution.addView(rbResFit)
        rgResolution.addView(rbResStretch)
        rgResolution.addView(rbResManual)
        // 1.1.3(vc60 交互改版·8815 实测反馈)：三选项点选不再弹确认框——点选后右方出现
        // 「应用」按钮，点击才落盘并重启生效；不点应用即自动取消（选择不落盘）。
        rgResolution.setOnCheckedChangeListener { _, _ ->
            if (isLoading) return@setOnCheckedChangeListener
            refreshRadioDrawables(rgResolution)
            applyResModeButton.visibility =
                if (currentResolutionMode() != LiteConfig.load(activity).resolutionMode) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
        }
        val resModeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(
                rgResolution,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            applyResModeButton = Button(activity).apply {
                text = "应用"
                textSize = 12f
                minHeight = dip(34)
                setAllCaps(false)
                background = Ui.pillSolid(activity, Ui.PRIMARY)
                setTextColor(Ui.PRIMARY_ON)
                setPadding(dip(14), dip(5), dip(14), dip(5))
                visibility = View.GONE
                setOnClickListener {
                    persistFromForm()
                    // vc147：参数改为「下次连接生效」，不再重建界面（渲染端丢失竞态）。
                    // appliedVideoKey 由 MainActivity.confirmVideoChange 统一落成，
                    // 此处已 persistFromForm，无需重复保存。
                    toast("分辨率设定已保存，下次连接生效")
                }
            }
            val btnParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            btnParams.setMargins(dip(8), 0, 0, 0)
            addView(applyResModeButton, btnParams)
        }
        column.addView(resModeRow, block(top = dip(6)))
        // 手动输入行：横 [框] × 纵 [框] [应用]——恒显示（vc60 交互改版，不再随单选切换可见性）。
        // 局部变量构建，避免 lateinit 属性的 smart cast 限制。
        // 1.1.3（UI 字号调整）：样式与「分辨率自定义」一致——fillInput 输入框 + 主色 pill 应用键。
        val resRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dip(4), 0, 0)
        }
        fun manualLabel(text: String): TextView = TextView(activity).apply {
            this.text = text
            setTextColor(Ui.SUB)
            textSize = 13f
        }
        fun manualField(hint: String): EditText = EditText(activity).apply {
            background = Ui.fillInput(activity)
            setHintTextColor(Ui.SUB)
            setTextColor(Ui.TEXT)
            this.hint = hint
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 13f
            setSingleLine(true)
            gravity = Gravity.CENTER
            setPadding(dip(10), dip(7), dip(10), dip(7))
        }
        inputResW = manualField("宽 320-4096")
        inputResH = manualField("高 320-4096")
        resRow.addView(manualLabel("横"), rowStretch(0))
        resRow.addView(
            inputResW,
            LinearLayout.LayoutParams(dip(78), ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        resRow.addView(manualLabel("×"), rowStretch(0))
        resRow.addView(
            inputResH,
            LinearLayout.LayoutParams(dip(78), ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        val applyResButton = Button(activity).apply {
            text = "应用"
            textSize = 12f
            minHeight = dip(34)
            setAllCaps(false)
            background = Ui.pillSolid(activity, Ui.PRIMARY)
            setTextColor(Ui.PRIMARY_ON)
            setPadding(dip(14), dip(5), dip(14), dip(5))
            setOnClickListener { applyManualResolution() }
        }
        resRow.addView(applyResButton, rowStretch(dip(8)))
        column.addView(resRow, block())
        // 计算说明：与「分辨率自定义」百分比联动。
        column.addView(
            TextView(activity).apply {
                text = "回报给 iPhone 的实际分辨率 = 手动值 ×「分辨率自定义」百分比。重新连接后生效。"
                setTextColor(Ui.SUB)
                textSize = 12f
            },
            block(top = dip(2), bottom = dip(4)),
        )
        // 横竖屏切换（M6k24：自动旋转取消，只留强制横屏/强制竖屏；画面声明方向跟随此设置）
        // 1.1.3（UI 字号调整）：加回「画面方向」小标题。
        column.addView(subSectionLabel("画面方向"), block(top = dip(8), bottom = dip(2)))
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
            AppDialog.Builder(activity)
                .setTitle("应用横竖屏设置")
                .setMessage(
                    "原方向：${if (previous == "portrait") "强制竖屏" else "强制横屏"}，" +
                        "确认修改为${if (target == "portrait") "强制竖屏" else "强制横屏"}吗？\n\n" +
                        "点击「应用」后软件将自动重启，重启后按新方向全局生效。",
                )
                .setNegativeButton("取消") { _, _ ->
                    isLoading = true
                    if (previous == "portrait") rbOrientPortrait.isChecked = true else rbOrientLand.isChecked = true
                    refreshRadioDrawables(rgOrientation)
                    isLoading = false
                }
                .setPositiveButton("应用并重启") { _, _ ->
                    persistFromForm()
                    // vc159：方向是启动级全局属性（onCreate 读 config 决定 requestedOrientation），
                    // vc147 的「下次连接生效」对它不成立——改为真实冷重启，与文案对齐。
                    restartAppForOrientation()
                }
                .show()
        }
        column.addView(rgOrientation, block(top = dip(6)))

        // ── 画面帧率（vc158：由「体验优化」组移入本组，归属画面设置）──
        // 四档 25/30/40/60：25=弱机自救/省流档（降负载减轻老硬解噪点）；
        // 40/60=流畅优先（弱硬解机型高帧率高码率可能出噪块，自行降档）。
        // 直接落盘不弹确认——帧率已纳入 appliedVideoKey 机制，连接时检测到变化会二次确认。
        column.addView(subSectionLabel("画面帧率"), block(top = dip(8), bottom = dip(2)))
        // vc161：同 WrapRadioGroup（短标签多数情况仍是一行，窄屏自动折行）
        rgFps = WrapRadioGroup(activity)
        rbFps25 = radio("25帧")
        rbFps30 = radio("30帧")
        rbFps40 = radio("40帧")
        rbFps60 = radio("60帧")
        rgFps.addButtons(rbFps25, rbFps30, rbFps40, rbFps60)
        rgFps.setOnCheckedChangeListener {
            if (isLoading) return@setOnCheckedChangeListener
            refreshRadioDrawables(rgFps)
            runCatching { persistFromForm() }
            toast("已应用，下次连接生效")
        }
        column.addView(rgFps, block(top = dip(6)))

        // ── 音频设置：导航触发压低 / 音频缓存 / 音视频链路 / 音频流选择 ──
        column.addView(sectionLabel("音频设置"), block(top = dip(16), bottom = dip(4)))
        inputDuck = buildValueRow(column, "导航音频触发音量降低", "${LiteConfig.MIN_DUCK_PERCENT}% - ${LiteConfig.MAX_DUCK_PERCENT}%") { applyDuck(it) }
        // M6k19：音频缓存毫秒数（跟随导航音量降低之后），范围 10-4000ms。1.1.3 改名「音频缓存」。
        inputBuffer = buildValueRow(column, "音频缓存", "${LiteConfig.MIN_MEDIA_BUFFER_MILLIS} - ${LiteConfig.MAX_MEDIA_BUFFER_MILLIS} (ms)") { applyBuffer(it) }
        // 生效说明：起播阈值跟随设置值；缓冲池深度有 1500ms 下限（T29 防抖动），设置 >1300ms 才会加深。
        column.addView(
            TextView(activity).apply {
                text = "影响音乐起播速度。更改在重新连接后生效。缓冲池固定不低于 1500ms 防网络卡顿，设置高于 1300 才会加深缓冲池。"
                setTextColor(Ui.SUB)
                textSize = 12f
            },
            block(top = dip(2), bottom = dip(4)),
        )
        // vc156（移植自上游 0.2.21 BufferedAudioStream 预缓冲）：媒体预缓冲开关，默认开。
        // 开 = 音乐起播/复播水位抬到约 1s，Wi-Fi 短断流不再卡顿；代价是切歌起播稍慢。
        cbPrebuffer = checkbox("音乐预缓冲（抗Wi-Fi卡顿，起播稍慢）")
        column.addView(cbPrebuffer, block(bottom = dip(4)))
        // 音视频传输方式（1.1.3：加回小标题；二选一选定后弹窗确认、下次连接生效）
        column.addView(subSectionLabel("音视频传输方式"), block(top = dip(8), bottom = dip(2)))
        val avGroup = RadioGroup(activity).apply { orientation = LinearLayout.HORIZONTAL }
        rbAvFull = radio("全CarPlay")
        rbAvBt = radio("蓝牙音频+CarPlay")
        avGroup.addView(rbAvFull)
        avGroup.addView(rbAvBt)
        avGroup.setOnCheckedChangeListener { _, _ ->
            if (isLoading) return@setOnCheckedChangeListener
            refreshRadioDrawables(avGroup)
            val previous = LiteConfig.load(activity).avPathMode
            val target = if (rbAvBt.isChecked) "bt_audio" else "full"
            if (target == previous) return@setOnCheckedChangeListener
            AppDialog.Builder(activity)
                .setTitle("应用音视频传输方式")
                .setMessage(
                    if (target == "bt_audio") {
                        // 20261012 定案：iOS 16+ 苹果不提供「音频自动切蓝牙」协议通道（实测实锤：
                        // no_audio 声明拒建流 / keep_formats 卡 handoff 隧道死锁），本选项改为
                        // 引导用户手动切换，底层仍走全 CarPlay 推流（画面+音频通道保持完整）。
                        "蓝牙音频+CarPlay 的音频切换需在 iPhone 上手动完成（iOS 16 及以上）：\n\n" +
                            "1. 连接成功后打开 iPhone 控制中心；\n" +
                            "2. 长按音乐卡片，点击右上角 AirPlay 图标；\n" +
                            "3. 在输出列表中选择车机蓝牙名称。\n\n" +
                            "画面始终由 CarPlay 推流，音乐改走车机蓝牙出声。" +
                            "每次重连后如输出被系统改回，需重新选择一次（部分 iPhone 会记住该选择）。\n\n" +
                            "点击「应用」后保存，下次连接生效。"
                    } else {
                        "原方式：${if (previous == "bt_audio") "蓝牙音频+CarPlay" else "全CarPlay"}，" +
                            "确认修改为全CarPlay吗？\n\n" +
                            "点击「应用」后保存，下次连接生效。"
                    },
                )
                .setNegativeButton("取消") { _, _ ->
                    isLoading = true
                    if (previous == "bt_audio") rbAvBt.isChecked = true else rbAvFull.isChecked = true
                    refreshRadioDrawables(avGroup)
                    isLoading = false
                }
                .setPositiveButton("应用") { _, _ ->
                    persistFromForm()
                    // vc147：不再重建界面，参数下次连接生效（渲染端丢失竞态定案）。
                    toast("已应用，下次连接生效")
                }
                .show()
        }
        column.addView(avGroup, block(top = dip(6)))
        // 音道选择（M6k21，参考 fork carlito12345 版 0~20 音频流编号覆盖）：
        // 媒体/导航各自独立；0=自动路由（usage），1~20=传统音频流编号（含车机厂商扩展流）。
        // 点选数值即试听，保存后重新连接生效。
        // 1.1.3（UI 字号调整）：加回「音道选择」小标题；媒体/导航两行合并为一行。
        column.addView(subSectionLabel("音道选择"), block(top = dip(8), bottom = dip(2)))
        val channelRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        channelRow.addView(audioStreamRow("媒体音频流", isMedia = true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        channelRow.addView(audioStreamRow("导航音频流", isMedia = false), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(channelRow, block(top = dip(6)))
        column.addView(
            TextView(activity).apply {
                text = "0 表示自动路由。1~20 用于选择传统音频流编号，包括车机支持的厂商扩展输出。点选数值可试听。更改在重新连接后生效。"
                setTextColor(Ui.SUB)
                textSize = 12f
            },
            block(top = dip(2), bottom = dip(4)),
        )

        // 体验优化（1.1.3 UI 调整：主项 +1dp（15f→16f）；改名统一「开机自启X」句式；
        // Siri/语音提示走媒体音量 从音频设置移入本组）
        column.addView(sectionLabel("体验优化"), block(top = dip(12), bottom = dip(4)))
        cbBoot = checkbox("开机自启Diplay Lite")
        // 1.0.11：开机自动开启蓝牙/热点/Wi-Fi（独立于"开机自启"，任一勾选 BootReceiver 即工作；
        // 1.0.29 Wi-Fi 与热点互斥——勾了热点则 Wi-Fi 自启跳过，STA/AP 不能同开）
        cbBootBluetooth = checkbox("开机自启蓝牙")
        cbBootHotspot = checkbox("开机自启热点")
        cbBootWifi = checkbox("开机自启WIFI")
        cbAutoConnect = checkbox("启动后自动连接")
        cbSoftDecode = checkbox("强制软解")
        cbSpeaker = checkbox("通话声音走媒体音量")
        // 1.1.2（EU5 双向无声）：Siri/助手语音走媒体通道——ASSISTANT/NAVIGATION usage 在
        // PATEO 等老 HAL 上静音，勾选后改走媒体音量（媒体通道已实测出声）。
        cbVoiceMedia = checkbox("Siri/语音提示走媒体音量")
        // vc158：承载方式单选（车机热点 / Wi-Fi Direct）已删除——Wi-Fi Direct 改为
        // 「连接设置 → 自动连接方式」的第四项，语义不再重复。
        column.addView(cbBoot, block(bottom = dip(2)))
        column.addView(cbBootBluetooth, block(bottom = dip(2)))
        column.addView(cbBootHotspot, block(bottom = dip(2)))
        column.addView(cbBootWifi, block(bottom = dip(2)))
        column.addView(cbAutoConnect, block(bottom = dip(2)))
        column.addView(cbSoftDecode, block(bottom = dip(2)))
        column.addView(cbSpeaker, block(bottom = dip(2)))
        column.addView(cbVoiceMedia, block())

        // M6k23：自定义返回图标页（选图→裁剪→确认保存）。1.1.3：入口行 -3dp（按钮随行号缩小）。
        column.addView(entryRow("自定义返回图标") { activity.setPage("caricon") }, block(top = dip(14)))
        // 功能入口行（M6k26：方控学习改为独立页面跳转，替代原全屏 Dialog —— 部分 ROM Dialog 内容区不渲染）
        column.addView(entryRow("方向盘按键学习") { activity.setPage("steering") }, block(top = dip(8)))
        // 1.0.29 系统诊断：蓝牙栈状态/getprop/进程/root 探测（t3-p1 等无 ADB 车机的排查通道）
        // 1.1.4：重做为中文检测报告（逐项检测+连接建议矩阵），原始取证数据仅写日志不上屏；
        // 报告页「上传诊断日志」直接接设置页 Gitee 上传通道。
        column.addView(entryRow("系统诊断（蓝牙/WiFi/热点/USB）") {
            Diagnostics.run(activity) { showGiteeUploadDialog() }
        }, block(top = dip(8), bottom = dip(8)))

        // 运行日志：预览（对话框）+ 导出（文件快照）+ Gitee 上传配置/上传（M6k25）
        val logRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                TextView(activity).apply {
                    text = "运行日志"
                    setTextColor(Ui.TEXT)
                    // 1.1.3（UI 字号调整）：入口行属子项，-3dp（原 16f）。
                    textSize = 13f
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(smallOutlineButton("预览") { showLogPreview() }, sideMargin())
            addView(smallOutlineButton("导出") { exportLog() }, sideMargin())
            addView(smallOutlineButton("上传") { showGiteeUploadDialog() }.also { giteeUploadButton = it }, sideMargin())
        }
        column.addView(logRow, block(top = dip(14)))

        // fe6t1：数据流抓包（应用层 pcap，无 root）——NCM 桥入/出站以太网帧环形落盘
        //（3 文件 × 32MB 滚动覆盖），供 wedge/黑边等数据面问题取证。文件在
        // Android/data/<pkg>/files/pcap/，文件管理器可直接取出，Wireshark 打开即读。
        val pcapStatus = TextView(activity).apply {
            text = if (PcapTap.isActive()) "抓包中：${PcapTap.currentFile()}" else "未抓包"
            setTextColor(Ui.SUB)
            textSize = 11f
        }
        val pcapRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                TextView(activity).apply {
                    text = "数据流抓包（试验）"
                    setTextColor(Ui.TEXT)
                    textSize = 13f
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(smallOutlineButton("开始") {
                val dir = File(activity.getExternalFilesDir(null), "pcap")
                val path = PcapTap.start(dir)
                pcapStatus.text = "抓包中：$path"
                LiteLog.log("service", "pcap capture started: $path")
                Toast.makeText(activity, "开始抓包（NCM 入/出站），文件在 files/pcap/", Toast.LENGTH_SHORT).show()
            }, sideMargin())
            addView(smallOutlineButton("停止") {
                val s = PcapTap.stats()
                PcapTap.stop()
                pcapStatus.text = "已停止（$s）"
                LiteLog.log("service", "pcap capture stopped: $s")
                Toast.makeText(activity, "已停止抓包", Toast.LENGTH_SHORT).show()
            }, sideMargin())
        }
        column.addView(pcapRow, block(top = dip(14)))
        column.addView(pcapStatus, block(top = dip(2)))

        // 关于：M15 纪律，真实 versionName
        column.addView(divider(), block(top = dip(14)))
        column.addView(
            TextView(activity).apply {
                text = "关于"
                setTextColor(Ui.TEXT)
                // 1.1.3（UI 字号调整）：入口行属子项，-3dp（原 16f）。
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
            },
            block(top = dip(12), bottom = dip(2)),
        )
        tvVersion = TextView(activity).apply {
            setTextColor(Ui.SUB)
            textSize = 13f
        }
        column.addView(tvVersion, block())
        // 1.1.3（GPLv3 合规）：开源声明入口——上游署名 + 许可证 + 源码地址 + 商标免责。
        column.addView(
            TextView(activity).apply {
                text = "本软件基于开源项目 DiPlay（GPL-3.0）修改"
                setTextColor(Ui.SUB)
                textSize = 11f
            },
            block(top = dip(4)),
        )
        column.addView(
            TextView(activity).apply {
                text = "查看开源许可与声明 ▸"
                setTextColor(Ui.PRIMARY)
                textSize = 13f
                setOnClickListener { showLicenseNotice() }
            },
            block(top = dip(2)),
        )
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

    /**
     * 1.1.3（GPLv3 合规）：开源许可与声明弹窗——许可证、上游署名、商标免责、数据说明。
     * 内容与公开仓 README 同步维护：源码 https://github.com/wangliunian5/diplay-lite
     */
    private fun showLicenseNotice() {
        val body = TextView(activity).apply {
            setTextColor(Ui.TEXT)
            textSize = 12f
            setPadding(dip(20), dip(12), dip(20), dip(8))
            text = buildString {
                appendLine("【许可】")
                appendLine("本软件是自由软件，基于 GNU GPL-3.0 授权分发，不提供任何担保。")
                appendLine("你可以在 GPL-3.0 条款下自由使用、学习、修改和再分发本软件；再分发（含修改版）须同样以 GPL-3.0 提供完整对应源码。")
                appendLine()
                appendLine("【上游致谢】")
                appendLine("本软件基于开源项目 DiPlay（© shihabal3amri）修改：")
                appendLine("https://github.com/shihabal3amri/DiPlay")
                appendLine("本分支（DiPlay Lite）为社区维护的非官方版本，修改内容包括：老车机兼容适配（API16+）、设置界面重构、系统诊断取证增强、低配设备性能优化。")
                appendLine("本分支源码：")
                appendLine("https://github.com/wangliunian5/diplay-lite")
                appendLine()
                appendLine("【商标声明】")
                appendLine("应用内可选择的各汽车品牌标志（BEIJING、BMW、BYD、Ford、Honda、Hyundai、Nissan、Stellantis、Toyota、Volkswagen）版权归各自权利人，仅供车主个性化识别使用；本应用与上述各汽车厂家无关联，未获其授权或背书。")
                appendLine("CarPlay 为 Apple Inc. 的商标；本应用与 Apple Inc. 无关联，未经 MFi 认证。")
                appendLine()
                appendLine("【数据说明】")
                appendLine("运行日志仅在你主动点击「上传」时发送至维护者的日志仓库，用于问题排查；应用不会在后台收集或上传任何数据。")
            }
        }
        val scroll = ScrollView(activity).apply { addView(body) }
        AppDialog.Builder(activity)
            .setTitle("开源许可与声明")
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .show()
    }

    /** 音乐缓存毫秒应用：范围内才落盘（10-4000ms），越界忽略本次输入。 */
    private fun applyBuffer(input: EditText) {
        if (isLoading) return
        val value = input.text.toString().trim().toIntOrNull() ?: return
        if (value in LiteConfig.MIN_MEDIA_BUFFER_MILLIS..LiteConfig.MAX_MEDIA_BUFFER_MILLIS) {
            persistFromForm()
            LiteLog.log("ui", "设置：音乐缓存 ${value}ms")
            Toast.makeText(activity, "已保存：音乐缓存 ${value}ms，重新连接后生效", Toast.LENGTH_SHORT).show()
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
                    // 1.1.3（UI 字号调整）：数值行标题属子项，-3dp（原 16f）。
                    textSize = 13f
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
                    // 1.1.3：按钮跟随字号调整（14f → 12f）。
                    textSize = 12f
                    minHeight = dip(34)
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
        // 1.1.3（UI 字号调整）：所有单选均为「小项」，统一 13f（小标题归属层级）。
        textSize = 13f
        setButtonDrawable(Ui.radio(activity, false))
        setPadding(dip(8), dip(4), dip(8), dip(4))
        // M6k19：自定义 buttonDrawable 偏小时文字基线与圈错位（8815 实测），强制垂直居中。
        gravity = Gravity.CENTER_VERTICAL
    }

    /** M6k21：「标签 · 当前值 [进入]」，进入弹 0~20 单选对话框（点选即试听）。 */
    private fun audioStreamRow(label: String, isMedia: Boolean): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            TextView(activity).apply {
                text = label
                setTextColor(Ui.TEXT)
                // 1.1.3（UI 字号调整）：音道行属子项，-3dp（原 16f）。
                textSize = 13f
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        val value = TextView(activity).apply {
            setTextColor(Ui.SUB)
            textSize = 12f
        }
        if (isMedia) mediaStreamValueText = value else navStreamValueText = value
        addView(
            value,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dip(8) },
        )
        addView(
            Button(activity).apply {
                text = "进入"
                // 1.1.3：按钮跟随字号调整（14f → 12f）。
                textSize = 12f
                minHeight = dip(34)
                setAllCaps(false)
                background = Ui.pillOutline(activity, Ui.OUTLINE)
                setTextColor(Ui.OUTLINE)
                setPadding(dip(12), dip(4), dip(12), dip(4))
                setOnClickListener { showAudioStreamDialog(label, isMedia) }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
    }

    private fun showAudioStreamDialog(label: String, isMedia: Boolean) {
        val current = if (isMedia) mediaAudioStreamValue else navAudioStreamValue
        val names = (0..20).map { if (it == 0) "0 · 自动路由" else it.toString() }.toTypedArray()
        val dialog = AppDialog.Builder(activity)
            .setTitle(label)
            .setSingleChoiceItems(names, current) { _, which ->
                // 点选即试听：与实际播放同路径（0=usage，1~20=传统 stream type）
                AudioChannelPreview.play(activity, which, isMedia)
            }
            .setPositiveButton("保存") { d, _ ->
                val selected = d.getCheckedItemPosition()
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
        // 1.1.3（UI 字号调整）：复选框主项 16f；20261012 实机反馈——与「音频缓存」等数值行
        // 标题同 dp，统一 13f（竖屏小屏一行放得下）。
        textSize = 13f
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
                // 1.1.3（UI 字号调整）：入口行属子项，-3dp（原 16f）。
                textSize = 13f
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            Button(activity).apply {
                text = "进入"
                // 1.1.3：按钮跟随字号调整（14f → 12f）。
                textSize = 12f
                minHeight = dip(34)
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
        // 1.1.3：按钮跟随字号调整（14f → 12f）。
        textSize = 12f
        minHeight = dip(34)
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
        // 1.1.3（UI 字号调整）：分组大标题 +1dp（原 16f）。
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
    }

    /** 1.1.3（UI 字号调整）：小节标题（归属层）——加回「自动连接方式/纵横比设定/画面方向/音视频传输方式/音道选择」。 */
    private fun subSectionLabel(text: String): TextView = TextView(activity).apply {
        this.text = text
        setTextColor(Ui.SUB)
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun divider(): View = View(activity).apply { setBackgroundColor(Ui.LINE) }

    private fun block(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = top
            bottomMargin = bottom
        }

    /** 1.0.29：手动分辨率输入行的子项参数（WRAP 内容 + 左边距）。 */
    private fun rowStretch(left: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = left
        }

    /**
     * vc161：改递归遍历——WrapRadioGroup 是「多行容器」，单选按钮在行内而非直接子级，
     * 原单层遍历取不到（且会 ClassCastException）。对普通 RadioGroup 行为不变。
     */
    private fun refreshRadioDrawables(group: ViewGroup) {
        for (i in 0 until group.childCount) {
            when (val child = group.getChildAt(i)) {
                is RadioButton -> child.setButtonDrawable(Ui.radio(activity, child.isChecked))
                is ViewGroup -> refreshRadioDrawables(child)
            }
        }
    }

    /**
     * 各组单选全部刷新选中态染色（自绘 drawable 不感知 checked 状态变化，必须手动重设）。
     * vc161：改用容器字段而非 `.parent` —— WrapRadioGroup 的按钮挂在**行**上，
     * 取 parent 只能刷到一行，会漏掉折行后另一行的按钮。
     */
    private fun refreshAllDrawables() {
        refreshRadioDrawables(rgOrientation)
        refreshRadioDrawables(rgAutoChannel)
        // avGroup 是局部变量（非字段），且其仍是 RadioGroup、按钮为直接子级，用 parent 取
        (rbAvFull.parent as? ViewGroup)?.let { refreshRadioDrawables(it) }
        // 1.0.29：分辨率设定组。
        refreshRadioDrawables(rgResolution)
        // vc157：帧率四档组。
        refreshRadioDrawables(rgFps)
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
            "p2p" -> rbAutoP2p.isChecked = true
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
        // 1.1.3（bt_audio 试验）：长按「蓝牙音频+CarPlay」循环切换 /info 声明变体并立即落盘，
        // 无独立 UI。no_audio=现状（清音频位+省略音频声明键）；keep_formats=清位但保留声明键。
        rbAvBt.setOnLongClickListener {
            val next = if (LiteConfig.load(activity).btAudioInfoVariant == "keep_formats") "no_audio" else "keep_formats"
            LiteConfig.setBtAudioInfoVariant(activity, next)
            Toast.makeText(activity, "bt_audio /info 变体：$next（重开会话生效）", Toast.LENGTH_LONG).show()
            true
        }
        // 1.0.29：分辨率设定回显（vc60：手动行恒显示，只回显选中态与数值）。
        when (config.resolutionMode) {
            "stretch" -> rbResStretch.isChecked = true
            "manual" -> rbResManual.isChecked = true
            else -> rbResFit.isChecked = true
        }
        inputResW.setText(config.manualWidth.toString())
        inputResH.setText(config.manualHeight.toString())
        mediaAudioStreamValue = config.mediaAudioStream
        navAudioStreamValue = config.navAudioStream
        updateStreamValueTexts()
        cbBoot.isChecked = config.bootAutostart
        cbBootBluetooth.isChecked = config.bootAutoBluetooth
        cbBootHotspot.isChecked = config.bootAutoHotspot
        cbBootWifi.isChecked = config.bootAutoWifi
        cbAutoConnect.isChecked = config.autoConnect
        cbSoftDecode.isChecked = config.softwareVideoDecoder
        // vc157：帧率四档回显（load 已过网格校验，else 兜底 30）。
        when (config.videoFps) {
            25 -> rbFps25.isChecked = true
            40 -> rbFps40.isChecked = true
            60 -> rbFps60.isChecked = true
            else -> rbFps30.isChecked = true
        }
        cbSpeaker.isChecked = config.speakerForCalls
        cbVoiceMedia.isChecked = config.voiceRoutedToMedia
        cbPrebuffer.isChecked = config.audioPrebuffer
        isLoading = false
    }

    /**
     * vc159 引入，vc161 改实现：方向是**启动级全局属性**（onCreate 读 config 决定
     * requestedOrientation），vc147 的「下次连接生效」对它不成立，必须让 onCreate 重跑。
     *
     * ⚠️ 原实现（Launcher intent + `killProcess`）有两个问题，vc161 已改掉：
     * ① `startActivity` 是异步的，紧接着 `killProcess`，车机这类慢 AMS 上可能
     *    **intent 还没送达进程就被杀** → 表现为「点了应用没起来」；
     * ② `killProcess` **不走 onDestroy**，LiteSessionService 无法优雅拆栈
     *    （iPhone 收不到断开信号）、VPN 不解绑，只能靠下次启动 `ensureCleanVpnState()`
     *    自检兜底。
     *
     * 改 `recreate()`：同样完整重跑 onCreate（满足"启动级属性"要求），但走完整生命周期，
     * onDestroy 会正常清理会话与 VPN，且不杀进程。
     * LiteConfig.save 走 commit()，persistFromForm 完成即已落盘，重建不丢
     * （nova 3e「apply 未落盘」教训）。
     */
    private fun restartAppForOrientation() {
        activity.recreate()
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
        // 1.0.29：手动宽高非数字时保留原值（显式校验在 applyManualResolution 里做）。
        val manualW = inputResW.text.toString().trim().toIntOrNull()
            ?.coerceIn(LiteConfig.MIN_MANUAL_RESOLUTION, LiteConfig.MAX_MANUAL_RESOLUTION)
            ?: current.manualWidth
        val manualH = inputResH.text.toString().trim().toIntOrNull()
            ?.coerceIn(LiteConfig.MIN_MANUAL_RESOLUTION, LiteConfig.MAX_MANUAL_RESOLUTION)
            ?: current.manualHeight
        LiteConfig.save(
            activity,
            current.copy(
                videoScalePercent = scale,
                navDuckPercent = duck,
                mediaBufferMillis = buffer,
                resolutionMode = currentResolutionMode(),
                manualWidth = manualW,
                manualHeight = manualH,
                // vc158：新增 p2p（Wi-Fi Direct）通道选项。
                activeChannel = when {
                    rbAutoRelay.isChecked -> "relay"
                    rbAutoP2p.isChecked -> "p2p"
                    rbAutoUsb.isChecked -> "usb"
                    else -> "car"
                },
                // vc157：帧率四档落盘（脏值防线在 LiteConfig.load 的 sanitizeFps）。
                videoFps = when {
                    rbFps25.isChecked -> 25
                    rbFps40.isChecked -> 40
                    rbFps60.isChecked -> 60
                    else -> 30
                },
                speakerForCalls = cbSpeaker.isChecked,
                voiceRoutedToMedia = cbVoiceMedia.isChecked,
                audioPrebuffer = cbPrebuffer.isChecked,
                softwareVideoDecoder = cbSoftDecode.isChecked,
                orientationMode = currentOrientationMode(),
                avPathMode = if (rbAvBt.isChecked) "bt_audio" else "full",
                mediaAudioStream = mediaAudioStreamValue,
                navAudioStream = navAudioStreamValue,
                bootAutostart = cbBoot.isChecked,
                bootAutoBluetooth = cbBootBluetooth.isChecked,
                bootAutoHotspot = cbBootHotspot.isChecked,
                bootAutoWifi = cbBootWifi.isChecked,
                autoConnect = cbAutoConnect.isChecked,
            ),
        )
    }

    private fun currentOrientationMode(): String = when {
        rbOrientPortrait.isChecked -> "portrait"
        else -> "land"
    }

    /** 1.0.29：分辨率设定当前选项（fit=自动保持纵横比 / stretch=自动拉伸 / manual=手动）。 */
    private fun currentResolutionMode(): String = when {
        rbResStretch.isChecked -> "stretch"
        rbResManual.isChecked -> "manual"
        else -> "fit"
    }

    /** 1.0.29：手动分辨率显式提交：320-4096 整数校验，确认后与分辨率自定义同款流程（重启生效）。 */
    private fun applyManualResolution() {
        val w = inputResW.text.toString().trim().toIntOrNull()
        val h = inputResH.text.toString().trim().toIntOrNull()
        val range = "${LiteConfig.MIN_MANUAL_RESOLUTION} - ${LiteConfig.MAX_MANUAL_RESOLUTION}"
        if (w == null || h == null ||
            w < LiteConfig.MIN_MANUAL_RESOLUTION || w > LiteConfig.MAX_MANUAL_RESOLUTION ||
            h < LiteConfig.MIN_MANUAL_RESOLUTION || h > LiteConfig.MAX_MANUAL_RESOLUTION
        ) {
            toast("请输入 $range 的整数（横、纵各一个）")
            return
        }
        val current = LiteConfig.load(activity)
        if (w == current.manualWidth && h == current.manualHeight) {
            toast("分辨率未变化：${w}×$h")
            return
        }
        inputResW.setText(w.toString())
        inputResH.setText(h.toString())
        AppDialog.Builder(activity)
            .setTitle("确认修改分辨率")
            .setMessage(
                "原分辨率：${current.manualWidth}×${current.manualHeight}，确认修改为 ${w}×$h 吗？\n\n" +
                    "回报给 iPhone 的实际分辨率 = 该值 ×「分辨率自定义」百分比。\n\n" +
                    "点击「应用」后保存，下次连接生效。",
            )
            .setNegativeButton("取消") { _, _ ->
                inputResW.setText(current.manualWidth.toString())
                inputResH.setText(current.manualHeight.toString())
            }
            .setPositiveButton("应用") { _, _ ->
                persistFromForm()
                // vc147：不再重建界面，参数下次连接生效（渲染端丢失竞态定案）。
                toast("分辨率已保存：${w}×$h，下次连接生效")
            }
            .show()
    }

    /** 分辨率显式提交：10-150 钳位；与当前值不同时弹确认（含 >100 超采样警告），确认后下次连接生效。 */
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
            append("点击「应用」后保存，下次连接生效。")
        }
        AppDialog.Builder(activity)
            .setTitle(if (clamped > 100) "放大超采样确认" else "确认修改分辨率")
            .setMessage(message)
            .setNegativeButton("取消") { _, _ -> input.setText(current.toString()) }
            .setPositiveButton("应用") { _, _ ->
                persistFromForm()
                // vc147：不再重建界面，参数下次连接生效（渲染端丢失竞态定案）。
                toast("分辨率已保存：$clamped%，下次连接生效")
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
        AppDialog.Builder(activity)
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
        AppDialog.Builder(activity)
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
            // vc95：ROM 标识 + ROM 编译时间——同 Android 版本的白牌车机大量雷同（鼎微多台
            // 同为 4.4.2、FE-6 各车 5.1），仅凭 MODEL 无法定位到具体一台测试机；display.id
            // 缺失/雷同（generic）时回退 Build.ID，再以 ROM 编译时间兜底区分。
            val romDisplay = Build.DISPLAY?.trim().orEmpty().ifEmpty { Build.ID }
            append("ROM版本：").append(romDisplay)
                .append("（built ").append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(Build.TIME)))
                .append("）\n")
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
        // 1.1.3（20261012 轮换）：旧 token 27a79346… 已作废。
        // 脱敏（公开源码前置）：token 由 local.properties 经 BuildConfig.GITEE_LOG_TOKEN 注入，
        // 未配置时为空串——下方判空降级为「生成文件后手动发送」路径，不再硬编码进源码。
        val token = BuildConfig.GITEE_LOG_TOKEN
        if (token.isEmpty()) throw UploadException(0, "GITEE_LOG_TOKEN not configured")
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

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }
}
