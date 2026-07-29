package me.rerere.rikkahub.service

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.AGENT_TASK_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.core.data.ai.prompts.DEFAULT_DIARY_PROMPT
import me.rerere.rikkahub.core.data.ai.prompts.DIARY_NO_INTERACTION_PROMPT
import me.rerere.rikkahub.core.data.ai.prompts.DIARY_TIME_REFERENCE_PROMPT
import me.rerere.rikkahub.core.data.ai.prompts.applyPlaceholders
import me.rerere.rikkahub.core.data.db.entity.AgentDiaryEntity
import me.rerere.rikkahub.core.data.repository.ConversationRepository
import me.rerere.rikkahub.core.data.repository.DiaryRepository
import me.rerere.rikkahub.core.data.repository.MemoryRepository
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "DiaryWorker"
private const val CHUNK_SIZE = 40_000

private const val DIARY_MARKDOWN_INSTRUCTION = """

---
**日记格式(Markdown):**
你可以使用markdown格式编写你的日记~
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
            val todayDiary = diaryRepo.getDiaryByDate(assistant.id.toString(), todayStr)
            if (todayDiary != null) {
                return if (isManual) {
                    Result.success(workDataOf("skipped" to true, "reason" to "already_exists"))
                } else {
                    Result.success()
                }
            }

            val lastDiary = diaryRepo.getLastDiaryOfAssistant(assistant.id.toString())
            val startTimeThreshold = lastDiary?.createdAt ?: LocalDate.now()
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

            val conversations = conversationRepo.getConversationsOfAssistantAnyMode(assistant.id).first()
            val allMessages = conversations.flatMap { conv ->
                conv.messageNodes.mapNotNull { node ->
                    node.messages.getOrNull(node.selectIndex)?.takeIf {
                        it.createdAt.toInstant(kotlinx.datetime.TimeZone.currentSystemDefault()).toEpochMilliseconds() > startTimeThreshold
                    }
                }
            }.sortedBy { it.createdAt.toInstant(kotlinx.datetime.TimeZone.currentSystemDefault()).toEpochMilliseconds() }

            val newMessages = allMessages.filter {
                it.role != MessageRole.TOOL && it.toContentText().isNotBlank()
            }

            val triggerTime = LocalDateTime.now().format(timeFormatter)
            val locale = Locale.getDefault().toLanguageTag()

            val diaryModelId = assistant.diaryModelId ?: currentSettings.diaryModelId
            val model = currentSettings.findModelById(diaryModelId)
                ?: currentSettings.findModelById(currentSettings.chatModelId)
                ?: error("没有可用模型")

            var generatedContent = ""

            if (newMessages.isEmpty()) {
                val memories = memoryRepo.getCombinedMemoriesFlow(assistant.id.toString()).first()
                val selectedMemories = if (memories.isNotEmpty()) {
                    memories.shuffled().take(3).joinToString("\n") { "- ${it.content}" }
                } else "No significant memories found yet."

                val finalPrompt = DIARY_NO_INTERACTION_PROMPT.applyPlaceholders(
                    "char" to assistant.name,
                    "user" to (currentSettings.displaySetting.userNickname.ifBlank { "User" }),
                    "memories" to selectedMemories,
                    "system_prompt" to assistant.systemPrompt,
                    "locale" to locale
                ) + DIARY_MARKDOWN_INSTRUCTION

                generatedContent = performGeneration(currentSettings, model, assistant, finalPrompt, isManual)
            } else {
                val messageGroups = mutableListOf<List<UIMessage>>()
                var currentGroup = mutableListOf<UIMessage>()
                var currentLen = 0
                for (msg in newMessages) {
                    val text = msg.toContentText()
                    if (currentLen + text.length > CHUNK_SIZE && currentGroup.isNotEmpty()) {
                        messageGroups.add(currentGroup)
                        currentGroup = mutableListOf()
                        currentLen = 0
                    }
                    currentGroup.add(msg)
                    currentLen += text.length
                }
                if (currentGroup.isNotEmpty()) messageGroups.add(currentGroup)

                messageGroups.forEachIndexed { index, group ->
                    val isFirst = index == 0
                    val chatContent = group.joinToString("\n") { message ->
                        val time = formatTimestamp(message.createdAt.toInstant(kotlinx.datetime.TimeZone.currentSystemDefault()).toEpochMilliseconds())
                        "[$time] ${message.role}: ${message.toContentText()}"
                    }

                    val firstMsgTime = formatTimestamp(group.first().createdAt.toInstant(kotlinx.datetime.TimeZone.currentSystemDefault()).toEpochMilliseconds())
                    val lastMsgTime = formatTimestamp(group.last().createdAt.toInstant(kotlinx.datetime.TimeZone.currentSystemDefault()).toEpochMilliseconds())

                    val timeRef = "\n\n" + DIARY_TIME_REFERENCE_PROMPT.applyPlaceholders(
                        "today_date" to LocalDate.now().toString() + " (" + LocalDate.now().dayOfWeek.name + ")",
                        "start_time" to firstMsgTime,
                        "end_time" to lastMsgTime,
                        "trigger_time" to triggerTime
                    )

                    val promptBase = if (isFirst) {
                        DEFAULT_DIARY_PROMPT
                    } else {
                        """
                        你正在编写一篇日记。
                        这是你根据之前的对话记录已经生成的日记草稿：
                        ---
                        $generatedContent
                        ---

                        现在，请结合接下来的这一段新对话记录，对上面的日记草稿进行完善、补充或继续编写。
                        请保持人称一致，风格连贯，并在最后输出一份包含之前内容和新内容的完整日记。

                        新的对话记录如下：
                        {{content}}
                        """.trimIndent()
                    }

                    val finalPrompt = promptBase.applyPlaceholders(
                        "content" to chatContent,
                        "char" to assistant.name,
                        "user" to (currentSettings.displaySetting.userNickname.ifBlank { "User" }),
                        "system_prompt" to assistant.systemPrompt,
                        "locale" to locale
                    ) + timeRef + (if (isFirst) DIARY_MARKDOWN_INSTRUCTION else "")

                    generatedContent = performGeneration(currentSettings, model, assistant, finalPrompt, isManual)
                }
            }

            if (generatedContent.isNotBlank()) {
                val diary = AgentDiaryEntity(
                    id = Uuid.random().toString(),
                    assistantId = assistant.id.toString(),
                    content = generatedContent,
                    date = todayStr,
                    createdAt = System.currentTimeMillis()
                )
                diaryRepo.insertDiary(diary)

                if (!isManual) {
                    showSuccessNotification(assistant.name, assistant.id.toString())
                }
                Result.success()
            } else {
                // 如果没有生成任何内容，也标记为跳过
                Result.success(workDataOf("skipped" to true, "reason" to "no_content"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Diary generation failed", e)
            if (isManual) {
                val errorInfo = e.localizedMessage ?: e.toString()
                val errorMsg = applicationContext.getString(R.string.diary_generate_failed, errorInfo)
                showSimpleNotification(errorMsg, assistantIdStr)
                return Result.failure(workDataOf("error" to errorInfo))
            }
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private suspend fun performGeneration(
        settings: me.rerere.rikkahub.data.datastore.Settings,
        model: me.rerere.ai.provider.Model,
        assistant: me.rerere.rikkahub.core.data.model.Assistant,
        prompt: String,
        isManual: Boolean
    ): String {
        var result = ""
        val timeoutMillis = if (isManual) 180_000L else 300_000L
        withTimeout(timeoutMillis) {
            generationHandler.generateText(
                settings = settings,
                model = model,
                messages = listOf(me.rerere.ai.ui.UIMessage.user(prompt)),
                assistant = assistant.copy(
                    temperature = 0.8f,
                    enableMemory = false,
                    enabledLorebookIds = emptySet(),
                    includeDiariesInContext = false,
                    localTools = emptyList()
                ),
                enabledModeIds = emptySet()
            ).collect { chunk ->
                if (chunk is me.rerere.rikkahub.data.ai.GenerationChunk.Messages) {
                    val lastMessage = chunk.messages.lastOrNull()
                    if (lastMessage?.role == MessageRole.ASSISTANT) {
                        result = lastMessage.toContentText()
                    }
                }
            }
        }
        return result
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
            .setSmallIcon(R.drawable.about_logo)
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
