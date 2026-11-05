package com.shilapi.xcertplay.usbbt

/**
 * vc246（USB-246-07）：USB port reset 计数（仅观测，不参与判定）。
 *
 * vc246 起 KK 常态不再每次 open 都 `USBDEVFS_RESET`，只保留两处一次性救援：
 * - `UsbBtBootstrapStream` 首轮 init 超时后的一次性 `portResetFirst` 兜底；
 * - `NcmUsbBridge` 有线路径的端点探针兜底（与 dongle 路径无关）。
 *
 * `UsbDongleGuard.softReset()`（sysfs authorized 翻转）**不计入**：它不是 port reset，
 * 且只走掉线重连路径，日志里单独标注。
 */
object UsbBtResetCounter {
    @Volatile
    var portResets: Int = 0
        private set

    fun note(source: String): Int = synchronized(this) {
        portResets += 1
        portResets
    }
}
