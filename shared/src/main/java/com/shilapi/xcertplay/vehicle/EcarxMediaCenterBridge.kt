package com.shilapi.xcertplay.vehicle

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import dalvik.system.DexClassLoader
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * ECARX 媒体中心「冒充在线音源」桥（1.1.4 最小版，路径取自 EasyPlay 0.2.7-lynk
 * ReflectiveBackend 的老 openapi 通道，适配我们无 now-playing 元数据的现状）。
 *
 * 作用：在 ECARX 车机（FE-6 这类）上把本应用注册为媒体中心的一个 ONLINE 音源
 * （sourceType=6），让车机媒体卡片/Dock 认识 CarPlay 播放；不发布歌名（后续接
 * iAP2 0x5001 NowPlayingUpdate 再喂）。
 *
 * 接入协议（全部反射，编译期零 ECARX 依赖，非 ECARX 车机探测失败即静默禁用）：
 *   1. 探测 com.ecarx.sdk.mediacenter.MediaCenterAPI（固件内置）或
 *      DexClassLoader 加载 /system/framework/ecarx.openapi.impl.jar；
 *   2. MediaCenterAPI.createMediaCenterAPI(ownedContext) —— ownedContext 劫持
 *      bindService，SDK 内部连接时把 IBinder 记下来供后续 binder 调用；
 *   3. MediaCenterAPI.connect(ECarXApiClient$ConnectionCallbacks proxy)；
 *   4. MediaCenterAPI.registerMusic(IMusicClient proxy) 拿 token —— proxy 里
 *      getCurrentSourceType/getSourceTypeList 恒回 6（ONLINE）；
 *   5. CarPlay 媒体流开始时显式抢源（2s 节流）：binder 上
 *      requestPlay(token) → updateCurrentSourceType(token, 6) →
 *      updateMusicPlaybackState(token, PlaybackInfoWrapper(infoProxy))。
 *
 * 健壮性：所有调用在单线程串行；被拒后 publishDenied 锁存不再重试（等下个显式
 * 抢源）；反射异常统一拆 InvocationTargetException 只落异常简名，绝不外抛。
 */
class EcarxMediaCenterBridge(
    private val context: Context,
    private val diagnostics: (String) -> Unit,
) : Closeable {
    private val lifecycle = Any()
    private val worker: ExecutorService =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "diplay-ecarx-media").apply { isDaemon = true }
        }
    @Volatile private var closed = false
    private var active = false

    // 探测/连接产物（worker 线程独占，无需 volatile）
    private var apiClass: Class<*>? = null
    private var serviceType: Class<*>? = null
    private var tokenType: Class<*>? = null
    private var ownedContext: OwnedBindingContext? = null
    private var api: Any? = null
    private var token: Any? = null
    private var wrapperConstructor: java.lang.reflect.Constructor<*>? = null

    // 发布状态机（EasyPlay 同款语义）
    private var pendingClaimAtMs = 0L
    private var publishDenied = false
    private var sourceClaimed = false

    companion object {
        private const val MEDIA_CENTER_API = "com.ecarx.sdk.mediacenter.MediaCenterAPI"
        private const val OPENAPI_JAR = "/system/framework/ecarx.openapi.impl.jar"
        private const val GKUI_JAR = "/system/framework/ecarx.gkui.openapi.impl.jar"
        private const val CONNECTION_CALLBACKS = "com.ecarx.sdk.openapi.ECarXApiClient\$ConnectionCallbacks"
        private const val IMUSIC_CLIENT = "com.ecarx.sdk.mediacenter.IMusicClient"
        private const val IMUSIC_PLAYBACK_INFO = "com.ecarx.sdk.mediacenter.IMusicPlaybackInfo"
        private const val PLAYBACK_INFO_WRAPPER = "com.ecarx.sdk.mediacenter.PlaybackInfoWrapper"
        private const val SVC_DESCRIPTOR = "ecarx.xsf.mediacenter.IMediaCenterSvc"
        private const val SVC_WIRE_INFO = "ecarx.xsf.mediacenter.IMusicPlaybackInfo"
        private const val SVC_CLIENT_TOKEN = "ecarx.xsf.mediacenter.IMediaCenterClientToken"
        private const val SVC_ACTION = "ecarx.xsf.MEDIA_CENTER_SERVICE"
        private const val SVC_PACKAGE = "ecarx.xsf.mediacenter"
        private const val SOURCE_ONLINE = 6
        private const val CLAIM_INTERVAL_MS = 2000L
    }

    /** 会话开始时调用；探测失败/已禁用为无害空操作。 */
    fun activate() {
        synchronized(lifecycle) {
            if (closed || active) return
            active = true
        }
        submit { connectAndRegister() }
    }

    /** CarPlay 媒体流开始（sink onAudioStarted, audioType=media）时触发显式抢源。 */
    fun onCarPlayMediaStarted() {
        if (!active || closed) return
        submit { publish(explicit = true) }
    }

    override fun close() {
        synchronized(lifecycle) {
            if (closed) return
            closed = true
            active = false
        }
        submit {
            unregisterQuiet()
            ownedContext?.close()
            ownedContext = null
            api = null
            token = null
        }
        worker.shutdown()
    }

    // ---------- worker 线程内部 ----------

    private fun connectAndRegister() {
        if (closed || !active || api != null) return
        if (File(GKUI_JAR).isFile) {
            diagnostic("disabled: gkui sdk present but unsupported in minimal bridge")
            return
        }
        try {
            val loader = loadEcarxLoader() ?: return
            val apiType = Class.forName(MEDIA_CENTER_API, true, loader)
            val owned = OwnedBindingContext(context)
            val instance = invoke(
                apiType.getMethod("createMediaCenterAPI", Context::class.java),
                null,
                owned,
            ) ?: run {
                diagnostic("disabled: createMediaCenterAPI returned null")
                return
            }
            apiClass = apiType
            ownedContext = owned
            api = instance
            val callbacksType = Class.forName(CONNECTION_CALLBACKS, true, loader)
            val connectionProxy = Proxy.newProxyInstance(
                loader,
                arrayOf(callbacksType),
                InvocationHandler { _, method, args ->
                    when (method.name) {
                        "onConnected" -> {
                            submit { onConnected() }
                            null
                        }
                        "onConnectionSuspended" -> {
                            diagnostic("connection suspended")
                            null
                        }
                        else -> defaultValue(method)
                    }
                },
            )
            invoke(apiType.getMethod("connect", callbacksType), instance, connectionProxy)
            diagnostic("connecting")
        } catch (error: Throwable) {
            diagnostic("stage=connect exception=${simpleName(error)}")
            release()
        }
    }

    private fun onConnected() {
        if (closed || !active || api == null || token != null) return
        try {
            val loader = apiClass?.classLoader ?: return
            val clientType = Class.forName(IMUSIC_CLIENT, true, loader)
            val clientProxy = Proxy.newProxyInstance(
                loader,
                arrayOf(clientType),
                InvocationHandler { _, method, _ -> musicClientValue(method) },
            )
            token = invoke(
                apiClass?.getMethod("registerMusic", clientType)
                    ?: throw IOException("ECARX api lost"),
                api,
                clientProxy,
            ) ?: throw IOException("ECARX returned no media token")
            val service = Class.forName(SVC_DESCRIPTOR, true, loader)
            val wireInfo = Class.forName(IMUSIC_PLAYBACK_INFO, true, loader)
            serviceType = service
            tokenType = Class.forName(SVC_CLIENT_TOKEN, true, loader)
            wrapperConstructor =
                Class.forName(PLAYBACK_INFO_WRAPPER, true, loader).getConstructor(wireInfo)
            diagnostic("registered")
            publish(explicit = false)
        } catch (error: Throwable) {
            diagnostic("stage=register exception=${simpleName(error)}")
            release()
        }
    }

    /** 显式（媒体流开始）或首次注册后的发布：requestPlay → selectSource → update。 */
    private fun publish(explicit: Boolean) {
        if (closed || !active || publishDenied) return
        val currentToken = token ?: return
        val now = SystemClock.elapsedRealtime()
        if (explicit) {
            if (now - pendingClaimAtMs < CLAIM_INTERVAL_MS) return
            pendingClaimAtMs = now
        }
        try {
            if (!sourceClaimed) {
                val accepted = call("requestPlay", arrayOf(tokenType ?: return), currentToken) == true
                if (!accepted) {
                    publishDenied = true
                    diagnostic("stage=request_rejected")
                    return
                }
                sourceClaimed = true
                diagnostic("stage=request_accepted")
            }
            selectSource(currentToken)
            update(currentToken)
        } catch (error: Throwable) {
            publishDenied = true
            diagnostic("stage=publish exception=${simpleName(error)}")
        }
    }

    private fun selectSource(currentToken: Any) {
        call("updateCurrentSourceType", arrayOf(tokenType ?: return, Int::class.javaPrimitiveType ?: Int::class.java), currentToken, SOURCE_ONLINE)
    }

    /** 发布空元数据快照（无歌名）：PlaybackInfoWrapper(IMusicPlaybackInfo proxy)。 */
    private fun update(currentToken: Any) {
        val loader = apiClass?.classLoader ?: return
        val infoType = Class.forName(IMUSIC_PLAYBACK_INFO, true, loader)
        val infoProxy = Proxy.newProxyInstance(
            loader,
            arrayOf(infoType),
            InvocationHandler { _, method, _ -> playbackInfoValue(method) },
        )
        val wrapper = wrapperConstructor?.newInstance(infoProxy)
            ?: throw IOException("ECARX playback info wrapper unavailable")
        // 服务侧签名是 ecarx.xsf.mediacenter.IMusicPlaybackInfo（wire 接口），
        // PlaybackInfoWrapper 实现/包装它——按接口找方法，不能按 wrapper 运行时类。
        val wireInfoType = Class.forName(SVC_WIRE_INFO, true, loader)
        call(
            "updateMusicPlaybackState",
            arrayOf(tokenType ?: return, wireInfoType),
            currentToken,
            wrapper,
        )
        diagnostic("stage=metadata_published")
    }

    private fun unregisterQuiet() {
        val currentToken = token ?: return
        try {
            val ok = call("unregister", arrayOf(tokenType ?: return), currentToken) == true
            diagnostic(if (ok) "stage=unregistered" else "stage=unregister_rejected")
        } catch (error: Throwable) {
            diagnostic("stage=unregister exception=${simpleName(error)}")
        } finally {
            token = null
            sourceClaimed = false
            publishDenied = false
        }
    }

    /** binder 调用：IMediaCenterSvc$Stub.asInterface(ownedContext.binder) 后反射。 */
    private fun call(name: String, parameterTypes: Array<Class<*>>, vararg args: Any?): Any? {
        val owned = ownedContext ?: throw IOException("ECARX media service is disconnected")
        val binder = owned.binder() ?: throw IOException("ECARX media service is disconnected")
        if (!binder.isBinderAlive) throw IOException("ECARX media service is dead")
        val loader = apiClass?.classLoader ?: throw IOException("ECARX loader lost")
        val stub = Class.forName("$SVC_DESCRIPTOR\$Stub", true, loader)
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder) ?: throw IOException("ECARX returned no media service")
        val service = serviceType ?: throw IOException("ECARX service type lost")
        val method = service.getMethod(name, *parameterTypes)
        return invoke(method, stub, *args)
    }

    private fun release() {
        token = null
        sourceClaimed = false
        publishDenied = false
        api = null
        apiClass = null
        serviceType = null
        tokenType = null
        wrapperConstructor = null
        ownedContext?.close()
        ownedContext = null
    }

    // ---------- 反射辅助 ----------

    /** 探测固件内置 MediaCenterAPI，否则 DexClassLoader 加载系统 openapi jar。 */
    private fun loadEcarxLoader(): ClassLoader? {
        return try {
            Class.forName(MEDIA_CENTER_API).classLoader
        } catch (expected: ClassNotFoundException) {
            val jar = File(OPENAPI_JAR)
            if (!jar.isFile || !jar.canRead()) {
                diagnostic("disabled: no ECARX media SDK on this head unit")
                return null
            }
            DexClassLoader(
                jar.path,
                context.getDir("ecarx-media-sdk", 0).path,
                null,
                context.classLoader,
            )
        }
    }

    private fun invoke(method: Method, receiver: Any?, vararg args: Any?): Any? {
        return try {
            method.invoke(receiver, *args)
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    /** IMusicClient proxy：媒体控制键上报（当前仅诊断），音源恒为 ONLINE(6)。 */
    private fun musicClientValue(method: Method): Any? {
        when (method.name) {
            "onPlay" -> diagnostic("media action=play")
            "onPause" -> diagnostic("media action=pause")
            "onNext" -> diagnostic("media action=next")
            "onPrevious" -> diagnostic("media action=previous")
            "getCurrentSourceType" -> return SOURCE_ONLINE
            "getMediaSourceTypeList" -> return intArrayOf(SOURCE_ONLINE)
            "getPlaylist" -> return emptyList<Any>()
            "getCurrentProgress" -> return 0L
            "getMusicPlaybackInfo" -> return null
        }
        return defaultValue(method)
    }

    /** IMusicPlaybackInfo proxy：空元数据（无歌名），音源 ONLINE、播放中。 */
    private fun playbackInfoValue(method: Method): Any? {
        when (method.name) {
            "getTitle", "getArtist", "getAlbum" -> return ""
            "getPlaybackStatus" -> return 1
            "getSourceType" -> return SOURCE_ONLINE
            "getDuration" -> return 0L
            "getPlayingItemPositionInQueue" -> return -1
            "getLaunchIntent" -> return null
        }
        return defaultValue(method)
    }

    /** 接口 proxy 未识别方法的返回值兜底（基本类型回零值，其余 null）。 */
    private fun defaultValue(method: Method): Any? {
        return when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Character.TYPE -> ' '
            else -> null
        }
    }

    private fun simpleName(error: Throwable): String {
        val cause = (error as? InvocationTargetException)?.targetException ?: error
        val name = cause.javaClass.simpleName
        return if (name.isNotEmpty() && Regex("[A-Za-z_\$][A-Za-z0-9_\$]*").matches(name)) name else "Exception"
    }

    private fun diagnostic(message: String) {
        runCatching { diagnostics("Ecarx media $message") }
    }

    private fun submit(block: () -> Unit) {
        try {
            worker.execute { runCatching { block() } }
        } catch (_: RejectedExecutionException) {
        }
    }

    /**
     * 劫持 bindService 的 Context：SDK 内部 connect 时用这个 Context 发起绑定，
     * 我们在回调里记录 IBinder 供后续 binder 调用；close 统一 unbind。
     * 只放行 ECARX 媒体中心服务的绑定请求（EasyPlay 同款护栏）。
     */
    private class OwnedBindingContext(base: Context) : ContextWrapper(base), Closeable {
        private val connections = LinkedHashMap<ServiceConnection, ServiceConnection>()
        @Volatile private var binder: IBinder? = null
        @Volatile private var closed = false

        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            if (closed) throw IllegalStateException("ECARX binding is closed")
            if (intent.action != SVC_ACTION || intent.`package` != SVC_PACKAGE) {
                throw IllegalArgumentException("unexpected ECARX binding intent")
            }
            val wrapper = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    synchronized(this@OwnedBindingContext) {
                        if (closed) return
                        binder = service
                        connection.onServiceConnected(name, service)
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    synchronized(this@OwnedBindingContext) {
                        if (closed) return
                        binder = null
                        connection.onServiceDisconnected(name)
                    }
                }
            }
            synchronized(this) { connections[connection] = wrapper }
            return try {
                super.bindService(intent, wrapper, flags)
            } catch (error: Exception) {
                synchronized(this) { connections.remove(connection) }
                throw error
            }
        }

        fun binder(): IBinder? = binder

        override fun close() {
            synchronized(this) {
                if (closed) return
                closed = true
                binder = null
                val all = connections.values.toList()
                connections.clear()
                for (connection in all) {
                    runCatching { unbindService(connection) }
                }
            }
        }
    }
}
