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
                    // Fetch fresh settings for each assistant to ensure lastNotificationTime is up-to-date
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
        // Check time window
        val currentHour = java.time.LocalTime.now().hour
        if (currentHour < assistant.notificationStartHour || currentHour >= assistant.notificationEndHour) {
            return
        }

        // Check frequency
        val now = System.currentTimeMillis()
        val timeSinceLastNotification = now - assistant.lastNotificationTime
        val minIntervalMs = assistant.notificationFrequencyHours * 60 * 60 * 1000L
        if (timeSinceLastNotification < minIntervalMs) {
            return
        }

        // Get latest conversation for THIS assistant
        val conversations = conversationRepository.getRecentConversations(assistant.id, 1)
        val conversation = conversations.firstOrNull() ?: return

        // 优化点：检查对话最后活跃时间
        // 如果用户刚刚才聊过天（或者AI刚回复过），不应该立刻触发主动消息
        val lastUpdateTime = conversation.updateAt
        val timeSinceLastActivity = now - lastUpdateTime.toEpochMilli()
        if (timeSinceLastActivity < minIntervalMs) {
            Log.d(TAG, "Assistant ${assistant.name} skipped: recent activity detected (${timeSinceLastActivity / 60000} mins ago)")
            return
        }

        val modelId = assistant.backgroundModelId ?: assistant.chatModelId ?: settings.chatModelId
        val model = settings.findModelById(modelId) ?: return
        val provider = model.findProvider(settings.providers) ?: return
        val providerHandler = providerManager.getProviderByType(provider)

        // Calculate context info
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

        val customPrompt = assistant.spontaneousPrompt.ifBlank {
            """
            You are ${assistant.name}. You are checking in on the user because they haven't messaged you for a while.

            [Persona/System Prompt]
            ${assistant.systemPrompt}

            [Master Memory]
            ${assistant.masterMemoryContent}

            [Context]
            - It has been $timeDiffHours hours ($timeDiffMinutes minutes) since the last message in this conversation.
            - Recent chat history (last 4 messages):
            {{history}}

            [Task]
            Based on your persona, master memory, and the context, do you want to send a spontaneous message to the user?
            - If YES: Formulate a natural, concise message as if you're reaching out in the chat. Keep it simple.
            - If NO: Explain why.

            [Output Format (Strict JSON)]
            {
                "send": true/false,
                "reason": "Why you decided to (not) send",
                "content": "The message text to send to the chat",
                "title": "Notification title (usually your name)"
            }
            """.trimIndent()
        }

        val prompt = customPrompt
            .replace("{{history}}", history)
            .replace("{{memories}}", memoryContext)

        val result = providerHandler.generateText(
            providerSetting = provider,
            messages = listOf(UIMessage.user(prompt)),
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
                        // 1. Add message to database
                        val newMessage = UIMessage.assistant(content).copy(modelId = model.id)
                        val updatedConversation = conversation.updateCurrentMessages(
                            conversation.currentMessages + newMessage
                        ).copy(updateAt = Instant.now())

                        conversationRepository.updateConversation(updatedConversation)

                        // 2. Send notification
                        sendNotification(title, content, conversation.id)

                        // 3. Update assistant state
                        updateAssistantState(assistant, content, false)
                    }
                } else {
                    // AI declined. Apply Half-Delay.
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
