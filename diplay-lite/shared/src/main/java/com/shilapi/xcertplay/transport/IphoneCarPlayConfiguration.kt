package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.os.Build
import android.util.Log

/**
 * Descriptor-based discovery of the iPhone's CarPlay configuration.
 *
 * Configuration ids differ between iPhone models, so the configuration is identified by its
 * interfaces: Apple USB Multiplexor (USBMUX) plus the NCM/Ethernet function CarPlay uses.
 */
object IphoneCarPlayConfiguration {
    // Lite 保留原 TAG（controller 的 debugLog 直接用它过滤日志流）。
    const val TAG = "xcertplay"

    private const val USBMUX_CLASS = 0xff
    private const val USBMUX_SUBCLASS = 0xfe
    private const val USBMUX_PROTOCOL = 0x02
    private const val APPLE_ETHERNET_CLASS = 0xff
    private const val APPLE_ETHERNET_SUBCLASS = 0xfd
    private const val APPLE_ETHERNET_PROTOCOL = 0x01
    private const val NCM_CONTROL_CLASS = 0x02
    private const val NCM_CONTROL_SUBCLASS = 0x0d
    private const val PREFERRED_USBMUX_OUT = 0x04
    private const val PREFERRED_USBMUX_IN = 0x85

    /** API16 适配：UsbDevice.getConfiguration/UsbConfiguration 都是 API 21+。 */
    fun find(device: UsbDevice): UsbConfiguration? {
        // <21 无法枚举多配置（NoSuchMethodError），返回 null 由调用方走 legacy 扫描兜底。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
        val configurations = (0 until device.configurationCount).map(device::getConfiguration)
        val chosen = configurations.firstOrNull { usbMuxInterface(it) != null && hasCdcNcm(it) && hasAppleEthernet(it) }
            ?: configurations.firstOrNull { usbMuxInterface(it) != null && hasCdcNcm(it) }
        Log.i(
            TAG,
            "carplay config chosen=${chosen?.id} " +
                "available=${configurations.map { it.id }} detail=${chosen?.let(::describe)}",
        )
        return chosen
    }

    fun describe(configuration: UsbConfiguration): String =
        (0 until configuration.interfaceCount).joinToString(",") { index ->
            describeInterface(configuration.getInterface(index))
        }

    /**
     * 诊断 dump：整台设备的全部配置/接口（API21+）。用于排查重枚举后 iPhone
     * 到底以什么形态回来（CarPlay 配置缺失时调用方把它打进 debugLog）。
     */
    fun describeDevice(device: UsbDevice): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return "legacy-interfaces=" + deviceInterfaces(device).joinToString(",") { describeInterface(it) }
        }
        return (0 until device.configurationCount).joinToString(";") { index ->
            val configuration = device.getConfiguration(index)
            "cfg${configuration.id}[" + describe(configuration) + "]"
        }
    }

    private fun describeInterface(usbInterface: UsbInterface): String =
        // UsbInterface.getAlternateSetting() 是 API 24+；API 17 车机（at8317 实测）
        // 无条件读取会 NoSuchMethodError，低版本固定打 0（诊断用途，不影响功能）。
        "${usbInterface.id}/${if (Build.VERSION.SDK_INT >= 24) usbInterface.alternateSetting else 0}" +
            ":${usbInterface.interfaceClass.toString(16)}" +
            ".${usbInterface.interfaceSubclass.toString(16)}" +
            ".${usbInterface.interfaceProtocol.toString(16)}" +
            "x${usbInterface.endpointCount}"

    fun usbMuxInterface(configuration: UsbConfiguration): UsbInterface? =
        (0 until configuration.interfaceCount).map(configuration::getInterface).firstOrNull {
            it.interfaceClass == USBMUX_CLASS &&
                it.interfaceSubclass == USBMUX_SUBCLASS &&
                it.interfaceProtocol == USBMUX_PROTOCOL
        }

    /**
     * API16（<21）legacy：无法枚举/选择多配置，只能扫 device.getInterface() 暴露的
     * 默认配置接口。重枚举后的 iPhone 若默认配置即 CarPlay 形态，这里能直接命中。
     */
    fun usbMuxInterfaceLegacy(device: UsbDevice): UsbInterface? =
        deviceInterfaces(device).firstOrNull { isUsbMux(it) }

    /** API16（<21）legacy：默认配置是否带 NCM 控制接口（决定 NCM 数据面是否可用）。 */
    fun hasCdcNcmLegacy(device: UsbDevice): Boolean =
        deviceInterfaces(device).any { it.interfaceClass == NCM_CONTROL_CLASS && it.interfaceSubclass == NCM_CONTROL_SUBCLASS }

    fun usbMuxEndpoints(usbInterface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
        val endpoints = (0 until usbInterface.endpointCount).map(usbInterface::getEndpoint)
        val out = endpoints.firstOrNull {
            it.address == PREFERRED_USBMUX_OUT &&
                it.direction == UsbConstants.USB_DIR_OUT &&
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        } ?: endpoints.singleOrNull {
            it.direction == UsbConstants.USB_DIR_OUT &&
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }
        val input = endpoints.firstOrNull {
            it.address == PREFERRED_USBMUX_IN &&
                it.direction == UsbConstants.USB_DIR_IN &&
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        } ?: endpoints.singleOrNull {
            it.direction == UsbConstants.USB_DIR_IN &&
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }
        return if (out != null && input != null) out to input else null
    }

    private fun deviceInterfaces(device: UsbDevice): List<UsbInterface> =
        (0 until device.interfaceCount).map(device::getInterface)

    private fun isUsbMux(usbInterface: UsbInterface): Boolean =
        usbInterface.interfaceClass == USBMUX_CLASS &&
            usbInterface.interfaceSubclass == USBMUX_SUBCLASS &&
            usbInterface.interfaceProtocol == USBMUX_PROTOCOL

    private fun hasCdcNcm(configuration: UsbConfiguration): Boolean =
        (0 until configuration.interfaceCount).map(configuration::getInterface).any {
            it.interfaceClass == NCM_CONTROL_CLASS && it.interfaceSubclass == NCM_CONTROL_SUBCLASS
        }

    private fun hasAppleEthernet(configuration: UsbConfiguration): Boolean =
        (0 until configuration.interfaceCount).map(configuration::getInterface).any {
            it.interfaceClass == APPLE_ETHERNET_CLASS &&
                it.interfaceSubclass == APPLE_ETHERNET_SUBCLASS &&
                it.interfaceProtocol == APPLE_ETHERNET_PROTOCOL
        }
}
