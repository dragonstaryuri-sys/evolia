package me.rerere.rikkahub.ui.pages.setting

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import me.rerere.rikkahub.common.ui.components.InlineSpinner
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.notification.NotificationSoundManager
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupItem
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import org.koin.androidx.compose.koinViewModel
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

/** 自定义铃声存放目录（相对 filesDir），需与 VoiceCallManager 的解析逻辑一致。 */
private const val RINGTONE_DIR = "ringtones"

/** 自定义铃声最大文件大小：10 MB。 */
private const val MAX_RINGTONE_SIZE_BYTES = 10L * 1024L * 1024L

/** 自定义铃声最长时长：60 秒。 */
private const val MAX_RINGTONE_DURATION_MS = 60_000L

private const val TAG = "CallRingtone"

private data class SystemRingtoneOption(
    val id: String,
    val title: String,
    val uri: Uri,
)

/** 待裁切的自定义铃声请求。 */
private data class RingtoneTrimRequest(
    val uri: Uri,
    val durationMs: Long,
)

private enum class RingtoneImportError { TOO_LARGE, TOO_LONG, INVALID, IO }

private sealed interface RingtoneImportResult {
    data class Success(val relativePath: String) : RingtoneImportResult
    data class Failure(val error: RingtoneImportError) : RingtoneImportResult
}

@Composable
fun SettingCallRingtonePage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val soundManager = remember { NotificationSoundManager(context) }
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val lazyListState = rememberLazyListState()

    // 通过 RingtoneManager 查询设备实际可用的系统铃声，最多取前两个作为可选项。
    val systemRingtones = remember(context) { querySystemRingtones(context) }
    val fallbackTitle = stringResource(R.string.call_ringtone_system_default)

    val importSuccessMsg = stringResource(R.string.call_ringtone_import_success)
    val errorTooLarge = stringResource(R.string.call_ringtone_error_too_large)
    val errorTooLong = stringResource(R.string.call_ringtone_error_too_long)
    val errorInvalid = stringResource(R.string.call_ringtone_error_invalid)
    val trimFailedMsg = stringResource(R.string.call_ringtone_trim_failed)

    // 待裁切请求：仅当所选音频超过 60 秒时进入应用内裁切器。
    var trimRequest by remember { mutableStateOf<RingtoneTrimRequest?>(null) }
    var trimming by remember { mutableStateOf(false) }

    // 离开页面时立即终止试听，避免音频在后台继续播放。
    DisposableEffect(Unit) {
        onDispose { soundManager.stopPreview() }
    }

    /** 应用一次导入结果：成功则替换设置并试听，失败则提示原因。 */
    fun applyImportResult(result: RingtoneImportResult) {
        when (result) {
            is RingtoneImportResult.Success -> {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        deleteOldCustomRingtone(context, settings.callRingtoneCustomPath, result.relativePath)
                    }
                    vm.updateSettings(
                        settings.copy(
                            callRingtone = "custom",
                            callRingtoneCustomPath = result.relativePath,
                        )
                    )
                    val customFile = File(context.filesDir, "$RINGTONE_DIR/${result.relativePath}")
                    soundManager.playRingtonePreview(Uri.fromFile(customFile))
                    Toast.makeText(context, importSuccessMsg, Toast.LENGTH_SHORT).show()
                }
            }

            is RingtoneImportResult.Failure -> {
                val message = when (result.error) {
                    RingtoneImportError.TOO_LARGE -> errorTooLarge
                    RingtoneImportError.TOO_LONG -> errorTooLong
                    RingtoneImportError.INVALID -> errorInvalid
                    RingtoneImportError.IO -> errorInvalid
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // 选择自定义文件后，立即停止正在试听的系统/默认铃声，避免与后续试听重叠。
        soundManager.stopPreview()
        scope.launch {
            val durationMs = withContext(Dispatchers.IO) { queryDurationMs(context, uri) }
            if (durationMs != null && durationMs > MAX_RINGTONE_DURATION_MS) {
                // 超过 60 秒：交给用户自己裁切，而不是直接拒绝导入。
                trimRequest = RingtoneTrimRequest(uri, durationMs)
            } else {
                val result = withContext(Dispatchers.IO) { importCustomRingtone(context, uri) }
                applyImportResult(result)
            }
        }
    }

    Scaffold(
        topBar = {
            OneUITopAppBar(
                title = stringResource(R.string.call_ringtone_page_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = { BackButton() }
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = lazyListState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            item {
                SettingsGroup(title = "") {
                    systemRingtones.forEach { option ->
                        val selected = settings.callRingtone == option.id
                        SettingGroupItem(
                            title = option.title.ifBlank { fallbackTitle },
                            onClick = {
                                vm.updateSettings(settings.copy(callRingtone = option.id))
                                soundManager.playRingtonePreview(option.uri)
                            },
                            trailing = {
                                if (selected) {
                                    Icon(
                                        Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        )
                    }

                    val customSelected = settings.callRingtone == "custom"
                    val customSubtitle = if (customSelected && settings.callRingtoneCustomPath.isNotBlank()) {
                        settings.callRingtoneCustomPath
                    } else {
                        stringResource(R.string.call_ringtone_custom_desc)
                    }
                    SettingGroupItem(
                        title = stringResource(R.string.call_ringtone_custom),
                        subtitle = customSubtitle,
                        onClick = {
                            // 打开文件选择器前先停掉正在试听的默认铃声，避免两段音频同时播放。
                            soundManager.stopPreview()
                            picker.launch(arrayOf("audio/*"))
                        },
                        trailing = {
                            if (customSelected) {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    )
                }
            }

            item {
                Text(
                    text = stringResource(R.string.call_ringtone_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }

    trimRequest?.let { request ->
        RingtoneTrimDialog(
            context = context,
            sourceUri = request.uri,
            durationMs = request.durationMs,
            trimming = trimming,
            onDismiss = { if (!trimming) trimRequest = null },
            onConfirm = { startMs, endMs ->
                scope.launch {
                    trimming = true
                    soundManager.stopPreview()
                    val trimmedFile = withContext(Dispatchers.IO) {
                        trimRingtone(context, request.uri, startMs, endMs)
                    }
                    if (trimmedFile == null) {
                        Toast.makeText(context, trimFailedMsg, Toast.LENGTH_LONG).show()
                    } else {
                        val knownDurationMs = endMs - startMs
                        val result = withContext(Dispatchers.IO) {
                            val imported = importCustomRingtone(context, Uri.fromFile(trimmedFile), "m4a", knownDurationMs = knownDurationMs)
                            trimmedFile.delete()
                            imported
                        }
                        applyImportResult(result)
                        trimRequest = null
                    }
                    trimming = false
                }
            }
        )
    }
}

/**
 * 音频裁切弹窗：允许用户拖动选择一段不超过 60 秒的片段。
 * 拖动起止位置时会从起点开始试听，到达终点自动停止。
 * 用户确认后由 media3 Transformer 执行裁切。
 */
@Composable
private fun RingtoneTrimDialog(
    context: Context,
    sourceUri: Uri,
    durationMs: Long,
    trimming: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (startMs: Long, endMs: Long) -> Unit,
) {
    val totalMs = durationMs.toFloat().coerceAtLeast(1f)
    val initialSpan = minOf(durationMs, MAX_RINGTONE_DURATION_MS).toFloat()
    var range by remember { mutableStateOf(0f..initialSpan) }

    // 试听播放器：拖动时从 range.start 播放，到 range.endInclusive 自动停止
    val mediaPlayer = remember { MediaPlayer() }
    val stopCheckHandler = remember { Handler(Looper.getMainLooper()) }
    val stopCheckRunnable = remember {
        object : Runnable {
            override fun run() {
                if (!mediaPlayer.isPlaying) return
                val endMs = range.endInclusive.toLong()
                if (mediaPlayer.currentPosition >= endMs) {
                    runCatching { mediaPlayer.pause() }
                    return
                }
                stopCheckHandler.postDelayed(this, 100)
            }
        }
    }

    fun startPreview() {
        runCatching {
            if (mediaPlayer.isPlaying) mediaPlayer.pause()
            mediaPlayer.reset()
            mediaPlayer.setDataSource(context, sourceUri)
            mediaPlayer.setOnPreparedListener {
                runCatching {
                    it.seekTo(range.start.toInt())
                    it.start()
                    stopCheckHandler.postDelayed(stopCheckRunnable, 100)
                }
            }
            mediaPlayer.prepareAsync()
        }
    }

    fun stopPreview() {
        stopCheckHandler.removeCallbacks(stopCheckRunnable)
        runCatching { if (mediaPlayer.isPlaying) mediaPlayer.pause() }
    }

    DisposableEffect(Unit) {
        onDispose {
            stopCheckHandler.removeCallbacks(stopCheckRunnable)
            runCatching { mediaPlayer.release() }
        }
    }

    AlertDialog(
        onDismissRequest = { stopPreview(); if (!trimming) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = { Text(stringResource(R.string.call_ringtone_trim_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.call_ringtone_trim_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                RangeSlider(
                    value = range,
                    valueRange = 0f..totalMs,
                    enabled = !trimming,
                    onValueChange = { new ->
                        val maxSpan = MAX_RINGTONE_DURATION_MS.toFloat()
                        val startMoved = new.start != range.start
                        range = if (startMoved) {
                            // 片段时长不超过 60s：start >= end - 60s
                            val start = new.start.coerceIn((new.endInclusive - maxSpan).coerceAtLeast(0f), new.endInclusive)
                            start..new.endInclusive
                        } else {
                            // 片段时长不超过 60s：end <= start + 60s
                            val end = new.endInclusive.coerceIn(new.start, (new.start + maxSpan).coerceAtMost(totalMs))
                            new.start..end
                        }
                    },
                    onValueChangeFinished = {
                        if (!trimming) startPreview()
                    }
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatMs(range.start.toLong()), style = MaterialTheme.typography.labelLarge)
                    Text(formatMs(range.endInclusive.toLong()), style = MaterialTheme.typography.labelLarge)
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    enabled = !trimming,
                    onClick = { startPreview() }
                ) {
                    Text(stringResource(R.string.call_ringtone_trim_play_preview))
                }
                if (trimming) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InlineSpinner(size = 20.dp, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.call_ringtone_trim_processing),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !trimming,
                onClick = {
                    stopPreview()
                    onConfirm(range.start.toLong(), range.endInclusive.toLong())
                },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(stringResource(R.string.call_ringtone_trim_confirm))
            }
        },
        dismissButton = {
            TextButton(
                enabled = !trimming,
                onClick = { stopPreview(); onDismiss() },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Text(stringResource(R.string.call_ringtone_trim_cancel))
            }
        }
    )
}

/** 查询设备实际可用的系统铃声，最多返回前两个（id 依次为 system1 / system2）。 */
private fun querySystemRingtones(context: Context): List<SystemRingtoneOption> {
    val result = mutableListOf<SystemRingtoneOption>()
    runCatching {
        val manager = RingtoneManager(context).apply {
            setType(RingtoneManager.TYPE_RINGTONE)
        }
        val cursor = manager.cursor ?: return@runCatching
        cursor.use {
            while (it.moveToNext() && result.size < 2) {
                val position = it.position
                val uri = manager.getRingtoneUri(position) ?: continue
                val title = it.getString(RingtoneManager.TITLE_COLUMN_INDEX).orEmpty()
                result.add(SystemRingtoneOption("system${result.size + 1}", title, uri))
            }
        }
    }
    return result
}

/**
 * 校验并导入自定义铃声：
 * 1. 文件大小不超过 10 MB；
 * 2. 时长不超过 60 秒且可被解析；
 * 3. 复制到私有 filesDir/ringtones/ 并返回相对文件名（保证重启后仍可访问）。
 */
private fun importCustomRingtone(
    context: Context,
    uri: Uri,
    extensionOverride: String? = null,
    knownDurationMs: Long? = null,
): RingtoneImportResult {
    val size = queryFileSize(context, uri)
    if (size != null && size > MAX_RINGTONE_SIZE_BYTES) {
        return RingtoneImportResult.Failure(RingtoneImportError.TOO_LARGE)
    }

    // 若调用方已知时长（例如刚由应用内裁切器产出），直接信任该值，
    // 避免 MediaMetadataRetriever 读取到错误的容器时长元数据导致误判超 60s。
    val durationMs = knownDurationMs ?: queryDurationMs(context, uri)
    Log.i(TAG, "importCustomRingtone: uri=$uri, size=$size, knownDurationMs=$knownDurationMs, resolvedDurationMs=$durationMs")
    if (durationMs != null && durationMs > MAX_RINGTONE_DURATION_MS) {
        return RingtoneImportResult.Failure(RingtoneImportError.TOO_LONG)
    }

    return try {
        val dir = File(context.filesDir, RINGTONE_DIR).apply { mkdirs() }
        val extension = extensionOverride ?: resolveExtension(context, uri)
        val target = File(dir, "custom_${System.currentTimeMillis()}.$extension")

        val inputStream = context.contentResolver.openInputStream(uri)
            ?: return RingtoneImportResult.Failure(RingtoneImportError.INVALID)
        inputStream.use { source ->
            FileOutputStream(target).use { output -> source.copyTo(output) }
        }

        if (target.length() <= 0L) {
            target.delete()
            return RingtoneImportResult.Failure(RingtoneImportError.INVALID)
        }
        RingtoneImportResult.Success(target.name)
    } catch (e: Exception) {
        RingtoneImportResult.Failure(RingtoneImportError.IO)
    }
}

/**
 * 使用 media3 Transformer 把 [sourceUri] 裁切为 [startMs, endMs) 片段。
 * 返回裁切后的临时文件；失败返回 null。必须在主线程（带 Looper）调用。
 */
private suspend fun trimRingtone(
    context: Context,
    sourceUri: Uri,
    startMs: Long,
    endMs: Long,
): File? = withContext(Dispatchers.Main) {
    suspendCancellableCoroutine<File?> { cont ->
        val outputFile = File(context.cacheDir, "ringtone_trim_${System.currentTimeMillis()}.mp4")
        if (outputFile.exists()) outputFile.delete()

        val requestedDurationMs = endMs - startMs
        Log.i(TAG, "trimRingtone: source=$sourceUri, startMs=$startMs, endMs=$endMs, requestedDurationMs=$requestedDurationMs")

        val mediaItem = MediaItem.Builder()
            .setUri(sourceUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs)
                    .build()
            )
            .build()
        val editedMediaItem = EditedMediaItem.Builder(mediaItem).build()

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                val outputSize = outputFile.length()
                val outputDuration = queryDurationMs(context, Uri.fromFile(outputFile))
                Log.i(TAG, "trimRingtone onCompleted: output=${outputFile.absolutePath}, size=$outputSize, metadataDurationMs=$outputDuration, requestedDurationMs=$requestedDurationMs")
                if (cont.isActive) cont.resume(outputFile)
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exception: ExportException,
            ) {
                Log.e(TAG, "trimRingtone onError", exception)
                if (cont.isActive) cont.resume(null)
            }
        }

        runCatching {
            Transformer.Builder(context)
                // 关闭 MP4 edit list 快速裁切，强制重编码，
                // 否则输出文件 mvhd 时长仍为原始时长，
                // MediaMetadataRetriever 会误判为超过 60s。
                .experimentalSetMp4EditListTrimEnabled(false)
                .addListener(listener)
                .build()
                .start(editedMediaItem, outputFile.absolutePath)
        }.onFailure {
            if (cont.isActive) cont.resume(null)
        }
    }
}

private fun queryFileSize(context: Context, uri: Uri): Long? = runCatching {
    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
        descriptor.length.takeIf { it >= 0L }
    }
}.getOrNull()

private fun queryDurationMs(context: Context, uri: Uri): Long? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    } catch (e: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

private fun resolveExtension(context: Context, uri: Uri): String {
    val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
    return when {
        mime == null -> "audio"
        mime.contains("mpeg") || mime.contains("mp3") -> "mp3"
        mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac") -> "m4a"
        mime.contains("ogg") -> "ogg"
        mime.contains("wav") -> "wav"
        mime.contains("flac") -> "flac"
        else -> "audio"
    }
}

private fun formatMs(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

private fun deleteOldCustomRingtone(context: Context, oldRelativePath: String, newRelativePath: String) {
    if (oldRelativePath.isBlank() || oldRelativePath == newRelativePath) return
    runCatching {
        val oldFile = File(File(context.filesDir, RINGTONE_DIR), oldRelativePath)
        if (oldFile.exists()) oldFile.delete()
    }
}
