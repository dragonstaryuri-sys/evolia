package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.core.data.db.entity.AgentTaskEntity
import me.rerere.rikkahub.ui.components.nav.BackButton
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.text.SimpleDateFormat
import java.util.*
import me.rerere.rikkahub.common.JsonInstant
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantAgentTaskPage(assistantId: String) {
    val vm: AssistantDetailVM = koinViewModel(parameters = { parametersOf(assistantId) })
    // 使用 collectAsStateWithLifecycle 监听任务列表
    val tasks by vm.agentTasks.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.agent_task_manager)) },
                navigationIcon = { BackButton() }
            )
        }
    ) { padding ->
        if (tasks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.agent_task_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(tasks, key = { it.id }) { task ->
                    AgentTaskItem(task = task, onDelete = { vm.deleteAgentTask(it) })
                }
            }
        }
    }
}

@Composable
private fun AgentTaskItem(task: AgentTaskEntity, onDelete: (AgentTaskEntity) -> Unit) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // 安全解析 task_data 以获取自定义名称
    val taskName = remember(task.taskData) {
        runCatching {
            JsonInstant.parseToJsonElement(task.taskData)
                .jsonObject["task_name"]
                ?.jsonPrimitive
                ?.contentOrNull
        }.getOrNull()
    }

    val icon = when (task.taskType) {
        "EMAIL" -> Icons.Rounded.Email
        "NOTIFICATION" -> Icons.Rounded.Notifications
        "DIARY" -> Icons.Rounded.Book
        else -> Icons.Rounded.Notifications
    }

    val typeText = when (task.taskType) {
        "EMAIL" -> stringResource(R.string.agent_task_type_email)
        "NOTIFICATION" -> stringResource(R.string.agent_task_type_notification)
        "DIARY" -> stringResource(R.string.agent_task_type_diary)
        else -> task.taskType
    }

    // 优先显示自定义名称，如果没有则显示任务类型
    val displayTitle = if (!taskName.isNullOrBlank()) taskName else typeText

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.agent_task_scheduled_at, dateFormat.format(Date(task.scheduledTime))),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (task.isExecuted) stringResource(R.string.agent_task_executed) else stringResource(R.string.agent_task_pending),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (task.isExecuted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                )
            }

            IconButton(onClick = { showDeleteDialog = true }) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.agent_task_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(task)
                    showDeleteDialog = false
                }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
