package com.shilapi.diplay.lite

import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 环形内存日志 + 可选文件持久化（无 adb 实测的观测通道，文件管理器/MTP 读取）。
 * 设计文档 §4.3：release 版可通过把 onTrace 回调置空整体关闭。
 */
object LiteLog {
    private const val MAX = 200
    // 每进程启动新建一个带时间戳的日志文件（diplay-log-<yyyyMMdd-HHmmss>.txt）：
    //  ① 文件名不同 → MTP/文件管理器必然刷新，不再"看不到更新"；
    //  ② 每份日志自成一体（含"构建标识"开头），不像轮转那样丢前半段（T29 就丢过）。
    private const val FILE_MAX_BYTES = 8L shl 20 // 8MB 上限，测试会话基本不会触发轮转
    private const val FILE_PREFIX = "diplay-log-"
    private const val OLD_SUFFIX = "-old.txt"

    private val lines = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileLock = Any()
    private var writer: FileWriter? = null
    private var logDirectory: File? = null
    private var fileBytes = 0L
    @Volatile private var currentFile: File? = null

    /** 挂载文件持久化；失败静默降级为仅内存日志，绝不影响主流程。 */
    fun attachFileLog(directory: File) {
        synchronized(fileLock) {
            try {
                directory.mkdirs()
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val file = File(directory, "$FILE_PREFIX$stamp.txt")
                fileBytes = 0L
                writer = FileWriter(file, true)
                logDirectory = directory
                currentFile = file
            } catch (error: Exception) {
                writer = null
                logDirectory = null
                currentFile = null
                Log.w("LiteLog", "文件日志不可用: ${error.message}")
            }
        }
        log("log", "文件日志挂载：${currentFile?.absolutePath ?: "(失败)"}")
    }

    /** 当前日志目录；未挂载或失败时为 null。 */
    fun directory(): File? = logDirectory

    /** 当前日志文件（带启动时间戳）；未挂载时为 null。 */
    fun currentFile(): File? = currentFile

    fun log(tag: String, message: String) {
        val stamp = synchronized(timeFormat) { timeFormat.format(Date()) }
        val line = "$stamp [$tag] $message"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX) lines.removeFirst()
        }
        synchronized(fileLock) {
            val w = writer ?: return
            try {
                w.write(line)
                w.write("\n")
                w.flush()
                fileBytes += line.length + 1
                if (fileBytes > FILE_MAX_BYTES) rotateLocked()
            } catch (_: Exception) {
                // 写入失败不影响主流程
            }
        }
    }

    private fun rotateLocked() {
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        val file = currentFile ?: run {
            writer = null
            return
        }
        val old = File(file.parentFile, file.nameWithoutExtension + OLD_SUFFIX)
        if (old.exists()) old.delete()
        if (!file.renameTo(old)) {
            // 轮转失败：截断重开，保证后续日志不丢
            file.delete()
        }
        fileBytes = 0L
        writer = try {
            FileWriter(file, true)
        } catch (_: Exception) {
            null
        }
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    /**
     * 把当前日志复制成同目录下的**新文件**（带时间戳），供"无 adb 实测"时手动取日志。
     *
     * 为什么不用原名：MTP / 车机文件管理器对**已存在的同名文件**常按旧缓存显示（"看不到更新"）。
     * 每次导出生成一个新文件名，文件管理器必然能看到。
     *
     * @return 导出的文件；未挂载、无内容或失败时返回 null。
     */
    fun exportSnapshot(): File? {
        val directory = logDirectory ?: return null
        synchronized(fileLock) {
            return try {
                writer?.flush()
                val source = currentFile ?: return null
                if (!source.exists() || source.length() == 0L) return null
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val target = File(directory, "diplay-log-export-$stamp.txt")
                source.copyTo(target, overwrite = true)
                target
            } catch (error: Exception) {
                Log.w("LiteLog", "日志导出失败: ${error.message}")
                null
            }
        }
    }

    fun clear() = synchronized(lines) { lines.clear() }
}
