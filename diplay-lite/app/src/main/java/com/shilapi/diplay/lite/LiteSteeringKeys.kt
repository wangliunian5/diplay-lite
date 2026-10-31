package com.shilapi.diplay.lite

import android.annotation.TargetApi
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.orchestration.CarPlayController

/**
 * 方控按键调度（M6k22，移植自上游 fork CarPlayMediaKeys，按 Lite 裁剪）。
 *
 * 事件源：
 * - MediaSession（API21+）：CarPlay 播放时持音频焦点，车机系统把媒体键投给本会话；
 *   标准媒体键直接映射 CarPlayMediaButton 发给 iPhone，自定义键按学习绑定匹配。
 * - SteeringKeyMonitor：logcat 日志流 + 广播，执行学习到的自定义绑定。
 * - 学习模式：把观察到的按键上报给设置页，逐步录入 4 个操作。
 * API 红线：MediaSession 仅 API21+ 启用；API16-20 只有 logcat/广播源。
 * AudioFocusRequest 是 API26+，低版本走已废弃的 requestAudioFocus。
 */
internal object LiteSteeringKeys {

    private const val TAG = "DiPlay-Steering"
    private val lock = this
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var controller: CarPlayController? = null
    private var appContext: Context? = null
    private var session: MediaSession? = null // 仅 API21+（startMediaSessionLocked 护栏）
    private var focusListener: AudioManager.OnAudioFocusChangeListener? = null
    private var focusHeld = false
    private var monitor: SteeringKeyMonitor? = null
    private var profile: SteeringProfile? = null
    private var learningOwner: Any? = null
    private var learningCallback: ((SteeringObservedKey?) -> Unit)? = null
    private var learningTimeout: Runnable? = null
    private var suppressedUntil = 0L
    // M6k25：学习向导打开期间整体抑制（未监听态也不触发实际动作），弹窗关闭/重新挂接时复位
    @Volatile private var uiSuppressed = false
    private val lastTrigger = mutableMapOf<String, Long>()
    private val observedKeys = ArrayDeque<String>()

    /** CarPlay 会话建立时挂接：加载配置、启动监听、创建 MediaSession 抢媒体键。 */
    fun attach(context: Context, carPlayController: CarPlayController) = synchronized(lock) {
        appContext = context.applicationContext
        controller = carPlayController
        profile = SteeringStore.loadEnabled(context)
        lastTrigger.clear()
        uiSuppressed = false
        LiteLog.log(TAG, "方控挂接 bindings=${profile?.bindings?.size ?: 0}")
        restartMonitorLocked(discovering = false)
        startMediaSessionLocked()
    }

    /** 会话拆除：释放监听/会话/焦点。 */
    fun detach() = synchronized(lock) {
        controller = null
        stopLearningLocked()
        monitor?.close()
        monitor = null
        releaseMediaSessionLocked()
        profile = null
        lastTrigger.clear()
        LiteLog.log(TAG, "方控已摘除")
    }

    fun reloadProfile(context: Context) = synchronized(lock) {
        appContext = context.applicationContext
        profile = SteeringStore.loadEnabled(context)
        lastTrigger.clear()
        if (learningOwner == null) restartMonitorLocked(discovering = false)
    }

    // ---------- 学习模式（设置页「方向盘按键学习」驱动） ----------

    fun startLearning(context: Context, owner: Any, onKey: (SteeringObservedKey?) -> Unit) = synchronized(lock) {
        stopLearningLocked()
        appContext = context.applicationContext
        learningOwner = owner
        learningCallback = onKey
        learningTimeout = Runnable {
            val callback = synchronized(lock) { learningCallback?.takeIf { learningOwner === owner } }
            stopLearning(owner)
            callback?.invoke(null)
        }.also { mainHandler.postDelayed(it, 20_000L) }
        restartMonitorLocked(discovering = true)
        startMediaSessionLocked()
        LiteLog.log(TAG, "方控学习开始")
    }

    fun stopLearning(owner: Any) = synchronized(lock) {
        if (learningOwner !== owner) return@synchronized
        stopLearningLocked()
        LiteLog.log(TAG, "方控学习结束")
    }

    private fun stopLearningLocked() {
        if (learningOwner == null && learningCallback == null) return
        learningOwner = null
        learningCallback = null
        learningTimeout?.let(mainHandler::removeCallbacks)
        learningTimeout = null
        suppressedUntil = SystemClock.elapsedRealtime() + 500L
        restartMonitorLocked(discovering = false)
    }

    /** 学习向导打开期间整体抑制方控动作；弹窗关闭时必须传 false 复位，attach 亦兜底复位。 */
    fun setUiSuppressed(suppressed: Boolean) = synchronized(lock) { uiSuppressed = suppressed }

    // ---------- 按键调度 ----------

    private fun onObservedKey(key: SteeringObservedKey) {
        val learner = synchronized(lock) {
            if (observedKeys.size >= 40) observedKeys.removeFirst()
            observedKeys.addLast(
                "key=${key.keyCode} event=${key.event} source=${key.source} tag=${key.logTag} broadcast=${key.broadcastAction}",
            )
            learningCallback
        }
        if (learner != null) {
            learner(key)
            return
        }
        if (uiSuppressed || SystemClock.elapsedRealtime() < suppressedUntil) return
        val currentProfile = synchronized(lock) { profile } ?: return
        val binding = currentProfile.bindings.firstOrNull {
            it.keyCode == key.keyCode &&
                (it.source == key.source || (key.source == "session" && it.source == "session")) &&
                (key.source != "logcat" || it.logTag == key.logTag) &&
                it.logContains == key.logContains &&
                (key.source != "broadcast" ||
                    (it.broadcastAction == key.broadcastAction && it.keyExtra == key.keyExtra && it.eventExtra == key.eventExtra))
        } ?: return
        if (key.event != binding.event) return
        val now = SystemClock.elapsedRealtime()
        val ready = synchronized(lock) {
            val previous = lastTrigger[binding.inputId]
            if (previous != null && now - previous < 180L) false
            else {
                lastTrigger[binding.inputId] = now
                true
            }
        }
        if (ready) sendOperation(binding.operation, key.source)
    }

    /**
     * vc87（fe6t5）窗口 KeyEvent 派发入口：FE-6 方控以窗口 KeyEvent 形态进 App
     * （fe6t2/fe6t3 日志：code=87/88 dev=-1 src=0，MediaSession/logcat/广播三通路
     * 零触发），学习绑定建得起但永不派发——「方控按键 sent=」零记录。
     * dispatchKeyEvent 观察侧转调本入口：DOWN 命中学习绑定（学习页按 ACTION_DOWN
     * 存储），UP 命中标准媒体键/默认映射；复用既有 binding 匹配与 180ms 去重。
     */
    fun onWindowKeyEvent(keyCode: Int, event: Int) {
        onSessionKeyEvent(keyCode, event)
    }

    /** MediaSession 回调/学习共用入口：source 固定 "session"。 */
    private fun onSessionKeyEvent(keyCode: Int, event: Int) {
        val learner = synchronized(lock) {
            if (observedKeys.size >= 40) observedKeys.removeFirst()
            observedKeys.addLast("key=$keyCode event=$event source=session")
            learningCallback
        }
        if (learner != null) {
            learner(SteeringObservedKey(keyCode, event, "session"))
            return
        }
        if (uiSuppressed || SystemClock.elapsedRealtime() < suppressedUntil) return
        // 学习绑定的自定义键优先（含厂商键码经 MediaSession 投递的形态）。
        val currentProfile = synchronized(lock) { profile }
        // key_event：学习向导经本地 KeyEvent 学到的键，同一键码经 MediaSession 投递时同样派发
        val binding = currentProfile?.bindings?.firstOrNull {
            (it.source == "session" || it.source == "key_event") && it.keyCode == keyCode
        }
        if (binding != null) {
            if (event != binding.event) return
            val now = SystemClock.elapsedRealtime()
            val ready = synchronized(lock) {
                val previous = lastTrigger[binding.inputId]
                if (previous != null && now - previous < 180L) false
                else {
                    lastTrigger[binding.inputId] = now
                    true
                }
            }
            if (ready) sendOperation(binding.operation, "session")
            return
        }
        if (event != KeyEvent.ACTION_UP) return
        // 仅 ACTION_UP 触发一次；标准媒体键 / Siri 键原生映射。
        val button = CarPlayMediaButton.forKeyCode(keyCode)
        if (button != null) {
            send(button, "session")
            return
        }
        if (CarPlayMediaButton.opensSiri(keyCode) || CarPlayMediaButton.opensSiriWhileCarPlay(keyCode)) {
            requestSiri("session")
        }
    }

    private fun sendOperation(operation: String, source: String) {
        when (operation) {
            "play_pause" -> send(CarPlayMediaButton.PLAY_PAUSE, source)
            "next" -> send(CarPlayMediaButton.NEXT, source)
            "previous" -> send(CarPlayMediaButton.PREVIOUS, source)
            "siri" -> requestSiri(source)
            // 1.0.29：音量键本地执行（调车机媒体音量流），不发 CarPlay 按钮。
            "volume_up" -> setLocalVolume(+1, source)
            "volume_down" -> setLocalVolume(-1, source)
        }
    }

    /** 1.0.29：方控音量键的本地执行 —— 调 STREAM_MUSIC（CarPlay 媒体音频所在流）±1 步。 */
    private fun setLocalVolume(delta: Int, source: String) {
        val context = appContext ?: return
        try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = (audio.getStreamVolume(AudioManager.STREAM_MUSIC) + delta).coerceIn(0, max)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            LiteLog.log(TAG, "方控音量 delta=$delta target=$target/$max source=$source")
        } catch (error: Exception) {
            LiteLog.log(TAG, "方控音量失败 delta=$delta ${error.message}")
        }
    }

    private fun send(index: Int, source: String) {
        val sent = synchronized(lock) { controller }?.sendMediaButton(index) ?: false
        LiteLog.log(TAG, "方控按键 index=$index source=$source sent=$sent")
    }

    private fun requestSiri(source: String) {
        val sent = synchronized(lock) { controller }?.requestSiri() ?: false
        LiteLog.log(TAG, "方控语音键 source=$source sent=$sent")
    }

    // ---------- MediaSession / 音频焦点（API21+） ----------

    /** `MediaSession` / `MediaSession.Callback` 是 API 21+；函数首行已判 `SDK_INT < 21` 直接返回，
     *  注解声明该契约（API16 上这些类根本不会被解析）。 */
    @TargetApi(21)
    private fun startMediaSessionLocked() {
        if (Build.VERSION.SDK_INT < 21 || session != null) return
        val context = appContext ?: return
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS) synchronized(lock) { focusHeld = false }
        }
        focusListener = listener
        focusHeld = try {
            if (Build.VERSION.SDK_INT >= 26) {
                val request = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                    .setOnAudioFocusChangeListener(listener, mainHandler)
                    .build()
                audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                audio.requestAudioFocus(
                    listener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN,
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (_: Exception) {
            false
        }
        session = MediaSession(context, "DiPlay CarPlay").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val key = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return super.onMediaButtonEvent(mediaButtonIntent)
                    if (key.repeatCount > 0) return true
                    onSessionKeyEvent(key.keyCode, key.action)
                    return true
                }
            }, mainHandler)
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                            PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                    )
                    .setState(PlaybackState.STATE_PLAYING, 0L, 1.0f)
                    .build(),
            )
            isActive = true
        }
        LiteLog.log(TAG, "媒体键会话已建立 focusGranted=$focusHeld")
    }

    /** 只操作 `session` 字段（`MediaSession?`）。API16 上该字段恒 null（startMediaSessionLocked
     *  已提前 return），`session?.` 不会解析到 21+ 的类方法；注解声明该前提。 */
    @TargetApi(21)
    private fun releaseMediaSessionLocked() {
        try {
            session?.isActive = false
            session?.release()
        } catch (_: Exception) {
        }
        session = null
        if (focusHeld) {
            try {
                val audio = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                val listener = focusListener
                if (audio != null && listener != null && Build.VERSION.SDK_INT >= 26) {
                    audio.abandonAudioFocus(listener)
                }
            } catch (_: Exception) {
            }
        }
        focusListener = null
        focusHeld = false
    }

    private fun restartMonitorLocked(discovering: Boolean) {
        monitor?.close()
        monitor = null
        val context = appContext ?: return
        val currentProfile = if (discovering) null else profile
        val bindings = currentProfile?.bindings.orEmpty()
        if (!discovering && bindings.none { it.source == "broadcast" || it.source == "logcat" }) return
        val next = SteeringKeyMonitor(context, bindings, discovering, ::onObservedKey)
        next.start()
        monitor = next
    }
}
