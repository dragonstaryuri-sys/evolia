package me.rerere.rikkahub.ui.pages.assistant.detail

import android.content.ClipData
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.HistoryEdu
import androidx.compose.material.icons.rounded.CleanHands
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.common.FeatureConfig
import me.rerere.rikkahub.core.data.model.Assistant
import me.rerere.rikkahub.core.data.model.AssistantMemory
import me.rerere.rikkahub.core.data.model.MemoryRetrievalMode
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import me.rerere.rikkahub.ui.components.ui.DebouncedTextField
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.utils.toLocalString

/**
 * Memory mode based on current settings
 */
private fun getMemoryMode(assistant: Assistant): MemoryMode {
    return when {
        !assistant.enableMemory -> MemoryMode.OFF
        assistant.enableMemoryConsolidation -> MemoryMode.ADVANCED
        assistant.useRagMemoryRetrieval -> {
            when (assistant.memoryRetrievalMode) {
                MemoryRetrievalMode.HYBRID -> MemoryMode.HYBRID
                MemoryRetrievalMode.KEYWORD -> MemoryMode.KEYWORD
                MemoryRetrievalMode.SEMANTIC -> MemoryMode.SEMANTIC
                MemoryRetrievalMode.OFF -> MemoryMode.BASIC
            }
        }

        assistant.enableRecentChatsReference -> MemoryMode.BASIC_RECENT
        else -> MemoryMode.BASIC
    }
}

private enum class MemoryMode(val displayNameRes: Int, val descriptionRes: Int) {
    OFF(R.string.memory_mode_off, R.string.memory_mode_off_desc),
    BASIC(R.string.memory_mode_basic, R.string.memory_mode_basic_desc),
    BASIC_RECENT(R.string.memory_mode_basic_recent, R.string.memory_mode_basic_recent_desc),
    SEMANTIC(R.string.memory_mode_basic_rag, R.string.memory_mode_basic_rag_desc),
    KEYWORD(R.string.memory_mode_keyword, R.string.memory_mode_keyword_desc),
    HYBRID(R.string.memory_mode_hybrid, R.string.memory_mode_hybrid_desc),
    ADVANCED(R.string.memory_mode_advanced, R.string.memory_mode_advanced_desc)
}

private enum class MemorySortOrder(val displayNameRes: Int) {
    NEWEST_FIRST(R.string.memory_sort_newest),
    OLDEST_FIRST(R.string.memory_sort_oldest),
    ALPHABETICAL(R.string.memory_sort_alphabetical)
}

@Composable
fun AssistantMemorySettings(
    assistant: Assistant,
    memories: List<AssistantMemory>,
    onUpdateAssistant: (Assistant) -> Unit,
    onAddMemory: (AssistantMemory) -> Unit,
    onUpdateMemory: (AssistantMemory) -> Unit,
    onDeleteMemory: (AssistantMemory) -> Unit,
    onRegenerateEmbeddings: (() -> Unit)? = null,
    embeddingProgress: EmbeddingProgress? = null,
    onTestRetrieval: ((String) -> Unit)? = null,
    retrievalResults: List<Pair<AssistantMemory, Float>> = emptyList(),
    assistantDetailVM: AssistantDetailVM,
    estimatedMemoryCapacity: Int,
    needsEmbeddingRegeneration: Boolean = false,
    initialMemoryTab: Int? = null,  // 0 = Core, 1 = Episodic
    scrollToMemoryId: Int? = null,
    onNavigateToModels: () -> Unit = {}
) {
    val memoryDialogState = useEditState<AssistantMemory> { memory ->
        if (memory.id == 0) {
            onAddMemory(memory)
        } else {
            onUpdateMemory(memory)
        }
    }

    val isOptimizing by assistantDetailVM.isOptimizing.collectAsStateWithLifecycle()
    val isConsolidating by assistantDetailVM.isConsolidating.collectAsStateWithLifecycle()
    val haptics = rememberPremiumHaptics()

    // Detail Memory Local State
    var localDetailThreshold by remember(assistant.detailMemoryThreshold) {
        mutableFloatStateOf(assistant.detailMemoryThreshold.toFloat())
    }

    if (embeddingProgress != null && embeddingProgress.isRunning) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(R.string.generating_embeddings)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.processing_items, embeddingProgress.current, embeddingProgress.total))
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { embeddingProgress.current.toFloat() / embeddingProgress.total.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = { }
        )
    }

    if (isOptimizing) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(R.string.memory_optimizing)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.memory_optimizing), style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = { }
        )
    }

    if (isConsolidating) {
        AlertDialog(
            onDismissRequest = { assistantDetailVM.cancelConsolidation() },
            title = { Text(stringResource(R.string.loading)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.context_refresh_loading), style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = { },
            dismissButton = {
                TextButton(onClick = { assistantDetailVM.cancelConsolidation() }) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            }
        )
    }

    memoryDialogState.EditStateContent { memory, update ->
        AlertDialog(
            onDismissRequest = { memoryDialogState.dismiss() },
            title = { Text(stringResource(R.string.assistant_page_manage_memory_title)) },
            text = {
                TextField(
                    value = memory.content,
                    onValueChange = { update(memory.copy(content = it)) },
                    label = { Text(stringResource(R.string.assistant_page_manage_memory_title)) },
                    minLines = 1,
                    maxLines = 8
                )
            },
            confirmButton = {
                TextButton(onClick = { memoryDialogState.confirm() }) {
                    Text(stringResource(R.string.assistant_page_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { memoryDialogState.dismiss() }) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            }
        )
    }

    val memorySearchQuery by assistantDetailVM.memorySearchQuery.collectAsState()
    val currentEmbeddingModelId by assistantDetailVM.currentEmbeddingModelId.collectAsState()
    val currentMode = getMemoryMode(assistant)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { MemoryModeIndicator(mode = currentMode) }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SettingsGroupHeader(title = stringResource(R.string.assistant_memory_group_settings))

                Column(
                    modifier = Modifier.clip(RoundedCornerShape(24.dp)),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    MemorySettingsItem(
                        title = stringResource(R.string.assistant_memory_enable_title),
                        subtitle = stringResource(R.string.assistant_memory_enable_desc),
                        position = if (!assistant.enableMemory) "ONLY" else "FIRST",
                        trailing = {
                            HapticSwitch(
                                checked = assistant.enableMemory,
                                onCheckedChange = { enabled ->
                                    if (!enabled) {
                                        onUpdateAssistant(
                                            assistant.copy(
                                                enableMemory = false,
                                                enableMasterMemory = false
                                            )
                                        )
                                    } else {
                                        onUpdateAssistant(assistant.copy(enableMemory = true))
                                    }
                                }
                            )
                        }
                    )

                    AnimatedVisibility(
                        visible = assistant.enableMemory,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            var showStrategyMenu by remember { mutableStateOf(false) }
                            val currentStrategyLabel = when {
                                !assistant.useRagMemoryRetrieval -> stringResource(R.string.memory_mode_off)
                                else -> when (assistant.memoryRetrievalMode) {
                                    MemoryRetrievalMode.HYBRID -> stringResource(R.string.memory_mode_hybrid)
                                    MemoryRetrievalMode.KEYWORD -> stringResource(R.string.memory_mode_keyword)
                                    MemoryRetrievalMode.SEMANTIC -> stringResource(R.string.memory_mode_semantic)
                                    MemoryRetrievalMode.OFF -> stringResource(R.string.memory_mode_off)
                                }
                            }
                            MemorySettingsItem(
                                title = stringResource(R.string.memory_retrieval_strategy_title),
                                subtitle = stringResource(R.string.memory_retrieval_strategy_desc),
                                position = "MIDDLE",
                                onClick = { showStrategyMenu = true },
                                trailing = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = currentStrategyLabel,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Icon(
                                            Icons.Rounded.ArrowDropDown,
                                            null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        DropdownMenu(
                                            expanded = showStrategyMenu,
                                            onDismissRequest = { showStrategyMenu = false }) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.memory_mode_off)) },
                                                onClick = {
                                                    showStrategyMenu = false; onUpdateAssistant(
                                                    assistant.copy(
                                                        useRagMemoryRetrieval = false,
                                                        enableMemoryConsolidation = false
                                                    )
                                                )
                                                })
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.memory_mode_hybrid)) },
                                                onClick = {
                                                    showStrategyMenu = false; onUpdateAssistant(
                                                    assistant.copy(
                                                        useRagMemoryRetrieval = true,
                                                        memoryRetrievalMode = MemoryRetrievalMode.HYBRID
                                                    )
                                                )
                                                })
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.memory_mode_keyword)) },
                                                onClick = {
                                                    showStrategyMenu = false; onUpdateAssistant(
                                                    assistant.copy(
                                                        useRagMemoryRetrieval = true,
                                                        memoryRetrievalMode = MemoryRetrievalMode.KEYWORD
                                                    )
                                                )
                                                })
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.memory_mode_semantic)) },
                                                onClick = {
                                                    showStrategyMenu = false; onUpdateAssistant(
                                                    assistant.copy(
                                                        useRagMemoryRetrieval = true,
                                                        memoryRetrievalMode = MemoryRetrievalMode.SEMANTIC
                                                    )
                                                )
                                                })
                                        }
                                    }
                                }
                            )

                            MemorySettingsItem(
                                title = stringResource(R.string.assistant_page_recent_chats),
                                subtitle = stringResource(R.string.assistant_page_recent_chats_desc),
                                position = "MIDDLE",
                                trailing = {
                                    HapticSwitch(
                                        checked = assistant.enableRecentChatsReference,
                                        onCheckedChange = { onUpdateAssistant(assistant.copy(enableRecentChatsReference = it)) }
                                    )
                                }
                            )

                            if (assistant.useRagMemoryRetrieval) {
                                MemorySettingsItem(
                                    title = stringResource(R.string.assistant_memory_enable_consolidation_title),
                                    subtitle = stringResource(R.string.assistant_memory_enable_consolidation_desc),
                                    position = "MIDDLE",
                                    trailing = {
                                        HapticSwitch(
                                            checked = assistant.enableMemoryConsolidation,
                                            onCheckedChange = {
                                                onUpdateAssistant(
                                                    assistant.copy(
                                                        enableMemoryConsolidation = it
                                                    )
                                                )
                                            }
                                        )
                                    }
                                )
                            }

                            if (assistant.useRagMemoryRetrieval) {
                                Column {
                                    MemorySettingsItem(
                                        title = stringResource(R.string.detail_memory_title),
                                        subtitle = stringResource(R.string.detail_memory_desc),
                                        position = if (assistant.enableDetailMemory) "MIDDLE" else "LAST",
                                        trailing = {
                                            HapticSwitch(
                                                checked = assistant.enableDetailMemory,
                                                onCheckedChange = { onUpdateAssistant(assistant.copy(enableDetailMemory = it)) })
                                        }
                                    )
                                    AnimatedVisibility(visible = assistant.enableDetailMemory) {
                                        Surface(
                                            color = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
                                            shape = RoundedCornerShape(
                                                bottomStart = 24.dp,
                                                bottomEnd = 24.dp,
                                                topStart = 10.dp,
                                                topEnd = 10.dp
                                            )
                                        ) {
                                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                                Text(
                                                    text = stringResource(R.string.detail_memory_hint),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(bottom = 8.dp)
                                                )
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = stringResource(R.string.detail_memory_threshold),
                                                        style = MaterialTheme.typography.titleSmall
                                                    )
                                                    Text(
                                                        text = localDetailThreshold.toInt().toString(),
                                                        style = MaterialTheme.typography.labelLarge,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                                Slider(
                                                    value = localDetailThreshold,
                                                    onValueChange = {
                                                        if (it != localDetailThreshold) {
                                                            localDetailThreshold =
                                                                it; haptics.perform(HapticPattern.Pop)
                                                        }
                                                    },
                                                    onValueChangeFinished = {
                                                        onUpdateAssistant(
                                                            assistant.copy(
                                                                detailMemoryThreshold = localDetailThreshold.toInt()
                                                            )
                                                        )
                                                    },
                                                    valueRange = 10f..50f, steps = 3
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            AnimatedVisibility(
                visible = assistant.enableMemory && assistant.useRagMemoryRetrieval,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SettingsGroupHeader(title = stringResource(R.string.rag_settings_group))
                    RagSettingsCard(assistant = assistant, onUpdateAssistant = onUpdateAssistant)
                }
            }
        }

        item {
            AnimatedVisibility(
                visible = assistant.enableMemory,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SettingsGroupHeader(title = stringResource(R.string.assistant_memory_group_master))
                    MasterMemoryCard(
                        assistant = assistant,
                        onUpdateAssistant = onUpdateAssistant,
                        onConsolidate = {
                            assistantDetailVM.runManualConsolidation(
                                consolidateEpisodes = false,
                                updateMaster = true
                            )
                        }
                    )
                }
            }
        }

        item {
            AnimatedVisibility(
                visible = assistant.enableMemory && assistant.enableMemoryConsolidation,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SettingsGroupHeader(title = stringResource(R.string.assistant_memory_group_consolidation))
                    ConsolidationSettingsCard(
                        assistant = assistant,
                        onUpdateAssistant = onUpdateAssistant,
                        onConsolidate = {
                            assistantDetailVM.runManualConsolidation(
                                consolidateEpisodes = true,
                                updateMaster = false
                            )
                        },
                        showSummarizerWarning = assistant.summarizerModelId == null,
                        onNavigateToModels = onNavigateToModels
                    )
                }
            }
        }

        item {
            AnimatedVisibility(
                visible = assistant.enableMemory,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                MemoryStatisticsCard(
                    assistant = assistant,
                    memories = memories,
                    estimatedMemoryCapacity = estimatedMemoryCapacity
                )
            }
        }

        item {
            AnimatedVisibility(
                visible = assistant.enableMemory,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                ManageMemoriesSection(
                    memories = memories,
                    assistant = assistant,
                    onAddMemory = { memoryDialogState.open(AssistantMemory(0, "")) },
                    onEditMemory = { memoryDialogState.open(it) },
                    onSearchQueryChange = { assistantDetailVM.updateMemorySearchQuery(it) },
                    onDeleteMemory = onDeleteMemory,
                    onRegenerateEmbeddings = onRegenerateEmbeddings,
                    onOptimizeMemories = { assistantDetailVM.optimizeMemories() },
                    needsEmbeddingRegeneration = needsEmbeddingRegeneration,
                    memorySearchQuery = memorySearchQuery,
                    currentEmbeddingModelId = currentEmbeddingModelId,
                    showMemoryTypes = true,
                    initialMemoryTab = initialMemoryTab,
                    scrollToMemoryId = scrollToMemoryId
                )
            }
        }

        item {
            AnimatedVisibility(
                visible = assistant.enableMemory && assistant.useRagMemoryRetrieval && onTestRetrieval != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                if (onTestRetrieval != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SettingsGroupHeader(title = stringResource(R.string.memory_debugger_group))
                        MemoryDebugger(onTestRetrieval = onTestRetrieval, retrievalResults = retrievalResults)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsGroupHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
    )
}

@Composable
private fun MemorySettingsItem(
    title: String,
    subtitle: String? = null,
    position: String = "MIDDLE", // ONLY, FIRST, MIDDLE, LAST
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val haptics = rememberPremiumHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 400f),
        label = "scale"
    )

    val topCorner by animateDpAsState(
        targetValue = when (position) {
            "ONLY", "FIRST" -> 24.dp
            else -> 10.dp
        },
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 200f),
        label = "topCorner"
    )
    val bottomCorner by animateDpAsState(
        targetValue = when (position) {
            "ONLY", "LAST" -> 24.dp
            else -> 10.dp
        },
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 200f),
        label = "bottomCorner"
    )

    Surface(
        onClick = {
            if (onClick != null) {
                haptics.perform(HapticPattern.Pop)
                onClick()
            }
        },
        enabled = onClick != null,
        color = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(
            topStart = topCorner,
            topEnd = topCorner,
            bottomStart = bottomCorner,
            bottomEnd = bottomCorner
        ),
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (trailing != null) {
                trailing()
            }
        }
    }
}

@Composable
private fun MemoryModeIndicator(mode: MemoryMode) {
    val backgroundColor by animateColorAsState(
        targetValue = if (mode == MemoryMode.OFF)
            if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh
        else
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
        animationSpec = spring(),
        label = "modeColor"
    )

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = backgroundColor,
        modifier = Modifier.animateContentSize()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Psychology,
                contentDescription = null,
                tint = if (mode == MemoryMode.OFF)
                    MaterialTheme.colorScheme.onSurfaceVariant
                else
                    MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Column {
                AnimatedContent(
                    targetState = mode.displayNameRes,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "modeName"
                ) { nameRes ->
                    Text(
                        text = stringResource(R.string.memory_mode_label, stringResource(nameRes)),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                AnimatedContent(
                    targetState = mode.descriptionRes,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "modeDesc"
                ) { descRes ->
                    Text(
                        text = stringResource(descRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun RagSettingsCard(
    assistant: Assistant,
    onUpdateAssistant: (Assistant) -> Unit
) {
    var localLimit by remember(assistant.ragLimit) {
        mutableFloatStateOf(assistant.ragLimit.toFloat())
    }
    var localThreshold by remember(assistant.ragSimilarityThreshold) {
        mutableFloatStateOf(assistant.ragSimilarityThreshold)
    }
    val haptics = rememberPremiumHaptics()

    Column(
        modifier = Modifier.clip(RoundedCornerShape(24.dp)),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Surface(
            color = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 10.dp, bottomEnd = 10.dp)
        ) {
            Column {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.rag_limit_title), style = MaterialTheme.typography.titleMedium)
                        Text(text = localLimit.toInt().toString(), color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = localLimit,
                        onValueChange = {
                            if (it != localLimit) {
                                localLimit = it
                                haptics.perform(HapticPattern.Pop)
                            }
                        },
                        onValueChangeFinished = {
                            onUpdateAssistant(assistant.copy(ragLimit = localLimit.toInt()))
                        },
                        valueRange = 0f..10f,
                        steps = 10
                    )
                }

                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                stringResource(R.string.assistant_memory_similarity_threshold_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = String.format("%.2f", localThreshold),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = localThreshold,
                            onValueChange = { newValue ->
                                if (newValue != localThreshold) {
                                    localThreshold = newValue
                                    haptics.perform(HapticPattern.Pop)
                                }
                            },
                            onValueChangeFinished = {
                                onUpdateAssistant(assistant.copy(ragSimilarityThreshold = localThreshold))
                            },
                            valueRange = 0f..1f,
                            steps = 19,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                stringResource(R.string.similarity_all) + " (0.0)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                stringResource(R.string.similarity_exact) + " (1.0)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Surface(
            color = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp, topStart = 10.dp, topEnd = 10.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.rag_retrieval_scope),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )

                FormItem(
                    label = { Text(stringResource(R.string.rag_include_core)) },
                    tail = {
                        HapticSwitch(
                            checked = assistant.ragIncludeCore,
                            onCheckedChange = { onUpdateAssistant(assistant.copy(ragIncludeCore = it)) }
                        )
                    }
                )

                FormItem(
                    label = { Text(stringResource(R.string.rag_include_episodic)) },
                    tail = {
                        HapticSwitch(
                            checked = assistant.ragIncludeEpisodes,
                            onCheckedChange = { onUpdateAssistant(assistant.copy(ragIncludeEpisodes = it)) }
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun MasterMemoryCard(
    assistant: Assistant,
    onUpdateAssistant: (Assistant) -> Unit,
    onConsolidate: () -> Unit
) {
    var showBackupDialog by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    if (showBackupDialog) {
        AlertDialog(
            onDismissRequest = { showBackupDialog = false },
            title = { Text(stringResource(R.string.master_memory_backup_title)) },
            text = { Text(stringResource(R.string.master_memory_backup_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showBackupDialog = false
                    if (assistant.masterMemoryContent.isNotBlank()) {
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(
                                    ClipData.newPlainText(
                                        "Master Memory",
                                        assistant.masterMemoryContent
                                    )
                                )
                            )
                        }
                    }
                    onConsolidate()
                }) {
                    Text(stringResource(R.string.master_memory_backup_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBackupDialog = false }) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            }
        )
    }

    Column(
        modifier = Modifier.clip(RoundedCornerShape(24.dp)),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Surface(
            color = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = if (assistant.enableMasterMemory && (BuildConfig.DEBUG || FeatureConfig.enableMasterMemoryEditing))
                RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 10.dp, bottomEnd = 10.dp)
            else
                RoundedCornerShape(24.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(Icons.Rounded.AutoStories, null, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.assistant_memory_enable_master_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(R.string.assistant_memory_enable_master_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HapticSwitch(
                    checked = assistant.enableMasterMemory,
                    onCheckedChange = { onUpdateAssistant(assistant.copy(enableMasterMemory = it)) }
                )
            }
        }

        AnimatedVisibility(visible = assistant.enableMasterMemory && (BuildConfig.DEBUG || FeatureConfig.enableMasterMemoryEditing)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Surface(
                    color = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = if (BuildConfig.DEBUG || FeatureConfig.enableMasterMemoryEditing) RoundedCornerShape(10.dp) else RoundedCornerShape(
                        bottomStart = 24.dp,
                        bottomEnd = 24.dp,
                        topStart = 10.dp,
                        topEnd = 10.dp
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Rounded.HistoryEdu,
                                null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                            Text(
                                stringResource(R.string.assistant_memory_master_content_title),
                                style = MaterialTheme.typography.titleSmall
                            )
                        }

                        if (BuildConfig.DEBUG || FeatureConfig.enableMasterMemoryEditing) {
                            DebouncedTextField(
                                value = assistant.masterMemoryContent,
                                onValueChange = { onUpdateAssistant(assistant.copy(masterMemoryContent = it)) },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 3,
                                maxLines = 10,
                                stateKey = "master_content_${assistant.id}"
                            )
                        } else {
                            Text(
                                text = assistant.masterMemoryContent.ifBlank { stringResource(R.string.assistant_memory_master_never_updated) },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            )
                        }

                        if (assistant.lastMasterMemoryUpdate > 0) {
                            val time = java.time.Instant.ofEpochMilli(assistant.lastMasterMemoryUpdate)
                                .atZone(java.time.ZoneId.systemDefault())
                                .toLocalDateTime()
                                .toLocalString()
                            Text(
                                text = stringResource(R.string.assistant_memory_master_last_update, time),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else if (BuildConfig.DEBUG || FeatureConfig.enableMasterMemoryEditing) {
                            Text(
                                text = stringResource(R.string.assistant_memory_master_never_updated),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (BuildConfig.DEBUG || FeatureConfig.enableMasterMemoryEditing) {
                    Surface(
                        color = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(
                            bottomStart = 24.dp,
                            bottomEnd = 24.dp,
                            topStart = 10.dp,
                            topEnd = 10.dp
                        )
                    ) {
                        Box(modifier = Modifier.padding(16.dp)) {
                            Button(
                                onClick = {
                                    if (assistant.masterMemoryContent.isNotBlank()) {
                                        showBackupDialog = true
                                    } else {
                                        onConsolidate()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.assistant_memory_update_masterfile))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConsolidationSettingsCard(
    assistant: Assistant,
    onUpdateAssistant: (Assistant) -> Unit,
    onConsolidate: () -> Unit,
    showSummarizerWarning: Boolean = false,
    onNavigateToModels: () -> Unit = {}
) {
    var localDelay by remember(assistant.consolidationDelayMinutes) {
        mutableFloatStateOf(assistant.consolidationDelayMinutes.toFloat())
    }
    val haptics = rememberPremiumHaptics()

    Column(
        modifier = Modifier.clip(RoundedCornerShape(24.dp)),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        AnimatedVisibility(
            visible = showSummarizerWarning,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Surface(
                onClick = onNavigateToModels,
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 10.dp, bottomEnd = 10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = stringResource(R.string.summarizer_warning),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        Surface(
            color = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = if (showSummarizerWarning) {
                RoundedCornerShape(10.dp)
            } else {
                RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 10.dp, bottomEnd = 10.dp)
            }
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        stringResource(R.string.assistant_memory_consolidation_delay_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(R.string.assistant_memory_consolidation_delay_value, localDelay.toInt()),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = stringResource(R.string.assistant_memory_enable_consolidation_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = localDelay,
                    onValueChange = {
                        if (it != localDelay) {
                            localDelay = it
                            haptics.perform(HapticPattern.Pop)
                        }
                    },
                    onValueChangeFinished = {
                        onUpdateAssistant(assistant.copy(consolidationDelayMinutes = localDelay.toInt()))
                    },
                    valueRange = 30f..300f,
                    steps = 26,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        Surface(
            color = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp, topStart = 10.dp, topEnd = 10.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onConsolidate,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Psychology, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.assistant_memory_consolidate_now))
                }

                if (assistant.lastConsolidationTime > 0) {
                    val time = java.time.Instant.ofEpochMilli(assistant.lastConsolidationTime)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDateTime()
                        .toLocalString()
                    Text(
                        text = stringResource(R.string.assistant_memory_last_consolidation, time),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryStatisticsCard(
    assistant: Assistant,
    memories: List<AssistantMemory>,
    estimatedMemoryCapacity: Int
) {
    val coreMemories = memories.count { it.type == 0 }
    val episodicMemories = memories.count { it.type == 1 }
    val withEmbeddings = memories.count { it.hasEmbedding }

    Surface(
        color = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.memory_statistics),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                if (assistant.enableMemoryConsolidation) {
                    StatItem(
                        value = coreMemories.toString(),
                        label = stringResource(R.string.stat_core),
                        color = MaterialTheme.colorScheme.primary
                    )
                    StatItem(
                        value = episodicMemories.toString(),
                        label = stringResource(R.string.stat_episodic),
                        color = MaterialTheme.colorScheme.secondary
                    )
                } else {
                    StatItem(
                        value = memories.size.toString(),
                        label = stringResource(R.string.stat_total),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                AnimatedVisibility(visible = assistant.useRagMemoryRetrieval) {
                    StatItem(
                        value = withEmbeddings.toString(),
                        label = stringResource(R.string.stat_embedded),
                        color = if (withEmbeddings < memories.size)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            AnimatedVisibility(visible = assistant.useRagMemoryRetrieval) {
                Text(
                    text = stringResource(R.string.estimated_capacity, estimatedMemoryCapacity),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
private fun StatItem(
    value: String,
    label: String,
    color: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ManageMemoriesSection(
    memories: List<AssistantMemory>,
    assistant: Assistant,
    onAddMemory: () -> Unit,
    onEditMemory: (AssistantMemory) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onDeleteMemory: (AssistantMemory) -> Unit,
    onRegenerateEmbeddings: (() -> Unit)?,
    onOptimizeMemories: () -> Unit,
    needsEmbeddingRegeneration: Boolean,
    memorySearchQuery: String,
    currentEmbeddingModelId: String,
    showMemoryTypes: Boolean,
    initialMemoryTab: Int? = null,
    scrollToMemoryId: Int? = null
) {
    var selectedTab by remember { mutableIntStateOf(initialMemoryTab ?: 0) }
    var sortOrder by remember { mutableStateOf(MemorySortOrder.NEWEST_FIRST) }
    var showSortMenu by remember { mutableStateOf(false) }

    LaunchedEffect(initialMemoryTab) {
        if (initialMemoryTab != null) {
            selectedTab = initialMemoryTab
        }
    }

    LaunchedEffect(scrollToMemoryId, memories) {
        if (scrollToMemoryId != null && memories.isNotEmpty()) {
            val targetMemory = memories.find { it.id == scrollToMemoryId }
            if (targetMemory != null) {
                onEditMemory(targetMemory)
            }
        }
    }

    val coreMemories = memories.filter { it.type == 0 }
    val episodicMemories = memories.filter { it.type == 1 }

    val displayMemories = remember(memories, selectedTab, memorySearchQuery, sortOrder, showMemoryTypes) {
        val baseList = if (showMemoryTypes) {
            when (selectedTab) {
                0 -> coreMemories
                else -> episodicMemories
            }
        } else {
            memories
        }
        baseList.filter { memory ->
            memorySearchQuery.isBlank() || memory.content.contains(memorySearchQuery, ignoreCase = true)
        }.let { list ->
            when (sortOrder) {
                MemorySortOrder.NEWEST_FIRST -> list.sortedByDescending { it.timestamp }
                MemorySortOrder.OLDEST_FIRST -> list.sortedBy { it.timestamp }
                MemorySortOrder.ALPHABETICAL -> list.sortedBy { it.content.lowercase() }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.assistant_page_manage_memory_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (selectedTab == 0) {
                    IconButton(onClick = onOptimizeMemories) {
                        Icon(
                            Icons.Rounded.CleanHands,
                            contentDescription = stringResource(R.string.memory_action_optimize)
                        )
                    }
                }

                Box {
                    IconButton(onClick = { showSortMenu = true }) {
                        Icon(Icons.Rounded.Sort, contentDescription = stringResource(R.string.memory_sort_button_desc))
                    }
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false }
                    ) {
                        MemorySortOrder.entries.forEach { order ->
                            DropdownMenuItem(
                                text = { Text(stringResource(order.displayNameRes)) },
                                onClick = {
                                    sortOrder = order
                                    showSortMenu = false
                                },
                                leadingIcon = {
                                    if (sortOrder == order) {
                                        Icon(Icons.Rounded.Checklist, null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            )
                        }
                    }
                }

                if (onRegenerateEmbeddings != null && assistant.useRagMemoryRetrieval && needsEmbeddingRegeneration) {
                    IconButton(onClick = onRegenerateEmbeddings) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.memory_action_regenerate_embeddings)
                        )
                    }
                }
                IconButton(onClick = onAddMemory) {
                    Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.memory_action_add))
                }
            }
        }

        AnimatedVisibility(
            visible = showMemoryTypes,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                modifier = Modifier.clip(RoundedCornerShape(10.dp))
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("${stringResource(R.string.stat_core)} (${coreMemories.size})") },
                    icon = { Icon(Icons.Rounded.AutoAwesome, null, modifier = Modifier.size(18.dp)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("${stringResource(R.string.stat_episodic)} (${episodicMemories.size})") },
                    icon = { Icon(Icons.Rounded.History, null, modifier = Modifier.size(18.dp)) }
                )
            }
        }

        TextField(
            value = memorySearchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.search_memories)) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            )
        )

        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            displayMemories.forEachIndexed { index, memory ->
                key(memory.id) {
                    val position = when {
                        displayMemories.size == 1 -> "ONLY"
                        index == 0 -> "FIRST"
                        index == displayMemories.size - 1 -> "LAST"
                        else -> "MIDDLE"
                    }
                    MemoryItem(
                        memory = memory,
                        onEditMemory = onEditMemory,
                        onDeleteMemory = onDeleteMemory,
                        useRagMemoryRetrieval = assistant.useRagMemoryRetrieval,
                        currentEmbeddingModelId = currentEmbeddingModelId,
                        showType = showMemoryTypes,
                        position = position
                    )
                }
            }

            if (displayMemories.isEmpty()) {
                Surface(
                    color = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (memorySearchQuery.isBlank()) stringResource(R.string.no_memories_yet) else stringResource(
                            R.string.no_matching_memories
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryItem(
    memory: AssistantMemory,
    onEditMemory: (AssistantMemory) -> Unit,
    onDeleteMemory: (AssistantMemory) -> Unit,
    useRagMemoryRetrieval: Boolean = false,
    currentEmbeddingModelId: String = "",
    showType: Boolean = false,
    position: String = "MIDDLE"
) {
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    val haptics = rememberPremiumHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 400f),
        label = "scale"
    )

    val topCorner = when (position) {
        "ONLY", "FIRST" -> 24.dp
        else -> 10.dp
    }

    val bottomCorner = when (position) {
        "ONLY", "LAST" -> 24.dp
        else -> 10.dp
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(R.string.assistant_page_delete)) },
            text = {
                Text(
                    text = stringResource(R.string.delete_memory_confirmation) + "\n\n\"${memory.content.take(100)}${if (memory.content.length > 100) "..." else ""}\""
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        onDeleteMemory(memory)
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            }
        )
    }

    Surface(
        onClick = { onEditMemory(memory) },
        color = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(
            topStart = topCorner,
            topEnd = topCorner,
            bottomStart = bottomCorner,
            bottomEnd = bottomCorner
        ),
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (showType) {
                            Surface(
                                color = if (memory.type == 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                                shape = MaterialTheme.shapes.extraSmall
                            ) {
                                Text(
                                    text = if (memory.type == 0) {
                                        stringResource(R.string.memory_type_core)
                                    } else {
                                        val label = stringResource(R.string.memory_type_episodic)
                                        if (BuildConfig.DEBUG) "$label${memory.id}" else label
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                    color = if (memory.type == 0) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }

                        if (useRagMemoryRetrieval && !memory.hasEmbedding) {
                            Surface(
                                color = Color(0xFFC62828),
                                shape = MaterialTheme.shapes.extraSmall
                            ) {
                                Text(
                                    text = stringResource(R.string.no_embedding),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                    color = Color.White
                                )
                            }
                        }
                    }

                    if (memory.timestamp > 0) {
                        Text(
                            text = java.time.Instant.ofEpochMilli(memory.timestamp)
                                .atZone(java.time.ZoneId.systemDefault())
                                .toLocalDateTime()
                                .toLocalString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }
                }

                Text(
                    text = memory.content,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (memory.type == 0) {
                IconButton(onClick = {
                    haptics.perform(HapticPattern.Pop)
                    showDeleteConfirmation = true
                }) {
                    Icon(Icons.Rounded.Delete, stringResource(R.string.assistant_page_delete))
                }
            }
        }
    }
}

@Composable
private fun MemoryDebugger(
    onTestRetrieval: (String) -> Unit,
    retrievalResults: List<Pair<AssistantMemory, Float>>
) {
    val (query, setQuery) = remember { mutableStateOf("") }

    Surface(
        color = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.rag_debugger_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = query,
                    onValueChange = setQuery,
                    placeholder = { Text(stringResource(R.string.test_query_placeholder)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    )
                )
                Button(
                    onClick = { onTestRetrieval(query) },
                    enabled = query.isNotBlank()
                ) {
                    Text(stringResource(R.string.test_button))
                }
            }

            AnimatedVisibility(
                visible = retrievalResults.isNotEmpty(),
                enter = fadeIn() + expandVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.retrieval_results_count, retrievalResults.size),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    retrievalResults.forEachIndexed { index, (memory, score) ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "#${index + 1}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        stringResource(R.string.retrieval_score, String.format("%.4f", score)),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (score >= 0.5f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = memory.content,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FormItem(
    label: @Composable () -> Unit,
    description: (@Composable () -> Unit)? = null,
    tail: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Box {
                val style = MaterialTheme.typography.titleMedium
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalTextStyle provides style
                ) {
                    label()
                }
            }
            if (description != null) {
                Box(modifier = Modifier.padding(top = 2.dp)) {
                    val style =
                        MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.material3.LocalTextStyle provides style
                    ) {
                        description()
                    }
                }
            }
        }
        if (tail != null) {
            tail()
        }
    }
}
