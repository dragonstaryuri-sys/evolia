package me.rerere.rikkahub.ui.pages.extension

import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.extensions.ExtensionCreator
import me.rerere.rikkahub.data.extensions.ExtensionImportExport
import me.rerere.rikkahub.data.extensions.ExtensionInstaller
import me.rerere.rikkahub.data.extensions.ExtensionRegistry
import me.rerere.rikkahub.data.extensions.ExtensionTemplates
import me.rerere.rikkahub.data.extensions.ExtensionType
import me.rerere.rikkahub.ui.components.extension.ExtensionPermissionDialog
import me.rerere.rikkahub.ui.components.extension.PendingExtensionPermissionRequest
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.components.ui.ToastType
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.AppShapes
import org.koin.compose.koinInject

/**
 * 本地扩展创建工作区（详设第七章轻量编辑器）。
 *
 * - 无 draftId：草稿列表 + 新建向导（名称 / 类型 / 模板，自动生成 app_id）；
 * - 有 draftId：文件树 + 文本编辑器 + 校验 + 安装（走统一 ExtensionInstaller）。
 * 草稿与已安装扩展严格分离：草稿不能被 Agent 调用。
 */
@Composable
fun ExtensionCreatorPage(
    draftId: String?,
    navController: NavController,
) {
    if (draftId == null) {
        DraftListScreen(navController)
    } else {
        DraftEditorScreen(draftId, navController)
    }
}

// ---------- 草稿列表 ----------

@Composable
private fun DraftListScreen(navController: NavController) {
    val creator = koinInject<ExtensionCreator>()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var drafts by remember { mutableStateOf(creator.listDrafts()) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }

    // 返回本页时刷新草稿列表
    androidx.compose.runtime.LaunchedEffect(refreshKey) {
        drafts = creator.listDrafts()
    }

    Scaffold(
        topBar = {
            OneUITopAppBar(
                title = "创建扩展",
                scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Rounded.Add, contentDescription = "新建草稿")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (drafts.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(80.dp))
                Text("暂无草稿", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "点击右上角 + 创建 Tool Extension 或 Mini App",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            ) {
                items(drafts, key = { it.draftId }) { draft ->
                    Card(
                        shape = AppShapes.CardMedium,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                            .clickable { navController.navigate(Screen.ExtensionCreator(draft.draftId)) },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(16.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(draft.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${draft.appId} · v${draft.version} · " +
                                        if (draft.extensionType == ExtensionType.MINI_APP) "Mini App" else "Tool",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = {
                                creator.deleteDraft(draft.draftId)
                                refreshKey++
                            }) {
                                Icon(Icons.Rounded.Delete, contentDescription = "删除草稿", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

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
}

@Composable
internal fun CreateDraftDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, type: String, templateId: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var selectedTemplate by remember { mutableStateOf(ExtensionTemplates.TEMPLATE_EMPTY_TOOL) }

    // 2026-11-19：部分 ROM 对弹窗窗口默认 PAN 上推，叠加内容 imePadding 会
    // 双重补偿，导致输入框顶部被裁切且滚不回顶。这里强制弹窗窗口随键盘
    // RESIZE，配合内容 verticalScroll，任何机型上输入框都能完整滚入可视区。
    val contentView = LocalView.current
    androidx.compose.runtime.LaunchedEffect(contentView) {
        var parent = contentView.parent
        while (parent != null && parent !is DialogWindowProvider) {
            parent = (parent as? android.view.View)?.parent
        }
        (parent as? DialogWindowProvider)?.window
            ?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

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

// ---------- 草稿编辑器 ----------

@Composable
private fun DraftEditorScreen(
    draftId: String,
    navController: NavController,
) {
    val creator = koinInject<ExtensionCreator>()
    val installer = koinInject<ExtensionInstaller>()
    val application = koinInject<android.app.Application>()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var draftMeta by remember { mutableStateOf(creator.getDraft(draftId)) }
    var files by remember { mutableStateOf(creator.listFiles(draftId)) }
    var selectedFile by remember { mutableStateOf(files.firstOrNull()) }
    var fileContent by remember { mutableStateOf("") }
    var dirty by remember { mutableStateOf(false) }
    var validationErrors by remember { mutableStateOf<List<String>?>(null) }
    var installing by remember { mutableStateOf(false) }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var newFilePath by remember { mutableStateOf("") }

    val registry = koinInject<ExtensionRegistry>()
    val toaster = LocalToaster.current
    // 安装权限确认（2026-11-19）：安装前预扫描 manifest，
    // 需要新授权的权限先弹窗，被拒则放弃本次安装
    var pendingPermissionRequest by remember { mutableStateOf<PendingExtensionPermissionRequest?>(null) }

    suspend fun installDraft(dir: java.io.File, grantNewPermissions: Boolean) {
        installing = true
        runCatching { installer.installFromDirectory(dir, grantNewPermissions = grantNewPermissions) }
            .onSuccess { ext ->
                toaster.show(message = "已安装 ${ext.name} v${ext.version}", type = ToastType.Success)
                // 安装成功：跳转拓展中心并关闭当前编辑页（从返回栈移除）
                navController.navigate(Screen.ExtensionWorkshop) {
                    popUpTo(Screen.ExtensionCreator(draftId)) { inclusive = true }
                }
            }
            .onFailure { e ->
                validationErrors = listOf(e.message ?: "安装失败")
            }
        installing = false
    }

    fun loadSelectedFile(path: String?) {
        selectedFile = path
        fileContent = path?.let { creator.readFile(draftId, it) } ?: ""
        dirty = false
    }

    androidx.compose.runtime.LaunchedEffect(draftId) {
        loadSelectedFile(files.firstOrNull())
    }

    // 2026-11-19：键盘弹出时页面上划，但只上划「必要量」——以编辑框底部
    // 露出键盘顶为限，且编辑框顶部不越出屏幕。若按整个 IME 高度全量上移，
    // 标题栏/文件条与编辑框上缘会被推出屏幕外，编辑框内容顶部无法滚回
    // 可视区（实测坑）。坐标在键盘收起时测量（此时无位移，即静止布局位）。
    var scaffoldHeightPx by remember { mutableStateOf(0f) }
    var editorTopRestPx by remember { mutableStateOf(0f) }
    var editorBottomRestPx by remember { mutableStateOf(0f) }
    val imeBottomPx = with(LocalDensity.current) { WindowInsets.ime.getBottom(this).toFloat() }
    val editorSlideUpPx = (editorBottomRestPx - (scaffoldHeightPx - imeBottomPx))
        .coerceIn(0f, editorTopRestPx)
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { scaffoldHeightPx = it.size.height.toFloat() }
            .graphicsLayer { translationY = -editorSlideUpPx },
        topBar = {
            OneUITopAppBar(
                title = draftMeta?.name ?: "编辑草稿",
                largeTitle = false,
                scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
                navigationIcon = { BackButton { navController.popBackStack() } },
                actions = {
                    IconButton(
                        onClick = {
                            val path = selectedFile
                            if (path != null) {
                                creator.saveFile(draftId, path, fileContent)
                                dirty = false
                                scope.launch { snackbarHostState.showSnackbar("已保存 $path") }
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
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 文件树（横向 chips 列表）
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                files.forEach { path ->
                    AssistChip(
                        onClick = {
                            if (dirty && selectedFile != null) {
                                creator.saveFile(draftId, selectedFile!!, fileContent)
                            }
                            loadSelectedFile(path)
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
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .padding(horizontal = 16.dp)
                    .onGloballyPositioned { coords ->
                        // 仅在键盘收起（无位移）时记录静止布局位置
                        if (imeBottomPx == 0f) {
                            editorTopRestPx = coords.boundsInWindow().top
                            editorBottomRestPx = coords.boundsInWindow().bottom
                        }
                    },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                label = { Text(selectedFile ?: "未选择文件") },
            )

            // 操作行：校验 / 安装
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    validationErrors = creator.validate(draftId)
                }) {
                    Text("校验")
                }
                Spacer(Modifier.width(12.dp))
                TextButton(
                    onClick = {
                        if (selectedFile != null && dirty) {
                            creator.saveFile(draftId, selectedFile!!, fileContent)
                            dirty = false
                        }
                        installing = true
                        scope.launch {
                            val dir = java.io.File(
                                ExtensionCreator.workspacesRoot(application),
                                draftId,
                            )
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
                                installDraft(dir, grantNewPermissions = false)
                            } else {
                                installing = false
                                pendingPermissionRequest = PendingExtensionPermissionRequest(
                                    manifest = manifest,
                                    permissions = needGrant,
                                    isUpdate = registry.installedExtensions.value.any { it.appId == manifest.appId },
                                    sourceDir = dir,
                                )
                            }
                        }
                    },
                    enabled = !installing,
                ) {
                    Text(if (installing) "安装中…" else "校验通过后安装")
                }
            }
        }
    }

    // 校验结果弹窗
    validationErrors?.let { errors ->
        AlertDialog(
            onDismissRequest = { validationErrors = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = AppShapes.CardMedium,
            title = { Text(if (errors.isEmpty()) "校验通过" else "校验失败") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (errors.isEmpty()) {
                        Text("扩展包校验通过，可以安装。")
                    } else {
                        errors.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { validationErrors = null }) { Text("知道了") }
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
                    scope.launch { installDraft(dir, grantNewPermissions = true) }
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
                    if (creator.createFile(draftId, path)) {
                        files = creator.listFiles(draftId)
                        loadSelectedFile(path)
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
