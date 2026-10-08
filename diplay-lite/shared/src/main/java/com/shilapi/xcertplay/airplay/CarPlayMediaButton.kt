package com.shilapi.xcertplay.airplay

import android.view.KeyEvent

/**
 * 硬件媒体按键 → CarPlay 媒体 HID 按键序号（对应 [AirPlayHid] 的 media report 索引）。
 *
 * 移植自上游 fork（M6k22，JSON/行为同构）：硬件 play/pause 都映射到 toggle，
 * 因为车机方控固件对播放状态的理解不可靠；明确的 play/pause 命令用 [PLAY]/[PAUSE]。
 */
object CarPlayMediaButton {
    const val PLAY = 1
    const val PAUSE = 2
    const val PLAY_PAUSE = 3
    const val NEXT = 4
    const val PREVIOUS = 5

    /** BYD 方控播放/暂停键；固件通常改写为 MEDIA_PLAY/PAUSE。 */
    const val KEYCODE_BYD_AUTO_MEDIA_PLAY_PAUSE = 353

    /** BYD 方控语音键：短按与长按键码。 */
    const val KEYCODE_BYD_AUTO_MEDIA_VOICE = 304
    const val KEYCODE_BYD_AUTO_MEDIA_VOICE_LONG = 312

    /** DiLink 3 的播放/暂停键（KEYCODE_AUTO_MEDIA_PLAY_PAUSE），固件未改写时。 */
    const val KEYCODE_BYD_AUTO_MEDIA_PLAY_PAUSE_DILINK3 = 331

    /** DiLink 3 的 CarPlay 语音键（短按/长按）。 */
    const val KEYCODE_BYD_CARPLAY_VOICE = 327
    const val KEYCODE_BYD_CARPLAY_VOICE_LONG = 328

    /** [keyCode] 是否是打开 Siri 的语音键。 */
    fun opensSiri(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_VOICE_ASSIST ||
        keyCode == KEYCODE_BYD_AUTO_MEDIA_VOICE || keyCode == KEYCODE_BYD_AUTO_MEDIA_VOICE_LONG

    /** 仅 CarPlay 会话期间由 BYD 下发的语音键。 */
    fun opensSiriWhileCarPlay(keyCode: Int): Boolean =
        keyCode == KEYCODE_BYD_CARPLAY_VOICE || keyCode == KEYCODE_BYD_CARPLAY_VOICE_LONG

    /** [keyCode] 对应的 CarPlay 按键，非媒体键返回 null。 */
    fun forKeyCode(keyCode: Int, experimentalDiLink3Keys: Boolean = false): Int? = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_NEXT -> NEXT
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> PREVIOUS
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_HEADSETHOOK,
        KEYCODE_BYD_AUTO_MEDIA_PLAY_PAUSE -> PLAY_PAUSE
        KEYCODE_BYD_AUTO_MEDIA_PLAY_PAUSE_DILINK3 -> if (experimentalDiLink3Keys) PLAY_PAUSE else null
        else -> null
    }
}
