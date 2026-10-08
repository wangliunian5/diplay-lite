package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.os.Build

/**
 * Finds the NCM control/data interface pair inside an active iPhone configuration.
 *
 * LIVI claims the control interface, claims the data interface, and selects data alternate
 * setting 1, the setting that carries the bulk endpoints. This class only reads descriptors.
 */
object NcmFunctionDiscovery {
    const val CONTROL_CLASS = 0x02
    const val CONTROL_SUBCLASS = 0x0d
    const val DATA_CLASS = 0x0a
    const val APPLE_ETHERNET_CLASS = 0xff
    const val APPLE_ETHERNET_SUBCLASS = 0xfd
    const val APPLE_ETHERNET_PROTOCOL = 0x01
    const val DATA_ALTERNATE_SETTING = 1

    data class NcmFunction(
        val control: UsbInterface,
        val data: UsbInterface,
        val statusIn: UsbEndpoint?,
        val bulkIn: UsbEndpoint,
        val bulkOut: UsbEndpoint,
    )

    /** API16 适配：UsbConfiguration 是 API 21+；< 21 走 [findLegacy]（默认配置接口扫描）。 */
    fun find(configuration: UsbConfiguration): NcmFunction? {
        return findCdcNcm(configuration)
    }

    /**
     * API16（<21）legacy：device.getInterface() 会列出默认配置下的全部接口条目
     * （含 alternate settings），足以定位 NCM 控制接口与带 bulk 端点的 alt-1 数据接口。
     */
    fun findLegacy(device: UsbDevice): NcmFunction? {
        val interfaces = (0 until device.interfaceCount).map(device::getInterface)
        val control = interfaces.firstOrNull {
            it.interfaceClass == CONTROL_CLASS && it.interfaceSubclass == CONTROL_SUBCLASS
        } ?: interfaces.firstOrNull {
            it.interfaceClass == APPLE_ETHERNET_CLASS &&
                it.interfaceSubclass == APPLE_ETHERNET_SUBCLASS &&
                it.interfaceProtocol == APPLE_ETHERNET_PROTOCOL
        } ?: return null
        val data = interfaces
            .filter { it.interfaceClass == DATA_CLASS && bulkEndpoints(it) != null }
            .minByOrNull { if (alternateSetting(it) == DATA_ALTERNATE_SETTING) 0 else 1 }
            ?: return null
        val endpoints = bulkEndpoints(data) ?: return null
        val statusIn = (0 until control.endpointCount)
            .map(control::getEndpoint)
            .singleOrNull {
                it.direction == UsbConstants.USB_DIR_IN &&
                    it.type == UsbConstants.USB_ENDPOINT_XFER_INT
            }
        return NcmFunction(control, data, statusIn, endpoints.first, endpoints.second)
    }

    /** 按 SDK 自动选择发现路径；< 21 忽略传入的 configuration 走 legacy。 */
    fun find(device: UsbDevice, configuration: UsbConfiguration?): NcmFunction? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && configuration != null) {
            find(configuration)
        } else {
            findLegacy(device)
        }

    /**
     * 1.0.3：UsbInterface.getAlternateSetting() 是 API 24+（at8317 实测 API17
     * NoSuchMethodError）。< 24 返回 -1（永远不等于 DATA_ALTERNATE_SETTING，
     * 退化为按端点形态优先的选择），保证低版本车机可用。
     */
    private fun alternateSetting(usbInterface: UsbInterface): Int =
        if (Build.VERSION.SDK_INT >= 24) usbInterface.alternateSetting else -1

    private fun findCdcNcm(configuration: UsbConfiguration): NcmFunction? {
        val control = interfaces(configuration).firstOrNull {
            it.interfaceClass == CONTROL_CLASS && it.interfaceSubclass == CONTROL_SUBCLASS
        } ?: return null
        val data = interfaces(configuration)
            .filter { it.interfaceClass == DATA_CLASS && bulkEndpoints(it) != null }
            .minByOrNull { if (alternateSetting(it) == DATA_ALTERNATE_SETTING) 0 else 1 }
            ?: return null
        val endpoints = bulkEndpoints(data) ?: return null
        val statusIn = (0 until control.endpointCount)
            .map(control::getEndpoint)
            .singleOrNull {
                it.direction == UsbConstants.USB_DIR_IN &&
                    it.type == UsbConstants.USB_ENDPOINT_XFER_INT
            }
        return NcmFunction(control, data, statusIn, endpoints.first, endpoints.second)
    }

    private fun interfaces(configuration: UsbConfiguration): List<UsbInterface> =
        (0 until configuration.interfaceCount).map(configuration::getInterface)

    private fun bulkEndpoints(usbInterface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
        val endpoints = (0 until usbInterface.endpointCount).map(usbInterface::getEndpoint)
        val input = endpoints.singleOrNull {
            it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }
        val output = endpoints.singleOrNull {
            it.direction == UsbConstants.USB_DIR_OUT && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }
        return if (input != null && output != null) input to output else null
    }
}
