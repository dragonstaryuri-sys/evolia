package me.rerere.rikkahub.service

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import kotlinx.datetime.toInstant
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.AGENT_TASK_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.core.data.db.entity.AgentDiaryEntity
import me.rerere.rikkahub.core.data.repository.ConversationRepository
import me.rerere.rikkahub.core.data.repository.DiaryRepository
import me.rerere.rikkahub.core.data.repository.MemoryRepository
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.utils.applyPlaceholders
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.uuid.Uuid
import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.work.workDataOf
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.ai.prompts.DIARY_NO_INTERACTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DIARY_TIME_REFERENCE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_DIARY_PROMPT
import me.rerere.ai.core.MessageRole

private const val TAG = "DiaryWorker"
private const val MAX_CHAT_CONTENT_LENGTH = 80_000 // 最大允许的聊天内容字符数

/**
 * Markdown formatting instruction for the AI.
 */
private const val DIARY_MARKDOWN_INSTRUCTION = """

---
**Format Instruction (Markdown):**
Please use Markdown to make the diary beautiful and readable:
- Use `###` for section headers (e.g., Morning, Afternoon, Evening or key events).
- Use **bold** for emphasis on important feelings or events.
- Use bullet points `-` for lists of activities or thoughts.
- Use `>` for self-reflections or quotes.
- Avoid using H1 (#) or H2 (##) to keep it concise within the card.
"""

class DiaryWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams), KoinComponent {

    private val settingsStore: SettingsStore by inject()
    private val diaryRepo: DiaryRepository by inject()
    private val conversationRepo: ConversationRepository by inject()
    private val memoryRepo: MemoryRepository by inject()
    private val generationHandler: GenerationHandler by inject()

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    override suspend fun doWork(): Result {
        val assistantIdStr = inputData.getString("assistantId")
        val isManual = inputData.getBoolean("isManual", false)

        return try {
            val currentSettings = settingsStore.settingsFlow.first { !it.init }
            val assistantId = assistantIdStr?.let { Uuid.parse(it) }
            val assistant = if (assistantId != null) {
                currentSettings.assistants.find { it.id == assistantId }
            } else {
                currentSettings.getCurrentAssistant()
            } ?: return Result.failure()

            if (!isManual && !assistant.enableAutoDiary) {
                return Result.success()
            }

            val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            // 检查今天是否已有日记
            val todayDiary = diaryRepo.getDiaryByDate(assistant.id.toString(), todayStr)
            if (todayDiary != null) {
                // 如果是手动触发且日记已存在，标记为 skipped，由 UI 层显示更精确的提示
                return if (isManual) {
                    Result.success(workDataOf("skipped" to true, "reason" to "already_exists"))
                } else {
                    Result.success()
                }
            }

            // 获取最后一次日记
            val lastDiary = diaryRepo.getLastDiaryOfAssistant(assistant.id.toString())

            // 确定时间起点
            val startTimeThreshold = lastDiary?.createdAt ?: LocalDate.now()
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

            // 1. 获取新消息
            val conversations = conversationRepo.getConversationsOfAssistantAnyMode(assistant.id).first()
            val allMessages = conversations.flatMap { conv ->
                conv.messageNodes.flatMap { node ->
                    node.messages.filter {
                        it.createdAt.toInstant(kotlinx.datetime.TimeZone.currentSystemDefault()).toEpochMilliseconds() > startTimeThreshold
                    }
                }
            }.sortedBy { it.createdAt.toInstant(kotlinx.datetime.TimeZone.currentSystemDefault()).toEpochMilliseconds() }

            // 核心修改：日记生成只拼接消息文本内容，不拼接思考链和工具调用的信息
            val newMessages = allMessages.filter {
                it.role != MessageRole.TOOL && it.toContentText().isNotBlank()
            }

            val triggerTime = LocalDateTime.now().format(timeFormatter)
            val locale = Locale.getDefault().toLanguageTag()

            // 2. 构造 Prompt
            val finalPrompt: String = if (newMessages.isEmpty()) {
                val memories = memoryRepo.getCombinedMemoriesFlow(assistant.id.toString()).first()
                val selectedMemories = if (memories.isNotEmpty()) {
                    memories.shuffled().take(3).joinToString("\n") { "- ${it.content}" }
                } else "No significant memories found yet."

                DIARY_NO_INTERACTION_PROMPT.applyPlaceholders(
                    "char" to assistant.name,
                    "user" to (currentSettings.displaySetting.userNickname.ifBlank { "User" }),
                    "memories" to selectedMemories,
                    "system_prompt" to assistant.systemPrompt,
                    "locale" to locale
                ) + DIARY_MARKDOWN_INSTRUCTION
            } else {
                var chatContent = newMessages.joinToString("\n") { message ->
                    val time = formatTimestamp(message.createdAt.toInstant(kotlinx.datetime.TimeZone.currentSystemDefault()).toEpochMilliseconds())
                    // 仅使用文本正文，过滤推理过程
                    "[$time] ${message.role}: ${message.toContentText()}"
                }

                // 处理超长文本：取头部和尾部
                if (chatContent.length > MAX_CHAT_CONTENT_LENGTH) {
                    val half = MAX_CHAT_CONTENT_LENGTH / 2
                    val head = chatContent.take(half)
                    val tail = chatContent.takeLast(half)
                    chatContent = "$head\n\n...[Content omitted due to length]...\n\n$tail"
                    Log.w(TAG, "Chat content for diary truncated: original length ${chatContent.length}")
                }

                val firstMsgTime = formatTimestamp(newMessages.first().createdAt.toInstant(kotlinx.datetime.TimeZone.currentSystemDefault()).toEpochMilliseconds())
                val lastMsgTime = formatTimestamp(newMessages.last().createdAt.toInstant(kotlinx.datetime.TimeZone.currentSystemDefault()).toEpochMilliseconds())

                val timeRef = "\n\n" + DIARY_TIME_REFERENCE_PROMPT.applyPlaceholders(
                    "today_date" to LocalDate.now().toString() + " (" + LocalDate.now().dayOfWeek.name + ")",
                    "start_time" to firstMsgTime,
                    "end_time" to lastMsgTime,
                    "trigger_time" to triggerTime
                )

                DEFAULT_DIARY_PROMPT.applyPlaceholders(
                    "content" to chatContent,
                    "char" to assistant.name,
                    "user" to (currentSettings.displaySetting.userNickname.ifBlank { "User" }),
                    "system_prompt" to assistant.systemPrompt,
                    "locale" to locale
                ) + timeRef + DIARY_MARKDOWN_INSTRUCTION
            }

            // 3. AI 生成
            val diaryModelId = assistant.diaryModelId ?: currentSettings.diaryModelId
            val model = currentSettings.findModelById(diaryModelId)
                ?: currentSettings.findModelById(currentSettings.chatModelId)
                ?: error("No model available")

            var generatedContent = ""
            generationHandler.generateText(
                settings = currentSettings,
                model = model,
                messages = listOf(UIMessage.user(finalPrompt)),
                assistant = assistant.copy(
                    temperature = 0.9f,
                    topP = 0.6f,
                )
            ).collect { chunk ->
                if (chunk is me.rerere.rikkahub.data.ai.GenerationChunk.Messages) {
                    val lastMessage = chunk.messages.lastOrNull()
                    if (lastMessage?.role == MessageRole.ASSISTANT) {
                        generatedContent = lastMessage.toContentText()
                    }
                }
            }

            // 4. 保存为“今天”的日记
            if (generatedContent.isNotBlank()) {
                val diary = AgentDiaryEntity(
                    id = Uuid.random().toString(),
                    assistantId = assistant.id.toString(),
                    content = generatedContent,
                    date = todayStr,
                    createdAt = System.currentTimeMillis()
                )
                diaryRepo.insertDiary(diary)

                // 只有自动生成时才发送系统通知。如果是手动生成，UI 层已经有对应的 Toast 提示了。
                if (!isManual) {
                    showSuccessNotification(assistant.name, assistant.id.toString())
                }
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Diary generation failed", e)
            if (isManual) {
                val errorInfo = e.localizedMessage ?: e.toString()
                val errorMsg = applicationContext.getString(R.string.diary_generate_failed, errorInfo)
                showSimpleNotification(errorMsg, assistantIdStr)
            }
            Result.retry()
        }
    }

    private fun formatTimestamp(ts: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault()).format(timeFormatter)

    private fun showSimpleNotification(text: String, assistantId: String? = null) {
        val notificationManager = NotificationManagerCompat.from(applicationContext)
        if (ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return

        val intent = Intent(applicationContext, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (assistantId != null) {
                putExtra("target_screen", "diary")
                putExtra("assistantId", assistantId)
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(applicationContext, AGENT_TASK_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.discover_page_diary_title))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        try {
            notificationManager.notify(System.currentTimeMillis().toInt(), notification)
        } catch (e: Exception) {}
    }

    private fun showSuccessNotification(name: String, assistantId: String) {
        showSimpleNotification(
            applicationContext.getString(R.string.diary_assistant_title_format, name) + ": " + applicationContext.getString(R.string.discover_page_diary_generate_success),
            assistantId
        )
    }
}
