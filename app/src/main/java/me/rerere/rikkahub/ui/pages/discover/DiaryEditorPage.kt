package me.rerere.rikkahub.ui.pages.discover

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.core.data.db.entity.DiaryImage
import me.rerere.rikkahub.core.data.db.entity.OcrStatus
import me.rerere.rikkahub.common.ui.components.FullscreenLoadingOverlay
import me.rerere.rikkahub.ui.components.crop.FourCornerCropScreen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.components.ui.permission.PermissionCamera
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.theme.AppShapes
import org.koin.androidx.compose.koinViewModel
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryEditorPage(
    diaryId: String,
    entryType: String = "text", // "text" 直接记录（默认），"scan" 文档扫描模式
    vm: DiaryVM = koinViewModel()
) {
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val haptics = rememberPremiumHaptics()
    val scope = rememberCoroutineScope()
    val isNew = diaryId == "new"
    val isSaving by vm.isSaving.collectAsStateWithLifecycle()

    var selectedAssistantId by remember { mutableStateOf("USER") }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var content by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }

    // 手写日记图片状态
    var imageBitmaps by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    // 裁剪队列：多张图片按顺序逐张裁剪
    var cropQueue by remember { mutableStateOf<List<Uri>>(emptyList()) }

    // 编辑时：被用户删除的已有图片 id 集合
    val removedExistingImageIds = remember { mutableStateListOf<String>() }

    // 图片放大预览
    var previewImagePaths by remember { mutableStateOf<List<String>?>(null) }
    var previewInitialIndex by remember { mutableIntStateOf(0) }
    var isPreparingPreview by remember { mutableStateOf(false) }

    val filteredDiaries by vm.filteredDiaries.collectAsStateWithLifecycle()
    val schedules by vm.getSchedulesForDate(selectedDate).collectAsStateWithLifecycle(emptyList())

    // 现有日记图片：从 filteredDiaries 动态派生，DB 有更新则立即响应
    val existingImages: List<DiaryImage> by remember(filteredDiaries, diaryId) {
        derivedStateOf {
            filteredDiaries.find { it.id == diaryId }?.images.orEmpty()
        }
    }

    // 保留的已有图片 = 已有图片 - 被用户删除的（derivedStateOf 避免每帧重复计算）
    val keptExistingImages: List<DiaryImage> by remember(existingImages, removedExistingImageIds) {
        derivedStateOf {
            existingImages.filter { it.id !in removedExistingImageIds }
        }
    }

    // 初始数据加载
    LaunchedEffect(diaryId, filteredDiaries) {
        if (!isNew) {
            filteredDiaries.find { it.id == diaryId }?.let {
                selectedAssistantId = it.assistantId
                content = it.content
                selectedDate = runCatching { LocalDate.parse(it.date) }.getOrDefault(LocalDate.now())
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current

    // ====== 文档扫描/拍照相关（提升到顶层，便于 entryType=scan 时自动触发） ======
    val cameraPermission = rememberPermissionState(PermissionCamera)
    var cameraOutputUri by remember { mutableStateOf<Uri?>(null) }
    var cameraOutputFile by remember { mutableStateOf<File?>(null) }

    // 选择图片后进入裁剪流程（无论单张还是多张，都按顺序逐张裁剪）
    val handleAddImageUris: (List<Uri>) -> Unit = { uris ->
        if (uris.isNotEmpty()) {
            cropQueue = cropQueue + uris
        }
    }

    // 拍照 Launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { captureSuccessful ->
        if (captureSuccessful && cameraOutputUri != null) {
            handleAddImageUris(listOf(cameraOutputUri!!))
        } else {
            cameraOutputFile?.delete()
        }
        cameraOutputFile = null
        cameraOutputUri = null
    }

    // 相册 Launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            handleAddImageUris(uris)
        }
    }

    // 触发拍照：检查权限后启动 cameraLauncher
    val startCamera: () -> Unit = {
        if (cameraPermission.allRequiredPermissionsGranted) {
            cameraOutputFile = context.cacheDir.resolve("diary_camera_${Uuid.random()}.jpg")
            cameraOutputUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                cameraOutputFile!!
            )
            cameraLauncher.launch(cameraOutputUri!!)
        } else {
            cameraPermission.requestPermissions()
        }
    }

    val startGallery: () -> Unit = { galleryLauncher.launch("image/*") }

    // 新建 + USER + 扫描模式：直接启动相册选择器（相册里自带拍照入口，二步合一）
    LaunchedEffect(Unit) {
        if (isNew && entryType == "scan" && selectedAssistantId == "USER") {
            startGallery()
        }
    }

    // 判断是否为手写日记模式：
    // - 新建：由 entryType 决定（"scan" = 手写，"text" = 直接记录）
    // - 编辑：由日记是否有图片决定（有图片 = 手写，无图片 = 直接记录）
    val isHandwriteMode = if (isNew) {
        entryType == "scan"
    } else {
        existingImages.isNotEmpty() || imageBitmaps.isNotEmpty()
    }

    val onSave = {
        if (isNew) {
            if (isHandwriteMode) {
                // 新建手写日记：只保存图片文件 + 写 DB，不再触发本地 OCR
                if (imageBitmaps.isEmpty()) {
                    // 没有图片，不保存
                } else {
                    vm.saveDiaryWithImages(
                        assistantId = "USER",
                        date = selectedDate.toString(),
                        imageBitmaps = imageBitmaps
                    ) {
                        toaster.show(vm.app.getString(R.string.diary_add_success))
                        navController.popBackStack()
                    }
                }
            } else {
                // 新建直接记录：保存文本
                if (content.isBlank()) {
                    // 内容为空不保存
                } else {
                    vm.saveDiary(assistantId = "USER", content = content, date = selectedDate.toString())
                    toaster.show(vm.app.getString(R.string.diary_add_success))
                    navController.popBackStack()
                }
            }
        } else {
            // 编辑模式
            if (isHandwriteMode) {
                // 编辑手写日记：统一走 vm.updateDiaryWithImages（内部维护 isSaving）
                vm.updateDiaryWithImages(
                    diaryId = diaryId,
                    keptExistingImages = keptExistingImages,
                    newImageBitmaps = imageBitmaps,
                    content = content,
                    assistantId = selectedAssistantId,
                    date = selectedDate.toString()
                ) { deleted ->
                    if (deleted) {
                        toaster.show("日记已删除")
                    } else {
                        toaster.show("已保存修改")
                    }
                    navController.popBackStack()
                }
            } else {
                // 编辑直接记录：保存文本
                vm.saveDiary(id = diaryId, assistantId = selectedAssistantId, content = content, date = selectedDate.toString())
                toaster.show("已保存修改")
                navController.popBackStack()
            }
        }
    }

    // 保存中禁止返回，避免图片文件写了一半用户就走
    BackHandler(enabled = !isSaving) { navController.popBackStack() }

    // 四角裁剪界面（从队列中逐张裁剪）
    val cropUri = cropQueue.firstOrNull()
    if (cropUri != null) {
        FourCornerCropScreen(
            sourceUri = cropUri,
            onCropComplete = { croppedBitmap ->
                imageBitmaps = imageBitmaps + croppedBitmap
                // 移除已裁剪的第一张，队列中还有则自动显示下一张
                cropQueue = cropQueue.drop(1)
            },
            onCancel = {
                // 取消裁剪：清空整个剩余队列
                cropQueue = emptyList()
            }
        )
    }

    // 处理相机权限 Rationale Dialog
    PermissionManager(permissionState = cameraPermission) {
        Scaffold(
        topBar = {
            OneUITopAppBar(
                title = stringResource(
                    when {
                        isNew && isHandwriteMode -> R.string.diary_scan_entry_title
                        isNew && !isHandwriteMode -> R.string.diary_add_type_direct
                        !isNew && isHandwriteMode -> R.string.diary_scan_entry_title
                        else -> R.string.diary_edit
                    }
                ),
                scrollBehavior = scrollBehavior,
                navigationIcon = { BackButton() },
                actions = {
                    if (isSaving) {
                        // 保存中：显示 loading，禁用点击
                        Box(
                            modifier = Modifier.size(48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    } else {
                        IconButton(
                            onClick = {
                                haptics.perform(HapticPattern.Pop)
                                onSave()
                            }
                        ) {
                            Icon(Icons.Rounded.Check, null)
                        }
                    }
                }
            )
        },
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .imePadding()
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
            ) {
                // 日期选择（新建和编辑都可修改日期）
                Text(stringResource(R.string.diary_select_date), style = MaterialTheme.typography.labelMedium)
                Box(modifier = Modifier.padding(vertical = 8.dp)) {
                    AssistChip(
                        onClick = { showDatePicker = true },
                        label = { Text(selectedDate.toString()) },
                        leadingIcon = { Icon(Icons.Rounded.CalendarToday, null, modifier = Modifier.size(16.dp)) }
                    )
                }

                if (showDatePicker) {
                    val datePickerState = rememberDatePickerState(
                        initialSelectedDateMillis = selectedDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    )
                    DatePickerDialog(
                        onDismissRequest = { showDatePicker = false },
                        confirmButton = {
                            TextButton(onClick = {
                                datePickerState.selectedDateMillis?.let {
                                    selectedDate = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                                }
                                showDatePicker = false
                            }) { Text(stringResource(R.string.confirm)) }
                        }
                    ) { DatePicker(state = datePickerState) }
                }

                Spacer(Modifier.height(16.dp))

                if (isHandwriteMode) {
                    // ===== 手写日记模式：只显示从相册导入 + 图片预览 =====
                    DiaryImagePickerSection(
                        imageBitmaps = imageBitmaps,
                        startGallery = startGallery,
                        onRemoveImage = { index ->
                            imageBitmaps = imageBitmaps.toMutableList().also { it.removeAt(index) }
                        },
                        onClickNewImage = { index ->
                            isPreparingPreview = true
                            scope.launch(Dispatchers.IO) {
                                val tempDir = File(context.cacheDir, "diary_preview").apply { mkdirs() }
                                val paths = imageBitmaps.mapIndexed { i, bm ->
                                    val f = File(tempDir, "preview_$i.png")
                                    runCatching {
                                        java.io.FileOutputStream(f).use { os ->
                                            bm.compress(Bitmap.CompressFormat.PNG, 100, os)
                                        }
                                    }
                                    f.absolutePath
                                }
                                previewInitialIndex = index
                                previewImagePaths = paths
                                isPreparingPreview = false
                            }
                        }
                    )

                    // 编辑模式下展示已有图片（可删除，可预览），并提供"继续添加图片"入口
                    if (!isNew && keptExistingImages.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        ExistingImagesSection(
                            images = keptExistingImages,
                            startGallery = startGallery,
                            onRemoveImage = { imageId ->
                                removedExistingImageIds.add(imageId)
                            },
                            onClickImage = { index ->
                                previewInitialIndex = index
                                previewImagePaths = keptExistingImages.map { it.imagePath }
                            }
                        )
                    }
                } else {
                    // ===== 直接记录模式：只显示文本编辑器 =====
                    Text(
                        text = if (isNew) stringResource(R.string.diary_write_hint) else "修改内容",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = AppShapes.CardLarge,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    ) {
                        BasicTextField(
                            value = content,
                            onValueChange = { content = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 350.dp)
                                .padding(20.dp),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                                lineHeight = 26.sp
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                            decorationBox = { innerTextField ->
                                if (content.isEmpty()) {
                                    Text(
                                        "开始记录...",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                    )
                                }
                                innerTextField()
                            }
                        )
                    }

                    // 只有新建模式时，才显示今日日程关联
                    if (isNew && schedules.isNotEmpty()) {
                        Spacer(Modifier.height(32.dp))
                        Text(stringResource(R.string.diary_schedule_link), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        schedules.forEach { schedule ->
                            Card(
                                onClick = {
                                    haptics.perform(HapticPattern.Pop)
                                    val item = "\n- [${if (schedule.isCompleted) "x" else " "}] ${schedule.title}"
                                    content += item
                                },
                                modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            ) {
                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = schedule.isCompleted, onCheckedChange = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(schedule.title, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }

            // 保存中：统一全屏加载蒙板（与 ChatPage 归档动画风格一致）
            FullscreenLoadingOverlay(
                visible = isSaving,
                icon = Icons.Rounded.Image,
                hint = "正在保存中…"
            )

            // 图片预览准备中：Bitmap 写缓存文件需要一点时间
            FullscreenLoadingOverlay(
                visible = isPreparingPreview,
                icon = Icons.Rounded.Image,
                hint = "加载中…"
            )
        }
        } // PermissionManager 结束

        // 图片放大预览
        previewImagePaths?.let { paths ->
            ImagePreviewDialog(
                images = paths,
                onDismissRequest = { previewImagePaths = null },
                initialIndex = previewInitialIndex
            )
        }
    }
}

/**
 * 手写日记图片选择区域（新建模式）。
 * 只提供从相册导入（相册自带拍照入口），避免重复的拍照选择。
 */
@Composable
private fun DiaryImagePickerSection(
    imageBitmaps: List<Bitmap>,
    startGallery: () -> Unit,
    onRemoveImage: (Int) -> Unit,
    onClickNewImage: (Int) -> Unit
) {
    // 导入图片入口：一个大的漂亮 Card（内置相册自带拍照按钮，不再让用户二选一）
    Card(
        onClick = startGallery,
        shape = AppShapes.CardLarge,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.diary_handwrite_gallery),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.diary_scan_gallery_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Spacer(Modifier.height(12.dp))

    // 已选图片预览
    if (imageBitmaps.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(imageBitmaps) { index, bitmap ->
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(AppShapes.CardMedium)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onClickNewImage(index) }
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    // 删除按钮
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.8f))
                            .clickable { onRemoveImage(index) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(16.dp))
}

/**
 * 已有图片展示区域（编辑模式）。
 *
 * 去掉 OCR 状态显示（图片理解交给对话模型按需处理），新增：
 * - 每张图片右上角删除按钮（将 imageId 回调给调用方，调用方负责更新"删除集合"）
 * - 点击卡片主体可放大预览
 * - 顶部提供"继续添加图片"入口（与新建手写日记的导入卡片样式一致）
 */
@Composable
private fun ExistingImagesSection(
    images: List<DiaryImage>,
    startGallery: () -> Unit,
    onRemoveImage: (String) -> Unit,
    onClickImage: (Int) -> Unit
) {
    // 继续添加图片入口
    Card(
        onClick = startGallery,
        shape = AppShapes.CardLarge,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.AddPhotoAlternate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.diary_handwrite_add_more_images),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.diary_handwrite_add_more_images_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Spacer(Modifier.height(12.dp))

    images.forEachIndexed { index, image ->
        EditableImageCard(
            imagePath = image.imagePath,
            onRemove = { onRemoveImage(image.id) },
            onClick = { onClickImage(index) }
        )
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * 编辑页用的可删除图片卡片：图片 + 右上角删除按钮。
 */
@Composable
private fun EditableImageCard(
    imagePath: String,
    onRemove: () -> Unit,
    onClick: () -> Unit
) {
    Box {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = AppShapes.CardMedium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            )
        ) {
            AsyncImage(
                model = File(imagePath),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .clip(AppShapes.CardMedium)
                    .clickable { onClick() },
                contentScale = ContentScale.Fit
            )
        }
        // 右上角删除按钮
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .background(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    shape = CircleShape
                )
                .size(32.dp)
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = stringResource(R.string.delete),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
