package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics

@Composable
fun AssistantExtendedEditPage(
    vm: AssistantDetailVM
) {
    val extendedState by vm.extendedState.collectAsStateWithLifecycle()
    val haptics = rememberPremiumHaptics()

    // 我们使用本地状态暂存修改，点击保存后再同步到 VM
    var localState by remember(extendedState) { mutableStateOf(extendedState) }
    val scrollState = rememberScrollState()

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    haptics.perform(HapticPattern.Success)
                    vm.updateExtendedState(localState)
                },
                icon = { Icon(Icons.Rounded.Save, null) },
                text = { Text(stringResource(R.string.save)) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding() // <--- 关键：增加 IME Padding 确保键盘弹出时不遮挡
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
                    onValueChange = { localState = localState.copy(personality = it) }
                )
                EditField(
                    label = stringResource(R.string.assistant_extended_interaction_habits),
                    value = localState.interactionHabits,
                    onValueChange = { localState = localState.copy(interactionHabits = it) }
                )
                EditField(
                    label = stringResource(R.string.assistant_extended_relationships),
                    value = localState.relationships,
                    onValueChange = { localState = localState.copy(relationships = it) }
                )
            }

            // ═══════════════════════════════════════════════════════════════════
            // 外貌细节
            // ═══════════════════════════════════════════════════════════════════
            SettingsGroup(
                title = stringResource(R.string.assistant_extended_appearance)
            ) {
                val app = localState.appearance

                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = app.hairColor,
                        onValueChange = { localState = localState.copy(appearance = app.copy(hairColor = it)) },
                        label = { Text(stringResource(R.string.assistant_extended_hair_color)) },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = app.hairCurliness,
                        onValueChange = { localState = localState.copy(appearance = app.copy(hairCurliness = it)) },
                        label = { Text(stringResource(R.string.assistant_extended_hair_curliness)) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = app.hairLength,
                        onValueChange = { localState = localState.copy(appearance = app.copy(hairLength = it)) },
                        label = { Text(stringResource(R.string.assistant_extended_hair_length)) },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = app.eyeColor,
                        onValueChange = { localState = localState.copy(appearance = app.copy(eyeColor = it)) },
                        label = { Text(stringResource(R.string.assistant_extended_eye_color)) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = app.eyelidType,
                        onValueChange = { localState = localState.copy(appearance = app.copy(eyelidType = it)) },
                        label = { Text(stringResource(R.string.assistant_extended_eyelid_type)) },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = app.eyelashLength,
                        onValueChange = { localState = localState.copy(appearance = app.copy(eyelashLength = it)) },
                        label = { Text(stringResource(R.string.assistant_extended_eyelash_length)) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = app.skinTone,
                        onValueChange = { localState = localState.copy(appearance = app.copy(skinTone = it)) },
                        label = { Text(stringResource(R.string.assistant_extended_skin_tone)) },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = if (app.height == 0) "" else app.height.toString(),
                        onValueChange = {
                            val h = it.toIntOrNull() ?: 0
                            localState = localState.copy(appearance = app.copy(height = h))
                        },
                        label = { Text(stringResource(R.string.assistant_extended_height)) },
                        modifier = Modifier.weight(1f)
                    )
                }

                // 数值滑块
                SliderField(
                    label = stringResource(R.string.assistant_extended_muscle),
                    value = app.muscle,
                    onValueChange = { localState = localState.copy(appearance = app.copy(muscle = it)) }
                )

                SliderField(
                    label = stringResource(R.string.assistant_extended_body_fat),
                    value = app.bodyFat,
                    onValueChange = { localState = localState.copy(appearance = app.copy(bodyFat = it)) }
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
                    onValueChange = { localState = localState.copy(preferences = it) }
                )
                EditField(
                    label = stringResource(R.string.assistant_extended_diet),
                    value = localState.diet,
                    onValueChange = { localState = localState.copy(diet = it) }
                )
                EditField(
                    label = stringResource(R.string.assistant_extended_taboos),
                    value = localState.taboos,
                    onValueChange = { localState = localState.copy(taboos = it) }
                )
            }

            Spacer(Modifier.height(100.dp))
        }
    }
}

@Composable
private fun EditField(label: String, value: String, onValueChange: (String) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
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
