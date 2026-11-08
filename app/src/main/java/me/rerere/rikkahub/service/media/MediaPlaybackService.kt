package me.rerere.rikkahub.service.media

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import me.rerere.rikkahub.service.voice.VoiceMessagePlayer
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * 统一媒体播放服务（MediaSessionService，AGENTS.md 12.6）：
 * - 全局唯一 ExoPlayer + MediaSession，电台节目与通话录音共用；
 * - 离开页面继续播放，通知栏由 Media3 默认 Provider 提供（播放/暂停/上一首/下一首）；
 * - 音频焦点由 player.handleAudioAttributes(..., true) 统一处理；
 * - 开始播放时停掉聊天语音条播放器（VoiceMessagePlayer），保证同一时刻只有一个媒体源发声。
 */
class MediaPlaybackService : MediaSessionService(), KoinComponent {
    private var mediaSession: MediaSession? = null
    private val voiceMessagePlayer: VoiceMessagePlayer by lazy { get() }

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        val sessionBuilder = MediaSession.Builder(this, player)
        MediaPlaybackController.sessionActivityIntent(this)?.let { intent ->
            val pendingIntent = android.app.PendingIntent.getActivity(
                this,
                0,
                intent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            sessionBuilder.setSessionActivity(pendingIntent)
        }
        mediaSession = sessionBuilder.build()

        // 媒体播放开始 → 停掉聊天语音条播放器（互斥；反向互斥由 VoiceMessagePlayer.play 调 controller.pause()）
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    voiceMessagePlayer.stop()
                }
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
