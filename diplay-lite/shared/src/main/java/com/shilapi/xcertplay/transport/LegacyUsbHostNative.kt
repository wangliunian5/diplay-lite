package com.shilapi.xcertplay.transport

/**
 * vc101（T3）：usbfs ioctl 兜底层（借鉴 legacy-ref LegacyUsbHostNative）。
 * NDK 独立编译 usb_host_compat.c → libusb_host_compat.so（app jniLibs），
 * 全部操作作用于 App 已授权的 UsbDeviceConnection fd，无需 root。
 * KK(API19) 兼容：纯 C、无 libc++、API 19 目标编译。
 * 首次访问 [available] 时加载；加载失败 available=false，调用侧跳过 native 路径。
 */
internal object LegacyUsbHostNative {
    val available: Boolean by lazy {
        runCatching { System.loadLibrary("usb_host_compat") }.isSuccess
    }

    /** DISCONNECT_CLAIM 原子断驱动+claim；返回 0 成功，否则 errno。 */
    external fun forceClaim(fileDescriptor: Int, interfaceId: Int): Int

    /** USBDEVFS_SETINTERFACE 切 alt；返回 0 成功，否则 errno。 */
    external fun setInterface(fileDescriptor: Int, interfaceId: Int, alternateSetting: Int): Int

    /** USBDEVFS_SETCONFIGURATION 切配置（EBUSY 时自动踢内核驱动重试）；返回 0 成功。 */
    external fun setConfiguration(fileDescriptor: Int, configurationId: Int): Int

    /** vc103f（T3）：裸 URB 探测——向端点地址直接提交 1 字节 bulk IN 验证内核端点表。
     *  返回 0=内核接受（端点可用），>0=拒绝码（EINVAL/ENOENT=端点表残缺实锤）。 */
    external fun submitUrbProbe(fileDescriptor: Int, endpointAddress: Int): Int

    /** vc103g（T3 终审）：USBDEVFS_RESET 强制内核完全重枚举——sunxi KK 描述符
     *  半解析的唯一修复。RESET 后 fd 上的设备状态失效，调用方须重开连接。 */
    external fun portReset(fileDescriptor: Int): Int

    /** vc103l（T3）：USBDEVFS_RELEASEINTERFACE 释放本 fd 的 usbfs claim——
     *  KK proc_setconfig 在有 usbfs claim 时拒绝切配置（EBUSY=16 实测）。 */
    external fun releaseInterface(fileDescriptor: Int, interfaceNumber: Int): Int

    /** vc189（T3）：直接走 USBDEVFS_CONTROL ioctl 下发 CarPlay 厂商配置请求（0x52），
     *  失败时原样返回内核 errno（Android 框架 controlTransfer 把它吞成不透明 -1）。
     *  约定：返回 >=0 = 实际传输字节数；返回 <0 = -(errno)。 */
    external fun controlTransferErrno(
        fileDescriptor: Int,
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        data: ByteArray,
        length: Int,
        timeoutMs: Int,
    ): Int
}
