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
    private const val FILE_MAX_BYTES = 1L shl 20 // 超过 1MB 轮转
    private const val FILE_NAME = "diplay-log.txt"
    private const val OLD_NAME = "diplay-log-old.txt"

    private val lines = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileLock = Any()
    private var writer: FileWriter? = null
    private var logDirectory: File? = null
    private var fileBytes = 0L

    /** 挂载文件持久化；失败静默降级为仅内存日志，绝不影响主流程。 */
    fun attachFileLog(directory: File) {
        synchronized(fileLock) {
            try {
                directory.mkdirs()
                val file = File(directory, FILE_NAME)
                val old = File(directory, OLD_NAME)
                if (old.exists()) old.delete()
                if (file.exists() && file.length() > FILE_MAX_BYTES) file.renameTo(old)
                fileBytes = if (file.exists()) file.length() else 0L
                writer = FileWriter(file, true)
                logDirectory = directory
            } catch (error: Exception) {
                writer = null
                logDirectory = null
                Log.w("LiteLog", "文件日志不可用: ${error.message}")
            }
        }
        log("log", "文件日志挂载：${logDirectory?.absolutePath ?: "(失败)"}")
    }

    /** 当前日志目录；未挂载或失败时为 null。 */
    fun directory(): File? = logDirectory

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
        val directory = logDirectory ?: run {
            writer = null
            return
        }
        val file = File(directory, FILE_NAME)
        val old = File(directory, OLD_NAME)
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

    fun clear() = synchronized(lines) { lines.clear() }
}
