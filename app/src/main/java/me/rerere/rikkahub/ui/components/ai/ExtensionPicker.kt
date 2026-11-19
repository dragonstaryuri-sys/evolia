package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.core.data.model.Assistant
import me.rerere.rikkahub.data.extensions.ExtensionRegistry
import me.rerere.rikkahub.data.extensions.ExtensionType
import me.rerere.rikkahub.data.extensions.InstalledExtension
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import me.rerere.rikkahub.ui.components.ui.ToggleSurface
import me.rerere.rikkahub.ui.theme.AppShapes
import me.rerere.rikkahub.ui.theme.LocalDarkMode

/**
 * 助手详情「MiniApps & Tools」入口按钮（McpPickerButton 同款交互）：
 * ToggleSurface 图标 + 徽标 = 当前助手已启用的扩展数量；点击弹出抽屉。
 */
@Composable
fun ExtensionPickerButton(
    assistant: Assistant,
    registry: ExtensionRegistry,
    modifier: Modifier = Modifier,
    onUpdateAssistant: (Assistant) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    val installed by registry.installedExtensions.collectAsStateWithLifecycle()
    val enabledCount = installed.count {
        it.appId in assistant.enabledMiniAppIds || it.appId in assistant.enabledToolExtensionIds
    }
    ToggleSurface(
        modifier = modifier,
        checked = enabledCount > 0,
        onClick = { showPicker = true },
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BadgedBox(
                badge = {
                    if (enabledCount > 0) {
                        Badge(containerColor = MaterialTheme.colorScheme.tertiaryContainer) {
                            Text(text = enabledCount.toString())
                        }
                    }
                }
            ) {
                Icon(imageVector = Icons.Rounded.Apps, contentDescription = "MiniApps & Tools")
            }
        }
    }
    if (showPicker) {
        ModalBottomSheet(
            onDismissRequest = { showPicker = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = AppShapes.BottomSheet,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.7f)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "MiniApps & Tools",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                ExtensionPicker(
                    assistant = assistant,
                    registry = registry,
                    installed = installed,
                    onUpdateAssistant = onUpdateAssistant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }
    }
}

/**
 * 抽屉内容：Mini App 与 Tool Extension 分组列出，每个扩展一张开关卡片
 * （McpPicker 同款样式）。开关写入对应的助手级授权集合。
 */
@Composable
fun ExtensionPicker(
    assistant: Assistant,
    registry: ExtensionRegistry,
    installed: List<InstalledExtension>,
    modifier: Modifier = Modifier,
    onUpdateAssistant: (Assistant) -> Unit,
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val miniApps = installed.filter { it.extensionType == ExtensionType.MINI_APP }
        val toolExts = installed.filter { it.extensionType == ExtensionType.TOOL }
        if (miniApps.isNotEmpty()) {
            item {
                Text(
                    text = "Mini Apps",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        items(miniApps, key = { it.appId }) { ext ->
            ExtensionPickerCard(
                extension = ext,
                icon = { Icon(Icons.Rounded.Apps, contentDescription = null) },
                enabled = assistant.enabledMiniAppIds.contains(ext.appId),
                onToggle = { value ->
                    val newIds = if (value) {
                        assistant.enabledMiniAppIds + ext.appId
                    } else {
                        assistant.enabledMiniAppIds - ext.appId
                    }
                    onUpdateAssistant(assistant.copy(enabledMiniAppIds = newIds))
                },
            )
        }
        if (toolExts.isNotEmpty()) {
            item {
                Text(
                    text = "Tools",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        items(toolExts, key = { it.appId }) { ext ->
            ExtensionPickerCard(
                extension = ext,
                icon = { Icon(Icons.Rounded.Build, contentDescription = null) },
                enabled = assistant.enabledToolExtensionIds.contains(ext.appId),
                onToggle = { value ->
                    val newIds = if (value) {
                        assistant.enabledToolExtensionIds + ext.appId
                    } else {
                        assistant.enabledToolExtensionIds - ext.appId
                    }
                    onUpdateAssistant(assistant.copy(enabledToolExtensionIds = newIds))
                },
            )
        }
        if (miniApps.isEmpty() && toolExts.isEmpty()) {
            item {
                Text(
                    text = "No extensions installed",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun ExtensionPickerCard(
    extension: InstalledExtension,
    icon: @Composable () -> Unit,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        shape = AppShapes.CardLarge,
        colors = CardDefaults.cardColors(
            containerColor = if (LocalDarkMode.current) {
                MaterialTheme.colorScheme.surfaceContainerLowest
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            }
        ),
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = extension.name,
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = extension.description.ifBlank { extension.appId },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                )
            }
            HapticSwitch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}
