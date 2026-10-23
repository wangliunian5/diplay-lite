package com.shilapi.xcertplay.usbbt

/**
 * vc181（外置 USB 蓝牙 C/D 批）：L2CAP 通道回调。
 * 移植自上游参考实现 usbbt.L2capChannelHandler。
 */
internal interface L2capChannelHandler {
    fun onClosed(channel: L2capLayer.Channel)

    fun onData(channel: L2capLayer.Channel, data: ByteArray)
}
