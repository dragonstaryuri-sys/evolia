package me.rerere.rikkahub.ui.pages.extension

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.extensions.ExtensionInstaller
import me.rerere.rikkahub.data.extensions.ExtensionImportExport
import me.rerere.rikkahub.data.extensions.ExtensionRegistry
import me.rerere.rikkahub.data.extensions.ExtensionRules
import me.rerere.rikkahub.data.extensions.ExtensionType
import me.rerere.rikkahub.data.extensions.InstalledExtension
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.pages.miniapp.permissionDisplayName
import me.rerere.rikkahub.ui.theme.AppShapes
import org.koin.compose.koinInject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 扩展详情页（详设 13.4 Settings 侧）：Tool 全局管理（启用 / 描述编辑 /
 * 恢复默认）、权限授权管理、更新与卸载（数据保留选择）、导出。
 */
@Composable
fun ExtensionDetailPage(
    appId: String,
    navController: NavController,
) {
    val registry = koinInject<ExtensionRegistry>()
    val installer = koinInject<ExtensionInstaller>()
    val settingsStore = koinInject<me.rerere.rikkahub.data.datastore.SettingsStore>()
    val providerManager = koinInject<me.rerere.ai.provider.ProviderManager>()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val extensions by registry.installedExtensions.collectAsState()
    val extension = extensions.firstOrNull { it.appId == appId }
    val allPermissions = remember(extension) {
        (extension?.manifest?.permissions.orEmpty() + extension?.tools.orEmpty().flatMap { it.permissions }).distinct()
    }

    var editingTool by remember { mutableStateOf<me.rerere.rikkahub.data.extensions.ResolvedTool?>(null) }
    var editingDescription by remember { mutableStateOf("") }
    var showUninstallDialog by remember { mutableStateOf(false) }
    var uninstallDeleteData by remember { mutableStateOf(false) }
    var updating by remember { mutableStateOf(false) }
    var updateError by remember { mutableStateOf<String?>(null) }
    var simplifyingToolId by remember { mutableStateOf<String?>(null) }
    var simplifyPreview by remember { mutableStateOf<me.rerere.rikkahub.data.extensions.ResolvedTool?>(null) }
    var simplifyPreviewText by remember { mutableStateOf("") }

    val updateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            updating = true
            scope.launch {
                runCatching { ExtensionImportExport.importFromUri(context, it) }
                    .onSuccess { ext ->
                        updating = false
                        snackbarHostState.showSnackbar("已更新 ${ext.name} v${ext.version}")
                    }
                    .onFailure { e ->
                        updating = false
                        updateError = e.message ?: "更新失败"
                    }
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let {
            scope.launch {
                runCatching {
                    val tempFile = File(context.cacheDir, "export_${appId.takeLast(8)}.evolia")
                    ExtensionImportExport.exportToZip(context, appId, tempFile)
                    context.contentResolver.openOutputStream(it)?.use { out ->
                        tempFile.inputStream().use { it.copyTo(out) }
                    }
                    tempFile.delete()
                }.onSuccess {
                    snackbarHostState.showSnackbar("已导出扩展包")
                }.onFailure { e ->
                    snackbarHostState.showSnackbar("导出失败：${e.message}")
                }
            }
        }
    }

    if (extension == null) {
        Scaffold(
            topBar = {
                OneUITopAppBar(
                    title = "扩展详情",
                    scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
                    navigationIcon = { BackButton { navController.popBackStack() } },
                )
            },
        ) { padding ->
            Text(
                "扩展不存在或已被卸载",
                modifier = Modifier.padding(padding).padding(32.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Scaffold(
        topBar = {
            OneUITopAppBar(
                title = extension.name,
                scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
                navigationIcon = { BackButton { navController.popBackStack() } },
                actions = {
                    IconButton(
                        onClick = {
                            exportLauncher.launch("${extension.appId}_${extension.version}.evolia")
                        },
                    ) {
                        Icon(Icons.Rounded.FileUpload, contentDescription = "导出扩展包")
                    }
                    IconButton(onClick = { showUninstallDialog = true }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "卸载")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        ) {
            // ---------- 基本信息 ----------
            item {
                Card(
                    shape = AppShapes.CardMedium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                extension.name,
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = extension.isEnabled,
                                onCheckedChange = { enabled ->
                                    scope.launch { registry.setExtensionEnabled(appId, enabled) }
                                },
                            )
                        }
                        if (extension.description.isNotBlank()) {
                            Text(
                                extension.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        InfoRow("app_id", extension.appId)
                        InfoRow("版本", "v${extension.version}")
                        InfoRow("类型", if (extension.extensionType == ExtensionType.MINI_APP) "Mini App" else "Tool Extension")
                        InfoRow("来源", sourceLabel(extension.sourceType))
                        InfoRow(
                            "安装时间",
                            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                .format(Date(extension.installedAt))
                        )
                        if (extension.extensionType == ExtensionType.MINI_APP) {
                            Spacer(Modifier.height(8.dp))
                            TextButton(
                                onClick = { navController.navigate(Screen.MiniApp(appId)) },
                                enabled = extension.isEnabled,
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("打开小应用")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // ---------- Tool 管理（第二层权限） ----------
            if (extension.tools.isNotEmpty()) {
                item {
                    SectionTitle("工具（全局开关）")
                    Text(
                        "关闭后任何助手都不能调用该工具；独立 Tool Extension 的工具对所有助手开放，Mini App 内工具还需在助手详情中开启。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                items(extension.tools, key = { it.toolId }) { tool ->
                    val config = extension.toolConfigs[tool.toolId]
                    val enabled = config?.enable ?: false
                    val effectiveDesc = ExtensionRules.effectiveDescription(config?.customDescription, tool.description)
                    Card(
                        shape = AppShapes.CardMedium,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
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
                                        scope.launch { registry.setToolEnable(appId, tool.toolId, value) }
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
                                // AI 简化（共用 ToolDescriptionService，与 MCP 描述管理同款能力）
                                TextButton(
                                    enabled = simplifyingToolId == null,
                                    onClick = {
                                        simplifyingToolId = tool.toolId
                                        scope.launch {
                                            runCatching {
                                                me.rerere.rikkahub.data.ai.tools.ToolDescriptionService.simplifySingle(
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
                                    },
                                ) {
                                    Text(if (simplifyingToolId == tool.toolId) "简化中…" else "AI 简化")
                                }
                                if (config?.customDescription != null) {
                                    TextButton(onClick = {
                                        scope.launch { registry.setToolCustomDescription(appId, tool.toolId, null) }
                                    }) {
                                        Text("恢复默认")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---------- 权限管理 ----------
            if (extension.manifest.permissions.isNotEmpty() || extension.tools.any { it.permissions.isNotEmpty() }) {
                item {
                    Spacer(Modifier.height(8.dp))
                    SectionTitle("权限")
                    Text(
                        "Manifest 中声明的权限只是申请；授权后扩展才能使用对应原生能力。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                items(allPermissions, key = { it }) { permission ->
                    val granted = permission in extension.grantedPermissions
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(permissionDisplayName(permission), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                permission,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = granted,
                            onCheckedChange = { value ->
                                scope.launch { registry.grantPermission(appId, permission, value) }
                            },
                        )
                    }
                }
            }

            // ---------- 更新 ----------
            item {
                Spacer(Modifier.height(16.dp))
                TextButton(
                    onClick = { updateLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                    enabled = !updating,
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("更新（导入 .evolia 扩展包）")
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // ---------- Tool 描述编辑弹窗 ----------
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

    // ---------- AI 简化预览确认 ----------
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

    // ---------- 卸载确认（数据保留选择，详设 14.5） ----------
    if (showUninstallDialog) {
        AlertDialog(
            onDismissRequest = { showUninstallDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = AppShapes.CardMedium,
            title = { Text("卸载扩展") },
            text = {
                Column {
                    Text("是否卸载「${extension.name}」？")
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = uninstallDeleteData,
                            onCheckedChange = { uninstallDeleteData = it },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "同时删除应用数据（默认保留）",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (uninstallDeleteData) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showUninstallDialog = false
                    val deleteData = uninstallDeleteData
                    scope.launch {
                        runCatching { installer.uninstall(appId, deleteMiniAppData = deleteData) }
                            .onSuccess {
                                snackbarHostState.showSnackbar("已卸载")
                                navController.popBackStack()
                            }
                            .onFailure { e ->
                                snackbarHostState.showSnackbar("卸载失败：${e.message}")
                            }
                    }
                }) {
                    Text("卸载", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUninstallDialog = false }) { Text("取消") }
            },
        )
    }

    // ---------- 更新失败弹窗 ----------
    updateError?.let { error ->
        AlertDialog(
            onDismissRequest = { updateError = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = AppShapes.CardMedium,
            title = { Text("更新失败") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { updateError = null }) { Text("知道了") }
            },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium)
}

private fun sourceLabel(sourceType: String): String = when (sourceType) {
    "LOCAL_CREATED" -> "本地创建"
    "LOCAL_IMPORT" -> "本地导入"
    "GITHUB" -> "GitHub"
    "BUILTIN" -> "内置"
    else -> sourceType
}
