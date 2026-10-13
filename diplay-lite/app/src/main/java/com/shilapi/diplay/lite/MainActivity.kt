package com.shilapi.diplay.lite

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.hardware.usb.UsbManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import kotlin.math.abs

/**
 * DiPlay Lite 主界面（原版式视觉重构）：视觉语言对齐上游原版 DiPlayActivity（DiAuto 设计）。
 *
 * 结构（原版式）：page 字符串路由 + render() 整页重建，构建代码收敛为原版式辅助函数
 * （dip/label/button/card/column/row/space/matchButton/rounded）。M6k25 单 Activity 化：
 * page = "home" | "settings" | "caricon" | "hotspot-car" | "hotspot-relay" | "steering"，原
 * SettingsActivity/CarIconActivity/HotspotConfigActivity 收编为同包页面对象
 * （SettingsPage/CarIconPage/HotspotPage），根除子 Activity 新窗口出生方向闪转问题。
 *
 * home 页形态（M6k21，对齐原版）：
 * - 「CARPLAY 状态」大状态卡（原版「无线 CARPLAY」式）：眉题 + 实时状态大字 + 主按钮
 *   （空闲=按默认通道连接 / 会话中=断开）+ 默认通道提示；
 *   （hero 文案区已删除，横屏右列改为与状态卡顶底对齐）
 * - USB 直连键（原版右列位）：竖屏在状态卡下方独立成行；横屏在右列 USB+设置。
 *   通道卡取消：热点/中继网络配置入口移入设置页（「车机热点网络配置」「中继热点网络配置」）。
 *
 * 必须保留的既有能力（勿破坏）：
 * - USB 通道连接路径：USB 设备探测（Apple VID）→ VpnService.prepare 授权 → startSession；
 * - 视频层：SurfaceView 铺满 + forwardTouch 触摸转发，ACTIVE 时面板收起、悬浮球唤回；
 * - 悬浮球：初始右对齐上下居中，拖拽后吸附最近左/右边（attachBallDrag）；
 * - errorCard：FAILED 态居中小卡片（≤300dp，标题 + 3 行省略 + 重试）；
 * - 旋转：onConfigurationChanged 分屏处理；布局方向用 Display 实际宽高判定
 *   （isLandscapeDisplay，勿信 configuration.orientation）；ACTIVE/CONNECTING 仅换面板
 *   不动 SurfaceView；空闲态 render() 整页重建。
 *
 * API16 红线：letterSpacing/stateListAnimator/RippleDrawable 均 ≥API21，全部显式护栏；
 * clipToOutline（API18）沿用既有护栏。零 XML，不改 Manifest。
 */
class MainActivity : Activity() {

    /**
     * 1.1.1 UI：全局字体放大 1.2 倍 —— 全部文字均为 sp 单位，覆盖 fontScale 一次生效
     * （含 AlertDialog/弹窗/输入框）。createConfigurationContext 是 API 17+，API 16 回落不放大。
     */
    override fun attachBaseContext(newBase: android.content.Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            val config = Configuration(newBase.resources.configuration)
            config.fontScale = 1.2f
            super.attachBaseContext(newBase.createConfigurationContext(config))
        } else {
            super.attachBaseContext(newBase)
        }
    }

    private val handler = Handler()

    /** 原版式 page 字符串路由（M6k25 单 Activity 化：全部页面均在本 Activity 内重建）。 */
    private var page = "home"

    // 子页对象：与 page 一一对应，离开页面时 cleanup()（persist/向导注销/线程池关闭等）。
    private var settingsPage: SettingsPage? = null
    private var carIconPage: CarIconPage? = null
    private var hotspotPage: HotspotPage? = null
    private var steeringPage: SteeringPage? = null

    // 视图引用：render()/homePanel() 程序化构建并在其中完成全部监听挂接与引用赋值
    private lateinit var video: SurfaceView

    // 1.0.29：分辨率设定=自动（拉伸）时，视频不做等比 letterbox，直接铺满容器。
    // onCreate 初始化、onResume 刷新（设置页可能改过）。
    private var videoStretchMode = false
    private lateinit var panel: View
    private lateinit var ballRoot: View
    private lateinit var statusDot: View
    private lateinit var statusLine: TextView
    // FAILED 态的居中小卡片（标题 + 3 行省略 + 结束）。
    private lateinit var errorCard: View
    private lateinit var errorTextView: TextView
    // M6k24：状态卡三键 —— 立即连接（常驻，连接中置灰）/ 选择 iPhone（常驻）/ 断开连接（仅连接中出现）。
    // 大状态卡主按钮不再做 0/1 状态切换（对齐原版交互）。
    private lateinit var primaryActionButton: Button
    private lateinit var pickIphoneButton: Button
    private lateinit var disconnectButton: Button
    // 默认通道提示行：设置页改默认通道后回主页实时刷新。
    private lateinit var hintLabel: TextView

    private var panelVisible = true
    private var lastActive = false
    // 与设置页同步：记录当前渲染出的默认通道，tick 里比对 LiteConfig.activeChannel
    private var shownActiveChannel: String? = null
    // 自动连接一个进程生命周期只发起一次，避免用户断开后再被拉起
    private var autoConnectTried = false

    // 1.0.16（方案四②）：开机自启等待弹窗——上电后等勾选的蓝牙/热点就绪并稳定 10s 再自动连接。
    private var autostartWaitActive = false
    private var autostartDialog: AlertDialog? = null
    private var autostartStableSecs = 0
    private var autostartDeadline = 0L

    private val autostartPoll = object : Runnable {
        override fun run() {
            if (!autostartWaitActive || !isFinishing) return
            val config = LiteConfig.load(this@MainActivity)
            // 就绪判定：勾了蓝牙自启 → 蓝牙已开；勾了热点自启 → AP state=13（AOSP ENABLED）。
            val btReady = !config.bootAutoBluetooth || runCatching {
                android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
            }.getOrDefault(false)
            val apReady = !config.bootAutoHotspot || runCatching {
                val wifi = getSystemService(WIFI_SERVICE) as? android.net.wifi.WifiManager
                val state = wifi?.let {
                    android.net.wifi.WifiManager::class.java.getMethod("getWifiApState").invoke(it) as Int
                } ?: -1
                state == 13
            }.getOrDefault(false)
            if (btReady && apReady) {
                autostartStableSecs += 1
            } else {
                autostartStableSecs = 0
            }
            autostartDialog?.setMessage(
                "等待勾选的蓝牙或WIFI热点启动…" +
                    (if (autostartStableSecs > 0) "（已就绪，稳定中 $autostartStableSecs/10s）" else ""),
            )
            if (autostartStableSecs >= AUTOSTART_STABLE_SECS) {
                LiteLog.log("ui", "自启等待：勾选项就绪且稳定 ${AUTOSTART_STABLE_SECS}s，发起自动连接")
                finishAutostartWait()
                maybeAutoConnect()
                return
            }
            if (System.currentTimeMillis() >= autostartDeadline) {
                LiteLog.log("ui", "自启等待：超时 ${AUTOSTART_WAIT_TIMEOUT_MILLIS / 1000}s，自动连接已取消")
                autostartWaitActive = false
                autostartDialog?.apply {
                    setMessage("等待超时：勾选的蓝牙/热点未就绪，自动连接已取消")
                    setButton(AlertDialog.BUTTON_POSITIVE, "确定") { d, _ -> d.dismiss() }
                    show()
                }
                autostartDialog = null
                return
            }
            handler.postDelayed(this, 1_000)
        }
    }

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
        // M4l：车机屏绝不能休眠（API16 无 setOutputSurface，Surface 重建 = 解码器重配）。
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyOrientationMode(this, LiteConfig.load(this).orientationMode)
        videoStretchMode = LiteConfig.load(this).resolutionMode == "stretch"
        render()
        // 1.0.16（方案四②）：开机自启拉起（BootReceiver 带 EXTRA_AUTOSTART）→ 等待弹窗。
        if (intent?.getBooleanExtra(EXTRA_AUTOSTART, false) == true) {
            beginAutostartWait()
        }
        // 1.0.11（FE-6 拉伸）：解码器实际输出尺寸 ≠ 面板尺寸时等比 letterbox，不硬拉伸。
        LiteVideoBridge.setVideoSizeListener { width, height ->
            runOnUiThread { applyVideoFit(width, height) }
        }
        LiteLog.log("ui", "主界面就绪（原版式视觉：page 路由 + render 整页重建）")
        bootstrapNetworkAdb()
    }

    /**
     * vc67（TS7 调试自举）：App 启动即用 root 把 adbd 拉到 TCP 5555。TS7 车机重启后
     * 网络 adb 不持久（展锐 ROM adbd 不读 persist），USB adb 又被编译期阉割，调试链路
     * 反复断——DiPlay 已有记住的 Superuser 授权，静默自举，失败无感（未 root/被拒绝均忽略）。
     * 仅诊断版本启用，稳定版可移除。
     */
    private fun bootstrapNetworkAdb() {
        Thread {
            try {
                val su = Runtime.getRuntime().exec(arrayOf("su", "-c",
                    "setprop service.adb.tcp.port 5555; stop adbd; start adbd"))
                su.waitFor()
                val check = Runtime.getRuntime().exec(arrayOf("sh", "-c", "getprop service.adb.tcp.port"))
                val value = check.inputStream.bufferedReader().readText().trim()
                LiteLog.log("ui", "adb 自举完成 tcp.port=$value")
            } catch (error: Exception) {
                LiteLog.log("ui", "adb 自举跳过：${error.message}")
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        LiteLog.log("ui", "onResume")
        // M6k24：设置页可能改过横竖屏 —— 回主页立即按最新设置强制窗口方向。
        applyOrientationMode(this, LiteConfig.load(this).orientationMode)
        // 1.0.29：设置页可能改过分辨率设定 —— 回主页刷新拉伸模式标志。
        videoStretchMode = LiteConfig.load(this).resolutionMode == "stretch"
        handler.post(refreshTask)
        maybeAutoConnect()
        // 1.1.4 系统诊断：新装无检测记录时，主界面就绪后延迟 5 秒触发首启快检（可跳过）。
        Diagnostics.quickCheckIfDue(this)
    }

    override fun onPause() {
        super.onPause()
        LiteLog.log("ui", "onPause")
        handler.removeCallbacks(refreshTask)
    }

    override fun onDestroy() {
        LiteLog.log("ui", "onDestroy（Activity 被销毁，下一次进入会重建 Surface 与解码器）")
        LiteVideoBridge.setVideoSizeListener(null)
        detachCurrentPage()
        super.onDestroy()
    }

    /**
     * 1.0.11（FE-6 拉伸）：按解码器实际输出尺寸把 SurfaceView 等比适配到容器内并居中。
     * width/height<=0 表示流已结束，恢复铺满。触摸映射（forwardTouch）基于 video 视图
     * 尺寸，等比适配后映射自动正确。
     */
    private fun applyVideoFit(width: Int, height: Int) {
        val parent = video.parent as? android.view.ViewGroup ?: return
        // 1.0.29：分辨率设定=自动（拉伸）时跳过等比 letterbox，恢复铺满容器。
        if (videoStretchMode) {
            val lp = video.layoutParams
            if (lp.width == FrameLayout.LayoutParams.MATCH_PARENT &&
                lp.height == FrameLayout.LayoutParams.MATCH_PARENT
            ) return
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT
            video.layoutParams = lp
            video.requestLayout()
            return
        }
        if (width <= 0 || height <= 0) {
            val lp = video.layoutParams
            if (lp.width == FrameLayout.LayoutParams.MATCH_PARENT &&
                lp.height == FrameLayout.LayoutParams.MATCH_PARENT
            ) return
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT
            video.layoutParams = lp
            video.requestLayout()
            return
        }
        val pw = parent.width
        val ph = parent.height
        if (pw <= 0 || ph <= 0) return
        val scale = minOf(pw.toDouble() / width, ph.toDouble() / height)
        val fitW = Math.max(1, Math.round(width * scale).toInt())
        val fitH = Math.max(1, Math.round(height * scale).toInt())
        val lp = video.layoutParams
        if (lp.width == fitW && lp.height == fitH) return
        lp.width = fitW
        lp.height = fitH
        if (parent is FrameLayout) {
            (lp as FrameLayout.LayoutParams).gravity = android.view.Gravity.CENTER
        }
        video.layoutParams = lp
        video.requestLayout()
        LiteLog.log("ui", "视频等比适配 coded=${width}x${height} → view=${fitW}x${fitH} panel=${pw}x${ph}")
    }

    // ---------- M6k25：单 Activity 页面路由 ----------

    /** 切页：离开旧页先清理（persist/向导注销/试听关闭等），再整页重建。 */
    fun setPage(target: String) {
        if (target == page) return
        detachCurrentPage()
        page = target
        render()
    }

    /** 返回主页（原各子 Activity 的 finish()）。 */
    fun goHome() = setPage("home")

    /** 设置页热点配置入口（原 HotspotConfigActivity.intent(this, mode)）。 */
    fun openHotspotPage(mode: String) = setPage("hotspot-$mode")

    /** 离开当前子页并释放其资源；home 页无资源需要释放。 */
    private fun detachCurrentPage() {
        when (page) {
            "settings" -> settingsPage?.cleanup()
            "caricon" -> carIconPage?.cleanup()
            "hotspot-car", "hotspot-relay" -> hotspotPage?.cleanup()
            "steering" -> steeringPage?.cleanup()
        }
        settingsPage = null
        carIconPage = null
        hotspotPage = null
        steeringPage = null
    }

    /**
     * CarIconPage 图库选择：统一由 MainActivity 发起（原 CarIconActivity.pickImage 的
     * startActivityForResult；单 Activity 后子页不能再自行启动结果回调）。
     */
    fun launchGalleryPicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        try {
            startActivityForResult(Intent.createChooser(intent, "选择图标图片"), REQUEST_GALLERY_PICK)
        } catch (_: Exception) {
            toast("未找到图片选择器")
        }
    }

    /**
     * 方向盘学习页的本地 KeyEvent 捕获（原 SettingsActivity.onKeyDown）：
     * 仅学习页且监听态（钩子非空）时消费。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (page == "steering") {
            steeringPage?.let { if (it.consumeKeyDown(keyCode, event)) return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * 系统返回键层级（1.1.1 UI）：
     * 图标页裁剪态 → 回图标主页；三级页（图标/方向盘/热点配置）→ 设置页；
     * 设置页 → 主页；主页 → 弹窗确认退出。
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            page == "caricon" && carIconPage?.onBackKey() == true -> return
            page != "home" && page != "settings" -> setPage("settings")
            page == "settings" -> goHome()
            else -> confirmExit()
        }
    }

    /** 主页返回：确认退出弹窗（防止误触直接杀会话）。 */
    private fun confirmExit() {
        AlertDialog.Builder(this)
            .setTitle("退出 DiPlay Lite")
            .setMessage("确认退出软件吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认退出") { _, _ -> finish() }
            .show()
    }

    /**
     * 旋转重建（M6k24 起仅兜底用）：方向已是强制二选一，系统旋转不再发生在会话中；
     * 设置页切换横竖屏回主页时窗口方向变化会走到这里。空闲态 render() 整页重建。
     * ACTIVE/CONNECTING 拒绝整页重建 —— Surface 换了会冻结画面，仅换面板不动 SurfaceView。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val phase = LiteState.phase
        // M6k25：子页。会话中不整页重建（与原独立子页行为一致，视图随 configChanges 自动重排）；
        // 空闲态重建当前页（render() 按 page 分发，天然重建正确）。
        if (page != "home") {
            if (phase == LiteState.Phase.IDLE) {
                render()
                LiteLog.log("ui", "旋转：空闲态整页重建（page=$page）")
            } else {
                LiteLog.log("ui", "旋转：子页保持（page=$page phase=$phase）")
            }
            return
        }
        if (phase == LiteState.Phase.ACTIVE || phase == LiteState.Phase.CONNECTING) {
            val root = video.parent as? FrameLayout
            if (root != null) {
                root.removeView(panel)
                panel = homePanel()
                root.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                // 旋转后让视频 SurfaceView 跟随新窗口尺寸重排（MediaCodec 输出自动缩放到 Surface）。
                video.requestLayout()
                setPanelVisible(panelVisible)
                renderState()
            }
            LiteLog.log("ui", "旋转：会话进行中，仅重建面板（视频 Surface 保持）")
            return
        }
        render()
        LiteLog.log("ui", "旋转：空闲态整页重建（page=$page）")
    }

    // ---------- 原版式构建辅助（对齐 DiPlayActivity 的写法） ----------

    private fun dip(value: Int): Int = Ui.dip(this, value)

    /**
     * 面板布局方向改用 Display 实际宽高判定，不再信 configuration.orientation ——
     * C8815（4.1 dalvik）实测强制横屏后 config 仍报 portrait，竖屏布局被画进横屏窗口，
     * 整版文字转 90°（"背景错位"）。Display 宽高与窗口真实形状同源，不受该 ROM bug 影响。
     */
    /**
     * M6k24：布局方向完全跟随设置（强制横屏/强制竖屏二选一，自动旋转已取消）。
     * 不再信 Display/configuration —— 8815 实测两者都会漂移（540x960↔960x540 翻转），
     * 导致竖屏设置下主页偶尔画成横版。窗口方向由 applyOrientationMode 强制，与设定一致。
     */
    private fun isLandscapeUi(): Boolean = LiteConfig.load(this).orientationMode != "portrait"

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.create("sans-serif", Typeface.NORMAL)
        setLineSpacing(dip(3).toFloat(), 1f)
    }

    /**
     * 原版式按钮：primary = ACCENT 实心 + BG 文字；secondary = CARD 面 + BORDER 描边 + TEXT 文字。
     * RippleDrawable/stateListAnimator 是 API21+，必须护栏（API16 红线）。
     */
    private fun button(title: String, primary: Boolean, height: Int = 46, click: () -> Unit) = Button(this).apply {
        text = title; isAllCaps = false; textSize = 15f
        setTextColor(if (primary) Ui.BG else Ui.TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val fill = if (primary) Ui.ACCENT else Ui.CARD
        val stroke = if (primary) Ui.ACCENT else Ui.BORDER
        background = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            RippleDrawable(ColorStateList.valueOf(0x336F9FD9), rounded(fill, stroke), null)
        } else {
            rounded(fill, stroke)
        }
        setPadding(dip(12), 0, dip(12), 0); minHeight = dip(height)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) stateListAnimator = null
        setOnClickListener { click() }
    }

    private fun card() = column().apply {
        background = rounded(Ui.CARD, Ui.BORDER)
        setPadding(dip(16), dip(14), dip(16), dip(16))
    }

    private fun rounded(color: Int, stroke: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dip(20).toFloat(); setStroke(dip(1), stroke)
    }

    private fun matchButton(top: Int = 0, height: Int = 50) =
        LinearLayout.LayoutParams(-1, dip(height)).apply { topMargin = dip(top) }

    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dip(height)) }

    /** 眉题字距：TextView.letterSpacing 是 API21+，护栏后应用（原版 .12f/.16f）。 */
    private fun TextView.applyLetterSpacing(spacing: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) letterSpacing = spacing
    }

    // ---------- 原版式 render：整页重建 ----------

    /**
     * 根视图：Surface 铺满 → home 面板 → 悬浮球 → errorCard。
     * 面板布局方向用 Display 实际宽高判定（isLandscapeDisplay），勿信 configuration.orientation。
     */
    internal fun render() {
        val root = FrameLayout(this).apply { background = ColorDrawable(Ui.BG) }

        // M3：CarPlay 画面镜像到该 SurfaceView；M4k：铺满整屏 + 接手势转发。
        // 子页时铺在页面底下（子页根布局不透明遮住），Surface 常驻避免切页反复换 Surface。
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
        root.addView(video, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        panel = when (page) {
            "settings" -> {
                val newPage = SettingsPage(this)
                settingsPage = newPage
                newPage.buildRoot()
            }
            "caricon" -> {
                val newPage = CarIconPage(this)
                carIconPage = newPage
                newPage.buildRoot()
            }
            "hotspot-car", "hotspot-relay" -> {
                val newPage = HotspotPage(this, page.removePrefix("hotspot-"))
                hotspotPage = newPage
                newPage.buildRoot()
            }
            "steering" -> {
                // M6k26：方控学习页（原全屏 Dialog 在部分 ROM 内容区不渲染，改页面跳转）。
                val newPage = SteeringPage(this)
                steeringPage = newPage
                newPage.buildRoot()
            }
            else -> homePanel()
        }
        root.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        if (page == "home") {
            // M4k/M6k：可拖拽悬浮球，面板收起后的唯一唤回入口（home 专属；子页用 attachPageBall）。
            val ball = FrameLayout(this).apply {
                background = Ui.pillSolid(this@MainActivity, 0x66FFFFFF).apply { cornerRadius = dip(23).toFloat() }
                // setClipToOutline 是 API 18+；部分白牌 ROM（山海等）谎报 SDK_INT≥18 但 framework 缺方法，
                // SDK_INT 护栏拦不住 → 改反射探测，方法不存在时静默跳过。
                runCatching {
                    FrameLayout::class.java
                        .getMethod("setClipToOutline", Boolean::class.javaPrimitiveType)
                        .invoke(this, true)
                }
            }
            val ballIcon = ImageView(this).apply {
                setImageDrawable(IconPainter.arrows(this@MainActivity, 20, Ui.TEXT))
            }
            ball.addView(
                ballIcon,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
            )
            ball.setOnClickListener { setPanelVisible(!panelVisible) }
            // 悬浮球初始位置 = 右对齐 + 上下居中；拖拽后吸附最近左/右边。
            // 1.1.1 UI：横屏（1024x600 车机）右列按键加高后与球重叠 → 初始位改右下角空区；
            // 竖屏右列窄，保持垂直居中。
            val ballSize = dip(46)
            val ballParams = FrameLayout.LayoutParams(ballSize, ballSize, Gravity.TOP or Gravity.END)
            ballParams.topMargin = if (isLandscapeUi()) {
                resources.displayMetrics.heightPixels - ballSize - dip(28)
            } else {
                ((resources.displayMetrics.heightPixels - ballSize) / 2).coerceAtLeast(0)
            }
            ballParams.rightMargin = dip(12)
            ball.layoutParams = ballParams
            attachBallDrag(ball, ballParams)
            ballRoot = ball
            root.addView(ball, ballParams)
        }

        // FAILED 态：居中小卡片（宽度 ≤300dp：标题 + 3 行省略 + 结束按钮）。
        // M6k24：「重试」改「结束」—— 点击只结束当前会话回空闲，由用户自己再点连接。
        val errorCardView = FrameLayout(this).apply {
            background = rounded(Ui.CARD, Ui.BORDER)
            setPadding(dip(18), dip(14), dip(18), dip(16))
        }
        val errorColumn = column()
        errorColumn.addView(label("连接失败", 14, Ui.WARNING, true))
        errorTextView = label("", 13, Ui.TEXT).apply {
            maxWidth = dip(264)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dip(2).toFloat(), 1f)
        }
        errorColumn.addView(
            errorTextView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dip(6) },
        )
        errorColumn.addView(
            button("结束", false, height = 40) {
                errorCardView.visibility = View.GONE
                disconnect()
            },
            matchButton(top = 12, height = 40),
        )
        errorCardView.addView(errorColumn, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        errorCard = errorCardView
        errorCardView.setOnClickListener { errorCardView.visibility = View.GONE }
        errorCardView.visibility = View.GONE
        // errorCard 仅在 home 页展示（与原版一致：子 Activity 时代卡片只存在于 MainActivity）。
        if (page == "home") {
            root.addView(
                errorCardView,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                    leftMargin = dip(24); rightMargin = dip(24)
                },
            )
        }
        setContentView(root)
        setPanelVisible(panelVisible)
        // M6k24k 方向诊断：每个 page 构建完成后记录一次（tag=当前 page）。
        logOrientation(this, page)
        renderState()
    }

    // ---------- home 页 ----------

    /**
     * home 面板（M6k21）：竖屏 = 状态大卡 → USB 直连（按钮+说明）；
     * 横屏 = 状态卡（左）｜USB 直连+设置（右），右列与左卡垂直对齐。
     */
    private fun homePanel(): View {
        // M6k24：布局方向完全跟随设置（强制横屏/强制竖屏），不再读 Display。
        val wide = isLandscapeUi()
        val config = LiteConfig.load(this)
        shownActiveChannel = config.activeChannel

        val content = column().apply { setPadding(dip(16), dip(10), dip(16), dip(20)) }
        content.addView(buildHeader())
        content.addView(space(12))

        val statusCard = buildStatusCard(config)

        if (wide) {
            // 右列（原版式）：USB 直连 + 设置入口。
            // 1.1.1 UI：按键加高（52dp）+ 行间距拉开（12dp），1024x600 车机横屏不再拥挤。
            // 8815 版式：hero 文案区删除，右列 MATCH_PARENT 与左侧状态大卡垂直对齐
            //（USB 直连贴卡顶、设置贴卡底，中段弹性居中）。
            val right = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
            right.addView(
                button("USB 直连", false) { connect("usb") },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dip(52)),
            )
            right.addView(
                label("将 iPhone 插入车机 USB 数据接口，\n当 iPhone 提示时允许 CarPlay。", 12, Ui.MUTED).apply {
                    gravity = Gravity.CENTER
                    setPadding(dip(8), dip(10), dip(8), dip(12))
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
            // M6k24：右列同样提供 3 个系统设置快捷入口 + 设置键。
            right.addView(
                quickSettingsRow(),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dip(12) },
            )
            right.addView(
                button("设置", false) { setPage("settings") },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dip(52)).apply { topMargin = dip(12) },
            )

            val topRow = row().apply { gravity = Gravity.TOP }
            topRow.addView(
                statusCard,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.6f),
            )
            topRow.addView(space(16), LinearLayout.LayoutParams(dip(16), 1))
            // 右列高度撑满行高（= 左卡高度），实现与状态大卡顶底对齐。
            topRow.addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            content.addView(topRow)
        } else {
            content.addView(statusCard)
            // 竖屏：USB 直连键独立成行（原版右列键位）。
            content.addView(
                button("USB 直连", false) { connect("usb") },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dip(50)).apply { topMargin = dip(12) },
            )
            content.addView(
                label("将 iPhone 插入车机 USB 数据接口，当 iPhone 提示时允许 CarPlay。", 12, Ui.MUTED).apply {
                    setPadding(dip(4), dip(8), dip(4), 0)
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
            // M6k23：竖屏最底部加「设置」入口（USB 直连同款样式按钮；齿轮已从头部删除）。
            // M6k24：设置键上方加 3 个系统设置快捷入口。
            content.addView(
                quickSettingsRow(),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dip(12) },
            )
            content.addView(
                button("设置", false) { setPage("settings") },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dip(50)).apply { topMargin = dip(8) },
            )
        }
        return ScrollView(this).apply {
            // 面板底色不透明 —— 悬浮球唤回菜单后不再透出 CarPlay 画面（8815 实测反馈）。
            setBackgroundColor(Ui.BG)
            isFillViewport = true
            addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    /** 头部：DiPlay Lite + 版本。M6k23：右上角齿轮删除（设置入口走竖屏底部/横屏右列按钮）。 */
    private fun buildHeader(): View {
        val left = column()
        left.addView(label("DiPlay Lite", 18, Ui.TEXT, true))
        left.addView(
            label("LITE · ${readVersionName()}", 10, Ui.MUTED).apply { setPadding(0, dip(2), 0, 0); applyLetterSpacing(0.08f) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        return left
    }

    /**
     * 「CARPLAY 状态」大状态卡（原版「无线 CARPLAY」式）：
     * ACCENT 眉题 + 实时状态点 + 状态大字 + 主按钮（按默认通道连接/断开）+ 默认通道提示。
     */
    private fun buildStatusCard(config: LiteConfig): View {
        val card = card()
        val eyebrowRow = row().apply { gravity = Gravity.CENTER_VERTICAL }
        eyebrowRow.addView(
            label("CARPLAY 状态", 12, Ui.ACCENT, true).apply { applyLetterSpacing(0.12f) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        statusDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.GRAY)
            }
        }
        eyebrowRow.addView(statusDot, LinearLayout.LayoutParams(dip(10), dip(10)))
        card.addView(eyebrowRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        statusLine = label(LiteState.statusText, 20, Ui.TEXT, true).apply {
            setPadding(0, dip(10), 0, dip(12))
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }
        card.addView(statusLine, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // M6k24：三键纵排（对齐原版交互）—— 立即连接（常驻，连接中置灰）
        // → 选择 iPhone（常驻，弹热点配置页同款蓝牙选择对话框）→ 断开连接（仅连接中出现）。
        primaryActionButton = button("立即连接", primary = true, height = 50) {
            connect(LiteConfig.load(this@MainActivity).activeChannel)
        }
        card.addView(primaryActionButton, matchButton())

        pickIphoneButton = button("选择 iPhone", primary = false, height = 46) {
            SystemSettingsJumps.showIphonePickerDialog(this@MainActivity) { address, name ->
                LiteConfig.saveBluetoothPick(this@MainActivity, address, name)
                toast(
                    if (address.isBlank()) "已清除选择：连接时自动匹配已配对 iPhone"
                    else "已选择 $name $address",
                )
                LiteLog.log("ui", "主页选择 iPhone：address=$address name=$name")
            }
        }
        card.addView(pickIphoneButton, matchButton(top = 8, height = 46))

        disconnectButton = button("断开连接", primary = false, height = 46) { disconnect() }
        disconnectButton.visibility = View.GONE
        card.addView(disconnectButton, matchButton(top = 8, height = 46))

        hintLabel = label(defaultChannelHint(config.activeChannel), 12, Ui.MUTED).apply { setPadding(0, dip(12), 0, 0) }
        card.addView(
            hintLabel,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        return card
    }

    private fun defaultChannelHint(channel: String): String = when (channel) {
        "relay" -> "默认通道：中继热点。车机与手机同连随身WIFI后自动建立 CarPlay。"
        "usb" -> "默认通道：USB 连接。数据线直连，当 iPhone 提示时允许 CarPlay。"
        else -> "默认通道：车机热点。手机连上车机热点后自动建立 CarPlay。"
    }

    /**
     * M6k24：主页快捷入口行（WiFi / 蓝牙 / 热点），放在设置键上方，一行三列等宽。
     * 跳转逻辑与热点配置页共用 SystemSettingsJumps（三级降级）。
     */
    private fun quickSettingsRow(): View {
        val rowView = row()
        val entries = listOf(
            "WiFi" to { SystemSettingsJumps.openWifiSettings(this) },
            "蓝牙" to { SystemSettingsJumps.openBluetoothSettings(this) },
            "热点" to { SystemSettingsJumps.openHotspotSettings(this) },
        )
        entries.forEachIndexed { index, (title, onClick) ->
            val entry = button(title, primary = false, height = 48, click = onClick)
            entry.layoutParams = LinearLayout.LayoutParams(0, dip(48), 1f).apply {
                if (index > 0) leftMargin = dip(8)
            }
            rowView.addView(entry)
        }
        return rowView
    }

    // ---------- 视图状态 ----------

    /** 配置面板的显隐：收起后整屏都是 CarPlay 画面与触摸区（home 页专属机制）。 */
    private fun setPanelVisible(visible: Boolean) {
        panelVisible = visible
        // M6k25：子页没有面板显隐机制 —— 只记录状态，回主页 render 时按状态生效。
        // （避免 refreshTask 的 setPanelVisible(!active) 把正在看的子页整个隐藏。）
        if (page != "home") return
        panel.visibility = if (visible) View.VISIBLE else View.GONE
        // 面板展开时悬浮球淡化（避免和标题栏抢视觉），收起时恢复醒目。
        ballRoot.alpha = if (visible) 0.5f else 1f
    }

    /**
     * 悬浮球可拖拽。按下记位、移动改 right/top margin、抬起几乎未动则视为点击。
     * TOP|END 定位：向右拖 = rightMargin 减小；范围钳在父容器内；松手吸边。
     */
    private fun attachBallDrag(ball: View, params: FrameLayout.LayoutParams) {
        val slop = dip(6)
        var downRawX = 0f
        var downRawY = 0f
        var downRight = 0
        var downTop = 0
        var dragging = false
        ball.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downRight = params.rightMargin
                    downTop = params.topMargin
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        val parent = view.parent as? View
                        val maxRight = ((parent?.width ?: 0) - view.width).coerceAtLeast(0)
                        val maxTop = ((parent?.height ?: 0) - view.height).coerceAtLeast(0)
                        params.rightMargin = (downRight - dx).coerceIn(0, maxRight)
                        params.topMargin = (downTop + dy).coerceIn(0, maxTop)
                        view.layoutParams = params
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        // 松手吸边 —— 球心在左半屏贴左、右半屏贴右，纵向保持松手位置。
                        val parent = view.parent as? View
                        val parentWidth = parent?.width ?: 0
                        if (parentWidth > 0) {
                            val centerX = parentWidth - params.rightMargin - view.width / 2
                            params.rightMargin = if (centerX > parentWidth / 2) {
                                dip(12)
                            } else {
                                (parentWidth - view.width - dip(12)).coerceAtLeast(0)
                            }
                            view.layoutParams = params
                        }
                    } else {
                        view.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    /**
     * 触摸转发：屏幕坐标归一化成 0..1 的接触点，交给当前会话发 HID 报告。
     * 未连接时返回 false（不消费事件）。
     */
    private var lastMoveForwardMs = 0L

    private fun forwardTouch(event: MotionEvent): Boolean {
        val service = LiteSessionService.current ?: return false
        val width = video.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val height = video.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        if (width <= 0 || height <= 0) return false
        // 只有 ACTION_MOVE 是可合并的中间态；DOWN/UP/POINTER_*/CANCEL 都必须逐条送达。
        val sticky = event.actionMasked != MotionEvent.ACTION_MOVE
        // 1.0.7：MOVE 按 30fps 节流——手指按屏抖动会以 60Hz 洪灌 HID 通道
        // （3e 实测 15 秒 310 条 POST），DOWN/UP 不受影响。
        if (!sticky) {
            val now = System.currentTimeMillis()
            if (now - lastMoveForwardMs < 33) return true
            lastMoveForwardMs = now
        }
        return service.sendTouch(CarPlayTouchMapper.contacts(event, width, height), sticky)
    }

    // ---------- 连接动作 ----------

    /** 按通道发起连接。中继/USB 底层未接入前给出明确提示（避免误认为故障）。 */
    private fun connect(channel: String) {
        val config = LiteConfig.load(this)
        when (channel) {
            "car" -> {
                if (config.ssid.isBlank()) {
                    toast("请先在「设置 → 车机热点网络配置」里填写热点名称")
                    return
                }
                val key = LiteConfig.videoKeyOf(config.videoScalePercent, config.limit30Fps)
                // M6d：实测改分辨率后不重启+重连会出现渲染异常，这里拦一道二次确认。
                if (config.appliedVideoKey.isNotEmpty() && config.appliedVideoKey != key) {
                    confirmVideoChange()
                    return
                }
                startSession(config, key)
            }
            "relay" -> {
                if (config.relaySsid.isBlank()) {
                    toast("请先在「设置 → 中继热点网络配置」里填写随身WIFI信息")
                    return
                }
                val key = LiteConfig.videoKeyOf(config.videoScalePercent, config.limit30Fps)
                // M6d：实测改分辨率后不重启+重连会出现渲染异常，这里拦一道二次确认。
                if (config.appliedVideoKey.isNotEmpty() && config.appliedVideoKey != key) {
                    confirmVideoChange()
                    return
                }
                startSession(config, key, "relay")
            }
            "usb" -> {
                // U4 USB 有线：数据面 TUN 需要 VPN 授权（一次性系统对话框）；
                // 已授权（prepare 返回 null）则直接进入连接流程。
                val key = LiteConfig.videoKeyOf(config.videoScalePercent, config.limit30Fps)
                if (config.appliedVideoKey.isNotEmpty() && config.appliedVideoKey != key) {
                    confirmVideoChange()
                    return
                }
                if (!hasAppleUsbDevice()) {
                    toast("未检测到 iPhone：请用数据线把 iPhone 连到车机 USB 口")
                    LiteLog.log("ui", "USB 通道连接请求：未发现 Apple VID(0x05ac) USB 设备")
                    return
                }
                val consent = try {
                    VpnService.prepare(this)
                } catch (error: Exception) {
                    LiteLog.log("ui", "USB 通道：VpnService.prepare 失败 ${error.message}")
                    null
                }
                if (consent != null) {
                    startActivityForResult(consent, REQUEST_VPN_PERMISSION)
                    LiteLog.log("ui", "USB 通道：请求 VPN 授权")
                    return
                }
                startSession(config, key, "usb")
            }
            else -> {
                toast("未知连接通道")
                LiteLog.log("ui", "未知通道连接请求：$channel")
            }
        }
    }

    /** 枚举 USB 设备看是否有 Apple VID（iPhone/iPad），无需任何权限。 */
    private fun hasAppleUsbDevice(): Boolean =
        (getSystemService(USB_SERVICE) as? UsbManager)?.deviceList?.values?.any {
            it.vendorId == APPLE_USB_VENDOR_ID
        } == true

    /** VpnService 授权 / CarIconPage 图库选择的系统回调。 */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // M6k25：图库选择结果转发给 CarIconPage（requestCode=1001）。
        if (requestCode == REQUEST_GALLERY_PICK) {
            carIconPage?.onGalleryResult(resultCode, data)
            return
        }
        if (requestCode != REQUEST_VPN_PERMISSION) return
        if (resultCode == RESULT_OK) {
            startSession(LiteConfig.load(this), LiteConfig.videoKeyOf(LiteConfig.load(this).videoScalePercent, LiteConfig.load(this).limit30Fps), "usb")
        } else {
            toast("未授予 VPN 权限，USB 连接无法建立")
            LiteLog.log("ui", "USB 通道：VPN 授权被拒绝")
        }
    }

    private fun startSession(config: LiteConfig, videoKey: String, channel: String = "car") {
        LiteConfig.saveAppliedVideoKey(this, videoKey)
        // 记录本轮会话通道：每卡绿点按它判定"哪张卡在连/已连"（UI 层自有键，不动 LiteConfig）。
        getSharedPreferences(SESSION_PREFS, MODE_PRIVATE)
            .edit()
            .putString(SESSION_CHANNEL_KEY, channel)
            .apply()
        if (channel == "relay") {
            LiteLog.log(
                "ui",
                "发起连接（relay）：ssid=${config.relaySsid} videoKey=$videoKey duck=${config.navDuckPercent}%",
            )
        } else {
            LiteLog.log(
                "ui",
                "发起连接（car）：ssid=${config.ssid} channel=${config.carIpv4Channel} security=${config.security} " +
                    "videoKey=$videoKey duck=${config.navDuckPercent}%",
            )
        }
        startService(
            Intent(this, LiteSessionService::class.java).putExtra(LiteSessionService.EXTRA_CHANNEL, channel),
        )
        toast("已启动会话服务")
    }

    /**
     * M6d：分辨率/帧率变更的二次确认。确认后立刻停止会话并重建界面 ——
     * 改了视频参数不重启会渲染异常（解码器尺寸与 Surface 对不上）。
     */
    private fun confirmVideoChange() {
        AlertDialog.Builder(this)
            .setTitle("已修改分辨率/帧率")
            .setMessage(
                "视频参数改动必须断开当前连接并重启软件才能生效，否则画面会渲染异常。\n\n" +
                    "现在断开并重启吗？",
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("断开并重启") { _, _ ->
                restartAppForVideoChange()
            }
            .show()
    }

    /**
     * 视频参数（分辨率/帧率/横竖屏）修改后的统一生效路径。
     *
     * 原「AlarmManager 预约拉起 + killProcess」在 EMUI9 实测不可靠：set() 是批量闹钟，
     * 进程死后自启动又受启动管理限制 —— 应用被杀后不再回来（"没有自动重启"）；残留现场里
     * stopService 的异步拆栈未等待，此时点连接会卡在连接中（残留 7000 监听，见
     * LiteSessionService 的 EADDRINUSE 记录）。
     *
     * M6k25 单 Activity 化后改为进程内重建：appliedVideoKey 落成新值（重启后点主页连接
     * 直接按新参数发起，不再二次弹确认）→ 停会话服务（拆栈由 closingController await 收口）
     * → finish + startActivity 重建 MainActivity（全新 SurfaceView 与页面状态），不杀进程、
     * 不依赖闹钟，行为等价于进程冷启动的"重启软件"。
     */
    internal fun restartAppForVideoChange() {
        val config = LiteConfig.load(this)
        LiteConfig.saveAppliedVideoKey(this, LiteConfig.videoKeyOf(config.videoScalePercent, config.limit30Fps))
        stopService(Intent(this, LiteSessionService::class.java))
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        LiteLog.log("ui", "视频参数已改：停止会话并重建界面以应用")
        startActivity(intent)
        finish()
    }

    private fun disconnect() {
        stopService(Intent(this, LiteSessionService::class.java))
        LiteLog.log("ui", "用户断开")
    }

    // ---------- 自动连接 ----------

    /**
     * 勾了"启动后自动连接"时回到前台自动以默认通道发起。
     * car/relay/usb 均可自动连接；视频参数被改过则跳过（避免开机弹确认框）。
     */
    private fun maybeAutoConnect() {
        if (autoConnectTried) return
        // 1.0.16（方案四②）：自启等待期间不发起，等勾选项就绪且稳定后由 autostartPoll 触发。
        if (autostartWaitActive) return
        if (LiteState.phase != LiteState.Phase.IDLE) return
        if (LiteSessionService.current != null) return
        val config = LiteConfig.load(this)
        if (!config.autoConnect) return
        val channel = config.activeChannel
        if (channel != "car" && channel != "relay" && channel != "usb") return
        val ssidBlank = when (channel) {
            "car" -> config.ssid.isBlank()
            "relay" -> config.relaySsid.isBlank()
            else -> false // USB 有线不依赖热点凭据
        }
        if (ssidBlank) return
        autoConnectTried = true
        val key = LiteConfig.videoKeyOf(config.videoScalePercent, config.limit30Fps)
        if (config.appliedVideoKey.isNotEmpty() && config.appliedVideoKey != key) {
            LiteLog.log("ui", "自动连接跳过：视频参数已改，请手动连接并确认重启")
            return
        }
        LiteLog.log("ui", "自动连接：按默认通道（$channel）自动发起")
        connect(channel)
    }

    // ---------- 1.0.16（方案四②）开机自启等待弹窗 ----------

    /**
     * 上电拉起主页后显示「等待勾选的蓝牙或WIFI热点启动」弹窗：
     * - 弹窗带「断开」键，点击即结束自动流程（已开的蓝牙/热点保留不关）；
     * - 勾选项一直不开启则一直等待，直到 60s 超时显示失败提示（不再自动连接）；
     * - 勾选项全部就绪后需连续稳定 10s（滤波，防 ROM 状态抖动）才发起自动连接。
     * 仅当勾了蓝牙/热点自启且开了自动连接时才弹；否则走原自动连接（onResume 即发）。
     */
    private fun beginAutostartWait() {
        val config = LiteConfig.load(this)
        val hasWaitItems = config.bootAutoBluetooth || config.bootAutoHotspot
        if (!config.autoConnect || !hasWaitItems || autostartWaitActive) {
            LiteLog.log(
                "ui",
                "自启等待跳过：autoConnect=${config.autoConnect} bt=${config.bootAutoBluetooth} " +
                    "ap=${config.bootAutoHotspot}",
            )
            return
        }
        autostartWaitActive = true
        autostartStableSecs = 0
        autostartDeadline = System.currentTimeMillis() + AUTOSTART_WAIT_TIMEOUT_MILLIS
        autostartDialog = AlertDialog.Builder(this)
            .setTitle("开机自启动")
            .setMessage("等待勾选的蓝牙或WIFI热点启动…")
            .setCancelable(false)
            .setNegativeButton("断开") { d, _ ->
                LiteLog.log("ui", "自启等待：用户点击断开，结束自动流程")
                autostartWaitActive = false
                handler.removeCallbacks(autostartPoll)
                d.dismiss()
            }
            .create()
        autostartDialog?.show()
        LiteLog.log(
            "ui",
            "自启等待：开始等待勾选项就绪（超时 ${AUTOSTART_WAIT_TIMEOUT_MILLIS / 1000}s，稳定 ${AUTOSTART_STABLE_SECS}s）",
        )
        handler.postDelayed(autostartPoll, 1_000)
    }

    private fun finishAutostartWait() {
        autostartWaitActive = false
        handler.removeCallbacks(autostartPoll)
        autostartDialog?.dismiss()
        autostartDialog = null
    }

    // ---------- 状态渲染 ----------

    private fun renderState() {
        val phase = LiteState.phase
        val running = LiteState.running
        // 大状态卡状态点：总览实时状态。
        (statusDot.background as? GradientDrawable)?.setColor(
            when (phase) {
                LiteState.Phase.ACTIVE -> Ui.GREEN
                LiteState.Phase.CONNECTING -> DOT_CONNECTING
                LiteState.Phase.FAILED -> DOT_FAILED
                LiteState.Phase.IDLE -> Color.GRAY
            },
        )
        statusLine.text = LiteState.statusText
        // 默认通道提示与设置页三选项同步（设置页改动后回到主页实时刷新）。
        val activeChannel = LiteConfig.load(this).activeChannel
        if (activeChannel != shownActiveChannel) {
            shownActiveChannel = activeChannel
            hintLabel.text = defaultChannelHint(activeChannel)
        }
        // M6k24：主按钮固定「立即连接」（连接中/激活置灰）；断开连接独立成键，仅连接中出现。
        primaryActionButton.isEnabled = !running
        primaryActionButton.alpha = if (running) 0.5f else 1f
        disconnectButton.visibility = if (running) View.VISIBLE else View.GONE
        // FAILED 态弹居中小卡片（标题+3行省略+重试）；其余状态收起。
        val failed = phase == LiteState.Phase.FAILED
        if (failed && errorCard.visibility != View.VISIBLE) {
            errorTextView.text = LiteState.statusText
            errorCard.visibility = View.VISIBLE
        } else if (!failed && errorCard.visibility == View.VISIBLE) {
            errorCard.visibility = View.GONE
        }
        // 连接成功即自动收起面板 —— 此时用户要看的是 CarPlay 画面；断开后再自动展开。
        val active = phase == LiteState.Phase.ACTIVE
        if (active != lastActive) {
            lastActive = active
            setPanelVisible(!active)
        }
    }

    /** 当前会话通道（仅服务存活时有效）；errorCard 重试与每卡绿点用它定位通道。 */
    private fun currentSessionChannel(): String? =
        if (LiteState.running) {
            getSharedPreferences(SESSION_PREFS, MODE_PRIVATE).getString(SESSION_CHANNEL_KEY, null)
        } else {
            null
        }

    internal fun readVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        // UI 层自有 prefs：只记"本轮会话通道"，不触碰 LiteConfig 逻辑字段。
        private const val SESSION_PREFS = "diplay_lite_ui"
        private const val SESSION_CHANNEL_KEY = "session_channel"
        // U4 USB 有线：VpnService 授权回调码 + Apple USB 厂商 ID（与 IphoneUsbMatcher 一致）。
        private const val REQUEST_VPN_PERMISSION = 7001
        // M6k25：CarIconPage 图库选择回调码（原 CarIconActivity.REQ_PICK_IMAGE=4301）。
        private const val REQUEST_GALLERY_PICK = 1001
        private const val APPLE_USB_VENDOR_ID = 0x05ac
        private val DOT_CONNECTING = Color.rgb(240, 200, 60)
        private val DOT_FAILED = Color.rgb(240, 80, 80)

        // 1.0.16（方案四②）开机自启等待弹窗参数。
        /** BootReceiver 拉起主页时标记「开机自启等待」流程。 */
        const val EXTRA_AUTOSTART = "autostart_wait"
        /** 勾选项就绪后需连续保持的稳定时长（滤波）。 */
        const val AUTOSTART_STABLE_SECS = 10
        /** 等待勾选项就绪的总超时。 */
        const val AUTOSTART_WAIT_TIMEOUT_MILLIS = 60_000L
    }
}
