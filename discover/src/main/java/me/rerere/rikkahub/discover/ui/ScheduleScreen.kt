package me.rerere.rikkahub.discover.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreTime
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.discover.R
import me.rerere.rikkahub.core.data.db.entity.ScheduleEntity
import me.rerere.rikkahub.common.ui.components.ExpandableCard
import me.rerere.rikkahub.common.ui.theme.LocalDarkTheme
import me.rerere.rikkahub.common.PowerUtils
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.*

// 右滑完成背景的品牌绿（深浅色成对，取值与 app 主题 extendColors.green2/green8 同源）
private val CompleteSwipeBgLight = Color(175, 240, 181)
private val CompleteSwipeBgDark = Color(4, 102, 37)
private val CompleteSwipeContentLight = Color(0, 128, 38)
private val CompleteSwipeContentDark = Color(126, 225, 139)

/**
 * 待办视图（嵌入日程规划页）。
 *
 * 保留原有难度分组（简单 / 一般 / 不简单）与待处理 / 已完成 Tab，
 * 在此基础上为 user 视角增加排程状态筛选（已排程 / 未排程 / 全部，默认已排程）；
 * assistant 视角不显示筛选项，直接展示其全部待办。
 * 分组内部按 截止日期队列 → 重要度 → 难度 → 创建时间 排序。
 *
 * @param onArrangeTime 待办卡片"安排时间"入口，由宿主页面打开日程表单
 */
@Composable
fun ScheduleTodoContent(
    viewModel: ScheduleViewModel,
    onArrangeTime: (ScheduleEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val pendingSchedules by viewModel.filteredPendingSchedules.collectAsState()
    val completedSchedules by viewModel.allCompletedSchedules.collectAsState()
    val scheduleFilter by viewModel.scheduleFilter.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    // 保持 scheduledTaskIds 处于活跃收集状态，供删除前的联动确认判断使用
    val scheduledTaskIds by viewModel.scheduledTaskIds.collectAsState()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    var editingSchedule by remember { mutableStateOf<ScheduleEntity?>(null) }
    var showEditDialog by remember { mutableStateOf(false) }

    // 删除待办前的联动确认：待办存在关联日程时提示日程将一并删除
    var pendingDelete by remember { mutableStateOf<ScheduleEntity?>(null) }
    val requestDelete: (ScheduleEntity) -> Unit = { task ->
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val hasEvents = task.id in scheduledTaskIds
        if (hasEvents) {
            pendingDelete = task
        } else {
            viewModel.deleteSchedule(task.id)
        }
    }

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.discover_schedule_status_pending),
        stringResource(R.string.discover_schedule_status_completed)
    )

    // 检查电池优化状态
    var isIgnoringBatteryOptimizations by remember {
        mutableStateOf(PowerUtils.isIgnoringBatteryOptimizations(context))
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
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

            // 排程状态筛选条（仅 user 视角的待处理 Tab；assistant 视角直接展示全部待办，不显示筛选项）
            if (selectedTabIndex == 0) {
                item(key = "schedule_filter") {
                    Column {
                        if (selectedCategory == "user") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val filterOptions = listOf(
                                    ScheduleStatusFilter.SCHEDULED to stringResource(R.string.schedule_filter_scheduled),
                                    ScheduleStatusFilter.UNSCHEDULED to stringResource(R.string.schedule_filter_unscheduled),
                                    ScheduleStatusFilter.ALL to stringResource(R.string.schedule_filter_all)
                                )
                                filterOptions.forEach { (option, label) ->
                                    FilterChip(
                                        selected = scheduleFilter == option,
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            viewModel.setScheduleFilter(option)
                                        },
                                        label = { Text(label) }
                                    )
                                }
                            }
                        }
                        // 滑动操作提示：让用户知道卡片支持左滑/右滑
                        Text(
                            text = stringResource(R.string.schedule_swipe_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, top = 6.dp)
                        )
                    }
                }
            }

            val displaySchedules = if (selectedTabIndex == 0) pendingSchedules else completedSchedules

            if (displaySchedules.isEmpty()) {
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(320.dp),
                        contentAlignment = Alignment.Center
                    ) {
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
                // 难度分组保留；列表已按新排序规则排列，组内保持该顺序
                listOf(0, 1, 2).forEach { diff ->
                    val items = displaySchedules.filter { it.difficulty == diff }
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
                                onSchedule = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onArrangeTime(it)
                                },
                                onDelete = {
                                    requestDelete(it)
                                }
                            )
                        }
                    }
                }
            } else {
                items(displaySchedules, key = { it.id }) { schedule ->
                    SwipeableScheduleItem(
                        schedule = schedule,
                        onToggle = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            viewModel.toggleComplete(schedule)
                        },
                        onClick = {
                            editingSchedule = schedule
                            showEditDialog = true
                        },
                        onSchedule = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onArrangeTime(schedule)
                        },
                        onDelete = {
                            requestDelete(schedule)
                        }
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                editingSchedule = null
                showEditDialog = true
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            shape = CircleShape
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null)
        }
    }

    if (showEditDialog) {
        ScheduleEditDialog(
            initialSchedule = editingSchedule,
            defaultCategory = viewModel.selectedCategory.value,
            onDismiss = { showEditDialog = false },
            onSave = { schedule ->
                viewModel.saveSchedule(schedule)
                showEditDialog = false
            }
        )
    }

    // 删除待办联动确认
    pendingDelete?.let { task ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            title = { Text(stringResource(R.string.schedule_todo_delete_with_events_title)) },
            text = { Text(stringResource(R.string.schedule_todo_delete_with_events_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDelete = null
                        viewModel.deleteSchedule(task.id)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(stringResource(R.string.schedule_todo_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingDelete = null },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
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
    onSchedule: (ScheduleEntity) -> Unit,
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
            key(schedule.id) {
                SwipeableScheduleItem(
                    schedule = schedule,
                    onToggle = { onToggle(schedule) },
                    onClick = { onClick(schedule) },
                    onSchedule = { onSchedule(schedule) },
                    onDelete = { onDelete(schedule) }
                )
            }
        }
    }
}

/**
 * 可滑动待办卡片：右滑切换完成状态，左滑删除（删除有关联日程时由宿主弹出确认），
 * 释放复选框与删除按钮占用的空间，把更多宽度留给标题。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableScheduleItem(
    schedule: ScheduleEntity,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    onSchedule: () -> Unit,
    onDelete: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onToggle()
                    // 不真正移除卡片：切换状态后回弹，由数据层驱动刷新
                    false
                }

                SwipeToDismissBoxValue.EndToStart -> {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDelete()
                    false
                }

                else -> false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val target = dismissState.targetValue
            if (target == SwipeToDismissBoxValue.Settled) {
                Box(Modifier.fillMaxSize())
            } else {
                val isCompleteAction = target == SwipeToDismissBoxValue.StartToEnd
                // 右滑完成使用品牌绿（取自主题 extendColors.green2/green8 的深浅色成对值，
                // discover 模块无法直接访问 extendColors，经 LocalDarkTheme 选择）
                val darkTheme = LocalDarkTheme.current
                val backgroundColor = if (isCompleteAction) {
                    if (darkTheme) CompleteSwipeBgDark else CompleteSwipeBgLight
                } else {
                    MaterialTheme.colorScheme.errorContainer
                }
                val contentColor = if (isCompleteAction) {
                    if (darkTheme) CompleteSwipeContentDark else CompleteSwipeContentLight
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                }
                val alignment = if (isCompleteAction) Alignment.CenterStart else Alignment.CenterEnd
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(MaterialTheme.shapes.large)
                        .background(backgroundColor),
                    contentAlignment = alignment
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = if (isCompleteAction) Icons.Rounded.Check else Icons.Rounded.Delete,
                            contentDescription = null,
                            tint = contentColor
                        )
                        Text(
                            text = stringResource(
                                if (isCompleteAction) R.string.schedule_swipe_complete
                                else R.string.schedule_swipe_delete
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = contentColor
                        )
                    }
                }
            }
        }
    ) {
        ScheduleItemCard(
            schedule = schedule,
            onClick = onClick,
            onSchedule = onSchedule
        )
    }
}

/**
 * 待办卡片本体：无复选框与删除按钮，完成状态以勾选图标 + 删除线表达；
 * "安排时间"入口仅对 user 类待办显示（assistant 待办不排程）。
 */
@Composable
private fun ScheduleItemCard(
    schedule: ScheduleEntity,
    onClick: () -> Unit,
    onSchedule: () -> Unit
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
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (schedule.isCompleted) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = stringResource(R.string.discover_schedule_completed),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
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
            // 安排时间入口：仅 user 类待办显示，已有日程的待办仍可继续添加日程
            if (schedule.category == "user") {
                IconButton(onClick = onSchedule) {
                    Icon(
                        Icons.Rounded.MoreTime,
                        contentDescription = stringResource(R.string.schedule_arrange_time),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    )
                }
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
    defaultCategory: String = "user",
    onDismiss: () -> Unit,
    onSave: (ScheduleEntity) -> Unit
) {
    var title by remember { mutableStateOf(initialSchedule?.title ?: "") }
    val content by remember { mutableStateOf(initialSchedule?.content ?: "") }
    var priority by remember { mutableIntStateOf(initialSchedule?.priority ?: 1) }
    var urgency by remember { mutableIntStateOf(initialSchedule?.urgency ?: 1) }
    var difficulty by remember { mutableIntStateOf(initialSchedule?.difficulty ?: 1) }
    var category by remember { mutableStateOf(initialSchedule?.category ?: defaultCategory) }

    var startTime by remember { mutableLongStateOf(initialSchedule?.startTime ?: System.currentTimeMillis()) }
    var endTime by remember { mutableStateOf(initialSchedule?.endTime) }

    val haptic = LocalHapticFeedback.current

    // 统一处理保存逻辑
    val handleSave = {
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
                category = category,
                startTime = startTime,
                endTime = endTime,
                updatedAt = System.currentTimeMillis()
            )
            onSave(schedule)
        } else {
            // 如果标题为空（可能是误触添加），则直接关闭
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = handleSave, // 点击外部或物理返回时触发自动保存
        confirmButton = {
            Button(
                onClick = handleSave,
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

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.schedule_category),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val categories = listOf("user", "assistant")
                        categories.forEachIndexed { index, cat ->
                            SegmentedButton(
                                selected = category == cat,
                                onClick = {
                                    if (category != cat) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        category = cat
                                    }
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                                label = {
                                    Text(
                                        text = if (cat == "user")
                                            stringResource(R.string.schedule_category_user)
                                        else
                                            stringResource(R.string.schedule_category_assistant),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            )
                        }
                    }
                }
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
