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

    // Default template logic
    val defaultPrompt = remember(assistant.name, assistant.systemPrompt) {
        """
        You are ${assistant.name}. You are checking in on the user because they haven't messaged you for a while.

        [Persona/System Prompt]
        ${assistant.systemPrompt}

        [Context]
        - It has been {{idle_hours}} hours ({{idle_minutes}} minutes) since the last message in this conversation.
        - Recent chat history (last 4 messages):
        {{history}}

        [Relevant Memories]
        {{memories}}

        [Task]
        Based on your persona and the context, do you want to send a spontaneous message to the user?
        - If YES: Formulate a natural, concise message as if you're reaching out in the chat.
        - If NO: Explain why.

        [Output Format (Strict JSON)]
        {
            "send": true/false,
            "reason": "Why you decided to (not) send",
            "content": "The message text to send to the chat",
            "title": "Notification title (usually your name)"
        }
        """.trimIndent()
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

                    // Spontaneous Messaging Prompt (Integrated from AssistantNotificationSubPage)
                    Column(modifier = Modifier.padding(bottom = 12.dp)) {
                        SettingGroupItem(
                            title = "主动消息提示词",
                            subtitle = "定义角色在无触发情况下主动找你聊天时的指令。",
                            trailing = {
                                if (assistant.spontaneousPrompt.isNotBlank()) {
                                    IconButton(onClick = { onUpdate(assistant.copy(spontaneousPrompt = "")) }) {
                                        Icon(
                                            imageVector = Icons.Rounded.Refresh,
                                            contentDescription = "恢复默认",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        )
                        OutlinedTextField(
                            value = assistant.spontaneousPrompt.ifBlank { defaultPrompt },
                            onValueChange = {
                                // Only update if it's different from default to avoid saving default template as custom
                                if (it != defaultPrompt) {
                                    onUpdate(assistant.copy(spontaneousPrompt = it))
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            minLines = 5,
                            maxLines = 15,
                            placeholder = {
                                Text(
                                    text = "输入自定义指令...",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            textStyle = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "支持变量: {{history}}, {{memories}}, {{idle_hours}}, {{idle_minutes}}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
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
