package me.rerere.rikkahub.ui.pages.extension

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Input
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.RadioButton
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
import me.rerere.rikkahub.data.extensions.ExtensionImportExport
import me.rerere.rikkahub.data.extensions.ExtensionInstaller
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
 * 发现 → 应用中心（2026-11-18 从设置-小应用拆分）：承载扩展的开发管理能力——
 * 创建（模板向导）/ 导入 .evolia / 权限授权 / 更新 / 导出 / 卸载（数据保留选择）。
 * 运行控制（工具开关 / 描述简化）在 设置 → 小应用。
 */
@Composable
fun ExtensionWorkshopPage(
    navController: NavController,
) {
    val registry = koinInject<ExtensionRegistry>()
    val installer = koinInject<ExtensionInstaller>()
    val creator = koinInject<me.rerere.rikkahub.data.extensions.ExtensionCreator>()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val extensions by registry.installedExtensions.collectAsState()

    var showCreateDialog by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var sheetExtension by remember { mutableStateOf<InstalledExtension?>(null) }
    var showUninstallDialog by remember { mutableStateOf(false) }
    var uninstallDeleteData by remember { mutableStateOf(false) }
    var updating by remember { mutableStateOf(false) }
    var updateError by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            importing = true
            scope.launch {
                runCatching { ExtensionImportExport.importFromUri(context, it) }
                    .onSuccess { ext ->
                        importing = false
                        snackbarHostState.showSnackbar("已安装 ${ext.name} v${ext.version}")
                    }
                    .onFailure { e ->
                        importing = false
                        importError = e.message ?: "导入失败"
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
                    val tempFile = File(context.cacheDir, "export_${sheetExtension?.appId.orEmpty().takeLast(8)}.evolia")
                    ExtensionImportExport.exportToZip(context, sheetExtension?.appId.orEmpty(), tempFile)
                    context.contentResolver.openOutputStream(it)?.use { out ->
                        tempFile.inputStream().use { input -> input.copyTo(out) }
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

    Scaffold(
        topBar = {
            OneUITopAppBar(
                title = "应用中心",
                scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Rounded.Add, contentDescription = "创建扩展")
                    }
                    IconButton(
                        onClick = {
                            importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                        },
                        enabled = !importing,
                    ) {
                        Icon(Icons.Rounded.Input, contentDescription = "导入扩展包")
                    }
                },
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
                Text("还没有安装任何扩展", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "点击右上角 + 创建扩展，或导入 .evolia 扩展包",
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
                                    Text(extension.name, style = MaterialTheme.typography.titleMedium)
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
                                Text(
                                    "${extension.appId} · v${extension.version}",
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

    // ---------- 扩展管理抽屉（权限 / 更新 / 导出 / 卸载 / 打开） ----------
    val sheetPermissions = remember(sheetExtension) {
        sheetExtension?.let { ext ->
            (ext.manifest.permissions + ext.tools.flatMap { it.permissions }).distinct()
        } ?: emptyList()
    }
    sheetExtension?.let { extension ->
        ModalBottomSheet(
            onDismissRequest = { sheetExtension = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = AppShapes.BottomSheet,
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(640.dp).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Column {
                        Text(extension.name, style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        InfoRow("app_id", extension.appId)
                        InfoRow("版本", "v${extension.version}")
                        InfoRow(
                            "类型",
                            if (extension.extensionType == ExtensionType.MINI_APP) "Mini App" else "Tool Extension"
                        )
                        InfoRow("来源", sourceLabel(extension.sourceType))
                        InfoRow(
                            "安装时间",
                            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                .format(Date(extension.installedAt))
                        )
                    }
                }
                if (extension.extensionType == ExtensionType.MINI_APP) {
                    item {
                        TextButton(
                            onClick = { navController.navigate(Screen.MiniApp(extension.appId)) },
                            enabled = extension.isEnabled,
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("打开小应用")
                        }
                    }
                }
                // ---------- 权限 ----------
                if (sheetPermissions.isNotEmpty()) {
                    item {
                        SectionTitle("权限")
                        Text(
                            "Manifest 中声明的权限只是申请；授权后扩展才能使用对应原生能力。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                    items(sheetPermissions, key = { "perm_$it" }) { permission ->
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
                                    scope.launch {
                                        registry.grantPermission(extension.appId, permission, value)
                                    }
                                },
                            )
                        }
                    }
                }
                // ---------- 管理操作 ----------
                item {
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            updateLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                        },
                        enabled = !updating,
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("更新（导入 .evolia 扩展包）")
                    }
                    TextButton(
                        onClick = {
                            exportLauncher.launch("${extension.appId}_${extension.version}.evolia")
                        },
                    ) {
                        Icon(Icons.Rounded.FileUpload, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("导出扩展包")
                    }
                    TextButton(onClick = { showUninstallDialog = true }) {
                        Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(4.dp))
                        Text("卸载", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    // ---------- 创建草稿弹窗 ----------
    if (showCreateDialog) {
        CreateDraftDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, type, templateId ->
                showCreateDialog = false
                runCatching { creator.createDraft(name, type, templateId) }
                    .onSuccess { meta ->
                        navController.navigate(Screen.ExtensionCreator(meta.draftId))
                    }
                    .onFailure { e ->
                        scope.launch { snackbarHostState.showSnackbar(e.message ?: "创建失败") }
                    }
            },
        )
    }

    // ---------- 卸载确认（数据保留选择，详设 14.5） ----------
    if (showUninstallDialog) {
        val target = sheetExtension
        AlertDialog(
            onDismissRequest = { showUninstallDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = AppShapes.CardMedium,
            title = { Text("卸载扩展") },
            text = {
                Column {
                    Text("是否卸载「${target?.name.orEmpty()}」？")
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
                    val appId = target?.appId ?: return@TextButton
                    showUninstallDialog = false
                    val deleteData = uninstallDeleteData
                    scope.launch {
                        runCatching { installer.uninstall(appId, deleteMiniAppData = deleteData) }
                            .onSuccess {
                                snackbarHostState.showSnackbar("已卸载")
                                sheetExtension = null
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

    // ---------- 导入/更新失败弹窗 ----------
    (importError ?: updateError)?.let { error ->
        AlertDialog(
            onDismissRequest = {
                importError = null
                updateError = null
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = AppShapes.CardMedium,
            title = { Text("导入失败") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = {
                    importError = null
                    updateError = null
                }) { Text("知道了") }
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
