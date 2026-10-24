package me.rerere.rikkahub.ui.pages.call

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.core.data.db.entity.CallRecordEntity
import me.rerere.rikkahub.ui.components.chat.formatCallDuration
import me.rerere.rikkahub.ui.components.ui.ToastAction
import me.rerere.rikkahub.ui.components.ui.ToastType
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.AppShapes
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 通话详情页（只读）：录音播放器 + 该 callId 的真实通话转写 + 录音保存/删除。
 *
 * - 不提供发送输入框；
 * - 转写仅展示 messageSource == CALL 的真实内容，不混入决策指令 / CALL_STATE 标记；
 * - 录音过期或已被删除时仍可读取转写，播放器显示不可用状态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallDetailPage(callId: String) {
    val vm: CallDetailVM = koinViewModel(parameters = { parametersOf(callId) })
    val navController = LocalNavController.current

    val record by vm.record.collectAsStateWithLifecycle()
    val transcript by vm.transcript.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val speed by vm.speed.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val assistantName by vm.assistantName.collectAsStateWithLifecycle()
    val userName by vm.userName.collectAsStateWithLifecycle()

    val toaster = LocalToaster.current
    val clipboard = LocalClipboardManager.current
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // 一次性操作提示：统一走全局顶部 Toaster；保存成功附带「复制路径」动作
    LaunchedEffect(message) {
        message?.let { msg ->
            val copyText = msg.copyText
            toaster.show(
                message = msg.text,
                type = if (copyText != null) ToastType.Success else ToastType.Normal,
                duration = if (copyText != null) 8000L else 4000L,
                action = copyText?.let { path ->
                    ToastAction("复制路径") { clipboard.setText(AnnotatedString(path)) }
                }
            )
            vm.clearMessage()
        }
    }

    val hasPlayableAudio = record?.audio != null && !vm.isExpired(record)
    val canSave = hasPlayableAudio && record?.forever == 0
    val isConnected = record?.connectedAt != null || (record?.durationMs ?: 0L) > 0L

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("通话详情", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, null)
                    }
                },
                actions = {
                    TextButton(
                        onClick = { vm.saveForever() },
                        enabled = canSave
                    ) {
                        Text("保存通话录音")
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
                PlayerCard(
                    record = record,
                    isPlaying = playback?.key == vm.audioKey && playback?.isPlaying == true,
                    positionMs = playback?.takeIf { it.key == vm.audioKey }?.positionMs ?: 0L,
                    durationMs = playback?.takeIf { it.key == vm.audioKey }?.durationMs
                        ?.takeIf { it > 0L } ?: (record?.durationMs ?: 0L),
                    expired = vm.isExpired(record),
                    currentSpeed = speed,
                    onToggle = { vm.togglePlay(record) },
                    onSeek = { vm.seekTo(it) },
                    onSpeed = { vm.setSpeed(it) },
                    onDelete = if (hasPlayableAudio) {
                        { showDeleteConfirm = true }
                    } else null
                )
            }

            if (!isConnected) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = AppShapes.CardMedium,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "未接通 / 已挂断",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            val reason = record?.endReason
                            if (!reason.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "结束原因：${describeEndReason(reason)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            if (transcript.isEmpty()) {
                item {
                    Text(
                        text = "没有通话转写记录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
            } else {
                items(transcript) { msg -> TranscriptRow(msg, userName, assistantName) }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除录音") },
            text = { Text("确定要删除这条通话录音吗？删除后不可恢复，但通话转写会保留。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    vm.deleteAudio(record)
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun PlayerCard(
    record: CallRecordEntity?,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    expired: Boolean,
    currentSpeed: Float,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeed: (Float) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    val hasAudio = record?.audio != null
    val speeds = listOf(1.0f, 1.25f, 1.5f, 2.0f)
    // 从持久化的当前倍速推导索引；若不在预设档位内，回退到 0 档。
    val speedIndex = speeds.indexOfFirst { it == currentSpeed }.coerceAtLeast(0)
    val displayedSpeed = speeds[speedIndex]

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.CardLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            val unavailable = !hasAudio || expired
            if (unavailable) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.MusicOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (expired) "录音已过期，无法播放" else "无通话录音",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                return@Column
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggle) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = formatCallDuration(positionMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = positionMs.toFloat().coerceIn(0f, durationMs.coerceAtLeast(0L).toFloat()),
                    onValueChange = { onSeek(it.toLong()) },
                    valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
                Text(
                    text = formatCallDuration(durationMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("删除录音")
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }
                TextButton(onClick = {
                    val nextIndex = (speedIndex + 1) % speeds.size
                    onSpeed(speeds[nextIndex])
                }) {
                    Text("${displayedSpeed}x")
                }
            }
        }
    }
}

@Composable
private fun TranscriptRow(message: UIMessage, userName: String, assistantName: String) {
    val isUser = message.role == MessageRole.USER
    val text = message.parts
        .filterIsInstance<UIMessagePart.Text>()
        .joinToString("") { it.text }
        .trim()
    if (text.isBlank()) return

    val speakerName = if (isUser) userName else assistantName.ifEmpty { "助手" }

    val alignment: Alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val container = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val onContainer = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Card(
            shape = AppShapes.CardMedium,
            colors = CardDefaults.cardColors(containerColor = container),
            modifier = Modifier.fillMaxWidth(0.88f)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text = speakerName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = onContainer
                )
            }
        }
    }
}

private fun describeEndReason(reason: String): String = when (reason) {
    "rejected" -> "用户拒绝"
    "missed" -> "未接听（超时）"
    "permission_denied" -> "麦克风权限不足"
    "init_failed" -> "语音会话初始化失败"
    "user_hangup" -> "用户挂断"
    "agent_hangup" -> "助手挂断"
    "cancelled" -> "已取消"
    "timeout" -> "静默超时"
    "error" -> "通话异常"
    else -> reason
}
