package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.R
import me.rerere.rikkahub.common.JsonInstant
import me.rerere.rikkahub.core.data.db.entity.AgentMonitorTaskEntity
import me.rerere.rikkahub.ui.components.nav.BackButton
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantAgentMonitorPage(assistantId: String) {
    val vm: AssistantDetailVM = koinViewModel(parameters = { parametersOf(assistantId) })
    val monitors by vm.monitorTasks.collectAsStateWithLifecycle()

    var detailMonitor by remember { mutableStateOf<AgentMonitorTaskEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.agent_monitor_manager)) },
                navigationIcon = { BackButton() }
            )
        }
    ) { padding ->
        if (monitors.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.agent_monitor_empty),
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
                items(monitors, key = { it.id }) { monitor ->
                    AgentMonitorItem(
                        monitor = monitor,
                        onClick = { detailMonitor = monitor },
                        onDelete = { vm.deleteMonitorTask(it) }
                    )
                }
            }
        }
    }

    detailMonitor?.let { monitor ->
        AgentMonitorDetailDialog(
            monitor = monitor,
            onDismiss = { detailMonitor = null }
        )
    }
}

@Composable
private fun AgentMonitorItem(
    monitor: AgentMonitorTaskEntity,
    onClick: () -> Unit,
    onDelete: (AgentMonitorTaskEntity) -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.MonitorHeart,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = monitor.monitorName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(
                        R.string.agent_monitor_created_at,
                        dateFormat.format(Date(monitor.createdAt))
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
            text = { Text(stringResource(R.string.agent_monitor_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(monitor)
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

/**
 * 监视器详情弹窗（只读）：仅展示监视器名称与监控时间段（取自 conditions 中的 start/end），
 * 不允许编辑，样式参考定时任务管理的只读字段。
 */
@Composable
private fun AgentMonitorDetailDialog(
    monitor: AgentMonitorTaskEntity,
    onDismiss: () -> Unit
) {
    val timePeriods = remember(monitor.conditions) {
        parseMonitorTimePeriods(monitor.conditions)
    }
    val timeText = if (timePeriods.isEmpty()) {
        stringResource(R.string.agent_monitor_time_unset)
    } else {
        timePeriods.joinToString("\n")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.agent_monitor_detail_title),
                textAlign = TextAlign.Start
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ReadOnlyMonitorField(
                    icon = Icons.Rounded.MonitorHeart,
                    label = stringResource(R.string.agent_monitor_name_label),
                    value = monitor.monitorName
                )
                ReadOnlyMonitorField(
                    icon = Icons.Rounded.Schedule,
                    label = stringResource(R.string.agent_monitor_time_label),
                    value = timeText
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.confirm))
            }
        }
    )
}

/**
 * 只读信息行：左图标 + 右列（标题 + 值），灰色置灰样式。
 */
@Composable
private fun ReadOnlyMonitorField(
    icon: ImageVector,
    label: String,
    value: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 从 conditions JSON 中提取监控时间段文本，如 ["12:00 - 17:00", "20:00 - 23:00"]。
 * 兼容 time_periods（数组/单对象）、time_range 以及顶层直接写 start/end 的写法；
 * 未找到任何时间段时返回空列表。
 */
private fun parseMonitorTimePeriods(conditions: String): List<String> {
    val obj = runCatching {
        JsonInstant.parseToJsonElement(conditions) as? JsonObject
    }.getOrNull() ?: return emptyList()

    val periods = mutableListOf<String>()

    fun collect(element: Any?) {
        when (element) {
            is JsonArray -> element.forEach { collect(it) }
            is JsonObject -> {
                val start = (element["start"] as? JsonPrimitive)?.contentOrNull
                val end = (element["end"] as? JsonPrimitive)?.contentOrNull
                if (!start.isNullOrBlank() || !end.isNullOrBlank()) {
                    periods.add("${start ?: ""} - ${end ?: ""}")
                }
            }
            else -> Unit
        }
    }

    collect(obj["time_periods"])
    collect(obj["time_range"])

    val flatStart = (obj["start"] as? JsonPrimitive)?.contentOrNull
    val flatEnd = (obj["end"] as? JsonPrimitive)?.contentOrNull
    if (!flatStart.isNullOrBlank() || !flatEnd.isNullOrBlank()) {
        periods.add("${flatStart ?: ""} - ${flatEnd ?: ""}")
    }

    return periods
}
