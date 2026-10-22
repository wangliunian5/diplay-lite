package com.shilapi.diplay.lite

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 热点配置页（M6k14，渲染图 3/4；M6k25 单 Activity 化为 page="hotspot-car"/"hotspot-relay"）：
 * 一个页面对象双模式。
 *
 * mode=car   → 车机热点配置：读写 LiteConfig.ssid/password + carIpv6/carIpv4 信道
 * mode=relay → 中继热点配置：读写 relaySsid/relayPassword（随身WiFi 凭据）+ relayIpv6/relayIpv4
 * 两套信道各自独立落盘；蓝牙选择为全局一份（两模式共用 LiteConfig.bluetoothAddress）。
 * 中继链路的连接编排依赖 GLM 的中继热点方案，本页只负责配置。
 *
 * M6k25 迁移要点：原 Activity 上下文改为构造传入的 MainActivity；原 onPause 的 saveFromForm
 * 收口到 [cleanup]（页面离开/重建时调用）；返回（原 finish()）与齿轮都回设置页。
 */
class HotspotPage(private val activity: MainActivity, private val mode: String) {

    private lateinit var nameInput: EditText
    private lateinit var passwordInput: EditText
    // M6k19：双栈信道改为点击弹选择框（自动 + 1..13），选定即生效。
    private var ipv6ChannelValue = 0
    private var ipv4ChannelValue = 0
    private lateinit var ipv6ValueText: TextView
    private lateinit var ipv4ValueText: TextView
    private lateinit var btSummary: TextView

    // M6k3：蓝牙点选的当前值。地址落盘 LiteConfig.bt_address（连接时用），名字只用于展示。
    private var bluetoothAddress = ""
    private var bluetoothName = ""

    // pillButton 风格码：0=描边蓝 / 1=实心蓝 / 2=实心灰
    private val STYLE_OUTLINE = 0
    private val STYLE_PRIMARY = 1
    private val STYLE_GRAY = 2

    // 1.0.13：两栈信道列表拆分。IPV4=自动+2.4GHz 0..13；IPV6=自动+5GHz 常用信道。
    // 推荐信道追加「(推荐)」标记：2.4G=1/6/11（非重叠），5G=36/44/149/157（非DFS主流）。
    private val CHANNEL_OPTIONS_IPV4 = listOf(0) + (1..13).toList()
    private val CHANNEL_OPTIONS_IPV6 =
        listOf(0, 36, 40, 44, 48, 149, 153, 157, 161, 165)
    private val RECOMMENDED_IPV4 = setOf(1, 6, 11)
    private val RECOMMENDED_IPV6 = setOf(36, 44, 149, 157)

    /** 页面离开时调用（原 onPause）：表单落盘。 */
    fun cleanup() {
        saveFromForm()
    }

    fun buildRoot(): View {
        applyOrientationMode(activity, LiteConfig.load(activity).orientationMode)
        val root = FrameLayout(activity).apply { setBackgroundColor(Ui.BG) }
        root.addView(buildContent())
        // M6k23：悬浮球所有画面常驻 —— 右上角球点击返回主页。
        attachPageBall(activity, root) { activity.setPage("settings") }
        loadIntoForm()
        LiteLog.log("ui", "热点配置页打开：$mode")
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
        column.addView(sectionLabel("配置参数"), block(top = dip(8), bottom = dip(4)))
        column.addView(buildCredentialRow(), block())
        column.addView(
                buttonRow(
                    pillButton("保存", STYLE_OUTLINE) {
                        saveFromForm()
                        toast("已保存")
                    },
                    // 1.1.1 UI 修正：车机页配置的就是车机热点 → 跳系统热点设置（原误为 WiFi 跳转）；
                    // 中继页跳系统 Wi-Fi（随身 WiFi 也在此页连接）。
                    if (isCar) {
                        jumpButton("系统热点跳转") { openHotspotSettings() }
                    } else {
                        jumpButton("系统WiFi跳转") { openWifiSettings() }
                    },
                ),
                block(top = dip(10)),
            )
        column.addView(divider(), block(top = dip(14)))
        column.addView(sectionLabel("蓝牙信息"), block(top = dip(12), bottom = dip(4)))
        btSummary = TextView(activity).apply {
            setTextColor(Ui.SUB)
            textSize = 14f
        }
        column.addView(btSummary, block(bottom = dip(10)))
        column.addView(
            buttonRow(
                jumpPrimaryButton("蓝牙选择") { showBluetoothDeviceDialog() },
                jumpButton("系统蓝牙跳转") { openBluetoothSettings() },
            ),
            block(),
        )
        column.addView(divider(), block(top = dip(14)))
        column.addView(sectionLabel("热点信道选择"), block(top = dip(12), bottom = dip(4)))
        column.addView(buildChannelRows(), block())
        return ScrollView(activity).apply {
            addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private val isCar: Boolean get() = mode == MODE_CAR

    private fun buildHeader(): View {
        val title = TextView(activity).apply {
            text = if (isCar) "车机热点配置" else "中继热点配置"
            setTextColor(Ui.TEXT)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        }
        val gear = ImageView(activity).apply {
            setImageDrawable(IconPainter.gear(activity, 22, Ui.TEXT))
            setPadding(dip(8), dip(8), 0, dip(8))
            setOnClickListener { activity.setPage("settings") }
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                ImageView(activity).apply {
                    setImageDrawable(IconPainter.back(activity, 22, Ui.TEXT))
                    setPadding(0, dip(8), dip(12), dip(8))
                    setOnClickListener { activity.setPage("settings") }
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
            addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(gear, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    /** 名称/密码两列下划线输入（图3/4）。 */
    private fun buildCredentialRow(): View {
        val nameColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(fieldLabel(if (isCar) "车机热点名称" else "中继热点名称"))
            val nameBox = Ui.lineInput(activity, "请输入热点名称")
            nameInput = nameBox.tag as EditText
            addView(nameBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val passwordColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(fieldLabel("热点密码"))
            val passwordBox = Ui.lineInput(activity, "请输入密码")
            passwordInput = passwordBox.tag as EditText
            addView(passwordBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(nameColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dip(12) })
            addView(passwordColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    /**
     * 双栈首选信道（M6k19 重做）：IPV6 / IPV4 各一行，点击弹「自动 + 可选信道」选择框，选定即落盘生效。
     * 可选信道 = 2.4GHz 1..13（两栈同列表，0=自动）。
     */
    private fun buildChannelRows(): View {
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val v6Row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dip(10), 0, dip(10))
            addView(fieldLabelInline("IPV6首选信道"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            ipv6ValueText = TextView(activity).apply {
                setTextColor(Ui.TEXT)
                textSize = 14f
                text = channelLabel(ipv6ChannelValue)
            }
            addView(ipv6ValueText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            setOnClickListener {
                pickChannel("IPV6首选信道", ipv6ChannelValue, CHANNEL_OPTIONS_IPV6, RECOMMENDED_IPV6) { picked ->
                    ipv6ChannelValue = picked
                }
            }
        }
        val v4Row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dip(10), 0, dip(10))
            addView(fieldLabelInline("IPV4首选信道"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            ipv4ValueText = TextView(activity).apply {
                setTextColor(Ui.TEXT)
                textSize = 14f
                text = channelLabel(ipv4ChannelValue)
            }
            addView(ipv4ValueText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            setOnClickListener {
                pickChannel("IPV4首选信道", ipv4ChannelValue, CHANNEL_OPTIONS_IPV4, RECOMMENDED_IPV4) { picked ->
                    ipv4ChannelValue = picked
                }
            }
        }
        column.addView(v6Row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dip(4) })
        column.addView(v4Row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dip(6) })
        return column
    }

    private fun channelLabel(channel: Int): String = if (channel > 0) channel.toString() else "自动"

    /** 选择框行标签：推荐信道追加「(推荐)」。 */
    private fun channelOptionLabel(channel: Int, recommended: Set<Int>): String =
        channelLabel(channel) + if (channel in recommended) "（推荐）" else ""

    /** 信道选择框：IPV4=自动+1..13，IPV6=自动+5G常用信道；单选定即落盘（saveFromForm 覆盖本页字段）并刷新行值。 */
    private fun pickChannel(title: String, current: Int, options: List<Int>, recommended: Set<Int>, onPicked: (Int) -> Unit) {
        val labels = options.map { channelOptionLabel(it, recommended) }.toTypedArray()
        val checked = options.indexOf(current).coerceAtLeast(0)
        AppDialog.Builder(activity)
            .setTitle(title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val picked = options.getOrNull(which) ?: 0
                onPicked(picked)
                ipv6ValueText.text = channelLabel(ipv6ChannelValue)
                ipv4ValueText.text = channelLabel(ipv4ChannelValue)
                saveFromForm()
                LiteLog.log("ui", "$title 选定：${channelLabel(picked)}（即时生效）")
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun sectionLabel(text: String): TextView = TextView(activity).apply {
        this.text = text
        setTextColor(Ui.TEXT)
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun fieldLabel(text: String): TextView = TextView(activity).apply {
        this.text = text
        setTextColor(Ui.SUB)
        textSize = 13f
        setPadding(0, 0, 0, dip(2))
    }

    private fun fieldLabelInline(text: String): TextView = TextView(activity).apply {
        this.text = text
        setTextColor(Ui.TEXT)
        textSize = 15f
    }

    private fun divider(): View = View(activity).apply { setBackgroundColor(Ui.LINE) }

    private fun block(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = top
            bottomMargin = bottom
        }

    /** 普通胶囊：0=描边蓝 / 1=实心蓝 / 2=实心灰；1 号支持图标角标。 */
    private fun pillButton(text: String, style: Int, startIcon: android.graphics.drawable.Drawable? = null, endIcon: android.graphics.drawable.Drawable? = null, onClick: () -> Unit): android.widget.Button =
        android.widget.Button(activity).apply {
            this.text = text
            textSize = 15f
            minHeight = dip(42)
            setAllCaps(false)
            when (style) {
                STYLE_OUTLINE -> {
                    background = Ui.pillOutline(activity, Ui.OUTLINE)
                    setTextColor(Ui.OUTLINE)
                }
                STYLE_PRIMARY -> {
                    background = Ui.pillSolid(activity, Ui.PRIMARY)
                    setTextColor(Ui.PRIMARY_ON)
                }
                else -> {
                    background = Ui.pillSolid(activity, Ui.GRAYBTN)
                    setTextColor(android.graphics.Color.WHITE)
                }
            }
            setCompoundDrawablesWithIntrinsicBounds(startIcon, null, endIcon, null)
            setPadding(dip(14), dip(6), dip(14), dip(6))
            setOnClickListener { onClick() }
        }

    private fun jumpPrimaryButton(text: String, onClick: () -> Unit): android.widget.Button =
        pillButton(text, STYLE_PRIMARY, startIcon = IconPainter.bluetooth(activity, 14, Ui.BG), onClick = onClick)

    private fun jumpButton(text: String, onClick: () -> Unit): android.widget.Button =
        pillButton(text, STYLE_GRAY, endIcon = IconPainter.upRight(activity, 10, Ui.SUB), onClick = onClick)

    private fun buttonRow(vararg buttons: android.widget.Button): LinearLayout {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.forEach { button ->
            row.addView(
                button,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dip(4)
                    rightMargin = dip(4)
                },
            )
        }
        return row
    }

    // ---------- 表单读写 ----------

    private fun loadIntoForm() {
        val config = LiteConfig.load(activity)
        if (isCar) {
            nameInput.setText(config.ssid)
            passwordInput.setText(config.password)
            ipv6ChannelValue = config.carIpv6Channel
            ipv4ChannelValue = config.carIpv4Channel
        } else {
            nameInput.setText(config.relaySsid)
            passwordInput.setText(config.relayPassword)
            ipv6ChannelValue = config.relayIpv6Channel
            ipv4ChannelValue = config.relayIpv4Channel
        }
        ipv6ValueText.text = channelLabel(ipv6ChannelValue)
        ipv4ValueText.text = channelLabel(ipv4ChannelValue)
        bluetoothAddress = config.bluetoothAddress
        bluetoothName = LiteConfig.bluetoothDeviceName(activity)
        refreshBluetoothSummary()
    }

    /** copy 合并落盘：只覆写本模式管辖的字段（M6k13 纪律沿用，不踩别的页面的字段）。 */
    private fun saveFromForm() {
        if (!::nameInput.isInitialized) return
        val current = LiteConfig.load(activity)
        val v6 = ipv6ChannelValue
        val v4 = ipv4ChannelValue
        val config = if (isCar) {
            current.copy(
                ssid = nameInput.text.toString().trim(),
                password = passwordInput.text.toString(),
                carIpv6Channel = v6,
                carIpv4Channel = v4,
            )
        } else {
            current.copy(
                relaySsid = nameInput.text.toString().trim(),
                relayPassword = passwordInput.text.toString(),
                relayIpv6Channel = v6,
                relayIpv4Channel = v4,
            )
        }
        LiteConfig.save(activity, config)
    }

    // ---------- 蓝牙选择（M6k3/M6k4 自主页迁移） ----------

    private fun refreshBluetoothSummary() {
        btSummary.text = when {
            bluetoothAddress.isBlank() -> "未选择（自动匹配已配对 iPhone）"
            bluetoothName.isNotBlank() -> "已选择 $bluetoothName ${bluetoothAddress}"
            else -> "已选择 $bluetoothAddress"
        }
    }

    private fun applyBluetoothPick(address: String, name: String) {
        bluetoothAddress = address
        bluetoothName = name
        refreshBluetoothSummary()
        LiteConfig.saveBluetoothPick(activity, address, name)
    }

    /** M6k4：已配对设备列表（名字+MAC 对照，同名机靠 MAC 区分）+ 手动输入 + 清除。M6k24 起与主页共用 SystemSettingsJumps。 */
    private fun showBluetoothDeviceDialog() {
        SystemSettingsJumps.showIphonePickerDialog(activity, ::applyBluetoothPick)
    }

    // ---------- 系统设置直达（M6k24 起共用 SystemSettingsJumps，三级降级行为不变） ----------

    private fun openHotspotSettings() = SystemSettingsJumps.openHotspotSettings(activity)

    private fun openBluetoothSettings() = SystemSettingsJumps.openBluetoothSettings(activity)

    private fun openWifiSettings() = SystemSettingsJumps.openWifiSettings(activity)

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val MODE_CAR = "car"
        const val MODE_RELAY = "relay"
    }
}
