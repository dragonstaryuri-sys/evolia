package me.rerere.rikkahub.ui.components.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.core.data.db.entity.BackgroundTaskStatus
import me.rerere.rikkahub.data.backgroundtask.BackgroundTaskManager
import me.rerere.rikkahub.data.backgroundtask.BackgroundTaskTypes
import me.rerere.rikkahub.data.backgroundtask.RadioAudioTaskCodec
import me.rerere.rikkahub.data.radio.RadioService
import me.rerere.rikkahub.service.media.MediaPlaybackController
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.AppShapes
import org.koin.compose.koinInject

/**
 * 通用后台任务卡片（消息流内）：状态唯一来源是 background_tasks 表（与 Agent 查询同源，
 * UI 与 Agent 不会看到互相矛盾的状态）。五态展示：排队中 / 生成中（逐句进度）/
 * 已完成（播放入口）/ 失败（原因 + 重试提示）/ 已取消。
 *
 * 挂载惯例同 RadioShareCard/PrintTaskCard：识别 Tool.Result 中的 task_id 生成渲染项，
 * 不把任务状态存成 Assistant 消息的临时字段；离开页面/切换会话/重启 App 后随 DB 自动恢复。
 * callback_state 不混入卡片主状态（任务完成但回调未送达时卡片仍显示成功）。
 */
@Composable
fun BackgroundTaskCard(
    taskId: String,
    modifier: Modifier = Modifier,
    backgroundTaskManager: BackgroundTaskManager = koinInject(),
    radioService: RadioService = koinInject(),
    player: MediaPlaybackController = koinInject(),
) {
    val task by remember(taskId) { backgroundTaskManager.observeTask(taskId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val navController = LocalNavController.current

    // 节目标题与播放条目（payload 内为创建时快照；播放条目实时构建，失败即禁用播放）
    var episodeTitle by remember(taskId) { mutableStateOf("") }
    var playableItem by remember(taskId) {
        mutableStateOf<me.rerere.rikkahub.service.media.PlaybackItem?>(null)
    }
    val episodeId = task?.let { currentTask ->
        if (currentTask.taskType == BackgroundTaskTypes.RADIO_AUDIO_GENERATION) {
            RadioAudioTaskCodec.decodePayload(currentTask.payloadJson)?.episodeId
        } else {
            null
        }
    }
    LaunchedEffect(taskId, episodeId) {
        if (episodeId.isNullOrBlank()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val payloadTask = task
            episodeTitle = payloadTask?.let {
                RadioAudioTaskCodec.decodePayload(it.payloadJson)?.episodeTitle
            } ?: ""
            playableItem = runCatching { radioService.buildPlaybackItem(episodeId) }.getOrNull()
        }
    }

    Surface(
        shape = AppShapes.CardMedium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            val currentTask = task
            if (currentTask == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Radio,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "任务记录不存在或已被清理",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                return@Column
            }

            val status = BackgroundTaskStatus.from(currentTask.status)
            val (icon, label, iconTint) = when (status) {
                BackgroundTaskStatus.QUEUED -> Triple(
                    Icons.Rounded.HourglassTop, "排队中", MaterialTheme.colorScheme.onSurfaceVariant
                )
                BackgroundTaskStatus.RUNNING -> Triple(
                    Icons.Rounded.GraphicEq, "生成中", MaterialTheme.colorScheme.primary
                )
                BackgroundTaskStatus.COMMITTING -> Triple(
                    Icons.Rounded.GraphicEq, "正在提交结果", MaterialTheme.colorScheme.primary
                )
                BackgroundTaskStatus.SUCCEEDED -> Triple(
                    Icons.Rounded.TaskAlt, "已完成", MaterialTheme.colorScheme.primary
                )
                BackgroundTaskStatus.FAILED -> Triple(
                    Icons.Rounded.ErrorOutline, "生成失败", MaterialTheme.colorScheme.error
                )
                BackgroundTaskStatus.CANCELLED -> Triple(
                    Icons.Rounded.Cancel, "已取消", MaterialTheme.colorScheme.onSurfaceVariant
                )
                null -> Triple(
                    Icons.Rounded.Radio, currentTask.status, MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp)
                )
                Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(
                        text = episodeTitle.ifBlank { "电台节目音频" },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = iconTint
                    )
                }
                // 完成后播放入口（音频可用即可播；点击卡片跳节目详情）
                if (status == BackgroundTaskStatus.SUCCEEDED && playableItem != null) {
                    IconButton(onClick = { playableItem?.let { player.playSingleItem(it) } }) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = "播放",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // 生成进度：synthesizing 阶段显示逐句 N/M 进度条；merging/encoding 阶段
            // （合成完成后还有合并 PCM → AAC 编码两段重活）显示阶段文案；提交阶段由 COMMITTING 状态表达
            val progress = remember(currentTask.progressJson) {
                currentTask.progressJson?.let {
                    runCatching {
                        me.rerere.rikkahub.common.JsonInstant.decodeFromString(
                            me.rerere.rikkahub.data.backgroundtask.RadioAudioTaskProgress.serializer(), it
                        )
                    }.getOrNull()
                }
            }
            val phaseText = when {
                status != BackgroundTaskStatus.RUNNING -> null
                progress != null && progress.phase == me.rerere.rikkahub.data.backgroundtask.RadioAudioTaskProgress.PHASE_MERGING ->
                    "分句合成完成，正在合并音频…"
                progress != null && progress.phase == me.rerere.rikkahub.data.backgroundtask.RadioAudioTaskProgress.PHASE_ENCODING ->
                    "合并完成，正在编码音频…"
                progress != null && progress.total > 0 -> "第 ${progress.completed}/${progress.total} 句"
                else -> "任务执行中"
            }
            val isSentenceProgress = status == BackgroundTaskStatus.RUNNING &&
                progress != null && progress.total > 0 &&
                progress.phase == me.rerere.rikkahub.data.backgroundtask.RadioAudioTaskProgress.PHASE_SYNTHESIZING
            if (status == BackgroundTaskStatus.RUNNING || status == BackgroundTaskStatus.QUEUED) {
                if (isSentenceProgress && progress != null) {
                    LinearProgressIndicator(
                        progress = { progress.completed.toFloat() / progress.total },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                }
                if (phaseText != null) {
                    Text(
                        text = phaseText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            // 失败原因 + 重试提示（Agent 可通过 get_background_task 读取同一 error_message）
            if (status == BackgroundTaskStatus.FAILED) {
                Text(
                    text = "失败原因：" + (currentTask.errorMessage ?: "未知") +
                        "。可以与助手确认后重新生成。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // 完成后引导：播放 + 跳详情
            if (status == BackgroundTaskStatus.SUCCEEDED && episodeId != null) {
                Text(
                    text = "点击卡片右上角播放，或进入节目详情查看音频",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // 整卡点击跳节目详情（与 RadioShareCard 一致的交互惯例）
            if (episodeId != null) {
                val clickableModifier = Modifier.fillMaxWidth()
                Surface(
                    onClick = { navController.navigate(Screen.RadioEpisode(episodeId = episodeId)) },
                    color = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = AppShapes.ListItemSmall,
                    modifier = clickableModifier.padding(top = 4.dp)
                ) {
                    Text(
                        text = "打开节目详情 →",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}
