package com.shilapi.diplay.lite

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.shilapi.xcertplay.usbbt.DiscoveredDevice
import com.shilapi.xcertplay.usbbt.PairedDevice
import com.shilapi.xcertplay.usbbt.PairState
import com.shilapi.xcertplay.usbbt.UsbBtStandby
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * vc207：外置蓝牙配对窗 v2（设计稿 工程文档/设计稿-外置蓝牙配对-v2-20261103 + 用户截图字段）。
 *
 * 布局（用户拍板，vc238 删「可被发现」块后）：
 * - 竖屏 = A 方案单列：名称框（点击本框改名）→ 已配对设备 → 已发现设备
 *   → 外置蓝牙设置组（默认折叠控高度）。底栏固定 [取消|完成]。
 * - 横屏 = 双列：左列纯配置（名称框（点击改名）、芯片/固件+BD_ADDR、dongle 日志 上传、三开关）；
 *   右列 = 已配对设备、已发现设备（点击配对），两列各自内部可滚动。
 *
 * vc238：可被发现开关/倒计时整块 UI 已删除；底层配对窗口行为不变 —— 未配对时由
 * refresh() 自动把配对窗口续到 10 分钟（等效旧开关常开），配对成功后不再续（等效旧开关关闭，
 * 此时 host 因已存 link key 仍保持 scan=3 可发现）。已发现设备来自 host 侧 inquiry 快照
 * （窗口开着时约 10s 一轮），整行可点 → startPairing() 发起 dedicated bonding（复用既有 SSP 应答链）。
 *
 * dongle 日志：只保留「上传」[DongleLogUploader]（Gitee 私有仓，30s 超时，TLS1.2 兜底），
 * 上传键与标题同行；下方状态行：尚未生成 / 上传中 / 上次上传时间 / 失败（含本地绝对路径）。
 *
 * API16：全部自绘（GradientDrawable/Canvas/ToggleView），无 Switch/Material 组件。
 */
object ExternalBtPairingDialog {
    private const val REFRESH_MILLIS = 1_000L

    /** 展示配对窗；入口会先确保常驻广播已启动（「开机自动初始化」关也能用）。 */
    fun show(activity: MainActivity) {
        activity.ensureUsbBtStandby()
        // vc210：方向判定改用窗口实际宽高比。K60 实测：App 请求竖屏（req=PORTRAIT）时
        // configuration 仍报 LANDSCAPE（面板物理横装 ROTATION_90，窗口 1440x3023 竖屏形），
        // 按 configuration 会把竖屏窗口渲染成横屏双列 —— vc209「竖屏错位/遮挡」的根因。
        val contentView = activity.findViewById<View>(android.R.id.content)
        val landscape = if (contentView != null && contentView.width > 0 && contentView.height > 0) {
            contentView.width > contentView.height
        } else {
            activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        val ui = DialogUi(activity, landscape)
        val dialog = AppDialog.Builder(activity)
            .setTitle("外置蓝牙配对")
            .setView(ui.root)
            .setNegativeButton("取消", null)
            .setPositiveButton("完成", null)
            .setOnDismissListener { ui.stop() }
            .create()
        if (landscape) {
            dialog.maxHeightFraction = 0.9f
            dialog.buttonHeightDp = 36
            dialog.renderButtons()
        }
        ui.attach(dialog)
        dialog.show()
        ui.start()
        // vc238：列高先按窗口高度估算，首帧布局后再按 AppDialog 内容区真实可视高度校正一次（仅横屏双列）。
        if (landscape) ui.correctColumnHeightAfterLayout()
        LiteLog.log(
            "ui",
            "外置蓝牙配对窗已打开（${if (landscape) "横屏双列" else "竖屏单列"}，" +
                "窗口 ${contentView?.width}x${contentView?.height}）",
        )
    }

    private enum class UploadState { IDLE, UPLOADING, SUCCESS, FAIL }

    @SuppressLint("SetTextI18n") // 全仓中文硬编码惯例（同 AppDialog），本弹层文案随状态动态拼接
    private class DialogUi(
        private val activity: MainActivity,
        private val landscape: Boolean,
    ) {
        val root: View
        private val handler = Handler(Looper.getMainLooper())
        private var dialog: AppDialog? = null
        private var running = false
        private var lastSignature = ""

        private var dongleName: String = LiteConfig.load(activity).usbBtDongleName
        private var settingsExpanded = false // 竖屏设置组默认折叠
        private var uploadState = UploadState.IDLE
        private var uploadFileName: String? = null // 本地生成/上传成功的文件名
        private var uploadDetail: String? = null   // 失败原因
        private var uploadFilePath: String? = null // 失败时本地文件的绝对路径（File.absolutePath）
        private var uploadTime: String? = null     // 上次上传成功时间（HH:mm:ss）

        private val tick = object : Runnable {
            override fun run() {
                if (!running) return
                refresh()
                handler.postDelayed(this, REFRESH_MILLIS)
            }
        }

        init {
            root = if (landscape) buildLandscapeShell() else LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }
        }

        /** 横屏外壳：左右双列一次成型，refresh 只重填两列内容。 */
        private lateinit var leftColumn: LinearLayout
        private lateinit var rightColumn: LinearLayout
        private var leftScroll: ColumnScrollView? = null
        private var rightScroll: ColumnScrollView? = null
        private lateinit var leftScrollHint: TextView
        private lateinit var rightScrollHint: TextView

        /** 当前列高（px）：先按窗口高度估算，首帧布局后按真实视口校正一次。 */
        private var columnHeight = 0

        private fun buildLandscapeShell(): View {
            leftColumn = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            rightColumn = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            // vc210：双列同高、各自滚动。列高 = 窗口高 - 标题/底栏/卡片内边距估高(dp190)。
            // 此前只右列定高、左列包内容：左列配置项总高 ~460dp 远超矮屏视口，把整张卡片
            // 顶出屏幕、底栏[取消][完成]被裁（vc209 横屏"遮住"根因）；定高后底栏恒可见。
            // vc238：改用 Activity 内容区高度（API16 横屏下 displayMetrics.heightPixels 含状态栏，
            // 会高估列高）；内容区未就绪时退回屏幕高度，attach 后另做一次校正。
            val hostContent = activity.findViewById<View>(android.R.id.content)
            val windowH = hostContent?.height?.takeIf { it > 0 }
                ?: activity.resources.displayMetrics.heightPixels
            columnHeight = (windowH - dp(190)).coerceAtLeast(dp(160))
            val leftPair = scrollColumn(leftColumn)
            leftScroll = leftPair.first; leftScrollHint = leftPair.second
            val rightPair = scrollColumn(rightColumn)
            rightScroll = rightPair.first; rightScrollHint = rightPair.second
            val rightWrap = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(rightPair.first, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, columnHeight))
                addView(rightPair.second, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            val leftWrap = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(leftPair.first, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, columnHeight))
                addView(leftPair.second, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            return LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(leftWrap, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.04f))
                addView(View(activity).apply { setBackgroundColor(Ui.LINE) },
                    LinearLayout.LayoutParams(1, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                        leftMargin = dp(11)
                    })
                addView(rightWrap, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(11)
                })
            }
        }

        /** vc210：定高滚动列 + 「▼ 还能滑」提示（内容超高且未滚到底才显示）。 */
        private fun scrollColumn(content: View): Pair<ColumnScrollView, TextView> {
            val hint = TextView(activity).apply {
                text = "▼ 还能滑"
                textSize = 11f
                setTextColor(Ui.SUB)
                gravity = Gravity.CENTER
                visibility = View.GONE
            }
            val scroll = ColumnScrollView(activity).apply {
                addView(content)
                isFillViewport = false
                // vc238：布局结束后重算提示（refresh 里的 post 可能早于测量，会误判「已全显示」）。
                onViewportChanged = { updateScrollHints() }
                onScrollChangedListener = { updateScrollHint(this, hint) }
            }
            return scroll to hint
        }

        private fun updateScrollHint(s: ScrollView, hint: TextView) {
            // vc238：未完成测量时保持现状，避免「内容 0 高 → 判为不用滚」把提示永远压掉。
            if (s.height <= 0) return
            val child = s.getChildAt(0) ?: return
            if (child.height <= 0) return
            val canScroll = child.height > s.height
            val atBottom = child.height - s.height - s.scrollY < dp(4)
            val want = if (canScroll && !atBottom) View.VISIBLE else View.GONE
            if (hint.visibility != want) hint.visibility = want
        }

        private fun updateScrollHints() {
            leftScroll?.let { if (::leftScrollHint.isInitialized) updateScrollHint(it, leftScrollHint) }
            rightScroll?.let { if (::rightScrollHint.isInitialized) updateScrollHint(it, rightScrollHint) }
        }

        /** vc238：首帧布局完成后，按 AppDialog 内容区真实可视高度校正列高（只做一次）。
         *  估算值偏大时收窄，保证整张卡片（标题/双列/底栏）都落在对话框限高视口内。 */
        fun correctColumnHeightAfterLayout() {
            val observer = root.viewTreeObserver
            if (!observer.isAlive) return
            observer.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    val vto = root.viewTreeObserver
                    if (vto.isAlive) vto.removeOnGlobalLayoutListener(this)
                    clampColumnsToViewport()
                }
            })
        }

        private fun clampColumnsToViewport() {
            val viewport = dialog?.contentViewportHeight() ?: 0
            if (viewport <= 0 || root.height <= 0) return
            val overflow = root.height - viewport
            if (overflow <= 0) return
            val target = (columnHeight - overflow).coerceAtLeast(dp(120))
            if (target >= columnHeight) return
            columnHeight = target
            leftScroll?.let { v ->
                (v.layoutParams as? LinearLayout.LayoutParams)?.height = target
                v.requestLayout()
            }
            rightScroll?.let { v ->
                (v.layoutParams as? LinearLayout.LayoutParams)?.height = target
                v.requestLayout()
            }
            updateScrollHints()
            LiteLog.log("ui", "外置蓝牙配对窗列高校正：$overflow px → ${target}px（视口 ${viewport}px）")
        }

        fun attach(d: AppDialog) {
            dialog = d
        }

        fun start() {
            running = true
            refresh()
            handler.postDelayed(tick, REFRESH_MILLIS)
        }

        fun stop() {
            running = false
            handler.removeCallbacks(tick)
            dialog = null
        }

        // ---------- 状态读取与渲染 ----------

        private fun refresh() = refresh(force = false)

        private fun refresh(force: Boolean) {
            val state = UsbBtStandby.pairState
            // vc238：可被发现开关 UI 已删，底层行为不变 —— 未配对时自动保持 10 分钟配对窗口
            // （等效旧开关「开」）；配对成功后不再续期（等效旧开关随配对成功自动关，
            // 此时 host 因 sharedLinkKeys 非空仍写 scan=3，可发现状态不回退）。
            // 总闸（保持常驻广播）关闭时不再续窗口：关闭 = 设备不可被搜索。
            if (
                LiteConfig.usbBtKeepStandbyOf(activity) &&
                state == PairState.UNPAIRED && UsbBtStandby.pairingRemainingMillis() <= 0
            ) {
                UsbBtStandby.enterPairingMode()
                LiteLog.log("ui", "外置蓝牙：未配对，自动保持配对窗口（可被发现）")
            }
            val paired = UsbBtStandby.pairedDevices()
            val discovered = UsbBtStandby.discoveredDevices()
            val address = UsbBtStandby.dongleAddress()
            val info = UsbBtStandby.dongleInfo()
            val pairTarget = UsbBtStandby.pairTarget
            val signature = buildString {
                append(state).append('|')
                append(paired.size).append('|')
                paired.forEach { append(it.address).append(',') }
                append('|').append(discovered.size).append('|')
                discovered.forEach { append(it.address).append(',').append(it.name).append(';') }
                append('|').append(address).append('|').append(info)
                append('|').append(pairTarget)
                append('|').append(dongleName)
                append('|').append(settingsExpanded)
                append('|').append(uploadState).append('|').append(uploadFileName)
                append('|').append(uploadTime).append('|').append(uploadFilePath).append('|').append(uploadDetail)
                append('|').append(LiteConfig.usbBtAutoInitOf(activity))
                append(LiteConfig.usbBtPreferExternalOf(activity))
                append(LiteConfig.usbBtKeepStandbyOf(activity))
            }
            if (!force && signature == lastSignature) return
            lastSignature = signature

            val pairedKeys = paired.map { it.address.replace(":", "") }.toSet()
            if (landscape) {
                leftColumn.removeAllViews()
                buildLeftColumn(leftColumn, state, address, info)
                rightColumn.removeAllViews()
                buildRightColumn(rightColumn, paired, discovered, pairedKeys, pairTarget)
                // vc209/vc210：重填后高度可能变化，布局后重估滚动提示。
                rightScroll?.post { updateScrollHints() }
                leftScroll?.post { updateScrollHints() }
            } else {
                val column = root as LinearLayout
                column.removeAllViews()
                column.addView(nameRow(state))
                buildPairedSection(column, paired)
                buildDiscoveredSection(column, discovered, pairedKeys, pairTarget)
                buildSettingsGroup(column, address, info, collapsible = true)
            }
        }

        // ---------- 横屏双列 ----------

        private fun buildLeftColumn(column: LinearLayout, state: PairState, address: String?, info: String?) {
            column.addView(nameRow(state))
            column.addView(sectionTitle("外置蓝牙设置", null))
            column.addView(chipInfoBlock(address, info))
            column.addView(dongleLogBlock())
            column.addView(settingRow(
                "开机自动初始化", "上电即初始化，缩短首次等待；老车机可能拖慢启动。",
                recommendOn = false,
                checked = LiteConfig.usbBtAutoInitOf(activity),
            ) { v ->
                LiteConfig.setUsbBtAutoInit(activity, v)
                true
            })
            column.addView(settingRow(
                "优先用外置蓝牙广播", "连接优先走 dongle，减少与车机蓝牙抢设备。",
                recommendOn = true,
                checked = LiteConfig.usbBtPreferExternalOf(activity),
            ) { v ->
                LiteConfig.setUsbBtPreferExternal(activity, v)
                true
            })
            column.addView(keepStandbyRow())
        }

        private fun buildRightColumn(
            column: LinearLayout,
            paired: List<PairedDevice>,
            discovered: List<DiscoveredDevice>,
            pairedKeys: Set<String>,
            pairTarget: String?,
        ) {
            buildPairedSection(column, paired)
            buildDiscoveredSection(column, discovered, pairedKeys, pairTarget)
        }

        // ---------- ① 名称行（点击改名） ----------

        private fun nameRow(state: PairState): View =
            LinearLayout(activity).apply {
                // vc210：两行结构 —— 行1 图标+名称+chip；行2 副标题独占整宽。
                // vc238：去掉独立的「✏ 改名」按钮，整个圆角框即改名入口（按下态变色以示可点）。
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(11), dp(12), dp(11))
                background = nameBoxBackground()
                isClickable = true
                setOnClickListener { showRenameDialog() }
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(iconBox())
                    addView(
                        TextView(activity).apply {
                            text = dongleName
                            setTextColor(Ui.TEXT)
                            textSize = 15f
                            setTypeface(typeface, Typeface.BOLD)
                            maxLines = 1
                            // vc211：竖屏被 chip 挤压截断成「DiPlay」→ 末尾省略号。
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        },
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            leftMargin = dp(11)
                        },
                    )
                    addView(chip(state))
                })
                // 行2：副标题独占整宽
                addView(TextView(activity).apply {
                    text = "USB 蓝牙适配器 · 点击本框，可自定义蓝牙名称"
                    setTextColor(Ui.SUB)
                    textSize = 11f
                    setPadding(0, dp(4), 0, 0)
                })
            }

        /** vc238：名称框背景 —— 常态圆角框，按下提亮 + ACCENT 描边（API16 无 Ripple，用 state_pressed 态）。 */
        private fun nameBoxBackground(): StateListDrawable {
            val normal = GradientDrawable().apply {
                setColor(0xB81E2C4A.toInt()) // INPUT_FILL 72%
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Ui.LINE)
            }
            val pressed = GradientDrawable().apply {
                setColor(0xD82A3A5C.toInt()) // 按下：填色提亮
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Ui.ACCENT)
            }
            return StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), pressed)
                addState(intArrayOf(), normal)
            }
        }

        private fun showRenameDialog() {
            val input = Ui.lineInput(activity, "蓝牙名称（≤248 字节）")
            val edit = input.tag as EditText
            edit.setText(dongleName)
            AppDialog.Builder(activity)
                .setTitle("修改蓝牙名称")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存") { _, _ ->
                    val name = edit.text.toString().trim()
                    when {
                        name.isEmpty() -> toast("名称不能为空，未修改")
                        name.toByteArray(Charsets.UTF_8).size > LiteConfig.MAX_USB_BT_NAME_BYTES ->
                            toast("名称超长（>${LiteConfig.MAX_USB_BT_NAME_BYTES} 字节），未修改")
                        else -> {
                            dongleName = name
                            activity.applyUsbBtDongleName(name)
                            toast("已改名「$name」，广播重启生效")
                            refresh(force = true)
                        }
                    }
                }
                .show()
        }

        // ---------- ② 已配对设备 ----------

        private fun buildPairedSection(column: LinearLayout, devices: List<PairedDevice>) {
            column.addView(sectionTitle("已配对设备", "${devices.size} 台"))
            if (devices.isEmpty()) {
                column.addView(emptyBox("无已配对设备，请配对"))
            } else {
                devices.forEachIndexed { index, dev ->
                    if (index > 0) column.addView(divider())
                    column.addView(pairedRow(dev))
                }
            }
        }

        private fun pairedRow(dev: PairedDevice): View {
            val displayName = dev.name ?: "未知设备"
            return LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                addView(TextView(activity).apply {
                    text = displayName.firstOrNull()?.toString() ?: "?"
                    setTextColor(0xFFCFE0FF.toInt())
                    textSize = 12f
                    setTypeface(typeface, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0xE61E2C4A.toInt())
                        setStroke(dp(1), Ui.LINE)
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
                })
                addView(
                    LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(TextView(activity).apply {
                            text = displayName
                            setTextColor(Ui.TEXT)
                            textSize = 13.5f
                            setTypeface(typeface, Typeface.BOLD)
                            maxLines = 1
                        })
                        addView(TextView(activity).apply {
                            text = "已配对 · 可连接　${dev.address}"
                            setTextColor(0xFF8FF0AE.toInt())
                            textSize = 11f
                            setPadding(0, dp(2), 0, 0)
                        })
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        leftMargin = dp(10)
                    },
                )
                addView(TextView(activity).apply {
                    text = "清除配对"
                    setTextColor(Ui.SUB)
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setPadding(dp(10), 0, dp(10), 0)
                    background = GradientDrawable().apply {
                        setColor(Color.TRANSPARENT)
                        cornerRadius = dp(6).toFloat()
                        setStroke(dp(1), Ui.BORDER)
                    }
                    setOnClickListener {
                        UsbBtStandby.clearPairing()
                        LiteLog.log("ui", "外置蓝牙：已清除配对（link key 删除，回到未配对）")
                        refresh(force = true)
                    }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(28)))
            }
        }

        // ---------- ③ 已发现设备（点击配对） ----------

        private fun buildDiscoveredSection(
            column: LinearLayout,
            devices: List<DiscoveredDevice>,
            pairedKeys: Set<String>,
            pairTarget: String?,
        ) {
            column.addView(sectionTitle("已发现设备（点击配对）", "${devices.size} 台"))
            if (devices.isEmpty()) {
                // vc238：总闸关闭时给出「不可被搜索」的真实原因，不再误导用户去开 iPhone 蓝牙。
                column.addView(
                    emptyBox(
                        if (!LiteConfig.usbBtKeepStandbyOf(activity)) {
                            "已关闭常驻广播，设备当前不可被搜索"
                        } else {
                            "未发现附近设备，请开启 iPhone 蓝牙并配对"
                        },
                    ),
                )
                return
            }
            devices.forEachIndexed { index, dev ->
                if (index > 0) column.addView(divider())
                column.addView(discoveredRow(dev, pairedKeys, pairTarget))
            }
        }

        private fun discoveredRow(dev: DiscoveredDevice, pairedKeys: Set<String>, pairTarget: String?): View {
            val key = dev.address.replace(":", "")
            val pairingThis = pairTarget?.equals(key, ignoreCase = true) == true
            val alreadyPaired = pairedKeys.any { it.equals(key, ignoreCase = true) }
            val displayName = dev.name ?: "未知设备"
            val meta = buildString {
                append(dev.address)
                dev.rssi?.let { append(" · ").append(it).append("dBm") }
            }
            return LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                addView(
                    LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(TextView(activity).apply {
                            text = displayName
                            setTextColor(Ui.TEXT)
                            textSize = 13.5f
                            setTypeface(typeface, Typeface.BOLD)
                            maxLines = 1
                        })
                        addView(TextView(activity).apply {
                            text = meta
                            setTextColor(Ui.SUB)
                            textSize = 11f
                            typeface = Typeface.MONOSPACE
                            setPadding(0, dp(2), 0, 0)
                        })
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(pill(
                    text = when {
                        pairingThis -> "配对中"
                        alreadyPaired -> "已配对"
                        else -> "可配对"
                    },
                    kind = when {
                        pairingThis -> 2 // 灰
                        alreadyPaired -> 0 // 绿
                        else -> 1 // 蓝
                    },
                ))
                if (!pairingThis && !alreadyPaired) {
                    setOnClickListener {
                        // 日志头段锚点：配对开始（固定文案，可 grep）。
                        LiteLog.log("anchor", "ANCHOR pair-begin")
                        val ok = UsbBtStandby.startPairing(dev.address)
                        LiteLog.log("ui", "外置蓝牙：点击已发现设备发起配对 addr=${dev.address} ok=$ok")
                        toast(if (ok) "已发起配对：$displayName" else "当前不可发起配对（需开着可被发现）")
                        refresh(force = true)
                    }
                }
            }
        }

        // ---------- ⑤ 设置组（竖屏默认折叠；横屏由左列直接平铺） ----------

        private fun buildSettingsGroup(
            column: LinearLayout,
            address: String?,
            info: String?,
            collapsible: Boolean,
        ) {
            column.addView(sectionTitle(
                "外置蓝牙设置",
                if (collapsible) (if (settingsExpanded) "▾ 收起" else "▸ 展开") else null,
            ).apply {
                if (collapsible) {
                    setOnClickListener {
                        settingsExpanded = !settingsExpanded
                        refresh(force = true)
                    }
                }
            })
            if (collapsible && !settingsExpanded) return
            column.addView(chipInfoBlock(address, info))
            column.addView(dongleLogBlock())
            column.addView(settingRow(
                "开机自动初始化", "上电即初始化，缩短首次等待；老车机可能拖慢启动。",
                recommendOn = false,
                checked = LiteConfig.usbBtAutoInitOf(activity),
            ) { v ->
                LiteConfig.setUsbBtAutoInit(activity, v)
                true
            })
            column.addView(settingRow(
                "优先用外置蓝牙广播", "连接优先走 dongle，减少与车机蓝牙抢设备。",
                recommendOn = true,
                checked = LiteConfig.usbBtPreferExternalOf(activity),
            ) { v ->
                LiteConfig.setUsbBtPreferExternal(activity, v)
                true
            })
            column.addView(keepStandbyRow())
        }

        /**
         * vc238：「保持常驻广播」= 外置蓝牙总闸（横竖屏共用，文案/行为只有这一份）。
         * 关闭 = 只关 inquiry scan（iPhone 搜不到）+ 保留 page scan（已配对 iPhone 仍可主动连上）；
         * 控制器占用中（活动链路/配对中/page 在飞）一律拒绝并让开关弹回，不抢流。
         */
        private fun keepStandbyRow(): View = settingRow(
            "保持常驻广播（关闭后设备不可被搜索）",
            "关闭只关「可被发现」：iPhone 搜索列表看不到本机，已配对设备仍可从手机蓝牙设置里主动连上。",
            recommendOn = true,
            checked = LiteConfig.usbBtKeepStandbyOf(activity),
        ) { want ->
            if (!UsbBtStandby.setDiscoverable(want)) {
                toast(if (want) "当前连接使用中，断开后再开启" else "当前连接使用中，断开后再关闭")
                false
            } else {
                LiteConfig.setUsbBtKeepStandby(activity, want)
                if (want) {
                    // 重新开播：未配对先续配对窗口，配对窗「已发现设备」随即恢复搜索。
                    if (UsbBtStandby.pairState == PairState.UNPAIRED) UsbBtStandby.enterPairingMode()
                    activity.ensureUsbBtStandby()
                }
                true
            }
        }

        /** 芯片/固件信息：ReadLocalVersion 实读 + BD_ADDR（mono）。 */
        private fun chipInfoBlock(address: String?, info: String?): View =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(2))
                addView(TextView(activity).apply {
                    text = "芯片/固件信息"
                    setTextColor(Ui.TEXT)
                    textSize = 13f
                })
                addView(TextView(activity).apply {
                    text = "${info ?: "读取中…"} · BD_ADDR ${address ?: "读取中…"}"
                    setTextColor(Ui.SUB)
                    textSize = 11f
                    typeface = Typeface.MONOSPACE
                    setPadding(0, dp(3), 0, 0)
                })
            }

        /** vc238：dongle 连接日志 —— 标题与[上传]同一行，下方只留状态行（无导出键/无说明文字）。
         *  上传中按钮冻结并置灰为「上传中…」；成功记上次上传时间；失败/超时给本地绝对路径。 */
        private fun dongleLogBlock(): View =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(2))
                val uploading = uploadState == UploadState.UPLOADING
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(activity).apply {
                        text = "dongle 连接日志"
                        setTextColor(Ui.TEXT)
                        textSize = 13f
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(logButton(if (uploading) "上传中" else "上传", enabled = !uploading) {
                        startUpload()
                    })
                })
                addView(TextView(activity).apply {
                    text = when (uploadState) {
                        UploadState.IDLE -> "尚未生成日志文件"
                        UploadState.UPLOADING -> "上传中… · ${uploadFileName.orEmpty()}"
                        UploadState.SUCCESS -> "上次上传 ${uploadTime.orEmpty()} · ${uploadFileName.orEmpty()}"
                        UploadState.FAIL -> "上传失败：${uploadDetail.orEmpty()}\n本地文件：${uploadFilePath.orEmpty()}"
                    }
                    setTextColor(
                        when (uploadState) {
                            UploadState.SUCCESS -> 0xFF8FF0AE.toInt()
                            UploadState.FAIL -> Ui.WARNING
                            else -> 0xFF7F91AB.toInt()
                        },
                    )
                    textSize = 10f
                    typeface = Typeface.MONOSPACE
                    setLineSpacing(0f, 1.3f)
                    setPadding(0, dp(5), 0, 0)
                })
            }

        /** vc238：只保留主按钮样式（导出键已删，次按钮样式无引用，一并去掉）。 */
        private fun logButton(
            text: String,
            enabled: Boolean,
            onClick: () -> Unit,
        ): View = TextView(activity).apply {
            this.text = if (!enabled) "$text…" else text
            textSize = 12.5f
            gravity = Gravity.CENTER
            setPadding(dp(9), 0, dp(9), 0)
            isEnabled = enabled
            if (!enabled) {
                setTextColor(0xFF7F91AB.toInt())
                background = GradientDrawable().apply {
                    setColor(0x852E3B52.toInt())
                    cornerRadius = dp(7).toFloat()
                }
            } else {
                setTextColor(Ui.PRIMARY_ON)
                background = GradientDrawable().apply {
                    setColor(Ui.ACCENT)
                    cornerRadius = dp(7).toFloat()
                }
            }
            if (enabled) setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(30))
        }

        /** 上传四态：点击即冻结按钮、后台上传；成功记上次上传时间；失败/超时给本地绝对路径。 */
        private fun startUpload() {
            if (uploadState == UploadState.UPLOADING) return
            val prepared = DongleLogUploader.prepare(activity)
            if (prepared == null) {
                toast("上传失败：暂无 dongle 日志")
                return
            }
            uploadState = UploadState.UPLOADING
            uploadFileName = prepared.fileName
            uploadDetail = null
            uploadFilePath = prepared.file.absolutePath
            refresh(force = true)
            Thread({
                val result = DongleLogUploader.upload(prepared)
                activity.runOnUiThread {
                    if (result.ok) {
                        uploadState = UploadState.SUCCESS
                        uploadFileName = result.remoteName ?: prepared.fileName
                        uploadDetail = null
                        uploadFilePath = prepared.file.absolutePath
                        uploadTime = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                        toast("上传成功：$uploadFileName")
                    } else {
                        uploadState = UploadState.FAIL
                        uploadDetail = result.error.orEmpty().ifEmpty { "超时或网络不可用" }
                        uploadFilePath = prepared.file.absolutePath
                        // 已删「导出」键：失败时必须给出可被用户取用的真实绝对路径。
                        toast("上传失败（$uploadDetail）\n本地文件：$uploadFilePath")
                    }
                    refresh(force = true)
                }
            }, "dongle-log-upload").start()
        }

        /** 设置开关行：名称 + 建议 tag + ToggleView；下挂说明。
         *  vc238：onChange 返回 false = 拒绝本次切换（开关弹回原状态），用于总闸占用中保护。 */
        private fun settingRow(
            name: String,
            hint: String,
            recommendOn: Boolean,
            checked: Boolean,
            onChange: (Boolean) -> Boolean,
        ): View = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(9), 0, dp(2))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(activity).apply {
                    text = name
                    setTextColor(Ui.TEXT)
                    textSize = 13f
                })
                addView(TextView(activity).apply {
                    text = if (recommendOn) "建议开" else "建议关"
                    textSize = 9.5f
                    setTextColor(if (recommendOn) 0xFF52C86C.toInt() else 0xFF7E93B3.toInt())
                    gravity = Gravity.CENTER
                    setPadding(dp(5), 0, dp(5), 0)
                    background = GradientDrawable().apply {
                        setColor(Color.TRANSPARENT)
                        cornerRadius = dp(4).toFloat()
                        setStroke(dp(1), if (recommendOn) 0x6652C86C else 0x667E93B3)
                    }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(16)).apply {
                    leftMargin = dp(6)
                })
                addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
                addView(ToggleView(activity).apply {
                    setCheckedSilent(checked)
                    onCheckedChange = { v ->
                        if (onChange(v)) {
                            LiteLog.log("ui", "外置蓝牙设置：$name → ${if (v) "开" else "关"}")
                            refresh(force = true)
                        } else {
                            // 拒绝：弹回原状态，绝不留在「界面关了、实际还开着」的假状态。
                            setCheckedSilent(!v)
                            LiteLog.log("ui", "外置蓝牙设置：$name 切换被拒绝（控制器占用中），已弹回")
                        }
                    }
                })
            })
            addView(TextView(activity).apply {
                text = hint
                setTextColor(Ui.SUB)
                textSize = 11f
                setLineSpacing(0f, 1.4f)
                setPadding(0, dp(3), 0, 0)
            })
        }

        // ---------- 小组件 ----------

        private fun sectionTitle(title: String, trailing: String?): View =
            LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                setPadding(0, dp(13), 0, dp(3))
                addView(TextView(activity).apply {
                    text = title
                    setTextColor(Ui.SUB)
                    textSize = 12f
                    setTypeface(typeface, Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                if (trailing != null) {
                    addView(TextView(activity).apply {
                        text = trailing
                        setTextColor(0xFF7F91AB.toInt())
                        textSize = 10f
                    })
                }
            }

        private fun emptyBox(text: String): View = TextView(activity).apply {
            this.text = text
            setTextColor(0xFF7F91AB.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(15), dp(8), dp(15))
            background = GradientDrawable().apply {
                setColor(0x380C111B)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), 0xE62A384B.toInt(), dp(4).toFloat(), dp(3).toFloat()) // 虚线
            }
        }

        private fun divider(): View = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(Ui.LINE)
        }

        /** 「可配对/配对中/已配对」pill：0=绿 1=蓝 2=灰。 */
        private fun pill(text: String, kind: Int): View {
            val (bg, border, fg) = when (kind) {
                0 -> Triple(0x1F34C759, 0x5C34C759, 0xFF8FF0AE.toInt())
                1 -> Triple(0x21A6C8FF, 0x52A6C8FF, 0xFFCFE0FF.toInt())
                else -> Triple(0x2478869C, 0x5778869C, 0xFFA9B6C8.toInt())
            }
            return TextView(activity).apply {
                this.text = text
                setTextColor(fg)
                textSize = 10.5f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(bg)
                    cornerRadius = dp(999).toFloat()
                    setStroke(dp(1), border)
                }
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(24))
            }
        }

        /** 状态 chip（名称行右侧）：PAIRED 绿 / PAIRING 蓝 / UNPAIRED 灰。 */
        private fun chip(state: PairState): View {
            val (kind, text) = when (state) {
                PairState.PAIRED -> 0 to "已配对"
                PairState.PAIRING -> 1 to "配对中"
                PairState.UNPAIRED -> 2 to "待配对"
            }
            return pill(text, kind)
        }

        /** 蓝牙图标（设计稿 SVG path 的 Canvas 版；34dp 圆角底 + ACCENT 描线）。 */
        private fun iconBox(): View {
            val box = dp(34)
            val glyph = dp(15)
            val bmp = Bitmap.createBitmap(glyph, glyph, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val scale = glyph / 24f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Ui.ACCENT
                style = Paint.Style.STROKE
                strokeWidth = 1.9f * scale
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val path = Path().apply {
                // 设计稿 path：M7 7l10 10-5 4V3l5 4L7 17
                moveTo(7f * scale, 7f * scale)
                lineTo(17f * scale, 17f * scale)
                lineTo(12f * scale, 21f * scale)
                lineTo(12f * scale, 3f * scale)
                lineTo(17f * scale, 7f * scale)
                lineTo(7f * scale, 17f * scale)
            }
            canvas.drawPath(path, paint)
            return ImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(box, box)
                setImageDrawable(BitmapDrawable(activity.resources, bmp))
                background = GradientDrawable().apply {
                    setColor(0x1AA6C8FF) // ACCENT 10%
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), 0x38A6C8FF) // ACCENT 22%
                }
            }
        }

        // ---------- 工具 ----------

        private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

        private fun toast(message: String) {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * vc238：横屏列内滚动视口。
 *
 * API16（Android 4.1）的 ScrollView 没有嵌套滚动协商：4.1.2 的 onInterceptTouchEvent 只看
 * 手指竖直位移是否超过 touchSlop，**不判断自身是否真有可滚距离**，一旦超过就拦截，并给
 * 内层发 ACTION_CANCEL。本弹层的内容又包在 AppDialog 的限高 ScrollView 里，于是内层列
 * ScrollView 每轮手势刚动几像素就被祖先抢走 —— 表现为「右列滑不动、只能看到上半段」。
 *
 * 解法：ACTION_DOWN 时若本列内容确实超出一屏，就向所有祖先声明「本轮手势由内层处理」
 * （ViewParent.requestDisallowInterceptTouchEvent 自 API1 起可用）；抬起/取消时归还。
 * 内容不足一屏则不声明，垂直拖动仍交给外层，不改变原有行为。
 */
private class ColumnScrollView(context: Context) : ScrollView(context) {
    /** 列内容重排/视口变化后回调（用于重算「▼ 还能滑」提示）。 */
    var onViewportChanged: (() -> Unit)? = null

    /** 滚动位置变化回调（用于「▼ 还能滑」提示随滚动隐藏/复现）。 */
    var onScrollChangedListener: (() -> Unit)? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // 必须在 onInterceptTouchEvent（而不是 onTouchEvent）里声明：
        // 手指按在列内的可点击子控件（开关/清除配对等）时，DOWN 会被子控件消费、不会到本层
        // onTouchEvent，但本层的 onInterceptTouchEvent 一定先于子控件派发被调用。
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val content = getChildAt(0)
                if (content != null && content.height > height) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        onScrollChangedListener?.invoke()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val notify = onViewportChanged ?: return
        // 布局期只登记回调，等本次布局结束再改提示可见性。
        post { notify() }
    }
}
