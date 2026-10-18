package com.shilapi.xcertplay.transport

import java.io.File
import java.io.FileOutputStream

/**
 * fe6t1（FE-6 数据流抓包，无 root）：应用层 pcap 采集。
 *
 * Tap 点在 NcmUsbBridge——入站 pollFrame()/出站 send() 的帧边界都是完整以太网帧，
 * 直接以 LINKTYPE_ETHERNET 原样落盘（不做伪 MAC 改写，方向由 Wireshark 按源/目的
 * IP 判读）。不依赖 tcpdump/root，全平台可用。
 *
 * 环形策略：单文件写满 maxFileBytes 换下一个，保留最近 maxFiles 个（wedge 类问题
 * 需要的是断链前最后几十秒，滚动覆盖即可，避免长期抓包撑爆存储）。
 *
 * 由设置页开关控制（PcapTap 单例），bridge 侧在未启动时零开销直返。
 * 文件落在 app 外部私有目录（Android/data/<pkg>/files/pcap/），无 root 可经
 * 文件管理器/USB 直接取出。
 */
object PcapTap {
    const val DIR_INBOUND = 0
    const val DIR_OUTBOUND = 1

    private const val PCAP_MAGIC = 0xa1b2c3d4.toInt()
    private const val VERSION_MAJOR = 2
    private const val VERSION_MINOR = 4
    private const val SNAPSHOT_LEN = 65535
    private const val LINKTYPE_ETHERNET = 1

    private val lock = Any()
    private var dir: File? = null
    private var maxFileBytes: Int = 32 * 1024 * 1024
    private var maxFiles: Int = 3
    private var out: FileOutputStream? = null
    private var fileBytes = 0L
    private var fileIndex = 0
    private var frameCount = 0L

    fun start(targetDir: File, maxFileBytes: Int = 32 * 1024 * 1024, maxFiles: Int = 3): String {
        synchronized(lock) {
            stopLocked()
            if (!targetDir.exists()) targetDir.mkdirs()
            this.dir = targetDir
            this.maxFileBytes = maxFileBytes.coerceAtLeast(1024 * 1024)
            this.maxFiles = maxFiles.coerceAtLeast(1)
            this.fileIndex = 0
            this.frameCount = 0
            openNextFileLocked()
            return currentPathLocked()
        }
    }

    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    fun isActive(): Boolean = synchronized(lock) { out != null }

    fun currentFile(): String? = synchronized(lock) { if (out != null) currentPathLocked() else null }

    fun stats(): String = synchronized(lock) {
        "frames=$frameCount files=$fileIndex active=${out != null} path=${if (out != null) currentPathLocked() else "-"}"
    }

    fun writeFrame(frame: ByteArray, direction: Int) {
        // 热路径：未启用时无锁直返。
        if (out == null) return
        synchronized(lock) {
            val stream = out ?: return
            try {
                val now = System.currentTimeMillis()
                val tsSec = (now / 1000L).toInt()
                val tsUsec = ((now % 1000L) * 1000L).toInt()
                val len = frame.size
                val header = ByteArray(16)
                writeU32(header, 0, tsSec)
                writeU32(header, 4, tsUsec)
                writeU32(header, 8, len)
                writeU32(header, 12, len)
                stream.write(header)
                stream.write(frame, 0, len)
                fileBytes += 16L + len
                frameCount++
                // 方向进日志不进包：0=in / 1=out 由统计行体现（包内容本身已有 IP 方向）。
                if (frameCount % 4096L == 0L) {
                    android.util.Log.i(
                        "PcapTap",
                        "capture progress dir=$direction ${stats()}",
                    )
                }
                if (fileBytes >= maxFileBytes) rollFileLocked()
            } catch (_: Exception) {
                // 存储满/IO 异常：静默停录，绝不影响会话。
                stopLocked()
            }
        }
    }

    private fun openNextFileLocked() {
        val base = dir ?: return
        out = FileOutputStream(File(base, "ncm-${System.currentTimeMillis()}-$fileIndex.pcap")).also {
            val global = ByteArray(24)
            writeU32(global, 0, PCAP_MAGIC)
            writeU16(global, 4, VERSION_MAJOR)
            writeU16(global, 6, VERSION_MINOR)
            writeU32(global, 8, 0) // thiszone
            writeU32(global, 12, 0) // sigfigs
            writeU32(global, 16, SNAPSHOT_LEN)
            writeU32(global, 20, LINKTYPE_ETHERNET)
            it.write(global)
        }
        fileBytes = 24L
    }

    private fun rollFileLocked() {
        runCatching { out?.close() }
        out = null
        pruneOldFilesLocked()
        fileIndex++
        openNextFileLocked()
    }

    private fun pruneOldFilesLocked() {
        val base = dir ?: return
        val files = base.listFiles { f -> f.name.startsWith("ncm-") && f.name.endsWith(".pcap") }
            ?.sortedBy { it.name }?.toMutableList() ?: return
        while (files.size >= maxFiles) {
            runCatching { files.removeAt(0).delete() }
        }
    }

    private fun stopLocked() {
        runCatching { out?.close() }
        out = null
    }

    private fun currentPathLocked(): String {
        val base = dir ?: return "-"
        return File(base, "ncm-current.pcap").parent + " (ncm-*.pcap, index=$fileIndex)"
    }

    private fun writeU32(b: ByteArray, off: Int, v: Int) {
        b[off] = v.toByte(); b[off + 1] = (v ushr 8).toByte()
        b[off + 2] = (v ushr 16).toByte(); b[off + 3] = (v ushr 24).toByte()
    }

    private fun writeU16(b: ByteArray, off: Int, v: Int) {
        b[off] = v.toByte(); b[off + 1] = (v ushr 8).toByte()
    }
}
