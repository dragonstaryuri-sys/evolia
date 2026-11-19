package me.rerere.rikkahub.ui.pages.setting

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.jvziyaoyao.scale.image.pager.ImagePager
import com.jvziyaoyao.scale.zoomable.pager.rememberZoomablePagerState
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.common.ui.components.ConfirmDialog
import me.rerere.rikkahub.common.ui.components.EmptyPlaceholder
import me.rerere.rikkahub.common.ui.components.InlineSpinner
import me.rerere.rikkahub.data.sandboxfile.SandboxFileCategory
import me.rerere.rikkahub.data.sandboxfile.SandboxFileInfo
import me.rerere.rikkahub.data.sandboxfile.SandboxFileGroup
import me.rerere.rikkahub.data.sandboxfile.SandboxFileRepository
import me.rerere.rikkahub.data.sandboxfile.SandboxFileRules
import me.rerere.rikkahub.data.sandboxfile.SandboxFileSource
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.AppShapes
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val sandboxFileDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * 沙箱文件管理页面（设置 → 数据设置 → 沙箱文件）。
 *
 * 两个 Tab：图片（网格缩略图）/ 其他文件（列表）。
 * 支持单文件预览/打开、保存到本地（SAF）、删除（二次确认）、长按多选批量操作。
 */
@Composable
fun SettingSandboxFilePage(vm: SandboxFileVM = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val navController = LocalNavController.current
    val repository = koinInject<SandboxFileRepository>()

    // 从预览页返回时保留当前 Tab（普通 remember 会在离开组合后丢失）
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    var pendingDeleteFiles by remember { mutableStateOf<List<SandboxFileInfo>?>(null) }
    var pendingExportFile by remember { mutableStateOf<SandboxFileInfo?>(null) }

    val imageFiles = remember(state.files) {
        state.files.filter { it.category == SandboxFileCategory.IMAGE }
    }
    val otherFiles = remember(state.files) {
        state.files.filter { it.category == SandboxFileCategory.OTHER }
    }

    // 按第一级子目录分组（新沙箱布局=助手目录，旧布局=会话目录，生图=整源一组）
    val imageGroups = remember(imageFiles, state.groupLabels) {
        SandboxFileRules.buildGroups(imageFiles) { key -> state.groupLabels[key] ?: key }
    }
    // 预览左右滑动顺序与图片 Tab 的展示顺序一致（分组 → 组内按时间倒序）
    val orderedImageFiles = remember(imageGroups) { imageGroups.flatMap { it.files } }
    val otherGroups = remember(otherFiles, state.groupLabels) {
        SandboxFileRules.buildGroups(otherFiles) { key -> state.groupLabels[key] ?: key }
    }

    // 单文件导出：ACTION_CREATE_DOCUMENT，由用户选择目标位置（保存不影响沙箱原文件）
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        val file = pendingExportFile
        pendingExportFile = null
        if (uri != null && file != null) {
            vm.exportSingle(file, uri)
        }
    }

    // 批量导出：ACTION_OPEN_DOCUMENT_TREE，导出到用户选择的目录
    val openDocumentTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            vm.exportSelected(uri)
        }
    }

    // 一次性反馈（Toast：预览 Dialog 之上也可见）
    LaunchedEffect(Unit) {
        vm.messages.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    // md/txt 走 Markdown 预览页，html 走 WebView 预览页，其余仍交给系统查看器
    val openOtherFile: (SandboxFileInfo) -> Unit = { file ->
        val previewKind = sandboxTextPreviewKindOf(file)
        val previewUri = previewKind?.let { repository.contentUriFor(file) }
        when {
            previewKind == null || previewUri == null -> vm.openWithViewer(file)
            previewKind == SandboxTextPreviewKind.HTML ->
                navController.navigate(Screen.WebView(url = previewUri.toString(), title = file.name))
            else ->
                navController.navigate(Screen.MarkdownViewer(title = file.name, uri = previewUri.toString()))
        }
    }

    Scaffold(
        topBar = {
            if (state.selectionMode) {
                SandboxFileSelectionTopBar(
                    selectedCount = state.selectedIds.size,
                    allSelected = state.selectedIds.isNotEmpty() &&
                        state.selectedIds.size >= state.files.size,
                    onExit = { vm.exitSelectionMode() },
                    onToggleSelectAll = { vm.toggleSelectAll() },
                    onSave = {
                        if (state.selectedIds.isNotEmpty()) {
                            openDocumentTreeLauncher.launch(null)
                        }
                    },
                    onDelete = {
                        val targets = state.files.filter { it.id in state.selectedIds }
                        if (targets.isNotEmpty()) pendingDeleteFiles = targets
                    }
                )
            } else {
                val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
                OneUITopAppBar(
                    title = stringResource(R.string.sandbox_file_page_title),
                    scrollBehavior = scrollBehavior,
                    navigationIcon = { BackButton() },
                    actions = {
                        IconButton(onClick = { vm.refresh() }) {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.sandbox_file_refresh)
                            )
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            TabRow(selectedTabIndex = tabIndex) {
                Tab(
                    selected = tabIndex == 0,
                    onClick = { tabIndex = 0 },
                    text = {
                        Text(stringResource(R.string.sandbox_file_tab_images, imageFiles.size))
                    }
                )
                Tab(
                    selected = tabIndex == 1,
                    onClick = { tabIndex = 1 },
                    text = {
                        Text(stringResource(R.string.sandbox_file_tab_others, otherFiles.size))
                    }
                )
            }

            when {
                state.loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        InlineSpinner()
                    }
                }

                state.scanError && state.files.isEmpty() -> {
                    // 扫描失败不能直接展示为空列表（方案 3.6）
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        EmptyPlaceholder(
                            text = stringResource(R.string.sandbox_file_scan_failed),
                            icon = Icons.Rounded.Refresh
                        )
                        TextButton(onClick = { vm.refresh() }) {
                            Text(stringResource(R.string.sandbox_file_retry))
                        }
                    }
                }

                else -> when (tabIndex) {
                    0 -> SandboxImageGrid(
                        groups = imageGroups,
                        resolveFile = { file -> repository.resolveFile(file) },
                        selectionMode = state.selectionMode,
                        selectedIds = state.selectedIds,
                        onItemClick = { file ->
                            if (state.selectionMode) vm.toggleSelected(file) else vm.openPreview(file)
                        },
                        onItemLongClick = { file ->
                            if (!state.selectionMode) vm.enterSelectionMode(file)
                        }
                    )

                    else -> SandboxOtherFileList(
                        groups = otherGroups,
                        selectionMode = state.selectionMode,
                        selectedIds = state.selectedIds,
                        onItemClick = { file ->
                            if (state.selectionMode) vm.toggleSelected(file) else openOtherFile(file)
                        },
                        onItemLongClick = { file ->
                            if (!state.selectionMode) vm.enterSelectionMode(file)
                        },
                        onMenuCopyPath = { file -> vm.copyPath(file) },
                        onMenuSave = { file ->
                            pendingExportFile = file
                            createDocumentLauncher.launch(file.name)
                        },
                        onMenuDelete = { file -> pendingDeleteFiles = listOf(file) }
                    )
                }
            }
        }
    }

    // 图片预览（沉浸式深色画布，属豁免清单「图片预览器」）
    state.previewFile?.let { previewTarget ->
        if (orderedImageFiles.isNotEmpty()) {
            val initialIndex = remember(previewTarget.id, orderedImageFiles) {
                orderedImageFiles.indexOfFirst { it.id == previewTarget.id }.coerceAtLeast(0)
            }
            SandboxImagePreviewDialog(
                files = orderedImageFiles,
                initialIndex = initialIndex,
                resolvePath = { file -> repository.resolveFile(file)?.absolutePath },
                onDismiss = { vm.closePreview() },
                onCopyPath = { file -> vm.copyPath(file) },
                onSave = { file ->
                    pendingExportFile = file
                    createDocumentLauncher.launch(file.name)
                },
                onDelete = { file -> pendingDeleteFiles = listOf(file) }
            )
        }
    }

    // 删除二次确认（单文件 / 批量共用一份文案与组件）
    pendingDeleteFiles?.let { deleteTargets ->
        // 生图产物可能仍被聊天消息与生图媒体库引用，删除后引用处将无法显示
        val containsGeneratedImage = deleteTargets.any { it.source == SandboxFileSource.GENERATED_IMAGE }
        val baseText = if (deleteTargets.size == 1) {
            stringResource(R.string.sandbox_file_delete_confirm_single)
        } else {
            stringResource(R.string.sandbox_file_delete_confirm_batch, deleteTargets.size)
        }
        val confirmText = if (containsGeneratedImage) {
            baseText + "\n" + stringResource(R.string.sandbox_file_delete_image_ref_hint)
        } else {
            baseText
        }
        ConfirmDialog(
            onDismissRequest = { pendingDeleteFiles = null },
            onConfirm = {
                pendingDeleteFiles = null
                if (deleteTargets.size == 1) {
                    vm.deleteSingle(deleteTargets.first())
                } else {
                    vm.deleteSelected()
                }
            },
            title = stringResource(R.string.sandbox_file_delete_title),
            confirmText = stringResource(R.string.sandbox_file_confirm_delete),
            dismissText = stringResource(R.string.sandbox_file_cancel),
            text = confirmText,
            dangerous = true
        )
    }
}

/**
 * 多选模式顶栏：展示已选数量 + 全选/保存/删除/退出。
 */
@Composable
private fun SandboxFileSelectionTopBar(
    selectedCount: Int,
    allSelected: Boolean,
    onExit: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit
) {
    val scrollBehavior: TopAppBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    OneUITopAppBar(
        title = stringResource(R.string.sandbox_file_selected_count, selectedCount),
        scrollBehavior = scrollBehavior,
        navigationIcon = {
            IconButton(onClick = onExit) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.sandbox_file_cancel)
                )
            }
        },
        actions = {
            IconButton(onClick = onToggleSelectAll) {
                Icon(
                    Icons.Rounded.SelectAll,
                    contentDescription = if (allSelected) {
                        stringResource(R.string.sandbox_file_select_all_cancel)
                    } else {
                        stringResource(R.string.sandbox_file_select_all)
                    }
                )
            }
            IconButton(onClick = onSave) {
                Icon(
                    Icons.Rounded.Save,
                    contentDescription = stringResource(R.string.sandbox_file_save)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = stringResource(R.string.sandbox_file_confirm_delete),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    )
}

/**
 * 图片 Tab：网格缩略图（合并 Python 沙箱与生图两个来源）。
 */
@Composable
private fun SandboxImageGrid(
    groups: List<SandboxFileGroup>,
    resolveFile: (SandboxFileInfo) -> File?,
    selectionMode: Boolean,
    selectedIds: Set<String>,
    onItemClick: (SandboxFileInfo) -> Unit,
    onItemLongClick: (SandboxFileInfo) -> Unit
) {
    val totalFiles = groups.sumOf { it.files.size }
    if (totalFiles == 0) {
        EmptyPlaceholder(
            text = stringResource(R.string.sandbox_file_images_empty),
            icon = Icons.Rounded.Image,
            modifier = Modifier.fillMaxSize()
        )
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        groups.forEach { group ->
            // 分组头：占满整行（助手名/会话标题/生图）
            item(key = "header_${group.key}", span = { GridItemSpan(maxLineSpan) }) {
                SandboxGroupHeader(group = group)
            }
            items(
                items = group.files,
                key = { it.id }
            ) { file ->
                SandboxImageGridItem(
                    file = file,
                    resolveFile = resolveFile,
                    selectionMode = selectionMode,
                    isSelected = file.id in selectedIds,
                    onClick = { onItemClick(file) },
                    onLongClick = { onItemLongClick(file) }
                )
            }
        }
    }
}

@Composable
private fun SandboxImageGridItem(
    file: SandboxFileInfo,
    resolveFile: (SandboxFileInfo) -> File?,
    selectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    val imageFile = remember(file.id) { resolveFile(file) }
    Column(
        modifier = Modifier
            .clip(AppShapes.ListItemSmall)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(AppShapes.ListItemSmall)
                .then(
                    if (isSelected) {
                        Modifier.border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = AppShapes.ListItemSmall
                        )
                    } else {
                        Modifier
                    }
                )
        ) {
            // Coil 按布局尺寸下采样解码，避免大图整载入内存（方案 3.3）
            val coilModel = ImageRequest.Builder(context)
                .data(imageFile)
                .crossfade(false)
                .build()
            AsyncImage(
                model = coilModel,
                contentDescription = file.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (selectionMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = file.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
    }
}

/**
 * 其他文件 Tab：纵向列表。
 */
@Composable
private fun SandboxOtherFileList(
    groups: List<SandboxFileGroup>,
    selectionMode: Boolean,
    selectedIds: Set<String>,
    onItemClick: (SandboxFileInfo) -> Unit,
    onItemLongClick: (SandboxFileInfo) -> Unit,
    onMenuCopyPath: (SandboxFileInfo) -> Unit,
    onMenuSave: (SandboxFileInfo) -> Unit,
    onMenuDelete: (SandboxFileInfo) -> Unit
) {
    val totalFiles = groups.sumOf { it.files.size }
    if (totalFiles == 0) {
        EmptyPlaceholder(
            text = stringResource(R.string.sandbox_file_others_empty),
            icon = Icons.Rounded.InsertDriveFile,
            modifier = Modifier.fillMaxSize()
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        groups.forEach { group ->
            item(key = "header_${group.key}") {
                SandboxGroupHeader(group = group)
            }
            items(
                items = group.files,
                key = { it.id }
            ) { file ->
                SandboxOtherFileItem(
                    file = file,
                    selectionMode = selectionMode,
                    isSelected = file.id in selectedIds,
                    onClick = { onItemClick(file) },
                    onLongClick = { onItemLongClick(file) },
                    onMenuCopyPath = { onMenuCopyPath(file) },
                    onMenuSave = { onMenuSave(file) },
                    onMenuDelete = { onMenuDelete(file) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SandboxOtherFileItem(
    file: SandboxFileInfo,
    selectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMenuCopyPath: () -> Unit,
    onMenuSave: () -> Unit,
    onMenuDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShapes.ListItemSmall)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(AppShapes.ListItemSmall)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = sandboxFileIconFor(file.extension),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "%s · %s".format(
                    formatSandboxFileSize(file.size),
                    formatSandboxFileTime(file.lastModified)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (selectionMode) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        } else {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = file.name,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 行内菜单：保存到本地 / 删除（不支持预览的类型也有保存入口，方案 3.4）
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = AppShapes.ButtonSquared
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sandbox_file_copy_path)) },
                    leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onMenuCopyPath()
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sandbox_file_menu_save)) },
                    leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onMenuSave()
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sandbox_file_menu_delete)) },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onMenuDelete()
                    }
                )
            }
        }
    }
}

/**
 * 图片预览：全屏深色画布 + 左右滑动切换 + 缩放查看 + 文件信息 + 保存/删除。
 * （沉浸式深色画布豁免清单：图片预览器允许固定深色前景）
 */
@Composable
private fun SandboxImagePreviewDialog(
    files: List<SandboxFileInfo>,
    initialIndex: Int,
    resolvePath: (SandboxFileInfo) -> String?,
    onDismiss: () -> Unit,
    onCopyPath: (SandboxFileInfo) -> Unit,
    onSave: (SandboxFileInfo) -> Unit,
    onDelete: (SandboxFileInfo) -> Unit
) {
    if (files.isEmpty()) return
    val zoomState = rememberZoomablePagerState { files.size }

    // 打开时定位到用户点击的那张图片
    LaunchedEffect(initialIndex) {
        if (initialIndex in files.indices) {
            zoomState.scrollToPage(initialIndex)
        }
    }

    val currentFile = files.getOrNull(zoomState.currentPage) ?: files.first()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            ImagePager(
                modifier = Modifier.fillMaxSize(),
                pagerState = zoomState,
                imageLoader = { index ->
                    val file = files.getOrNull(index)
                    val path = remember(file?.id) { file?.let(resolvePath) }
                    val painter = rememberAsyncImagePainter(path.orEmpty())
                    Pair(painter, painter.intrinsicSize)
                }
            )
            if (files.size > 1) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 12.dp),
                    shape = AppShapes.ButtonPill,
                    color = Color.White.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "${zoomState.currentPage + 1} / ${files.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = currentFile.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "%s · %s".format(
                            formatSandboxFileSize(currentFile.size),
                            formatSandboxFileTime(currentFile.lastModified)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SandboxPreviewActionButton(
                            icon = Icons.Rounded.ContentCopy,
                            label = stringResource(R.string.sandbox_file_copy_path),
                            onClick = { onCopyPath(currentFile) }
                        )
                        SandboxPreviewActionButton(
                            icon = Icons.Rounded.Download,
                            label = stringResource(R.string.sandbox_file_save),
                            onClick = { onSave(currentFile) }
                        )
                        SandboxPreviewActionButton(
                            icon = Icons.Rounded.Delete,
                            label = stringResource(R.string.sandbox_file_confirm_delete),
                            onClick = { onDelete(currentFile) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SandboxPreviewActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = AppShapes.ButtonPill,
        color = Color.White.copy(alpha = 0.15f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

// ===== 展示辅助 =====

/** 可在应用内直接预览的文本类文件（点击后不再调起系统查看器） */
private enum class SandboxTextPreviewKind { MARKDOWN, HTML }

private fun sandboxTextPreviewKindOf(file: SandboxFileInfo): SandboxTextPreviewKind? {
    return when (file.extension?.lowercase()) {
        "md", "markdown", "txt" -> SandboxTextPreviewKind.MARKDOWN
        "html", "htm" -> SandboxTextPreviewKind.HTML
        else -> null
    }
}

private fun sandboxFileIconFor(extension: String?): ImageVector {
    val ext = extension?.lowercase()
    return when (ext) {
        "pdf" -> Icons.Rounded.PictureAsPdf
        "zip", "rar", "7z", "tar", "gz" -> Icons.Rounded.FolderZip
        "py", "js", "json", "xml", "html", "css", "ts", "java", "kt" -> Icons.Rounded.Code
        "csv", "xlsx", "xls" -> Icons.Rounded.TableChart
        "mp3", "wav", "m4a", "ogg", "flac" -> Icons.Rounded.AudioFile
        "mp4", "mov", "avi", "mkv", "webm" -> Icons.Rounded.VideoFile
        "txt", "md", "doc", "docx", "log" -> Icons.Rounded.Description
        else -> Icons.Rounded.InsertDriveFile
    }
}

private fun formatSandboxFileSize(bytes: Long): String {
    return when {
        bytes >= 1 shl 30 -> "%.1f GB".format(bytes.toDouble() / (1 shl 30))
        bytes >= 1 shl 20 -> "%.1f MB".format(bytes.toDouble() / (1 shl 20))
        bytes >= 1 shl 10 -> "%.1f KB".format(bytes.toDouble() / (1 shl 10))
        else -> "$bytes B"
    }
}

private fun formatSandboxFileTime(lastModified: Long): String {
    if (lastModified <= 0) return ""
    return Instant.ofEpochMilli(lastModified)
        .atZone(ZoneId.systemDefault())
        .format(sandboxFileDateFormatter)
}

/**
 * 分组头：文件夹图标 + 展示名 + 组内文件数。
 */
@Composable
private fun SandboxGroupHeader(group: SandboxFileGroup) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Rounded.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = group.label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = group.files.size.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}
