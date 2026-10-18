package me.rerere.rikkahub.ui.pages.call

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.core.data.db.entity.CallRecordEntity
import me.rerere.rikkahub.core.data.repository.CallRecordRepository
import me.rerere.rikkahub.core.data.repository.ConversationRepository
import me.rerere.rikkahub.service.voice.VoiceMessagePlayer
import java.io.File

private const val TAG = "CallDetailVM"

/**
 * 通话详情页 ViewModel（只读页，无发送输入框）。
 *
 * - 订阅通话记录实体（[CallRecordEntity]）；
 * - 拉取该 callId 的真实通话转写（messageSource == CALL）；
 * - 复用全局单例 [VoiceMessagePlayer] 播放录音（与聊天页语音条互斥）；
 * - 录音永久保存 / 手动删除。
 */
class CallDetailVM(
    private val callId: String,
    private val context: Application,
    private val callRecordRepository: CallRecordRepository,
    private val conversationRepository: ConversationRepository,
    private val voiceMessagePlayer: VoiceMessagePlayer,
) : ViewModel() {

    /** 录音播放 key（与聊天页语音条 key 空间保持一致，避免冲突）。 */
    val audioKey: String = "call_audio:$callId"

    val record: StateFlow<CallRecordEntity?> = callRecordRepository.getByIdFlow(callId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 全局播放器状态（仅当播放 key == audioKey 时对本页有意义）。 */
    val playback = voiceMessagePlayer.playback

    private val _transcript = MutableStateFlow<List<UIMessage>>(emptyList())
    val transcript: StateFlow<List<UIMessage>> = _transcript.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)

    /** 一次性操作结果提示（保存/删除），UI 用 Snackbar/Toast 展示后调用 [clearMessage]。 */
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        // 记录可用后拉取真实转写；记录刷新（保存/删除录音）时重拉，保证 UI 一致。
        viewModelScope.launch {
            record.collect { r ->
                if (r == null) return@collect
                _transcript.value = conversationRepository.getCallTranscript(r.conversationId, callId)
            }
        }
    }

    /** 当前录音文件（不存在返回 null）。 */
    fun audioFile(record: CallRecordEntity?): File? {
        val relative = record?.audio ?: return null
        val file = File(context.filesDir, relative)
        return if (file.exists()) file else null
    }

    /** 录音是否已过期（按 audioExpiresAt 业务期限判断，即使物理文件尚未清理）。 */
    fun isExpired(record: CallRecordEntity?): Boolean {
        val expiresAt = record?.audioExpiresAt ?: return false
        return record.forever == 0 && System.currentTimeMillis() > expiresAt
    }

    /** 播放 / 暂停（不可用时忽略）。 */
    fun togglePlay(record: CallRecordEntity?) {
        val file = audioFile(record) ?: return
        val uri: Uri = file.toUri()
        voiceMessagePlayer.playOrToggle(audioKey, uri)
    }

    fun seekTo(positionMs: Long) = voiceMessagePlayer.seekTo(audioKey, positionMs)

    fun setSpeed(speed: Float) = voiceMessagePlayer.setSpeed(audioKey, speed)

    /** 永久保存：forever=1，自动清理任务跳过该录音。 */
    fun saveForever() {
        viewModelScope.launch {
            runCatching { callRecordRepository.updateForever(callId, 1) }
                .onSuccess { _message.value = "已保存，录音将不会自动删除" }
                .onFailure { Log.w(TAG, "saveForever failed", it) }
        }
    }

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
                _message.value = "录音已不存在"
                return@launch
            }
            val ok = withContext(Dispatchers.IO) {
                val file = File(context.filesDir, relative)
                if (!file.exists()) true else runCatching { file.delete() }.getOrDefault(false)
            }
            if (ok) {
                runCatching { callRecordRepository.clearAudio(callId) }
                _message.value = "录音已删除"
            } else {
                Log.w(TAG, "deleteAudio: file delete failed, keep record for retry: $relative")
                _message.value = "删除失败，请稍后重试"
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
