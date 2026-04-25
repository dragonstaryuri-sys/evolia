package me.rerere.rikkahub.ui.pages.assistant.detail

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.core.data.model.Assistant
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupItem
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.core.data.model.ContextPriority

/**
 * Advanced tab - Notifications and custom request settings.
 */
@Composable
fun AssistantAdvancedSubPage(
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit,
    onNavigateToAgentTasks: () -> Unit
) {
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        onUpdate(assistant.copy(enableSpontaneous = true))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // ═══════════════════════════════════════════════════════════════════
        // AUTOMATION GROUP
        // ═══════════════════════════════════════════════════════════════════
        SettingsGroup(title = stringResource(R.string.agent_automation_title)) {
            SettingGroupItem(
                title = stringResource(R.string.agent_task_manager),
                subtitle = stringResource(R.string.agent_task_manager_desc),
                onClick = {
                    onNavigateToAgentTasks()
                }
            )
        }

        // ═══════════════════════════════════════════════════════════════════
        // NOTIFICATIONS GROUP
        // ═══════════════════════════════════════════════════════════════════
        SettingsGroup(title = stringResource(R.string.assistant_advanced_group_spontaneous)) {
            SettingGroupItem(
                title = stringResource(R.string.assistant_advanced_enable_spontaneous_title),
                subtitle = stringResource(R.string.assistant_advanced_enable_spontaneous_desc),
                trailing = {
                    HapticSwitch(
                        checked = assistant.enableSpontaneous,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    onUpdate(assistant.copy(enableSpontaneous = true))
                                }
                            } else {
                                onUpdate(assistant.copy(enableSpontaneous = false))
                            }
                        }
                    )
                }
            )

            AnimatedVisibility(
                visible = assistant.enableSpontaneous,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SettingGroupItem(
                        title = stringResource(R.string.assistant_advanced_active_hours_title),
                        subtitle = stringResource(R.string.assistant_advanced_active_hours_desc),
                        trailing = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = assistant.notificationStartHour.toString(),
                                    onValueChange = {
                                        val hour = it.toIntOrNull()?.coerceIn(0, 23) ?: 7
                                        onUpdate(assistant.copy(notificationStartHour = hour))
                                    },
                                    label = { Text(stringResource(R.string.assistant_advanced_start_label)) },
                                    modifier = Modifier.width(70.dp),
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodySmall
                                )
                                OutlinedTextField(
                                    value = assistant.notificationEndHour.toString(),
                                    onValueChange = {
                                        val hour = it.toIntOrNull()?.coerceIn(0, 23) ?: 22
                                        onUpdate(assistant.copy(notificationEndHour = hour))
                                    },
                                    label = { Text(stringResource(R.string.assistant_advanced_end_label)) },
                                    modifier = Modifier.width(70.dp),
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    )

                    SettingGroupItem(
                        title = stringResource(R.string.assistant_advanced_frequency_title),
                        subtitle = stringResource(R.string.assistant_advanced_frequency_desc),
                        trailing = {
                            OutlinedTextField(
                                value = assistant.notificationFrequencyHours.toString(),
                                onValueChange = {
                                    val hours = it.toIntOrNull()?.coerceAtLeast(1) ?: 4
                                    onUpdate(assistant.copy(notificationFrequencyHours = hours))
                                },
                                modifier = Modifier.width(70.dp),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall,
                                suffix = { Text("h") }
                            )
                        }
                    )

                }
            }
        }

        // ═══════════════════════════════════════════════════════════════════
        // CUSTOM REQUEST GROUP
        // ═══════════════════════════════════════════════════════════════════
        SettingsGroup(title = stringResource(R.string.assistant_advanced_group_custom_request)) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = if (LocalDarkMode.current)
                    MaterialTheme.colorScheme.surfaceContainerLow
                else
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    CustomHeaders(
                        headers = assistant.customHeaders,
                        onUpdate = { onUpdate(assistant.copy(customHeaders = it)) }
                    )
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = if (LocalDarkMode.current)
                    MaterialTheme.colorScheme.surfaceContainerLow
                else
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    CustomBodies(
                        customBodies = assistant.customBodies,
                        onUpdate = { onUpdate(assistant.copy(customBodies = it)) }
                    )
                }
            }
        }
        // ═══════════════════════════════════════════════════════════════════
        // ADVANCED CONTEXT SETTINGS
        // ═══════════════════════════════════════════════════════════════════
        SettingsGroup(title = stringResource(R.string.assistant_context_priority_group)) {
            SettingGroupItem(
                title = stringResource(R.string.assistant_context_priority_title),
                subtitle = when (assistant.contextPriority) {
                    ContextPriority.CHAT_HISTORY -> stringResource(R.string.assistant_context_priority_chat)
                    ContextPriority.MEMORIES -> stringResource(R.string.assistant_context_priority_memory)
                    ContextPriority.BALANCED -> stringResource(R.string.assistant_context_priority_balanced)
                },
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.PriorityHigh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                onClick = {
                    val nextPriority = when (assistant.contextPriority) {
                        ContextPriority.BALANCED -> ContextPriority.CHAT_HISTORY
                        ContextPriority.CHAT_HISTORY -> ContextPriority.MEMORIES
                        ContextPriority.MEMORIES -> ContextPriority.BALANCED
                    }
                    onUpdate(assistant.copy(contextPriority = nextPriority))
                }
            )

            Text(
                text = stringResource(R.string.assistant_context_priority_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}
