package com.shilapi.diplay.lite

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.shilapi.xcertplay.media.RootMicSource
import com.shilapi.xcertplay.media.RootMicStream
import com.shilapi.xcertplay.root.SuShell
import java.io.File
import java.io.PushbackInputStream

/**
 * vc197：FE6 root 直采语音源（兜底通路）。
 *
 * ## 为什么需要这条路
 * vc194 → vc196 三轮实测把 App 侧能动的参数走完了：
 * · 源 × 采样率 × 声道 **13 档 × 2 轮**：`源6` 全配置 `BLOCKED`（`readCalls=0`，一个字节
 *   都不返回），能读的 `源0/44.1k`、`源1/24k` 读出来**全是 0**；
 * · 同一台车机**自带语音助手正常收音**（用户实测录像，"关闭高德地图"被识别执行）
 *   ⇒ 麦克风、AK7738 DSP、MicBias 偏置、系统麦总开关**全都没坏**；
 * · 车机自己的进程（`ecarx.audio.service`，vc195 里 pid 3191）以 `16000/2ch` 常驻
 *   `state=RUNNING` 吃 `pcmC0D1c`，`hw_ptr` 稳定 +16020fps ⇒ **aif3 上有真实数据流**。
 *
 * 结论：静音是 **AudioFlinger 给第三方 record track 加的填充**，不是硬件没音。
 * 绕过 AudioFlinger、直接读 ALSA 采集节点就能拿到真音 —— 代价是 `/dev/snd` 下的节点
 * 是 `system:audio 0660`，必须 root。所以定位是「兜底」：有 root 的机器语音可用。
 *
 * ## 实现要点
 * · 二进制随包注入（`assets/tinycap/diplay-pcmcap-<abi>`，v3 起支持 `-W` 裸 PCM 直出），
 *   首次使用时拷到 `/data/local/tmp` 并 `chmod 755`（`/data/local/tmp` 是少数几个
 *   `su` 域可执行的目录之一）。
 * · 逐档试开，**先接受真正出数的**，都不行时退回第一个「能打开但全零」的（至少不比
 *   AudioRecord 差）。
 * · 探测阶段读到的音频用 [PushbackInputStream] 塞回流里，一行不丢。
 */
class Fe6RootMicSource(
    private val context: Context,
    private val diagnostics: (String) -> Unit,
) : RootMicSource {

    /** root 可用性与部署状态只探一次（su 授权、cp/chmod 都是秒级但没必要每会话重来）。 */
    private var availability: Boolean? = null
    private var deployNote = ""

    /**
     * vc198：实测可用的 su 可执行文件（`su` 或绝对路径）。
     * vc197 失败教训：只写 `ProcessBuilder("su", ...)` 时，若 su 不在 App 进程的 PATH
     * （SuperSU systemless 常在 `/su/bin/su`、Magisk 在 `/sbin/su`），进程根本起不来，
     * 而 `catch(_: Throwable){""}` 把 IOException 吞成空串 ⇒ 日志只剩「未授权」。
     */
    @Volatile private var suBinary: String? = null

    /** vc198：su 探测的完整过程（含每档 exitCode/stdout/stderr/耗时），供日志与屏上显示。 */
    @Volatile private var suReport: String = "尚未探测"

    /** 最近一次自检结论，给设置页直接上屏。 */
    val lastReport: String get() = suReport

    /**
     * vc199：自检时**实测命中**的采集参数（`card`/`device`/`rate`/`channels`）。
     *
     * 会话里的 `open()` 会先试它 —— 因为 Siri 只活 1.8~3.2s，来不及现场扫四档
     * （vc198 实测 `root fallback #1` 触发到 iPhone TEARDOWN 只剩 276ms）。
     */
    @Volatile private var cachedSpec: IntArray? = null

    /** 所有 su 子进程，release() 时统一收尸（su 被 kill 不等于它的子进程会死）。 */
    private val children = ArrayList<Process>()

    override fun isAvailable(): Boolean {
        availability?.let { return it }
        val rootOk = probeRoot()
        if (!rootOk) {
            availability = false
            deployNote = suReport
            diagnostics("root mic: root 不可用 —— $deployNote")
            return false
        }
        val deployed = ensureDeployed()
        availability = deployed
        if (!deployed) diagnostics("root mic: 二进制部署失败 ($deployNote)")
        return deployed
    }

    /** 授权结果变化后（设置页点了授权按钮）强制重探。 */
    fun invalidate() {
        availability = null
        suBinary = null
        suReport = "尚未探测"
        deployNote = ""
    }

    override fun open(): RootMicStream? {
        if (!isAvailable()) return null
        // vc199：自检时已经命中过某组参数 ⇒ 会话里直接拿它当第一档，不再从候选表头部试起。
        // 这一步把「选节点」从会话中（Siri 只活 1.8~3.2s，根本来不及）挪到自检时
        // （用户在设置页主动点，不限时）。
        val candidates = ArrayList<IntArray>()
        cachedSpec?.let { candidates.add(it) }
        for (spec in defaultCandidates()) {
            if (candidates.none { it.contentEquals(spec) }) candidates.add(spec)
        }
        val deadline = SystemClock.elapsedRealtime() + PROBE_TOTAL_MILLIS
        var silentFallback: RootMicStream? = null
        for (spec in candidates) {
            if (SystemClock.elapsedRealtime() > deadline) {
                diagnostics("root mic: 探测总时长用尽，停止试开")
                break
            }
            val card = spec[0]
            val device = spec[1]
            val rate = spec[2]
            val channels = spec[3]
            val probe = tryOpen(card, device, rate, channels) ?: continue
            if (probe.peak > 0) {
                diagnostics(
                    "root mic: 命中 card$card/d$device ${rate}Hz/${channels}ch " +
                        "peak=${probe.peak}（真实音频）",
                )
                silentFallback?.close()
                return probe.stream
            }
            diagnostics(
                "root mic: card$card/d$device ${rate}Hz/${channels}ch 可读但全零" +
                    "（${probe.bytes}B），继续试下一档",
            )
            if (silentFallback == null) silentFallback = probe.stream else probe.stream.close()
        }
        if (silentFallback != null) {
            diagnostics("root mic: 没有一档出数，接受「可读但全零」的节点（不比 AudioRecord 差）")
        } else {
            diagnostics("root mic: 所有候选节点都打不开（EBUSY/无权限，见上面各档 errno）")
        }
        return silentFallback
    }

    override fun release() {
        synchronized(children) {
            children.forEach { proc ->
                runCatching { proc.destroy() }
            }
            children.clear()
        }
    }

    /**
     * vc198：设置页「① 授权并自检」按钮的执行体。**必须在后台线程调用** ——
     * 会起 su（可能要等授权弹窗，最长 20s）+ 试采三档（各 1.2s）。
     *
     * 为什么要把授权单独做成按钮，而不是等 CarPlay 会话里偷偷探测：
     * vc193/194/195 三次「点按钮时能拿到 uid=0」与 vc197「会话中调 su 判未授权」
     * 的差别，除了命令写法，还有**调用时机的可见性** —— 老版 SuperSU 的授权框需要
     * 前台 UI，投屏全屏时可能弹不出来而被默认拒绝。所以授权要前置到建链之前。
     *
     * 三步结果全部进返回串（设置页直接上屏）：
     * 1. 逐个 su 候选跑 `su -c id`，报 uid + exitCode + stderr + 耗时；
     * 2. 部署采集二进制并 `ls -l` 验证；
     * 3. 用 d1 16000/2ch → 48000/1ch → 24000/1ch 试采 1 秒，报 peak。
     *    **`peak > 0` 就是「root 直采能拿到真音」的现场证据**，不必等 CarPlay 会话。
     */
    fun selfTest(): String {
        invalidate()
        val lines = ArrayList<String>()
        // 前台点击场景：给足授权弹窗时间（会话内只给 6s，见 PROBE_SU_TIMEOUT_SESSION_MILLIS）。
        val rootOk = probeRoot(PROBE_SU_TIMEOUT_MILLIS)
        lines.add("① 授权：" + suReport.trim())
        if (!rootOk) {
            lines.add("结论：root 不可用 ⇒ 语音兜底不会生效。请到车机授权管理器把 DiPlay Lite 设为允许。")
            return lines.joinToString("\n")
        }
        val deployed = ensureDeployed()
        lines.add("② 部署：" + deployNote.trim())
        if (!deployed) {
            lines.add("结论：采集二进制没能就位 ⇒ 语音兜底不会生效。")
            return lines.joinToString("\n")
        }
        val probes = defaultCandidates()
        var best: String? = null
        var readableSpec: IntArray? = null
        var readableNote = ""
        val detail = StringBuilder()
        for (spec in probes) {
            val probe = tryOpen(spec[0], spec[1], spec[2], spec[3]) ?: continue
            val note = "card${spec[0]}/d${spec[1]} ${spec[2]}Hz/${spec[3]}ch " +
                "${probe.bytes}B peak=${probe.peak}"
            runCatching { probe.stream.close() }
            detail.append("\n   ").append(note)
            if (probe.peak > 0) {
                cachedSpec = spec
                best = note
                detail.append("  ← 命中，已存为会话首选")
                break
            }
            if (readableSpec == null) {
                readableSpec = spec
                readableNote = note
            }
        }
        // 没有真音但至少「能读」的档也存下来：会话里总比从第一档重新试快 400ms。
        if (best == null && readableSpec != null) {
            cachedSpec = readableSpec
            detail.append("\n   （无真音，但 $readableNote 可读，已存为会话首选）")
        }
        lines.add("③ 试采：$detail")
        lines.add(
            if (best != null) {
                "结论：直采拿到真音（$best）⇒ 语音兜底可用，去连 CarPlay 试 Siri"
            } else {
                "结论：直采可读但全零 ⇒ 车机此刻确实没有音频可采。" +
                    "试着对着麦克风连续说话再点一次；仍全零则上「一键体检」看 DSP 状态。"
            },
        )
        return lines.joinToString("\n")
    }

    /**
     * 直采候选表（按 FE6 实测拓扑排序）。
     *
     * · `d1` = `MultiMedia1_Capture ak7738-aif3-1` —— 麦克风主通路，车机自己的
     *   `ecarx.audio.service` 就以 16000/2ch 常驻吃它；
     * · `d16` = `I2S0AWB_Capture ak7738-aif4-16` —— 备用 I2S 采集。
     *
     * 参数依据：
     * · **16000/2ch 是实物参数**（`/proc/asound/card0/pcm1c/sub0` 实证 `S16_LE / 2ch / 16000`），
     *   hw_params 只有它一定能过；
     * · 48000/1ch 是全历史**唯一出过真音**的参数（1.1.3，`peak=2118`）；
     * · 24000/1ch 从未出过音，放最后。
     */
    private fun defaultCandidates(): List<IntArray> = listOf(
        intArrayOf(0, 1, 16_000, 2),
        intArrayOf(0, 1, 48_000, 1),
        intArrayOf(0, 16, 16_000, 2),
        intArrayOf(0, 1, 24_000, 1),
    )

    // ── 试开一档 ────────────────────────────────────────────────────────────

    private class Opened(val stream: RootMicStream, val peak: Int, val bytes: Int)

    /**
     * 起一次 `-W` 直采，边读边算峰值：
     * · 进程在 [PROBE_WINDOW_MILLIS] 内退出且没读到字节 ⇒ 该档不可用（EBUSY/参数不接受）
     * · 读到字节 ⇒ 可用；`peak > 0` 说明拿到的是真实音频
     *
     * 探测读到的数据全部塞回 [PushbackInputStream]，接管后一行不丢。
     */
    private fun tryOpen(card: Int, device: Int, rate: Int, channels: Int): Opened? {
        val cmd = "$REMOTE -W -D $card -d $device -r $rate -c $channels -t $CAPTURE_SECONDS"
        val proc = try {
            ProcessBuilder(suArgv(cmd)).start()
        } catch (error: Throwable) {
            diagnostics(
                "root mic: 启动 su 失败 ${error.javaClass.simpleName}: ${error.message}" +
                    "（su=${suBinary ?: "su"}）",
            )
            return null
        }
        synchronized(children) { children.add(proc) }

        // stderr 是 v3 的诊断通道（BEGIN/TIMEOUT/RAW_HB/RAW_END），转进日志便于定案。
        val errLines = StringBuilder()
        Thread({
            runCatching {
                proc.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (errLines.length < 4_000) errLines.append(line).append('\n')
                        if (line.startsWith("TIMEOUT") || line.startsWith("RAW_ERROR")) {
                            diagnostics("root mic card$card/d$device stderr: $line")
                        }
                    }
                }
            }
        }, "fe6-rootmic-err").apply {
            isDaemon = true
            start()
        }

        val input = proc.inputStream
        val collected = java.io.ByteArrayOutputStream(PROBE_BYTES)
        val scratch = ByteArray(8 * 1_024)
        val windowEnd = SystemClock.elapsedRealtime() + PROBE_WINDOW_MILLIS
        var peak = 0
        while (SystemClock.elapsedRealtime() < windowEnd && collected.size() < PROBE_BYTES) {
            val available = runCatching { input.available() }.getOrDefault(-1)
            if (available < 0) break
            if (available == 0) {
                if (hasExited(proc)) break
                try {
                    Thread.sleep(20L)
                } catch (_: InterruptedException) {
                    break
                }
                continue
            }
            val want = minOf(available, scratch.size)
            val read = runCatching { input.read(scratch, 0, want) }.getOrDefault(-1)
            if (read <= 0) break
            collected.write(scratch, 0, read)
            peak = maxOf(peak, peakOf(scratch, read))
        }

        val total = collected.size()
        if (total == 0) {
            val alive = !hasExited(proc)
            runCatching { proc.destroy() }
            synchronized(children) { children.remove(proc) }
            diagnostics(
                "root mic: card$card/d$device ${rate}Hz/${channels}ch 无数据" +
                    "(alive=$alive ${if (errLines.isNotEmpty()) "stderr=${errLines.toString().trim()}" else ""})",
            )
            return null
        }

        // 把探测数据倒回去，接管后的读流从第一个字节开始。
        val pushback = PushbackInputStream(input, maxOf(total + 4_096, 32 * 1_024))
        pushback.unread(collected.toByteArray())

        val stream = RootMicStream(
            input = pushback,
            rate = rate,
            channels = channels,
            node = "card$card/d$device",
            closer = {
                runCatching { proc.destroy() }
                synchronized(children) { children.remove(proc) }
            },
        )
        return Opened(stream, peak, total)
    }

    // ── su / 部署 ───────────────────────────────────────────────────────────

    /**
     * vc199：su 调用统一交给 [SuShell]。
     *
     * vc198 这里有一份自己的实现，用 `proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)` 做超时 ——
     * 那是 **API 26+** 的 Java 8 API，FE6 是 api22，调用直接抛 `NoSuchMethodError`，
     * 又被 `runCatching { }.getOrDefault(false)` 静默吞成"超时"，于是 28 次探测全部
     * **15~41ms 就返回 `exit=-1`，却写着「等待授权超时 20000ms」**。
     *
     * 更扎心的是同一次运行里，体检（`Fe6VoiceProbe`）和 usbfx（`WiredUsbForensics`）
     * 用**轮询 `exitValue()`** 的写法都拿到了 `uid=0(root)` / 450 行 root dmesg。
     * 所以根因不是授权，是这一行 API。现在三处都改走 [SuShell]，杜绝第四次分叉。
     */
    private fun suExec(argv: List<String>, timeoutMs: Long): SuShell.Result =
        SuShell.run(argv, timeoutMs)


    /**
     * vc198：探测 root，并把**完整过程**写进 [suReport]。
     *
     * 与体检 `Fe6VoiceProbe.probeSu()` 逐字对齐：`su -c id` + `contains("uid=0")`。
     * vc197 用的是 `su -c id -u` + `trim() == "0"`（从未在实机验证过）—— 老版 su
     * （本机 vc194 日志实证带 `daemonsu` 的 SuperSU）可能把 `-u` 当自己的参数吃掉、
     * 或把命令二次分词，实际执行成 `id`，输出 `uid=0(root) gid=0(root) ...`，
     * `trim() == "0"` 永远为 false ⇒ **有 root 也判成未授权**。vc197 那次 31ms 就
     * 返回、连弹窗都没等，与该假设吻合。
     */
    private fun probeRoot(timeoutMs: Long = PROBE_SU_TIMEOUT_SESSION_MILLIS): Boolean {
        val report = StringBuilder()
        for (candidate in suCandidates()) {
            val result = suExec(listOf(candidate, "-c", "id"), timeoutMs)
            report.append("  ").append(result.summary()).append('\n')
            // ① 进程根本没起来（IOException/ENOENT）⇒ 这个路径没有可执行的 su，换下一个。
            if (result.launchError != null) continue
            // ② 等待期间抛异常 ⇒ **我们自己的代码问题**。必须当场说清楚，
            //    绝不能再像 vc198 那样把它伪装成「等待授权超时」。
            if (result.waitError != null) {
                suReport = "su 调用本身异常（不是授权问题）：${result.waitError}\n$report"
                diagnostics("root mic: su 调用异常 —— ${result.waitError}\n$report")
                return false
            }
            if (result.isRoot) {
                suBinary = candidate
                suReport = "uid=0（$candidate，${result.elapsedMs}ms）"
                diagnostics(
                    "root mic: su 可用 [$candidate] ${result.elapsedMs}ms " +
                        "${result.stdout.trim().replace('\n', ' ').take(80)}",
                )
                return true
            }
            suBinary = candidate
            suReport = if (result.timedOut) {
                "su 已就位但 ${timeoutMs}ms 内无响应（授权弹窗可能没弹出，或没点「允许」）：\n$report"
            } else {
                "su 已就位但未授权（$candidate）：\n$report"
            }
            diagnostics("root mic: root 探测失败，实测过程:\n$report")
            return false
        }
        suReport = "找不到可执行的 su，实测过程:\n$report"
        diagnostics("root mic: root 探测失败，实测过程:\n$report")
        return false
    }

    /**
     * su 的候选位置：先 PATH 里的 `su`（老 SuperSU 常见），再逐个绝对路径。
     * 只对**存在**的绝对路径发起调用，避免每次都白起 5 个必然失败的进程。
     */
    private fun suCandidates(): List<String> {
        val list = ArrayList<String>()
        list.add("su")
        for (path in SU_ABSOLUTE_PATHS) {
            if (!list.contains(path) && runCatching { File(path).exists() }.getOrDefault(false)) {
                list.add(path)
            }
        }
        return list
    }

    /**
     * 把随包注入的二进制放到 `/data/local/tmp` 并 chmod。
     * 不直接跑 `filesDir` 里的副本：App 私有目录是 `u0_aXX` 可执行，`su` 域（不同
     * SELinux 上下文）常常 exec 不了，只有 `/data/local/tmp` 两边都能碰。
     */
    private fun ensureDeployed(): Boolean {
        val abi = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && Build.SUPPORTED_ABIS.isNotEmpty()) {
                Build.SUPPORTED_ABIS[0]
            } else {
                @Suppress("DEPRECATION") Build.CPU_ABI
            }
        }.getOrDefault("armeabi-v7a")
        val asset = "tinycap/diplay-pcmcap-" + if (abi.contains("64")) "arm64-v8a" else "armeabi-v7a"
        val local = File(context.filesDir, "pcmcap/diplay-pcmcap")
        try {
            local.parentFile?.mkdirs()
            context.assets.open(asset).use { input ->
                local.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (error: Throwable) {
            deployNote = "asset 缺失($asset): ${error.javaClass.simpleName}"
            return false
        }
        val command = "cp ${local.absolutePath} $REMOTE && chmod 755 $REMOTE && ls -l $REMOTE"
        // vc199：部署这一步必须合并 stderr —— `cp`/`chmod` 失败的原因（SELinux denial、
        // 目标只读）都在 stderr 里，分开收就等于看不到为什么失败。
        val result = SuShell.run(suArgv(command), DEPLOY_SU_TIMEOUT_MILLIS, mergeError = true)
        if (!result.stdout.contains("diplay-pcmcap")) {
            deployNote = "cp/chmod 失败: ${result.summary()}"
            diagnostics("root mic: 二进制部署失败 —— $deployNote")
            return false
        }
        deployNote = result.stdout.trim()
        diagnostics("root mic: 二进制就位 [$deployNote]")
        return true
    }

    /** 拼成 `su -c "<命令串>"`：整串必须是一个参数，否则 su 只吃第一个词。 */
    private fun suArgv(command: String): List<String> {
        val bin = suBinary ?: "su"
        return listOf(bin, "-c", command)
    }

    /**
     * vc200：判断子进程是否已退出 —— **`Process.isAlive()` 的 api16 替身**。
     *
     * 🔴 `Process.isAlive()` 是 **API 26+**。api22（FE6）上它抛的是
     * `NoSuchMethodError: No virtual method isAlive()Z` —— 属于 `Error` 不是 `Exception`，
     * 被 `runCatching{}` 包住就会伪装成业务失败。vc199 正是因为这一行，400ms 探测窗口
     * **一毫秒都没用上**（`available()==0` 在 su 刚拉起时立刻成立，第一次轮询就炸）。
     *
     * `exitValue()` 是 **API 1**，进程还在跑时抛 `IllegalThreadStateException`。
     * 全项目「等子进程」一律走这个形状：轮询 `exitValue()` + `Thread.sleep`。
     */
    private fun hasExited(proc: Process): Boolean = try {
        proc.exitValue()
        true
    } catch (_: IllegalThreadStateException) {
        false
    }

    private fun peakOf(buf: ByteArray, length: Int): Int {
        var peak = 0
        var i = 0
        while (i + 1 < length) {
            val sample = ((buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)).toShort().toInt()
            val magnitude = if (sample < 0) -sample else sample
            if (magnitude > peak) peak = magnitude
            i += 2
        }
        return peak
    }

    private companion object {
        /** `/data/local/tmp` 是 su 域与 app 域都允许执行的位置。 */
        const val REMOTE = "/data/local/tmp/diplay-pcmcap"
        /** 采集进程最长跑 900s（Siri 会话远短于此），到点退出后由上层决定是否重开。 */
        const val CAPTURE_SECONDS = 900
        /**
         * 单档探测窗口。
         *
         * vc199 从 1200ms 降到 **400ms**：Siri 上行整场只活 1.8~3.2s（vc198 实测 1.78s），
         * 而 vc198 的 `root fallback #1` 在被触发时距离 iPhone TEARDOWN 只剩 **276ms**。
         * 每档要 1.2s 的话，四档跑完必然错过整个会话 —— 探测快比探测全更重要。
         * 400ms @16k/2ch 能收 12.8KB（≈6 个 period），算 peak 完全够。
         */
        const val PROBE_WINDOW_MILLIS = 400L
        /** 单档探测最多留 32KB（@16k/2ch ≈ 0.5s），够判峰值又不至于吃内存。 */
        const val PROBE_BYTES = 32 * 1_024
        /** 四档总预算：4×400ms + 启动开销，必须明显小于 Siri 的存活时间。 */
        const val PROBE_TOTAL_MILLIS = 2_500L

        /**
         * su 的常见绝对路径。vc194 日志实测本机只有 `/system/xbin/su` + `daemonsu`
         * （老版 SuperSU）；其余几条覆盖 Magisk systemless（`/sbin/su`、`/su/bin/su`、
         * `/magisk/bin/su`）与 AOSP 原生（`/system/bin/su`）。
         */
        val SU_ABSOLUTE_PATHS = listOf(
            "/system/xbin/su",
            "/system/bin/su",
            "/sbin/su",
            "/su/bin/su",
            "/magisk/bin/su",
            "/data/local/tmp/su",
        )

        /** 设置页前台点击授权时的超时：授权弹窗要人等，给足。 */
        const val PROBE_SU_TIMEOUT_MILLIS = 20_000L
        /**
         * 会话内探测的超时：此时 App 在后台/投屏全屏，授权框可能弹不出来。
         * 不能等 20s —— Siri 整场只活 3 秒多，等下去等于放弃这一次。6s 够 su 快速返回。
         */
        const val PROBE_SU_TIMEOUT_SESSION_MILLIS = 6_000L
        /** 部署（cp+chmod）通常毫秒级，给 8s 足够，超时说明 su 挂住。 */
        const val DEPLOY_SU_TIMEOUT_MILLIS = 8_000L
    }
}
