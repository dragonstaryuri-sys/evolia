package me.rerere.rikkahub.core.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.common.JsonInstant
import me.rerere.rikkahub.core.data.model.*
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid

class DoubaoImportManager(
    private val context: Context,
    private val conversationRepo: ConversationRepository,
) {
    private val _progress = MutableStateFlow(0f)
    val progress = _progress.asStateFlow()

    private val _progressText = MutableStateFlow("")
    val progressText = _progressText.asStateFlow()

    private val _status = MutableStateFlow("")
    val status = _status.asStateFlow()

    private val importLog = StringBuilder()

    fun getLog() = importLog.toString()

    /**
     * 清空当前所有导入日志和进度状态
     */
    fun clear() {
        importLog.clear()
        _progress.value = 0f
        _progressText.value = ""
        _status.value = ""
    }

    private fun log(message: String) {
        val time = DateTimeFormatter.ofPattern("HH:mm:ss").format(java.time.LocalTime.now())
        val logLine = "[$time] $message"
        importLog.append(logLine).append("\n")
        _status.value = message
        Log.d("DoubaoImport", logLine)
    }

    suspend fun parseData(uri: Uri): DoubaoImportData? = withContext(Dispatchers.IO) {
        try {
            val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            content?.let { JsonInstant.decodeFromString<DoubaoImportData>(it) }
        } catch (e: Exception) {
            log("解析文件失败: ${e.localizedMessage}")
            null
        }
    }

    suspend fun performImport(
        data: DoubaoImportData,
        assistantId: Uuid,
        roundsPerSession: Int,
        onPreviewRequest: suspend (Conversation) -> Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            log("开始导入流程，目标智能体: ${data.botInfo.name}")

            // 1. 完善提取逻辑: text 优先 (过滤[卡片]), 备选 tts_content
            val validHistory = data.chatHistory.mapNotNull { item ->
                val textContent = item.content?.text?.trim()
                val ttsContent = item.content?.ttsContent?.trim()

                val finalContent = when {
                    // 如果 text 有内容且不是 [卡片]，则使用它
                    !textContent.isNullOrBlank() && textContent != "[卡片]" -> textContent
                    // 否则，如果 tts_content 有内容，则使用它
                    !ttsContent.isNullOrBlank() -> ttsContent
                    // 两者都没有或者是 text 为 [卡片] 且 tts 为空，则跳过
                    else -> null
                }

                if (finalContent != null) {
                    item to finalContent
                } else {
                    null
                }
            }

            val totalMessages = validHistory.size
            log("经过滤后有效消息总数: $totalMessages")

            if (totalMessages == 0) {
                log("数据源中未检测到有效对话，导入终止。")
                return@withContext false
            }

            val messagesPerSession = (roundsPerSession * 2).coerceIn(2, 100)
            val chunks = validHistory.chunked(messagesPerSession)
            log("计划切分为 ${chunks.size} 个会话片段。")

            var importedCount = 0

            chunks.forEachIndexed { index, chunk ->
                val conversationId = Uuid.random()
                val messageNodes = chunk.map { (item, content) ->
                    val role = if (item.userType == "assistant") MessageRole.ASSISTANT else MessageRole.USER
                    val timestamp = item.createTime.toLongOrNull() ?: (System.currentTimeMillis() / 1000)

                    val createdAt = Instant.fromEpochSeconds(timestamp)
                        .toLocalDateTime(TimeZone.currentSystemDefault())

                    UIMessage(
                        id = Uuid.random(),
                        role = role,
                        parts = listOf(UIMessagePart.Text(content)),
                        createdAt = createdAt
                    ).toMessageNode()
                }

                val conversation = Conversation(
                    id = conversationId,
                    assistantId = assistantId,
                    title = "${data.botInfo.name} 历史导入 (${index + 1})",
                    messageNodes = messageNodes,
                    createAt = java.time.Instant.ofEpochSecond(chunk.first().first.createTime.toLongOrNull() ?: 0L)
                )

                if (index == 0) {
                    log("正在展示第一个会话预览，等待确认...")
                    if (!onPreviewRequest(conversation)) {
                        log("用户在预览阶段选择了终止导入。")
                        return@withContext false
                    }
                    log("预览确认通过，开始批量入库...")
                }

                conversationRepo.insertConversation(conversation)
                importedCount += chunk.size

                _progress.value = importedCount.toFloat() / totalMessages
                _progressText.value = "$importedCount/$totalMessages"
                log("已入库: $importedCount 消息 (进度 ${index + 1}/${chunks.size})")
            }

            log("导入同步已圆满完成！")
            true
        } catch (e: Exception) {
            log("导入发生异常: ${e.localizedMessage}")
            e.printStackTrace()
            false
        }
    }
}
