package me.rerere.rikkahub.ui.pages.extension

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Input
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.common.ui.components.AppFab
import me.rerere.rikkahub.common.ui.components.ConfirmDialog
import me.rerere.rikkahub.common.ui.components.EmptyPlaceholder
import me.rerere.rikkahub.data.extensions.ExtensionCreator
import me.rerere.rikkahub.data.extensions.ExtensionDraftValidator
import me.rerere.rikkahub.data.extensions.ExtensionImportExport
import me.rerere.rikkahub.data.extensions.ExtensionInstaller
import me.rerere.rikkahub.data.extensions.ExtensionRegistry
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
 * 拓展中心（发现 → 拓展中心，2026-11-20 补充需求第一章）：
 * 三个 Tab——市场（V1 占位）/ 我的拓展（已安装管理）/ 草稿箱（未安装开发版本）。
 *
 * 页面职责 = 展示、分类与入口导航；开发/校验/安装逻辑在编辑器与
 * ExtensionCreator / ExtensionInstaller，不在本页堆积。
 */
@Composable
fun ExtensionWorkshopPage(
    navController: NavController,
) {
    val registry = koinInject<ExtensionRegistry>()
    val installer = koinInject<ExtensionInstaller>()
    val creator = koinInject<ExtensionCreator>()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val toaster = LocalToaster.current

    val extensions by registry.installedExtensions.collectAsState()

    // 草稿列表（草稿箱 Tab + 我的拓展「有新版本开发中」角标共用）
    var drafts by remember { mutableStateOf(creator.listDrafts()) }
    var refreshKey by remember { mutableIntStateOf(0) }
    // 返回本页 / 删除草稿 / 新建草稿后刷新
    androidx.compose.runtime.LaunchedEffect(refreshKey) {
        withContext(Dispatchers.IO) { drafts = creator.listDrafts() }
    }

    val pagerState = rememberPagerState { 3 }
    val tabTitles = listOf("市场", "我的拓展", "草稿箱")

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
    // 二次确认删除的草稿（草稿箱 Tab）
    var draftDeleteTarget by remember { mutableStateOf<ExtensionCreator.DraftMeta?>(null) }
    // 导出选项弹窗（2026-11-20 补充需求第五章：导出时选择二次开发权限）
    var showExportDialog by remember { mutableStateOf(false) }
    var exportAllowModification by remember { mutableStateOf(true) }
    // 待导出的目标（弹窗确认后交给 SAF launcher）
    var exportTarget by remember { mutableStateOf<InstalledExtension?>(null) }

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
        val target = exportTarget
        if (uri != null && target != null) {
            val allowModification = exportAllowModification
            scope.launch {
                runCatching {
                    val tempFile = File(context.cacheDir, "export_${target.appId.takeLast(8)}.evolia")
                    withContext(Dispatchers.IO) {
                        ExtensionImportExport.exportToZip(
                            context = context,
                            appId = target.appId,
                            targetFile = tempFile,
                            allowModification = allowModification,
                        )
                    }
                    context.contentResolver.openOutputStream(uri)?.use { out ->
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
        exportTarget = null
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
        floatingActionButton = {
            // 新建拓展入口在草稿箱 Tab（需求一.4：草稿箱为空时提供新建入口）
            if (pagerState.currentPage == 2) {
                AppFab(
                    onClick = { showCreateDialog = true },
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "新建拓展")
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            SecondaryTabRow(selectedTabIndex = pagerState.currentPage) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = {
                            scope.launch { pagerState.animateScrollToPage(index) }
                        },
                        text = { Text(title) },
                    )
                }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (page) {
                    0 -> MarketTab()
                    1 -> InstalledExtensionsTab(
                        extensions = extensions,
                        drafts = drafts,
                        onOpenDetail = { sheetExtension = it },
                        onUninstall = {
                            uninstallTarget = it
                            showUninstallDialog = true
                        },
                    )
                    2 -> DraftsTab(
                        drafts = drafts,
                        onOpenDraft = { navController.navigate(Screen.ExtensionCreator(it.draftId)) },
                        onDeleteDraft = { draftDeleteTarget = it },
                    )
                }
            }
        }
    }

    // ---------- 扩展管理抽屉（权限 / 开发新版本 / 更新 / 导出 / 卸载 / 打开） ----------
    val sheetPermissions = remember(sheetExtension) {
        sheetExtension?.let { ext ->
            (ext.manifest.permissions + ext.tools.flatMap { it.permissions }).distinct()
        } ?: emptyList()
    }
    val sheetDraft = sheetExtension?.let { ext -> drafts.firstOrNull { it.appId == ext.appId } }
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
                        // 2026-11-20：二次开发权限随包展示（能力层判定见 ExtensionCreator.requireEditable）
                        InfoRow(
                            "二次开发",
                            if (extension.manifest.isModificationAllowed) "允许" else "不允许"
                        )
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
                // ---------- 二次开发入口（需求一.4 修改已安装拓展） ----------
                if (extension.manifest.isModificationAllowed) {
                    item {
                        TextButton(
                            onClick = {
                                sheetExtension = null
                                scope.launch {
                                    runCatching {
                                        withContext(Dispatchers.IO) {
                                            creator.createDraftFromInstalled(extension.appId)
                                        }
                                    }.onSuccess { meta ->
                                        navController.navigate(Screen.ExtensionCreator(meta.draftId))
                                    }.onFailure { e ->
                                        toaster.show(
                                            message = e.message ?: "创建开发版本失败",
                                            type = ToastType.Error,
                                        )
                                    }
                                }
                            },
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (sheetDraft != null) {
                                    "继续开发（草稿 v${sheetDraft.version}）"
                                } else {
                                    "开发新版本（v→自动递增）"
                                }
                            )
                        }
                    }
                }
                // ---------- 工具配置管理（需求一.3；描述简化仍在设置-小应用） ----------
                if (extension.tools.isNotEmpty()) {
                    item {
                        Column {
                            Text(
                                "工具配置（全局启用；描述编辑在 设置 → 小应用）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            extension.tools.forEach { tool ->
                                val enable = extension.toolConfigs[tool.toolId]?.enable == true
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        tool.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Switch(
                                        checked = enable,
                                        onCheckedChange = { checked ->
                                            scope.launch {
                                                registry.setToolEnable(extension.appId, tool.toolId, checked)
                                            }
                                        },
                                    )
                                }
                            }
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
                            exportTarget = extension
                            exportAllowModification = true
                            showExportDialog = true
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

    // ---------- 导出选项弹窗（二次开发权限随包携带） ----------
    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = AppShapes.CardMedium,
            title = { Text("导出扩展包") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "是否允许导入者二次开发（修改源码 / 创建新版本）？该选择写入扩展包 Manifest，重新导入不丢失。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { exportAllowModification = true },
                    ) {
                        RadioButton(selected = exportAllowModification, onClick = { exportAllowModification = true })
                        Column {
                            Text("允许二次开发", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "导入者可修改源码、创建新版本",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { exportAllowModification = false },
                    ) {
                        RadioButton(selected = !exportAllowModification, onClick = { exportAllowModification = false })
                        Column {
                            Text("不允许二次开发", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "导入者仅可安装、使用与修改运行配置（应用内限制）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showExportDialog = false
                    val target = exportTarget
                    if (target != null) {
                        sheetExtension = null
                        exportLauncher.launch("${target.appId}_${target.version}.evolia")
                    }
                }) { Text("导出") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showExportDialog = false
                    exportTarget = null
                }) { Text("取消") }
            },
        )
    }

    // ---------- 草稿删除二次确认（需求一.3） ----------
    draftDeleteTarget?.let { target ->
        ConfirmDialog(
            onDismissRequest = { draftDeleteTarget = null },
            onConfirm = {
                draftDeleteTarget = null
                scope.launch {
                    withContext(Dispatchers.IO) { creator.deleteDraft(target.draftId) }
                    refreshKey++
                }
            },
            title = "删除草稿",
            text = "确定删除草稿「${target.name}」（v${target.version}）吗？未安装的开发内容将丢失。",
            confirmText = "删除",
            dismissText = "取消",
            dangerous = true,
        )
    }

    // ---------- 创建草稿弹窗（草稿箱 Tab FAB → 模板向导 → 编辑器） ----------
    if (showCreateDialog) {
        CreateDraftDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, type, templateId ->
                showCreateDialog = false
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { creator.createDraft(name, type, templateId) }
                    }.onSuccess { meta ->
                        refreshKey++
                        navController.navigate(Screen.ExtensionCreator(meta.draftId))
                    }.onFailure { e ->
                        toaster.show(message = e.message ?: "创建失败", type = ToastType.Error)
                    }
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

// ---------- Tab 1：市场（V1 占位） ----------

@Composable
private fun MarketTab() {
    Box(modifier = Modifier.fillMaxSize()) {
        EmptyPlaceholder(
            text = "拓展市场开发中\n后续版本将提供扩展浏览、下载与安装",
            icon = Icons.Rounded.Storefront,
            modifier = Modifier.fillMaxSize().padding(32.dp),
        )
    }
}

// ---------- Tab 2：我的拓展 ----------

@Composable
private fun InstalledExtensionsTab(
    extensions: List<InstalledExtension>,
    drafts: List<ExtensionCreator.DraftMeta>,
    onOpenDetail: (InstalledExtension) -> Unit,
    onUninstall: (InstalledExtension) -> Unit,
) {
    val draftByAppId = remember(drafts) { drafts.associateBy { it.appId } }
    if (extensions.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize()) {
            EmptyPlaceholder(
                text = "还没有安装任何扩展\n可导入 .evolia 扩展包，或在草稿箱开发后安装",
                icon = Icons.Rounded.Input,
                modifier = Modifier.fillMaxSize().padding(32.dp),
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        items(extensions, key = { it.appId }) { extension ->
            val activeDraft = draftByAppId[extension.appId]
            Card(
                shape = AppShapes.CardMedium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .clickable { onOpenDetail(extension) },
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
                            if (activeDraft != null) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "有新版本开发中 v${activeDraft.version}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
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
                        onClick = { onUninstall(extension) },
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

// ---------- Tab 3：草稿箱 ----------

@Composable
private fun DraftsTab(
    drafts: List<ExtensionCreator.DraftMeta>,
    onOpenDraft: (ExtensionCreator.DraftMeta) -> Unit,
    onDeleteDraft: (ExtensionCreator.DraftMeta) -> Unit,
) {
    if (drafts.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.weight(1f))
                EmptyPlaceholder(
                    text = "草稿箱是空的\n点击右下角 + 新建拓展",
                    icon = Icons.Rounded.Add,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.weight(1.2f))
            }
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        items(drafts, key = { it.draftId }) { draft ->
            Card(
                shape = AppShapes.CardMedium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    .clickable { onOpenDraft(draft) },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(16.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(draft.name, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.width(8.dp))
                            TypeTag(draft.extensionType)
                        }
                        Text(
                            buildString {
                                append("v${draft.version}")
                                if (draft.baseVersion != null) {
                                    append(" · 基于 v${draft.baseVersion} 开发")
                                }
                                append(" · ${draft.appId}")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "修改于 " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                    .format(Date(draft.updatedAt)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                draftValidationLabel(draft.lastValidationLevel),
                                style = MaterialTheme.typography.labelSmall,
                                color = draftValidationColor(draft.lastValidationLevel),
                            )
                        }
                    }
                    IconButton(onClick = { onDeleteDraft(draft) }) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = "删除草稿",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

/** 草稿校验状态文案（需求一.3「简单的开发状态」） */
private fun draftValidationLabel(level: String?): String = when (level) {
    ExtensionDraftValidator.Level.PASS.name -> "校验通过"
    ExtensionDraftValidator.Level.WARN.name -> "有警告"
    ExtensionDraftValidator.Level.FAIL.name -> "校验失败"
    else -> "未校验"
}

@Composable
private fun draftValidationColor(level: String?): androidx.compose.ui.graphics.Color = when (level) {
    ExtensionDraftValidator.Level.PASS.name -> MaterialTheme.colorScheme.primary
    ExtensionDraftValidator.Level.WARN.name -> MaterialTheme.colorScheme.tertiary
    ExtensionDraftValidator.Level.FAIL.name -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
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
