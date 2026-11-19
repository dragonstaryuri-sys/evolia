package me.rerere.rikkahub.ui.pages.extension

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ProviderManager
import me.rerere.rikkahub.data.ai.tools.ToolDescriptionService
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.extensions.ExtensionRegistry
import me.rerere.rikkahub.data.extensions.ExtensionRules
import me.rerere.rikkahub.data.extensions.ExtensionType
import me.rerere.rikkahub.data.extensions.InstalledExtension
import me.rerere.rikkahub.data.extensions.ResolvedTool
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.theme.AppShapes
import org.koin.compose.koinInject

/**
 * 设置 → 小应用（2026-11-18 职责拆分）：只做运行控制——扩展/工具启用开关、
 * 工具描述编辑与 AI 简化（MCP 设置页工具管理同款能力，无基础设置）。
 * 创建 / 导入 / 权限 / 更新 / 卸载等开发管理功能在「发现 → 拓展中心」
 * （ExtensionWorkshopPage）。
 */
@Composable
fun SettingMiniAppsPage(
    navController: NavController,
) {
    val registry = koinInject<ExtensionRegistry>()
    val settingsStore = koinInject<SettingsStore>()
    val providerManager = koinInject<ProviderManager>()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val extensions by registry.installedExtensions.collectAsState()

    // 抽屉与工具编辑状态
    var sheetExtension by remember { mutableStateOf<InstalledExtension?>(null) }
    var editingTool by remember { mutableStateOf<ResolvedTool?>(null) }
    var editingDescription by remember { mutableStateOf("") }
    var simplifyingToolId by remember { mutableStateOf<String?>(null) }
    var simplifyPreview by remember { mutableStateOf<ResolvedTool?>(null) }
    var simplifyPreviewText by remember { mutableStateOf("") }

    fun simplifyTool(extension: InstalledExtension, tool: ResolvedTool) {
        val config = extension.toolConfigs[tool.toolId]
        simplifyingToolId = tool.toolId
        scope.launch {
            runCatching {
                ToolDescriptionService.simplifySingle(
                    settingsStore, providerManager,
                    (config?.customDescription ?: tool.description).ifBlank { tool.name },
                    kind = "扩展",
                )
            }.onSuccess { simplified ->
                simplifyingToolId = null
                simplifyPreview = tool
                simplifyPreviewText = simplified
            }.onFailure { e ->
                simplifyingToolId = null
                snackbarHostState.showSnackbar("简化失败：${e.message}")
            }
        }
    }

    Scaffold(
        topBar = {
            OneUITopAppBar(
                title = "小应用",
                scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
                navigationIcon = { BackButton() },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (extensions.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(80.dp))
                Text("暂无已安装扩展", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "前往 发现 → 拓展中心 创建或导入扩展",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            ) {
                items(extensions, key = { it.appId }) { extension ->
                    Card(
                        shape = AppShapes.CardMedium,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .clickable { sheetExtension = extension },
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        extension.name,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    TypeTag(extension.extensionType)
                                }
                                if (extension.description.isNotBlank()) {
                                    Text(
                                        extension.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                                val enabledTools = extension.tools.count {
                                    extension.toolConfigs[it.toolId]?.enable == true
                                }
                                Text(
                                    "${extension.appId} · v${extension.version} · $enabledTools/${extension.tools.size} tools",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            Switch(
                                checked = extension.isEnabled,
                                onCheckedChange = { enabled ->
                                    scope.launch {
                                        registry.setExtensionEnabled(extension.appId, enabled)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------- 工具管理抽屉（MCP 工具页签同款能力） ----------
    sheetExtension?.let { extension ->
        ModalBottomSheet(
            onDismissRequest = { sheetExtension = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = AppShapes.BottomSheet,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(600.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        extension.name,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = extension.isEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                registry.setExtensionEnabled(extension.appId, enabled)
                            }
                        },
                    )
                }
                if (extension.tools.isEmpty()) {
                    Text(
                        "该扩展没有提供工具",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(extension.tools, key = { it.toolId }) { tool ->
                        val config = extension.toolConfigs[tool.toolId]
                        val enabled = config?.enable ?: false
                        val effectiveDesc = ExtensionRules.effectiveDescription(
                            config?.customDescription, tool.description
                        )
                        Card(
                            shape = AppShapes.CardMedium,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            ),
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(tool.name, style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            tool.fullId,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        if (effectiveDesc.isNotBlank()) {
                                            Text(
                                                effectiveDesc,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(top = 4.dp),
                                            )
                                        }
                                    }
                                    Switch(
                                        checked = enabled,
                                        onCheckedChange = { value ->
                                            scope.launch {
                                                registry.setToolEnable(extension.appId, tool.toolId, value)
                                            }
                                        },
                                    )
                                }
                                Row {
                                    TextButton(onClick = {
                                        editingTool = tool
                                        editingDescription = config?.customDescription ?: tool.description
                                    }) {
                                        Text(if (config?.customDescription != null) "编辑描述" else "自定义描述")
                                    }
                                    TextButton(
                                        enabled = simplifyingToolId == null,
                                        onClick = { simplifyTool(extension, tool) },
                                    ) {
                                        Text(if (simplifyingToolId == tool.toolId) "简化中…" else "AI 简化")
                                    }
                                    if (config?.customDescription != null) {
                                        TextButton(onClick = {
                                            scope.launch {
                                                registry.setToolCustomDescription(extension.appId, tool.toolId, null)
                                            }
                                        }) {
                                            Text("恢复默认")
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

    // ---------- 描述编辑弹窗 ----------
    editingTool?.let { tool ->
        AlertDialog(
            onDismissRequest = { editingTool = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = AppShapes.CardMedium,
            title = { Text("自定义描述：${tool.name}") },
            text = {
                OutlinedTextField(
                    value = editingDescription,
                    onValueChange = { editingDescription = it },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val newDesc = editingDescription.trim()
                    scope.launch {
                        val appId = sheetExtension?.appId ?: return@launch
                        registry.setToolCustomDescription(
                            appId, tool.toolId,
                            newDesc.takeIf { it.isNotBlank() && it != tool.description },
                        )
                    }
                    editingTool = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editingTool = null }) { Text("取消") }
            },
        )
    }

    // ---------- AI 简化预览弹窗 ----------
    simplifyPreview?.let { tool ->
        AlertDialog(
            onDismissRequest = { simplifyPreview = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = AppShapes.CardMedium,
            title = { Text("简化描述：${tool.name}") },
            text = {
                OutlinedTextField(
                    value = simplifyPreviewText,
                    onValueChange = { simplifyPreviewText = it },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val appId = sheetExtension?.appId ?: return@launch
                        registry.setToolCustomDescription(appId, tool.toolId, simplifyPreviewText.trim())
                    }
                    simplifyPreview = null
                }) { Text("应用") }
            },
            dismissButton = {
                TextButton(onClick = { simplifyPreview = null }) { Text("取消") }
            },
        )
    }
}

@Composable
internal fun TypeTag(type: String) {
    val label = if (type == ExtensionType.MINI_APP) "Mini App" else "Tool"
    Tag(type = TagType.INFO) {
        Text(label)
    }
}
