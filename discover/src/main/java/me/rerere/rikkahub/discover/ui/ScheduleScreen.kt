package me.rerere.rikkahub.discover.ui

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.discover.R
import me.rerere.rikkahub.core.data.db.entity.ScheduleEntity
import me.rerere.rikkahub.common.ui.components.ExpandableCard // 引用 common 里的组件
import org.koin.androidx.compose.koinViewModel

@Composable
fun ScheduleScreen(
    onBack: () -> Unit,
    viewModel: ScheduleViewModel = koinViewModel()
) {
    val pendingSchedules by viewModel.allPendingSchedules.collectAsState()
    val completedSchedules by viewModel.allCompletedSchedules.collectAsState()
    val haptic = LocalHapticFeedback.current

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.discover_schedule_status_pending),
        stringResource(R.string.discover_schedule_status_completed)
    )

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
                TabRow(
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
                    showAddDialog = true
                },
                shape = CircleShape
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
            }
        }
    ) { padding ->
        val displaySchedules = if (selectedTabIndex == 0) pendingSchedules else completedSchedules

        val groupedSchedules = remember(displaySchedules) {
            displaySchedules.groupBy { it.difficulty }.mapValues { (_, list) ->
                list.sortedWith(
                    compareByDescending<ScheduleEntity> { it.priority }
                        .thenByDescending { it.urgency }
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
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
            } else {
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
                                onDelete = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.deleteSchedule(it.id)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddScheduleDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { title, priority, urgency, difficulty ->
                viewModel.addSchedule(title, priority = priority, urgency = urgency, difficulty = difficulty)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun DifficultyGroup(
    difficulty: Int,
    schedules: List<ScheduleEntity>,
    onToggle: (ScheduleEntity) -> Unit,
    onDelete: (ScheduleEntity) -> Unit
) {
    val title = when(difficulty) {
        2 -> stringResource(R.string.schedule_difficulty_2)
        1 -> stringResource(R.string.schedule_difficulty_1)
        else -> stringResource(R.string.schedule_difficulty_0)
    }

    // 调用 common 模块中的折叠组件
    ExpandableCard(
        title = title,
        initiallyExpanded = true
    ) {
        schedules.forEach { schedule ->
            ScheduleItem(
                schedule = schedule,
                onToggle = { onToggle(schedule) },
                onDelete = { onDelete(schedule) }
            )
        }
    }
}

@Composable
private fun ScheduleItem(
    schedule: ScheduleEntity,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    val itemBaseColor = when (schedule.priority) {
        2 -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
        1 -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
        else -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.25f)
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (schedule.isCompleted) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        else itemBaseColor,
        onClick = onToggle
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
                    )
                )

                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
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
                            else -> MaterialTheme.colorScheme.tertiary
                        }.copy(alpha = 0.1f)
                    )
                    PropertyTag(
                        text = when(schedule.urgency) {
                            2 -> stringResource(R.string.schedule_urgency_2)
                            1 -> stringResource(R.string.schedule_urgency_1)
                            else -> stringResource(R.string.schedule_urgency_0)
                        },
                        containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
private fun PropertyTag(text: String, containerColor: Color) {
    Surface(
        color = containerColor,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun AddScheduleDialog(onDismiss: () -> Unit, onConfirm: (String, Int, Int, Int) -> Unit) {
    var title by remember { mutableStateOf("") }
    var priority by remember { mutableIntStateOf(1) }
    var urgency by remember { mutableIntStateOf(1) }
    var difficulty by remember { mutableIntStateOf(1) }
    val haptic = LocalHapticFeedback.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.discover_schedule_add_task)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = { Text(stringResource(R.string.discover_schedule_input_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )

                Column {
                    Text(
                        text = stringResource(R.string.schedule_priority) + ": " +
                            when(priority) { 2 -> stringResource(R.string.schedule_priority_2) 1 -> stringResource(R.string.schedule_priority_1) else -> stringResource(R.string.schedule_priority_0) },
                        style = MaterialTheme.typography.labelMedium
                    )
                    Slider(
                        value = priority.toFloat(),
                        onValueChange = {
                            if (it.toInt() != priority) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            priority = it.toInt()
                        },
                        valueRange = 0f..2f,
                        steps = 1
                    )
                }

                Column {
                    Text(
                        text = stringResource(R.string.schedule_urgency) + ": " +
                            when(urgency) { 2 -> stringResource(R.string.schedule_urgency_2) 1 -> stringResource(R.string.schedule_urgency_1) else -> stringResource(R.string.schedule_urgency_0) },
                        style = MaterialTheme.typography.labelMedium
                    )
                    Slider(
                        value = urgency.toFloat(),
                        onValueChange = {
                            if (it.toInt() != urgency) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            urgency = it.toInt()
                        },
                        valueRange = 0f..2f,
                        steps = 1
                    )
                }

                Column {
                    Text(
                        text = stringResource(R.string.schedule_difficulty) + ": " +
                            when(difficulty) { 2 -> stringResource(R.string.schedule_difficulty_2) 1 -> stringResource(R.string.schedule_difficulty_1) else -> stringResource(R.string.schedule_difficulty_0) },
                        style = MaterialTheme.typography.labelMedium
                    )
                    Slider(
                        value = difficulty.toFloat(),
                        onValueChange = {
                            if (it.toInt() != difficulty) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            difficulty = it.toInt()
                        },
                        valueRange = 0f..2f,
                        steps = 1
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { if (title.isNotBlank()) onConfirm(title, priority, urgency, difficulty) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onDismiss()
            }) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
