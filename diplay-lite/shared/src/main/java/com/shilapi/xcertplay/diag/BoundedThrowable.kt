package com.shilapi.xcertplay.diag

/**
 * 限深异常格式化：全程不调用 Throwable.printStackTrace。
 *
 * 背景（T3 Android 4.4 / API19 实测，2026-11-05）：4.4 的 printStackTrace 对
 * cause/suppressed 环没有保护，遇到环会自递归并抛 StackOverflowError
 * （实测栈顶为 Throwable.printStackTrace(Throwable.java:341) 重复 54 帧）。
 * 日志格式化本身变成崩溃源，因此统一改为手工遍历：
 * 固定最大 cause 深度、固定最大栈帧数、遇到重复异常对象立即截断。
 */
fun Throwable.boundedTrace(maxFrames: Int = 5, maxCauses: Int = 3): String {
    val builder = StringBuilder(256)
    val seen = ArrayList<Throwable>(maxCauses + 1)
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth <= maxCauses) {
        var repeated = false
        for (item in seen) {
            if (item === current) {
                repeated = true
                break
            }
        }
        if (repeated) {
            builder.append("\n    [异常链出现环，已截断]")
            break
        }
        seen.add(current)
        if (depth > 0) builder.append("\nCaused by: ")
        builder.append(current.javaClass.name)
        val message = current.message
        if (!message.isNullOrEmpty()) {
            builder.append(": ")
            if (message.length > MAX_MESSAGE_CHARS) {
                builder.append(message, 0, MAX_MESSAGE_CHARS).append("…")
            } else {
                builder.append(message)
            }
        }
        val frames = current.stackTrace
        val limit = if (frames.size < maxFrames) frames.size else maxFrames
        for (index in 0 until limit) {
            builder.append("\n    at ").append(frames[index].toString())
        }
        if (frames.size > limit) {
            builder.append("\n    at ...(").append(frames.size - limit).append(" more)")
        }
        current = current.cause
        depth++
    }
    if (current != null) builder.append("\n    [cause 深度超过 ").append(maxCauses).append("，已截断]")
    return builder.toString()
}

private const val MAX_MESSAGE_CHARS = 200
