package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.core.data.db.entity.ProfileHistoryEntity
import me.rerere.rikkahub.ui.pages.setting.components.FieldHistorySection
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import me.rerere.rikkahub.ui.pages.setting.components.groupByField

@Composable
fun AssistantExtendedEditPage(
    vm: AssistantDetailVM
) {
    val extendedState by vm.extendedState.collectAsStateWithLifecycle()
    val assistantProfileHistory by vm.assistantProfileHistory.collectAsStateWithLifecycle()

    // 使用本地状态暂存修改
    var localState by remember(extendedState) { mutableStateOf(extendedState) }
    val scrollState = rememberScrollState()

    // 把扁平的历史记录按 fieldKey 分组，便于每个字段下方独立渲染
    val historyByField = remember(assistantProfileHistory) { groupByField(assistantProfileHistory) }

    // 关键逻辑：退出页面（Composable 被销毁）时自动保存
    val currentState by rememberUpdatedState(localState)
    DisposableEffect(vm) {
        onDispose {
            vm.updateExtendedState(currentState)
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(scrollState)
        ) {
            // ═══════════════════════════════════════════════════════════════════
            // 基本人格
            // ═══════════════════════════════════════════════════════════════════
            SettingsGroup(
                title = stringResource(R.string.assistant_extended_core_personality)
            ) {
                EditField(
                    label = stringResource(R.string.assistant_extended_personality),
                    value = localState.personality,
                    onValueChange = { localState = localState.copy(personality = it) },
                    history = historyByField["personality"].orEmpty()
                )
                EditField(
                    label = stringResource(R.string.assistant_extended_interaction_habits),
                    value = localState.interactionHabits,
                    onValueChange = { localState = localState.copy(interactionHabits = it) },
                    history = historyByField["interaction_habits"].orEmpty()
                )
                EditField(
                    label = stringResource(R.string.assistant_extended_relationships),
                    value = localState.relationships,
                    onValueChange = { localState = localState.copy(relationships = it) },
                    history = historyByField["relationships"].orEmpty()
                )
            }

            // ═══════════════════════════════════════════════════════════════════
            // 外貌（自由文本，整合成一个字段便于 AI 更新）
            // ═══════════════════════════════════════════════════════════════════
            SettingsGroup(
                title = stringResource(R.string.assistant_extended_appearance)
            ) {
                EditField(
                    label = stringResource(R.string.assistant_extended_appearance),
                    value = localState.appearance,
                    onValueChange = { localState = localState.copy(appearance = it) },
                    history = historyByField["appearance"].orEmpty()
                )
            }

            // ═══════════════════════════════════════════════════════════════════
            // 喜好与禁忌
            // ═══════════════════════════════════════════════════════════════════
            SettingsGroup(
                title = stringResource(R.string.assistant_extended_life_habits)
            ) {
                EditField(
                    label = stringResource(R.string.assistant_extended_preferences),
                    value = localState.preferences,
                    onValueChange = { localState = localState.copy(preferences = it) },
                    history = historyByField["preferences"].orEmpty()
                )
                EditField(
                    label = stringResource(R.string.assistant_extended_diet),
                    value = localState.diet,
                    onValueChange = { localState = localState.copy(diet = it) },
                    history = historyByField["diet"].orEmpty()
                )
                EditField(
                    label = stringResource(R.string.assistant_extended_taboos),
                    value = localState.taboos,
                    onValueChange = { localState = localState.copy(taboos = it) },
                    history = historyByField["taboos"].orEmpty()
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun EditField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    history: List<ProfileHistoryEntity> = emptyList()
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
        // 该字段的历史版本（默认收起，可展开复制旧值）
        FieldHistorySection(records = history)
    }
}

@Composable
private fun SliderField(label: String, value: Int, onValueChange: (Int) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value.toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = 0f..100f,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
