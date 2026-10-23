package com.shilapi.xcertplay.usbbt

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID

/**
 * vc181（外置 USB 蓝牙 C/D 批）：dongle 身份信息与初始化步骤模型。
 * 移植自上游参考实现 usbbt.UsbBtIdentity.java。
 *
 * 三个服务 UUID 与上游逐字一致（iAP2 Accessory / iAP2 iPhone / CarPlay）。
 * [extendedInquiryResponse] 生成 EIR 载荷（AD 结构 = len + type + data）：
 * type 6 = iAP2 Accessory UUID 正序 + 反序、type 7 = CarPlay UUID 反序、
 * type 10 = inquiry response TX power、type 9/8 = 设备名（238 字节上限，
 * 超长改用 type 8 截断名）。EIR 总长由调用方补 0 到 240。
 */
internal class UsbBtIdentity(
    val name: String,
    val classOfDevice: Int,
    val iap2RfcommChannel: Int,
    val ioCapability: Int,
) {
    companion object {
        val IAP2_ACCESSORY_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacaff")
        val IAP2_IPHONE_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")
        val CARPLAY_UUID: UUID = UUID.fromString("ec884348-cd41-40a2-9727-575d50bf1fd3")

        /** 上游 EVENT_CODES_ENABLED：Set_Event_Mask 位图对应的事件码列表。 */
        private val EVENT_CODES_ENABLED = listOf(
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
            16, 18, 20, 21, 22, 23, 24, 26, 27, 28,
            34, 35, 47, 48, 49, 50, 51, 52, 53, 54, 56, 59, 61,
        )

        /** 8 字节 LE 事件掩码位图（bit = 事件码 - 1）。 */
        fun eventMask(): ByteArray {
            val mask = ByteArray(8)
            for (code in EVENT_CODES_ENABLED) {
                val bit = code - 1
                mask[bit / 8] = (mask[bit / 8].toInt() or (1 shl (bit % 8))).toByte()
            }
            return mask
        }

        /** UUID → 16 字节大端。 */
        fun uuidBigEndian(uuid: UUID): ByteArray =
            ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
    }

    /** 单条 EIR AD 结构：len(1) + type(1) + data。 */
    private fun eirField(out: ByteArrayOutputStream, type: Int, data: ByteArray) {
        out.write(data.size + 1)
        out.write(type)
        out.write(data)
    }

    /** EIR 载荷（未补 0；调用方 copyOf 到 240）。[txPower] 为 null 时省略 type 10。 */
    fun extendedInquiryResponse(txPower: Int?): ByteArray {
        val body = ByteArrayOutputStream()
        val accessory = uuidBigEndian(IAP2_ACCESSORY_UUID)
        eirField(body, 6, accessory + accessory.reversedArray())
        eirField(body, 7, uuidBigEndian(CARPLAY_UUID).reversedArray())
        if (txPower != null) {
            eirField(body, 10, byteArrayOf(txPower.toByte()))
        }
        val names = ByteArrayOutputStream()
        val room = 238 - body.size()
        val bytes = name.toByteArray(Charsets.UTF_8)
        if (bytes.size <= room) {
            eirField(names, 9, bytes)
        } else {
            eirField(names, 8, HciCommands.utf8Prefix(room, name))
        }
        names.write(body.toByteArray())
        return names.toByteArray()
    }
}
