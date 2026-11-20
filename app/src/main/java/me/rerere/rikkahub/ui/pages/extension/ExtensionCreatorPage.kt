package me.rerere.rikkahub.ui.pages.extension

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.extensions.ExtensionCreator
import me.rerere.rikkahub.data.extensions.ExtensionDraftValidator
import me.rerere.rikkahub.data.extensions.ExtensionImportExport
import me.rerere.rikkahub.data.extensions.ExtensionInstaller
import me.rerere.rikkahub.data.extensions.ExtensionRegistry
import me.rerere.rikkahub.data.extensions.ExtensionTemplates
import me.rerere.rikkahub.data.extensions.ExtensionType
import me.rerere.rikkahub.ui.components.extension.ExtensionPermissionDialog
import me.rerere.rikkahub.ui.components.extension.PendingExtensionPermissionRequest
import me.rerere.rikkahub.ui.components.ime.DialogImeResizeEffect
import me.rerere.rikkahub.ui.components.ime.ImeAwareEditorColumn
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.components.ui.ToastType
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.AppShapes
import org.koin.compose.koinInject

/** 自动保存防抖间隔（2026-11-20 补充需求二.1：编辑变化后自动保存，避免频繁写入） */
private const val AUTOSAVE_DELAY_MS = 800L

/**
 * 草稿编辑器（详设第七章轻量编辑器 + 2026-11-20 补充需求第二/四章）。
 *
 * - 草稿箱入口（拓展中心 → 草稿箱）总是带 draftId 进入；draftId 为空的
 *   旧入口（已移除的草稿列表）防御性返回；
 * - 自动保存：输入防抖 800ms + 退后台（ON_STOP）+ 离开页面兜底保存，
 *   落盘统一 Dispatchers.IO；异常退出后恢复 = 最近一次成功落盘内容；
 * - 校验 = 三层校验（结构 / 代码与运行环境 / 内容哈希），结果分「错误（阻断）」
 *   与「警告」展示；安装前强制重新校验 + 哈希一致性复查；
 * - 版本号由系统管理（workspace.json），保存 manifest.json 时自动归一；
 * - Mini App 支持工作区预览（Screen.MiniAppPreview）；安装成功后删除工作区
 *   （草稿移入我的拓展）。
 */
@Composable
fun ExtensionCreatorPage(
    draftId: String?,
    navController: NavController,
) {
    if (draftId == null) {
        // 草稿列表已迁移至拓展中心「草稿箱」Tab；空 draftId 视为无效入口
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }
    DraftEditorScreen(draftId, navController)
}

@Composable
internal fun CreateDraftDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, type: String, templateId: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var selectedTemplate by remember { mutableStateOf(ExtensionTemplates.TEMPLATE_EMPTY_TOOL) }

    // 弹窗窗口随键盘 RESIZE（通用组件 DialogImeResizeEffect）：
    // 输入框完整滚入可视区，不被键盘裁切
    DialogImeResizeEffect()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = AppShapes.CardMedium,
        title = { Text("新建扩展草稿") },
        text = {
            // 内容可滚动：窗口 RESIZE 后高度不足时仍可完整看到输入框
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("扩展名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text("选择模板", style = MaterialTheme.typography.labelMedium)
                ExtensionTemplates.TEMPLATES.forEach { template ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { selectedTemplate = template.id },
                    ) {
                        RadioButton(selected = selectedTemplate == template.id, onClick = { selectedTemplate = template.id })
                        Text(template.name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val template = ExtensionTemplates.TEMPLATES.firstOrNull { it.id == selectedTemplate }
                    onConfirm(name.trim(), template?.type ?: ExtensionType.TOOL, selectedTemplate)
                },
                enabled = name.isNotBlank(),
            ) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 校验结果弹窗数据（错误阻断 / 警告分组展示） */
private data class ValidationUi(
    val errors: List<ExtensionDraftValidator.Issue>,
    val warnings: List<ExtensionDraftValidator.Issue>,
    /** 安装流程触发的校验且仅剩警告时 = true，弹窗提供「仍要安装」按钮（需求四.4） */
    val allowInstallAnyway: Boolean = false,
)

@Composable
private fun DraftEditorScreen(
    draftId: String,
    navController: NavController,
) {
    val creator = koinInject<ExtensionCreator>()
    val installer = koinInject<ExtensionInstaller>()
    val application = koinInject<android.app.Application>()
    val appScope = koinInject<AppScope>()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var draftMeta by remember { mutableStateOf(creator.getDraft(draftId)) }
    var files by remember { mutableStateOf(creator.listFiles(draftId)) }
    var selectedFile by remember { mutableStateOf(files.firstOrNull()) }
    var fileContent by remember { mutableStateOf("") }
    var dirty by remember { mutableStateOf(false) }
    var validationUi by remember { mutableStateOf<ValidationUi?>(null) }
    var installError by remember { mutableStateOf<String?>(null) }
    var installing by remember { mutableStateOf(false) }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var newFilePath by remember { mutableStateOf("") }

    val registry = koinInject<ExtensionRegistry>()
    val toaster = LocalToaster.current
    // 安装权限确认（2026-11-19）：安装前预扫描 manifest，
    // 需要新授权的权限先弹窗，被拒则放弃本次安装
    var pendingPermissionRequest by remember { mutableStateOf<PendingExtensionPermissionRequest?>(null) }

    suspend fun refreshMeta() {
        // 草稿被编辑中 manifest 可能是暂时的非法 JSON（自动保存中间态），
        // getDraft 返回 null 时保留旧元信息（版本 chip 维持上一次可用值）
        val meta = withContext(Dispatchers.IO) { creator.getDraft(draftId) }
        if (meta != null) {
            draftMeta = meta
        }
    }

    suspend fun loadSelectedFile(path: String?) {
        val content = path?.let {
            withContext(Dispatchers.IO) { creator.readFile(draftId, it) }
        } ?: ""
        selectedFile = path
        fileContent = content
        dirty = false
    }

    /**
     * 保存当前文件并同步受管版本归一结果（manifest.json 保存时可能被
     * 改写 version，返回值同步回编辑器，避免「编辑器显示与落盘不一致」）。
     */
    suspend fun saveCurrentFile(): String? {
        val path = selectedFile ?: return null
        val content = fileContent
        val saved = withContext(Dispatchers.IO) { creator.saveFile(draftId, path, content) }
        if (saved != null) {
            dirty = false
            if (saved != content) {
                fileContent = saved
            }
            refreshMeta()
        }
        return saved
    }

    /** 退出页面 / 退后台的兜底保存（AppScope，不被页面协程取消） */
    fun scheduleExitSave() {
        if (!dirty) return
        val path = selectedFile ?: return
        val content = fileContent
        appScope.launch(Dispatchers.IO + NonCancellable) {
            creator.saveFile(draftId, path, content)
        }
    }

    LaunchedEffect(draftId) {
        loadSelectedFile(files.firstOrNull())
    }

    // 自动保存（防抖）：输入停止 800ms 后落盘；collectLatest 保证新输入
    // 取消未完成的延迟，避免频繁写盘
    LaunchedEffect(selectedFile) {
        snapshotFlow { fileContent }
            .drop(1) // 跳过文件装载初值
            .collectLatest {
                delay(AUTOSAVE_DELAY_MS)
                if (dirty) saveCurrentFile()
            }
    }

    // 退后台兜底保存（应用进入后台仍能保存）
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        scheduleExitSave()
    }

    // 离开页面兜底保存（compose 销毁后页面 scope 已取消，走 AppScope）
    DisposableEffect(Unit) {
        onDispose {
            scheduleExitSave()
        }
    }

    suspend fun proceedInstall(sourceDir: java.io.File, grantNewPermissions: Boolean) {
        installing = true
        // 安装前一致性复查：校验通过的文件必须与最终安装的文件完全一致
        val stale = withContext(Dispatchers.IO) { creator.checkValidationFreshness(draftId) }
        if (stale != null) {
            installing = false
            installError = stale
            return
        }
        runCatching {
            installer.installFromDirectory(sourceDir, grantNewPermissions = grantNewPermissions)
        }
            .onSuccess { ext ->
                toaster.show(message = "已安装 ${ext.name} v${ext.version}", type = ToastType.Success)
                // 安装成功：工作区删除（草稿移入我的拓展），跳转拓展中心
                withContext(Dispatchers.IO + NonCancellable) { creator.deleteDraft(draftId) }
                navController.navigate(Screen.ExtensionWorkshop) {
                    popUpTo(Screen.ExtensionCreator(draftId)) { inclusive = true }
                }
            }
            .onFailure { e ->
                installing = false
                installError = e.message ?: "安装失败"
            }
        installing = false
    }

    /**
     * 安装前权限预扫描（与校验结果无关，所有安装路径共用）：
     * 需要新授权的权限先弹窗确认，被拒则放弃安装。
     */
    suspend fun installAfterChecks() {
        val dir = java.io.File(ExtensionCreator.workspacesRoot(application), draftId)
        val manifest = ExtensionImportExport.readManifestFromDirectory(dir)
        val needGrant = if (manifest != null) {
            ExtensionImportExport.permissionsNeedingGrant(
                manifest,
                registry.installedExtensions.value,
            )
        } else {
            emptyList()
        }
        if (manifest == null || needGrant.isEmpty()) {
            // 无需新授权（或预扫描失败走安装器报错）→ 直接安装
            proceedInstall(dir, grantNewPermissions = false)
        } else {
            pendingPermissionRequest = PendingExtensionPermissionRequest(
                manifest = manifest,
                permissions = needGrant,
                isUpdate = registry.installedExtensions.value.any { it.appId == manifest.appId },
                sourceDir = dir,
            )
        }
    }

    /** 安装按钮统一入口：保存 → 重新校验 → 分流（阻断 / 警告确认 / 直接安装） */
    fun startInstall() {
        scope.launch {
            if (dirty) saveCurrentFile()
            val result = withContext(Dispatchers.IO) { creator.validate(draftId) }
            refreshMeta()
            when {
                result == null -> {
                    installError = "草稿不存在"
                }

                result.errors.isNotEmpty() -> {
                    validationUi = ValidationUi(result.errors, result.warnings)
                }

                result.warnings.isNotEmpty() -> {
                    validationUi = ValidationUi(
                        errors = emptyList(),
                        warnings = result.warnings,
                        allowInstallAnyway = true,
                    )
                }

                else -> installAfterChecks()
            }
        }
    }

    // 2026-11-20：键盘处理统一走 ImeAware 通用组件（参考
    // FullScreenSystemPromptEditor）：编辑框完整落在键盘上方，
    // 视口始终在屏幕内，长内容在编辑框内部滚动可完整滚到顶。
    Scaffold(
        topBar = {
            OneUITopAppBar(
                title = draftMeta?.name ?: "编辑草稿",
                largeTitle = false,
                scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
                navigationIcon = { BackButton { navController.popBackStack() } },
                actions = {
                    // Mini App 工作区预览（需求四.3 第三层）
                    if (draftMeta?.extensionType == ExtensionType.MINI_APP) {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    if (dirty) saveCurrentFile()
                                    navController.navigate(Screen.MiniAppPreview(draftId))
                                }
                            },
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = "预览")
                        }
                    }
                    IconButton(
                        onClick = {
                            scope.launch {
                                val saved = saveCurrentFile()
                                if (saved != null) snackbarHostState.showSnackbar("已保存 $selectedFile")
                            }
                        },
                        enabled = dirty && selectedFile != null,
                    ) {
                        Icon(Icons.Rounded.Save, contentDescription = "保存")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        ImeAwareEditorColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 版本信息（受管只读）：开发版本 + 基于版本（如有）
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    buildString {
                        append("v${draftMeta?.version ?: "?"}")
                        if (draftMeta?.baseVersion != null) {
                            append(" · 基于 v${draftMeta?.baseVersion} 开发")
                        }
                        append(" · 版本号由系统管理")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 文件树（横向 chips 列表）
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                files.forEach { path ->
                    AssistChip(
                        onClick = {
                            scope.launch {
                                if (dirty) saveCurrentFile()
                                loadSelectedFile(path)
                            }
                        },
                        label = { Text(path) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                IconButton(onClick = { showNewFileDialog = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = "新建文件")
                }
            }

            // 代码编辑器
            OutlinedTextField(
                value = fileContent,
                onValueChange = {
                    fileContent = it
                    dirty = true
                },
                modifier = Modifier.fillMaxSize().weight(1f).padding(horizontal = 16.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                label = { Text(selectedFile ?: "未选择文件") },
            )

            // 操作行：校验 / 安装（安装前自动重新校验）
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    scope.launch {
                        if (dirty) saveCurrentFile()
                        val result = withContext(Dispatchers.IO) { creator.validate(draftId) }
                        refreshMeta()
                        if (result != null) {
                            validationUi = ValidationUi(result.errors, result.warnings)
                        }
                    }
                }) {
                    Text("校验")
                }
                Spacer(Modifier.width(12.dp))
                TextButton(
                    onClick = { startInstall() },
                    enabled = !installing,
                ) {
                    Text(if (installing) "安装中…" else "校验并安装")
                }
            }
        }
    }

    // 校验结果弹窗（错误阻断 / 警告分组）
    validationUi?.let { ui ->
        AlertDialog(
            onDismissRequest = { validationUi = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = AppShapes.CardMedium,
            title = {
                Text(
                    when {
                        ui.errors.isNotEmpty() -> "校验未通过"
                        ui.warnings.isNotEmpty() -> "校验通过（有警告）"
                        else -> "校验通过"
                    }
                )
            },
            text = {
                LazyColumn(
                    modifier = Modifier.height(280.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                ) {
                    if (ui.errors.isEmpty() && ui.warnings.isEmpty()) {
                        item {
                            Text(
                                "扩展包校验通过，可以安装。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    if (ui.errors.isNotEmpty()) {
                        item {
                            Text(
                                "错误（阻断安装）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        items(ui.errors) { issue ->
                            Text(
                                "· " + (issue.file?.let { "[$it] " } ?: "") + issue.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (ui.warnings.isNotEmpty()) {
                        item {
                            Text(
                                "警告（不阻断，安装后可实测）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        items(ui.warnings) { issue ->
                            Text(
                                "· " + (issue.file?.let { "[$it] " } ?: "") + issue.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (ui.allowInstallAnyway && ui.errors.isEmpty()) {
                    // 需求四.4：警告需用户确认后允许继续安装（仍走权限预扫描）
                    TextButton(onClick = {
                        validationUi = null
                        scope.launch { installAfterChecks() }
                    }) { Text("仍要安装") }
                } else {
                    TextButton(onClick = { validationUi = null }) { Text("知道了") }
                }
            },
        )
    }

    // 安装失败 / 一致性失败弹窗
    installError?.let { error ->
        AlertDialog(
            onDismissRequest = { installError = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = AppShapes.CardMedium,
            title = { Text("无法安装") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { installError = null }) { Text("知道了") }
            },
        )
    }

    // 安装权限确认弹窗（拒绝则不安装）
    pendingPermissionRequest?.let { request ->
        ExtensionPermissionDialog(
            request = request,
            onConfirm = { confirmed ->
                pendingPermissionRequest = null
                val dir = confirmed.sourceDir
                if (dir != null) {
                    scope.launch { proceedInstall(dir, grantNewPermissions = true) }
                }
            },
            onDismiss = { dismissed ->
                pendingPermissionRequest = null
                scope.launch {
                    snackbarHostState.showSnackbar("需要授予权限才能安装，已取消")
                }
            },
        )
    }

    // 新建文件弹窗
    if (showNewFileDialog) {
        AlertDialog(
            onDismissRequest = { showNewFileDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = AppShapes.CardMedium,
            title = { Text("新建文件") },
            text = {
                OutlinedTextField(
                    value = newFilePath,
                    onValueChange = { newFilePath = it },
                    label = { Text("相对路径（如 web/app.js）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val path = newFilePath.trim()
                    scope.launch {
                        val created = withContext(Dispatchers.IO) { creator.createFile(draftId, path) }
                        if (created) {
                            files = withContext(Dispatchers.IO) { creator.listFiles(draftId) }
                            loadSelectedFile(path)
                        }
                    }
                    showNewFileDialog = false
                    newFilePath = ""
                }, enabled = newFilePath.isNotBlank()) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showNewFileDialog = false; newFilePath = "" }) { Text("取消") }
            },
        )
    }
}
