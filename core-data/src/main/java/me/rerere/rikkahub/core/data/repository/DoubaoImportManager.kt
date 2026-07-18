package me.rerere.rikkahub.core.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.common.JsonInstant
import me.rerere.rikkahub.core.data.model.*
import java.time.format.DateTimeFormatter
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
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
            if (content.isNullOrBlank()) return@withContext null
            JsonInstant.decodeFromString<DoubaoImportData>(content)
        } catch (e: Exception) {
            log("文件解析失败，可能格式不正确: ${e.localizedMessage}")
            null
        }
    }

    /**
     * 校验数据，返回有效消息的数量
     */
    fun getValidMessageCount(data: DoubaoImportData): Int {
        return data.chatHistory.count { item ->
            val textContent = item.content?.text?.trim()
            val ttsContent = item.content?.ttsContent?.trim()
            (!textContent.isNullOrBlank() && textContent != "[卡片]") || !ttsContent.isNullOrBlank()
        }
    }

    /**
     * 生成导入模板 JSON 字符串
     */
    fun generateTemplateJson(): String {
        val now = System.currentTimeMillis() / 1000
        val templateData = DoubaoImportData(
            botInfo = DoubaoBotInfo(
                name = "示例智能体",
                description = "这是一个导入模板，你可以根据这个格式修改自己的聊天记录进行导入。"
            ),
            chatHistory = listOf(
                DoubaoHistoryItem("user", (now - 60).toString(), DoubaoContent("你好呀")),
                DoubaoHistoryItem("assistant", (now - 50).toString(), DoubaoContent("你好！很高兴见到你。")),
                DoubaoHistoryItem("user", (now - 40).toString(), DoubaoContent("你今天心情怎么样？")),
                DoubaoHistoryItem("assistant", (now - 30).toString(), DoubaoContent("我今天心情很好，因为可以和你聊天。"))
            )
        )
        return JsonInstant.encodeToString(templateData)
    }

    /**
     * 执行导入过程
     * @return 成功导入的会话（Conversation）数量，如果失败则返回 -1
     */
    @OptIn(ExperimentalTime::class)
    suspend fun performImport(
        data: DoubaoImportData,
        assistantId: Uuid,
        roundsPerSession: Int,
        onPreviewRequest: suspend (Conversation) -> Boolean
    ): Int = withContext(Dispatchers.IO) {
        try {
            log("开始导入流程，目标智能体: ${data.botInfo.name}")

            // 复用提取逻辑
            val validHistory = data.chatHistory.mapNotNull { item ->
                val textContent = item.content?.text?.trim()
                val ttsContent = item.content?.ttsContent?.trim()

                val finalContent = when {
                    !textContent.isNullOrBlank() && textContent != "[卡片]" -> textContent
                    !ttsContent.isNullOrBlank() -> ttsContent
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
                return@withContext -1
            }

            val messagesPerSession = (roundsPerSession * 2).coerceIn(2, 100)
            val rawChunks = validHistory.chunked(messagesPerSession)

            // 优化逻辑：如果最后一个分片消息少于20条，且存在上一个分片，则合并
            val chunks = if (rawChunks.size > 1 && rawChunks.last().size < 20) {
                val lastChunk = rawChunks.last()
                val secondLastChunk = rawChunks[rawChunks.size - 2]
                rawChunks.dropLast(2) + listOf(secondLastChunk + lastChunk)
            } else {
                rawChunks
            }

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
                        return@withContext -1
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
            chunks.size
        } catch (e: Exception) {
            log("导入发生异常: ${e.localizedMessage}")
            e.printStackTrace()
            -1
        }
    }
}
