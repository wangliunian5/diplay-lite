package com.shilapi.xcertplay.network

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
        val generation = ++attachGeneration
        return try {
            startAirPlayServer(
                generation,
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
        generation: Int,
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

    /** 读 /proc/net/tcp 列出占用指定端口的 socket（本地地址、状态、inode），用于定位 EADDRINUSE 来源。 */
    private fun tcpPortOwners(port: Int): String = runCatching {
        val wanted = String.format("%04X", port)
        val found = StringBuilder()
        val lines = File("/proc/net/tcp").readLines()
        for (line in lines.drop(1)) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size > 9 && parts[1].endsWith(":$wanted")) {
                found.append("[").append(parts[1]).append(" state=").append(parts[3])
                    .append(" inode=").append(parts[9]).append("]")
            }
        }
        if (found.isEmpty()) "(无占用记录)" else found.toString()
    }.getOrElse { "诊断不可用" }

    private fun acceptLoop(
        generation: Int,
        server: ServerSocket,
    ) {
        try {
            while (active.get()) {
                val socket: Socket = server.accept()
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
