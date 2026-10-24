package me.rerere.rikkahub.ui.pages.call

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.core.data.db.entity.CallRecordEntity
import me.rerere.rikkahub.core.data.repository.CallRecordRepository
import me.rerere.rikkahub.core.data.repository.ConversationRepository
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.voice.CallRecorder
import me.rerere.rikkahub.service.voice.VoiceMessagePlayer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "CallDetailVM"

/** 一次性操作提示：UI 用全局顶部 Toaster 展示，copyText 非空时附带「复制路径」动作。 */
data class CallDetailMessage(
    val text: String,
    val copyText: String? = null
)

/** 录音导出到公共目录的结果。 */
private sealed interface ExportResult {
    /** 成功：newPath 为公共目录绝对路径（写入 DB 的 audio 字段），displayPath 为面向用户的展示路径。 */
    data class Success(val newPath: String, val displayPath: String) : ExportResult

    /** 系统不支持公共目录写入（Android 9 及以下）：降级为仅应用内保存。 */
    data object LegacyFallback : ExportResult

    data class Error(val reason: String) : ExportResult
}

/**
 * 通话详情页 ViewModel（只读页，无发送输入框）。
 *
 * - 订阅通话记录实体（[CallRecordEntity]）；
 * - 拉取该 callId 的真实通话转写（messageSource == CALL）；
 * - 复用全局单例 [VoiceMessagePlayer] 播放录音（与聊天页语音条互斥）；
 * - 录音永久保存（移动到公共 Download/Evolia，单份存储）/ 手动删除。
 */
class CallDetailVM(
    private val callId: String,
    private val context: Application,
    private val callRecordRepository: CallRecordRepository,
    private val conversationRepository: ConversationRepository,
    private val voiceMessagePlayer: VoiceMessagePlayer,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    /** 录音播放 key（与聊天页语音条 key 空间保持一致，避免冲突）。 */
    val audioKey: String = "call_audio:$callId"

    val record: StateFlow<CallRecordEntity?> = callRecordRepository.getByIdFlow(callId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 通话对应的助手名称（根据 record.assistantId 查找，找不到回退"助手"）。 */
    val assistantName: StateFlow<String> = combine(record, settingsStore.settingsFlow) { r, settings ->
        val id = runCatching { Uuid.parse(r?.assistantId.orEmpty()) }.getOrNull()
        id?.let { settings.assistants.find { a -> a.id == it }?.name }.orEmpty()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** 用户昵称（来自显示设置，空则回退"用户"）。 */
    val userName: StateFlow<String> = settingsStore.settingsFlow
        .map { it.displaySetting.userNickname.ifEmpty { "用户" } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "用户")

    /** 全局播放器状态（仅当播放 key == audioKey 时对本页有意义）。 */
    val playback = voiceMessagePlayer.playback

    /** 当前播放倍速（持久化在播放器层，滑动后重建仍保持）。 */
    val speed = voiceMessagePlayer.speed

    private val _transcript = MutableStateFlow<List<UIMessage>>(emptyList())
    val transcript: StateFlow<List<UIMessage>> = _transcript.asStateFlow()

    private val _message = MutableStateFlow<CallDetailMessage?>(null)

    /** 一次性操作结果提示（保存/删除），UI 展示后调用 [clearMessage]。 */
    val message: StateFlow<CallDetailMessage?> = _message.asStateFlow()

    init {
        // 记录可用后拉取真实转写；记录刷新（保存/删除录音）时重拉，保证 UI 一致。
        viewModelScope.launch {
            record.collect { r ->
                if (r == null) return@collect
                _transcript.value = conversationRepository.getCallTranscript(r.conversationId, callId)

                // 自动检测：audio 指向的文件被用户在外部移动/删除时，
                // 直接清空 audio/forever，播放器显示「无通话录音」，而不是点播放才发现。
                val storedAudio = r.audio ?: return@collect
                val fileExists = withContext(Dispatchers.IO) {
                    CallRecorder.resolveAudioFile(context, storedAudio)?.exists() == true
                }
                if (!fileExists) {
                    Log.w(TAG, "audio file missing, reset audio/forever: $storedAudio")
                    runCatching { callRecordRepository.clearAudio(callId) }
                }
            }
        }
    }

    /** 当前录音文件（不存在返回 null）。兼容私有目录相对路径与公共目录绝对路径。 */
    fun audioFile(record: CallRecordEntity?): File? {
        val stored = record?.audio ?: return null
        return CallRecorder.resolveAudioFile(context, stored)?.takeIf { it.exists() }
    }

    /** 录音是否已过期（按 audioExpiresAt 业务期限判断，即使物理文件尚未清理）。 */
    fun isExpired(record: CallRecordEntity?): Boolean {
        val expiresAt = record?.audioExpiresAt ?: return false
        return record.forever == 0 && System.currentTimeMillis() > expiresAt
    }

    /** 播放 / 暂停；文件已被外部移动/删除时提示并修正记录（兜底：覆盖页面停留期间文件被删的情况）。 */
    fun togglePlay(record: CallRecordEntity?) {
        val file = audioFile(record)
        if (file == null) {
            // audio 字段还有值但文件不存在：清空 audio/forever 并提示
            if (record?.audio != null) {
                viewModelScope.launch {
                    Log.w(TAG, "togglePlay: audio file missing, reset audio/forever: ${record.audio}")
                    runCatching { callRecordRepository.clearAudio(callId) }
                    _message.value = CallDetailMessage("找不到录音文件")
                }
            }
            return
        }
        voiceMessagePlayer.playOrToggle(audioKey, file.toUri())
    }

    fun seekTo(positionMs: Long) = voiceMessagePlayer.seekTo(audioKey, positionMs)

    fun setSpeed(speed: Float) = voiceMessagePlayer.setSpeed(audioKey, speed)

    /**
     * 永久保存录音：把音频文件**移动**到公共下载目录 Download/Evolia，全程只保留一份文件。
     *
     * 顺序保证安全可重试：公共目录写入成功 → 更新 DB（audio=公共绝对路径 + forever=1）→ 最后删私有原文件。
     * 任意一步失败都不置 forever（保存按钮保持可用），重试不丢数据；
     * 自动清理（7 天）与"清理缓存"都只针对 forever=0 且在私有目录内的录音，公共目录文件不会被碰。
     */
    fun saveForever() {
        viewModelScope.launch {
            val currentRecord = record.value
            if (currentRecord == null) {
                _message.value = CallDetailMessage("通话记录不存在，无法保存")
                return@launch
            }
            // 异常兜底：audio 已是公共目录绝对路径（理论上必有 forever=1），直接补标记即可
            val alreadyExported = currentRecord.audio?.startsWith("/") == true
            if (alreadyExported) {
                runCatching { callRecordRepository.updateForever(callId, 1) }
                _message.value = CallDetailMessage("录音已保存在 ${currentRecord.audio}")
                return@launch
            }

            val sourceFile = audioFile(currentRecord)
            if (sourceFile == null) {
                _message.value = CallDetailMessage("录音文件不存在，无法保存")
                return@launch
            }

            val assistantDisplayName = assistantName.value.ifEmpty { "通话" }
            val result = withContext(Dispatchers.IO) {
                exportAudioToPublicDownloads(sourceFile, assistantDisplayName)
            }
            when (result) {
                is ExportResult.Success -> {
                    val dbUpdated = runCatching {
                        callRecordRepository.updateAudio(callId, result.newPath, null)
                        callRecordRepository.updateForever(callId, 1)
                    }.isSuccess
                    if (dbUpdated) {
                        // DB 已指向公共目录后再删私有原文件；删除失败仅留孤儿文件并记录日志
                        runCatching { sourceFile.delete() }
                            .onFailure {
                                Log.w(TAG, "saveForever: private file delete failed, orphan tolerated: ${sourceFile.path}")
                            }
                        _message.value = CallDetailMessage(
                            text = "录音已保存至 ${result.displayPath}",
                            copyText = result.newPath
                        )
                    } else {
                        // DB 更新失败：删除刚导出的公共文件，回到可重试状态
                        withContext(Dispatchers.IO) {
                            runCatching { File(result.newPath).delete() }
                        }
                        _message.value = CallDetailMessage("保存失败，请稍后重试")
                    }
                }

                is ExportResult.LegacyFallback -> {
                    // Android 9 及以下无公共目录写权限：仅标记 forever，录音保留在应用内
                    runCatching { callRecordRepository.updateForever(callId, 1) }
                        .onSuccess {
                            _message.value = CallDetailMessage("已保存，录音将保留在应用内（当前系统版本不支持导出到公共目录）")
                        }
                        .onFailure {
                            Log.w(TAG, "saveForever fallback failed", it)
                            _message.value = CallDetailMessage("保存失败，请稍后重试")
                        }
                }

                is ExportResult.Error -> {
                    Log.w(TAG, "saveForever export failed: ${result.reason}")
                    _message.value = CallDetailMessage("导出录音失败：${result.reason}")
                }
            }
        }
    }

    /**
     * 通过 MediaStore 把录音写入公共下载目录 Download/Evolia（单份存储：调用方在成功后再删原文件）。
     * Android 10+ 应用对自己创建的 MediaStore 文件拥有读写权限，无需申请存储权限。
     */
    private fun exportAudioToPublicDownloads(source: File, assistantName: String): ExportResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return ExportResult.LegacyFallback
        }
        return try {
            val timestamp = SimpleDateFormat("MMdd_HHmmss", Locale.getDefault()).format(Date())
            // 清理文件名非法字符，助手名过长时截断，保证在文件管理器里可读
            val sanitizedAssistantName = assistantName
                .replace(Regex("[\\\\/:*?\"<>|\\s]"), "")
                .take(12)
                .ifEmpty { "通话" }
            val displayName = "${sanitizedAssistantName}的通话录音_$timestamp.m4a"
            val relativeDir = "${Environment.DIRECTORY_DOWNLOADS}/Evolia"

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: return ExportResult.Error("无法在公共目录创建文件")

            val output = context.contentResolver.openOutputStream(uri)
            if (output == null) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                return ExportResult.Error("无法写入公共目录文件")
            }
            output.use { out ->
                source.inputStream().use { input -> input.copyTo(out) }
            }

            // 发布文件（清除 pending 后才会出现在文件管理器）
            val published = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            context.contentResolver.update(uri, published, null, null)

            // MediaStore 重名时会自动改名（如 "xxx (1).m4a"），优先查询实际落盘路径
            val constructedPath = File(
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "Evolia"
                ),
                displayName
            ).absolutePath
            val actualPath = queryActualDataPath(uri) ?: constructedPath
            ExportResult.Success(
                newPath = actualPath,
                displayPath = "Download/Evolia/${File(actualPath).name}"
            )
        } catch (e: Exception) {
            Log.w(TAG, "exportAudioToPublicDownloads failed", e)
            ExportResult.Error(e.message ?: "未知错误")
        }
    }

    /** 查询 MediaStore 条目的实际磁盘路径（DATA 列已废弃但仍可用，查询失败返回 null 由调用方回退）。 */
    private fun queryActualDataPath(uri: Uri): String? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()

    /**
     * 删除录音（二次确认后调用）：
     * 先停播放器，再删文件；文件不存在视为已删除直接修正数据库。
     * 文件删除失败保留记录以便重试，不伪装成功。
     */
    fun deleteAudio(record: CallRecordEntity?) {
        viewModelScope.launch {
            voiceMessagePlayer.stop()
            val relative = record?.audio
            if (relative == null) {
                _message.value = CallDetailMessage("录音已不存在")
                return@launch
            }
            val ok = withContext(Dispatchers.IO) {
                val file = CallRecorder.resolveAudioFile(context, relative)
                if (file == null || !file.exists()) {
                    true
                } else {
                    runCatching { file.delete() }.getOrDefault(false)
                }
            }
            if (ok) {
                runCatching { callRecordRepository.clearAudio(callId) }
                _message.value = CallDetailMessage("录音已删除")
            } else {
                Log.w(TAG, "deleteAudio: file delete failed, keep record for retry: $relative")
                _message.value = CallDetailMessage("删除失败，请稍后重试")
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    override fun onCleared() {
        super.onCleared()
        // 全局单例播放器：只停播放，不 release
        voiceMessagePlayer.stop()
    }
}
