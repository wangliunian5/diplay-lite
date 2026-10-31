package com.shilapi.xcertplay.media

import java.io.InputStream

/**
 * vc197：root 直采语音源 —— FE6（AKM AK7738 车规 DSP）有线 Siri 全零的**兜底通路**。
 *
 * 为什么需要它：vc194/vc195/vc196 三轮实测把「App 侧参数」这条路彻底走完了 ——
 * 13 档 源×采样率×声道 矩阵里，`源6` 全配置 `BLOCKED`（一个字节都不返回），
 * 能读的 `源0/44.1k`、`源1/24k` 读出来 **全是 0**。而同一台车机**自带语音助手正常收音**
 * （用户实测录像），说明麦克风、DSP、偏置供电、系统麦总开关全是好的。
 *
 * 结论：静音是在 **AudioFlinger / AudioPolicy 层**加进去的（第三方 record track 被
 * 交付静音填充），而车机自己的进程（`ecarx.audio.service`）能在 `pcmC0D1c`(aif3) 上
 * 拿到真实音频。⇒ 只要**绕过 AudioFlinger**，直接从 ALSA 采集节点读，就能拿到真音。
 * 这条路必须 root（`/dev/snd` 下各节点是 `system:audio 0660`），所以定位是「兜底」：
 * 有 root 的机器语音可用，没 root 的机器保持现状 + 静音保活。
 *
 * 实现方（app 模块）负责：解压随包注入的 `diplay-pcmcap` 二进制、`su` 提权、
 * 按候选节点/参数逐档试开，只把**第一个真正出数的流**交回来。
 */
interface RootMicSource {
    /** root 是否可用（`su` 能拿到 uid=0 且二进制就位）。结果应缓存，勿每次重探。 */
    fun isAvailable(): Boolean

    /**
     * 起一次直采。
     *
     * 返回的流必须是 **S16_LE 交错**的裸 PCM，采样率/声道以 [RootMicStream] 内的
     * 实测值为准（实现方允许试多档，例如先试车机主通路的 16000/2ch）。
     * 打开失败（EBUSY / 无权限 / 节点不存在）返回 null，由调用方决定是否重试。
     */
    fun open(): RootMicStream?

    /** 释放所有子进程与临时资源（会话结束时调用）。 */
    fun release()
}

/** 一条已打开的 root 直采流。 */
class RootMicStream(
    val input: InputStream,
    val rate: Int,
    val channels: Int,
    /** 实际命中的节点描述，例如 `card0/d1(aif3) 16000Hz/2ch`，落日志用。 */
    val node: String,
    private val closer: () -> Unit,
) {
    fun close() {
        runCatching { input.close() }
        runCatching { closer() }
    }
}
