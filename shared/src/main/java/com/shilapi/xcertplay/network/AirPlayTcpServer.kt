package com.shilapi.xcertplay.network

import android.os.Build
import android.util.Log
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * AirPlay TCP 监听器（Lite：自 minapi21 分支 CarPlayVpnService.attachWireless 抽出）。
 *
 * 无线路径不需要 Android VPN 接口——仅在热点地址上绑定 ServerSocket 并为每个入站连接
 * 创建 [AirPlaySession]。原 Service + Binder + VPN/NCM 有线逻辑全部裁剪。
 */
class AirPlayTcpServer {

    sealed class AttachResult {
        object Started : AttachResult() { override fun toString() = "Started" }
        object AlreadyStarted : AttachResult() { override fun toString() = "AlreadyStarted" }
        data class Failed(val message: String) : AttachResult()
    }

    private data class AirPlayAttachment(
        val address: InetAddress,
        val config: AirPlayConfig,
        val identity: AirPlayIdentity,
        val pairings: PairingStore,
        val mfi: MfiAuthenticator?,
        val listener: AirPlaySessionListener,
        val media: AirPlayMediaHandler,
    )

    private val active = AtomicBoolean(false)
    private val sessionsLock = Any()
    private val sessions = mutableSetOf<AirPlaySession>()
    @Volatile private var attachment: AirPlayAttachment? = null
    private var serverSocket: ServerSocket? = null
    private var attachGeneration = 0
    private val acceptedConnections = AtomicInteger(0)

    /** 已接受的入站 TCP 连接总数（断线重连采样 tcpAccepted 判据：0=iPhone 未进来）。 */
    fun acceptedConnectionCount(): Int = acceptedConnections.get()

    /** 在热点地址上启动 AirPlay 监听；重复调用会先替换旧附件。 */
    @Synchronized
    fun attach(
        bindAddress: InetAddress,
        config: AirPlayConfig,
        identity: AirPlayIdentity,
        pairings: PairingStore,
        mfi: MfiAuthenticator?,
        listener: AirPlaySessionListener,
        media: AirPlayMediaHandler,
    ): AttachResult {
        if (active.get()) {
            Log.i(TAG, "replacing stale wireless attachment")
            releaseLocked()
        }
        active.set(true)
        return try {
            startAirPlayServer(
                AirPlayAttachment(bindAddress, config, identity, pairings, mfi, listener, media),
            )
            AttachResult.Started
        } catch (error: Exception) {
            releaseLocked()
            AttachResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /** 释放当前 AirPlay 监听与所有会话。 */
    @Synchronized
    fun detach() {
        releaseLocked()
    }

    fun isAttached(): Boolean = active.get() && attachment != null

    private fun startAirPlayServer(
        replacement: AirPlayAttachment,
    ) {
        val port = replacement.config.port
        var lastError: IOException? = null
        for (attempt in 1..BIND_ATTEMPTS) {
            val server = ServerSocket()
            // API16 适配：bind 前开 SO_REUSEADDR，避免上一会话 socket TIME_WAIT 时 EADDRINUSE。
            server.reuseAddress = true
            try {
                server.bind(InetSocketAddress(replacement.address, port))
                attachment = replacement
                serverSocket = server
                val generation = ++attachGeneration
                Thread(
                    { acceptLoop(generation, server) },
                    "airplay-accept",
                ).apply {
                    isDaemon = true
                    start()
                }
                return
            } catch (error: IOException) {
                runCatching { server.close() }
                lastError = error
                Log.w(TAG, "airplay bind $port attempt=$attempt failed: ${error.message}")
                // 1.1.2（PDYM20 130013 / EU5 实车 7100 EADDRINUSE）：bind 失败大概率是上一代
                // listener 的 fd 释放竞态（teardown 异步线程与重建 attach 赛跑，且「占用诊断
                // 无占用记录」即自占用特征）——每次重试前强制同步释放本对象持有的旧监听与
                // 会话，再重试 bind。attach 持有本对象 monitor，releaseLocked 合法。
                runCatching { releaseLocked() }
                if (attempt < BIND_ATTEMPTS) {
                    try {
                        Thread.sleep(BIND_RETRY_MILLIS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }
        }
        active.set(false)
        throw IOException(
            "AirPlay 监听端口 $port 绑定失败（重试${BIND_ATTEMPTS}次）：" +
                "${lastError?.message}；占用诊断 ${tcpPortOwners(port)}",
            lastError,
        )
    }

    /**
     * 读 /proc/net/tcp(6) 列出占用指定端口的 socket（本地地址、状态、inode），并尽力反查持有进程，
     * 用于定位 EADDRINUSE 来源。tcp6 也要扫：[::]:7000 这类 v6 通配监听同样会顶掉 0.0.0.0:7000 的 bind。
     */
    private fun tcpPortOwners(port: Int): String = runCatching {
        val wanted = String.format("%04X", port)
        val found = StringBuilder()
        for (table in listOf("/proc/net/tcp", "/proc/net/tcp6")) {
            val lines = runCatching { File(table).readLines() }.getOrNull() ?: continue
            for (line in lines.drop(1)) {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size > 9 && parts[1].endsWith(":$wanted")) {
                    val inode = parts[9]
                    found.append("[")
                    if (table.endsWith("6")) found.append("IPv6 ")
                    found.append(parts[1]).append(" state=").append(parts[3]).append(" inode=").append(inode)
                    socketOwner(inode)?.let { found.append(' ').append(it) }
                    found.append("]")
                }
            }
        }
        if (found.isEmpty()) "(无占用记录)" else found.toString()
    }.getOrElse { "诊断不可用" }

    /**
     * M6k3：inode → 进程反查。扫各进程 /proc/<pid>/fd 下的 socket:[inode] 软链，命中后读 cmdline/comm 取进程名。
     * 车机 ROM 的 SELinux 普遍宽松，多数能直接读出占用者（系统服务/残留实例一目了然）；
     * 读不到（新系统跨应用 procfs 隔离）就返回 null，诊断退回只显示 inode，与旧行为一致。
     * 注意：本应用自己进程的 fd 一定能读 —— 10-02 那种"自己残留监听顶掉新会话"的场景会被直接点名。
     */
    private fun socketOwner(inode: String): String? {
        val target = "socket:[$inode]"
        val pids = File("/proc").listFiles { file -> file.name.all { it in '0'..'9' } } ?: return null
        for (pidDir in pids) {
            val fds = runCatching { File(pidDir, "fd").listFiles() }.getOrNull() ?: continue
            for (fd in fds) {
                if (readLink(fd) != target) continue
                val name = runCatching {
                    File(pidDir, "cmdline").readBytes().toString(Charsets.UTF_8).substringBefore('\u0000').trim()
                }.getOrNull()?.ifEmpty { null }
                    ?: runCatching { File(pidDir, "comm").readText().trim() }.getOrNull()?.ifEmpty { null }
                    ?: "(进程名不可读)"
                val self = if (pidDir.name.toIntOrNull() == android.os.Process.myPid()) "（本应用进程）" else ""
                return "pid=${pidDir.name} name=$name$self"
            }
        }
        return null
    }

    /** readlink：API21+ 用 Os；4.x 无该 API，反射 libcore.io.Libcore.os（平台类，与 MediaCodec 探测同款做法）。 */
    private fun readLink(file: File): String? = try {
        if (Build.VERSION.SDK_INT >= 21) {
            android.system.Os.readlink(file.absolutePath)
        } else {
            val os = Class.forName("libcore.io.Libcore").getField("os").get(null)
            os?.javaClass?.methods?.firstOrNull { it.name == "readlink" }
                ?.invoke(os, file.absolutePath) as? String
        }
    } catch (_: Throwable) {
        null
    }

    private fun acceptLoop(
        generation: Int,
        server: ServerSocket,
    ) {
        try {
            while (active.get()) {
                val socket: Socket = server.accept()
                acceptedConnections.incrementAndGet()
                Log.i(TAG, "airplay connection accepted from ${socket.remoteSocketAddress}")
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.setSoLinger(true, 0)
                val session = synchronized(this) {
                    if (!active.get()) {
                        socket.close()
                        return
                    }
                    val current = attachment
                    if (current == null) {
                        socket.close()
                        return
                    }
                    AirPlaySession(
                        socket = socket,
                        config = current.config,
                        identity = current.identity,
                        pairings = current.pairings,
                        mfi = current.mfi,
                        listener = object : AirPlaySessionListener by current.listener {
                            override fun onSessionEnded(session: AirPlaySession) {
                                removeSession(session)
                                current.listener.onSessionEnded(session)
                            }
                        },
                        media = current.media,
                    ).also(::addSession)
                }
                session.start()
            }
        } catch (error: IOException) {
            if (active.get()) {
                attachment?.listener?.let { onTransportError(generation, it, error) }
            }
        }
    }

    private fun addSession(session: AirPlaySession) {
        synchronized(sessionsLock) { sessions.add(session) }
    }

    private fun removeSession(session: AirPlaySession?) {
        if (session == null) return
        synchronized(sessionsLock) { sessions.remove(session) }
    }

    private fun closeSessionsLocked() {
        synchronized(sessionsLock) {
            sessions.toList().forEach { session ->
                try {
                    session.close()
                } catch (error: Exception) {
                    Log.w(TAG, "AirPlay session replacement failed", error)
                }
            }
            sessions.clear()
        }
    }

    private fun onTransportError(
        generation: Int,
        listener: AirPlaySessionListener,
        error: Throwable,
    ) {
        val message = error.message ?: error.javaClass.simpleName
        Log.e(TAG, "CarPlay transport stopped: $message", error)
        Thread(
            {
                synchronized(this) {
                    if (generation != attachGeneration) return@Thread
                    releaseLocked()
                }
                listener.onTransportError(message)
            },
            "airplay-teardown",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** Caller must hold this server's monitor. */
    private fun releaseLocked() {
        attachGeneration += 1
        active.set(false)
        attachment = null
        serverSocket?.close()
        serverSocket = null
        closeSessionsLocked()
    }

    companion object {
        private const val TAG = "xcertplay-airplay"
        private const val BIND_ATTEMPTS = 3
        private const val BIND_RETRY_MILLIS = 500L
    }
}
