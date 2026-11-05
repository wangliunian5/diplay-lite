package com.shilapi.diplay.lite

import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext

/**
 * vc240：运行日志上传（含 dongle 链路）——四段式统一上传件。
 *
 * 文件结构（用户拍板 vc240-upload-merge）：
 * ① 头区：元信息 + 采集范围说明；
 * ② 骨架区：dongle/配对/锚点/总闸链路行，完整不裁剪（上限 160KB，含 -old.txt，old 在前）；
 * ③ 滚动区：最近 20 分钟（整段丢弃 `TRACE airplay`；`bulk read fail` 仅首尾取样+计数）；
 * ④ 尾区：最后 30 秒 + 收尾锚点 + 裁剪说明。
 *
 * 体积上限 768KB；裁剪优先级：TRACE → bulk 噪声 → 非关键杂项 → 周期 stats 只留最近 2 分钟
 * → 骨架（除超 160KB 外绝不裁）。每次裁剪都在文件里留一行明确的省略说明。
 *
 * 失败兜底：四段式组装异常/为空时回落到 [DongleLogUploader]（skeleton 模式，文件名与头部
 * 字段格式保持 `dongle-*.txt` 兼容，`ANCHOR` 前缀不变）。
 */
object RuntimeLogUploader {
    private const val MAX_BYTES = 768 * 1024
    private const val SKELETON_CAP_BYTES = 160 * 1024
    private const val TAIL_CAP_BYTES = 96 * 1024
    private const val ROLLING_WINDOW_MILLIS = 20 * 60_000L
    private const val TAIL_WINDOW_MILLIS = 30_000L
    private const val STATS_RECENT_MILLIS = 2 * 60_000L
    private const val TIMEOUT_MILLIS = 60_000L
    private const val RESERVE_BYTES = 2048
    private const val MAX_SKELETON_GENS_KEPT = 4 // 首个 gen + 最近 3 个 gen

    private const val TRACE_MARK = "TRACE airplay"
    private const val BULK_MARK = "bulk read fail"
    private const val ANCHOR_MARK = "ANCHOR "

    private const val SKELETON_SEP = "──── 骨架区（完整，不裁剪） ────"
    private const val ROLLING_SEP = "──── 滚动区（最近 20 分钟） ────"
    private const val TAIL_SEP = "──── 尾区（最后 30 秒 + 收尾锚点） ────"

    private val TIME_REGEX = Regex("^(\\d{2})-(\\d{2}) (\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3})")
    private val GEN_REGEX = Regex("gen=(\\d+)")
    private val TAG_REGEX = Regex("\\[([a-z]+)\\]")

    /** 骨架关键词：dongle 链路 / 配对 / 锚点 / 总闸。`bulk read fail` 在判定前先排除。 */
    private val SKELETON_KEYWORDS = arrayOf(
        "usbbt", "dongle", "host:", "standby", "inquiry", "pair", "hci", "外置蓝牙",
        "scan=", "gate-off", "gate-on", "link-ready", "connect-begin", "handoff",
        "session_active", "acl",
    )

    /** 非关键杂项 tag：滚动区超预算时优先省略（[ui]/[media]/[log]/配对与锚点不在此列）。 */
    private val MISC_TAGS = setOf("statusbar", "cluster", "app", "service", "usbfx", "pcap", "vpn")

    /** 本地已备好的上传件。 */
    class Prepared(val file: File, val fileName: String, val lineCount: Int, val sizeBytes: Long)

    /** 上传结果：ok=true 时 remoteName=仓库内真实文件名（409 冲突可能加 -1 后缀）。 */
    class Result(val ok: Boolean, val remoteName: String?, val error: String?)

    private class Entry(val line: String, val tod: Long?)

    private class GenGroup(val gen: Int?, val lines: ArrayList<String>)

    /** bulk read fail 取样：前 2 行 + 后 2 行 + 总计数。 */
    private class Noise {
        private val head = ArrayList<String>(2)
        private val tail = ArrayDeque<String>()
        var count = 0
            private set

        fun add(line: String) {
            count++
            if (head.size < 2) head.add(line)
            tail.addLast(line)
            while (tail.size > 2) tail.removeFirst()
        }

        fun samples(): List<String> = (head + tail).distinct()
    }

    /**
     * 组装四段式上传件；异常或为空时回落 [DongleLogUploader] 的 skeleton 模式。
     */
    fun prepare(context: Context): Prepared? {
        val full = try {
            buildBundle(context)
        } catch (error: Exception) {
            LiteLog.log(
                "log",
                "四段式日志组装失败 ${error.javaClass.simpleName} ${error.message.orEmpty()}，回落 skeleton 模式",
            )
            null
        }
        if (full != null) return full
        val skeleton = DongleLogUploader.prepare(context) ?: return null
        LiteLog.log("log", "运行日志上传：回落 skeleton 模式（${skeleton.fileName}）")
        return Prepared(skeleton.file, skeleton.fileName, skeleton.lineCount, skeleton.file.length())
    }

    // ---------- 组装 ----------

    private fun buildBundle(context: Context): Prepared? {
        val current = LiteLog.currentFile() ?: return null
        val directory = current.parentFile ?: return null
        if (!current.isFile || current.length() == 0L) return null
        val old = File(directory, current.nameWithoutExtension + "-old.txt")

        val nowTod = todOfNow()
        val rollingStart = nowTod - ROLLING_WINDOW_MILLIS
        val tailStart = nowTod - TAIL_WINDOW_MILLIS

        val skeletonEntries = ArrayList<Entry>()
        val windowEntries = ArrayList<Entry>()
        val noise = Noise()

        // old 在前（时间更早），current 在后：骨架搜索天然跨轮转段。
        if (old.isFile) scanSkeletonOnly(old, skeletonEntries)
        val traceDropped = scanCurrent(current, nowTod, rollingStart, skeletonEntries, windowEntries, noise)

        val skeletonFit = fitSkeleton(skeletonEntries, SKELETON_CAP_BYTES)

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val headerText = buildHeader(context, current, old.isFile, stamp, rollingStart, nowTod)
        val headerBytes = headerText.toByteArray(Charsets.UTF_8).size
        val skeletonBytes = sectionBytes(skeletonFit.first)

        val tailCap = (MAX_BYTES - headerBytes - skeletonBytes - RESERVE_BYTES - 1024)
            .coerceIn(512, TAIL_CAP_BYTES)
        val tailEntries = windowEntries.filter { it.tod != null && it.tod >= tailStart }
        val anchorEntries = skeletonEntries
            .filter { it.line.contains(ANCHOR_MARK, ignoreCase = true) }
            .takeLast(8)
        val tailFit = fitTail(tailEntries, anchorEntries, tailCap, tailStart)
        val tailBytes = sectionBytes(tailFit.first)

        val rollingBudget = (MAX_BYTES - headerBytes - skeletonBytes - tailBytes - RESERVE_BYTES)
            .coerceAtLeast(1024)
        val rollingEntries = windowEntries.filter { it.tod != null && it.tod < tailStart }
        val rollingFit = fitRolling(rollingEntries, rollingBudget, nowTod, traceDropped, noise)

        val text = buildString {
            append(headerText)
            append(SKELETON_SEP).append('\n')
            append(skeletonFit.first).append('\n')
            append(ROLLING_SEP).append('\n')
            append(rollingFit.first).append('\n')
            append(TAIL_SEP).append('\n')
            append(tailFit.first)
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) {
            LiteLog.log("log", "运行日志上传件超出 768KB（${bytes.size} 字节），已拒绝落盘")
            return null
        }
        val totalLines = text.count { it == '\n' }
        val fileName = buildFileName(context, stamp)
        val target = File(directory, fileName)
        target.outputStream().use { it.write(bytes) }
        LiteLog.log(
            "log",
            "运行日志上传件已生成：$fileName（${bytes.size} 字节，" +
                "骨架 ${skeletonFit.second} 行 / 滚动 ${rollingFit.second} 行 / 尾区 ${tailFit.second} 行）",
        )
        return Prepared(target, fileName, totalLines, bytes.size.toLong())
    }

    /** old.txt 只取骨架行（滚动区只取 current）。 */
    private fun scanSkeletonOnly(file: File, out: MutableList<Entry>) {
        try {
            file.bufferedReader().useLines { seq ->
                seq.forEach { line ->
                    if (line.isEmpty()) return@forEach
                    val lower = line.lowercase(Locale.US)
                    if (lower.contains(BULK_MARK)) return@forEach
                    if (isSkeletonLine(lower)) out.add(Entry(line, null))
                }
            }
        } catch (_: Exception) {
        }
    }

    /** current：骨架 + 最近 20 分钟窗口；TRACE 整段丢弃，bulk 噪声只取样计数。返回丢弃的 TRACE 行数。 */
    private fun scanCurrent(
        file: File,
        nowTod: Long,
        rollingStart: Long,
        skeleton: MutableList<Entry>,
        window: MutableList<Entry>,
        noise: Noise,
    ): Int {
        var traceDropped = 0
        try {
            file.bufferedReader().useLines { seq ->
                seq.forEach { line ->
                    if (line.isEmpty()) return@forEach
                    val tod = parseTod(line, nowTod)
                    val inWindow = tod != null && tod >= rollingStart && tod <= nowTod
                    val lower = line.lowercase(Locale.US)
                    if (inWindow && lower.contains(TRACE_MARK.lowercase(Locale.US))) {
                        traceDropped++
                        return@forEach
                    }
                    if (inWindow && lower.contains(BULK_MARK)) {
                        noise.add(line)
                        return@forEach
                    }
                    if (isSkeletonLine(lower)) {
                        skeleton.add(Entry(line, tod))
                        return@forEach
                    }
                    if (inWindow) window.add(Entry(line, tod))
                }
            }
        } catch (_: Exception) {
        }
        return traceDropped
    }

    private fun isSkeletonLine(lower: String): Boolean {
        if (lower.contains(BULK_MARK)) return false
        if (lower.contains(ANCHOR_MARK.lowercase(Locale.US))) return true
        for (keyword in SKELETON_KEYWORDS) {
            if (lower.contains(keyword)) return true
        }
        return false
    }

    /** 骨架区：完整保留；超 160KB 时保首个 gen + 最近 3 个 gen，再超则首尾双保留。 */
    private fun fitSkeleton(entries: List<Entry>, cap: Int): Pair<String, Int> {
        if (entries.isEmpty()) return "（无 dongle/链路骨架行）\n" to 0
        val groups = ArrayList<GenGroup>()
        var current: GenGroup? = null
        for (entry in entries) {
            val gen = GEN_REGEX.find(entry.line)?.groupValues?.get(1)?.toIntOrNull()
            val group = current
            if (group == null || (gen != null && group.gen != gen)) {
                val next = GenGroup(gen, ArrayList())
                current = next
                groups.add(next)
                next.lines.add(entry.line)
            } else {
                group.lines.add(entry.line)
            }
        }
        var selected: List<GenGroup> = groups
        if (bytesOfGroups(groups) > cap) {
            val genGroups = groups.filter { it.gen != null }
            if (genGroups.size > MAX_SKELETON_GENS_KEPT) {
                val keep = HashSet<GenGroup>()
                keep.add(genGroups.first())
                keep.addAll(genGroups.takeLast(3))
                selected = groups.filter { it.gen == null || keep.contains(it) }
            }
        }
        var lines = selected.flatMap { it.lines }
        var omitted = entries.size - lines.size
        if (bytesOfLines(lines) > cap) {
            val budget = (cap - 512).coerceAtLeast(256)
            val (head, tail, dropped) = headTail(lines, budget)
            val marker = "… 骨架区超 160KB：省略 $dropped 行" +
                "（保首个 gen 与最近 3 个 gen 的首尾锚点，中间已裁）…"
            lines = head + listOf(marker) + tail
            omitted += dropped
        }
        val text = buildString {
            if (omitted > 0) {
                append("（骨架区：完整骨架超 160KB，按「首个 gen + 最近 3 个 gen」保留，省略 ")
                    .append(omitted).append(" 行）\n")
            }
            lines.forEach { append(it).append('\n') }
        }
        return text to lines.size
    }

    /** 骨架超限兜底：按字节保留首尾各一半（中间插省略标记）。 */
    private fun headTail(
        lines: List<String>,
        budget: Int,
    ): Triple<List<String>, List<String>, Int> {
        val half = (budget / 2).coerceAtLeast(128)
        var headBytes = 0
        var headEnd = 0
        while (headEnd < lines.size) {
            val next = lineBytes(lines[headEnd])
            if (headBytes + next > half) break
            headBytes += next
            headEnd++
        }
        var tailBytes = 0
        var tailStart = lines.size
        while (tailStart > headEnd) {
            val next = lineBytes(lines[tailStart - 1])
            if (tailBytes + next > half) break
            tailBytes += next
            tailStart--
        }
        return Triple(lines.subList(0, headEnd), lines.subList(tailStart, lines.size), tailStart - headEnd)
    }

    /** 滚动区：已过滤 TRACE/bulk；超预算按 非关键杂项 → 周期 stats(2min) → 硬裁 依次省略。 */
    private fun fitRolling(
        entries: List<Entry>,
        budget: Int,
        nowTod: Long,
        traceDropped: Int,
        noise: Noise,
    ): Pair<String, Int> {
        val notes = ArrayList<String>()
        if (traceDropped > 0) {
            notes.add("（滚动区：已整段丢弃 TRACE airplay 共 $traceDropped 行）\n")
        }
        if (noise.count > 0) {
            notes.add("（空闲噪声 bulk read fail：共 ${noise.count} 行，未逐行进正文；首尾取样如下）\n")
            for (sample in noise.samples()) notes.add("  $sample\n")
        }
        var current = entries
        if (bytesOfEntries(current) + bytesOfNotes(notes) > budget) {
            var removed = 0
            current = current.filter {
                val misc = isMiscTag(it.line)
                if (misc) removed++
                !misc
            }
            if (removed > 0) notes.add("（滚动区：非关键杂项 $removed 行已省略）\n")
        }
        if (bytesOfEntries(current) + bytesOfNotes(notes) > budget) {
            val cutoff = nowTod - STATS_RECENT_MILLIS
            var removed = 0
            current = current.filter {
                val drop = isPeriodicStats(it.line) && (it.tod ?: nowTod) < cutoff
                if (drop) removed++
                !drop
            }
            if (removed > 0) notes.add("（滚动区：周期 stats 仅保留最近 2 分钟，省略 $removed 行）\n")
        }
        var lines = current.map { it.line }
        val lineBudget = (budget - bytesOfNotes(notes) - 256).coerceAtLeast(256)
        if (bytesOfLines(lines) > lineBudget) {
            var start = lines.size
            var used = 0
            while (start > 0) {
                val next = lineBytes(lines[start - 1])
                if (used + next > lineBudget) break
                used += next
                start--
            }
            if (start > 0) {
                notes.add("（骨架完整、滚动自 ${lineTime(lines[start])} 起被裁，省略 $start 行）\n")
            }
            lines = lines.subList(start, lines.size)
        }
        if (lines.isEmpty()) {
            notes.add("（滚动区无日志行）\n")
        }
        val text = buildString {
            notes.forEach { append(it) }
            lines.forEach { append(it).append('\n') }
        }
        return text to lines.size
    }

    /** 尾区：最后 30 秒 + 收尾锚点；超 96KB 从最早处裁并留说明。 */
    private fun fitTail(
        entries: List<Entry>,
        anchors: List<Entry>,
        cap: Int,
        tailStart: Long,
    ): Pair<String, Int> {
        var lines = entries.map { it.line }
        val anchorLines = anchors.map { it.line }
        var note = ""
        val anchorBytes = bytesOfLines(anchorLines) + (if (anchorLines.isEmpty()) 0 else 64)
        if (bytesOfLines(lines) + anchorBytes > cap) {
            val lineBudget = (cap - anchorBytes - 256).coerceAtLeast(256)
            var start = lines.size
            var used = 0
            while (start > 0) {
                val next = lineBytes(lines[start - 1])
                if (used + next > lineBudget) break
                used += next
                start--
            }
            if (start > 0) {
                note = "（尾区：最后 30 秒内省略 $start 行，自 ${lineTime(lines[start])} 起被裁）\n"
            }
            lines = lines.subList(start, lines.size)
        }
        val text = buildString {
            append(note)
            lines.forEach { append(it).append('\n') }
            if (anchorLines.isNotEmpty()) {
                append("（收尾锚点：最近 ${anchorLines.size} 条，自 ${todText(tailStart)} 前）\n")
                anchorLines.forEach { append(it).append('\n') }
            }
            if (lines.isEmpty() && anchorLines.isEmpty()) {
                append("（最后 30 秒无日志行）\n")
            }
        }
        return text to (lines.size + anchorLines.size)
    }

    // ---------- 头区 / 文件名 ----------

    private fun buildHeader(
        context: Context,
        current: File,
        hasOld: Boolean,
        stamp: String,
        rollingStart: Long,
        nowTod: Long,
    ): String = buildString {
        append("运行日志（含 dongle 链路）\n\n")
        append("设备：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        val metrics = Resources.getSystem().displayMetrics
        append("分辨率：").append(metrics.widthPixels).append('x').append(metrics.heightPixels)
            .append(" density=").append(metrics.density).append('\n')
        append("系统版本：Android").append(Build.VERSION.RELEASE)
            .append("-api").append(Build.VERSION.SDK_INT).append('\n')
        val romDisplay = Build.DISPLAY?.trim().orEmpty().ifEmpty { Build.ID }
        append("ROM版本：").append(romDisplay)
            .append("（built ").append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(Build.TIME)))
            .append("）\n")
        append("软件版本：").append(versionName(context)).append('\n')
        append("上传时间：").append(stamp).append('\n')
        append("日志文件：").append(current.name)
            .append(if (hasOld) "（含 -old.txt 轮转段，old 在前）" else "（无轮转段）")
            .append('\n')
        append("──── 采集范围 ────\n")
        append("骨架区：dongle 检测/初始化/配对/锚点/总闸链路，完整不裁剪（上限 160KB，含 -old.txt）\n")
        append("滚动区：最近 20 分钟（").append(todText(rollingStart)).append(" ~ ")
            .append(todText(nowTod - TAIL_WINDOW_MILLIS)).append("），整段丢弃 TRACE airplay\n")
        append("尾区：最后 30 秒（").append(todText(nowTod - TAIL_WINDOW_MILLIS)).append(" ~ ")
            .append(todText(nowTod)).append("）+ 收尾锚点\n")
        append("总上限：768KB；bulk read fail 仅首尾取样+计数\n")
    }

    private fun buildFileName(context: Context, stamp: String): String {
        val systemVersion = "Android${Build.VERSION.RELEASE}-api${Build.VERSION.SDK_INT}"
        val safeModel = Build.MODEL.trim().replace(Regex("[^0-9A-Za-z._-]"), "-")
        return "diplay-$stamp-$systemVersion-v${versionName(context)}-$safeModel.txt"
            .replace(Regex("[\\s]+"), "")
    }

    private fun versionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (_: Exception) {
        "unknown"
    }

    // ---------- 工具 ----------

    private fun isMiscTag(line: String): Boolean {
        val tag = TAG_REGEX.find(line)?.groupValues?.get(1)?.lowercase(Locale.US) ?: return false
        return MISC_TAGS.contains(tag)
    }

    private fun isPeriodicStats(line: String): Boolean {
        val lower = line.lowercase(Locale.US)
        return lower.contains("video stats") || lower.contains("touch stats") ||
            lower.contains("microphone gain window")
    }

    /** 解析 `MM-dd HH:mm:ss.SSS` → 当日毫秒（跨零点按 ±12h 归一）。 */
    private fun parseTod(line: String, nowTod: Long): Long? {
        val m = TIME_REGEX.find(line) ?: return null
        val hour = m.groupValues[3].toIntOrNull() ?: return null
        val minute = m.groupValues[4].toIntOrNull() ?: return null
        val second = m.groupValues[5].toIntOrNull() ?: return null
        val millis = m.groupValues[6].toIntOrNull() ?: return null
        val tod = hour * 3_600_000L + minute * 60_000L + second * 1_000L + millis
        return if (tod > nowTod + 12 * 3_600_000L) tod - 24 * 3_600_000L else tod
    }

    private fun todOfNow(): Long {
        val calendar = Calendar.getInstance()
        return calendar.get(Calendar.HOUR_OF_DAY) * 3_600_000L +
            calendar.get(Calendar.MINUTE) * 60_000L +
            calendar.get(Calendar.SECOND) * 1_000L +
            calendar.get(Calendar.MILLISECOND)
    }

    private fun todText(tod: Long): String {
        val day = 24 * 3_600_000L
        val t = ((tod % day) + day) % day
        return String.format(
            Locale.US,
            "%02d:%02d:%02d",
            t / 3_600_000L,
            (t / 60_000L) % 60,
            (t / 1_000L) % 60,
        )
    }

    private fun lineTime(line: String): String {
        val m = TIME_REGEX.find(line) ?: return "-"
        return "${m.groupValues[3]}:${m.groupValues[4]}:${m.groupValues[5]}"
    }

    private fun lineBytes(line: String): Int = line.toByteArray(Charsets.UTF_8).size + 1

    private fun bytesOfLines(lines: List<String>): Int {
        var total = 0
        for (line in lines) total += lineBytes(line)
        return total
    }

    private fun bytesOfGroups(groups: List<GenGroup>): Int {
        var total = 0
        for (group in groups) total += bytesOfLines(group.lines)
        return total
    }

    private fun bytesOfEntries(entries: List<Entry>): Int {
        var total = 0
        for (entry in entries) total += lineBytes(entry.line)
        return total
    }

    private fun bytesOfNotes(notes: List<String>): Int {
        var total = 0
        for (note in notes) total += note.toByteArray(Charsets.UTF_8).size
        return total
    }

    private fun sectionBytes(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    // ---------- 上传（60s 总超时，409 同名加 -1 重试一次） ----------

    fun upload(prepared: Prepared): Result {
        val token = BuildConfig.GITEE_LOG_TOKEN
        if (token.isEmpty()) return Result(false, null, "GITEE_LOG_TOKEN 未配置")
        val encoded = try {
            Base64.encodeToString(prepared.file.readBytes(), Base64.NO_WRAP)
        } catch (error: Exception) {
            return Result(false, null, "读取本地文件失败：${error.message.orEmpty()}")
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        var attemptName = prepared.fileName
        var lastError = "未知错误"
        for (attempt in 0..1) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return Result(false, null, timeoutText())
            val timedOut = AtomicBoolean(false)
            val outcome = try {
                postGiteeContents(token, attemptName, stamp, encoded, remaining, timedOut)
            } catch (error: Exception) {
                if (timedOut.get()) return Result(false, null, timeoutText())
                LiteLog.log("log", "运行日志上传异常：${error.javaClass.simpleName} ${error.message.orEmpty()}")
                return Result(false, null, "网络不通：${error.message.orEmpty()}")
            }
            if (timedOut.get()) {
                LiteLog.log("log", "运行日志上传超时（60s）")
                return Result(false, null, timeoutText())
            }
            val (code, body) = outcome
            if (code in 200..299) {
                val path = runCatching {
                    JSONObject(body).optJSONObject("content")?.optString("path").orEmpty()
                }.getOrDefault("")
                val remote = path.ifEmpty { attemptName }
                LiteLog.log("log", "运行日志上传成功：$remote")
                return Result(true, remote, null)
            }
            val sanitized = body.replace(token, "***")
                .let { if (it.length > 300) it.substring(0, 300) + "…" else it }
            lastError = "HTTP $code $sanitized"
            LiteLog.log("log", "运行日志上传失败：$lastError")
            if ((code == 409 || body.contains("already exists", ignoreCase = true)) && attempt == 0) {
                attemptName = prepared.fileName.removeSuffix(".txt") + "-1.txt"
                continue
            }
            break
        }
        return Result(false, null, lastError)
    }

    private fun timeoutText(): String = "上传超时（${TIMEOUT_MILLIS / 1000}s）"

    private fun postGiteeContents(
        token: String,
        name: String,
        stamp: String,
        encoded: String,
        timeoutMs: Long,
        timedOut: AtomicBoolean,
    ): Pair<Int, String> {
        val url = URL(
            "https://gitee.com/api/v5/repos/wangliunian/display--log-collection/contents/" +
                URLEncoder.encode(name, "UTF-8") +
                "?access_token=" + URLEncoder.encode(token, "UTF-8"),
        )
        val body = JSONObject()
            .put("content", encoded)
            .put("branch", "master")
            .put("message", "DiPlay Lite 运行日志（含 dongle 链路）$stamp")
            .toString()
        val connection = url.openConnection() as HttpURLConnection
        val perCallTimeout = if (timeoutMs > Int.MAX_VALUE) Int.MAX_VALUE else timeoutMs.toInt()
        val watchdog = Timer(true)
        try {
            watchdog.schedule(object : TimerTask() {
                override fun run() {
                    timedOut.set(true)
                    runCatching { connection.disconnect() }
                }
            }, timeoutMs)
            connection.requestMethod = "POST"
            connection.connectTimeout = perCallTimeout
            connection.readTimeout = perCallTimeout
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json;charset=UTF-8")
            enableTls12ForPreLollipop(connection)
            connection.outputStream.use { out -> out.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to text
        } finally {
            watchdog.cancel()
            connection.disconnect()
        }
    }

    /** Android 4.x（API16-20）默认不启用 TLSv1.2，Gitee 需要：显式换 SSLContext 兜底。 */
    private fun enableTls12ForPreLollipop(connection: HttpURLConnection) {
        if (Build.VERSION.SDK_INT > 20 || connection !is HttpsURLConnection) return
        try {
            val context = SSLContext.getInstance("TLSv1.2")
            context.init(null, null, null)
            connection.sslSocketFactory = context.socketFactory
        } catch (_: Exception) {
        }
    }
}
