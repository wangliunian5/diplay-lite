package com.shilapi.diplay.lite

import android.os.Build

/**
 * vc197：FE6 车型识别 —— 「实验性语音兜底」只对这台车机放开。
 *
 * 为什么必须限定机型：兜底会做两件对普通车机**只有风险没有收益**的事 ——
 * ① 上行改成「采集/发送解耦 + 静音保活」，多一条线程和最多 160ms 缓冲；
 * ② 用 root 直读 ALSA 采集节点。前者会改变发送时序，后者要提权。
 * 在没有这个故障的机型上，这两件事都不该发生。
 *
 * 识别依据（来自现场日志的机型字段，非猜测）：
 * · `Build.MODEL` = **FE-6**（日志文件名尾部的 `-FE-6` 就是它）
 * · `ro.product.device` / `ro.board.platform` 关联 **xea406m**（ECARX 平台代号）
 * · 制造商 ECARX
 */
object Fe6Device {

    /** 结果缓存：Build 字段进程内不变，没必要每次都反射。 */
    private val cached: Boolean by lazy { detect() }

    fun isFe6(): Boolean = cached

    /** 命中原因，落诊断日志用（外测时一眼能确认机型判对没判对）。 */
    fun describe(): String = fingerprint()

    private fun detect(): Boolean {
        val fp = fingerprint()
        return fp.contains("fe-6") ||
            fp.contains("fe6") ||
            fp.contains("xea406m") ||
            (fp.contains("ecarx") && fp.contains("mt"))
    }

    private fun fingerprint(): String {
        val parts = mutableListOf(
            Build.MODEL.orEmpty(),
            Build.DEVICE.orEmpty(),
            Build.BOARD.orEmpty(),
            Build.HARDWARE.orEmpty(),
            Build.PRODUCT.orEmpty(),
            Build.MANUFACTURER.orEmpty(),
            Build.BRAND.orEmpty(),
            prop("ro.product.model"),
            prop("ro.product.device"),
            prop("ro.build.product"),
            prop("ro.board.platform"),
        )
        return parts.filter { it.isNotEmpty() }.joinToString(" ").lowercase()
    }

    private fun prop(key: String): String = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        (get.invoke(null, key) as? String).orEmpty()
    } catch (_: Throwable) {
        ""
    }
}
