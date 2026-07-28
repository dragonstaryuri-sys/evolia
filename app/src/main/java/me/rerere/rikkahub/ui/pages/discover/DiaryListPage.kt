package me.rerere.rikkahub.ui.pages.discover

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.core.data.db.entity.AgentDiaryEntity
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.hooks.HapticPattern
import org.koin.androidx.compose.koinViewModel
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryListPage(
    assistantId: String? = null,
    vm: DiaryVM = koinViewModel()
) {
    val navController = LocalNavController.current
    val haptics = rememberPremiumHaptics()
    val toaster = LocalToaster.current

    val isCalendarMode by vm.isCalendarMode.collectAsStateWithLifecycle()
    val selectedAssistantIds by vm.selectedAssistantIds.collectAsStateWithLifecycle()
    val personnelIds by vm.personnelList.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    val filteredDiaries by vm.filteredDiaries.collectAsStateWithLifecycle()
    val datesWithDiaries by vm.datesWithDiaries.collectAsStateWithLifecycle()
    val diariesAtSelectedDate by vm.diariesAtSelectedDate.collectAsStateWithLifecycle()
    val selectedDate by vm.selectedDate.collectAsStateWithLifecycle()

    // 用于跟踪当前显示的月份，以便显示年份标题
    var currentMonthByPager by remember { mutableStateOf(YearMonth.from(selectedDate)) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val defaultUserStr = stringResource(R.string.diary_filter_user)
    val userNickname = if (settings.displaySetting.userNickname.isBlank()) defaultUserStr else settings.displaySetting.userNickname

    // 动态显示年份标题：日历模式显示当前滑动的月份年份，列表模式显示最新日记年份（或当前年）
    val topTitle = if (isCalendarMode) currentMonthByPager.year.toString() else LocalDate.now().year.toString()

    LaunchedEffect(assistantId) {
        if (assistantId != null) {
            vm.togglePersonnelFilter(assistantId)
        }
    }

    LaunchedEffect(vm, toaster) {
        vm.observeTaskResults(toaster)
    }

    Scaffold(
        topBar = {
            Surface(tonalElevation = 1.dp) {
                Column {
                    OneUITopAppBar(
                        title = topTitle,
                        scrollBehavior = scrollBehavior,
                        navigationIcon = { BackButton() },
                        actions = {
                            IconButton(onClick = {
                                haptics.perform(HapticPattern.Pop)
                                navController.navigate(Screen.DiaryEditor(diaryId = "new"))
                            }) {
                                Icon(Icons.Rounded.Add, null)
                            }
                        }
                    )
                    PersonnelFilterBar(
                        personnelIds = personnelIds,
                        selectedIds = selectedAssistantIds,
                        onToggle = { id ->
                            haptics.perform(HapticPattern.Pop)
                            vm.togglePersonnelFilter(id)
                        },
                        getAssistantName = { id ->
                            if (id == "USER") userNickname
                            else settings.assistants.find { it.id.toString() == id }?.name ?: "Agent"
                        }
                    )
                }
            }
        },
        bottomBar = {
            NavigationBar(modifier = Modifier.height(64.dp)) {
                NavigationBarItem(
                    selected = isCalendarMode,
                    onClick = {
                        haptics.perform(HapticPattern.Pop)
                        vm.isCalendarMode.value = true
                    },
                    icon = { Icon(Icons.Rounded.CalendarMonth, null) },
                    label = { Text(stringResource(R.string.diary_view_calendar)) }
                )
                NavigationBarItem(
                    selected = !isCalendarMode,
                    onClick = {
                        haptics.perform(HapticPattern.Pop)
                        vm.isCalendarMode.value = false
                    },
                    icon = { Icon(Icons.AutoMirrored.Rounded.List, null) },
                    label = { Text(stringResource(R.string.diary_view_list)) }
                )
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (!isCalendarMode) {
                DiaryListView(
                    diaries = filteredDiaries,
                    onDiaryClick = { navController.navigate(Screen.DiaryDetail(it)) },
                    onDelete = { vm.deleteDiary(it) }
                )
            } else {
                DiaryCalendarView(
                    selectedDate = selectedDate,
                    onMonthChange = { currentMonthByPager = it },
                    datesWithDiaries = datesWithDiaries,
                    diariesAtSelectedDate = diariesAtSelectedDate,
                    onDateSelect = {
                        haptics.perform(HapticPattern.Pop)
                        vm.selectedDate.value = it
                    },
                    onDiaryClick = { navController.navigate(Screen.DiaryDetail(it)) },
                    onDelete = { vm.deleteDiary(it) }
                )
            }
        }
    }
}

@Composable
private fun PersonnelFilterBar(
    personnelIds: List<String>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    getAssistantName: (String) -> String
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
    ) {
        item {
            FilterChip(
                selected = selectedIds.contains("ALL"),
                onClick = { onToggle("ALL") },
                label = { Text(stringResource(R.string.diary_filter_all)) }
            )
        }
        items(personnelIds) { id ->
            FilterChip(
                selected = selectedIds.contains(id),
                onClick = { onToggle(id) },
                label = { Text(getAssistantName(id)) },
                leadingIcon = if (selectedIds.contains(id)) {
                    { Icon(Icons.Rounded.Check, null, modifier = Modifier.size(16.dp)) }
                } else null
            )
        }
    }
}

@Composable
private fun DiaryListView(
    diaries: List<AgentDiaryEntity>,
    onDiaryClick: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    val settings = LocalSettings.current
    // 分组和排序逻辑：我 > 主智能体 > 其他。同一级别按创建时间倒序。
    val groupedDiaries = remember(diaries, settings.assistants) {
        diaries.groupBy { it.date }
            .toList()
            .sortedByDescending { it.first } // 日期倒序
            .map { (date, items) ->
                date to items.sortedWith(
                    compareBy<AgentDiaryEntity> {
                        when {
                            it.assistantId == "USER" -> 0
                            settings.assistants.find { a -> a.id.toString() == it.assistantId }?.isMain == true -> 1
                            else -> 2
                        }
                    }.thenByDescending { it.createdAt }
                )
            }
    }

    if (groupedDiaries.isEmpty()) {
        EmptyState(stringResource(R.string.discover_page_diary_empty))
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 16.dp, top = 16.dp, bottom = 16.dp), // 减小整体左边距
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            groupedDiaries.forEach { (date, items) ->
                item(key = date) {
                    DiaryTimelineGroup(date, items, onDiaryClick, onDelete)
                }
            }
        }
    }
}

@Composable
private fun DiaryTimelineGroup(
    date: String,
    items: List<AgentDiaryEntity>,
    onDiaryClick: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    val parsedDate = remember(date) { LocalDate.parse(date) }
    Row(modifier = Modifier.fillMaxWidth()) {
        // 左侧时间轴日期 - 宽度减小
        Column(
            modifier = Modifier.width(44.dp).padding(top = 4.dp),
            horizontalAlignment = Alignment.End
        ) {
            Text(
                text = parsedDate.dayOfMonth.toString().padStart(2, '0'),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${parsedDate.monthValue}月",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.width(12.dp)) // 间距调小

        // 右侧卡片列表
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items.forEach { diary ->
                DiarySummaryCard(diary = diary, onClick = { onDiaryClick(diary.id) }, onDelete = { onDelete(diary.id) })
            }
        }
    }
}

@Composable
private fun DiaryCalendarView(
    selectedDate: LocalDate,
    onMonthChange: (YearMonth) -> Unit,
    datesWithDiaries: List<String>,
    diariesAtSelectedDate: List<AgentDiaryEntity>,
    onDateSelect: (LocalDate) -> Unit,
    onDiaryClick: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    val settings = LocalSettings.current
    val sortedDiaries = remember(diariesAtSelectedDate, settings.assistants) {
        diariesAtSelectedDate.sortedWith(
            compareBy<AgentDiaryEntity> {
                when {
                    it.assistantId == "USER" -> 0
                    settings.assistants.find { a -> a.id.toString() == it.assistantId }?.isMain == true -> 1
                    else -> 2
                }
            }.thenByDescending { it.createdAt }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            SwipeableCalendar(selectedDate, datesWithDiaries, onDateSelect, onMonthChange)
        }
        item {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )
        }
        if (sortedDiaries.isEmpty()) {
            item {
                EmptyState(stringResource(R.string.diary_empty_at_date))
            }
        } else {
            items(sortedDiaries, key = { it.id }) { diary ->
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    DiarySummaryCard(diary = diary, onClick = { onDiaryClick(diary.id) }, onDelete = { onDelete(diary.id) })
                }
            }
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SwipeableCalendar(
    selectedDate: LocalDate,
    datesWithDiaries: List<String>,
    onDateSelect: (LocalDate) -> Unit,
    onMonthChange: (YearMonth) -> Unit
) {
    val pagerState = rememberPagerState(initialPage = 500, pageCount = { 1000 })

    // 监听页码变化，同步标题栏的年份
    LaunchedEffect(pagerState.currentPage) {
        val diff = pagerState.currentPage - 500
        val month = YearMonth.from(selectedDate).plusMonths(diff.toLong())
        onMonthChange(month)
    }

    Column {
        // 月份显示
        val currentMonth = remember(pagerState.currentPage) {
            YearMonth.from(selectedDate).plusMonths((pagerState.currentPage - 500).toLong())
        }
        Text(
            text = currentMonth.format(DateTimeFormatter.ofPattern("MMMM")),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.primary
        )

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) { page ->
            val month = remember(page) {
                YearMonth.from(selectedDate).plusMonths((page - 500).toLong())
            }
            MonthGrid(month, selectedDate, datesWithDiaries, onDateSelect)
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    selectedDate: LocalDate,
    datesWithDiaries: List<String>,
    onDateSelect: (LocalDate) -> Unit
) {
    val monthStart = month.atDay(1)
    val firstDayOfWeek = monthStart.dayOfWeek.value % 7
    val days = remember(month) {
        val list = mutableListOf<LocalDate?>()
        repeat(firstDayOfWeek) { list.add(null) }
        for (i in 1..month.lengthOfMonth()) {
            list.add(month.atDay(i))
        }
        list
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("日", "一", "二", "三", "四", "五", "六").forEach { day ->
                Text(
                    text = day,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        days.chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    Box(modifier = Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                        if (date != null) {
                            val isSelected = date == selectedDate
                            val hasDiary = datesWithDiaries.contains(date.toString())
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                    .clickable { onDateSelect(date) }
                            ) {
                                Text(
                                    text = date.dayOfMonth.toString(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                if (hasDiary) {
                                    Box(modifier = Modifier.size(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
                                } else {
                                    Spacer(Modifier.size(4.dp))
                                }
                            }
                        }
                    }
                }
                // 确保不满 7 天的行也是左对齐
                if (week.size < 7) {
                    repeat(7 - week.size) {
                        Box(modifier = Modifier.weight(1f).aspectRatio(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun DiarySummaryCard(
    diary: AgentDiaryEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val settings = LocalSettings.current
    val haptics = rememberPremiumHaptics()
    val clipboard = LocalClipboardManager.current
    val toaster = LocalToaster.current
    val copiedMessage = stringResource(R.string.diary_copied)
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val authorName = if (diary.assistantId == "USER") {
        val defaultUserStr = stringResource(R.string.diary_filter_user)
        if (settings.displaySetting.userNickname.isBlank()) defaultUserStr else settings.displaySetting.userNickname
    } else settings.assistants.find { it.id.toString() == diary.assistantId }?.name ?: "Agent"

    val authorAvatar = if (diary.assistantId == "USER") settings.displaySetting.userAvatar
                      else settings.assistants.find { it.id.toString() == diary.assistantId }?.avatar ?: me.rerere.rikkahub.core.data.model.Avatar.Dummy

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.diary_delete_confirm_title)) },
            text = { Text(stringResource(R.string.diary_delete_confirm_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteConfirm = false
                }) { Text(stringResource(R.string.confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        // 底部增加内边距
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                UIAvatar(name = authorName, value = authorAvatar, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text(authorName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.weight(1f))

                // 复制按钮
                IconButton(
                    onClick = {
                        haptics.perform(HapticPattern.Pop)
                        clipboard.setText(AnnotatedString(diary.content))
                        toaster.show(copiedMessage)
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // 删除按钮
                IconButton(
                    onClick = {
                        haptics.perform(HapticPattern.Pop)
                        showDeleteConfirm = true
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Rounded.Delete, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
                }
            }
            Spacer(Modifier.height(8.dp))
            val summary = remember(diary.content) {
                if (diary.content.length > 150) diary.content.take(150) + "..." else diary.content
            }
            MarkdownBlock(
                content = summary,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                modifier = Modifier.heightIn(max = 120.dp)
            )
        }
    }
}

@Composable
private fun EmptyState(msg: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(msg, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
