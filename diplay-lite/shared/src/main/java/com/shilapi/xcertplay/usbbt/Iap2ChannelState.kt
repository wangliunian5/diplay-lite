package com.shilapi.xcertplay.usbbt

/**
 * vc181（外置 USB 蓝牙 C/D 批）：iAP2 通道三态。
 * 移植自上游参考实现 usbbt.Iap2ChannelState（反编译恢复失败，按噪声清洗规则直落枚举）。
 */
internal enum class Iap2ChannelState {
    NONE,
    OPENING,
    OPEN,
}
