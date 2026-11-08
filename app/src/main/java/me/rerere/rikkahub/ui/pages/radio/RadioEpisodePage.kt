package me.rerere.rikkahub.ui.pages.radio

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Publish
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.common.ui.components.ConfirmDialog
import me.rerere.rikkahub.common.ui.components.EmptyPlaceholder
import me.rerere.rikkahub.common.ui.components.InlineSpinner
import me.rerere.rikkahub.common.ui.components.InputDialog
import me.rerere.rikkahub.core.data.db.entity.RadioEpisodeStatus
import me.rerere.rikkahub.service.media.MediaPlaybackController
import me.rerere.rikkahub.service.media.SleepTimerState
import me.rerere.rikkahub.ui.components.chat.formatCallDuration
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.components.ui.ToastType
import me.rerere.rikkahub.ui.theme.AppShapes
import org.koin.compose.koinInject
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 节目详情页：状态 + 播放器（P2 控制条 + P1 设置抽屉）+ 播放列表抽屉 + 文字稿。
 *
 * 删除节目不提供 UI 入口（删除只向创作 Agent 开放，经工具执行并落审计）；
 * 文字稿正文只读展示，内容修改走 Agent（update_episode）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioEpisodePage(episodeId: String) {
    val vm: RadioEpisodeVM = koinViewModel(parameters = { parametersOf(episodeId) })
    val player: MediaPlaybackController = koinInject()
    val toaster = LocalToaster.current

    val episode by vm.episode.collectAsStateWithLifecycle()
    val currentAsset by vm.currentAsset.collectAsStateWithLifecycle()
    val backupAsset by vm.backupAsset.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val generating by vm.generating.collectAsStateWithLifecycle()
    val generationError by vm.generationError.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val playerState by player.state.collectAsStateWithLifecycle()
    val sleepTimer by player.sleepTimer.collectAsStateWithLifecycle()
    val speedOnlyCurrent by player.speedOnlyCurrent.collectAsStateWithLifecycle()

    var showRenameDialog by remember { mutableStateOf(false) }
    var showExportConfirm by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showPlaylistSheet by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            toaster.show(message = it.text, type = if (it.isError) ToastType.Error else ToastType.Success)
            vm.clearMessage()
        }
    }
    LaunchedEffect(generationError) {
        generationError?.let {
            toaster.show(message = it, type = ToastType.Error)
            vm.clearGenerationError()
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            OneUITopAppBar(
                title = episode?.title ?: stringResource(R.string.radio_episode_detail),
                scrollBehavior = scrollBehavior,
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { vm.addToQueue() }) {
                        Icon(Icons.Rounded.PlaylistAdd, contentDescription = stringResource(R.string.radio_add_to_queue))
                    }
                    IconButton(onClick = { showPlaylistSheet = true }) {
                        Icon(Icons.Rounded.QueueMusic, contentDescription = stringResource(R.string.radio_playlist_title))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                StatusCard(episode = episode)
            }
            if (episode?.status == RadioEpisodeStatus.PUBLISHED) {
                item {
                    PlayerCard(
                    isPlaying = playback?.isPlaying == true,
                    positionMs = playback?.positionMs ?: 0L,
                    durationMs = playback?.durationMs ?: currentAsset?.durationMs ?: 0L,
                    assetDurationMs = currentAsset?.durationMs ?: 0L,
                    hasCurrentAudio = currentAsset != null,
                    generating = generating,
                    hasBackup = backupAsset != null,
                    backupExpiresAt = backupAsset?.expiresAt,
                    liked = episode?.liked == 1,
                    published = episode?.status == RadioEpisodeStatus.PUBLISHED,
                    onToggle = { vm.togglePlay() },
                    onSeek = { vm.seekTo(it) },
                    onSeekBack = { player.seekBy(-MediaPlaybackController.SEEK_BACK_MS) },
                    onSeekForward = { player.seekBy(MediaPlaybackController.SEEK_FORWARD_MS) },
                    onGenerate = { vm.generateAudio() },
                    onCancelGeneration = { vm.cancelAudioGeneration() },
                    onAddToQueue = { vm.addToQueue() },
                    onRestoreBackup = { vm.restoreBackup() },
                    onPublish = { vm.publish() },
                    onOpenSettings = { showSettingsSheet = true },
                    onToggleLiked = { vm.toggleLiked() }
                    )
                }
            } else {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = AppShapes.CardMedium,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.radio_draft_notice),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
            }
            item {
                ScriptCard(content = episode?.content.orEmpty())
            }
        }
    }

    if (showRenameDialog) {
        var renameValue by remember(episode?.id) { mutableStateOf(episode?.title.orEmpty()) }
        InputDialog(
            onDismissRequest = { showRenameDialog = false },
            onConfirm = {
                showRenameDialog = false
                vm.updateTitle(renameValue)
            },
            title = stringResource(R.string.radio_rename_title),
            value = renameValue,
            onValueChange = { renameValue = it },
            confirmText = "保存",
            singleLine = true
        )
    }

    if (showExportConfirm) {
        ConfirmDialog(
            onDismissRequest = { showExportConfirm = false },
            onConfirm = {
                showExportConfirm = false
                vm.exportAudio()
            },
            title = stringResource(R.string.radio_export_audio),
            text = "将节目音频复制导出到手机 Download/Evolia/radio/ 目录。导出的是独立副本，之后在应用内删除音频不会影响导出文件。",
            confirmText = stringResource(R.string.radio_export),
            dismissText = stringResource(R.string.radio_cancel)
        )
    }

    // ===== 播放设置抽屉（P1：倍速 + 睡眠定时）=====
    if (showSettingsSheet) {
        PlayerSettingsSheet(
            speed = playerState.speed,
            speedOnlyCurrent = speedOnlyCurrent,
            sleepTimer = sleepTimer,
            onDismiss = { showSettingsSheet = false },
            onSpeedChange = { player.setSpeed(it) },
            onSpeedOnlyCurrentChange = { player.setSpeedOnlyCurrent(it) },
            onSleepTimerMinutes = { player.setSleepTimer(it) },
            onSleepTimerEndOfEpisode = { player.setSleepTimerEndOfEpisode(it) }
        )
    }

    // ===== 播放列表抽屉（共享组件：模式切换/拖拽排序/切播不关抽屉）=====
    if (showPlaylistSheet) {
        PlaylistSheet(controller = player, onDismiss = { showPlaylistSheet = false })
    }
}

@Composable
private fun StatusCard(episode: me.rerere.rikkahub.core.data.db.entity.RadioEpisodeEntity?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.CardLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Tag(
                type = if (episode?.status == RadioEpisodeStatus.PUBLISHED) TagType.SUCCESS else TagType.INFO
            ) {
                Text(
                    text = if (episode?.status == RadioEpisodeStatus.PUBLISHED) stringResource(R.string.radio_status_published) else stringResource(R.string.radio_status_draft),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = buildString {
                    if (episode != null) {
                        append("更新于 ")
                        append(formatDateTime(episode.updatedAt))
                        episode.publishedAt?.let {
                            append(" · 发布于 ")
                            append(formatDateTime(it))
                        }
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 播放器卡片（P2 控制条布局）：
 * 左上角重新生成 → 进度条 → 底部「设置 | 后退15s | 播放/暂停 | 前进30s | 点赞」。
 */
@Composable
private fun PlayerCard(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    assetDurationMs: Long,
    hasCurrentAudio: Boolean,
    generating: Boolean,
    hasBackup: Boolean,
    backupExpiresAt: Long?,
    liked: Boolean,
    published: Boolean,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onGenerate: () -> Unit,
    onCancelGeneration: () -> Unit,
    onAddToQueue: () -> Unit,
    onRestoreBackup: () -> Unit,
    onPublish: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleLiked: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.CardLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when {
                hasCurrentAudio -> {
                    // 左上角：重新生成（生成中显示转圈）
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (generating) {
                            InlineSpinner(size = 16.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.radio_generating),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onCancelGeneration) {
                                Text(stringResource(R.string.radio_stop))
                            }
                        } else {
                            TextButton(onClick = onGenerate, enabled = !generating) {
                                Text(stringResource(R.string.radio_regenerate))
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            IconButton(onClick = onAddToQueue) {
                                Icon(
                                    Icons.Rounded.PlaylistAdd,
                                    contentDescription = stringResource(R.string.radio_add_to_queue)
                                )
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = formatCallDuration(positionMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = positionMs.toFloat()
                                .coerceIn(0f, durationMs.coerceAtLeast(0L).toFloat()),
                            onValueChange = { onSeek(it.toLong()) },
                            valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                        )
                        Text(
                            text = formatCallDuration(if (durationMs > 0) durationMs else assetDurationMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // P2 控制条：设置 | 后退15s | 播放/暂停 | 前进30s | 点赞
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.radio_playback_settings))
                        }
                        IconButton(onClick = onSeekBack) {
                            Icon(Icons.Rounded.Replay10, contentDescription = stringResource(R.string.radio_seek_back))
                        }
                        IconButton(onClick = onToggle) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (isPlaying) stringResource(R.string.radio_pause) else stringResource(R.string.radio_play),
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        IconButton(onClick = onSeekForward) {
                            Icon(Icons.Rounded.Forward30, contentDescription = stringResource(R.string.radio_seek_forward))
                        }
                        IconButton(onClick = onToggleLiked) {
                            Icon(
                                imageVector = if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                contentDescription = if (liked) stringResource(R.string.radio_unlike_episode) else stringResource(R.string.radio_like_episode),
                                tint = if (liked) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }

                    if (hasBackup) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.radio_backup_audio) + (backupExpiresAt?.let {
                                    stringResource(R.string.radio_backup_expires, formatDateTime(it))
                                } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onRestoreBackup, enabled = !generating) {
                                Icon(Icons.Rounded.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.radio_restore_backup))
                            }
                        }
                    }
                }

                generating -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InlineSpinner()
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.radio_generating),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.radio_no_audio),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = onGenerate) {
                            Icon(Icons.Rounded.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.radio_generate_audio))
                        }
                    }
                }
            }

            if (!published) {
                Button(
                    onClick = onPublish,
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppShapes.ButtonPill
                ) {
                    Icon(Icons.Rounded.Publish, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.radio_publish_episode))
                }
            }
        }
    }
}

/**
 * 播放设置抽屉（P1 参考稿）：
 * - 倍速播放：0.5x–3.0x 刻度条 + 「只应用到当前节目」开关
 * - 睡眠定时：关 / 30 / 60 / 90 分钟 chips + 「定时到本集结束」开关
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSettingsSheet(
    speed: Float,
    speedOnlyCurrent: Boolean,
    sleepTimer: SleepTimerState,
    onDismiss: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onSpeedOnlyCurrentChange: (Boolean) -> Unit,
    onSleepTimerMinutes: (Int) -> Unit,
    onSleepTimerEndOfEpisode: (Boolean) -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = AppShapes.BottomSheet
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ===== 倍速播放 =====
            Card(
                shape = AppShapes.CardMedium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "倍速播放",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "%.1fx".format(speed),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    var localSpeed by remember(speed) { mutableFloatStateOf(speed) }
                    Slider(
                        value = localSpeed,
                        onValueChange = { localSpeed = it },
                        onValueChangeFinished = { onSpeedChange(localSpeed) },
                        valueRange = MediaPlaybackController.MIN_SPEED..MediaPlaybackController.MAX_SPEED,
                        steps = 24 // 0.1 步进
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "只应用到当前节目",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = speedOnlyCurrent,
                            onCheckedChange = onSpeedOnlyCurrentChange
                        )
                    }
                }
            }

            // ===== 睡眠定时 =====
            Card(
                shape = AppShapes.CardMedium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "睡眠定时",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = when {
                                sleepTimer.endOfEpisode -> "本集结束"
                                sleepTimer.minutes > 0 -> "${sleepTimer.minutes} 分钟"
                                else -> "OFF"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = if (sleepTimer.minutes > 0 || sleepTimer.endOfEpisode) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MediaPlaybackController.SLEEP_TIMER_OPTIONS.forEach { minutes ->
                            val label = if (minutes == 0) "关" else "$minutes"
                            AssistChip(
                                onClick = { onSleepTimerMinutes(minutes) },
                                label = { Text(label) },
                                enabled = !sleepTimer.endOfEpisode
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "定时到本集结束",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = sleepTimer.endOfEpisode,
                            onCheckedChange = onSleepTimerEndOfEpisode
                        )
                    }
                    // 分钟定时倒计时（mm:ss）
                    if (sleepTimer.minutes > 0 && sleepTimer.remainingSeconds > 0) {
                        Text(
                            text = "剩余 %02d:%02d".format(
                                sleepTimer.remainingSeconds / 60,
                                sleepTimer.remainingSeconds % 60
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScriptCard(content: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.CardLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.radio_script),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            if (content.isBlank()) {
                Text(
                    text = stringResource(R.string.radio_script_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                SelectionContainer {
                    Text(
                        text = content,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
