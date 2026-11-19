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
import me.rerere.rikkahub.data.extensions.ExtensionManifest
import me.rerere.rikkahub.data.extensions.ExtensionRegistry
import me.rerere.rikkahub.data.extensions.ExtensionRules
import me.rerere.rikkahub.data.extensions.ExtensionSourceType
import me.rerere.rikkahub.data.extensions.ExtensionType
import me.rerere.rikkahub.data.extensions.InstalledExtension
import me.rerere.rikkahub.ui.components.extension.ExtensionPermissionDialog
import me.rerere.rikkahub.ui.components.extension.PendingExtensionPermissionRequest
import me.rerere.rikkahub.ui.components.extension.permissionDisplayName
import me.rerere.rikkahub.ui.components.ui.ToastType
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.context.LocalToaster
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
    val toaster = LocalToaster.current

    val extensions by registry.installedExtensions.collectAsState()

    var showCreateDialog by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var sheetExtension by remember { mutableStateOf<InstalledExtension?>(null) }
    var showUninstallDialog by remember { mutableStateOf(false) }
    var uninstallDeleteData by remember { mutableStateOf(false) }
    var updating by remember { mutableStateOf(false) }
    var updateError by remember { mutableStateOf<String?>(null) }
    // 安装/更新权限确认（2026-11-19）：安装前预扫描 manifest，需要新授权的
    // 权限先弹窗；被拒则放弃本次安装/更新
    var pendingPermissionRequest by remember { mutableStateOf<PendingExtensionPermissionRequest?>(null) }
    // 卡片上直接发起卸载的目标（与详情抽屉内的卸载共用同一确认弹窗）
    var uninstallTarget by remember { mutableStateOf<InstalledExtension?>(null) }

    /** 安装临时包并按结果提示；[grantNewPermissions] = 用户已在弹窗中同意授权 */
    suspend fun installZipPackage(tempFile: File, isUpdate: Boolean, grantNewPermissions: Boolean) {
        if (isUpdate) updating = true else importing = true
        runCatching {
            installer.installFromZip(
                zipFile = tempFile,
                sourceType = ExtensionSourceType.LOCAL_IMPORT,
                grantNewPermissions = grantNewPermissions,
            )
        }.onSuccess { ext ->
            toaster.show(
                message = "已${if (isUpdate) "更新" else "安装"} ${ext.name} v${ext.version}",
                type = ToastType.Success,
            )
        }.onFailure { e ->
            if (isUpdate) updateError = e.message ?: "更新失败" else importError = e.message ?: "导入失败"
        }
        if (isUpdate) updating = false else importing = false
        tempFile.delete()
    }

    /** 导入 .evolia：复制 → 预扫描权限 → 无新增权限直接安装，否则弹权限确认 */
    suspend fun handlePackageUri(uri: android.net.Uri, isUpdate: Boolean) {
        val tempFile = runCatching { ExtensionImportExport.copyToTempFile(context, uri) }
            .onFailure { e ->
                if (isUpdate) updateError = e.message ?: "更新失败" else importError = e.message ?: "导入失败"
            }
            .getOrNull() ?: return
        val manifest = ExtensionImportExport.readManifestFromZip(tempFile)
        if (manifest == null) {
            // 预扫描失败：交给 Installer 完整校验，由其给出准确错误
            installZipPackage(tempFile, isUpdate, grantNewPermissions = false)
            return
        }
        val needGrant = ExtensionImportExport.permissionsNeedingGrant(manifest, extensions)
        if (needGrant.isEmpty()) {
            installZipPackage(tempFile, isUpdate, grantNewPermissions = false)
        } else {
            pendingPermissionRequest = PendingExtensionPermissionRequest(
                manifest = manifest,
                permissions = needGrant,
                isUpdate = isUpdate,
                tempZipFile = tempFile,
            )
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { scope.launch { handlePackageUri(it, isUpdate = false) } }
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
                    toaster.show(message = "已导出扩展包", type = ToastType.Success)
                }.onFailure { e ->
                    toaster.show(message = "导出失败：${e.message}", type = ToastType.Error)
                }
            }
        }
    }

    val updateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { scope.launch { handlePackageUri(it, isUpdate = true) } }
    }

    Scaffold(
        topBar = {
            OneUITopAppBar(
                title = "拓展中心",
                largeTitle = false,
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
                            // 卸载入口（卡片级，2026-11-19：卡片不再放启用开关，
                            // 运行控制归设置-小应用页；卸载走二次确认弹窗）
                            TextButton(
                                onClick = {
                                    uninstallTarget = extension
                                    showUninstallDialog = true
                                },
                            ) {
                                Text(
                                    "卸载",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
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
                        // 2026-11-19：权限改为基本信息内只读展示（授权状态见设置-小应用）
                        InfoRow(
                            "权限",
                            sheetPermissions.ifEmpty { listOf("无") }
                                .joinToString("、") { permissionDisplayName(it) }
                        )
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
                        scope.launch { toaster.show(message = e.message ?: "创建失败", type = ToastType.Error) }
                    }
            },
        )
    }

    // ---------- 卸载确认（数据保留选择，详设 14.5；卡片与详情抽屉共用） ----------
    if (showUninstallDialog) {
        val target = uninstallTarget ?: sheetExtension
        AlertDialog(
            onDismissRequest = {
                uninstallTarget = null
                showUninstallDialog = false
            },
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
                    // 确认后立即收起确认弹窗与详情抽屉，再异步执行卸载，
                    // 结果用顶部 Toast（toolbar 形式）提示
                    showUninstallDialog = false
                    sheetExtension = null
                    uninstallTarget = null
                    val deleteData = uninstallDeleteData
                    scope.launch {
                        runCatching { installer.uninstall(appId, deleteMiniAppData = deleteData) }
                            .onSuccess {
                                toaster.show(message = "已卸载", type = ToastType.Success)
                            }
                            .onFailure { e ->
                                toaster.show(
                                    message = "卸载失败：${e.message}",
                                    type = ToastType.Error,
                                )
                            }
                    }
                }) {
                    Text("卸载", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    uninstallTarget = null
                    showUninstallDialog = false
                }) { Text("取消") }
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

    // ---------- 安装/更新权限确认弹窗（拒绝则不安装/放弃更新） ----------
    pendingPermissionRequest?.let { request ->
        ExtensionPermissionDialog(
            request = request,
            onConfirm = { confirmed ->
                pendingPermissionRequest = null
                val temp = confirmed.tempZipFile
                if (temp != null) {
                    scope.launch { installZipPackage(temp, confirmed.isUpdate, grantNewPermissions = true) }
                }
            },
            onDismiss = { dismissed ->
                dismissed.tempZipFile?.delete()
                pendingPermissionRequest = null
                toaster.show(
                    message = "需要授予权限才能${if (dismissed.isUpdate) "更新" else "安装"}，已取消",
                    type = ToastType.Warning,
                )
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

private fun sourceLabel(sourceType: String): String = when (sourceType) {
    "LOCAL_CREATED" -> "本地创建"
    "LOCAL_IMPORT" -> "本地导入"
    "GITHUB" -> "GitHub"
    "BUILTIN" -> "内置"
    else -> sourceType
}
