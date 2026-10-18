package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.os.Build
import java.io.File

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
        // vc103o：true = 已按内核真实活动配置（sysfs）对齐发现，openOnce 无需再切配置。
        val kernelAligned: Boolean = false,
    )

    /** API16 适配：UsbConfiguration 是 API 21+；< 21 走 [findLegacy]（默认配置接口扫描）。 */
    fun find(configuration: UsbConfiguration): NcmFunction? {
        return findCdcNcmCandidates(configuration).firstOrNull()
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

    /**
     * 按 SDK 自动选择发现路径；< 21 忽略传入的 configuration 走 legacy。
     *
     * 1.1.4（TS7 mazda2 NTB16 绕过）：多 NCM 组时优选**内核未绑定**的组——
     * iPhone CarPlay 配置可暴露两组 CDC-NCM（TS7 实测 If3/4 被 cdc_ncm 抢绑、
     * If5/6 空闲），绑死组与内核双读导致 NTB16 打碎。[avoidControlId] 传入
     * 上次失败组的 control id，排序时排最后（重建轮换）。
     *
     * 1.1.5/vc70（TS7 端点差分实验）：反转内核启发——**优先选描述符序第一组**
     * （If3/4，带 interrupt status EP 0x87）。vc69 在 5/6 组上 mDNS 应答 USB 层全部
     * ACK 但 iPhone 网络栈零响应，嫌疑收敛到 MUSB 主控 EP 0x07 TX 侧静默丢弃；
     * 换 3/4 组（OUT=0x06）做 A/B。claim 用 force=true 会 detach 内核驱动，且有
     * RootNcmReclaimer + 外部 unbind 守护兜底，内核双读已被外部解绑排除。
     */
    fun find(device: UsbDevice, configuration: UsbConfiguration?, avoidControlId: Int? = null): NcmFunction? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && configuration != null) {
            val candidates = findCdcNcmCandidates(configuration)
            when {
                candidates.isEmpty() -> null
                candidates.size == 1 -> candidates[0]
                else -> {
                    val bound = boundControlIds(device, configuration.id)
                    candidates.sortedWith(
                        compareBy(
                            { if (bound?.contains(it.control.id) == true) 0 else 1 },
                            { if (it.control.id == avoidControlId) 1 else 0 },
                        ),
                    ).first()
                }
            }
        } else {
            // vc103p：findKernelAligned 需要 rawDescriptors（connection 才有），调用点上移至
            // CarPlayController.openNcmOnce（openDevice 后调）；此处维持 legacy 扫描。
            findLegacy(device)
        }

    /**
     * vc103p（T3 终审·第三版）：KK 上 framework 接口缓存 = 设备添加时刻的活动配置布局，
     * 之后内核被切配置（legacy 残留/RESET/重试）即错位——实测 T3：framework=配置 5 布局
     * （control=2/data=3/EP 0x87+0x05），内核停配置 6（IF3=NCM control 0x87 INTR、
     * IF4=NCM data 0x88/0x06），App 拿 5 的地图走 6 的路：claim(2) 误中 AppleEthernet、
     * SET_INTERFACE(3,1) 撞 control 无 alt1（EINVAL=22）、裸 URB 0x87 打在 INTR 端点上
     * 假阳性存活、数据面全程死读 → iPhone mDNS 无应答 → 0x4301 静默。
     *
     * 数据源（T3 实测定案）：
     * - sysfs bConfigurationValue = 内核真实活动配置值（KK 可读，实测=6）；
     * - 接口目录下**无 ep_* 子目录**（KK sunxi 内核未导出）→ 端点拓扑改从
     *   [rawDescriptors] 解析（/dev/bus/usb 节点 blob，实测 858B 含全部 6 个配置）。
     *
     * 返回按「内核活动配置拓扑 + framework 对象」拼装的 NcmFunction：UsbInterface 仅按
     * id 用于 claim ioctl（class 标签错位无碍）；UsbEndpoint 按地址跨接口搜索（usbfs
     * bulkTransfer 仅按地址构造 URB，端点类型以内核表为准）。kernelAligned=true 时
     * NcmUsbBridge.openOnce 跳过 pre-claim 切配置。sysfs 不可读/拓扑缺失返 null 走 legacy。
     */
    fun findKernelAligned(device: UsbDevice, rawDescriptors: ByteArray?): NcmFunction? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) return null
        if (rawDescriptors == null || rawDescriptors.size < 18 + 9) return null
        return try {
            val root = File("/sys/bus/usb/devices")
            val vendor = "%04x".format(device.vendorId)
            val product = "%04x".format(device.productId)
            val dirs = root.listFiles() ?: return null
            val devDir = dirs.firstOrNull { dir ->
                !dir.name.contains(":") &&
                    runCatching {
                        File(dir, "idVendor").readText().trim().equals(vendor, ignoreCase = true) &&
                            File(dir, "idProduct").readText().trim().equals(product, ignoreCase = true)
                    }.getOrDefault(false)
            } ?: return null
            val kernelConfig = File(devDir, "bConfigurationValue").readText().trim().toIntOrNull()
                ?: return null
            // 解析 rawDescriptors 中 bConfigurationValue==kernelConfig 的配置段拓扑。
            val raw = rawDescriptors
            var off = 18
            var cfgVal = -1
            var curIface = -1
            var ctrlId = -1
            var statusAddr = -1
            var dataId = -1
            var inAddr = -1
            var outAddr = -1
            var ctrlIdHit = -1
            var statusAddrHit = -1
            var dataIdHit = -1
            var inAddrHit = -1
            var outAddrHit = -1
            while (off + 2 <= raw.size) {
                val len = raw[off].toInt() and 0xff
                if (len < 2 || off + len > raw.size) break
                when (raw[off + 1].toInt() and 0xff) {
                    0x02 -> if (len >= 9) {
                        if (cfgVal == kernelConfig && ctrlId >= 0 && dataId >= 0 && inAddr >= 0) {
                            ctrlIdHit = ctrlId; statusAddrHit = statusAddr
                            dataIdHit = dataId; inAddrHit = inAddr; outAddrHit = outAddr
                        }
                        cfgVal = raw[off + 5].toInt() and 0xff
                        curIface = -1; ctrlId = -1; statusAddr = -1
                        dataId = -1; inAddr = -1; outAddr = -1
                    }
                    0x04 -> if (len >= 9) {
                        curIface = raw[off + 2].toInt() and 0xff
                        val cls = raw[off + 5].toInt() and 0xff
                        val sub = raw[off + 6].toInt() and 0xff
                        if (cfgVal == kernelConfig) {
                            if (cls == CONTROL_CLASS && sub == CONTROL_SUBCLASS && ctrlId < 0) ctrlId = curIface
                            if (cls == DATA_CLASS && dataId < 0) dataId = curIface
                        }
                    }
                    0x05 -> if (len >= 7 && cfgVal == kernelConfig) {
                        val addr = raw[off + 2].toInt() and 0xff
                        val epType = raw[off + 3].toInt() and 0x03
                        when {
                            epType == 2 && (addr and 0x80) != 0 && curIface == dataId && inAddr < 0 -> inAddr = addr
                            epType == 2 && (addr and 0x80) == 0 && curIface == dataId && outAddr < 0 -> outAddr = addr
                            epType == 3 && (addr and 0x80) != 0 && curIface == ctrlId && statusAddr < 0 -> statusAddr = addr
                        }
                    }
                }
                off += len
            }
            if (cfgVal == kernelConfig && ctrlId >= 0 && dataId >= 0 && inAddr >= 0) {
                ctrlIdHit = ctrlId; statusAddrHit = statusAddr
                dataIdHit = dataId; inAddrHit = inAddr; outAddrHit = outAddr
            }
            if (ctrlIdHit < 0 || dataIdHit < 0 || inAddrHit < 0) return null
            val allIfs = (0 until device.interfaceCount).map(device::getInterface)
            val control = allIfs.firstOrNull { it.id == ctrlIdHit } ?: return null
            val data = allIfs.firstOrNull { it.id == dataIdHit } ?: return null
            var bulkIn: UsbEndpoint? = null
            var bulkOut: UsbEndpoint? = null
            var statusIn: UsbEndpoint? = null
            for (iface in allIfs) {
                for (i in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(i)
                    if (ep.address == inAddrHit &&
                        ep.direction == UsbConstants.USB_DIR_IN &&
                        ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK
                    ) bulkIn = ep
                    if (outAddrHit >= 0 &&
                        ep.address == outAddrHit &&
                        ep.direction == UsbConstants.USB_DIR_OUT &&
                        ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK
                    ) bulkOut = ep
                    if (statusAddrHit > 0 &&
                        ep.address == statusAddrHit &&
                        ep.direction == UsbConstants.USB_DIR_IN &&
                        ep.type == UsbConstants.USB_ENDPOINT_XFER_INT
                    ) statusIn = ep
                }
            }
            bulkIn ?: return null
            bulkOut ?: return null
            NcmFunction(control, data, statusIn, bulkIn, bulkOut, kernelAligned = true)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 1.0.3：UsbInterface.getAlternateSetting() 是 API 24+（at8317 实测 API17
     * NoSuchMethodError）。< 24 返回 -1（永远不等于 DATA_ALTERNATE_SETTING，
     * 退化为按端点形态优先的选择），保证低版本车机可用。
     */
    private fun alternateSetting(usbInterface: UsbInterface): Int =
        if (Build.VERSION.SDK_INT >= 24) usbInterface.alternateSetting else -1

    private fun findCdcNcm(configuration: UsbConfiguration): NcmFunction? {
        return findCdcNcmCandidates(configuration).firstOrNull()
    }

    /**
     * 1.1.4：收集配置内**全部** CDC-NCM 功能对（comm 接口 + 紧随其后带 bulk 端点的
     * data 接口），按描述符顺序配对。两组 NCM 时返回两条候选。
     */
    private fun findCdcNcmCandidates(configuration: UsbConfiguration): List<NcmFunction> {
        val entries = interfaces(configuration)
        val consumedData = HashSet<Int>()
        val candidates = mutableListOf<NcmFunction>()
        for ((index, control) in entries.withIndex()) {
            if (control.interfaceClass != CONTROL_CLASS || control.interfaceSubclass != CONTROL_SUBCLASS) continue
            val dataEntry = entries.withIndex().firstOrNull { (i, iface) ->
                i > index && i !in consumedData &&
                    iface.interfaceClass == DATA_CLASS && bulkEndpoints(iface) != null
            } ?: continue
            consumedData.add(dataEntry.index)
            val endpoints = bulkEndpoints(dataEntry.value) ?: continue
            val statusIn = (0 until control.endpointCount)
                .map(control::getEndpoint)
                .singleOrNull {
                    it.direction == UsbConstants.USB_DIR_IN &&
                        it.type == UsbConstants.USB_ENDPOINT_XFER_INT
                }
            candidates.add(NcmFunction(control, dataEntry.value, statusIn, endpoints.first, endpoints.second))
        }
        return candidates
    }

    /**
     * 1.1.4：读 sysfs 找出本机被内核驱动绑定的接口号（如 /sys/bus/usb/devices/
     * 1-1:6.3/driver 存在 = config 6 的 interface 3 被 cdc_ncm 占用）。
     * 返回 null = 无法判定（无 sysfs 或 IO 失败），调用方维持原顺序。
     */
    private fun boundControlIds(device: UsbDevice, configId: Int): Set<Int>? {
        return try {
            val root = File("/sys/bus/usb/devices")
            val vendor = "%04x".format(device.vendorId)
            val product = "%04x".format(device.productId)
            val dirs = root.listFiles() ?: return null
            val deviceNames = dirs.filter { dir ->
                !dir.name.contains(":") &&
                    runCatching {
                        File(dir, "idVendor").readText().trim().equals(vendor, ignoreCase = true) &&
                            File(dir, "idProduct").readText().trim().equals(product, ignoreCase = true)
                    }.getOrDefault(false)
            }.map { it.name }.toSet()
            if (deviceNames.isEmpty()) return null
            val bound = HashSet<Int>()
            val pattern = Regex("^(.+):(\\d+)\\.(\\d+)$")
            for (dir in dirs) {
                val match = pattern.find(dir.name) ?: continue
                if (match.groupValues[1] !in deviceNames) continue
                if (match.groupValues[2].toIntOrNull() != configId) continue
                val interfaceId = match.groupValues[3].toIntOrNull() ?: continue
                if (File(dir, "driver").exists()) bound.add(interfaceId)
            }
            bound
        } catch (error: Throwable) {
            null
        }
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
