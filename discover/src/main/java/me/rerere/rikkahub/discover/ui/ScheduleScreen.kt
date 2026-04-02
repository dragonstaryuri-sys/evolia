package me.rerere.rikkahub.discover.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.discover.R
import me.rerere.rikkahub.core.data.db.entity.ScheduleEntity
import me.rerere.rikkahub.common.ui.components.ExpandableCard
import me.rerere.rikkahub.common.PowerUtils
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    onBack: () -> Unit,
    viewModel: ScheduleViewModel = koinViewModel()
) {
    val pendingSchedules by viewModel.allPendingSchedules.collectAsState()
    val completedSchedules by viewModel.allCompletedSchedules.collectAsState()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var editingSchedule by remember { mutableStateOf<ScheduleEntity?>(null) }
    var showEditDialog by remember { mutableStateOf(false) }

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.discover_schedule_status_pending),
        stringResource(R.string.discover_schedule_status_completed)
    )

    // 检查电池优化状态
    var isIgnoringBatteryOptimizations by remember {
        mutableStateOf(PowerUtils.isIgnoringBatteryOptimizations(context))
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                LargeTopAppBar(
                    title = { Text(stringResource(R.string.discover_schedule_all_tasks)) },
                    navigationIcon = {
                        IconButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onBack()
                        }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                        }
                    },
                    scrollBehavior = scrollBehavior
                )
                SecondaryTabRow(
                    selectedTabIndex = selectedTabIndex,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    divider = {}
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedTabIndex = index
                            },
                            text = { Text(title) }
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    editingSchedule = null
                    showEditDialog = true
                },
                shape = CircleShape
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
            }
        }
    ) { padding ->
        val displaySchedules = if (selectedTabIndex == 0) pendingSchedules else completedSchedules

        val groupedSchedules = remember(displaySchedules, selectedTabIndex) {
            if (selectedTabIndex == 0) {
                displaySchedules.groupBy { it.difficulty }.mapValues { (_, list) ->
                    list.sortedWith(
                        compareByDescending<ScheduleEntity> { it.priority }
                            .thenByDescending { it.urgency }
                    )
                }
            } else {
                emptyMap()
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 优化建议卡片
            if (selectedTabIndex == 0 && !isIgnoringBatteryOptimizations) {
                item {
                    OptimizationCard(
                        onAction = {
                            PowerUtils.requestIgnoreBatteryOptimizations(context)
                        }
                    )
                }
            }

            if (displaySchedules.isEmpty()) {
                item {
                    Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (selectedTabIndex == 0)
                                stringResource(R.string.discover_schedule_no_tasks)
                            else
                                stringResource(R.string.discover_schedule_no_completed_tasks),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (selectedTabIndex == 0) {
                listOf(0, 1, 2).forEach { diff ->
                    val items = groupedSchedules[diff] ?: emptyList()
                    if (items.isNotEmpty()) {
                        item(key = "diff_$diff") {
                            DifficultyGroup(
                                difficulty = diff,
                                schedules = items,
                                onToggle = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    viewModel.toggleComplete(it)
                                },
                                onClick = {
                                    editingSchedule = it
                                    showEditDialog = true
                                },
                                onDelete = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.deleteSchedule(it.id)
                                }
                            )
                        }
                    }
                }
            } else {
                items(displaySchedules, key = { it.id }) { schedule ->
                    ScheduleItem(
                        schedule = schedule,
                        onToggle = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            viewModel.toggleComplete(schedule)
                        },
                        onClick = {
                            editingSchedule = schedule
                            showEditDialog = true
                        },
                        onDelete = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.deleteSchedule(schedule.id)
                        }
                    )
                }
            }
        }
    }

    if (showEditDialog) {
        ScheduleEditDialog(
            initialSchedule = editingSchedule,
            onDismiss = { showEditDialog = false },
            onSave = { schedule ->
                viewModel.saveSchedule(schedule)
                showEditDialog = false
            }
        )
    }

    // 当从设置返回时刷新状态
    LaunchedEffect(Unit) {
        isIgnoringBatteryOptimizations = PowerUtils.isIgnoringBatteryOptimizations(context)
    }
}

@Composable
private fun OptimizationCard(onAction: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        ),
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.auto_task_optimization_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.auto_task_optimization_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    text = stringResource(R.string.auto_task_optimization_action),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
private fun DifficultyGroup(
    difficulty: Int,
    schedules: List<ScheduleEntity>,
    onToggle: (ScheduleEntity) -> Unit,
    onClick: (ScheduleEntity) -> Unit,
    onDelete: (ScheduleEntity) -> Unit
) {
    val title = when(difficulty) {
        2 -> stringResource(R.string.schedule_difficulty_2)
        1 -> stringResource(R.string.schedule_difficulty_1)
        else -> stringResource(R.string.schedule_difficulty_0)
    }

    ExpandableCard(
        title = title,
        initiallyExpanded = true
    ) {
        schedules.forEach { schedule ->
            ScheduleItem(
                schedule = schedule,
                onToggle = { onToggle(schedule) },
                onClick = { onClick(schedule) },
                onDelete = { onDelete(schedule) }
            )
        }
    }
}

@Composable
private fun ScheduleItem(
    schedule: ScheduleEntity,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val itemBaseColor = when (schedule.priority) {
        2 -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        1 -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (schedule.isCompleted) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        } else {
            itemBaseColor
        },
        onClick = onClick,
        border = if (!schedule.isCompleted && schedule.priority == 2) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.2f))
        } else null
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = schedule.isCompleted,
                onCheckedChange = { onToggle() }
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = schedule.title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium,
                        textDecoration = if (schedule.isCompleted) androidx.compose.ui.text.style.TextDecoration.LineThrough else null
                    ),
                    color = if (schedule.isCompleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )

                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PropertyTag(
                        text = when(schedule.priority) {
                            2 -> stringResource(R.string.schedule_priority_2)
                            1 -> stringResource(R.string.schedule_priority_1)
                            else -> stringResource(R.string.schedule_priority_0)
                        },
                        containerColor = when(schedule.priority) {
                            2 -> MaterialTheme.colorScheme.error
                            1 -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.outline
                        }.copy(alpha = 0.12f),
                        contentColor = when(schedule.priority) {
                            2 -> MaterialTheme.colorScheme.error
                            1 -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    PropertyTag(
                        text = when(schedule.urgency) {
                            2 -> stringResource(R.string.schedule_urgency_2)
                            1 -> stringResource(R.string.schedule_urgency_1)
                            else -> stringResource(R.string.schedule_urgency_0)
                        },
                        containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
private fun PropertyTag(
    text: String,
    containerColor: Color,
    contentColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Surface(
        color = containerColor,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            ),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            color = contentColor
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleEditDialog(
    initialSchedule: ScheduleEntity?,
    onDismiss: () -> Unit,
    onSave: (ScheduleEntity) -> Unit
) {
    var title by remember { mutableStateOf(initialSchedule?.title ?: "") }
    // 保持 content 状态但不再 UI 中显示，用于静默保留历史数据
    val content by remember { mutableStateOf(initialSchedule?.content ?: "") }
    var priority by remember { mutableIntStateOf(initialSchedule?.priority ?: 1) }
    var urgency by remember { mutableIntStateOf(initialSchedule?.urgency ?: 1) }
    var difficulty by remember { mutableIntStateOf(initialSchedule?.difficulty ?: 1) }

    var startTime by remember { mutableLongStateOf(initialSchedule?.startTime ?: System.currentTimeMillis()) }
    var endTime by remember { mutableStateOf(initialSchedule?.endTime) }
    var reminderTime by remember { mutableStateOf(initialSchedule?.reminderTime) }

    val haptic = LocalHapticFeedback.current

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank()) {
                        val schedule = (initialSchedule ?: ScheduleEntity(
                            title = title,
                            startTime = startTime
                        )).copy(
                            title = title,
                            content = content, // 静默保存原有 content
                            priority = priority,
                            urgency = urgency,
                            difficulty = difficulty,
                            startTime = startTime,
                            endTime = endTime,
                            reminderTime = reminderTime,
                            updatedAt = System.currentTimeMillis()
                        )
                        onSave(schedule)
                    }
                },
                enabled = title.isNotBlank()
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Title
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.discover_schedule_input_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // 详细描述 (Content) 已移除，以节省空间并弃用该功能

                // Compact Property Selectors
                PropertySelector(
                    label = stringResource(R.string.schedule_priority),
                    value = priority,
                    onValueChange = { priority = it },
                    haptic = haptic,
                    labelProvider = {
                        when(it) {
                            2 -> stringResource(R.string.schedule_priority_2)
                            1 -> stringResource(R.string.schedule_priority_1)
                            else -> stringResource(R.string.schedule_priority_0)
                        }
                    }
                )

                PropertySelector(
                    label = stringResource(R.string.schedule_urgency),
                    value = urgency,
                    onValueChange = { urgency = it },
                    haptic = haptic,
                    labelProvider = {
                        when(it) {
                            2 -> stringResource(R.string.schedule_urgency_2)
                            1 -> stringResource(R.string.schedule_urgency_1)
                            else -> stringResource(R.string.schedule_urgency_0)
                        }
                    }
                )

                PropertySelector(
                    label = stringResource(R.string.schedule_difficulty),
                    value = difficulty,
                    onValueChange = { difficulty = it },
                    haptic = haptic,
                    labelProvider = {
                        when(it) {
                            2 -> stringResource(R.string.schedule_difficulty_2)
                            1 -> stringResource(R.string.schedule_difficulty_1)
                            else -> stringResource(R.string.schedule_difficulty_0)
                        }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                // Time Pickers
                TimeSelector(
                    label = stringResource(R.string.schedule_start_time),
                    time = startTime,
                    icon = Icons.Rounded.Timer,
                    onTimeSelected = { it?.let { startTime = it } }
                )

                TimeSelector(
                    label = stringResource(R.string.schedule_end_time),
                    time = endTime,
                    icon = Icons.Rounded.CalendarMonth,
                    onTimeSelected = { endTime = it },
                    allowClear = true
                )

                TimeSelector(
                    label = stringResource(R.string.schedule_reminder_time),
                    time = reminderTime,
                    icon = Icons.Rounded.Notifications,
                    onTimeSelected = { reminderTime = it },
                    allowClear = true
                )
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PropertySelector(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    labelProvider: @Composable (Int) -> String
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            listOf(0, 1, 2).forEach { index ->
                SegmentedButton(
                    selected = value == index,
                    onClick = {
                        if (value != index) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onValueChange(index)
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = 3),
                    label = {
                        Text(
                            text = labelProvider(index),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun TimeSelector(
    label: String,
    time: Long?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onTimeSelected: (Long?) -> Unit,
    allowClear: Boolean = false
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dateFormatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    fun showPickers() {
        val calendar = Calendar.getInstance().apply {
            time?.let { this.timeInMillis = it }
        }
        android.app.DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                calendar.set(Calendar.YEAR, year)
                calendar.set(Calendar.MONTH, month)
                calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)

                android.app.TimePickerDialog(
                    context,
                    { _, hourOfDay, minute ->
                        calendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                        calendar.set(Calendar.MINUTE, minute)
                        onTimeSelected(calendar.timeInMillis)
                    },
                    calendar.get(Calendar.HOUR_OF_DAY),
                    calendar.get(Calendar.MINUTE),
                    true
                ).show()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable { showPickers() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = time?.let { dateFormatter.format(Date(it)) } ?: stringResource(R.string.schedule_none),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (allowClear && time != null) {
            IconButton(onClick = { onTimeSelected(null) }) {
                Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}
