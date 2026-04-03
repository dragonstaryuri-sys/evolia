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
import me.rerere.rikkahub.BACKUP_NOTIFICATION_CHANNEL_ID
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
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import me.rerere.rikkahub.data.ai.prompts.DIARY_NO_INTERACTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DIARY_TIME_REFERENCE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_DIARY_PROMPT


private const val TAG = "DiaryWorker"

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

            // 如果是自动触发，且未开启自动日记开关，直接返回
            if (!isManual && !assistant.enableAutoDiary) {
                return Result.success()
            }

            val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            // 先检查今天有没有日记
            val todayDiary = diaryRepo.getDiaryByDate(assistant.id.toString(), todayStr)
            if (todayDiary != null) {
                if (isManual) {
                    showSimpleNotification(applicationContext.getString(R.string.diary_no_new_messages))
                }
                return Result.success()
            }
            val lastDiary = diaryRepo.getLastDiaryOfAssistant(assistant.id.toString())

            // 确定未处理消息的时间起点：如果有旧日记，从旧日记创建时间开始；否则从今天凌晨开始
            val startTimeThreshold = lastDiary?.createdAt ?: LocalDate.now()
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

            // 1. 获取今日未处理的消息
            val conversations = conversationRepo.getConversationsOfAssistant(assistant.id).first()
            val newMessages = conversations.flatMap { conv ->
                conv.messageNodes.flatMap { node ->
                    node.messages.filter {
                        it.createdAt.toInstant(kotlinx.datetime.TimeZone.currentSystemDefault()).toEpochMilliseconds() > startTimeThreshold
                    }
                }
            }.sortedBy { it.createdAt.toInstant(kotlinx.datetime.TimeZone.currentSystemDefault()).toEpochMilliseconds() }

            val triggerTime = LocalDateTime.now().format(timeFormatter)
            val locale = Locale.getDefault().toLanguageTag()

            // 2. 逻辑分支判断
            val finalPrompt: String = if (newMessages.isEmpty()) {
                // 分支 A: 没有任何新消息
                if (isManual) {
                    showSimpleNotification(applicationContext.getString(R.string.diary_no_new_messages))
                    return Result.success()
                } else {
                    // 自动触发且无历史日记时，才从记忆里抠点东西写
                    if (lastDiary != null) return Result.success() // 已经有历史了，没新话就不强求写了

                    val memories = memoryRepo.getCombinedMemoriesFlow(assistant.id.toString()).first()
                    val selectedMemories = if (memories.isNotEmpty()) {
                        memories.shuffled().take(3).joinToString("\n") { "- ${it.content}" }
                    } else {
                        "No significant memories found yet."
                    }

                    DIARY_NO_INTERACTION_PROMPT.applyPlaceholders(
                        "char" to assistant.name,
                        "user" to (currentSettings.displaySetting.userNickname.ifBlank { "User" }),
                        "memories" to selectedMemories,
                        "system_prompt" to assistant.systemPrompt,
                        "locale" to locale
                    )
                }
            } else {
                // 分支 B: 有新消息。直接生成一篇新日记。
                // 不再判断 lastDiary != null 来决定是否用 ContinuationPrompt，统一用 standard prompt
                val chatContent = newMessages.joinToString("\n") { "${it.role}: ${it.toText()}" }

                val firstMsgTime = formatTimestamp(newMessages.first().createdAt.toInstant(kotlinx.datetime.TimeZone.currentSystemDefault()).toEpochMilliseconds())
                val lastMsgTime = formatTimestamp(newMessages.last().createdAt.toInstant(kotlinx.datetime.TimeZone.currentSystemDefault()).toEpochMilliseconds())

                val timeRef = "\n\n" + DIARY_TIME_REFERENCE_PROMPT.applyPlaceholders(
                    "start_time" to firstMsgTime,
                    "end_time" to lastMsgTime,
                    "trigger_time" to triggerTime
                )

                // 使用系统默认提示词
                DEFAULT_DIARY_PROMPT.applyPlaceholders(
                    "content" to chatContent,
                    "char" to assistant.name,
                    "user" to (currentSettings.displaySetting.userNickname.ifBlank { "User" }),
                    "system_prompt" to assistant.systemPrompt,
                    "locale" to locale
                ) + timeRef
            }

            // 3. AI 生成过程
            val diaryModelId = assistant.diaryModelId ?: currentSettings.diaryModelId
            val model = currentSettings.findModelById(diaryModelId)
                ?: currentSettings.findModelById(currentSettings.chatModelId)
                ?: error("No model available")

            var generatedContent = ""
            generationHandler.generateText(
                settings = currentSettings,
                model = model,
                messages = listOf(UIMessage.user(finalPrompt)),
                assistant = assistant
            ).collect { chunk ->
                if (chunk is me.rerere.rikkahub.data.ai.GenerationChunk.Messages) {
                    generatedContent = chunk.messages.lastOrNull()?.toText() ?: ""
                }
            }

            // 4. 保存或合并结果
            if (generatedContent.isNotBlank()) {
                val diary = AgentDiaryEntity(
                    id = Uuid.random().toString(), // 开启新篇章
                    assistantId = assistant.id.toString(),
                    content = generatedContent, // 不再拼接
                    date = todayStr,
                    createdAt = System.currentTimeMillis()
                )
                diaryRepo.insertDiary(diary)
                showSuccessNotification(assistant.name)
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Diary generation failed", e)
            Result.retry()
        }
    }

    private fun formatTimestamp(ts: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault()).format(timeFormatter)

    private fun showSimpleNotification(text: String) {
        val notificationManager = NotificationManagerCompat.from(applicationContext)
        if (ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // 如果没有权限，可以选择直接返回或者记录日志
            Log.w(TAG, "Missing POST_NOTIFICATIONS permission")
            return
        }
        val notification = NotificationCompat.Builder(applicationContext, BACKUP_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.discover_page_diary_title))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        try {
            notificationManager.notify(System.currentTimeMillis().toInt(), notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException when showing notification", e)
        }
    }

    private fun showSuccessNotification(name: String) {
        showSimpleNotification(applicationContext.getString(R.string.diary_assistant_title_format, name) + ": " + applicationContext.getString(R.string.discover_page_diary_generate_success))
    }
}
