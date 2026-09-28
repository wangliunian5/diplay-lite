package com.shilapi.xcertplay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shilapi.xcertplay.mfi.MfiProtocolMajorResult
import com.shilapi.xcertplay.mfi.MfiSelfCheck
import com.shilapi.xcertplay.mfi.MfiSelfCheckResult
import com.shilapi.xcertplay.transport.LinuxI2cTransport
import com.shilapi.xcertplay.ui.theme.XcertplayTheme
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var status by mutableStateOf<DiagnosticStatus>(DiagnosticStatus.Idle)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            XcertplayTheme {
                var devicePath by remember { mutableStateOf("/dev/i2c-1") }
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    Column(
                        modifier = Modifier.padding(padding).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("板载 I2C 诊断")
                        OutlinedTextField(
                            value = devicePath,
                            onValueChange = { devicePath = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Linux I2C 设备") },
                            singleLine = true,
                            enabled = status !is DiagnosticStatus.Running,
                        )
                        Button(
                            onClick = { runSelfCheck(devicePath) },
                            enabled = status !is DiagnosticStatus.Running,
                        ) {
                            Text("运行 MFi 自检")
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(status.message())
                        Text("CH341 需要针对部署配置特定的 VID/PID。")
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun runSelfCheck(devicePath: String) {
        status = DiagnosticStatus.Running
        executor.execute {
            val next = try {
                LinuxI2cTransport.open(devicePath).use { MfiSelfCheck(it).run() }
                    .let { DiagnosticStatus.Result(it) }
            } catch (error: LinkageError) {
                DiagnosticStatus.Failure(error.message ?: "I2C 原生库不可用")
            } catch (error: Exception) {
                DiagnosticStatus.Failure(error.message ?: error.javaClass.simpleName)
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) status = next
            }
        }
    }
}

private sealed class DiagnosticStatus {
    data object Idle : DiagnosticStatus()
    data object Running : DiagnosticStatus()
    data class Result(val selfCheck: MfiSelfCheckResult) : DiagnosticStatus()
    data class Failure(val message: String) : DiagnosticStatus()

    fun message(): String = when (this) {
        Idle -> "空闲"
        Running -> "运行中…"
        is Failure -> "失败：$message"
        is Result -> {
            val chip = selfCheck.chip ?: return if (selfCheck.discovery.interrupted) {
                "MFi 扫描被中断"
            } else {
                "未发现设备"
            }
            val major = when (val result = chip.protocolMajor) {
                is MfiProtocolMajorResult.Value -> "%d".format(result.major)
                is MfiProtocolMajorResult.MfiFailure -> result.error.message ?: result.error.javaClass.simpleName
                is MfiProtocolMajorResult.TransportFailure -> result.error.message ?: result.error.javaClass.simpleName
            }
            "发现：0x%02X；设备版本：0x%02X；协议主版本（原始）：%s".format(
                chip.address7Bit,
                chip.deviceVersion,
                major,
            )
        }
    }
}
