package me.rerere.rikkahub.service.notification

import android.content.Context
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import me.rerere.rikkahub.R

/**
 * 通知渠道与铃声试听管理。
 *
 * 渠道精简为以下类别：
 * - [AI_MESSAGES_CHANNEL_ID]：AI 消息提醒（AI 回复 / 主动消息 / 回复生成完成 / AI 任务）
 * - [DIARY_CHANNEL_ID]：日记更新
 * - [PROFILE_CHANNEL_ID]：关系档案更新
 * - [BACKUP_CHANNEL_ID]：数据备份
 * - 语音通话渠道由 VoiceCallForegroundService 创建（ID `voice_call_foreground`），此处不重复创建。
 */
class NotificationSoundManager(private val context: Context) {

    companion object {
        const val AI_MESSAGES_CHANNEL_ID = "ai_messages"
        const val DIARY_CHANNEL_ID = "diary_updates"
        const val PROFILE_CHANNEL_ID = "relationship_profile_updates"
        const val BACKUP_CHANNEL_ID = "data_backup"

        /**
         * 需要清理的历史渠道 ID。
         * 迁移到精简渠道后从系统侧删除这些旧渠道，避免用户在系统通知设置里看到一堆残留类别。
         */
        private val LEGACY_CHANNEL_IDS = listOf(
            "ai_reply",
            "ai_reply_chime",
            "ai_reply_bright",
            "ai_reply_alert",
            "spontaneous_message",
            // 历史「主动消息提醒」渠道：当年 SpontaneousWorker 使用的真实 ID，
            // 主动消息现已统一并入 AI 消息提醒（ai_messages），此渠道需要清理。
            "assistant_spontaneous",
            "chat_completed",
            "backup_status",
            "agent_task_notification",
            "chat_generation_foreground"
        )
    }

    /** 创建精简后的应用自建渠道，并清理历史渠道。 */
    fun setupChannels() {
        val manager = NotificationManagerCompat.from(context)
        createChannel(manager, AI_MESSAGES_CHANNEL_ID, R.string.notification_channel_ai_messages,
            NotificationManagerCompat.IMPORTANCE_HIGH)
        createChannel(manager, DIARY_CHANNEL_ID, R.string.notification_channel_diary_updates,
            NotificationManagerCompat.IMPORTANCE_DEFAULT)
        createChannel(manager, PROFILE_CHANNEL_ID, R.string.notification_channel_relationship_profile,
            NotificationManagerCompat.IMPORTANCE_DEFAULT)
        createChannel(manager, BACKUP_CHANNEL_ID, R.string.notification_channel_data_backup,
            NotificationManagerCompat.IMPORTANCE_HIGH)

        // 清理历史渠道（无效 ID 调用无副作用）
        LEGACY_CHANNEL_IDS.forEach { manager.deleteNotificationChannel(it) }
    }

    private fun createChannel(
        manager: NotificationManagerCompat,
        channelId: String,
        nameRes: Int,
        importance: Int
    ) {
        val channel = NotificationChannelCompat
            .Builder(channelId, importance)
            .setName(context.getString(nameRes))
            .setVibrationEnabled(true)
            .build()
        manager.createNotificationChannel(channel)
    }

    // ===== 铃声试听（设置页使用） =====
    // 切换铃声或重复点击时必须先停掉上一个试听，避免多段音频叠加。

    @Volatile private var previewRingtone: Ringtone? = null

    /** 试听来电铃声：uri 可为系统铃声或自定义 file://。 */
    @Synchronized
    fun playRingtonePreview(uri: Uri?) {
        stopPreview()
        if (uri == null) return
        runCatching {
            val ringtone = RingtoneManager.getRingtone(context, uri)
            previewRingtone = ringtone
            ringtone?.play()
        }
    }

    /** 停止当前试听。 */
    @Synchronized
    fun stopPreview() {
        runCatching { previewRingtone?.stop() }
        previewRingtone = null
    }
}
