package com.shilapi.diplay.lite

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/**
 * DiPlay Lite 主界面：单屏程序化 UI（零 XML 资源、零 AndroidX、无图片）。
 *
 * M0 骨架阶段：热点表单 + 服务启停 + 状态/日志显示；
 * 连接编排（shared 模块 CarPlayController）在 M2 接入 LiteSessionService。
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
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button

    private val refreshTask = object : Runnable {
        override fun run() {
            renderState()
            if (!isFinishing) handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        loadIntoForm()
        renderState()
        LiteLog.log("ui", "DiPlay Lite M0 骨架就绪（minSdk 16）")
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshTask)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshTask)
    }

    // ---------- UI 构造（纯代码，无 XML） ----------

    private fun buildUi(): View {
        val pad = dip(12)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(BG)
        }
        scroll.addView(root)

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

        return scroll
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

    // ---------- 表单读写 ----------

    private fun loadIntoForm() {
        val config = LiteConfig.load(this)
        ssidInput.setText(config.ssid)
        passwordInput.setText(config.password)
        channelInput.setText(if (config.channel > 0) config.channel.toString() else "")
        val securities = listOf("WPA2", "WPA2-WPA3", "WPA3", "OPEN")
        securityInput.setSelection(securities.indexOf(config.security).coerceAtLeast(0))
        bluetoothInput.setText(config.bluetoothAddress)
    }

    private fun saveFromForm(): LiteConfig {
        val channelText = channelInput.text.toString().trim()
        val config = LiteConfig(
            ssid = ssidInput.text.toString().trim(),
            password = passwordInput.text.toString(),
            channel = channelText.toIntOrNull() ?: 0,
            security = securityInput.selectedItem as? String ?: "WPA2",
            bluetoothAddress = bluetoothInput.text.toString().trim(),
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
        LiteLog.log("ui", "发起连接：ssid=${config.ssid} channel=${config.channel} security=${config.security}")
        startService(Intent(this, LiteSessionService::class.java))
        toast("已启动会话服务")
    }

    private fun disconnect() {
        stopService(Intent(this, LiteSessionService::class.java))
        LiteLog.log("ui", "用户断开")
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
        val sb = StringBuilder()
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
