package me.rerere.rikkahub.ui.pages.discover

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val defaultUserStr = stringResource(R.string.diary_filter_user)
    val listTitle = stringResource(R.string.discover_page_diary_title)
    val userNickname = if (settings.displaySetting.userNickname.isBlank()) defaultUserStr else settings.displaySetting.userNickname

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
                        title = listTitle,
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
                    onDiaryClick = { navController.navigate(Screen.DiaryDetail(it)) }
                )
            } else {
                DiaryCalendarView(
                    selectedDate = selectedDate,
                    datesWithDiaries = datesWithDiaries,
                    diariesAtSelectedDate = diariesAtSelectedDate,
                    onDateSelect = {
                        haptics.perform(HapticPattern.Pop)
                        vm.selectedDate.value = it
                    },
                    onDiaryClick = { navController.navigate(Screen.DiaryDetail(it)) }
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
    onDiaryClick: (String) -> Unit
) {
    if (diaries.isEmpty()) {
        EmptyState(stringResource(R.string.discover_page_diary_empty))
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(diaries, key = { it.id }) { diary ->
                DiarySummaryCard(diary = diary, onClick = { onDiaryClick(diary.id) })
            }
        }
    }
}

@Composable
private fun DiaryCalendarView(
    selectedDate: LocalDate,
    datesWithDiaries: List<String>,
    diariesAtSelectedDate: List<AgentDiaryEntity>,
    onDateSelect: (LocalDate) -> Unit,
    onDiaryClick: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            WeekGridCalendar(selectedDate, datesWithDiaries, onDateSelect)
        }
        item {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )
        }
        if (diariesAtSelectedDate.isEmpty()) {
            item {
                EmptyState(stringResource(R.string.diary_empty_at_date))
            }
        } else {
            items(diariesAtSelectedDate, key = { it.id }) { diary ->
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    DiarySummaryCard(diary = diary, onClick = { onDiaryClick(diary.id) })
                }
            }
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun WeekGridCalendar(
    selectedDate: LocalDate,
    datesWithDiaries: List<String>,
    onDateSelect: (LocalDate) -> Unit
) {
    val monthStart = selectedDate.withDayOfMonth(1)
    val firstDayOfWeek = monthStart.dayOfWeek.value % 7
    val days = remember(selectedDate.month, selectedDate.year) {
        val list = mutableListOf<LocalDate?>()
        repeat(firstDayOfWeek) { list.add(null) }
        for (i in 1..selectedDate.lengthOfMonth()) {
            list.add(selectedDate.withDayOfMonth(i))
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
            }
        }
    }
}

@Composable
private fun DiarySummaryCard(
    diary: AgentDiaryEntity,
    onClick: () -> Unit
) {
    val settings = LocalSettings.current
    val defaultUserStr = stringResource(R.string.diary_filter_user)

    val authorAssistant = remember(diary.assistantId, settings.assistants) {
        settings.assistants.find { it.id.toString() == diary.assistantId }
    }

    val authorName = if (diary.assistantId == "USER") {
        if (settings.displaySetting.userNickname.isBlank()) defaultUserStr else settings.displaySetting.userNickname
    } else authorAssistant?.name ?: "Agent"

    val authorAvatar = if (diary.assistantId == "USER") settings.displaySetting.userAvatar
                      else authorAssistant?.avatar ?: me.rerere.rikkahub.core.data.model.Avatar.Dummy

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                UIAvatar(name = authorName, value = authorAvatar, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(8.dp))
                Text(authorName, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.weight(1f))
                Text(diary.date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            val summary = remember(diary.content) {
                if (diary.content.length > 200) diary.content.take(200) + "..." else diary.content
            }
            MarkdownBlock(content = summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.heightIn(max = 140.dp))
        }
    }
}

@Composable
private fun EmptyState(msg: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(msg, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
