package com.shilapi.xcertplay.media

import java.io.File

/**
 * 弱机优化的量化依据：本进程在这段窗口里的 CPU 占用与线程数。
 *
 * 为什么需要它：C8815（双核 Cortex-A5）上"卡顿"的实测特征是**收包线程单包处理被抢占到 93~163 ms**
 * （而那段代码只做解密 + 入队，解码在别的线程）⇒ 真凶是 CPU/调度争抢。只有把 CPU 占用打进 stats，
 * 才能判断每轮优化到底有没有把争抢真降下来。
 *
 * 读 `/proc/self/stat` 的 utime/stime（USER_HZ = 100）与 `/proc/self/task` 条目数；
 * 任何一步失败都返回 null —— 无 adb 环境下，指标缺一条只是少个数字，绝不影响主流程。
 *
 * 注意：**每个 stats 拥有者各持一个实例**（窗口互相独立），不要做成共享单例，
 * 否则两个 5 秒窗口会互相把基线刷掉，算出来的占用率是垃圾。
 */
internal class ProcessLoadSampler {
    private var lastTicks = -1L
    private var lastNs = 0L

    /** 采样一次，返回如 `cpu=83%of2cpu threads=21`；首次调用只建立基线。 */
    fun sample(nowNs: Long = System.nanoTime()): String? {
        val ticks = readCpuTicks() ?: return null
        val cores = Runtime.getRuntime().availableProcessors()
        val threads = readThreadCount()
        if (lastTicks < 0L) {
            lastTicks = ticks
            lastNs = nowNs
            return "cpu=?/of${cores}cpu threads=$threads"
        }
        val elapsedNs = nowNs - lastNs
        val deltaTicks = ticks - lastTicks
        lastTicks = ticks
        lastNs = nowNs
        val percent = if (elapsedNs > 0L && deltaTicks >= 0L) {
            deltaTicks * 1_000_000_000L / elapsedNs
        } else {
            -1L
        }
        return "cpu=${if (percent < 0L) "?" else percent.toString()}%of${cores}cpu threads=$threads"
    }

    /** utime + stime，单位 USER_HZ(100)；字段 14/15 = comm 之后的下标 11/12。 */
    private fun readCpuTicks(): Long? = try {
        val stat = File("/proc/self/stat").readText()
        val commEnd = stat.lastIndexOf(')')
        if (commEnd < 0) {
            null
        } else {
            val fields = stat.substring(commEnd + 2).split(' ')
            val utime = fields.getOrNull(11)?.toLongOrNull()
            val stime = fields.getOrNull(12)?.toLongOrNull()
            if (utime == null || stime == null) null else utime + stime
        }
    } catch (_: Exception) {
        null
    }

    private fun readThreadCount(): Int = try {
        File("/proc/self/task").list()?.size ?: -1
    } catch (_: Exception) {
        -1
    }
}