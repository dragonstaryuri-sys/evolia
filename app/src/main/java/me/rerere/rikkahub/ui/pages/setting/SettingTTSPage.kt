package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.ui.theme.LocalDarkMode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DEFAULT_SYSTEM_TTS_ID
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.components.ui.AutoProviderIcon
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.setting.components.TTSProviderConfigure
import me.rerere.tts.provider.TTSProviderSetting
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.spring
import me.rerere.rikkahub.ui.components.ui.ItemPosition
import me.rerere.rikkahub.ui.components.ui.PhysicsSwipeToDelete
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Close
import me.rerere.rikkahub.ui.theme.AppShapes
import me.rerere.rikkahub.ui.components.ui.ToastType
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.rounded.Schedule

@Composable
fun SettingTTSPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var editingProvider by remember { mutableStateOf<TTSProviderSetting?>(null) }
    var selectedTab by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val newProviders = settings.ttsProviders.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
        vm.updateSettings(settings.copy(ttsProviders = newProviders))
    }

    var showFilterSettingsDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            OneUITopAppBar(
                title = stringResource(R.string.setting_voice_page_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    if (selectedTab == 0) {
                        IconButton(onClick = { showFilterSettingsDialog = true }) {
                            Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.settings))
                        }
                        AddTTSProviderButton {
                            vm.updateSettings(
                                settings.copy(
                                    ttsProviders = listOf(it) + settings.ttsProviders
                                )
                            )
                        }
                    }
                }
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { innerPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            androidx.compose.material3.PrimaryTabRow(selectedTabIndex = selectedTab) {
                androidx.compose.material3.Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.setting_voice_tab_tts)) }
                )
                androidx.compose.material3.Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.setting_voice_tab_asr)) }
                )
            }
            if (selectedTab == 0) {
                val haptics = rememberPremiumHaptics(enabled = settings.displaySetting.enableUIHaptics)

        var draggingIndex by remember { mutableIntStateOf(-1) }
        var dragOffset by remember { mutableFloatStateOf(0f) }
        var isUnlocked by remember { mutableStateOf(false) }
        var neighborsUnlocked by remember { mutableStateOf(false) }

        val density = androidx.compose.ui.platform.LocalDensity.current
        val canDelete = settings.ttsProviders.size > 1

        if (dragOffset == 0f && neighborsUnlocked) {
            neighborsUnlocked = false
        }

        var showDeleteDialog by remember { mutableStateOf(false) }
        var providerToDelete by remember { mutableStateOf<TTSProviderSetting?>(null) }

        val tts = LocalTTSState.current
        val ttsError by tts.error.collectAsState()
        val toaster = LocalToaster.current

        LaunchedEffect(ttsError) {
            ttsError?.let { errorMessage ->
                toaster.show(
                    message = "TTS Error: $errorMessage",
                    type = ToastType.Error
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            state = lazyListState
        ) {
            itemsIndexed(settings.ttsProviders, key = { _, provider -> provider.id }) { index, provider ->
                val isSelected = settings.selectedTTSProviderId == provider.id
                val position = when {
                    settings.ttsProviders.size == 1 -> ItemPosition.ONLY
                    index == 0 -> ItemPosition.FIRST
                    index == settings.ttsProviders.lastIndex -> ItemPosition.LAST
                    else -> ItemPosition.MIDDLE
                }

                val thresholdPx = with(density) { 35.dp.toPx() }
                if (draggingIndex >= 0 && !neighborsUnlocked && kotlin.math.abs(dragOffset) >= thresholdPx) {
                    neighborsUnlocked = true
                }

                val shouldNeighborFollow = draggingIndex >= 0 &&
                    draggingIndex != index &&
                    !isUnlocked &&
                    !neighborsUnlocked

                val neighborOffset = if (shouldNeighborFollow) {
                    val distance = kotlin.math.abs(index - draggingIndex)
                    when (distance) {
                        1 -> dragOffset * 0.35f
                        2 -> dragOffset * 0.12f
                        else -> 0f
                    }
                } else {
                    0f
                }


                ReorderableItem(
                    state = reorderableState,
                    key = provider.id,
                    animateItemModifier = Modifier
                ) { isDragging ->
                    key(isSelected) {
                        PhysicsSwipeToDelete(
                            position = if (isSelected) ItemPosition.ONLY else position,
                            groupCornerRadius = if (isSelected) 100.dp else 24.dp,
                            deleteEnabled = canDelete && !isSelected,
                            neighborOffset = neighborOffset,
                            onDragProgress = { offset, unlocked ->
                                draggingIndex = index
                                dragOffset = offset
                                isUnlocked = unlocked
                            },
                            onDragEnd = {
                                if (draggingIndex == index) {
                                    draggingIndex = -1
                                    dragOffset = 0f
                                }
                            },
                            onDelete = {
                                providerToDelete = provider
                                showDeleteDialog = true
                            },
                            modifier = Modifier
                                .scale(if (isDragging) 0.95f else 1f)
                                .fillMaxWidth()
                        ) { _ ->
                        TTSProviderItemContent(
                            provider = provider,
                            isSelected = isSelected,
                            haptics = haptics,
                            onSelect = {
                                if (!isSelected) {
                                    haptics.perform(HapticPattern.Pop)
                                    vm.updateSettings(settings.copy(selectedTTSProviderId = provider.id))
                                }
                            },
                            onEdit = {
                                editingProvider = provider
                            },
                            dragHandle = {
                                IconButton(
                                    onClick = {},
                                    modifier = Modifier.longPressDraggableHandle(
                                        onDragStarted = {
                                            haptics.perform(HapticPattern.Pop)
                                        },
                                        onDragStopped = {
                                            haptics.perform(HapticPattern.Thud)
                                        }
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.DragIndicator,
                                        contentDescription = null
                                    )
                                }
                            }
                        )
                    }
                    }
                }
            }
        }

        if (showDeleteDialog && providerToDelete != null) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = {
                    showDeleteDialog = false
                    providerToDelete = null
                },
                title = { Text(stringResource(R.string.confirm_delete)) },
                text = { Text(stringResource(R.string.assistant_page_delete_dialog_text)) },
                dismissButton = {
                    TextButton(onClick = {
                        showDeleteDialog = false
                        providerToDelete = null
                    }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        providerToDelete?.let { p ->
                            val newProviders = settings.ttsProviders - p
                            val newSelectedId =
                                if (settings.selectedTTSProviderId == p.id) DEFAULT_SYSTEM_TTS_ID else settings.selectedTTSProviderId
                            vm.updateSettings(settings.copy(
                                ttsProviders = newProviders,
                                selectedTTSProviderId = newSelectedId
                            ))
                        }
                        showDeleteDialog = false
                        providerToDelete = null
                    }) {
                        Text(stringResource(R.string.confirm))
                    }
                }
            )
        }
            } else {
                AsrTab(vm = vm, contentPadding = PaddingValues(16.dp))
            }
        }
    }

    editingProvider?.let { provider ->
        val bottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var currentProvider by remember(provider) { mutableStateOf(provider) }
        val tts = LocalTTSState.current
        val scope = rememberCoroutineScope()
        var selectedPreviewLang by remember { mutableStateOf("zh") }
        val previewTexts = mapOf(
            "zh" to "哼，我声音好听吗？",
            "en" to "Hello, this is an English voice preview. How does it sound?",
            "ko" to "안녕하세요, 이것은 한국어 음性 미리보기입니다. 어떠신가요?"
        )
        ModalBottomSheet(
            onDismissRequest = {
                editingProvider = null
            },
            sheetState = bottomSheetState,
            sheetGesturesEnabled = false,
            dragHandle = {
                IconButton(
                    onClick = {
                        scope.launch {
                            bottomSheetState.hide()
                            editingProvider = null
                        }
                    }
                ) {
                    Icon(Icons.Rounded.KeyboardArrowDown, null)
                }
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .fillMaxHeight(0.8f),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val providerTypeName = when (currentProvider) {
                        is TTSProviderSetting.OpenAI -> "OpenAI"
                        is TTSProviderSetting.Mimo -> "MiMo"
                        is TTSProviderSetting.Custom -> "OpenAI(兼容)"
                        is TTSProviderSetting.Gemini -> "Google"
                        is TTSProviderSetting.MiniMax -> "MiniMax"
                        is TTSProviderSetting.ElevenLabs -> "ElevenLabs"
                        is TTSProviderSetting.Azure -> "Azure"
                        is TTSProviderSetting.SystemTTS -> "System"
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.setting_tts_page_edit_provider),
                            style = MaterialTheme.typography.headlineSmall
                        )
                        if (currentProvider.builtIn) {
                            Spacer(Modifier.size(8.dp))
                            Tag(type = TagType.INFO) {
                                Text("Built-in")
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        listOf("zh" to "中", "en" to "EN", "ko" to "KR").forEach { (code, label) ->
                            androidx.compose.material3.InputChip(
                                selected = selectedPreviewLang == code,
                                onClick = { selectedPreviewLang = code },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.padding(horizontal = 2.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                scope.launch {
                                    tts.speak(
                                        text = previewTexts[selectedPreviewLang] ?: "",
                                        overrideSetting = currentProvider
                                    )
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
                                contentDescription = stringResource(R.string.test_tts)
                            )
                        }
                    }
                }


                TTSProviderConfigure(
                    setting = currentProvider,
                    onValueChange = { newState ->
                        currentProvider = newState
                    },
                    modifier = Modifier.weight(1f)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = {
                            editingProvider = null
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.cancel))
                    }

                    TextButton(
                        onClick = {
                            val newProviders = settings.ttsProviders.map {
                                if (it.id == provider.id) currentProvider else it
                            }
                            vm.updateSettings(settings.copy(ttsProviders = newProviders))
                            editingProvider = null
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.chat_page_save))
                    }
                }
            }
        }
    }

    if (showFilterSettingsDialog) {
        TtsTextFilterSettingsDialog(
            displaySetting = settings.displaySetting,
            onDismiss = { showFilterSettingsDialog = false },
            onUpdateSettings = { newDisplaySetting ->
                // 收到新包裹，直接更新整个 displaySetting
                vm.updateSettings(settings.copy(displaySetting = newDisplaySetting))
            }
        )
    }
}

@Composable
private fun TtsTextFilterSettingsDialog(
    displaySetting: me.rerere.rikkahub.data.datastore.DisplaySetting,
    onDismiss: () -> Unit,
    onUpdateSettings: (me.rerere.rikkahub.data.datastore.DisplaySetting) -> Unit // 统一的回调
) {
    val rules = displaySetting.ttsTextFilterRules
    val filterEmojis = displaySetting.filterEmojis
    var showAddDialog by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<me.rerere.rikkahub.data.datastore.TtsTextFilterRule?>(null) }
    var showTimePicker by remember { mutableStateOf(false) }
    var pickingStart by remember { mutableStateOf(true) } // true 为选择开始时间，false 为结束时间

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        dragHandle = {
            IconButton(
                onClick = {
                    scope.launch {
                        sheetState.hide()
                        onDismiss()
                    }
                }
            ) {
                Icon(Icons.Rounded.KeyboardArrowDown, null)
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.tts_filter_rules_title),
                    style = MaterialTheme.typography.titleLarge
                )
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.add))
                }
            }

            androidx.compose.material3.Card(
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                shape = AppShapes.CardLarge
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.tts_filter_rules_desc_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.tts_filter_rules_desc_content),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Global Emoji Filter Switch
            androidx.compose.material3.Card(
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                shape = AppShapes.CardLarge
            ) {
                androidx.compose.material3.ListItem(
                    colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = {
                        Text(stringResource(R.string.tts_filter_rules_filter_emojis_title))
                    },
                    supportingContent = {
                        Text(
                            text = stringResource(R.string.tts_filter_rules_filter_emojis_desc),
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    trailingContent = {
                        HapticSwitch(
                            checked = filterEmojis,
                            onCheckedChange = { enabled ->
                                onUpdateSettings(displaySetting.copy(filterEmojis = enabled))
                            }
                        )
                    }
                )
            }

            if (rules.isEmpty()) {
                androidx.compose.material3.Card(
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    shape = AppShapes.CardLarge
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.tts_filter_rules_no_rules),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rules.forEach { rule ->
                        TtsFilterRuleItem(
                            rule = rule,
                            onToggle = { enabled ->
                                onUpdateSettings(displaySetting.copy(ttsTextFilterRules = rules.map {
                                    if (it.id == rule.id) it.copy(enabled = enabled) else it
                                }))
                            },
                            onEdit = { editingRule = rule },
                            onDelete = {
                                onUpdateSettings(displaySetting.copy(ttsTextFilterRules = rules.filter { it.id != rule.id }))
                            }
                        )
                    }
                }
            }
            androidx.compose.material3.Card(
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                shape = AppShapes.CardLarge
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("夜间静音时段", style = MaterialTheme.typography.titleSmall)
                        // 使用项目自带的 HapticSwitch
                        me.rerere.rikkahub.ui.components.ui.HapticSwitch(
                            checked = displaySetting.muteTimeEnabled,
                            onCheckedChange = { enabled ->
                                onUpdateSettings(displaySetting.copy(muteTimeEnabled = enabled))
                            }
                        )
                    }

                    Text(
                        text = "在设定时段内，ai朗读将强制保持静默。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    androidx.compose.ui.util.lerp(1f, 0.5f, if(displaySetting.muteTimeEnabled) 0f else 1f).let { alpha ->
                        Row(
                            modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 开始时间选择
                            OutlinedTextField(
                                value = displaySetting.muteTimeStart ?: "",
                                onValueChange = {}, // 不允许手动输入
                                label = { Text("开始时间") },
                                modifier = Modifier.weight(1f),
                                readOnly = true, // 只读，点击触发
                                trailingIcon = { Icon(Icons.Rounded.Schedule, null) },
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                    .also { interactionSource ->
                                        LaunchedEffect(interactionSource) {
                                            interactionSource.interactions.collect {
                                                if (it is androidx.compose.foundation.interaction.PressInteraction.Release) {
                                                    pickingStart = true
                                                    showTimePicker = true
                                                }
                                            }
                                        }
                                    }
                            )
                            // 结束时间选择
                            OutlinedTextField(
                                value = displaySetting.muteTimeEnd ?: "",
                                onValueChange = {},
                                label = { Text("结束时间") },
                                modifier = Modifier.weight(1f),
                                readOnly = true,
                                trailingIcon = { Icon(Icons.Rounded.Schedule, null) },
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                    .also { interactionSource ->
                                        LaunchedEffect(interactionSource) {
                                            interactionSource.interactions.collect {
                                                if (it is androidx.compose.foundation.interaction.PressInteraction.Release) {
                                                    pickingStart = false
                                                    showTimePicker = true
                                                }
                                            }
                                        }
                                    }
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            if (showTimePicker) {
                val currentTime = if (pickingStart) displaySetting.muteTimeStart else displaySetting.muteTimeEnd
                val initialHour = currentTime?.substringBefore(":")?.toIntOrNull() ?: 0
                val initialMinute = currentTime?.substringAfter(":")?.toIntOrNull() ?: 0

                val timePickerState = androidx.compose.material3.rememberTimePickerState(
                    initialHour = initialHour,
                    initialMinute = initialMinute,
                    is24Hour = true
                )

                // 这里由于 M3 官方没提供标准的 TimePickerDialog 包裹类，我们写一个通用的
                androidx.compose.material3.DatePickerDialog( // 借用 DatePickerDialog 的容器样式
                    onDismissRequest = { showTimePicker = false },
                    confirmButton = {
                        TextButton(onClick = {
                            val formattedTime = String.format("%02d:%02d", timePickerState.hour, timePickerState.minute)
                            if (pickingStart) {
                                onUpdateSettings(displaySetting.copy(muteTimeStart = formattedTime))
                            } else {
                                onUpdateSettings(displaySetting.copy(muteTimeEnd = formattedTime))
                            }
                            showTimePicker = false
                        }) { Text(stringResource(R.string.confirm)) }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            // 可选：清除时间
                            if (pickingStart) {
                                onUpdateSettings(displaySetting.copy(muteTimeStart = null))
                            } else {
                                onUpdateSettings(displaySetting.copy(muteTimeEnd = null))
                            }
                            showTimePicker = false
                        }) { Text("清除") }
                    }
                ) {
                    Box(modifier = Modifier.padding(24.dp).align(Alignment.CenterHorizontally)) {
                        androidx.compose.material3.TimePicker(state = timePickerState)
                    }
                }
            }
        }
    }

    if (showAddDialog || editingRule != null) {
        TtsFilterRuleEditDialog(
            rule = editingRule,
            onDismiss = {
                showAddDialog = false
                editingRule = null
            },
            onSave = { newRule ->
                val newRules = if (editingRule != null) {
                    rules.map { if (it.id == editingRule!!.id) newRule else it }
                } else {
                    rules + newRule
                }
                onUpdateSettings(displaySetting.copy(ttsTextFilterRules = newRules))
                showAddDialog = false
                editingRule = null
            }
        )
    }
}

@Composable
private fun TtsFilterRuleItem(
    rule: me.rerere.rikkahub.data.datastore.TtsTextFilterRule,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val modeText = when (rule.mode) {
        me.rerere.rikkahub.data.datastore.TtsFilterMode.SKIP -> stringResource(R.string.tts_filter_mode_skip)
        me.rerere.rikkahub.data.datastore.TtsFilterMode.ONLY_READ -> stringResource(R.string.tts_filter_mode_only_read)
    }
    val modeColor = when (rule.mode) {
        me.rerere.rikkahub.data.datastore.TtsFilterMode.SKIP -> MaterialTheme.colorScheme.error
        me.rerere.rikkahub.data.datastore.TtsFilterMode.ONLY_READ -> MaterialTheme.colorScheme.primary
    }

    androidx.compose.material3.Card(
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = AppShapes.CardLarge,
        onClick = onEdit
    ) {
        androidx.compose.material3.ListItem(
            colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val start = rule.pattern
                    val end = rule.endPattern ?: rule.pattern
                    Text("${start}text${end}")
                }
            },
            supportingContent = {
                Text(
                    text = modeText,
                    style = MaterialTheme.typography.bodySmall,
                    color = modeColor
                )
            },
            trailingContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Rounded.Delete,
                            stringResource(R.string.delete),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                    HapticSwitch(
                        checked = rule.enabled,
                        onCheckedChange = onToggle
                    )
                }
            }
        )
    }
}

@Composable
private fun TtsFilterRuleEditDialog(
    rule: me.rerere.rikkahub.data.datastore.TtsTextFilterRule?,
    onDismiss: () -> Unit,
    onSave: (me.rerere.rikkahub.data.datastore.TtsTextFilterRule) -> Unit
) {
    var pattern by remember { mutableStateOf(rule?.pattern ?: "（") }
    var endPattern by remember { mutableStateOf(rule?.endPattern ?: "）") }
    var mode by remember { mutableStateOf(rule?.mode ?: me.rerere.rikkahub.data.datastore.TtsFilterMode.SKIP) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (rule != null) stringResource(R.string.tts_filter_dialog_edit) else stringResource(R.string.tts_filter_dialog_add))
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = pattern,
                        onValueChange = { pattern = it },
                        label = { Text(stringResource(R.string.tts_filter_dialog_pattern_label)) },
                        placeholder = { Text("（") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = endPattern,
                        onValueChange = { endPattern = it },
                        label = { Text(stringResource(R.string.tts_filter_dialog_end_pattern_label)) },
                        placeholder = { Text("）") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Text(
                    text = stringResource(R.string.tts_filter_dialog_mode_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    androidx.compose.material3.FilterChip(
                        selected = mode == me.rerere.rikkahub.data.datastore.TtsFilterMode.SKIP,
                        onClick = { mode = me.rerere.rikkahub.data.datastore.TtsFilterMode.SKIP },
                        label = { Text(stringResource(R.string.tts_filter_mode_skip)) },
                        modifier = Modifier.weight(1f)
                    )
                    androidx.compose.material3.FilterChip(
                        selected = mode == me.rerere.rikkahub.data.datastore.TtsFilterMode.ONLY_READ,
                        onClick = { mode = me.rerere.rikkahub.data.datastore.TtsFilterMode.ONLY_READ },
                        label = { Text(stringResource(R.string.tts_filter_mode_only_read)) },
                        modifier = Modifier.weight(1f)
                    )
                }

                val start = pattern
                val end = endPattern.ifEmpty { pattern }
                Text(
                    text = when (mode) {
                        me.rerere.rikkahub.data.datastore.TtsFilterMode.SKIP -> stringResource(R.string.tts_filter_dialog_desc_skip, start, end)
                        me.rerere.rikkahub.data.datastore.TtsFilterMode.ONLY_READ -> stringResource(R.string.tts_filter_dialog_desc_only_read, start, end)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (pattern.isNotBlank()) {
                        onSave(
                            me.rerere.rikkahub.data.datastore.TtsTextFilterRule(
                                id = rule?.id ?: kotlin.uuid.Uuid.random().toString(),
                                pattern = pattern,
                                endPattern = endPattern.ifBlank { null },
                                mode = mode,
                                enabled = rule?.enabled ?: true
                            )
                        )
                    }
                }
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun AddTTSProviderButton(onAdd: (TTSProviderSetting) -> Unit) {
    var showBottomSheet by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val haptics = rememberPremiumHaptics()

    IconButton(
        onClick = {
            searchQuery = ""
            showBottomSheet = true
        }
    ) {
        Icon(Icons.Rounded.Add, stringResource(R.string.setting_tts_page_add_provider_content_description))
    }

    if (showBottomSheet) {
        val bottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        data class TTSPreset(
            val type: kotlin.reflect.KClass<out TTSProviderSetting>,
            val name: String,
            val description: Int,
            val isLocal: Boolean = false
        )

        val allTtsPresets = remember {
            listOf(
                TTSPreset(TTSProviderSetting.SystemTTS::class, "System TTS", R.string.systemtts_dct, isLocal = true),
                TTSPreset(TTSProviderSetting.OpenAI::class, "OpenAI", R.string.openia_dct),
                TTSPreset(TTSProviderSetting.Mimo::class, "MiMo", R.string.mimo_dct),
                TTSPreset(TTSProviderSetting.Custom::class, "Custom(OpenAI兼容)", R.string.openia_dct),
                TTSPreset(TTSProviderSetting.Gemini::class, "Gemini", R.string.gemini_dct),
                TTSPreset(TTSProviderSetting.Azure::class, "Azure TTS",R.string.azure_dct ),
                TTSPreset(TTSProviderSetting.ElevenLabs::class, "ElevenLabs", R.string.elevenlabs_dct),
                TTSPreset(TTSProviderSetting.MiniMax::class, "MiniMax", R.string.minimax_dct),
            )
        }

        val context = androidx.compose.ui.platform.LocalContext.current
        val filteredPresets = remember(searchQuery, allTtsPresets) {
            if (searchQuery.isBlank()) {
                allTtsPresets
            } else {
                allTtsPresets.filter { preset ->
                    preset.name.contains(searchQuery, ignoreCase = true) ||
                    context.getString(preset.description).contains(searchQuery, ignoreCase = true)
                }
            }
        }

        val scope = rememberCoroutineScope()

        ModalBottomSheet(
            onDismissRequest = {
                showBottomSheet = false
            },
            sheetState = bottomSheetState,
            sheetGesturesEnabled = false,
            dragHandle = {
                IconButton(
                    onClick = {
                        scope.launch {
                            bottomSheetState.hide()
                            showBottomSheet = false
                        }
                    }
                ) {
                    Icon(Icons.Rounded.KeyboardArrowDown, null)
                }
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .fillMaxHeight(0.85f)
                    .clipToBounds()
            ) {
                Text(
                    text = stringResource(R.string.setting_tts_page_add_provider),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                )

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.setting_provider_page_search_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppShapes.SearchField,
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.assistant_page_cancel))
                            }
                        }
                    } else null
                )

                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    itemsIndexed(filteredPresets, key = { _, preset -> preset.name }) { index, preset ->
                        val position = when {
                            filteredPresets.size == 1 -> ItemPosition.ONLY
                            index == 0 -> ItemPosition.FIRST
                            index == filteredPresets.lastIndex -> ItemPosition.LAST
                            else -> ItemPosition.MIDDLE
                        }

                        val shape = when (position) {
                            ItemPosition.FIRST -> RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 10.dp, bottomEnd = 10.dp)
                            ItemPosition.LAST -> RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomStart = 24.dp, bottomEnd = 24.dp)
                            ItemPosition.MIDDLE -> RoundedCornerShape(10.dp)
                            ItemPosition.ONLY -> RoundedCornerShape(24.dp)
                        }

                        androidx.compose.material3.Surface(
                            onClick = {
                                haptics.perform(HapticPattern.Pop)
                                val newProvider = when (preset.type) {
                                    TTSProviderSetting.SystemTTS::class -> TTSProviderSetting.SystemTTS(builtIn = true)
                                    TTSProviderSetting.OpenAI::class -> TTSProviderSetting.OpenAI(builtIn = true)
                                    TTSProviderSetting.Mimo::class -> TTSProviderSetting.Mimo(builtIn = true)
                                    TTSProviderSetting.Custom::class -> TTSProviderSetting.Custom(builtIn = true)
                                    TTSProviderSetting.Gemini::class -> TTSProviderSetting.Gemini(builtIn = true)
                                    TTSProviderSetting.Azure::class -> TTSProviderSetting.Azure(builtIn = true)
                                    TTSProviderSetting.ElevenLabs::class -> TTSProviderSetting.ElevenLabs(builtIn = true)
                                    TTSProviderSetting.MiniMax::class -> TTSProviderSetting.MiniMax(builtIn = true)
                                    else -> TTSProviderSetting.SystemTTS(builtIn = true)
                                }
                                onAdd(newProvider)
                                showBottomSheet = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = shape,
                            color = if (LocalDarkMode.current) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                when (preset.type) {
                                    TTSProviderSetting.SystemTTS::class -> {
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .background(
                                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                                    shape = me.rerere.rikkahub.ui.hooks.rememberAvatarShape(false)
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.PhoneAndroid,
                                                contentDescription = null,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                    else -> {
                                        val providerName = when (preset.type) {
                                            TTSProviderSetting.OpenAI::class -> "OpenAI"
                                            TTSProviderSetting.Mimo::class -> "Mimo"
                                            TTSProviderSetting.Custom::class -> "OpenAI"
                                            TTSProviderSetting.Gemini::class -> "Google"
                                            TTSProviderSetting.Azure::class -> "Azure"
                                            TTSProviderSetting.ElevenLabs::class -> "ElevenLabs"
                                            TTSProviderSetting.MiniMax::class -> "MiniMax"
                                            else -> "Unknown"
                                        }
                                        AutoProviderIcon(
                                            name = providerName,
                                            modifier = Modifier.size(40.dp)
                                        )
                                    }
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = preset.name,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = stringResource(preset.description),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (preset.isLocal) {
                                        Tag(type = TagType.SUCCESS) {
                                            Text("Local")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}


@Composable
private fun TTSProviderItemContent(
    provider: TTSProviderSetting,
    isSelected: Boolean,
    haptics: me.rerere.rikkahub.ui.hooks.PremiumHaptics,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    dragHandle: @Composable () -> Unit
) {
    val backgroundColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "selectionBackground"
    )
    val textColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "textColor"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(0.dp))
            .background(backgroundColor)
            .clickable {
                haptics.perform(HapticPattern.Pop)
                onSelect()
            }
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (provider) {
            is TTSProviderSetting.SystemTTS -> {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = me.rerere.rikkahub.ui.hooks.rememberAvatarShape(false)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PhoneAndroid,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            else -> {
                val providerTypeName = when (provider) {
                    is TTSProviderSetting.OpenAI -> "OpenAI"
                    is TTSProviderSetting.Mimo -> "Mimo"
                    is TTSProviderSetting.Gemini -> "Google"
                    is TTSProviderSetting.MiniMax -> "MiniMax"
                    is TTSProviderSetting.ElevenLabs -> "ElevenLabs"
                    is TTSProviderSetting.Azure -> "Azure"
                    is TTSProviderSetting.SystemTTS -> "System"
                    is TTSProviderSetting.Custom -> "OpenAI"
                }
                AutoProviderIcon(
                    name = providerTypeName,
                    modifier = Modifier.size(40.dp)
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = if (provider is TTSProviderSetting.SystemTTS) Arrangement.spacedBy(8.dp) else Arrangement.Center,
        ) {
            @Suppress("DEPRECATION")
            Text(
                text = provider.name.ifEmpty { stringResource(R.string.setting_tts_page_default_name) },
                style = MaterialTheme.typography.titleMedium,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (provider is TTSProviderSetting.SystemTTS) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .clipToBounds()
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.wrapContentWidth(align = Alignment.Start, unbounded = true)
                    ) {
                        Tag(type = TagType.SUCCESS) {
                            Text("Local")
                        }
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(width = 40.dp, height = 24.dp)
                            .background(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        backgroundColor
                                    )
                                )
                            )
                    )
                }
            }
        }

        IconButton(
            onClick = {
                haptics.perform(HapticPattern.Pop)
                onEdit()
            }
        ) {
            Icon(
                imageVector = Icons.Rounded.Settings,
                contentDescription = stringResource(R.string.setting_tts_page_more_options_content_description)
            )
        }

        dragHandle()
    }
}
