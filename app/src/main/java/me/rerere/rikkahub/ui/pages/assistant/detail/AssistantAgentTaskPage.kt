package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.ViewModule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.R
import me.rerere.rikkahub.core.data.db.entity.AgentTaskEntity
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.nav.BackButton
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import java.text.SimpleDateFormat
import java.util.*
import me.rerere.rikkahub.common.JsonInstant
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantAgentTaskPage(assistantId: String) {
    val vm: AssistantDetailVM = koinViewModel(parameters = { parametersOf(assistantId) })
    val tasks by vm.agentTasks.collectAsStateWithLifecycle()
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
    var showEditModelTask by remember { mutableStateOf<AgentTaskEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.agent_task_manager)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Rounded.Add, contentDescription = "Add")
                    }
                }
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
                    AgentTaskItem(
                        task = task,
                        onDelete = { vm.deleteAgentTask(task) },
                        onClick = { showEditModelTask = task }
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateTaskDialog(
            assistantId = assistantId,
            isMainAgent = assistant.isMain,
            onDismiss = { showCreateDialog = false },
            onConfirm = { task ->
                vm.addAgentTask(task)
                showCreateDialog = false
            }
        )
    }

    showEditModelTask?.let { task ->
        EditTaskDialog(
            task = task,
            onDismiss = { showEditModelTask = null },
            onSave = { model ->
                vm.updateAgentTaskModel(task, model)
                showEditModelTask = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateTaskDialog(
    assistantId: String,
    isMainAgent: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (AgentTaskEntity) -> Unit
) {
    var taskName by remember { mutableStateOf("") }
    var instruction by remember { mutableStateOf("") }
    var scheduledTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var repeatInterval by remember { mutableLongStateOf(0L) }
    var selectedModelId by remember { mutableStateOf<Uuid?>(null) }

    val settingsStore = koinInject<SettingsStore>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val repeatOptions = listOf(
        0L to stringResource(R.string.agent_task_repeat_never),
        86400000L to stringResource(R.string.agent_task_repeat_daily),
        604800000L to stringResource(R.string.agent_task_repeat_weekly),
        2592000000L to stringResource(R.string.agent_task_repeat_monthly),
        31536000000L to stringResource(R.string.agent_task_repeat_yearly)
    )
    var expandedRepeat by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.agent_task_add)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.agent_task_presets),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                // 添加 horizontalScroll 使其可以滑动
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val presets = mutableListOf(
                        Triple(stringResource(R.string.agent_task_preset_good_night), 86400000L, "提醒用户该睡觉了"),
                        Triple(stringResource(R.string.agent_task_preset_good_morning), 86400000L, "给用户发一个早安问候"),
                        Triple(stringResource(R.string.agent_task_preset_weekly_report), 604800000L, "总结用户本周作息情况")
                    )

                    // 仅为主智能体添加记忆整理预置
                    if (isMainAgent) {
                        presets.add(0, Triple(
                            stringResource(R.string.agent_task_preset_memory_consolidation),
                            86400000L,
                            stringResource(R.string.agent_task_instruction_memory_consolidation)
                        ))
                    }

                    presets.forEach { (name, interval, desc) ->
                        SuggestionChip(
                            onClick = {
                                taskName = name
                                repeatInterval = interval
                                instruction = desc
                            },
                            label = { Text(name, maxLines = 1) }
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                OutlinedTextField(
                    value = taskName,
                    onValueChange = { taskName = it },
                    label = { Text(stringResource(R.string.agent_task_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                )

                OutlinedCard(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Rounded.Event, contentDescription = null)
                        Column {
                            Text(stringResource(R.string.agent_task_scheduled_time), style = MaterialTheme.typography.labelSmall)
                            Text(dateFormat.format(Date(scheduledTime)), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                ExposedDropdownMenuBox(
                    expanded = expandedRepeat,
                    onExpandedChange = { expandedRepeat = it }
                ) {
                    OutlinedTextField(
                        value = repeatOptions.find { it.first == repeatInterval }?.second ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.agent_task_repeat_interval)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedRepeat) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium
                    )
                    ExposedDropdownMenu(
                        expanded = expandedRepeat,
                        onDismissRequest = { expandedRepeat = false }
                    ) {
                        repeatOptions.forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    repeatInterval = value
                                    expandedRepeat = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = instruction,
                    onValueChange = { instruction = it },
                    label = { Text(stringResource(R.string.agent_task_instruction)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    shape = MaterialTheme.shapes.medium
                )

                // 任务模型选择（非必选，为空时使用 assistant 当前对话模型）
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Rounded.ViewModule, contentDescription = null)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.agent_task_model),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            ModelSelector(
                                modelId = selectedModelId,
                                providers = settings.providers,
                                type = ModelType.CHAT,
                                allowClear = true,
                                onSelect = { model ->
                                    selectedModelId = if (model.id == Uuid.NIL) null else model.id
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = taskName.isNotBlank() && instruction.isNotBlank(),
                onClick = {
                    val taskData = buildJsonObject {
                        put("task_name", taskName)
                        put("instruction", instruction)
                    }.toString()
                    onConfirm(
                        AgentTaskEntity(
                            assistantId = assistantId,
                            taskType = "NOTIFICATION",
                            taskData = taskData,
                            scheduledTime = scheduledTime,
                            repeatInterval = repeatInterval,
                            model = selectedModelId?.toString()
                        )
                    )
                }
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = scheduledTime)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let {
                        val calendar = Calendar.getInstance().apply { timeInMillis = scheduledTime }
                        val hour = calendar.get(Calendar.HOUR_OF_DAY)
                        val minute = calendar.get(Calendar.MINUTE)

                        val newCalendar = Calendar.getInstance().apply {
                            timeInMillis = it
                            set(Calendar.HOUR_OF_DAY, hour)
                            set(Calendar.MINUTE, minute)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        scheduledTime = newCalendar.timeInMillis
                    }
                    showDatePicker = false
                    showTimePicker = true
                }) { Text(stringResource(R.string.confirm)) }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        val calendar = Calendar.getInstance().apply { timeInMillis = scheduledTime }
        val timePickerState = rememberTimePickerState(
            initialHour = calendar.get(Calendar.HOUR_OF_DAY),
            initialMinute = calendar.get(Calendar.MINUTE)
        )
        Dialog(onDismissRequest = { showTimePicker = false }) {
            Card(shape = MaterialTheme.shapes.extraLarge) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(stringResource(R.string.diary_select_time), style = MaterialTheme.typography.titleMedium)
                    TimePicker(state = timePickerState)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showTimePicker = false }) { Text(stringResource(R.string.cancel)) }
                        TextButton(onClick = {
                            val newCalendar = Calendar.getInstance().apply {
                                timeInMillis = scheduledTime
                                set(Calendar.HOUR_OF_DAY, timePickerState.hour)
                                set(Calendar.MINUTE, timePickerState.minute)
                            }
                            scheduledTime = newCalendar.timeInMillis
                            showTimePicker = false
                        }) { Text(stringResource(R.string.confirm)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentTaskItem(
    task: AgentTaskEntity,
    onDelete: (AgentTaskEntity) -> Unit,
    onClick: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    var showDeleteDialog by remember { mutableStateOf(false) }

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

    val displayTitle = if (!taskName.isNullOrBlank()) taskName else typeText

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
                if (task.repeatInterval > 0) {
                    val repeatText = when (task.repeatInterval) {
                        86400000L -> stringResource(R.string.agent_task_repeat_daily)
                        604800000L -> stringResource(R.string.agent_task_repeat_weekly)
                        2592000000L -> stringResource(R.string.agent_task_repeat_monthly)
                        31536000000L -> stringResource(R.string.agent_task_repeat_yearly)
                        else -> "${task.repeatInterval / 60000} min"
                    }
                    Text(
                        text = stringResource(R.string.agent_task_repeat_interval) + ": " + repeatText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
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

/**
 * 编辑已有定时任务的弹窗：展示任务全部信息（场景名称、执行时间、重复频率、描述），
 * 仅允许修改「任务模型」字段，其他字段只读。
 * 模型为空时使用 assistant 当前对话模型。
 */
@Composable
private fun EditTaskDialog(
    task: AgentTaskEntity,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit
) {
    val settingsStore = koinInject<SettingsStore>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    val taskData = remember(task.taskData) {
        runCatching { JsonInstant.parseToJsonElement(task.taskData).jsonObject }.getOrNull()
    }
    val taskName = remember(taskData) {
        taskData?.get("task_name")?.jsonPrimitive?.contentOrNull
    }
    val instruction = remember(taskData) {
        taskData?.get("instruction")?.jsonPrimitive?.contentOrNull
    }

    val initialModelId = remember(task.model) {
        task.model
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
    }
    var selectedModelId by remember { mutableStateOf(initialModelId) }

    // 重复频率转小时展示（仅显示，不改存储）
    val hourUnit = stringResource(R.string.agent_task_repeat_hour_unit)
    val neverText = stringResource(R.string.agent_task_repeat_never)
    val repeatText = remember(task.repeatInterval, hourUnit, neverText) {
        if (task.repeatInterval <= 0L) {
            neverText
        } else {
            val hours = task.repeatInterval / 3_600_000.0
            if (hours % 1.0 == 0.0) {
                "${hours.toLong()} $hourUnit"
            } else {
                String.format(Locale.getDefault(), "%.1f %s", hours, hourUnit)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.agent_task_edit_title),
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
                // 场景名称（只读）
                ReadOnlyTaskField(
                    icon = Icons.Rounded.Book,
                    label = stringResource(R.string.agent_task_name),
                    value = taskName ?: ""
                )

                // 首次任务执行时间（只读）
                ReadOnlyTaskField(
                    icon = Icons.Rounded.Event,
                    label = stringResource(R.string.agent_task_scheduled_time),
                    value = dateFormat.format(Date(task.scheduledTime))
                )

                // 重复频率（只读，小时）
                ReadOnlyTaskField(
                    icon = Icons.Rounded.Event,
                    label = stringResource(R.string.agent_task_repeat_interval),
                    value = repeatText
                )

                // 任务描述（只读）
                ReadOnlyTaskField(
                    icon = Icons.Rounded.Book,
                    label = stringResource(R.string.agent_task_instruction),
                    value = instruction ?: ""
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                // 任务模型（可编辑）—— 用 OutlinedCard 卡片样式，与新建页一致
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Rounded.ViewModule, contentDescription = null)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.agent_task_model),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            ModelSelector(
                                modelId = selectedModelId,
                                providers = settings.providers,
                                type = ModelType.CHAT,
                                allowClear = true,
                                onSelect = { model ->
                                    selectedModelId = if (model.id == Uuid.NIL) null else model.id
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(selectedModelId?.toString()) }) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * 只读任务信息行：左图标 + 右列（标题 + 值），灰色置灰样式，
 * 用于编辑弹窗展示不可修改的字段。
 */
@Composable
private fun ReadOnlyTaskField(
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
