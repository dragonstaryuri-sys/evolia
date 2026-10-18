package me.rerere.rikkahub.service.voice

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import me.rerere.rikkahub.R

/**
 * 通话固定音效管理（当前仅"挂断提示音"）。
 *
 * 使用内置于 res/raw 的原创合成音效（call_hangup.wav），由 MediaPlayer 播放并在结束后释放。
 * 仅在**真实接通的通话结束**时播放一次；拒绝/超时/初始化失败路径不经过此逻辑，因此不会重复播放。
 */
object CallSoundPlayer {
    private const val TAG = "CallSoundPlayer"

    /** 去重窗口：1.5 秒内只播放一次，兜底防止多个结束回调重复播。 */
    private const val MIN_INTERVAL_MS = 1500L

    @Volatile private var hangupPlayer: MediaPlayer? = null
    @Volatile private var lastHangupAt = 0L

    /** 播放固定挂断提示音。 */
    @Synchronized
    fun playHangup(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastHangupAt < MIN_INTERVAL_MS) {
            Log.i(TAG, "playHangup: skipped (within dedupe window)")
            return
        }
        lastHangupAt = now
        runCatching {
            releaseHangup()
            val mp = MediaPlayer.create(context, R.raw.call_hangup) ?: return
            mp.setOnCompletionListener { finished ->
                runCatching { finished.release() }
                if (hangupPlayer === finished) hangupPlayer = null
            }
            hangupPlayer = mp
            mp.start()
            Log.i(TAG, "playHangup: playing")
        }.onFailure { Log.w(TAG, "playHangup failed", it) }
    }

    /** 释放资源（幂等）。 */
    @Synchronized
    fun releaseHangup() {
        runCatching { hangupPlayer?.release() }
        hangupPlayer = null
    }
}
