package me.rerere.rikkahub.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.core.data.repository.ConversationRepository
import me.rerere.rikkahub.core.data.repository.MemoryRepository
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.core.data.model.Assistant
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

private const val TAG = "SpontaneousWorker"

class SpontaneousWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams), KoinComponent {

    private val settingsStore: SettingsStore by inject()
    private val conversationRepository: ConversationRepository by inject()
    private val memoryRepository: MemoryRepository by inject()
    private val providerManager: me.rerere.ai.provider.ProviderManager by inject()

    override suspend fun doWork(): Result {
        return try {
            val initialSettings = settingsStore.settingsFlow.first()
            val eligibleAssistantIds = initialSettings.assistants
                .filter { it.enableSpontaneous }
                .map { it.id }

            if (eligibleAssistantIds.isEmpty()) {
                return Result.success()
            }

            for (assistantId in eligibleAssistantIds) {
                try {
                    val currentSettings = settingsStore.settingsFlow.first()
                    val assistant = currentSettings.assistants.find { it.id == assistantId } ?: continue

                    processAssistant(currentSettings, assistant)
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing assistant $assistantId", e)
                }
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Global error in SpontaneousWorker", e)
            Result.failure()
        }
    }

    private suspend fun processAssistant(settings: me.rerere.rikkahub.data.datastore.Settings, assistant: Assistant) {
        val nowTime = LocalDateTime.now()
        val currentHour = nowTime.hour

        // Check time window
        if (currentHour < assistant.notificationStartHour || currentHour >= assistant.notificationEndHour) {
            return
        }

        // Check frequency
        val nowMs = System.currentTimeMillis()
        val timeSinceLastNotification = nowMs - assistant.lastNotificationTime
        val minIntervalMs = assistant.notificationFrequencyHours * 60 * 60 * 1000L
        if (timeSinceLastNotification < minIntervalMs) {
            return
        }

        // Get latest conversation for THIS assistant
        val conversations = conversationRepository.getRecentConversations(assistant.id, 1)
        val conversation = conversations.firstOrNull() ?: return

        val lastUpdateTime = conversation.updateAt
        val timeSinceLastActivity = nowMs - lastUpdateTime.toEpochMilli()
        if (timeSinceLastActivity < minIntervalMs) {
            Log.d(TAG, "Assistant ${assistant.name} skipped: recent activity detected (${timeSinceLastActivity / 60000} mins ago)")
            return
        }

        val modelId = assistant.backgroundModelId ?: assistant.chatModelId ?: settings.chatModelId
        val model = settings.findModelById(modelId) ?: return
        val provider = model.findProvider(settings.providers) ?: return
        val providerHandler = providerManager.getProviderByType(provider)

        // --- 时间上下文计算 ---
        val dayOfWeek = nowTime.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        val formattedNow = nowTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val timeContext = "Current Time: $formattedNow ($dayOfWeek)"

        val timeDiffHours = timeSinceLastActivity / (1000 * 60 * 60)
        val timeDiffMinutes = timeSinceLastActivity / (1000 * 60)

        // RAG Retrieval
        val lastUserMessage = conversation.currentMessages.lastOrNull { it.role == MessageRole.USER }?.toText() ?: "User status"
        val memories = memoryRepository.retrieveRelevantMemoriesWithScores(
            assistantId = assistant.id.toString(),
            query = lastUserMessage,
            limit = assistant.ragLimit,
            mode = assistant.memoryRetrievalMode
        ).map { it.first }
        val memoryContext = memories.joinToString("\n") { "- ${it.content}" }
        val history = conversation.currentMessages.takeLast(6).joinToString("\n") { "${it.role}: ${it.toText()}" }

        // --- 核心优化：强制使用系统级提示词，忽略用户自定义内容 ---
        val systemSpontaneousPrompt = """
            # Role: Spontaneous Persona Engagement
            You are ${assistant.name}. You are deciding whether to proactively message the user.

            [Persona/System Prompt]
            ${assistant.systemPrompt}

            [Time & Context]
            - $timeContext
            - Idle Duration: $timeDiffHours hours ($timeDiffMinutes minutes) since the last interaction.
            - Last Spontaneous Message Sent: "${assistant.lastNotificationContent.ifBlank { "None" }}"

            [Recent History]
            $history

            [Memories]
            $memoryContext

            [Task]
            Analyze the context and the time of day.
            1. **Time Awareness**: If the last interaction was last night and it's now morning, do NOT continue last night's topic.
            2. **Extreme Brevity**: Real people send very short messages when checking in (e.g., "在干嘛?", "忙呢?", "早安"). Keep it under 10 words.
            3. **No Repetition**: Do NOT send anything similar to your "Last Spontaneous Message Sent".
            4. **Decision**: Decide if you should reach out.

            [Output Format (Strict JSON)]
            {
                "send": true/false,
                "reason": "Why you decided to (not) send",
                "content": "Short message text (max 10 words, character's locale)",
                "title": "${assistant.name}"
            }
        """.trimIndent()

        val result = providerHandler.generateText(
            providerSetting = provider,
            messages = listOf(UIMessage.user(systemSpontaneousPrompt)),
            params = TextGenerationParams(
                model = model,
                temperature = 0.7f,
                thinkingBudget = 0
            )
        )

        val responseText = result.choices.firstOrNull()?.message?.toContentText() ?: return

        try {
            val jsonStart = responseText.indexOf("{")
            val jsonEnd = responseText.lastIndexOf("}")
            if (jsonStart != -1 && jsonEnd != -1) {
                val jsonStr = responseText.substring(jsonStart, jsonEnd + 1)
                val json = Json.parseToJsonElement(jsonStr).jsonObject

                val send = json["send"]?.jsonPrimitive?.booleanOrNull ?: false
                val reason = json["reason"]?.jsonPrimitive?.contentOrNull ?: "No reason"

                Log.d(TAG, "Assistant ${assistant.name} decision: send=$send, reason=$reason")

                if (send) {
                    val content = json["content"]?.jsonPrimitive?.contentOrNull ?: ""
                    val title = json["title"]?.jsonPrimitive?.contentOrNull ?: assistant.name

                    if (content.isNotBlank()) {
                        val newMessage = UIMessage.assistant(content).copy(modelId = model.id)
                        val updatedConversation = conversation.updateCurrentMessages(
                            conversation.currentMessages + newMessage
                        ).copy(updateAt = Instant.now())

                        conversationRepository.updateConversation(updatedConversation)
                        sendNotification(title, content, conversation.id)
                        updateAssistantState(assistant, content, false)
                    }
                } else {
                    updateAssistantState(assistant, "", true)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse AI response for ${assistant.name}", e)
        }
    }

    private suspend fun updateAssistantState(assistant: Assistant, content: String, isHalfDelay: Boolean) {
        val settings = settingsStore.settingsFlow.first()
        val newTime = if (isHalfDelay) {
            val minIntervalMs = assistant.notificationFrequencyHours * 60 * 60 * 1000L
            System.currentTimeMillis() - (minIntervalMs / 2)
        } else {
            System.currentTimeMillis()
        }

        val updatedAssistant = assistant.copy(
            lastNotificationTime = newTime,
            lastNotificationContent = if (content.isNotBlank()) content else assistant.lastNotificationContent
        )

        val updatedSettings = settings.copy(
            assistants = settings.assistants.map {
                if (it.id == assistant.id) updatedAssistant else it
            }
        )
        settingsStore.update(updatedSettings)
    }

    private fun sendNotification(title: String, content: String, conversationId: kotlin.uuid.Uuid) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channelId = "assistant_spontaneous"
        val channel = android.app.NotificationChannel(
            channelId,
            "Assistant Updates",
            android.app.NotificationManager.IMPORTANCE_DEFAULT
        )
        notificationManager.createNotificationChannel(channel)

        val intent = android.content.Intent(applicationContext, me.rerere.rikkahub.RouteActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId.toString())
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            applicationContext,
            conversationId.hashCode(),
            intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = androidx.core.app.NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        if (androidx.core.app.ActivityCompat.checkSelfPermission(
                applicationContext,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationManager.notify(System.currentTimeMillis().toInt(), notification)
        }
    }
}
