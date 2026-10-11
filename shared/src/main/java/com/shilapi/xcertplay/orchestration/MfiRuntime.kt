package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.mfi.MfiAuthenticator
import java.io.Closeable

/**
 * An opened MFi authenticator plus the handle that releases its backing transport.
 *
 * Lite：MFiRuntime 扫描器（I2C 地址探测）随 CH341/I2C 后端一并裁剪，
 * LOCAL 离线证书路径由 CarPlayController 直接构造本会话。
 */
class MfiSession(
    val client: MfiAuthenticator,
    private val closeable: Closeable?,
) : Closeable {
    override fun close() {
        closeable?.close()
    }
}
