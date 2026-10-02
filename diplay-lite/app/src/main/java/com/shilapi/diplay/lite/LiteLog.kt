package com.shilapi.diplay.lite

/**
 * 环形内存日志：只保留最近 [MAX] 行，避免 512MB 设备上的日志膨胀。
 * 设计文档 §4.3：release 版可通过把 onTrace 回调置空整体关闭。
 */
object LiteLog {
    private const val MAX = 200
    private val lines = ArrayDeque<String>()

    fun log(tag: String, message: String) {
        synchronized(lines) {
            lines.addLast("${System.currentTimeMillis()} [$tag] $message")
            while (lines.size > MAX) lines.removeFirst()
        }
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun clear() = synchronized(lines) { lines.clear() }
}
