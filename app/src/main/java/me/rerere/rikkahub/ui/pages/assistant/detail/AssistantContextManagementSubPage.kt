package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.core.data.model.Assistant
import me.rerere.rikkahub.service.DEFAULT_FULL_SUMMARY_PROMPT
import me.rerere.rikkahub.service.DEFAULT_TEMP_SUMMARY_PROMPT
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupItem
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import kotlin.math.roundToInt

@Composable
fun AssistantContextManagementSubPage(
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit,
    onNavigateToLorebooks: () -> Unit,
    onNavigateToModels: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // ═══════════════════════════════════════════════════════════════════
        // LOREBOOKS
        // ═══════════════════════════════════════════════════════════════════
        SettingsGroup(title = stringResource(R.string.context_lorebooks_title)) {
            SettingGroupItem(
                title = stringResource(R.string.assistant_lorebooks_title),
                subtitle = stringResource(R.string.context_lorebooks_desc),
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.Book,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                onClick = onNavigateToLorebooks
            )
        }

        // ═══════════════════════════════════════════════════════════════════
        // MESSAGE HISTORY & SUMMARIZATION
        // ═══════════════════════════════════════════════════════════════════

        SettingsGroup(title = stringResource(R.string.context_message_history_title)) {
            // 历史消息上限 (始终显示)
            val historyLimit = assistant.maxHistoryMessages ?: 0
            var sliderValue by remember(historyLimit) { mutableFloatStateOf(historyLimit.toFloat()) }

            SliderSettingCard(
                title = if (sliderValue.roundToInt() == 0) {
                    stringResource(R.string.context_max_messages_unlimited)
                } else {
                    stringResource(R.string.assistant_context_history_limit_title)
                },
                value = sliderValue,
                valueText = if (sliderValue.roundToInt() == 0) "" else stringResource(R.string.assistant_context_history_limit_value, sliderValue.roundToInt()),
                description = if (assistant.enableContextRefresh && assistant.autoRegenerateSummary) {
                    stringResource(R.string.assistant_context_history_limit_desc)
                } else {
                    stringResource(R.string.context_max_messages_desc)
                },
                onValueChange = { sliderValue = it },
                onValueChangeFinished = {
                    val newValue = sliderValue.roundToInt()
                    onUpdate(assistant.copy(
                        maxHistoryMessages = if (newValue == 0) null else newValue
                    ))
                },
                valueRange = 0f..100f,
                steps = 99
            )

            val needsSummarizerWarning = assistant.enableContextRefresh && assistant.summarizerModelId == null
            AnimatedVisibility(
                visible = needsSummarizerWarning,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                SummarizerWarningBanner(onClick = onNavigateToModels)
            }

            SettingGroupItem(
                title = stringResource(R.string.assistant_context_refresh_title),
                subtitle = stringResource(R.string.assistant_context_refresh_desc),
                trailing = {
                    HapticSwitch(
                        checked = assistant.enableContextRefresh,
                        onCheckedChange = { enabled ->
                            onUpdate(assistant.copy(
                                enableContextRefresh = enabled,
                                autoRegenerateSummary = if (!enabled) false else assistant.autoRegenerateSummary
                            ))
                        }
                    )
                },
                onClick = {
                    val newEnabled = !assistant.enableContextRefresh
                    onUpdate(assistant.copy(
                        enableContextRefresh = newEnabled,
                        autoRegenerateSummary = if (!newEnabled) false else assistant.autoRegenerateSummary
                    ))
                }
            )

            AnimatedVisibility(
                visible = assistant.enableContextRefresh,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                SettingGroupItem(
                    title = stringResource(R.string.assistant_context_auto_summarize_title),
                    subtitle = stringResource(R.string.assistant_context_auto_summarize_desc),
                    trailing = {
                        HapticSwitch(
                            checked = assistant.autoRegenerateSummary,
                            onCheckedChange = { enabled ->
                                onUpdate(assistant.copy(autoRegenerateSummary = enabled))
                            }
                        )
                    },
                    onClick = {
                        onUpdate(assistant.copy(autoRegenerateSummary = !assistant.autoRegenerateSummary))
                    }
                )
            }

            AnimatedVisibility(
                visible = assistant.enableContextRefresh,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                val tempLimit = assistant.maxTemporarySummariesToInclude
                var tempSliderValue by remember(tempLimit) { mutableFloatStateOf(tempLimit.toFloat()) }

                SliderSettingCard(
                    title = stringResource(R.string.assistant_context_episodic_limit_title),
                    value = tempSliderValue,
                    valueText = stringResource(R.string.assistant_context_episodic_limit_value, tempSliderValue.roundToInt()),
                    description = stringResource(R.string.assistant_context_episodic_limit_desc),
                    onValueChange = { tempSliderValue = it },
                    onValueChangeFinished = {
                        val newValue = tempSliderValue.roundToInt()
                        onUpdate(assistant.copy(maxTemporarySummariesToInclude = newValue))
                    },
                    valueRange = 0f..20f,
                    steps = 20
                )
            }
        }

        // ═══════════════════════════════════════════════════════════════════
        // MEMORY RETRIEVAL
        // ═══════════════════════════════════════════════════════════════════
        AnimatedVisibility(
            visible = assistant.enableMemory && assistant.useRagMemoryRetrieval,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            SettingsGroup(title = stringResource(R.string.assistant_memory_group_settings)) {
                val ragLimit = assistant.ragLimit
                var ragSliderValue by remember(ragLimit) { mutableFloatStateOf(ragLimit.toFloat()) }

                SliderSettingCard(
                    title = stringResource(R.string.assistant_memory_rag_limit_title),
                    value = ragSliderValue,
                    valueText = stringResource(R.string.assistant_memory_rag_limit_value, ragSliderValue.roundToInt()),
                    description = stringResource(R.string.assistant_memory_rag_limit_desc),
                    onValueChange = { ragSliderValue = it },
                    onValueChangeFinished = {
                        val newValue = ragSliderValue.roundToInt()
                        onUpdate(assistant.copy(ragLimit = newValue))
                    },
                    valueRange = 1f..50f,
                    steps = 49
                )
            }
        }

        // ═══════════════════════════════════════════════════════════════════
        // SEARCH RESULTS
        // ═══════════════════════════════════════════════════════════════════
        SettingsGroup(title = stringResource(R.string.context_search_results_title)) {
            val maxSearchResults = assistant.maxSearchResultsRetained ?: 0
            var searchSliderValue by remember(maxSearchResults) { mutableFloatStateOf(maxSearchResults.toFloat()) }

            SliderSettingCard(
                title = if (searchSliderValue.roundToInt() == 0) {
                    stringResource(R.string.context_max_search_results_unlimited)
                } else {
                    stringResource(R.string.context_max_search_results, searchSliderValue.roundToInt())
                },
                value = searchSliderValue,
                valueText = "",
                description = stringResource(R.string.context_max_search_results_desc),
                onValueChange = { searchSliderValue = it },
                onValueChangeFinished = {
                    val newValue = searchSliderValue.roundToInt()
                    onUpdate(assistant.copy(
                        maxSearchResultsRetained = if (newValue == 0) null else newValue
                    ))
                },
                valueRange = 0f..50f,
                steps = 49
            )
        }

        // ═══════════════════════════════════════════════════════════════════
        // CUSTOM PROMPTS
        // ═══════════════════════════════════════════════════════════════════
        AnimatedVisibility(
            visible = assistant.enableContextRefresh,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            SettingsGroup(title = stringResource(R.string.assistant_context_custom_prompts_title)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Full Summary Prompt
                    Column {
                        OutlinedTextField(
                            value = assistant.fullSummaryPrompt.ifBlank { DEFAULT_FULL_SUMMARY_PROMPT },
                            onValueChange = { onUpdate(assistant.copy(fullSummaryPrompt = it)) },
                            label = { Text(stringResource(R.string.assistant_context_full_summary_prompt_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3
                        )
                        VariableHintRow(
                            variables = listOf("{{previous_summary}}", "{{new_messages}}", "{{locale}}"),
                            onVariableClick = { v ->
                                onUpdate(assistant.copy(fullSummaryPrompt = assistant.fullSummaryPrompt + v))
                            }
                        )
                    }

                    // Temporary Summary Prompt
                    Column {
                        OutlinedTextField(
                            value = assistant.temporarySummaryPrompt.ifBlank { DEFAULT_TEMP_SUMMARY_PROMPT },
                            onValueChange = { onUpdate(assistant.copy(temporarySummaryPrompt = it)) },
                            label = { Text(stringResource(R.string.assistant_context_episodic_summary_prompt_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3
                        )
                        VariableHintRow(
                            variables = listOf("{{new_messages}}", "{{locale}}"),
                            onVariableClick = { v ->
                                onUpdate(assistant.copy(temporarySummaryPrompt = assistant.temporarySummaryPrompt + v))
                            }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VariableHintRow(
    variables: List<String>,
    onVariableClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(top = 8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(bottom = 4.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Info,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.assistant_page_available_variables),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            variables.forEach { variable ->
                AssistChip(
                    onClick = { onVariableClick(variable) },
                    label = {
                        Text(
                            text = variable,
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        labelColor = MaterialTheme.colorScheme.primary
                    ),
                    border = null,
                    modifier = Modifier.height(28.dp)
                )
            }
        }
    }
}

@Composable
private fun SummarizerWarningBanner(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
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
                text = stringResource(R.string.assistant_context_no_summarizer_warning),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun SliderSettingCard(
    title: String,
    value: Float,
    valueText: String,
    description: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int
) {
    Surface(
        color = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (valueText.isNotEmpty()) {
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = value,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                valueRange = valueRange,
                steps = steps
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
