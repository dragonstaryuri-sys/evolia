package me.rerere.rikkahub.ui.pages.setting

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.service.notification.NotificationSoundManager
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupItem
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup

@Composable
fun SettingNotificationPage() {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val lazyListState = rememberLazyListState()

    val manager = remember { NotificationManagerCompat.from(context) }

    fun openSystemNotificationSettings() {
        runCatching {
            context.startActivity(
                Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** 读取某个渠道在系统侧是否被关闭。渠道尚未创建时视为可用。 */
    fun isChannelDisabled(channelId: String): Boolean {
        val channel = manager.getNotificationChannel(channelId) ?: return false
        return channel.importance == NotificationManagerCompat.IMPORTANCE_NONE
    }

    Scaffold(
        topBar = {
            OneUITopAppBar(
                title = stringResource(R.string.notification_page_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = { BackButton() }
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = lazyListState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // 通知类型：App 内开关无法覆盖系统渠道设置，统一引导到系统通知设置页操作。
            item {
                SettingsGroup(title = "") {
                    NotificationChannelRow(
                        title = stringResource(R.string.notification_page_switch_ai_reply),
                        subtitle = stringResource(R.string.notification_page_switch_ai_reply_desc),
                        channelDisabled = isChannelDisabled(NotificationSoundManager.AI_MESSAGES_CHANNEL_ID),
                        onClick = { openSystemNotificationSettings() }
                    )
                    NotificationChannelRow(
                        title = stringResource(R.string.notification_page_switch_diary),
                        subtitle = stringResource(R.string.notification_page_switch_diary_desc),
                        channelDisabled = isChannelDisabled(NotificationSoundManager.DIARY_CHANNEL_ID),
                        onClick = { openSystemNotificationSettings() }
                    )
                    NotificationChannelRow(
                        title = stringResource(R.string.notification_page_switch_profile),
                        subtitle = stringResource(R.string.notification_page_switch_profile_desc),
                        channelDisabled = isChannelDisabled(NotificationSoundManager.PROFILE_CHANNEL_ID),
                        onClick = { openSystemNotificationSettings() }
                    )
                    NotificationChannelRow(
                        title = stringResource(R.string.notification_page_switch_backup),
                        subtitle = stringResource(R.string.notification_page_switch_backup_desc),
                        channelDisabled = isChannelDisabled(NotificationSoundManager.BACKUP_CHANNEL_ID),
                        onClick = { openSystemNotificationSettings() }
                    )
                    // 语音通话：前台服务通知渠道（由 VoiceCallForegroundService 创建）
                    NotificationChannelRow(
                        title = stringResource(R.string.notification_page_switch_call),
                        subtitle = stringResource(R.string.notification_page_switch_call_desc),
                        channelDisabled = isChannelDisabled("voice_call_foreground"),
                        onClick = { openSystemNotificationSettings() }
                    )
                }
            }

            // 第二组：提示音和铃声
            item {
                SettingsGroup(title = stringResource(R.string.notification_page_sound_section)) {
                    // 消息提示音由系统通知渠道控制，App 内不再提供独立设置页
                    SettingGroupItem(
                        title = stringResource(R.string.notification_page_message_sound),
                        subtitle = stringResource(R.string.notification_page_message_sound_desc),
                        onClick = { openSystemNotificationSettings() },
                        trailing = {
                            Text(
                                text = stringResource(R.string.notification_page_go_to_system),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    )
                    SettingGroupItem(
                        title = stringResource(R.string.notification_page_call_ringtone),
                        subtitle = stringResource(R.string.notification_page_call_ringtone_desc),
                        onClick = { navController.navigate(Screen.SettingCallRingtone) }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

/**
 * 通知渠道行：点击跳转到系统通知设置页。
 * 当渠道在系统侧被关闭时，右侧提示文字标红，引导用户去系统开启。
 */
@Composable
private fun NotificationChannelRow(
    title: String,
    subtitle: String,
    channelDisabled: Boolean,
    onClick: () -> Unit,
) {
    SettingGroupItem(
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        trailing = {
            Text(
                text = stringResource(R.string.notification_page_go_to_system),
                style = MaterialTheme.typography.bodyMedium,
                color = if (channelDisabled) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
            )
        }
    )
}
