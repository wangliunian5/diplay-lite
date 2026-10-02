package com.shilapi.xcertplay.transport

import java.io.IOException

/**
 * iAP2 会话通用异常（Lite：从 IphoneUsbHost.kt 抽出独立保留）。
 * 历史命名含 "Usb"，但无线（蓝牙 RFCOMM）链路的识别/控制/会话建立同样使用本异常。
 */
sealed class IphoneUsbException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class PermissionDenied(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class DeviceUnavailable(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class TimedOut(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class Protocol(message: String) : IphoneUsbException(message)
}
