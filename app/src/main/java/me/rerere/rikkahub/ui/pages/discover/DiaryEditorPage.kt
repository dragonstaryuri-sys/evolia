package me.rerere.rikkahub.ui.pages.discover

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.hooks.HapticPattern
import org.koin.androidx.compose.koinViewModel
import java.time.LocalDate
import java.time.ZoneId
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryEditorPage(
    diaryId: String,
    vm: DiaryVM = koinViewModel()
) {
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val haptics = rememberPremiumHaptics()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val isNew = diaryId == "new"

    var selectedAssistantId by remember { mutableStateOf("USER") }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var content by remember { mutableStateOf("") }
    var isAuthorExpanded by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

    val filteredDiaries by vm.filteredDiaries.collectAsStateWithLifecycle()
    val schedules by vm.getSchedulesForDate(selectedDate).collectAsStateWithLifecycle(emptyList())

    // 初始数据加载
    LaunchedEffect(diaryId, filteredDiaries) {
        if (!isNew) {
            filteredDiaries.find { it.id == diaryId }?.let {
                selectedAssistantId = it.assistantId
                content = it.content
                selectedDate = runCatching { LocalDate.parse(it.date) }.getOrDefault(LocalDate.now())
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val onSave = {
        if (content.isBlank() && selectedAssistantId == "USER") {
            // 内容为空且是用户日记时不保存
        } else {
            if (isNew) {
                if (selectedAssistantId == "USER") {
                    vm.saveDiary(assistantId = selectedAssistantId, content = content, date = selectedDate.toString())
                    toaster.show(vm.app.getString(R.string.diary_add_success))
                } else {
                    vm.generateTodayDiary(selectedAssistantId, toaster)
                }
            } else {
                // 修改现有日记，传入 diaryId
                vm.saveDiary(id = diaryId, assistantId = selectedAssistantId, content = content, date = selectedDate.toString())
                toaster.show("已保存修改")
            }
            navController.popBackStack()
        }
    }

    BackHandler { navController.popBackStack() }

    Scaffold(
        topBar = {
            OneUITopAppBar(
                title = stringResource(if (isNew) R.string.diary_add_title else R.string.diary_edit),
                scrollBehavior = scrollBehavior,
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = {
                        haptics.perform(HapticPattern.Pop)
                        onSave()
                    }) {
                        Icon(Icons.Rounded.Check, null)
                    }
                }
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            // 只有新建模式才允许选作者和日期
            if (isNew) {
                Text(stringResource(R.string.diary_select_personnel), style = MaterialTheme.typography.labelMedium)
                Box(modifier = Modifier.padding(vertical = 8.dp)) {
                    val authorName = if (selectedAssistantId == "USER") stringResource(R.string.diary_personnel_user)
                                    else settings.assistants.find { it.id.toString() == selectedAssistantId }?.name ?: ""

                    InputChip(
                        selected = true,
                        onClick = { isAuthorExpanded = true },
                        label = { Text(authorName) },
                        trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null) }
                    )
                    DropdownMenu(expanded = isAuthorExpanded, onDismissRequest = { isAuthorExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.diary_personnel_user)) },
                            onClick = { selectedAssistantId = "USER"; isAuthorExpanded = false }
                        )
                        settings.assistants.forEach { assistant ->
                            DropdownMenuItem(
                                text = { Text(assistant.name) },
                                onClick = { selectedAssistantId = assistant.id.toString(); isAuthorExpanded = false }
                            )
                        }
                    }
                }

                Text(stringResource(R.string.diary_select_date), style = MaterialTheme.typography.labelMedium)
                Box(modifier = Modifier.padding(vertical = 8.dp)) {
                    AssistChip(
                        onClick = { showDatePicker = true },
                        label = { Text(selectedDate.toString()) },
                        leadingIcon = { Icon(Icons.Rounded.CalendarToday, null, modifier = Modifier.size(16.dp)) }
                    )
                }

                if (showDatePicker) {
                    val datePickerState = rememberDatePickerState(
                        initialSelectedDateMillis = selectedDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    )
                    DatePickerDialog(
                        onDismissRequest = { showDatePicker = false },
                        confirmButton = {
                            TextButton(onClick = {
                                datePickerState.selectedDateMillis?.let {
                                    selectedDate = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                                }
                                showDatePicker = false
                            }) { Text(stringResource(R.string.confirm)) }
                        }
                    ) { DatePicker(state = datePickerState) }
                }

                Spacer(Modifier.height(16.dp))
            }

            // 编辑区域：如果是修改模式（!isNew），或者新建用户日记，直接显示编辑器
            if (!isNew || selectedAssistantId == "USER") {
                Text(
                    text = if (isNew) stringResource(R.string.diary_write_hint) else "修改内容",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                BasicTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 350.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 26.sp
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { innerTextField ->
                        if (content.isEmpty()) Text("开始记录...", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        innerTextField()
                    }
                )

                // 只有新建模式且是用户日记时，才显示今日日程关联
                if (isNew && schedules.isNotEmpty()) {
                    Spacer(Modifier.height(32.dp))
                    Text(stringResource(R.string.diary_schedule_link), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    schedules.forEach { schedule ->
                        Card(
                            onClick = {
                                haptics.perform(HapticPattern.Pop)
                                val item = "\n- [${if (schedule.isCompleted) "x" else " "}] ${schedule.title}"
                                content += item
                            },
                            modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        ) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = schedule.isCompleted, onCheckedChange = null)
                                Spacer(Modifier.width(8.dp))
                                Text(schedule.title, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            } else {
                // 如果是新建 AI 日记，显示生成按钮
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                    Button(
                        onClick = { onSave() },
                        shape = MaterialTheme.shapes.large
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.discover_page_diary_generate))
                    }
                }
            }
        }
    }
}
