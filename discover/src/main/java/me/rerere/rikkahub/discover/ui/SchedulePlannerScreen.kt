package me.rerere.rikkahub.discover.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.*
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.core.data.db.entity.ScheduleEntity
import me.rerere.rikkahub.discover.R
import me.rerere.rikkahub.discover.repo.ScheduleEventWithTask
import org.koin.androidx.compose.koinViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * 日程规划页（发现页日程入口直达）。
 *
 * 默认展示所选日期的全天时间轴（日程视图），可切换到待办视图；
 * 顶部日期可点开日历选择器查看过去 / 当天 / 未来任意日期。
 * AI 自动排程本期仅保留占位入口（禁用状态），不实现自动排程逻辑。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulePlannerScreen(
    onBack: () -> Unit,
    viewModel: SchedulePlannerViewModel = koinViewModel(),
    todoViewModel: ScheduleViewModel = koinViewModel()
) {
    val viewMode by viewModel.viewMode.collectAsState()
    val selectedDate by viewModel.selectedDate.collectAsState()
    val dayEvents by viewModel.eventsForDate.collectAsState()
    val selectedCategory by todoViewModel.selectedCategory.collectAsState()
    val haptic = LocalHapticFeedback.current

    // 按日期缓存各天已加载的日程快照：翻页动画期间，退场页保留其当日数据
    val eventsCache = remember { mutableStateMapOf<LocalDate, List<ScheduleEventWithTask>>() }
    LaunchedEffect(dayEvents) {
        eventsCache[dayEvents.date] = dayEvents.events
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showDatePicker by remember { mutableStateOf(false) }
    var formRequest by remember { mutableStateOf<EventFormRequest?>(null) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                LargeTopAppBar(
                    title = {
                        val dateLabel = remember(selectedDate) { formatDateLabel(selectedDate) }
                        Text(
                            text = dateLabel,
                            modifier = Modifier.clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                showDatePicker = true
                            }
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onBack()
                        }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                        }
                    },
                    actions = {
                        // 分类切换仅在待办视图有意义（时间轴视图的日程均为 user 类）
                        if (viewMode == PlannerView.TODO) {
                            IconButton(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val next = if (selectedCategory == "user") "assistant" else "user"
                                todoViewModel.setCategory(next)
                            }) {
                                Icon(
                                    imageVector = if (selectedCategory == "user") Icons.Rounded.Person else Icons.Rounded.SmartToy,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior
                )
                // 待办视图 / 日程视图切换
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    val timelineLabel = stringResource(R.string.schedule_planner_view_timeline)
                    val todoLabel = stringResource(R.string.schedule_planner_view_todo)
                    SegmentedButton(
                        selected = viewMode == PlannerView.TIMELINE,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            viewModel.setViewMode(PlannerView.TIMELINE)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        label = { Text(timelineLabel) }
                    )
                    SegmentedButton(
                        selected = viewMode == PlannerView.TODO,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            viewModel.setViewMode(PlannerView.TODO)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        label = { Text(todoLabel) }
                    )
                }
            }
        },
        floatingActionButton = {
            if (viewMode == PlannerView.TIMELINE) {
                FloatingActionButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        formRequest = EventFormRequest.newAt(selectedDate, defaultStartMinute())
                    },
                    shape = CircleShape
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                }
            }
        }
    ) { padding ->
        when (viewMode) {
            PlannerView.TIMELINE -> {
                // 翻页动画：按日程数据实际到达的日期触发，方向与切日方向一致
                // （共享轴模式：新页从滑动方向滑入，旧页向反方向位移 1/3 并淡出）
                AnimatedContent(
                    targetState = dayEvents.date,
                    transitionSpec = {
                        val forward = targetState.isAfter(initialState)
                        val moveSpec = tween<IntOffset>(280, easing = FastOutSlowInEasing)
                        val fadeSpec = tween<Float>(280, easing = FastOutSlowInEasing)
                        if (forward) {
                            (slideInHorizontally(moveSpec) { it } + fadeIn(fadeSpec)) togetherWith
                                (slideOutHorizontally(moveSpec) { -it / 3 } + fadeOut(fadeSpec))
                        } else {
                            (slideInHorizontally(moveSpec) { -it } + fadeIn(fadeSpec)) togetherWith
                                (slideOutHorizontally(moveSpec) { it / 3 } + fadeOut(fadeSpec))
                        }
                    },
                    label = "dayPage"
                ) { animatedDate ->
                    TimelineContent(
                        events = eventsCache[animatedDate].orEmpty(),
                        selectedDate = animatedDate,
                        onEventClick = { formRequest = EventFormRequest.edit(it) },
                        onEmptyAreaClick = { minute ->
                            formRequest = EventFormRequest.newAt(animatedDate, minute)
                        },
                        onSwipeDay = { deltaDays ->
                            // 以 VM 当前日期为基准，连续滑动不丢日
                            viewModel.setSelectedDate(viewModel.selectedDate.value.plusDays(deltaDays))
                        },
                        modifier = Modifier.padding(padding)
                    )
                }
            }

            PlannerView.TODO -> {
                ScheduleTodoContent(
                    viewModel = todoViewModel,
                    onArrangeTime = { task ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        formRequest = EventFormRequest.forTask(task, selectedDate, defaultStartMinute())
                    },
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }

    if (showDatePicker) {
        ScheduleDatePickerDialog(
            initialDate = selectedDate,
            onDismiss = { showDatePicker = false },
            onConfirm = { date ->
                viewModel.setSelectedDate(date)
                showDatePicker = false
            }
        )
    }

    formRequest?.let { form ->
        EventEditSheet(
            form = form,
            viewModel = viewModel,
            onDismiss = { formRequest = null }
        )
    }
}

/** 新增日程的默认开始时间：当前时间向下取整到 30 分钟 */
private fun defaultStartMinute(): Int {
    val now = LocalTime.now()
    val minute = now.hour * 60 + now.minute
    return (minute / 30) * 30
}

private fun formatDateLabel(date: LocalDate): String {
    val weekday = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
    val formatter = DateTimeFormatter.ofPattern("M/d", Locale.getDefault())
    return "${formatter.format(date)} $weekday"
}

/**
 * 事件表单请求。formId 在每次打开表单时唯一生成，
 * 驱动表单内部状态在"连续新增"时重置。
 */
data class EventFormRequest(
    val formId: Long,
    val editEventId: Long?,
    val presetTaskId: Long?,
    val initialTitle: String?,
    val initialDate: LocalDate,
    val initialStartMinute: Int,
    val initialEndMinute: Int
) {
    companion object {
        private fun nextFormId() = System.nanoTime()

        fun newAt(date: LocalDate, startMinute: Int): EventFormRequest {
            val end = (startMinute + 60).coerceAtMost(DAY_LAST_MINUTE)
            return EventFormRequest(
                formId = nextFormId(),
                editEventId = null,
                presetTaskId = null,
                initialTitle = null,
                initialDate = date,
                initialStartMinute = startMinute,
                initialEndMinute = end
            )
        }

        fun forTask(task: ScheduleEntity, date: LocalDate, startMinute: Int): EventFormRequest {
            val end = (startMinute + 60).coerceAtMost(DAY_LAST_MINUTE)
            return EventFormRequest(
                formId = nextFormId(),
                editEventId = null,
                presetTaskId = task.id,
                initialTitle = task.title,
                initialDate = date,
                initialStartMinute = startMinute,
                initialEndMinute = end
            )
        }

        fun edit(item: ScheduleEventWithTask): EventFormRequest = EventFormRequest(
            formId = nextFormId(),
            editEventId = item.event.id,
            presetTaskId = item.task?.id,
            initialTitle = item.task?.title,
            initialDate = LocalDate.ofEpochDay(item.event.date),
            initialStartMinute = item.event.startTime,
            initialEndMinute = item.event.endTime
        )
    }
}

private const val TIMELINE_HOUR_HEIGHT_DP = 56
private val TimelineHourHeight = TIMELINE_HOUR_HEIGHT_DP.dp
private val TimelineLabelWidth = 52.dp
private val SwipeDayThreshold = 72.dp
private val TimelineMinCardHeight = 44.dp
private val TimelineCardGap = 6.dp

private class PositionedEvent(
    val item: ScheduleEventWithTask,
    val top: Dp,
    val height: Dp
)

/**
 * 全天时间轴：左侧 00:00-24:00 时间刻度，右侧日程卡片。
 *
 * 卡片按开始时间锚定到时间轴位置；同一时间段允许多个日程，
 * 重叠的卡片纵向顺延排布（不做左右分栏），保证可读性与点击准确性。
 * 当卡片因重叠被顺延时，对应时间段的时间轴按卡片堆叠占用的实际高度拉长，
 * 使"最晚结束刻度"落在最底部卡片的底边，刻度与卡片总高度保持一致。
 * 点击空白区域按 30 分钟粒度换算出默认开始时间。
 */
@Composable
private fun TimelineContent(
    events: List<ScheduleEventWithTask>,
    selectedDate: LocalDate,
    onEventClick: (ScheduleEventWithTask) -> Unit,
    onEmptyAreaClick: (startMinute: Int) -> Unit,
    onSwipeDay: (deltaDays: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    // 左右滑动切日的触发阈值：水平拖动超过该距离即切换（与垂直滚动互不干扰）
    val swipeThresholdPx = with(density) { SwipeDayThreshold.toPx() }

    val layout = remember(events) { buildTimelineLayout(events) }

    // 进入页面时滚动到当前时间前约 2 小时的位置（等待测量完成后，避免越界）
    var scrolled by remember { mutableStateOf(false) }
    LaunchedEffect(layout) {
        if (scrolled) return@LaunchedEffect
        snapshotFlow { scrollState.maxValue }.first { it > 0 }
        val nowMinute = LocalTime.now().let { it.hour * 60 + it.minute }
        val targetY = layout.minuteToY((nowMinute - 120).coerceAtLeast(0))
        val targetPx = with(density) { targetY.roundToPx() }
        scrollState.scrollTo(targetPx.coerceIn(0, scrollState.maxValue))
        scrolled = true
    }

    val isToday = selectedDate == LocalDate.now()
    val nowMinute = remember(isToday) {
        if (isToday) LocalTime.now().let { it.hour * 60 + it.minute } else -1
    }

    // 左右滑动切换前一天 / 后一天：水平拖动累积距离，松手超过阈值即切日；
    // 与垂直滚动互不干扰（各自只响应本方向的拖动），卡片点击仍由子级处理
    var swipeAccumulated = 0f
    Row(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { swipeAccumulated = 0f },
                    onDragEnd = {
                        if (kotlin.math.abs(swipeAccumulated) >= swipeThresholdPx) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (swipeAccumulated < 0) onSwipeDay(1) else onSwipeDay(-1)
                        }
                    },
                    onDragCancel = { swipeAccumulated = 0f },
                ) { change, dragAmount ->
                    change.consume()
                    swipeAccumulated += dragAmount
                }
            }
    ) {
        // 左侧时间刻度
        Box(
            modifier = Modifier
                .width(TimelineLabelWidth)
                .height(layout.totalHeight)
        ) {
            for (hour in 0..24) {
                val labelOffset = (layout.minuteToY(hour * 60) - 10.dp).coerceAtLeast(0.dp)
                val labelText = if (hour == 24) "24:00" else "%02d:00".format(hour)
                Text(
                    text = labelText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(y = labelOffset)
                        .padding(end = 10.dp)
                )
            }
        }

        // 右侧日程区域
        Box(
            modifier = Modifier
                .weight(1f)
                .height(layout.totalHeight)
                .padding(end = 12.dp)
                .pointerInput(layout) {
                    detectTapGestures { offset ->
                        val minute = layout.yToMinute(offset.y.toDp())
                        val snapped = ((minute / 30) * 30).coerceIn(0, DAY_LAST_MINUTE)
                        onEmptyAreaClick(snapped)
                    }
                }
        ) {
            // 整点分隔线（位置随时间轴拉伸而移动）
            for (hour in 1..24) {
                HorizontalDivider(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(y = layout.minuteToY(hour * 60)),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )
            }

            // 当前时间指示线（仅当天）
            if (nowMinute >= 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(y = layout.minuteToY(nowMinute))
                        .height(2.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary,
                            shape = MaterialTheme.shapes.extraSmall
                        )
                )
            }

            // 日程卡片（纵向顺延，重叠不左右分栏）
            layout.positioned.forEach { positioned ->
                val event = positioned.item.event
                val task = positioned.item.task
                Surface(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onEventClick(positioned.item)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(y = positioned.top)
                        .height(positioned.height),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = task?.title.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${formatMinute(event.startTime)} - ${formatMinute(event.endTime)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // 完成标识：跟随待办完成状态，仅显示勾选图标，不置灰卡片
                        if (task?.isCompleted == true) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = stringResource(R.string.discover_schedule_completed),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatMinute(minute: Int): String {
    val safe = minute.coerceIn(0, DAY_LAST_MINUTE)
    return "%02d:%02d".format(safe / 60, safe % 60)
}

/** 基础刻度：每分钟对应的 dp 高度（未拉伸区域按此比例渲染） */
private val TimelineBasePerMinute = TimelineHourHeight * (1f / 60f)
private const val DAY_TOTAL_MINUTES = 24 * 60

/** 时间轴锚点：某时刻在拉伸后时间轴上的 dp 位置 */
private class TimelineKnot(val minute: Int, val y: Dp)

/**
 * 时间轴布局结果。
 *
 * knots 为分段线性映射的锚点（时刻 → dp 位置），分钟与 dp 均严格递增；
 * 相邻锚点之间线性插值。未落入锚点区间的部分按基础刻度延伸。
 */
private class TimelineLayout(
    val positioned: List<PositionedEvent>,
    val knots: List<TimelineKnot>,
    val totalHeight: Dp
) {
    fun minuteToY(minute: Int): Dp = minuteToYOf(knots, minute)

    fun yToMinute(y: Dp): Int {
        val knots = this.knots
        val first = knots.first()
        if (y <= first.y) {
            return (first.minute + ((y - first.y) / TimelineBasePerMinute).toInt()).coerceAtLeast(0)
        }
        for (i in 0 until knots.size - 1) {
            val a = knots[i]
            val b = knots[i + 1]
            if (y >= a.y && y <= b.y) {
                val spanY = b.y - a.y
                if (spanY <= 0.dp) return b.minute
                val frac = (y - a.y) / spanY
                return (a.minute + (b.minute - a.minute) * frac).toInt()
            }
        }
        val last = knots.last()
        return last.minute + ((y - last.y) / TimelineBasePerMinute).toInt()
    }
}

private fun minuteToYOf(knots: List<TimelineKnot>, minute: Int): Dp {
    if (minute <= knots.first().minute) {
        val first = knots.first()
        return first.y + TimelineBasePerMinute * (minute - first.minute)
    }
    for (i in 0 until knots.size - 1) {
        val a = knots[i]
        val b = knots[i + 1]
        if (minute >= a.minute && minute <= b.minute) {
            val span = b.minute - a.minute
            if (span <= 0) return b.y
            val frac = (minute - a.minute).toFloat() / span
            return a.y + (b.y - a.y) * frac
        }
    }
    val last = knots.last()
    return last.y + TimelineBasePerMinute * (minute - last.minute)
}

/**
 * 构建时间轴布局：
 * 1. 按时间重叠关系把日程聚成若干簇（簇内日程卡片纵向顺延堆叠）；
 * 2. 簇起点刻度 = 第一张卡片的顶边；簇终点刻度（最晚结束时间）= 最底部卡片的底边，
 *    簇内时间轴因此按卡片堆叠的实际高度拉长；
 * 3. 簇与簇之间的空闲时段仍按基础刻度渲染。
 */
private fun buildTimelineLayout(events: List<ScheduleEventWithTask>): TimelineLayout {
    val knots = mutableListOf(TimelineKnot(0, 0.dp))
    val positioned = mutableListOf<PositionedEvent>()
    var prevCardBottom = 0.dp

    var i = 0
    while (i < events.size) {
        // 聚类：起点早于当前簇结束时间的连续日程归入同一簇
        var clusterEndMinute = events[i].event.endTime
        var j = i + 1
        while (j < events.size && events[j].event.startTime < clusterEndMinute) {
            if (events[j].event.endTime > clusterEndMinute) {
                clusterEndMinute = events[j].event.endTime
            }
            j++
        }

        val clusterStartMinute = events[i].event.startTime
        val naturalY = minuteToYOf(knots, clusterStartMinute)
        val clusterTop = maxOf(naturalY, prevCardBottom + TimelineCardGap)
        val lastKnot = knots.last()
        if (lastKnot.minute == clusterStartMinute) {
            // 与上一簇结束时刻相同（背靠背日程）：合并锚点，把卡片间隙并入该刻度
            knots[knots.size - 1] = TimelineKnot(lastKnot.minute, maxOf(lastKnot.y, clusterTop))
        } else {
            knots.add(TimelineKnot(clusterStartMinute, clusterTop))
        }

        var cursor = clusterTop
        for (k in i until j) {
            val item = events[k]
            val duration = (item.event.endTime - item.event.startTime).coerceAtLeast(0)
            val height = maxOf(TimelineBasePerMinute * duration, TimelineMinCardHeight)
            val top = if (k == i) clusterTop else cursor + TimelineCardGap
            positioned.add(PositionedEvent(item, top, height))
            cursor = top + height
        }
        val clusterBottom = cursor

        knots.add(TimelineKnot(clusterEndMinute, clusterBottom))
        prevCardBottom = clusterBottom
        i = j
    }

    val lastKnot = knots.last()
    val dayEndY = if (lastKnot.minute >= DAY_TOTAL_MINUTES) {
        lastKnot.y
    } else {
        lastKnot.y + TimelineBasePerMinute * (DAY_TOTAL_MINUTES - lastKnot.minute)
    }
    return TimelineLayout(positioned, knots.toList(), dayEndY + 24.dp)
}

@Composable
private fun ScheduleDatePickerDialog(
    initialDate: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit
) {
    val initialMillis = initialDate
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli()
    val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = {
                val millis = state.selectedDateMillis
                if (millis != null) {
                    onConfirm(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                }
            }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        colors = DatePickerDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        DatePicker(state = state)
    }
}

@Composable
private fun ScheduleTimePickerDialog(
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val safeInitial = initialMinute.coerceIn(0, DAY_LAST_MINUTE)
    val state = rememberTimePickerState(
        initialHour = safeInitial / 60,
        initialMinute = safeInitial % 60,
        is24Hour = true
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = { Text(stringResource(R.string.schedule_event_pick_time)) },
        text = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TimePicker(state = state)
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(state.hour * 60 + state.minute) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

/**
 * 日程新增 / 编辑表单（同一套结构，编辑模式预填已有数据）。
 * 字段顺序：日期 → 名称（新建可选已有待办）→ 开始/结束时间 → 待办候选列表。
 * 抽屉直接以全高展开；保存成功后关闭抽屉返回时间轴。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventEditSheet(
    form: EventFormRequest,
    viewModel: SchedulePlannerViewModel,
    onDismiss: () -> Unit
) {
    val isEdit = form.editEventId != null
    val haptic = LocalHapticFeedback.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 表单内部状态：以 formId 为 key，重新打开时整体重置
    var title by remember(form.formId) { mutableStateOf(form.initialTitle ?: "") }
    var selectedTaskId by remember(form.formId) { mutableStateOf(form.presetTaskId) }
    var date by remember(form.formId) { mutableStateOf(form.initialDate) }
    var startMinute by remember(form.formId) { mutableIntStateOf(form.initialStartMinute) }
    var endMinute by remember(form.formId) { mutableIntStateOf(form.initialEndMinute) }

    var showDatePicker by remember { mutableStateOf(false) }
    var pickingTimeFor by remember { mutableStateOf<TimeField?>(null) }
    var showConflictConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val isSaving by viewModel.isSaving.collectAsState()
    val candidates by viewModel.candidateTodos.collectAsState()
    val candidateFilter by viewModel.candidateFilter.collectAsState()

    val dateLabel = remember(date) { formatDateLabel(date) }

    val timeInvalid = endMinute <= startMinute
    val canSave = when {
        timeInvalid -> false
        isEdit -> title.isNotBlank()
        else -> selectedTaskId != null || title.isNotBlank()
    }

    fun doSave(allowOverlap: Boolean) {
        if (!canSave || isSaving) return
        if (isEdit) {
            viewModel.updateEvent(
                eventId = form.editEventId ?: return,
                title = title,
                date = date,
                startMinute = startMinute,
                endMinute = endMinute,
                allowOverlap = allowOverlap,
                onConflict = { showConflictConfirm = true },
                onSuccess = { onDismiss() }
            )
        } else {
            viewModel.saveNewEvent(
                candidateTaskId = selectedTaskId,
                title = title,
                date = date,
                startMinute = startMinute,
                endMinute = endMinute,
                allowOverlap = allowOverlap,
                onConflict = { showConflictConfirm = true },
                onSuccess = { onDismiss() }
            )
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(
                    if (isEdit) R.string.schedule_event_edit else R.string.schedule_event_new
                ),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )

            // 日期（顶部，不在开始/结束时间下重复展示）
            SheetPickerRow(
                icon = { Icon(Icons.Rounded.CalendarToday, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) },
                label = stringResource(R.string.schedule_event_date),
                value = dateLabel,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    showDatePicker = true
                }
            )

            // 日程名称：来自待办；新建模式下修改输入即切换为"创建全新事项"
            OutlinedTextField(
                value = title,
                onValueChange = {
                    title = it
                    if (!isEdit) selectedTaskId = null
                },
                label = { Text(stringResource(R.string.schedule_event_name_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = MaterialTheme.shapes.medium
            )

            // 开始 / 结束时间（仅小时和分钟，单行展示；点按数值弹出时间选择器）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Rounded.Timer,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.schedule_event_start_short),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = formatMinute(startMinute),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            pickingTimeFor = TimeField.START
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "-",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.schedule_event_end_short),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = formatMinute(endMinute),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            pickingTimeFor = TimeField.END
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }
            if (timeInvalid) {
                Text(
                    text = stringResource(R.string.schedule_event_time_invalid),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // 待办候选列表（仅新建模式）
            if (!isEdit) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.schedule_event_pick_task),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 排程状态筛选：未排程（默认）/ 已排程 / 全部
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val filterOptions = listOf(
                            ScheduleStatusFilter.UNSCHEDULED to stringResource(R.string.schedule_filter_unscheduled),
                            ScheduleStatusFilter.SCHEDULED to stringResource(R.string.schedule_filter_scheduled),
                            ScheduleStatusFilter.ALL to stringResource(R.string.schedule_filter_all)
                        )
                        filterOptions.forEach { (option, label) ->
                            FilterChip(
                                selected = candidateFilter == option,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    viewModel.setCandidateFilter(option)
                                },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    if (candidates.isEmpty()) {
                        Text(
                            text = stringResource(R.string.discover_schedule_no_tasks),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.height(180.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(candidates, key = { it.id }) { todo ->
                                val isSelected = selectedTaskId == todo.id
                                Surface(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        selectedTaskId = todo.id
                                        title = todo.title
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.small,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                    },
                                    contentColor = if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = todo.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (isSelected) {
                                            Icon(
                                                Icons.Rounded.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 操作区
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 编辑模式提供删除日程（仅删除当前日程，不影响待办）
                if (isEdit) {
                    TextButton(
                        onClick = { showDeleteConfirm = true },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.schedule_event_delete_title))
                    }
                } else {
                    Spacer(Modifier.width(8.dp))
                }

                Button(
                    onClick = { doSave(allowOverlap = false) },
                    enabled = canSave && !isSaving
                ) {
                    Text(stringResource(R.string.schedule_event_save))
                }
            }
        }
    }

    if (showDatePicker) {
        ScheduleDatePickerDialog(
            initialDate = date,
            onDismiss = { showDatePicker = false },
            onConfirm = {
                date = it
                showDatePicker = false
            }
        )
    }

    pickingTimeFor?.let { field ->
        val initial = if (field == TimeField.START) startMinute else endMinute
        ScheduleTimePickerDialog(
            initialMinute = initial,
            onDismiss = { pickingTimeFor = null },
            onConfirm = { minute ->
                val clamped = minute.coerceIn(0, DAY_LAST_MINUTE)
                if (field == TimeField.START) startMinute = clamped else endMinute = clamped
                pickingTimeFor = null
            }
        )
    }

    // 时间重叠确认：允许用户确认后继续保存
    if (showConflictConfirm) {
        AlertDialog(
            onDismissRequest = { showConflictConfirm = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            title = { Text(stringResource(R.string.schedule_event_overlap_title)) },
            text = { Text(stringResource(R.string.schedule_event_overlap_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        showConflictConfirm = false
                        doSave(allowOverlap = true)
                    }
                ) {
                    Text(stringResource(R.string.schedule_event_overlap_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showConflictConfirm = false },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    // 删除日程确认：仅删除日程记录，待办保留
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            title = { Text(stringResource(R.string.schedule_event_delete_title)) },
            text = { Text(stringResource(R.string.schedule_event_delete_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        form.editEventId?.let { viewModel.deleteEvent(it) }
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(stringResource(R.string.schedule_event_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirm = false },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

private enum class TimeField { START, END }

@Composable
private fun SheetPickerRow(
    icon: @Composable () -> Unit,
    label: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
