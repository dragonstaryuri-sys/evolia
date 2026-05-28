package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.core.data.model.Assistant
import me.rerere.rikkahub.core.data.model.Avatar
import me.rerere.rikkahub.ui.components.ui.DebouncedTextField
import me.rerere.rikkahub.ui.components.ui.TagsInput
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupItem
import me.rerere.rikkahub.core.data.model.Tag as DataTag

/**
 * Profile tab - Assistant identity and appearance settings.
 */
@Composable
fun AssistantProfileSubPage(
    assistant: Assistant,
    tags: List<DataTag>,
    onUpdate: (Assistant) -> Unit,
    vm: AssistantDetailVM,
    onNavigateToExtendedEdit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // ═══════════════════════════════════════════════════════════════════
        // AVATAR SECTION
        // ═══════════════════════════════════════════════════════════════════
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            UIAvatar(
                value = assistant.avatar,
                name = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                onUpdate = { avatar ->
                    onUpdate(assistant.copy(avatar = avatar))
                },
                modifier = Modifier.size(96.dp)
            )

            Text(
                text = "点击更换头像",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // ═══════════════════════════════════════════════════════════════════
        // IDENTITY GROUP
        // ═══════════════════════════════════════════════════════════════════
        SettingsGroup(title = "身份标识") {
            SettingGroupItem(
                title = stringResource(R.string.assistant_page_name),
                subtitle = "智能体的显示名称",
                trailing = {
                    DebouncedTextField(
                        value = assistant.name,
                        onValueChange = { onUpdate(assistant.copy(name = it)) },
                        stateKey = assistant.id,
                        modifier = Modifier.fillMaxWidth(0.5f),
                        singleLine = true
                    )
                }
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = if (me.rerere.rikkahub.ui.theme.LocalDarkMode.current)
                    MaterialTheme.colorScheme.surfaceContainerLow
                else
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.assistant_page_tags),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    TagsInput(
                        value = assistant.tags,
                        tags = tags,
                        onValueChange = { tagIds, updatedTags ->
                            vm.updateTags(tagIds, updatedTags)
                        },
                    )
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════════
        // APPEARANCE GROUP
        // ═══════════════════════════════════════════════════════════════════
        SettingsGroup(title = "外观与展示") {
            val hasImageSource = assistant.background != null ||
                assistant.avatar is Avatar.Image ||
                assistant.avatar is Avatar.Resource

            if (hasImageSource) {
                SettingGroupItem(
                    title = stringResource(R.string.assistant_page_material_you_from_character),
                    subtitle = stringResource(R.string.assistant_page_material_you_from_character_desc),
                    trailing = {
                        HapticSwitch(
                            checked = assistant.useAssistantMaterialYouColors,
                            onCheckedChange = { enabled ->
                                onUpdate(assistant.copy(useAssistantMaterialYouColors = enabled))
                            }
                        )
                    }
                )
            }

            // Background Picker
            BackgroundPicker(
                background = assistant.background,
                backgroundDim = assistant.backgroundDim,
                onUpdate = { background ->
                    onUpdate(assistant.copy(background = background))
                },
                onDimChange = { dim ->
                    onUpdate(assistant.copy(backgroundDim = dim))
                }
            )
        }

        // ═══════════════════════════════════════════════════════════════════
        // EXTENDED PERSONALITY GROUP
        // ═══════════════════════════════════════════════════════════════════
        // 逻辑：主智能体始终显示，其他智能体仅在 DEBUG 环境下显示
        if (assistant.isMain || BuildConfig.DEBUG) {
            SettingsGroup(title = stringResource(R.string.assistant_advanced_extended_state_title)) {
                SettingGroupItem(
                    title = stringResource(R.string.assistant_advanced_extended_state_title),
                    subtitle = stringResource(R.string.assistant_advanced_extended_state_desc),
                    icon = {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailing = {
                        HapticSwitch(
                            checked = assistant.hasExtendedState,
                            enabled = true, // 允许手动开启或关闭
                            onCheckedChange = {
                                onUpdate(assistant.copy(hasExtendedState = it))
                            }
                        )
                    }
                )

                AnimatedVisibility(
                    visible = assistant.hasExtendedState,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    SettingGroupItem(
                        title = stringResource(R.string.assistant_advanced_extended_edit_entry_title),
                        subtitle = stringResource(R.string.assistant_advanced_extended_edit_entry_desc),
                        onClick = onNavigateToExtendedEdit
                    )
                }
            }
        }
    }
}
