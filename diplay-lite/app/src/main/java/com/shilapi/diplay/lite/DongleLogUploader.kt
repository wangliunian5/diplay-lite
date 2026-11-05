package com.shilapi.diplay.lite

import android.content.Context
import android.os.Build
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext

/**
 * vc207：配对窗「dongle 连接日志」导出/上传通道。
 *
 * 与设置页 Gitee 上传同源（owner/repo/token/30s 总时长超时/TLS1.2 兜底，API16-20 兼容），
 * 差异只在内容：**只保留 dongle 相关行**（usbbt/host:/standby/inquiry/pair/hci/外置蓝牙），
 * 且把 LiteLog 8MB 轮转产生的 -old.txt 一并带上（日志炸弹触发轮转后 currentFile 只剩
 * 最新段，不带 old 会丢配对证据——vc206 实测踩过）。
 *
 * 命名（截图字段为准）：
 * `dongle-<yyyyMMdd>-<HHmmss>-Android<ver>-api<SDK>-v<version>_<vc>-<model>.txt`
 * 例：dongle-20261101-115212-Android14-api34-v1.3.0_vc207-23013RK75C.txt
 */
object DongleLogUploader {
    private const val MAX_BYTES = 512 * 1024
    private const val TIMEOUT_MILLIS = 30_000

    /** 头尾双保留：头部锚点区间外再留 64KB 余量；头部最多占一半，另一半保底给尾部。 */
    private const val HEAD_EXTRA_BYTES = 64 * 1024
    private const val HEAD_CAP_BYTES = MAX_BYTES / 2

    /** 截断时给省略标记预留的字节余量（标记 ≈120 字节，留 1KB 足够）。 */
    private const val TRUNCATE_RESERVE_BYTES = 1024

    /** 锚点前缀：所有锚点日志统一以 ANCHOR 开头，便于从轮转后的 -old.txt 里 grep。 */
    private const val ANCHOR_MARK = "ANCHOR "

    /** 本地已备好的上传件（导出也复用它：直接把这个文件交给用户）。 */
    class Prepared(val file: File, val fileName: String, val lineCount: Int)

    /** 上传结果：ok=true 时 remoteName=仓库内真实文件名（409 冲突可能加 -1 后缀）。 */
    class Result(val ok: Boolean, val remoteName: String?, val error: String?)

    private val KEYWORDS = arrayOf(
        "usbbt", "dongle", "host:", "standby", "inquiry", "pair", "hci", "外置蓝牙", "anchor",
    )

    /**
     * 汇总 dongle 相关日志行 → 写本地 dongle-*.txt（与 LiteLog 同目录）。
     * 无日志/无可写目录时返回 null。
     */
    fun prepare(context: Context): Prepared? {
        val current = LiteLog.currentFile() ?: return null
        val directory = current.parentFile ?: return null
        if (!current.isFile || current.length() == 0L) return null
        val old = File(directory, current.nameWithoutExtension + "-old.txt")

        val lines = ArrayList<String>()
        // old 在前（时间更早），current 在后
        if (old.isFile) collectLines(old, lines)
        collectLines(current, lines)
        if (lines.isEmpty()) return null

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = buildFileName(context, stamp)
        val header = buildString {
            append("dongle 连接日志（外置 USB 蓝牙）\n\n")
            append("设备：").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n")
            append("系统版本：Android").append(Build.VERSION.RELEASE)
                .append("（api ").append(Build.VERSION.SDK_INT).append("）\n")
            append("软件版本：").append(versionName(context)).append("\n")
            append("导出时间：").append(stamp).append("\n")
            append("日志行数：").append(lines.size).append("（仅 dongle 相关；含 -old.txt 轮转段）\n")
            append("──── dongle 日志 ────\n")
        }
        val body = lines.joinToString(separator = "\n", postfix = "\n").toByteArray(Charsets.UTF_8)
        val headerBytes = header.toByteArray(Charsets.UTF_8)
        val payload = if (headerBytes.size + body.size <= MAX_BYTES) {
            headerBytes + body
        } else {
            // 超上限：头尾双保留（头部按事件锚点，尾部保最新证据），中间插省略标记
            truncateHeadTail(headerBytes, lines)
        }
        val target = File(directory, fileName)
        return try {
            target.outputStream().use { it.write(payload) }
            Prepared(target, fileName, lines.size)
        } catch (error: Exception) {
            LiteLog.log("log", "dongle 日志本地写入失败 ${error.message.orEmpty()}")
            null
        }
    }

    /**
     * 头尾双保留截断。
     *
     * 头部：从最早一条锚点日志（启动/检测 dongle/打开/初始化/配对开始）开始，
     * 一直留到首次「链路就绪」或「配对完成」锚点，再额外留 [HEAD_EXTRA_BYTES]；
     * 头部总量不超过 [HEAD_CAP_BYTES]，确保尾部始终有额度。
     * 尾部：用剩余额度从最新一行向前回填。
     * 中间：一行 `… 已省略 N 行（time A ~ time B）…`。
     *
     * 因为 [prepare] 已把 `-old.txt` 拼在 current 之前，锚点搜索天然跨轮转段：
     * 长时间采集时连接段落在 old 里也能被头部保住。
     */
    private fun truncateHeadTail(headerBytes: ByteArray, lines: List<String>): ByteArray {
        val budget = MAX_BYTES - headerBytes.size - TRUNCATE_RESERVE_BYTES
        // 头部起点：最早锚点；旧日志没有锚点时退回文件起点
        var headStart = lines.indexOfFirst { it.contains(ANCHOR_MARK, ignoreCase = true) }
        if (headStart < 0) {
            headStart = 0
        } else {
            // 锚点前再回退 64KB：防止轮转后起始段只剩 link-ready，连接前导行被切掉
            var preRoll = 0
            while (headStart > 0 && preRoll < HEAD_EXTRA_BYTES) {
                headStart--
                preRoll += lineBytes(lines[headStart])
            }
        }
        // 头部终点：首次连上/配对完成；找不到则只保起点那一行，其余按 64KB 余量延伸
        val endOffset = lines.subList(headStart, lines.size).indexOfFirst {
            it.contains("ANCHOR link-ready", ignoreCase = true) ||
                it.contains("ANCHOR pair-done", ignoreCase = true)
        }
        var headEnd = if (endOffset >= 0) headStart + endOffset + 1 else headStart + 1
        var headBytes = 0
        for (index in headStart until headEnd) headBytes += lineBytes(lines[index])
        // 锚点区间之后再留 64KB，且头部不超过总上限的一半
        var extra = 0
        while (headEnd < lines.size && extra < HEAD_EXTRA_BYTES) {
            val next = lineBytes(lines[headEnd])
            if (headBytes + next > HEAD_CAP_BYTES) break
            headBytes += next
            extra += next
            headEnd++
        }
        // 锚点区间本身过大时从尾部裁，避免挤掉全部尾部额度
        while (headEnd > headStart + 1 && headBytes > HEAD_CAP_BYTES) {
            headEnd--
            headBytes -= lineBytes(lines[headEnd])
        }
        // 尾部：从最新一行向前回填剩余额度
        val tailBudget = budget - headBytes
        var tailStart = lines.size
        var tailBytes = 0
        while (tailStart > headEnd) {
            val next = lineBytes(lines[tailStart - 1])
            if (tailBytes + next > tailBudget) break
            tailBytes += next
            tailStart--
        }
        if (tailStart < headEnd) tailStart = headEnd
        val omitted = tailStart - headEnd
        val truncated = omitted > 0 && headEnd < lines.size
        val marker = if (truncated) {
            "… 已省略 $omitted 行（${lineTime(lines[headEnd])} ~ ${lineTime(lines[tailStart - 1])}；超 512KB，头尾双保留）…\n"
        } else {
            ""
        }
        // vc239（LOG-262-01）：真正触发头尾双保留时才落这一行，让日志侧能证实标记确实插入。
        if (truncated) {
            LiteLog.log(
                "log",
                "已截断：省略 $omitted 行（${lineTime(lines[headEnd])} ~ " +
                    "${lineTime(lines[tailStart - 1])}；头尾双保留）",
            )
        }
        val headText = lines.subList(headStart, headEnd).joinToString("\n")
        val tailText = if (tailStart < lines.size) {
            lines.subList(tailStart, lines.size).joinToString("\n", postfix = "\n")
        } else {
            ""
        }
        return headerBytes + (headText + "\n" + marker + tailText).toByteArray(Charsets.UTF_8)
    }

    /** 单行字节数（UTF-8 实际字节 + 换行），用于按真实体积分配头尾额度。 */
    private fun lineBytes(line: String): Int = line.toByteArray(Charsets.UTF_8).size + 1

    /** 取日志行开头的时间戳 `MM-dd HH:mm:ss`，用于省略标记的时间区间。 */
    private fun lineTime(line: String): String =
        TIME_PREFIX.find(line)?.groupValues?.get(1) ?: "-"

    private val TIME_PREFIX = Regex("^(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})")

    /** 上传到 Gitee 私有仓（阻塞，worker 线程调用；30s 总时长超时，同名冲突加 -1 重试一次）。 */
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
            // 总时长上限覆盖建连、写正文、等响应全过程（write 本身没有超时 API）
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return Result(false, null, timeoutText())
            val timedOut = AtomicBoolean(false)
            val outcome = try {
                postGiteeContents(token, attemptName, stamp, encoded, remaining, timedOut)
            } catch (error: Exception) {
                if (timedOut.get()) return Result(false, null, timeoutText())
                return Result(false, null, "网络不通：${error.message.orEmpty()}")
            }
            if (timedOut.get()) return Result(false, null, timeoutText())
            val (code, body) = outcome
            if (code in 200..299) {
                val path = runCatching {
                    JSONObject(body).optJSONObject("content")?.optString("path").orEmpty()
                }.getOrDefault("")
                val remote = path.ifEmpty { attemptName }
                LiteLog.log("log", "dongle 日志上传成功：$remote")
                return Result(true, remote, null)
            }
            val sanitized = body.replace(token, "***")
                .let { if (it.length > 300) it.substring(0, 300) + "…" else it }
            lastError = "HTTP $code $sanitized"
            LiteLog.log("log", "dongle 日志上传失败：$lastError")
            if ((code == 409 || body.contains("already exists", ignoreCase = true)) && attempt == 0) {
                attemptName = prepared.fileName.removeSuffix(".txt") + "-1.txt"
                continue
            }
            break
        }
        return Result(false, null, lastError)
    }

    private fun timeoutText(): String = "上传超时（${TIMEOUT_MILLIS / 1000}s）"

    // ---------- 内部 ----------

    private fun collectLines(file: File, out: ArrayList<String>) {
        try {
            file.bufferedReader().useLines { seq ->
                seq.forEach { line ->
                    val lower = line.lowercase(Locale.US)
                    for (keyword in KEYWORDS) {
                        if (lower.contains(keyword)) {
                            out.add(line)
                            break
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun versionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (_: Exception) {
        "unknown"
    }

    /** 1.3.0-vc207-debug → 1.3.0_vc207（去 -debug 尾巴、-vc → _vc，对齐截图命名）。 */
    private fun buildFileName(context: Context, stamp: String): String {
        val version = versionName(context)
            .removeSuffix("-debug")
            .replace("-vc", "_vc")
        val safeModel = Build.MODEL.trim().replace(Regex("[^0-9A-Za-z._-]"), "-")
        return "dongle-$stamp-Android${Build.VERSION.RELEASE}-api${Build.VERSION.SDK_INT}" +
            "-v$version-$safeModel.txt".replace(Regex("[\\s]+"), "")
    }

    /**
     * POST Gitee contents API：与设置页同一通道（仓库级 token，标题突出 dongle）。
     * [timedOut] 由 watchdog 置位：HttpURLConnection 的 connect/read 超时管不到
     * `outputStream.write()`，慢网下写正文会无限期阻塞；这里用 daemon Timer 在
     * 总时长到点后 disconnect()，把阻塞的 write 打断并按超时失败处理。
     */
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
            .put("message", "DiPlay Lite dongle 连接日志 $stamp")
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
