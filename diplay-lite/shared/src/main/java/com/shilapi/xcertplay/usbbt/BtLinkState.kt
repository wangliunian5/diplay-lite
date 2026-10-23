package com.shilapi.xcertplay.usbbt

/**
 * vc181（外置 USB 蓝牙 C/D 批）：基带链路状态。
 * 移植自上游参考实现 usbbt.BtLinkState（反编译恢复失败，按语义直写）。
 */
enum class BtLinkState {
    NONE,
    PAGING,
    UNENCRYPTED,
    ENCRYPTED,
}
