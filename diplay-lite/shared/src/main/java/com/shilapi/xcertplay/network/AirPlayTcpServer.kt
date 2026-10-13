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
        /** vc67：携带实际绑定成功的端口（7100 被占时可能回退到其他端口）。 */
        data class Started(val port: Int) : AttachResult() { override fun toString() = "Started(port=$port)" }
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
            val boundPort = startAirPlayServer(
                AirPlayAttachment(bindAddress, config, identity, pairings, mfi, listener, media),
            )
            AttachResult.Started(boundPort)
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

    /**
     * vc67：绑定首选端口，被占时端口回退。coocaa BEP10A 215128 实测：车机自带投屏服务
     * 常驻监听 7100（AirPlay 标准端口族，OEM 常同时占 7000/7100 等多个），且该类服务
     * 重启后 inode 变化、无法无 root 清除。三层策略：
     * ①首选 config.port（默认 7100）按 BIND_ATTEMPTS 重试（自残留竞态自愈）；
     * ②扫 /proc/net/tcp(6) 全表 LISTEN 端口做黑名单，从 7101..7199 选第一个空闲直绑
     *  （跳过被占端口，不再逐个盲试）；③仍失败则 bind(port=0) 内核任选空闲端口兜底。
     * 实际端口经 AttachResult.Started(port) 回传，controller 注入 0x5703 airPlayPort
     * 与 Bonjour TXT——iPhone 侧不写死端口，规范即从这两个字段获取。
     */
    private fun startAirPlayServer(
        replacement: AirPlayAttachment,
    ): Int {
        var lastError: IOException? = null

        fun bindOnce(port: Int): ServerSocket? {
            val server = ServerSocket()
            // API16 适配：bind 前开 SO_REUSEADDR，避免上一会话 socket TIME_WAIT 时 EADDRINUSE。
            server.reuseAddress = true
            return try {
                server.bind(InetSocketAddress(replacement.address, port))
                server
            } catch (error: IOException) {
                runCatching { server.close() }
                lastError = error
                Log.w(TAG, "airplay bind $port failed: ${error.message}")
                // 1.1.2（PDYM20 130013 / EU5 实车 7100 EADDRINUSE）：bind 失败大概率是上一代
                // listener 的 fd 释放竞态（teardown 异步线程与重建 attach 赛跑，且「占用诊断
                // 无占用记录」即自占用特征）——每次重试前强制同步释放本对象持有的旧监听与
                // 会话，再重试 bind。attach 持有本对象 monitor，releaseLocked 合法。
                runCatching { releaseLocked() }
                null
            }
        }

        fun startAcceptLoop(server: ServerSocket, port: Int): Int {
            // attachment.config 同步为实际端口，下游（会话/诊断）读到的 port 恒为真值。
            attachment = replacement.copy(config = replacement.config.copy(port = port))
            serverSocket = server
            val generation = ++attachGeneration
            Thread(
                { acceptLoop(generation, server) },
                "airplay-accept",
            ).apply {
                isDaemon = true
                start()
            }
            return port
        }

        // ① 首选端口，带重试（自占用竞态自愈窗口）
        val preferred = replacement.config.port
        for (attempt in 1..BIND_ATTEMPTS) {
            val server = bindOnce(preferred)
            if (server != null) {
                if (attempt > 1) Log.i(TAG, "airplay bind $preferred recovered on attempt $attempt")
                return startAcceptLoop(server, preferred)
            }
            if (attempt < BIND_ATTEMPTS) {
                try {
                    Thread.sleep(BIND_RETRY_MILLIS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        // ② 预扫 LISTEN 端口黑名单，从首选+1 起找空闲端口直绑
        val occupied = occupiedListenPorts()
        Log.w(
            TAG,
            "airplay port $preferred occupied; LISTEN ports on host: " +
                occupied.sorted().joinToString(",").ifEmpty { "(unreadable)" },
        )
        for (candidate in (preferred + 1)..(preferred + PORT_FALLBACK_RANGE)) {
            if (candidate in occupied) continue
            val server = bindOnce(candidate)
            if (server != null) {
                Log.i(TAG, "airplay fallback bound to $candidate (preferred $preferred occupied)")
                return startAcceptLoop(server, candidate)
            }
        }

        // ③ 内核任选空闲端口兜底（port=0）
        runCatching {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(replacement.address, 0))
            val port = server.localPort
            Log.w(TAG, "airplay ephemeral fallback bound to $port (all candidates occupied)")
            return startAcceptLoop(server, port)
        }.getOrElse { error ->
            lastError = IOException(lastError?.message ?: error.message, error)
        }

        active.set(false)
        throw IOException(
            "AirPlay 监听端口 $preferred 绑定失败（重试${BIND_ATTEMPTS}次+回退" +
                "${preferred + 1}..${preferred + PORT_FALLBACK_RANGE}+随机端口均失败）：" +
                "${lastError?.message}；占用诊断 ${tcpPortOwners(preferred)}",
            lastError,
        )
    }

    /** 读 /proc/net/tcp(6) 返回本机全部 LISTEN（state=0A）本地端口集合；读不到返回空集。 */
    private fun occupiedListenPorts(): Set<Int> = buildSet {
        for (table in listOf("/proc/net/tcp", "/proc/net/tcp6")) {
            val lines = runCatching { File(table).readLines() }.getOrNull() ?: continue
            for (line in lines.drop(1)) {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size > 3 && parts[3] == "0A") {
                    val portHex = parts[1].substringAfterLast(':')
                    val port = portHex.toIntOrNull(16)
                    if (port != null && port > 0) add(port)
                }
            }
        }
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
        /** vc67：首选端口被占时向上扫描的候选端口范围（7101..7110），超出仍失败则走内核随机端口。 */
        private const val PORT_FALLBACK_RANGE = 10
    }
}
