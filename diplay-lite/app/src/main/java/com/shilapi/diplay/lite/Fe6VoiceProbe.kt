// vc193：FE6 root 语音流路径取证 + 多档回落矩阵。
//
// 背景（日志 20261028-174703，vc192 分析 工程文档/日志分析-20261028-FE6-vc192-Siri断链.md）：
// FE6 有线 Siri 的麦克风上行有两种互斥失效形态，现有代码只能探测其中一种：
//   α「读到全零」 v1.1.3/v1.1.9 共 4 次 first packet 成功，但 level window peak=0
//   β「读阻塞」   v1.2.x 起 first packet 与 level window 双零，无 error、无 zero-read
// β 形态在 AudioRecord 语义下既不是错误码也不是 0 字节，采集线程永远卡在第一帧 ——
// 现有 zeroReadStreak / peak==0 两个自愈器都触发不了，日志里只剩一句 uplink started。
//
// 本探针用 root 拿底层证据（App 层已到顶）：
//   B1 节点拓扑   /proc/asound/cards|pcm + /dev/snd 列表（有几路 capture）
//   B2 当前占用   /proc/asound/card*/pcm*c/sub0/status 的 RUNNING/owner_pid ← 决定性
//   B3 AudioFlinger 输入线程是否 active、client 是谁
//   B4 QG/ECARX 语音策略、属性、候选占用包
//   B5 mixer 增益与静音位（tinymix 全量 dump，宿主侧过滤）
//   C  L0..L6 AudioRecord 矩阵（8 档源×率×声道，独立线程 + 超时 stop 唤醒阻塞读）
//      R1 root PCM 直采（自带 diplay-pcmcap，绕开 AudioFlinger）
//      R2 停占用者后重测 L0    R3 解 mic-mute 后重测 L0
//
// 纪律（沿用项目既有裁决）：
//  · su 一律「写脚本 + 单条短命令 sh <path>」并自报 uid：T3 鼎微 SuperSU 对含管道/
//    分号的长命令串静默降权（vc100 定案，见 RootNcmReclaimer 头注释）。ECARX 的 su
//    实现无样本，一律按「可能降权」处理，uid≠0 立即终止整轮。
//  · su -c 内不写管道/重定向：老 ROM（T3 toybox 实锤）管道失效，一律全量拉回宿主侧过滤。
//  · R2/R3 是写操作，必须由用户在设置页显式勾选授权，且只碰白名单目标、只记改前值。
//  · 全程只写 App 外部私有目录（getExternalFilesDir，免运行时权限）+ LiteLog，
//    不依赖 /sdcard 写权限（targetSdk 34 + api22 上运行期授权不可控）。
package com.shilapi.diplay.lite

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.shilapi.xcertplay.root.SuShell
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

internal object Fe6VoiceProbe {

    /** 与其它探针统一走 LiteLog，tag 固定为 micprobe，便于日志仓 grep。 */
    private const val TAG = "micprobe"

    private const val SU_TIMEOUT_MILLIS = 8_000L
    private const val MATRIX_SECONDS = 3
    /** read 超时后留给 stop() 唤醒阻塞读的窗口：太短会误判 BLOCKED。 */
    private const val MATRIX_STOP_GRACE_MILLIS = 1_200L
    /** vc194：AudioRecord 构造超时。MTK HAL openInputStream 死等时必须在 4s 内放弃。 */
    private const val OPEN_TIMEOUT_MILLIS = 4_000L
    /** vc194：stop+release 的限时（跑在守护线程，绝不阻塞体检线程）。 */
    private const val CLOSE_DEADLINE_MILLIS = 1_500L
    /** vc194：整个体检的硬上限。超时强制释放并复位，防止僵尸 AudioRecord 占住正式 Siri 的麦克风。 */
    /**
     * vc195：体检总看门狗从 180s 提到 **600s**。
     *
     * vc194 的 180s 是按当时流程算的；vc195 加了 R1a 三段扫描（最多 3×75s）+
     * R1b 四个节点真采 + 能读档复采，正常路径就要 180~240s，180s 会把它半路掐断。
     * 这个看门狗的作用是「防僵尸 AudioRecord 占住麦克风」，不是催命，宁宽勿紧。
     */
    private const val SCAN_WATCHDOG_MILLIS = 600_000L
    /** vc194：progress 心跳落日志间隔（卡死时靠它定位阶段）。 */
    private const val HEARTBEAT_MILLIS = 5_000L
    /** vc195：两轮采样都必须落在 MATRIX_SECONDS(3s) 采集窗内（vc194 的 1.5s/7s 第二轮已跑出窗）。 */
    private const val CAPTURE_SAMPLE_DELAY_MILLIS = 1_000L
    /** vc195：第二轮相对第一轮的间隔（累计 2.2s，仍在 3s 窗内）。 */
    private const val CAPTURE_SAMPLE_GAP_MILLIS = 1_200L
    private const val CAPTURE_SAMPLE_ROUNDS = 2
    /** vc194：等采集期采样器收尾的上限。 */
    private const val CAPTURE_SAMPLE_WAIT_MILLIS = 20_000L

    /** vc195：R1a 每段（8 个节点）的时限。8×(open4+alarm) 留足余量。 */
    private const val R1_SCAN_SEGMENT_TIMEOUT_MILLIS = 75_000L
    /** vc195：R1b 单个节点真采的时限（采集窗口 + alarm 硬超时余量）。 */
    private const val R1_CAPTURE_TIMEOUT_MILLIS = 30_000L
    /** vc195：R1b 最多试几个节点（按 aif3→aif4→其余 排序后取前 N）。 */
    private const val R1_MAX_NODES = 4
    /** vc195：R1 每次真采的秒数。2s 足够听见「喂」一声，又不至于拖长体检。 */
    private const val R1_SECONDS = 2

    // ── 供设置页轮询的状态（全部 volatile，探针线程写、UI 线程读）──
    @Volatile var running: Boolean = false
        private set
    @Volatile var lastConclusion: String = "-"
        private set
    @Volatile var lastSuState: String = "未探测（点「只做拓扑侦察」可单测）"
        private set
    @Volatile var progress: String = ""
        private set
    /** vc194：看门狗已强制中止本次体检（后续阶段应尽快退出）。 */
    @Volatile var aborted: Boolean = false
        private set

    /** 体检结果块（设置页直接显示多行，含 root 状态与矩阵摘要）。 */
    @Volatile var summary: String = "尚未体检"
        private set

    private val reportLines = ArrayList<String>()
    private val reportLock = Any()

    // vc194：采集期快照（B5T/B2T）由守护线程写、体检线程读
    @Volatile private var duringMixer: String = ""
    @Volatile private var duringOccupancy: String = ""
    @Volatile private var samplerRef: Thread? = null
    /** vc194：看门狗代际。正常结束时自增，看门狗据此退出。 */
    private final val scanGeneration = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * F5：体检总看门狗（180s 硬上限）+ F6 心跳。
     *
     * vc193 实测：矩阵卡死后 `running` 长真、体检线程永久持有 AudioRecord，
     * 用户随后连 CarPlay 时这个僵尸录音可能仍占着 input，直接威胁正式 Siri 路径。
     * 这里保证无论卡在哪，到点都复位 `running` 并落 ABORT，UI 可再次操作。
     */
    private fun startScanWatchdog(onUpdate: (String) -> Unit) {
        val generation = scanGeneration.incrementAndGet()
        val dog = Thread({
            val deadline = SystemClock.elapsedRealtime() + SCAN_WATCHDOG_MILLIS
            while (true) {
                if (scanGeneration.get() != generation) return@Thread
                if (!running) return@Thread
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0) break
                runCatching { Thread.sleep(minOf(HEARTBEAT_MILLIS, left)) }
                if (scanGeneration.get() != generation || !running) return@Thread
                // F6：心跳落日志，卡死时能反推停在哪一段
                val stage = progress.ifEmpty { "…" }
                report("HEARTBEAT left=${(deadline - SystemClock.elapsedRealtime()) / 1000}s stage=$stage")
            }
            if (scanGeneration.get() != generation) return@Thread
            aborted = true
            running = false
            report("ABORT watchdog=$SCAN_WATCHDOG_MILLIS stage=$progress")
            LiteLog.log(TAG, "scan aborted by watchdog at stage=$progress")
            runCatching { samplerRef?.interrupt() }
            emit(onUpdate, "体检超过 ${SCAN_WATCHDOG_MILLIS / 1000}s 已强制中止（卡在：$progress）")
        }, "micprobe-watchdog")
        dog.isDaemon = true
        dog.start()
    }

    private fun stopScanWatchdog() {
        scanGeneration.incrementAndGet()
        runCatching { samplerRef?.interrupt() }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 对外入口
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 一键体检：B（拓扑取证）+ C（矩阵）。约 40~80s，必须在独立线程调用。
     *
     * @param allowWrite 用户显式授权 R2（停占用者）/ R3（解 mic-mute）。false 时两档 SKIP。
     * @param onUpdate    每完成一段回调一次（UI 线程安全由调用方负责，设置页用 runOnUiThread）。
     */
    fun runFullScan(context: Context, allowWrite: Boolean, onUpdate: (String) -> Unit) {
        if (!startGuard(onUpdate)) return
        startScanWatchdog(onUpdate)
        val app = context.applicationContext
        val started = System.currentTimeMillis()
        try {
            val dir = reportDir(app, started)
            emit(onUpdate, "开始体检（root 取证 + 矩阵），报告目录 ${dir.name}")
            report("BEGIN version=${BuildConfig.VERSION_NAME} vc=${BuildConfig.VERSION_CODE} " +
                "abi=${abi()} sdk=${Build.VERSION.SDK_INT} ts=$started")

            // ── 层 B ──
            val su = probeSu()
            lastSuState = su
            emit(onUpdate, "root 状态：$su")
            if (!su.contains("uid=0")) {
                lastConclusion = "无 root（$su）—— B2/B3/B5 与 R1-R3 全部无法取证，只能跑 L0-L6"
                emit(onUpdate, lastConclusion)
                runAudioRecordMatrix(onUpdate)
                finish(app, dir, onUpdate)
                return
            }

            val b2 = reportStage("B2", onUpdate) { collectOccupancy() }
            val b4 = reportStage("B4", onUpdate) { collectPolicy() }
            reportStage("B1", onUpdate) { collectTopology() }
            reportStage("B3", onUpdate) { collectAudioFlinger() }
            val b5 = reportStage("B5", onUpdate) { collectMixer() }

            // ── 层 C：矩阵 → 选最能读的档 → 对它做采集期 mixer 快照 → root 档 ──
            val matrix = runAudioRecordMatrix(onUpdate)
            val bestTier = bestProbeTier(matrix)
            // vc195 G3：采样窗口必须落在**真正出数据的那一档**上。
            // vc194 把采样器挂在矩阵开头（1.5s / 7s），两次快照都落在 L0/L1 —— 而 L0/L1
            // 在 FE6 上是 blocked 档，压根没有有效流。于是拿到「mixer 纹丝不动」，还被写成了
            // 「【定案】HAL 没配 ADC」；唯一能读的 L6 期间一次采样都没有。
            // 现在：先跑完矩阵 → 挑出能读的档 → 在这一档采集时抓 B5T。
            startCaptureSampler(bestTier, onUpdate)
            val bestSnapshot = probeOneTier(bestTier)
            report("MATRIX ${bestTier.name}-snapshot $bestSnapshot")
            emit(onUpdate, "能读档 ${bestTier.name} 复采 → ${verdictOf(bestSnapshot)}")
            awaitCaptureSampler()
            val duringEvidence = mixerDiff(b5, duringMixer)
            report("B5-DIFF $duringEvidence")
            emit(onUpdate, "采集期 mixer 对比：${duringEvidence.take(160)}")
            if (duringOccupancy.isNotBlank()) {
                report("B2T-CAPTURED ${duringOccupancy.lineSequence().count()} lines")
            }
            val rootR1 = probeRootCapture(app, dir, onUpdate)

            var afterR2: String? = null
            var afterR3: String? = null
            if (allowWrite) {
                val occupant = pickOccupant(b4)
                if (occupant != null) {
                    afterR2 = attemptStopOccupant(occupant, bestTier, onUpdate)
                } else {
                    emit(onUpdate, "R2 跳过：B2/B4 未识别出可白名单停用的占用者")
                    report("R2 SKIP no whitelist occupant")
                }
                // vc195：R3 的目标必须来自**R3 时刻的新鲜 dump**。vc194 拿体检开头的 b5
                // 选目标，重跑时会看到上次已改过的值（MIC Input Volume 已是 10）从而漏判。
                val preR3 = reportStage("B5R", onUpdate) { collectMixer("B5R") }
                val beforeValues = mixerValueMap(preR3)
                val r3Targets = pickR3Targets(preR3)
                if (r3Targets.isNotEmpty()) {
                    afterR3 = attemptUnmute(r3Targets, beforeValues, bestTier, onUpdate)
                } else {
                    emit(onUpdate, "R3 跳过：B5R 未发现静音位/零增益/关断的 ADC")
                    report("R3 SKIP no target control")
                }
            } else {
                emit(onUpdate, "R2/R3 未授权（写操作）：可在设置页勾选「允许修改车机音频」后重跑")
                report("R2/R3 SKIP not authorized")
            }

            lastConclusion = conclusionOf(
                matrix, rootR1, b2, b5, duringEvidence, afterR2, afterR3,
                bestTier.name, bestSnapshot,
            )
            emit(onUpdate, lastConclusion)
            report("CONCLUSION $lastConclusion")
            finish(app, dir, onUpdate)
        } catch (error: Throwable) {
            stopScanWatchdog()
            lastConclusion = "体检异常：${error.javaClass.simpleName}: ${error.message}"
            LiteLog.log(TAG, "full scan failed ${error.javaClass.simpleName}: ${error.message}")
            emit(onUpdate, lastConclusion)
            running = false
        }
    }

    /** 只做 root 拓扑侦察（约 8s）：不出矩阵，适合先确认 root 与节点/占用。 */
    fun runTopologyOnly(context: Context, onUpdate: (String) -> Unit) {
        if (!startGuard(onUpdate)) return
        val app = context.applicationContext
        try {
            val dir = reportDir(app, System.currentTimeMillis())
            report("BEGIN(TOPOLOGY ONLY) version=${BuildConfig.VERSION_NAME}")
            val su = probeSu()
            lastSuState = su
            emit(onUpdate, "root 状态：$su")
            if (!su.contains("uid=0")) {
                lastConclusion = "无 root（$su）"
                emit(onUpdate, lastConclusion)
                finish(app, dir, onUpdate)
                return
            }
            reportStage("B1", onUpdate) { collectTopology() }
            val b2 = reportStage("B2", onUpdate) { collectOccupancy() }
            reportStage("B3", onUpdate) { collectAudioFlinger() }
            reportStage("B4", onUpdate) { collectPolicy() }
            reportStage("B5", onUpdate) { collectMixer() }
            lastConclusion = "拓扑侦察完成：${summarizeOccupancy(b2)}"
            emit(onUpdate, lastConclusion)
            report("CONCLUSION $lastConclusion")
            finish(app, dir, onUpdate)
        } catch (error: Throwable) {
            lastConclusion = "拓扑侦察异常：${error.javaClass.simpleName}: ${error.message}"
            emit(onUpdate, lastConclusion)
            running = false
        }
    }

    private fun startGuard(onUpdate: (String) -> Unit): Boolean {
        if (running) {
            emit(onUpdate, "已有体检在跑，请等它结束")
            return false
        }
        running = true
        aborted = false
        reportLines.clear()
        progress = ""
        return true
    }

    private fun reportStage(
        name: String,
        onUpdate: (String) -> Unit,
        block: () -> String,
    ): String {
        progress = "$name 采集中…"
        val text = try {
            block()
        } catch (error: Throwable) {
            val msg = "[$name] 异常 ${error.javaClass.simpleName}: ${error.message}"
            report(msg)
            msg
        }
        emit(onUpdate, "[$name] done (${text.length} chars)")
        return text
    }

    // ─────────────────────────────────────────────────────────────────────
    // root 执行：写脚本 + 单条短命令 + uid 自报
    // ─────────────────────────────────────────────────────────────────────

    /** su 可用性：短命令即可，不需要脚本。返回 "uid=0" 或失败描述。 */
    private fun probeSu(): String {
        val out = suExec("id", 5_000L).trim()
        return when {
            out.isEmpty() -> "su 无响应（未授权/无 su）"
            out.contains("uid=0") -> "uid=0（已获得 root）"
            else -> "可能被降权输出=${out.take(80)}"
        }
    }

    /**
     * 把脚本写到私有 filesDir 再 `su -c "sh <path>"`。
     * 理由见文件头纪律：T3 SuperSU 对长命令串静默降权，脚本文件模式是唯一稳定用法。
     */
    private fun runSuScript(context: Context, name: String, body: String, timeoutMillis: Long): String {
        val path = File(context.filesDir, "micprobe-$name.sh")
        return try {
            path.writeText(body)
            suExec("sh ${path.absolutePath}", timeoutMillis)
        } catch (error: Throwable) {
            report("[$name] script write failed: ${error.javaClass.simpleName}")
            ""
        }
    }

    /**
     * 执行 su 命令，pump 线程收输出，超时强杀。
     *
     * vc199：改为转调 [SuShell]（全项目唯一实现）。本文件原先是「轮询 exitValue」的
     * 正确写法，但 vc198 在 `Fe6RootMicSource` 里另写了一份 `waitFor(timeout, unit)`
     * （API 26+）导致 root 探测全灭 —— 三份分叉就是根因，现在统一。
     * 语义保持不变：stderr 并在 stdout 里一起返回。
     */
    private fun suExec(command: String, timeoutMillis: Long = SU_TIMEOUT_MILLIS): String {
        val result = SuShell.su(command, timeoutMillis, mergeError = true)
        if (result.waitError != null) {
            report("su 调用异常: ${result.waitError} cmd=${command.take(40)}")
        } else if (result.timedOut) {
            report("su timeout after ${timeoutMillis}ms cmd=${command.take(40)}")
        } else if (result.launchError != null) {
            report("su 启动失败: ${result.launchError} cmd=${command.take(40)}")
        }
        return result.stdout.trim()
    }

    // ─────────────────────────────────────────────────────────────────────
    // 层 B：五组取证
    // ─────────────────────────────────────────────────────────────────────

    private fun collectTopology(): String {
        val ctx = appCtx() ?: return "".also { report("[B1] no app context") }
        val body = """
            echo "[B1] uid=$(id -u)"
            echo "[B1] --- /proc/asound/cards"
            cat /proc/asound/cards
            echo "[B1] --- /proc/asound/pcm"
            cat /proc/asound/pcm
            echo "[B1] --- /dev/snd"
            ls -l /dev/snd
            echo "[B1] --- tinyalsa tools"
            ls /system/bin/tinycap /system/bin/tinymix /system/bin/tinypcminfo /system/xbin/tinycap /system/xbin/tinymix
            echo "[B1] --- done"
        """.trimIndent()
        val out = runSuScript(ctx, "b1", body, 10_000L)
        out.lineSequence().filter { it.isNotBlank() }.forEach { report(it) }
        return out
    }

    /**
     * B2：当前 PCM 占用。
     *
     * vc193 教训（实测 20261028-190537，B2 只有 57 chars 空转）：原实现硬编码
     * `card0-3 / device0-3`，但 **Linux 的 substream 目录只在流运行时才存在**，
     * 车机空闲时 `/proc/asound/card0/pcm1c/sub0/` 整个不出现，`[ -f ]` 全部为假。
     * vc194 改 glob 遍历真实存在的目录，并把「扫到几个」也落日志，空结果可自证。
     */
    private fun collectOccupancy(tag: String = "B2"): String {
        val ctx = appCtx() ?: return "".also { report("[$tag] no app context") }
        val body = """
            echo "[$tag] uid=$(id -u)"
            echo "[$tag] --- capture dirs"
            ls -d /proc/asound/card*/pcm*c/sub* 2>/dev/null
            echo "[$tag] --- detail"
            for d in /proc/asound/card*/pcm*c/sub*; do
              if [ -d "${'$'}d" ]; then
                echo "[$tag] dir=${'$'}d"
                cat ${'$'}d/status 2>/dev/null
                cat ${'$'}d/hw_params 2>/dev/null
                echo "[$tag] enddir"
              fi
            done
            echo "[$tag] done"
        """.trimIndent()
        val out = runSuScript(ctx, tag.lowercase(), body, 12_000L)
        out.lineSequence().filter { it.isNotBlank() }.forEach { report(it) }
        return out
    }

    private fun collectAudioFlinger(): String {
        val out = suExec("dumpsys media.audio_flinger", 12_000L)
        if (out.isEmpty()) {
            report("[B3] dumpsys media.audio_flinger 无输出")
            return ""
        }
        val keep = out.lineSequence()
            .filter { line ->
                val l = line.lowercase()
                l.contains("input") || l.contains("active") || l.contains("pid") ||
                    l.contains("client") || l.contains("thread") || l.contains("record")
            }
            .take(90)
            .toList()
        keep.forEach { report("[B3] ${it.trim()}") }
        report("[B3] total_lines=${out.lineSequence().count()} kept=${keep.size}")
        return keep.joinToString("\n")
    }

    private fun collectPolicy(): String {
        val ctx = appCtx() ?: return "".also { report("[B4] no app context") }
        val body = """
            echo "[B4] uid=$(id -u)"
            echo "[B4] --- audio/mic props"
            getprop
            echo "[B4] --- settings global mic"
            settings list global
            echo "[B4] --- settings system mic"
            settings list system
            echo "[B4] --- packages"
            pm list packages
            echo "[B4] --- ps audio/voice"
            ps
            echo "[B4] done"
        """.trimIndent()
        val raw = runSuScript(ctx, "b4", body, 15_000L)
        // 全量拉回宿主侧过滤：老 ROM 的 su 内管道不可用（T3 实锤）。
        var section = ""
        val kept = ArrayList<String>()
        raw.lineSequence().forEach { line ->
            when {
                line.startsWith("[B4] ---") -> {
                    section = line.removePrefix("[B4] ---").trim()
                    report("[B4] section=$section")
                }
                line.startsWith("[B4]") -> kept.add(line)
                section.startsWith("audio/mic props") -> {
                    val l = line.lowercase()
                    if (l.contains("audio") || l.contains("mic") || l.contains("voice") ||
                        l.contains("qg") || l.contains("ecarx")
                    ) {
                        kept.add("[B4-prop] ${line.trim()}")
                    }
                }
                section.startsWith("settings") -> {
                    if (line.lowercase().contains("mic")) kept.add("[B4-set] ${section}: ${line.trim()}")
                }
                section.startsWith("packages") -> {
                    val l = line.lowercase()
                    if (l.contains("qinggan") || l.contains("speech") || l.contains("voice") ||
                        l.contains("assistant") || l.contains("recorder") || l.contains("audio")
                    ) {
                        kept.add("[B4-pkg] ${line.trim()}")
                    }
                }
                section.startsWith("ps audio") -> {
                    val l = line.lowercase()
                    if (l.contains("audio") || l.contains("voice") || l.contains("assistant") ||
                        l.contains("qinggan") || l.contains("speech")
                    ) {
                        kept.add("[B4-ps] ${line.trim()}")
                    }
                }
            }
        }
        kept.take(120).forEach { report(it) }
        report("[B4] kept=${kept.size} rawLen=${raw.length}")
        return kept.joinToString("\n")
    }

    /** B5：mixer 增益与静音位全量 dump（宿主侧过滤）。tag 可换（B5T = 采集期快照）。 */
    private fun collectMixer(tag: String = "B5"): String {
        val out = suExec("tinymix", 10_000L)
        if (out.isEmpty()) {
            report("[$tag] tinymix 无输出（工具不存在或 ROM 无此节点）")
            return ""
        }
        val filtered = out.lineSequence()
            .filter { line ->
                val l = line.lowercase()
                l.contains("mic") || l.contains("adc") || l.contains("mute") ||
                    l.contains("capture") || l.contains("input")
            }
            .take(80)
            .toList()
        filtered.forEach { report("[$tag] ${it.trim()}") }
        report("[$tag] total=${out.lineSequence().count()} kept=${filtered.size}")
        return filtered.joinToString("\n")
    }

    /**
     * F3：L0 采集**进行中**再抓一次 mixer + 占用（tag B5T/B2T），与空闲态 B5/B2 对比。
     *
     * 这是全案定性的最后一步：FE6 空闲态 `MIC Input Volume=0` + `Audio_ADC_x_Switch=Off`
     * 可能只是省电默认。**只有采集期间的值才有判据力**：
     *   · 采集中被 HAL 调上去了 → 空闲快照是假象，问题在 AudioFlinger 以上
     *   · 采集中纹丝不动     → ECARX HAL 根本没为第三方 input 配 CSM/ADC（定案）
     *
     * 用法：矩阵开跑前 [startCaptureSampler]，矩阵跑完后 [awaitCaptureSampler] 取结果。
     */
    private fun startCaptureSampler(target: Tier, onUpdate: (String) -> Unit) {
        duringMixer = ""
        duringOccupancy = ""
        val sampler = Thread({
            val snapshots = ArrayList<String>()
            for (round in 1..CAPTURE_SAMPLE_ROUNDS) {
                val delay = if (round == 1) CAPTURE_SAMPLE_DELAY_MILLIS else CAPTURE_SAMPLE_GAP_MILLIS
                runCatching { Thread.sleep(delay) }
                if (Thread.currentThread().isInterrupted) break
                progress = "采集中快照 B5T$round（${target.name}）…"
                val snap = runCatching { collectMixer("B5T$round") }.getOrDefault("")
                if (snap.isNotBlank()) snapshots.add(snap)
                if (round == 1) {
                    duringOccupancy = runCatching { collectOccupancy("B2T") }.getOrDefault("")
                }
            }
            duringMixer = snapshots.joinToString("\n")
        }, "micprobe-sampler")
        sampler.isDaemon = true
        samplerRef = sampler
        sampler.start()
        emit(
            onUpdate,
            "已挂采集期快照采样器：${target.name} 采集期间抓 ${CAPTURE_SAMPLE_ROUNDS} 次 mixer",
        )
    }

    /** 等采集期采样器收尾（限时，采样器是守护线程，卡住也不拖体检）。 */
    private fun awaitCaptureSampler() {
        val sampler = samplerRef ?: return
        runCatching { sampler.join(CAPTURE_SAMPLE_WAIT_MILLIS) }
        samplerRef = null
    }

    /** 比较空闲态与采集期的 mixer，只输出**有变化**的行（无变化即铁证）。 */
    private fun mixerDiff(idle: String, during: String): String {
        if (during.isBlank()) return "B5T 无数据（采样器未完成或 tinymix 失败）"
        val before = mixerValueMap(idle)
        val afterMap = mixerValueMap(during)
        if (before.isEmpty() || afterMap.isEmpty()) {
            return "diff 跳过（idle=${before.size} during=${afterMap.size} 项，解析不足）"
        }
        val changed = ArrayList<String>()
        for ((name, value) in afterMap) {
            val old = before[name] ?: continue
            if (old != value) changed.add("$name: $old -> $value")
        }
        return if (changed.isEmpty()) {
            "采集期与空闲态**完全一致**（${afterMap.size} 项无变化）⇒ HAL 未为第三方 input 配置 ADC"
        } else {
            "采集期变化 ${changed.size} 项：" + changed.joinToString(" | ")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 层 C：AudioRecord 矩阵
    // ─────────────────────────────────────────────────────────────────────

    private data class Tier(val name: String, val source: Int, val rate: Int, val channels: Int)

    /**
     * vc196：矩阵补 24000Hz 与 stereo 档。
     *
     * L0–L6 保持原样（与 vc194/vc195 日志逐档可比，`L6=源0/44.1k` 的历史结论不作废）；
     * L7–L12 是本次新增，针对 vc195 暴露的两个盲点：
     * - **真实业务档是 `源6/24000`**（vc195 实测首包 `body=960B = 24k×1ch×20ms×2B`），
     *   而旧矩阵只有 48k/16k/44.1k ⇒ 源 6 被整片误判成 `BLOCKED`，R3 复测落错档。
     * - **车机自己的录音进程以 `16000/2ch` 吃 `pcm1c`(aif3)**，而旧矩阵**全是 mono**
     *   ⇒ 27 个版本从未试过 stereo。
     */
    private fun tiers(): List<Tier> = listOf(
        Tier("L0", MediaRecorder.AudioSource.VOICE_RECOGNITION, 48_000, 1),
        Tier("L1", MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000, 1),
        Tier("L2", MediaRecorder.AudioSource.MIC, 48_000, 1),
        Tier("L3", MediaRecorder.AudioSource.MIC, 16_000, 1),
        Tier("L4", MediaRecorder.AudioSource.CAMCORDER, 48_000, 1),
        Tier("L5", MediaRecorder.AudioSource.VOICE_COMMUNICATION, 48_000, 1),
        Tier("L6", MediaRecorder.AudioSource.DEFAULT, 44_100, 1),
        // ── vc196 新增 ────────────────────────────────────────────────────
        Tier("L7", MediaRecorder.AudioSource.VOICE_RECOGNITION, 24_000, 1),  // 真实业务档
        Tier("L8", MediaRecorder.AudioSource.VOICE_RECOGNITION, 24_000, 2),  // 最强候选
        Tier("L9", MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000, 2),  // 车机 pid3191 档
        Tier("L10", MediaRecorder.AudioSource.DEFAULT, 16_000, 2),
        Tier("L11", MediaRecorder.AudioSource.MIC, 24_000, 1),
        Tier("L12", MediaRecorder.AudioSource.VOICE_COMMUNICATION, 24_000, 1),
    )

    /** 跑完全部 AudioRecord 矩阵档（vc196 起 13 档），返回逐档结果文本（同时写进 report）。 */
    private fun runAudioRecordMatrix(onUpdate: (String) -> Unit): String {
        val sb = StringBuilder()
        val all = tiers()
        report("MATRIX_BEGIN tiers=${all.size} seconds=$MATRIX_SECONDS openTimeout=$OPEN_TIMEOUT_MILLIS")
        emit(onUpdate, "AudioRecord 矩阵开始（${all.size} 档，每档 ≤ ${OPEN_TIMEOUT_MILLIS / 1000 + MATRIX_SECONDS + 2}s）")
        for ((index, tier) in all.withIndex()) {
            if (aborted) {
                report("MATRIX_ABORT at ${tier.name}")
                break
            }
            progress = "矩阵 ${tier.name}（${index + 1}/${all.size}）采集中…"
            // vc194：**进入即落日志**。vc193 卡死时一行 MATRIX 都没有，全靠 B5 时间戳反推。
            report("MATRIX_ENTER ${tier.name} src=${tier.source} rate=${tier.rate} ch=${tier.channels}")
            val tierStart = SystemClock.elapsedRealtime()
            val line = probeOneTier(tier)
            sb.append(line).append('\n')
            report("MATRIX $line")
            emit(onUpdate, "矩阵 ${tier.name} → ${verdictOf(line)}")
            LiteLog.log(TAG, "tier ${tier.name} done in ${SystemClock.elapsedRealtime() - tierStart}ms")
        }
        report("MATRIX_END")
        return sb.toString()
    }

    /**
     * 单档探测。**vc194 重写：把「构造」「startRecording」「read」全部纳入超时保护。**
     *
     * vc193 的致命缺陷（实测 20261028-190537 日志，B5 之后 685s 零日志、MATRIX 0 条）：
     * `AudioRecord(...)` 构造在 executor 外、`record.stop()` 在体检线程同步调用，
     * 两者都在 `Future.get(超时)` 的保护范围之外。MTK HAL 的 openInputStream 死等时
     * 就永久阻塞，连「超时」都触发不了 —— 整个体检卡死，层 C 零产出。
     *
     * vc194 结构：
     *   ① 构造跑在 executor 里 → `openFuture.get(OPEN_TIMEOUT)`，超时判 `OPEN_BLOCKED`
     *      （构造卡死本身就是 β 形态的铁证，比读阻塞更早、更狠）
     *   ② `startRecording` + read 循环跑在第二步 future → `get(MATRIX_SECONDS)`
     *   ③ `stop()/release()` 一律丢给独立守护线程 + `join(CLOSE_DEADLINE)`，
     *      **绝不在体检线程上调用**，HAL 死锁也拖不住主流程
     *   ④ 任何一步超时都落一行带 `stage=` 的 verdict，卡住也能定位到阶段
     */
    private fun probeOneTier(tier: Tier): String {
        val mask = if (tier.channels >= 2) {
            AudioFormat.CHANNEL_IN_STEREO
        } else {
            AudioFormat.CHANNEL_IN_MONO
        }
        val minBuffer = runCatching {
            AudioRecord.getMinBufferSize(tier.rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        }.getOrDefault(-1)
        if (minBuffer <= 0) {
            return "${tier.name} verdict=OPEN_FAIL src=${tier.source} rate=${tier.rate} " +
                "ch=${tier.channels} stage=minBuffer note=\"minBuffer=$minBuffer\""
        }
        val bufferSize = maxOf(minBuffer * 2, 4096)

        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "micprobe-${tier.name}") }
        val holder = AtomicReference<AudioRecord?>(null)
        var openMs = -1L
        var openBlocked = false
        var openError = ""
        var startBlocked = false
        var startMs = -1L

        // ── 第一步：构造（可阻塞，务必限时）──
        val openStart = SystemClock.elapsedRealtime()
        val openFuture: Future<Unit> = executor.submit(Callable {
            val rec = AudioRecord(
                tier.source,
                tier.rate,
                mask,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
            openMs = SystemClock.elapsedRealtime() - openStart
            holder.set(rec)
        })
        try {
            openFuture.get(OPEN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            openBlocked = true
        } catch (failure: Throwable) {
            val cause = failure.cause ?: failure
            openError = "${cause.javaClass.simpleName}:${cause.message ?: ""}".take(90)
        }

        if (openBlocked) {
            report("MATRIX_OPEN_BLOCKED ${tier.name} src=${tier.source} rate=${tier.rate} " +
                "waitedMs=$OPEN_TIMEOUT_MILLIS")
            // 构造卡在 native 里：没有对象可 stop，只能放弃这个 executor 线程（守护性质，不阻退出）
            runCatching { holder.get()?.let { closeRecordWithDeadline(it) } }
            runCatching { executor.shutdownNow() }
            return "${tier.name} verdict=OPEN_BLOCKED src=${tier.source} rate=${tier.rate} " +
                "ch=${tier.channels} stage=construct waitedMs=$OPEN_TIMEOUT_MILLIS " +
                "minBuf=$minBuffer openMs=-1"
        }
        if (openError.isNotEmpty()) {
            runCatching { executor.shutdownNow() }
            return "${tier.name} verdict=OPEN_FAIL src=${tier.source} rate=${tier.rate} " +
                "ch=${tier.channels} stage=construct openMs=$openMs note=\"$openError\""
        }

        val record = holder.get()
        if (record == null) {
            runCatching { executor.shutdownNow() }
            return "${tier.name} verdict=OPEN_FAIL src=${tier.source} rate=${tier.rate} " +
                "ch=${tier.channels} stage=construct note=\"null record\""
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            val state = record.state
            closeRecordWithDeadline(record)
            runCatching { executor.shutdownNow() }
            return "${tier.name} verdict=OPEN_FAIL src=${tier.source} rate=${tier.rate} " +
                "ch=${tier.channels} stage=construct openMs=$openMs state=$state " +
                "note=\"STATE_UNINITIALIZED\""
        }

        // ── 第二步：startRecording + read 循环 ──
        val startedAt = SystemClock.elapsedRealtime()
        var readCalls = 0
        var bytes = 0L
        var peak = 0
        var firstByteMs = -1L
        var readError = 0
        val readFuture: Future<Unit> = executor.submit(Callable {
            val buffer = ByteArray(2048)
            val startAt = SystemClock.elapsedRealtime()
            record.startRecording()
            startMs = SystemClock.elapsedRealtime() - startAt
            while (!Thread.currentThread().isInterrupted) {
                val n = record.read(buffer, 0, buffer.size)
                if (n < 0) {
                    readError = n
                    break
                }
                if (n == 0) continue
                readCalls++
                if (firstByteMs < 0) firstByteMs = SystemClock.elapsedRealtime() - startedAt
                bytes += n
                val p = peakOf(buffer, n)
                if (p > peak) peak = p
            }
        })

        var blocked = false
        try {
            readFuture.get(MATRIX_SECONDS * 1000L + MATRIX_STOP_GRACE_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            blocked = true
            if (startMs < 0) startBlocked = true
        } catch (_: Throwable) {
            // read 抛异常（部分 ROM 在 stop 时抛 IllegalStateException）：按读结束处理
        } finally {
            // ③ 限时关闭：独立守护线程，HAL 死锁也拖不住体检主流程
            closeRecordWithDeadline(record)
            runCatching { readFuture.cancel(true) }
            runCatching { executor.shutdownNow() }
        }

        val elapsed = SystemClock.elapsedRealtime() - startedAt
        // vc195 G1：**判据顺序必须「先阻塞、后错误码」**。
        //
        // vc194 实测把 L0-L5 六档全部判成 READ_FAIL，其实是 BLOCKED：read 阻塞在 HAL →
        // 宿主侧 Future 超时 → 我们调 stop() 打断它 → **被打断的 read 返回 -3**
        // (ERROR_INVALID_OPERATION)。而 vc194 的 `when` 里 `readError < 0` 排在最前，
        // 于是「卡死」被记成「读失败」，结论方向完全带偏（听着像参数被 HAL 拒绝）。
        // 铁证：那六行 elapsedMs 恒为 5701ms、readCalls=0、blocked=true。
        val closeArtifact = blocked && readError < 0
        val verdict = when {
            startBlocked -> "START_BLOCKED"
            blocked && readCalls == 0 -> "BLOCKED"
            blocked && bytes == 0L -> "BLOCKED"
            readError < 0 -> "READ_FAIL"
            bytes == 0L -> "EMPTY"
            peak > 0 -> "OK"
            else -> "SILENT"
        }
        return "${tier.name} verdict=$verdict src=${tier.source} rate=${tier.rate} " +
            "ch=${tier.channels} minBuf=$minBuffer openMs=$openMs startMs=$startMs " +
            "readCalls=$readCalls bytes=$bytes firstByteMs=$firstByteMs peak=$peak " +
            "elapsedMs=$elapsed readError=$readError blocked=$blocked closeArtifact=$closeArtifact"
    }

    /**
     * 限时 stop+release。**绝不能在体检线程直接调 `record.stop()`** ——
     * api22/MTK 的 stop 拿不到 AudioFlinger 锁时会死等，vc193 就是这样卡死的。
     * 这里丢给守护线程，主线程只等 deadline，超时就放弃（线程泄漏好过整机体检卡死）。
     */
    private fun closeRecordWithDeadline(record: AudioRecord, deadlineMillis: Long = CLOSE_DEADLINE_MILLIS) {
        val closer = Thread({
            runCatching { record.stop() }
            runCatching { record.release() }
        }, "micprobe-close")
        closer.isDaemon = true
        closer.start()
        runCatching { closer.join(deadlineMillis) }
    }

    private fun verdictOf(matrixLine: String): String =
        Regex("verdict=([A-Z_]+)").find(matrixLine)?.groupValues?.get(1) ?: "?"

    private fun peakOf(buffer: ByteArray, length: Int): Int {
        var peak = 0
        var i = 0
        while (i + 1 < length) {
            val sample = ((buffer[i].toInt() and 0xFF) or ((buffer[i + 1].toInt() and 0xFF) shl 8))
                .toShort().toInt()
            val abs = if (sample < 0) -sample else sample
            if (abs > peak) peak = abs
            i += 2
        }
        return peak
    }

    // ─────────────────────────────────────────────────────────────────────
    // 层 C：root 三档
    // ─────────────────────────────────────────────────────────────────────

    /**
     * R1：root PCM 直采 —— 全套实验里**唯一能回答「物理麦有没有音」**的一档。
     *
     * vc195 重做。vc194 实测现象：`R1 probe stdout=BEGIN version=1 rate=48000 ch=1` 之后
     * 40 秒一个字都没有，被 suExec 超时杀掉，于是报「二进制未能运行」。
     * 真因不是没执行（BEGIN 出来了），而是**没刷出来**：v1 只有 BEGIN 那行 fflush，
     * 其余 printf 全留在 stdio 的 4KB 全缓冲里，进程被杀时整块丢失。
     * 同时 v1 的 open/START/read 都没有硬超时，一旦卡在 ioctl 上，
     * 循环头的 `now_ms() < deadline` 检查根本轮不到执行。
     *
     * v2 二进制（auth/tinycap/src/diplay_pcmcap.c）改了：stdout 无缓冲 + 每阶段 alarm()
     * 硬超时（SIGALRM 里直接 write 打 `TIMEOUT stage=xxx`）+ open 用 O_NONBLOCK 取 fd。
     *
     * 本函数分三步，**任何一步拿到答案就停**：
     *   R1a `-S` 设备扫描 —— 只 open+hw_params，快速答「哪些采集节点、什么参数能用」
     *   R1b 对可用节点真采 2s —— 答「能读出什么」（peak>0 = 物理麦有音）
     *   R1c 系统 `/system/bin/tinycap` 交叉对照 —— 换实现再问一遍，
     *       排除「自研二进制有问题」这一嫌疑（FE6 实测该工具存在）
     */
    private fun probeRootCapture(context: Context, dir: File, onUpdate: (String) -> Unit): String {
        progress = "R1 root 直采…"
        val assetName = "tinycap/diplay-pcmcap-${abi()}"
        val local = File(context.filesDir, "pcmcap/diplay-pcmcap")
        try {
            local.parentFile?.mkdirs()
            context.assets.open(assetName).use { input ->
                local.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (error: Throwable) {
            report("R1 SKIP asset missing ($assetName): ${error.javaClass.simpleName}")
            emit(onUpdate, "R1 跳过：包内未找到 $assetName")
            return "R1 verdict=SKIP note=\"asset absent\""
        }

        val remote = "/data/local/tmp/diplay-pcmcap"
        val copy = suExec("cp ${local.absolutePath} $remote", 6_000L)
        val chmod = suExec("chmod 755 $remote", 5_000L)
        report("R1 deploy copy=[$copy] chmod=[$chmod]")
        val verify = suExec("ls -l $remote", 5_000L)
        if (!verify.contains("diplay-pcmcap")) {
            emit(onUpdate, "R1 跳过：拷贝失败（$verify）")
            return "R1 verdict=SKIP note=\"copy failed\""
        }

        // ── R1a：设备扫描（只 open + hw_params，不 start 不 read）──
        // vc195：**分段扫**。FE6 有 23 个采集节点，一次扫完一旦前面几个节点卡在 ioctl，
        // 整轮被 suExec 超时杀掉就什么都不剩。按 8 个一段跑，某段超时不影响其它段。
        //
        // vc199：**参数不能写死 48000/1ch**。vc198 那次 17 个节点全是
        // `open=ok pversion=ok hw=fail errno=22` —— EINVAL 不是权限问题（open 全过了），
        // 是 hw_params 的参数与节点能力不匹配：FE6 的 aif3/aif4 实物是
        // `S16_LE / 2ch / 16000Hz`（`/proc/asound/card0/pcm1c/sub0` 实证），
        // 拿 48000/1ch 去配必然 EINVAL，于是误判成「直采不通」。
        //
        // 现在的顺序：**先只用实物参数 16000/2ch 扫一遍**（正常 3 段 ≈3 秒就完），
        // 一个都不通才补试 48000/1 → 24000/1 → 16000/1。既不白白拖长体检，
        // 也不会因为参数不对就断言「直采不通」。
        progress = "R1a 采集节点扫描…"
        emit(onUpdate, "R1a 扫描采集节点（先 16000Hz/2ch，分 3 段）…")
        val scanSpecs = listOf(
            intArrayOf(16_000, 2),   // FE6 实物参数（pcm1c / pcm16c 实证）
            intArrayOf(48_000, 1),   // 全历史唯一出过真音的参数（1.1.3，peak=2118）
            intArrayOf(24_000, 1),
            intArrayOf(16_000, 1),
        )
        report("R1A_BEGIN rate=${scanSpecs[0][0]} ch=${scanSpecs[0][1]} segment=8")
        val hwNodes = LinkedHashMap<Pair<Int, Int>, CaptureNode>()
        val allStalls = ArrayList<String>()
        var usedSpec = scanSpecs[0]
        for ((specIndex, spec) in scanSpecs.withIndex()) {
            val scanBuf = StringBuilder()
            for (start in listOf(0, 8, 16)) {
                suExec("pkill diplay-pcmcap", 3_000L)   // 清上一段可能残留的僵尸（su 被杀不等于子进程死）
                val out = suExec(
                    "$remote -S -D 0 -d $start -r ${spec[0]} -c ${spec[1]}",
                    R1_SCAN_SEGMENT_TIMEOUT_MILLIS,
                )
                report(
                    "R1A_SEG start=$start rate=${spec[0]} ch=${spec[1]} " +
                        "lines=${out.lineSequence().count()}",
                )
                out.lineSequence().filter { it.isNotBlank() }.forEach { report("R1A $it") }
                scanBuf.append(out).append('\n')
                out.lineSequence()
                    .filter { it.startsWith("TIMEOUT") }
                    .forEach { if (!allStalls.contains(it)) allStalls.add(it) }
            }
            val scan = scanBuf.toString()
            Regex("""PROBE card=(\d+) device=(\d+) open=ok pversion=ok hw=ok""")
                .findAll(scan)
                .forEach { match ->
                    val card = match.groupValues[1].toInt()
                    val device = match.groupValues[2].toInt()
                    hwNodes.getOrPut(card to device) {
                        CaptureNode(card, device, spec[0], spec[1])
                    }
                }
            if (hwNodes.isNotEmpty()) {
                usedSpec = spec
                break
            }
            if (specIndex < scanSpecs.lastIndex) {
                val next = scanSpecs[specIndex + 1]
                emit(onUpdate, "R1a ${spec[0]}Hz/${spec[1]}ch 无节点接受，换 ${next[0]}Hz/${next[1]}ch 重扫…")
                report("R1A_RETRY next rate=${next[0]} ch=${next[1]}")
            }
        }
        val hwOk = hwNodes.keys.toList()
        report(
            "R1A_SUMMARY hw_ok=${hwOk.size} stall=${allStalls.size} " +
                "nodes=${hwOk.joinToString(",") { "D${it.second}" }} " +
                "rate=${usedSpec[0]} ch=${usedSpec[1]} " +
                "stalls=${allStalls.joinToString(";") { it.removePrefix("TIMEOUT ").trim() }}",
        )
        emit(
            onUpdate,
            "R1a：${hwOk.size} 个节点参数可用（${usedSpec[0]}Hz/${usedSpec[1]}ch），" +
                "${allStalls.size} 次阶段阻塞",
        )
        if (hwOk.isEmpty()) {
            val note = if (allStalls.isNotEmpty()) "hw_params 阶段阻塞" else "无可打开节点"
            emit(onUpdate, "R1 无可用采集节点（$note）⇒ 直采这条路在此车机上不通")
            return "R1 verdict=NO_NODE hwOk=0 stall=${allStalls.size} note=\"$note\""
        }

        // ── R1b：按优先级真采 ──
        val ordered = orderCaptureNodes(hwOk)
        report("R1B_ORDER ${ordered.joinToString(",") { "c${it.first}d${it.second}" }}")
        var okLine = ""
        var silentLine = ""
        var blockedLine = ""
        val wavs = ArrayList<String>()
        for ((c, d) in ordered.take(R1_MAX_NODES)) {
            // vc199：必须用 R1a 命中的那组参数去采 —— 用别的参数会被 hw_params 拒掉（EINVAL）。
            val node = hwNodes[c to d] ?: CaptureNode(c, d, usedSpec[0], usedSpec[1])
            progress = "R1b 直采 card$c/device$d…"
            emit(
                onUpdate,
                "R1b 直采 card$c device$d（${R1_SECONDS}s，${node.rate}Hz/${node.channels}ch）…",
            )
            val wav = File(dir, "r1-c${c}d${d}.wav")
            wavs.add(wav.absolutePath)
            val out = suExec(
                "$remote -D $c -d $d -r ${node.rate} -c ${node.channels} " +
                    "-t $R1_SECONDS -o \"${wav.absolutePath}\"",
                R1_CAPTURE_TIMEOUT_MILLIS,
            )
            out.lineSequence().filter { it.isNotBlank() }.forEach { report("R1B $it") }
            val res = out.lineSequence().lastOrNull { it.startsWith("RESULT") } ?: ""
            val stall = out.lineSequence().firstOrNull { it.startsWith("TIMEOUT") } ?: ""
            val peak = Regex("peak=(\\d+)").find(res)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val frames = Regex("frames=(\\d+)").find(res)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val code = Regex("code=(\\d+)").find(res)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            val verdict = when {
                peak > 0 -> "OK"
                frames > 0 -> "SILENT"
                stall.isNotEmpty() -> "BLOCKED"
                res.contains("no frames") -> "BLOCKED"
                res.isNotEmpty() -> "OPEN_FAIL"
                else -> "NO_OUTPUT"
            }
            val line = "R1b card=$c device=$d rate=${node.rate} ch=${node.channels} " +
                "verdict=$verdict peak=$peak frames=$frames code=$code" +
                (if (stall.isNotEmpty()) " stall=\"${stall.removePrefix("TIMEOUT ").trim()}\"" else "") +
                (if (res.isNotEmpty()) " detail=\"${res.take(200)}\"" else " note=\"无 RESULT 行\"")
            report("MATRIX $line")
            emit(onUpdate, "R1b D$d → $verdict（peak=$peak frames=$frames）")
            when (verdict) {
                "OK" -> { okLine = line; break }
                "SILENT" -> if (silentLine.isEmpty()) silentLine = line
                "BLOCKED" -> if (blockedLine.isEmpty()) blockedLine = line
                else -> Unit
            }
        }
        // root 造的 WAV 归 app 所有，否则外测和我们自己都取不出来
        if (wavs.isNotEmpty()) chownToApp(wavs)

        // ── R1c：系统 tinycap 交叉对照（换实现再问一遍）──
        var sysLine = ""
        if (okLine.isEmpty()) {
            val (c, d) = ordered.first()
            // vc199：同样用 R1a 命中的参数，别再用写死的 48000/1ch。
            val node = hwNodes[c to d] ?: CaptureNode(c, d, usedSpec[0], usedSpec[1])
            val sysWav = File(dir, "r1-sys-c${c}d${d}.wav")
            progress = "R1c 系统 tinycap 交叉…"
            report(
                "R1C_BEGIN card=$c device=$d rate=${node.rate} ch=${node.channels} " +
                    "path=${sysWav.absolutePath}",
            )
            val script = """
                #!/system/bin/sh
                BIN=/system/bin/tinycap
                if [ ! -x ${'$'}BIN ]; then echo "TINYCAP_ABSENT"; exit 1; fi
                rm -f "${sysWav.absolutePath}"
                ${'$'}BIN "${sysWav.absolutePath}" -D $c -d $d -c ${node.channels} -r ${node.rate} &
                pid=${'$'}!
                sleep ${R1_SECONDS + 2}
                kill ${'$'}pid 2>/dev/null
                wait ${'$'}pid 2>/dev/null
                echo "TINYCAP_EXIT"
            """.trimIndent()
            val tcOut = runSuScript(context, "r1c", script, 40_000L)
            report("R1C out=${tcOut.replace('\n', '|').take(300)}")
            chownToApp(listOf(sysWav.absolutePath))
            val (peak, bytes) = peakOfWav(sysWav)
            val verdict = when {
                tcOut.contains("TINYCAP_ABSENT") -> "SKIP"
                peak > 0 -> "OK"
                bytes > 0 -> "SILENT"
                else -> "BLOCKED"
            }
            sysLine = "R1c verdict=$verdict peak=$peak bytes=$bytes card=$c device=$d"
            report("MATRIX $sysLine")
            emit(onUpdate, "R1c 系统 tinycap → $verdict（peak=$peak bytes=$bytes）")
        }

        val finalLine = when {
            okLine.isNotEmpty() -> "R1 verdict=OK $okLine"
            sysLine.contains("verdict=OK") -> "R1 verdict=OK via=\"system-tinycap\" $sysLine"
            silentLine.isNotEmpty() -> "R1 verdict=SILENT $silentLine"
            sysLine.contains("verdict=SILENT") -> "R1 verdict=SILENT via=\"system-tinycap\" $sysLine"
            blockedLine.isNotEmpty() -> "R1 verdict=BLOCKED $blockedLine"
            else -> "R1 verdict=UNKNOWN nodes=${hwOk.size} $sysLine"
        }
        report("MATRIX $finalLine")
        return finalLine
    }

    /**
     * 节点优先级：FE6 的麦克风主通路是 `ak7738-aif3` = pcmC0D**1**c，
     * 副通路 `I2S0AWB_Capture ak7738-aif4` = D**16**c。先试这两条，
     * 再按 card/device 升序兜底 —— 盲目从 D0 开始试会浪费掉大半体检时间在播放侧节点上。
     */
    /**
     * vc199：R1a 命中的「节点 + 可用参数」。
     *
     * 为什么必须带着参数走：ALSA 的 `hw_params` 会校验 rate/channels，和节点能力不符
     * 就返 `EINVAL(22)`。vc198 的 17 个节点全是 `open=ok` 但 `hw=fail errno=22`，
     * 就是因为扫描段写死了 `48000/1ch`，而 FE6 实物是 `16000/2ch`。
     */
    private data class CaptureNode(val card: Int, val device: Int, val rate: Int, val channels: Int)

    private fun orderCaptureNodes(nodes: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        val preferred = listOf(1, 16, 0, 2, 3, 4, 5)
        return nodes.sortedWith(
            compareBy(
                { (_, d) -> preferred.indexOf(d).let { if (it < 0) preferred.size else it } },
                { (c, _) -> c },
                { (_, d) -> d },
            ),
        )
    }

    /** root 创建的文件归 app 所有，否则 App 读不到自己 filesDir 里 root 写的 WAV。 */
    private fun chownToApp(paths: List<String>) {
        val uid = Process.myUid()
        val list = paths.joinToString(" ") { "\"$it\"" }
        suExec("chown $uid:$uid $list; chmod 640 $list", 8_000L)
    }

    /** 读 WAV 跳过 44 字节头算 peak 与数据字节数。失败返回 0 to 0。 */
    private fun peakOfWav(file: File): Pair<Int, Long> {
        if (!file.exists() || file.length() <= 44L) return 0 to 0L
        return try {
            file.inputStream().use { input ->
                var skipped = 0L
                while (skipped < 44L) {
                    val n = input.skip(44L - skipped)
                    if (n <= 0) break
                    skipped += n
                }
                val buf = ByteArray(8192)
                var peak = 0
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                    val p = peakOf(buf, n)
                    if (p > peak) peak = p
                }
                peak to total
            }
        } catch (_: Throwable) {
            0 to 0L
        }
    }

    /**
     * R2：停掉白名单占用者后重测。只碰语音类包。
     *
     * vc195 G2：重测档从 `tiers()[0]`（恒 L0）改成**矩阵里最能读的那一档**。
     * vc194 的 R2/R3 都用 L0 复测，而 L0 在 FE6 上是 blocked 档 ——
     * 「改动有没有用」这个问题永远得不到回答（改了也是 blocked，看起来像没改对）。
     */
    private fun attemptStopOccupant(pkg: String, retest: Tier, onUpdate: (String) -> Unit): String {
        progress = "R2 停占用者 $pkg…"
        emit(onUpdate, "R2 停止占用者：$pkg")
        val stop = suExec("am force-stop $pkg", 10_000L)
        report("R2 force-stop $pkg out=[$stop]")
        Thread.sleep(800)
        val after = probeOneTier(retest)
        report("MATRIX R2-after-stop(retest=${retest.name}) $after")
        emit(onUpdate, "R2 重测 ${retest.name} → ${verdictOf(after)}")
        return after
    }

    /** tinymix 单行的控件名与当前值。vc194 修正：vc193 用 `split(',',':','\t').first()`
     *  取到的是行首编号（"0"），不是控件名 —— 一调就错。
     *  vc195：前缀放宽到 `[B5R]`/`[B5T]`/`[B5T1]` —— R3 前会重抓一次新鲜 dump。 */
    private fun parseTinymixLine(line: String): Pair<String, String>? {
        val raw = line.replace(Regex("""\[B5[A-Z]?\d?\]"""), "").trim()
        if (raw.isEmpty()) return null
        val cols = raw.split('\t')
        if (cols.size >= 5) {
            val name = cols[3].trim()
            val value = cols[4].trim()
            return if (name.isEmpty()) null else name to value
        }
        // 少数 ROM 用空格对齐：拆成「编号 类型 数量 名称… 值」
        val m = Regex("""^\d+\s+\w+\s+\d+\s+(.+?)\s{2,}(\S+)$""").find(raw) ?: return null
        return m.groupValues[1].trim() to m.groupValues[2].trim()
    }

    /** 从一次 mixer dump 建「控件名 → 当前值」表（R3 回滚的基准）。 */
    private fun mixerValueMap(dump: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (line in dump.lineSequence()) {
            val (name, value) = parseTinymixLine(line) ?: continue
            map[name] = value
        }
        return map
    }

    /**
     * R3 的三类目标（vc194 扩充）。
     *
     * vc193 只认「名字含 mic + 含 mute + 值像开启」的控件，**恰好漏掉 FE6 的真凶**：
     * `MIC Input Volume L/R = 0`（名字里没有 mute）与 `Audio_ADC_1~4_Switch = Off`。
     * 而且对音量类控件写 0 只会更静音。
     */
    private enum class R3Kind { CLEAR_MUTE, RAISE_GAIN, ENABLE_ADC }

    private data class R3Target(val kind: R3Kind, val name: String)

    private fun pickR3Targets(b5: String): List<R3Target> {
        val targets = ArrayList<R3Target>()
        for (line in b5.lineSequence()) {
            val (name, value) = parseTinymixLine(line) ?: continue
            val lowName = name.lowercase()
            val lowValue = value.lowercase()
            when {
                // ① 模拟输入音量为 0：FE6 实测 MIC Input Volume L/R 都是 0
                lowName.contains("mic input volume") && value == "0" ->
                    targets.add(R3Target(R3Kind.RAISE_GAIN, name))
                // ② ADC 通道未使能：Audio_ADC_1~4_Switch = Off
                lowName.startsWith("audio_adc_") && lowName.endsWith("_switch") &&
                    (lowValue == "off" || value == "0") ->
                    targets.add(R3Target(R3Kind.ENABLE_ADC, name))
                // ③ 传统 mute 位（原文含义：置 0 = 解除静音）
                lowName.contains("mic") && lowName.contains("mute") &&
                    (value == "1" || lowValue == "on") ->
                    targets.add(R3Target(R3Kind.CLEAR_MUTE, name))
            }
        }
        return targets.distinctBy { it.name }.take(8)
    }

    /**
     * R3：按类型分派写操作，**每步都记改前值**，目标值绝不盲写。
     *
     * vc195 两处修正：
     *  · G2 复测用 `retest`（矩阵里最能读的档），不再恒用 L0。
     *  · G4 **写完必须回滚**。vc194 把 FE6 的 `MIC Input Volume L/R` 0→10、
     *    `Audio_ADC_1~4_Switch` Off→On 之后没有恢复，等于把车机的麦克风前置永久改了一遍
     *    （外测反馈「车机语音助手变响了」就是这个）。本版测完就还原，
     *    并把「改前→改后」全量写进报告，需要复现的人照着报告手工执行即可。
     */
    private fun attemptUnmute(
        targets: List<R3Target>,
        beforeValues: Map<String, String>,
        retest: Tier,
        onUpdate: (String) -> Unit,
    ): String {
        progress = "R3 解静音/提增益/开 ADC…"
        val mixer = "/system/bin/tinymix"
        // 回滚清单：(控件名, 恢复值索引字符串)
        val rollback = ArrayList<Pair<String, String>>()
        for (target in targets) {
            val detail = suExec("$mixer \"${target.name}\"", 5_000L)
            report("R3 before[${target.kind}] [${target.name}] => " +
                "${detail.replace('\n', '|').take(160)}")
            val desired = when (target.kind) {
                R3Kind.CLEAR_MUTE -> "0"
                R3Kind.ENABLE_ADC -> enumIndexFor(detail, "On") ?: "1"
                R3Kind.RAISE_GAIN -> gainMidpoint(detail)
            }
            // 记改前值：优先用 R3 前的 dump（干净），再用单查详情里的枚举项翻索引
            val original = beforeValues[target.name]
            val restore = when {
                original.isNullOrBlank() -> null
                original.matches(Regex("""-?\d+""")) -> original
                else -> enumIndexFor(detail, original) ?: original
            }
            if (restore != null) rollback.add(target.name to restore)
            val set = suExec("$mixer \"${target.name}\" $desired", 5_000L)
            report("R3 set[${target.kind}] [${target.name}] <= $desired (was=$original) => " +
                "${set.replace('\n', '|').take(160)}")
        }
        emit(onUpdate, "R3 已调整 ${targets.size} 个控件，正在复测 ${retest.name}…")
        Thread.sleep(500)
        val after = probeOneTier(retest)
        report("MATRIX R3-after(retest=${retest.name}) $after")
        emit(onUpdate, "R3 重测 ${retest.name} → ${verdictOf(after)}")

        // ── 回滚 ──
        if (rollback.isNotEmpty()) {
            for ((name, value) in rollback) {
                val out = suExec("$mixer \"$name\" $value", 5_000L)
                report("R3 ROLLBACK [$name] <= $value => ${out.replace('\n', '|').take(120)}")
            }
            val verify = collectMixer("B5Z")
            val back = mixerValueMap(verify)
            val mismatch = rollback.filter { (name, value) -> back[name] != null && back[name] != value }
            report(
                "R3 ROLLBACK_DONE restored=${rollback.size} mismatch=${mismatch.size} " +
                    "detail=${mismatch.joinToString(";") { "${it.first}:want=${it.second},got=${back[it.first]}" }}",
            )
            emit(onUpdate, "R3 已回滚 ${rollback.size} 个控件（不一致 ${mismatch.size} 个）")
        }
        return after
    }

    /**
     * 从 tinymix 的控件详情里取枚举项序号（如 "On" → "1"），取不到返回 null。
     *
     * vc195 重写：vc194 的做法是把整行拆成 token 再找 "On"，但 FE6 的单查输出形如
     *   `Audio_ADC_1_Switch	Off	(Off On)`
     * 整行拆开是 [Audio_ADC_1_Switch, Off, Off, On]，`indexOfFirst("On")` 得到 3 ——
     * 写进去就是第 4 个枚举项，直接写错。必须**只取最后一对括号里的内容**再拆。
     */
    private fun enumIndexFor(detail: String, label: String): String? {
        val paren = Regex("""\(([^()]*)\)""").findAll(detail).lastOrNull()?.groupValues?.get(1)?.trim()
        val named = Regex("""(?:Items?|values?)\s*:\s*(.+)""", RegexOption.IGNORE_CASE)
            .find(detail)?.groupValues?.get(1)?.trim()
        val source = named ?: paren ?: return null
        // 数值型控件会写成 (range 0->15)，不是枚举，别当枚举拆
        if (source.lowercase().contains("range")) return null
        val items = (if (source.contains(',') || source.contains('|')) {
            source.split(',', '|')
        } else {
            source.split(' ')
        }).map { it.trim() }.filter { it.isNotEmpty() }
        val index = items.indexOfFirst { it.equals(label, ignoreCase = true) }
        return if (index >= 0) index.toString() else null
    }

    /**
     * vc195 G2：从矩阵结果里挑「最能读的那一档」给 R2/R3 做复测基准。
     *
     * 排序：OK（有真音）> SILENT（能读但全零）> EMPTY > 其余（BLOCKED/READ_FAIL/…）。
     * 一个能读的都没有时退回 L6 —— vc194 实测 FE6 上只有 L6(DEFAULT/44.1k) 能读出数据，
     * 恒用 L0 会让「改动有没有用」永远无解。
     */
    private fun bestProbeTier(matrix: String): Tier {
        val all = tiers()
        val rank = mapOf("OK" to 0, "SILENT" to 1, "EMPTY" to 2)
        var best: Tier? = null
        var bestRank = Int.MAX_VALUE
        for (tier in all) {
            val line = matrix.lineSequence().firstOrNull { it.startsWith("${tier.name} verdict=") }
                ?: continue
            val v = verdictOf(line)
            val r = rank[v] ?: continue   // BLOCKED/READ_FAIL/OPEN_FAIL 不参与
            if (r < bestRank) {
                bestRank = r
                best = tier
            }
        }
        val chosen = best ?: all.firstOrNull { it.name == "L6" } ?: all.first()
        report(
            "BEST_TIER ${chosen.name} src=${chosen.source} rate=${chosen.rate} " +
                "rank=${if (best == null) "fallback" else bestRank}",
        )
        return chosen
    }

    /** 音量类的安全目标值：取 tinymix 报的 min/max 中值；拿不到 range 就用保守的 10。 */
    private fun gainMidpoint(detail: String): String {
        val nums = Regex("""-?\d+""").findAll(detail).mapNotNull { it.value.toIntOrNull() }.toList()
        if (nums.size >= 2) {
            val min = nums[0]
            val max = nums[1]
            if (max > min) return ((min + max) / 2).toString()
        }
        return "10"
    }

    // ─────────────────────────────────────────────────────────────────────
    // 证据解析
    // ─────────────────────────────────────────────────────────────────────

    /** 从 B4 的包列表里挑一个语音类占用者（白名单关键字，绝不泛指 other）。 */
    private fun pickOccupant(b4: String): String? {
        val candidates = b4.lineSequence()
            .filter { it.startsWith("[B4-pkg]") }
            .map { it.substringAfter("package:").trim() }
            .filter { pkg ->
                val l = pkg.lowercase()
                (l.contains("qinggan") || l.contains("speech") || l.contains("voice") ||
                    l.contains("assistant")) && !l.contains("diplay")
            }
            .toList()
        return candidates.firstOrNull()
    }

    private fun summarizeOccupancy(b2: String): String {
        val dirs = ArrayList<String>()
        var current = ""
        for (line in b2.lineSequence()) {
            if (line.contains("dir=")) {
                current = line.substringAfter("dir=").trim()
            } else if (line.contains("state:", ignoreCase = true) &&
                line.lowercase().contains("running")
            ) {
                dirs.add("${current.substringAfterLast("/proc/asound/")}(${line.trim()})")
            }
        }
        if (dirs.isEmpty()) return "捕获期无 RUNNING 的 capture 节点"
        return dirs.joinToString("；")
    }

    /**
     * 结论映射（对应方案 §4.3 的证据→结论表）。
     *
     * vc195 G7：**按证据强度分级措辞**。vc194 的体检把「L0/L1 blocked 期间 mixer 没变」
     * 写成了「【定案】ECARX HAL 根本没为第三方 input 配置 ADC」，这是超范围的断言 ——
     * 没有流的时候 mixer 不变是必然的。本版只在**采集期确实有数据流过**（bestVerdict 是
     * SILENT/OK，即读到字节）时才允许用「已证实」，否则一律标「待证实」并说明缺什么证据。
     *
     * 优先级：有真音 > 能出音的改动 > 占用 > 阻塞 > 静音 + mixer 证据。
     */
    private fun conclusionOf(
        matrix: String,
        r1: String,
        b2: String,
        b5: String,
        b5Diff: String,
        afterR2: String?,
        afterR3: String?,
        bestTierName: String,
        bestSnapshot: String,
    ): String {
        val l0 = Regex("L0 verdict=([A-Z_]+)").find(matrix)?.groupValues?.get(1) ?: "?"
        val okTier = Regex("(L\\d) verdict=OK").find(matrix)?.groupValues?.get(1)
        val r1Verdict = Regex("R1 verdict=([A-Z_]+)").find(r1)?.groupValues?.get(1) ?: "?"
        val r1Peak = Regex("R1[^\\n]*?peak=(\\d+)").find(r1)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val bestVerdict = verdictOf(bestSnapshot)
        val tierLines = matrix.lineSequence().filter { it.contains("verdict=") }.toList()
        val allBlocked = tierLines.isNotEmpty() && tierLines.all { it.contains("verdict=BLOCKED") }
        val silentCount = tierLines.count { it.contains("verdict=SILENT") }
        // 采集期快照只有落在「真的读到数据」的档上才有判据力
        val diffValid = bestVerdict == "SILENT" || bestVerdict == "OK"
        val adcUntouched = diffValid && b5Diff.contains("完全一致")
        val adcTouched = diffValid && b5Diff.contains("采集期变化")

        return when {
            okTier != null ->
                "【已证实·可修】$okTier 能采到非零音频（L0=$l0）⇒ 只是音源/采样率选错，" +
                    "把 $okTier 参数写进 sourcesFor() 首选即可。"
            afterR3 != null && verdictOf(afterR3) == "OK" ->
                "【已证实·可修】R3 调完 mixer 后 $bestTierName 出音 ⇒ 根因=模拟前端未开" +
                    "（MIC Input Volume=0 / ADC Switch=Off），上行启动前自动查/配 mixer 即可。" +
                    "（R3 已回滚，正式版需保留该配置）"
            afterR2 != null && verdictOf(afterR2) == "OK" ->
                "【已证实·需协同】停掉占用者后 $bestTierName 出音 ⇒ 根因=麦克风被车机语音服务独占，" +
                    "需在启动上行前抢占或提示用户关车机语音唤醒。"
            r1Verdict == "OK" ->
                "【已证实·可修(重)】root 直采 peak=$r1Peak>0 ⇒ 物理麦有音、AudioFlinger/AudioPolicy " +
                    "不给流，唯一确定解=root 直采接管上行。"
            r1Verdict == "SILENT" ->
                "【已证实·车机侧】root 直采绕开 AudioFlinger 后仍是全零 PCM（peak=0）⇒ " +
                    "整车侧无音频（DSP/隐私开关/硬件），App 侧无解。"
            adcUntouched ->
                "【已证实】$bestTierName 确实读到字节但全零，且采集期 mixer 与空闲态完全一致 ⇒ " +
                    "HAL 没为第三方 input 开模拟前端（不是静音位问题），只能 root 直采或代调 mixer 接管。"
            adcTouched ->
                "【已证实·分层】$bestTierName 读到全零，但采集期 HAL 确实改了 mixer（${b5Diff.take(120)}）" +
                    "⇒ 问题在 AudioFlinger 以上的路由/格式协商，优先换 source 与采样率。"
            allBlocked ->
                "【β 形态确认·待证实原因】所有 AudioRecord 档位均 BLOCKED ⇒ 读阻塞、非静音；" +
                    "占用：${summarizeOccupancy(b2)}。mixer 证据不足（$bestTierName=$bestVerdict 无数据流），" +
                    "需 R1 直采或真实 Siri 会话期间再抓。"
            r1Verdict == "NO_NODE" ->
                "【未定型】R1 直采一个节点都打不开（$r1）⇒ 换实现（系统 tinycap）或查 SELinux。" +
                    "矩阵 L0=$l0，$bestTierName=$bestVerdict。"
            r1Verdict == "SKIP" || r1Verdict == "UNKNOWN" ->
                "【未定型】R1 未跑出结果（$r1Verdict）⇒ 「物理麦有没有音」这个问题本次仍未回答。" +
                    "矩阵 L0=$l0，$bestTierName=$bestVerdict。"
            else ->
                "【待判】L0=$l0 $bestTierName=$bestVerdict R1=$r1Verdict；" +
                    "SILENT 档=${silentCount} 个；占用：${summarizeOccupancy(b2)}；" +
                    "R3 候选=${pickR3Targets(b5).size}"
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 报告落盘
    // ─────────────────────────────────────────────────────────────────────

    private var appContextRef: Context? = null

    private fun appCtx(): Context? = appContextRef

    private fun reportDir(context: Context, stamp: Long): File {
        appContextRef = context.applicationContext
        val name = "micprobe-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(stamp))
        // 外部私有目录：免运行时存储权限（api22 + targetSdk 34 上最稳）
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, name)
        runCatching { dir.mkdirs() }
        return dir
    }

    private fun report(line: String) {
        synchronized(reportLock) { if (reportLines.size < 4000) reportLines.add(line) }
        LiteLog.log(TAG, line)
    }

    private fun finish(context: Context, dir: File, onUpdate: (String) -> Unit) {
        stopScanWatchdog()
        val text = synchronized(reportLock) { reportLines.joinToString("\n") }
        val lines = text.lineSequence().count()
        val file = File(dir, "report.txt")
        runCatching { file.writeText(text + "\nEND lines=$lines aborted=$aborted\n") }
        summary = buildSummary(text)
        LiteLog.log(TAG, "END lines=$lines aborted=$aborted file=${file.absolutePath}")
        progress = ""
        running = false
        emit(onUpdate, "体检结束，报告：${file.absolutePath}")
    }

    /** 设置页展示用的紧凑摘要（取关键行，避免整屏滚动）。 */
    private fun buildSummary(text: String): String {
        val su = Regex("""\[B1\] uid=(\d+)""").find(text)?.groupValues?.get(1)
        val matrix = Regex("""MATRIX (L\d verdict=[A-Z_]+)""")
            .findAll(text).map { it.groupValues[1] }.toList()
        // vc195：R1 行改成了多段拼接（R1b 节点明细 + R1c 系统 tinycap），peak 落在段内，
        // 旧正则 `R1 verdict=X peak=N` 匹配不到，摘要会显示「未跑」。
        val r1Line = text.lineSequence().firstOrNull { it.contains("MATRIX R1 verdict=") } ?: ""
        val r1Verdict = Regex("""R1 verdict=([A-Z_]+)""").find(r1Line)?.groupValues?.get(1) ?: "未跑"
        val r1Peak = Regex("""peak=(\d+)""").find(r1Line)?.groupValues?.get(1) ?: "-"
        val nodes = Regex("""R1A_SUMMARY hw_ok=(\d+) stall=(\d+) nodes=(\S*)""").find(text)
        val best = Regex("""BEST_TIER (\S+) src=\d+ rate=(\d+)""").find(text)
        val running = Regex("""\[B2\] (card\d+ device\d+ status=RUNNING[^\n]*)""").findAll(text)
            .map { it.groupValues[1] }.toList()
        return buildString {
            append("root=").append(if (su == "0") "uid=0" else (su?.let { "uid=$it" } ?: "未取到"))
            append('\n')
            matrix.forEach { append(it).append('\n') }
            append("能读档=").append(best?.groupValues?.get(1) ?: "-")
                .append(" r=").append(best?.groupValues?.get(2) ?: "-").append('\n')
            append("R1 直采=").append(r1Verdict).append(" peak=").append(r1Peak)
            nodes?.let {
                append(" 节点(hw_ok=").append(it.groupValues[1])
                    .append("/stall=").append(it.groupValues[2])
                    .append(")=").append(it.groupValues[3].ifBlank { "-" })
            }
            append('\n')
            append("占用=").append(if (running.isEmpty()) "无（全 closed）" else running.joinToString(","))
        }
    }

    private fun emit(onUpdate: (String) -> Unit, message: String) {
        runCatching { onUpdate(message) }
        LiteLog.log(TAG, "[ui] $message")
    }

    private fun abi(): String {
        @Suppress("DEPRECATION")
        val primary = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Build.SUPPORTED_ABIS?.firstOrNull()
        } else {
            Build.CPU_ABI
        } ?: Build.CPU_ABI
        return when {
            primary.contains("arm64") -> "arm64-v8a"
            primary.contains("arm") -> "armeabi-v7a"
            else -> primary
        }
    }
}
