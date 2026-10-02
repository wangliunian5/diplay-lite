package com.shilapi.diplay.lite

import android.view.Surface
import com.shilapi.xcertplay.media.AndroidMediaSink

/**
 * M3 视频面桥：Activity 提供 SurfaceView 的 Surface，后台 Service 提供 AndroidMediaSink。
 * 两者生命周期互不依赖（先启动 Service 还是先到前台都可能），故用进程内单例互相补齐：
 * 任一方后到时立即接线，一方先销毁时安全解绑。
 */
object LiteVideoBridge {
    private const val SCREEN_MAIN = 110
    private const val SCREEN_ALT = 111

    private val lock = Any()

    @Volatile private var sink: AndroidMediaSink? = null
    @Volatile private var surface: Surface? = null

    /** Service 创建/销毁 sink 时调用；传入已存在的 Surface 会立即接线。 */
    fun bindSink(newSink: AndroidMediaSink?) = synchronized(lock) {
        sink = newSink
        newSink?.let { current -> surface?.let { applySurface(current, it) } }
    }

    /** SurfaceView 创建/销毁时调用；已存在的 sink 会立即接线或解绑。 */
    fun updateSurface(newSurface: Surface?) = synchronized(lock) {
        val previous = surface
        surface = newSurface
        val current = sink ?: return@synchronized
        if (previous != null && previous !== newSurface) {
            current.clearSurface(SCREEN_MAIN, previous)
            current.clearSurface(SCREEN_ALT, previous)
        }
        if (newSurface != null) applySurface(current, newSurface)
    }

    private fun applySurface(target: AndroidMediaSink, value: Surface) {
        target.setSurface(SCREEN_MAIN, value)
        target.setSurface(SCREEN_ALT, value)
    }
}