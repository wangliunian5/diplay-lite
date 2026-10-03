package com.shilapi.diplay.lite

import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.shilapi.xcertplay.media.CarPlayTouchMapper

/**
 * DiPlay Lite 主界面：单屏程序化 UI（零 XML 资源、零 AndroidX、无图片）。
 *
 * M0 骨架阶段：热点表单 + 服务启停 + 状态/日志显示；
 * 连接编排（shared 模块 CarPlayController）在 M2 接入 LiteSessionService。
 * M4k：车机形态——默认全屏横屏，SurfaceView 铺满整屏做触摸目标；
 * 配置/日志面板作为浮层（连接成功后自动收起，左上角小按钮可随时唤回）。
 *
 * 状态刷新用 500ms 轮询（Handler），代替广播与生命周期库，保持零依赖。
 */
class MainActivity : Activity() {

    private val handler = Handler()
    private lateinit var statusDot: TextView
    private lateinit var statusLine: TextView
    private lateinit var logView: TextView
    private lateinit var ssidInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var channelInput: EditText
    private lateinit var securityInput: Spinner
    private lateinit var bluetoothInput: EditText
    private lateinit var fps30Check: CheckBox
    private lateinit var scaleInput: EditText
    private lateinit var speakerCallsCheck: CheckBox
    private lateinit var duckInput: EditText
    private lateinit var softDecodeCheck: CheckBox
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var video: SurfaceView
    private lateinit var panel: ScrollView
    private lateinit var panelToggle: Button
    private var panelVisible = true
    private var lastActive = false

    private val refreshTask = object : Runnable {
        override fun run() {
            renderState()
            if (!isFinishing) handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 车机形态：状态栏也收掉，投屏画面占满整块屏。
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
        )
        // M4l：车机屏绝不能休眠。屏幕一黑一亮，SurfaceView 的 Surface 就会销毁重建，
        // 而 API16 不支持 setOutputSurface（API23+），只能重建解码器 + 等下一个关键帧，
        // 期间收到的帧全部丢弃（实测 M4k 日志里 shown 从 17fps 掉到 2.8fps 就是这种窗口）。
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        loadIntoForm()
        setPanelVisible(true)
        renderState()
        LiteLog.log("ui", "DiPlay Lite M0 骨架就绪（minSdk 16）")
    }

    override fun onResume() {
        super.onResume()
        LiteLog.log("ui", "onResume")
        handler.post(refreshTask)
    }

    override fun onPause() {
        super.onPause()
        LiteLog.log("ui", "onPause")
        handler.removeCallbacks(refreshTask)
    }

    /**
     * M4l 诊断：Activity 重建本身会让 SurfaceView 的 Surface 销毁重建（见 onCreate 注释）。
     * 这条日志用来区分"只是 Surface 重建"（无 onDestroy）与"Activity 真的被销毁"（有 onDestroy）。
     */
    override fun onDestroy() {
        LiteLog.log("ui", "onDestroy（Activity 被销毁，下一次进入会重建 Surface 与解码器）")
        super.onDestroy()
    }

    // ---------- UI 构造（纯代码，无 XML） ----------

    private fun buildUi(): View {
        val pad = dip(12)
        // 根容器用 FrameLayout：SurfaceView 铺满整屏（车机画面），配置/日志面板浮在上面。
        val outer = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        // M3：CarPlay 画面镜像到该 SurfaceView；Surface 经 LiteVideoBridge 交给后台 MediaCodec。
        // M4k：铺满屏幕 + 接手势 —— CarPlay 的触摸全靠这个 View 转成 HID 报告发回 iPhone。
        video = SurfaceView(this)
        video.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                LiteVideoBridge.updateSurface(holder.surface)
                LiteLog.log("ui", "视频 Surface 就绪")
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                LiteVideoBridge.updateSurface(null)
                LiteLog.log("ui", "视频 Surface 销毁")
            }
        })
        video.setOnTouchListener { _, event -> forwardTouch(event) }
        outer.addView(
            video,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        panel = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(BG)
        }
        panel.addView(root)
        outer.addView(
            panel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        // 面板开关：无条件浮在最上层，面板收起后它是唯一能唤回配置/日志的入口。
        // 靠右上角，避开面板标题行的左上角。
        panelToggle = Button(this).apply {
            text = "面板"
            textSize = 12f
            alpha = 0.65f
            setOnClickListener { setPanelVisible(!panelVisible) }
        }
        val toggleParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END,
        )
        toggleParams.setMargins(0, dip(6), dip(6), 0)
        outer.addView(panelToggle, toggleParams)

        // 标题行：状态圆点 + 标题
        val title = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = TextView(this).apply {
            text = "●"
            textSize = 18f
            setTextColor(Color.GRAY)
        }
        title.addView(statusDot)
        title.addView(TextView(this).apply {
            text = "DiPlay Lite"
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(dip(8), 0, 0, 0)
        })
        root.addView(title)

        statusLine = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 14f
            setPadding(0, dip(4), 0, dip(8))
        }
        root.addView(statusLine)

        // 表单：SSID / 密码 / 信道 / 加密 / 蓝牙地址
        ssidInput = EditText(this).apply {
            hint = "车机热点 SSID"
            setSingleLine()
        }
        root.addView(fieldRow("热点 SSID", ssidInput))

        passwordInput = EditText(this).apply {
            hint = "热点密码"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(fieldRow("密码", passwordInput))

        channelInput = EditText(this).apply {
            hint = "留空自动实测（B3），失败请手填"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        root.addView(fieldRow("信道（1-165，2.4G/5G）", channelInput))

        securityInput = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                listOf("WPA2", "WPA2-WPA3", "WPA3", "OPEN"),
            ).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        }
        root.addView(fieldRow("加密方式", securityInput))

        bluetoothInput = EditText(this).apply {
            hint = "留空自动选择已配对 iPhone"
            setSingleLine()
        }
        root.addView(fieldRow("iPhone 蓝牙地址（可选）", bluetoothInput))

        // M6b 性能项：C8815 硬解 960x540 撑不到 60fps，这里允许把 /info 的 maxFPS 降到 30。
        fps30Check = CheckBox(this).apply {
            text = "限制视频帧率 30fps（省 CPU，动态画面略顿）"
            setTextColor(Color.LTGRAY)
            textSize = 13f
        }
        root.addView(fps30Check)

        // M6b 性能项：按百分比缩小 /info 声明的分辨率（长宽等比，各自取偶）。
        // 像素少 33~44% 是弱机最直接的省算力手段；渲染仍铺满物理屏，故画面无黑边。
        scaleInput = EditText(this).apply {
            hint = "50-100，100 = 原生分辨率"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        root.addView(fieldRow("分辨率百分比（50-100）", scaleInput))

        // M6c：CarPlay 通话声音路由。默认听筒（本机 telephony 流走 STREAM_VOICE_CALL）；
        // 勾上则通话期间切扬声器（MODE_IN_COMMUNICATION + setSpeakerphoneOn）。开关即时生效，无需重连。
        speakerCallsCheck = CheckBox(this).apply {
            text = "通话声音走扬声器（默认听筒）"
            setTextColor(Color.LTGRAY)
            textSize = 13f
            // 即时持久化：服务侧每次通话流开始时现读 LiteConfig，所以勾完直接打电话即可生效，无需重新连接。
            setOnCheckedChangeListener { _, _ -> saveFromForm() }
        }
        root.addView(speakerCallsCheck)

        // M6d：导航播报时把音乐压低（type=101 与音乐同走 STREAM_MUSIC，只能我们自己做 duck）。
        duckInput = EditText(this).apply {
            hint = "30-70，导航播报时音乐的音量百分比"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        root.addView(fieldRow("导航压低音乐到（30-70%）", duckInput))

        // M6e：强制软件解码视频。默认关（现状=厂商硬解）；给硬解异常的机型兜底，也用于二分定位。
        // 注意：8815（Cortex-A5、无 NEON）上软解会比硬解慢很多，帧率可能掉到个位数。
        softDecodeCheck = CheckBox(this).apply {
            text = "强制软件解码视频（默认关；弱机可能掉帧）"
            setTextColor(Color.LTGRAY)
            textSize = 13f
        }
        root.addView(softDecodeCheck)

        // 操作行：连接 / 断开
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dip(12), 0, dip(12))
        }
        connectButton = Button(this).apply {
            text = "连接"
            setOnClickListener { connect() }
        }
        disconnectButton = Button(this).apply {
            text = "断开"
            setOnClickListener { disconnect() }
        }
        actions.addView(connectButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(disconnectButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actions)

        // 导出日志：把当前日志复制到同目录下的新文件（带时间戳），避免同名文件"看不到更新"。
        val exportButton = Button(this).apply {
            text = "导出日志"
            setOnClickListener { exportLog() }
        }
        root.addView(exportButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // 日志区（长按清空）
        logView = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setTextColor(Color.rgb(170, 200, 170))
            setBackgroundColor(Color.rgb(10, 12, 18))
            setPadding(dip(8), dip(8), dip(8), dip(8))
            minHeight = dip(180)
            text = ""
            setOnLongClickListener {
                LiteLog.clear()
                true
            }
        }
        root.addView(logView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        return outer
    }

    private fun fieldRow(label: String, input: View): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dip(6), 0, dip(6))
            addView(TextView(this@MainActivity).apply {
                text = label
                setTextColor(Color.LTGRAY)
                textSize = 13f
                setPadding(0, 0, 0, dip(4))
            })
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun dip(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    /** 配置/日志面板的显隐：收起后整屏都是 CarPlay 画面与触摸区。 */
    private fun setPanelVisible(visible: Boolean) {
        panelVisible = visible
        panel.visibility = if (visible) View.VISIBLE else View.GONE
        panelToggle.text = if (visible) "隐藏" else "面板"
    }

    /**
     * 触摸转发：把屏幕坐标归一化成 0..1 的 [AirPlayContact]，交给当前会话发 HID 报告。
     * 未连接时返回 false（不消费事件）；尺寸未布局完时用屏幕尺寸兜底。
     */
    private fun forwardTouch(event: MotionEvent): Boolean {
        val service = LiteSessionService.current ?: return false
        val width = video.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val height = video.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        if (width <= 0 || height <= 0) return false
        return service.sendTouch(CarPlayTouchMapper.contacts(event, width, height))
    }

    // ---------- 表单读写 ----------

    private fun loadIntoForm() {
        val config = LiteConfig.load(this)
        ssidInput.setText(config.ssid)
        passwordInput.setText(config.password)
        channelInput.setText(if (config.channel > 0) config.channel.toString() else "")
        val securities = listOf("WPA2", "WPA2-WPA3", "WPA3", "OPEN")
        securityInput.setSelection(securities.indexOf(config.security).coerceAtLeast(0))
        bluetoothInput.setText(config.bluetoothAddress)
        fps30Check.isChecked = config.limit30Fps
        scaleInput.setText(config.videoScalePercent.toString())
        speakerCallsCheck.isChecked = config.speakerForCalls
        duckInput.setText(config.navDuckPercent.toString())
        softDecodeCheck.isChecked = config.softwareVideoDecoder
    }

    private fun saveFromForm(): LiteConfig {
        val channelText = channelInput.text.toString().trim()
        // 百分比非法（空/非数字/越界）一律钳到 50~100，避免把 0 或 999 写进 /info。
        val scale = scaleInput.text.toString().trim().toIntOrNull()
            ?.coerceIn(LiteConfig.MIN_SCALE_PERCENT, LiteConfig.MAX_SCALE_PERCENT)
            ?: LiteConfig.MAX_SCALE_PERCENT
        // 压低值同样钳到 30~70，避免把 0（静音）或 100（等于没压）写进去。
        val duck = duckInput.text.toString().trim().toIntOrNull()
            ?.coerceIn(LiteConfig.MIN_DUCK_PERCENT, LiteConfig.MAX_DUCK_PERCENT)
            ?: 50
        val config = LiteConfig(
            ssid = ssidInput.text.toString().trim(),
            password = passwordInput.text.toString(),
            channel = channelText.toIntOrNull() ?: 0,
            security = securityInput.selectedItem as? String ?: "WPA2",
            bluetoothAddress = bluetoothInput.text.toString().trim(),
            limit30Fps = fps30Check.isChecked,
            videoScalePercent = scale,
            speakerForCalls = speakerCallsCheck.isChecked,
            navDuckPercent = duck,
            softwareVideoDecoder = softDecodeCheck.isChecked,
        )
        LiteConfig.save(this, config)
        return config
    }

    // ---------- 动作 ----------

    private fun connect() {
        if (ssidInput.text.toString().trim().isEmpty()) {
            toast("请先填写车机热点 SSID")
            return
        }
        val config = saveFromForm()
        val key = LiteConfig.videoKeyOf(config.videoScalePercent, config.limit30Fps)
        // M6d：实测改分辨率后不重启+重连会出现渲染异常，所以这里拦一道二次确认。
        if (config.appliedVideoKey.isNotEmpty() && config.appliedVideoKey != key) {
            confirmVideoChange(key)
            return
        }
        startSession(config, key)
    }

    private fun startSession(config: LiteConfig, videoKey: String) {
        LiteConfig.saveAppliedVideoKey(this, videoKey)
        LiteLog.log(
            "ui",
            "发起连接：ssid=${config.ssid} channel=${config.channel} security=${config.security} " +
                "videoKey=$videoKey duck=${config.navDuckPercent}%",
        )
        startService(Intent(this, LiteSessionService::class.java))
        toast("已启动会话服务")
    }

    /**
     * M6d：分辨率/帧率变更的二次确认。确认后立刻断开当前连接并重启软件 ——
     * 用户实测：改了视频参数不重启会渲染异常（解码器尺寸与 Surface 对不上）。
     */
    private fun confirmVideoChange(videoKey: String) {
        AlertDialog.Builder(this)
            .setTitle("已修改分辨率/帧率")
            .setMessage(
                "视频参数改动必须断开当前连接并重启软件才能生效，否则画面会渲染异常。\n\n" +
                    "现在断开并重启吗？",
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("断开并重启") { _, _ ->
                LiteConfig.saveAppliedVideoKey(this, videoKey)
                disconnect()
                restartApp()
            }
            .show()
    }

    /** 4.1 上最稳的自重启：先用 AlarmManager 预约拉起自己，再杀掉当前进程。 */
    private fun restartApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT)
        (getSystemService(ALARM_SERVICE) as? AlarmManager)
            ?.set(AlarmManager.RTC, System.currentTimeMillis() + 400, pending)
        LiteLog.log("ui", "视频参数已改：断开连接并重启软件")
        finish()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun disconnect() {
        stopService(Intent(this, LiteSessionService::class.java))
        LiteLog.log("ui", "用户断开")
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

    // ---------- 状态渲染 ----------

    private fun renderState() {
        statusDot.setTextColor(
            when (LiteState.phase) {
                LiteState.Phase.ACTIVE -> Color.rgb(80, 220, 100)
                LiteState.Phase.CONNECTING -> Color.rgb(240, 200, 60)
                LiteState.Phase.FAILED -> Color.rgb(240, 80, 80)
                LiteState.Phase.IDLE -> Color.GRAY
            },
        )
        statusLine.text = LiteState.statusText
        // 连接成功即自动收起面板 —— 此时用户要看的是 CarPlay 画面，而不是配置表单。
        // 断开后（或用户点"面板"）再自动展开，方便改配置重连。
        val active = LiteState.phase == LiteState.Phase.ACTIVE
        if (active != lastActive) {
            lastActive = active
            setPanelVisible(!active)
        }
        val sb = StringBuilder()
        LiteLog.currentFile()?.let {
            sb.append("日志文件: ").append(it.absolutePath).append("\n\n")
        }
        for (line in LiteLog.snapshot()) sb.append(line).append('\n')
        logView.text = sb
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private val BG = Color.rgb(16, 20, 28)
    }
}
