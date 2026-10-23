package com.shilapi.xcertplay.usbbt

/**
 * vc181（外置 USB 蓝牙 C/D 批）：L2CAP 服务（按 PSM 注册的通道开 factory）。
 * 移植自上游参考实现 usbbt.L2capService。
 */
internal interface L2capService {
    /** 通道双向配置齐了之后回调；返回 null 视为拒服务（通道随即发 DISCONNECT）。 */
    fun open(channel: L2capLayer.Channel): L2capChannelHandler?
}
