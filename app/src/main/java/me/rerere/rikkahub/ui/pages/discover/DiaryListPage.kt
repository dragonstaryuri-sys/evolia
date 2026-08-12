package me.rerere.rikkahub.ui.pages.discover

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
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
import me.rerere.rikkahub.service.OcrFailureEvent
import me.rerere.rikkahub.ui.theme.AppShapes
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
    val diaryList by vm.diaryList.collectAsStateWithLifecycle()
    val hasMore by vm.hasMore.collectAsStateWithLifecycle()
    val isLoadingMore by vm.isLoadingMore.collectAsStateWithLifecycle()
    val datesWithDiaries by vm.datesWithDiaries.collectAsStateWithLifecycle()
    val diariesAtSelectedDate by vm.diariesAtSelectedDate.collectAsStateWithLifecycle()
    val selectedDate by vm.selectedDate.collectAsStateWithLifecycle()

    // 用于跟踪当前显示的月份，以便显示年份标题
    var currentMonthByPager by remember { mutableStateOf(YearMonth.from(selectedDate)) }

    // OCR 失败提醒：保存日记后 OCR 在后台执行，失败时用户已在列表页。
    // 用一个状态保存最近一条失败通知，用户可手动关闭。
    var ocrFailureNotification by remember { mutableStateOf<OcrFailureEvent?>(null) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val defaultUserStr = stringResource(R.string.diary_filter_user)
    val userNickname = if (settings.displaySetting.userNickname.isBlank()) defaultUserStr else settings.displaySetting.userNickname

    // 动态显示年份标题：日历模式显示当前滑动的月份年份，列表模式显示最新日记年份（或当前年）
    val topTitle = if (isCalendarMode) currentMonthByPager.year.toString() else LocalDate.now().year.toString()

    // 智能体自动日记设置弹窗
    var showAutoDiarySheet by remember { mutableStateOf(false) }
    val autoDiarySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // ===== 新增日记流程 =====
    // 作者选择弹窗
    var showAddDiaryAuthorSheet by remember { mutableStateOf(false) }
    val addDiaryAuthorSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 选中的作者（默认 USER）
    var pendingAuthorId by remember { mutableStateOf("USER") }
    // 选完作者后，如果是 USER，弹日记类型（文档扫描/直接记录）
    var showAddDiaryTypeSheet by remember { mutableStateOf(false) }
    val addDiaryTypeSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(assistantId) {
        if (assistantId != null) {
            vm.togglePersonnelFilter(assistantId)
        }
    }

    LaunchedEffect(vm, toaster) {
        vm.observeTaskResults(toaster)
    }

    // 收集 OCR 失败事件：保存手写日记后，OCR 在 AppScope 后台执行，
    // 用户已返回列表页。失败时在 topbar 下方显示可关闭的提醒横幅。
    LaunchedEffect(vm) {
        vm.ocrFailureEvents.collect { event ->
            ocrFailureNotification = event
        }
    }

    LaunchedEffect(showAutoDiarySheet) {
        if (showAutoDiarySheet) autoDiarySheetState.show() else autoDiarySheetState.hide()
    }

    LaunchedEffect(showAddDiaryAuthorSheet) {
        if (showAddDiaryAuthorSheet) addDiaryAuthorSheetState.show() else addDiaryAuthorSheetState.hide()
    }
    LaunchedEffect(showAddDiaryTypeSheet) {
        if (showAddDiaryTypeSheet) addDiaryTypeSheetState.show() else addDiaryTypeSheetState.hide()
    }

    if (showAutoDiarySheet) {
        ModalBottomSheet(
            onDismissRequest = { showAutoDiarySheet = false },
            sheetState = autoDiarySheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            AutoDiarySettingsContent(
                assistants = settings.assistants,
                onToggle = { assistantId, enabled ->
                    haptics.perform(HapticPattern.Pop)
                    vm.toggleAutoDiary(assistantId, enabled)
                }
            )
            Spacer(modifier = Modifier.height(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()))
        }
    }

    // ===== 新增日记：为谁新建？作者选择弹窗 =====
    if (showAddDiaryAuthorSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAddDiaryAuthorSheet = false },
            sheetState = addDiaryAuthorSheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            AddDiaryAuthorSheetContent(
                personnelIds = personnelIds,
                selectedId = pendingAuthorId,
                userNickname = userNickname,
                assistants = settings.assistants,
                userAvatar = settings.displaySetting.userAvatar,
                onSelect = { id ->
                    haptics.perform(HapticPattern.Tick)
                    pendingAuthorId = id
                },
                onConfirm = {
                    haptics.perform(HapticPattern.Pop)
                    showAddDiaryAuthorSheet = false
                    if (pendingAuthorId == "USER") {
                        // 是 USER，再弹日记类型选择
                        showAddDiaryTypeSheet = true
                    } else {
                        // 是智能体，直接开始生成当日日记
                        vm.generateTodayDiary(pendingAuthorId, toaster)
                    }
                },
                onDismiss = { showAddDiaryAuthorSheet = false }
            )
            Spacer(modifier = Modifier.height(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()))
        }
    }

    // ===== 新增日记：USER 选了作者后选日记类型 =====
    if (showAddDiaryTypeSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAddDiaryTypeSheet = false },
            sheetState = addDiaryTypeSheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            AddDiaryTypeSheetContent(
                onScan = {
                    haptics.perform(HapticPattern.Pop)
                    showAddDiaryTypeSheet = false
                    navController.navigate(Screen.DiaryEditor(diaryId = "new", entryType = "scan"))
                },
                onDirect = {
                    haptics.perform(HapticPattern.Pop)
                    showAddDiaryTypeSheet = false
                    navController.navigate(Screen.DiaryEditor(diaryId = "new", entryType = "text"))
                },
                onDismiss = { showAddDiaryTypeSheet = false }
            )
            Spacer(modifier = Modifier.height(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()))
        }
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
                            // 搜索按钮
                            IconButton(onClick = {
                                haptics.perform(HapticPattern.Pop)
                                navController.navigate(Screen.DiarySearch)
                            }) {
                                Icon(Icons.Rounded.Search, null)
                            }
                            // 添加按钮：先弹"为谁新建"选择
                            IconButton(onClick = {
                                haptics.perform(HapticPattern.Pop)
                                pendingAuthorId = "USER" // 默认选中自己
                                showAddDiaryAuthorSheet = true
                            }) {
                                Icon(Icons.Rounded.Add, null)
                            }
                            // 设置按钮（自动日记管理）
                            IconButton(onClick = {
                                haptics.perform(HapticPattern.Pop)
                                showAutoDiarySheet = true
                            }) {
                                Icon(Icons.Rounded.Settings, null)
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
                        },
                        getAssistantAvatar = { id ->
                            if (id == "USER") settings.displaySetting.userAvatar
                            else settings.assistants.find { it.id.toString() == id }?.avatar ?: me.rerere.rikkahub.core.data.model.Avatar.Dummy
                        }
                    )
                    // OCR 失败提醒横幅：点击"查看原因"跳转日记详情，点击关闭按钮可手动关闭
                    ocrFailureNotification?.let { event ->
                        OcrFailureBanner(
                            failedCount = event.failedCount,
                            onViewDetail = {
                                navController.navigate(Screen.DiaryDetail(event.diaryId))
                                ocrFailureNotification = null
                            },
                            onDismiss = { ocrFailureNotification = null }
                        )
                    }
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
                    diaries = diaryList,
                    onDiaryClick = { navController.navigate(Screen.DiaryDetail(it)) },
                    onDelete = { vm.deleteDiary(it) },
                    hasMore = hasMore,
                    isLoadingMore = isLoadingMore,
                    onLoadMore = { vm.loadMore() }
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
    getAssistantName: (String) -> String,
    getAssistantAvatar: (String) -> me.rerere.rikkahub.core.data.model.Avatar
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
                label = { Text(stringResource(R.string.diary_filter_all)) },
                leadingIcon = {
                    Icon(
                        Icons.Rounded.Groups,
                        null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
        }
        items(personnelIds) { id ->
            val isSelected = selectedIds.contains(id)
            FilterChip(
                selected = isSelected,
                onClick = { onToggle(id) },
                label = { Text(getAssistantName(id)) },
                leadingIcon = {
                    if (isSelected) {
                        Icon(Icons.Rounded.Check, null, modifier = Modifier.size(16.dp))
                    } else {
                        UIAvatar(
                            name = getAssistantName(id),
                            value = getAssistantAvatar(id),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun DiaryListView(
    diaries: List<AgentDiaryEntity>,
    onDiaryClick: (String) -> Unit,
    onDelete: (String) -> Unit,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit
) {
    val settings = LocalSettings.current
    val listState = rememberLazyListState()

    // 监听滚动到底部触发加载更多
    // 当列表为空时（首次加载），跳过自动 loadMore，避免与首次分页加载竞争
    LaunchedEffect(listState.canScrollForward, hasMore, isLoadingMore, diaries.size) {
        if (diaries.isNotEmpty() && !listState.canScrollForward && hasMore && !isLoadingMore) {
            onLoadMore()
        }
    }

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
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            groupedDiaries.forEach { (date, items) ->
                item(key = date) {
                    DiaryTimelineGroup(date, items, onDiaryClick, onDelete)
                }
            }

            // 加载更多指示器
            if (hasMore || isLoadingMore) {
                item(key = "pagination_footer") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isLoadingMore) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.diary_load_more),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
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
fun DiarySummaryCard(
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
            // 手写日记（有图片）：在正文上方显示"yyyy年M月d日 扫描日记"
            if (diary.images.isNotEmpty()) {
                val formattedDate = remember(diary.date) {
                    runCatching {
                        LocalDate.parse(diary.date)
                            .format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
                    }.getOrNull() ?: diary.date
                }
                Text(
                    text = "$formattedDate 扫描日记",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
            }
            MarkdownBlock(
                content = diary.content,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                modifier = Modifier
                    .heightIn(max = 120.dp)
                    .clipToBounds()
            )
        }
    }
}

/**
 * OCR 失败提醒横幅：显示在 topbar 下方。
 *
 * - 点击"查看原因"跳转到日记详情页查看具体失败原因
 * - 点击关闭按钮可手动关闭横幅
 * - 使用 errorContainer 配色，简洁醒目
 */
@Composable
private fun OcrFailureBanner(
    failedCount: Int,
    onViewDetail: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = stringResource(R.string.diary_ocr_failed_banner, failedCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = onViewDetail,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text(
                    text = stringResource(R.string.diary_ocr_failed_view_detail),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.diary_ocr_failed_dismiss),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun EmptyState(msg: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(msg, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoDiarySettingsContent(
    assistants: List<me.rerere.rikkahub.core.data.model.Assistant>,
    onToggle: (String, Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = stringResource(R.string.diary_auto_generate),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            text = stringResource(R.string.diary_auto_diary_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (assistants.isEmpty()) {
            EmptyState(stringResource(R.string.diary_no_assistants))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(assistants, key = { it.id.toString() }) { assistant ->
                    val assistantId = assistant.id.toString()

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            UIAvatar(
                                name = assistant.name,
                                value = assistant.avatar,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = assistant.name,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            if (assistant.isMain) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.diary_main_agent),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Switch(
                            checked = assistant.enableAutoDiary,
                            onCheckedChange = { enabled ->
                                onToggle(assistantId, enabled)
                            }
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

/**
 * 新增日记：作者选择弹窗内容
 * 列表项 = 头像 + 名称，默认选中 USER，右上角确认按钮
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddDiaryAuthorSheetContent(
    personnelIds: List<String>,
    selectedId: String,
    userNickname: String,
    assistants: List<me.rerere.rikkahub.core.data.model.Assistant>,
    userAvatar: me.rerere.rikkahub.core.data.model.Avatar,
    onSelect: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetScrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.fillMaxWidth().heightIn(min = 420.dp),
        topBar = {
            // 顶部：drag handle + 标题 + 右上角确认
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                }
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.diary_add_author_title),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge
                        )
                    },
                    actions = {
                        TextButton(onClick = onConfirm) {
                            Text(
                                text = stringResource(R.string.confirm),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Rounded.Close, null)
                        }
                    },
                    scrollBehavior = sheetScrollBehavior,
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        },
        containerColor = Color.Transparent
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(personnelIds, key = { it }) { id ->
                val name = if (id == "USER") userNickname
                else assistants.find { a -> a.id.toString() == id }?.name ?: ""
                val avatar = if (id == "USER") userAvatar
                else assistants.find { a -> a.id.toString() == id }?.avatar
                    ?: me.rerere.rikkahub.core.data.model.Avatar.Dummy
                val selected = id == selectedId

                Card(
                    onClick = { onSelect(id) },
                    shape = AppShapes.CardMedium,
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        UIAvatar(name = name, value = avatar, modifier = Modifier.size(44.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(
                            text = name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        // 选中态：Check 圆形按钮
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
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

/**
 * 新增日记：USER 选择日记类型（文档扫描 / 直接记录）
 */
@Composable
private fun AddDiaryTypeSheetContent(
    onScan: () -> Unit,
    onDirect: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // drag handle + 标题
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.diary_add_type_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Rounded.Close, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // 文档扫描
        Card(
            onClick = onScan,
            shape = AppShapes.CardLarge,
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.DocumentScanner,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.diary_add_type_scan),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.diary_add_type_scan_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // 直接记录
        Card(
            onClick = onDirect,
            shape = AppShapes.CardLarge,
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.EditNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.diary_add_type_direct),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.diary_add_type_direct_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
