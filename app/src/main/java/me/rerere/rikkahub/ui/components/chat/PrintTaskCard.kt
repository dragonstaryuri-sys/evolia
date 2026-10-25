package me.rerere.rikkahub.ui.components.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.print.PrintTask
import me.rerere.rikkahub.data.print.PrintTaskManager
import me.rerere.rikkahub.data.print.PrintTaskStatus
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import org.koin.compose.koinInject

/**
 * 聊天内的打印任务卡片：标题/目标打印机/实时状态/打印预览/确认与取消操作/
 * 发送进度/失败原因与重试。状态来源于 [PrintTaskManager]（集中状态机），
 * 确认与取消不触发任何 AI 轮次。
 */
@Composable
fun PrintTaskCard(
    taskId: String,
    modifier: Modifier = Modifier,
    printTaskManager: PrintTaskManager = koinInject(),
) {
    val tasks by printTaskManager.tasks.collectAsStateWithLifecycle()
    val task = tasks.find { it.record.taskId == taskId }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (task == null) {
                Text(
                    text = stringResource(R.string.print_card_task_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }
            PrintTaskCardBody(task = task, printTaskManager = printTaskManager)
        }
    }
}

@Composable
private fun PrintTaskCardBody(
    task: PrintTask,
    printTaskManager: PrintTaskManager,
) {
    val record = task.record
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // 标题行
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Rounded.Print,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = record.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        PrintStatusBadge(status = record.status)
    }

    Spacer(modifier = Modifier.height(6.dp))
    // 目标打印机与说明
    val deviceText = record.deviceName?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.print_card_no_device)
    Text(
        text = deviceText,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    // 打印预览（确认前检查实际排版）
    val previewPath = task.previewFile?.absolutePath
    if (previewPath != null && !record.status.isTerminal) {
        Spacer(modifier = Modifier.height(8.dp))
        ZoomableAsyncImage(
            model = "file://$previewPath",
            contentDescription = stringResource(R.string.print_card_preview),
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
        )
    }

    // 发送进度
    if (record.status == PrintTaskStatus.SENDING && record.rowCount > 0) {
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = {
                (record.sentRows.toFloat() / record.rowCount.toFloat()).coerceIn(0f, 1f)
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.print_card_progress_fmt, record.sentRows, record.rowCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    // 错误 / 警告信息
    record.errorMessage?.let { message ->
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = if (record.status == PrintTaskStatus.COMPLETED) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            }
        )
    }
    if (record.status == PrintTaskStatus.COMPLETED) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.print_card_completed_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    // 操作区
    val canConfirm = record.status == PrintTaskStatus.AWAITING_CONFIRMATION
    val canCancel = record.status == PrintTaskStatus.CONNECTING || record.status == PrintTaskStatus.SENDING
    val canRetry = record.status == PrintTaskStatus.FAILED && record.sentRows == 0
    if (canConfirm || canCancel || canRetry) {
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (canConfirm) {
                Button(
                    onClick = { scope.launch { printTaskManager.confirm(record.taskId) } },
                    enabled = !task.cancelRequested
                ) {
                    Text(stringResource(R.string.print_card_confirm))
                }
                TextButton(
                    onClick = { scope.launch { printTaskManager.cancel(record.taskId) } },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Text(stringResource(R.string.print_card_cancel))
                }
            }
            if (canCancel) {
                TextButton(
                    onClick = { scope.launch { printTaskManager.cancel(record.taskId) } },
                    enabled = !task.cancelRequested,
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Text(
                        if (task.cancelRequested) {
                            stringResource(R.string.print_card_cancelling)
                        } else {
                            stringResource(R.string.print_card_cancel_print)
                        }
                    )
                }
            }
            if (canRetry) {
                Button(
                    onClick = { scope.launch { printTaskManager.retry(record.taskId) } }
                ) {
                    Text(stringResource(R.string.print_card_retry))
                }
            }
        }
        if (canCancel && task.cancelRequested) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.print_card_cancel_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PrintStatusBadge(status: PrintTaskStatus) {
    val (label, container, content) = when (status) {
        PrintTaskStatus.PREPARING -> Triple(
            stringResource(R.string.print_card_status_preparing),
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer
        )
        PrintTaskStatus.AWAITING_CONFIRMATION -> Triple(
            stringResource(R.string.print_card_status_awaiting),
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        PrintTaskStatus.CONNECTING -> Triple(
            stringResource(R.string.print_card_status_connecting),
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer
        )
        PrintTaskStatus.SENDING -> Triple(
            stringResource(R.string.print_card_status_sending),
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer
        )
        PrintTaskStatus.COMPLETED -> Triple(
            stringResource(R.string.print_card_status_completed),
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        PrintTaskStatus.FAILED -> Triple(
            stringResource(R.string.print_card_status_failed),
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer
        )
        PrintTaskStatus.CANCELLED -> Triple(
            stringResource(R.string.print_card_status_cancelled),
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
        PrintTaskStatus.OUTCOME_UNKNOWN -> Triple(
            stringResource(R.string.print_card_status_unknown),
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer
        )
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = container,
        contentColor = content
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}
