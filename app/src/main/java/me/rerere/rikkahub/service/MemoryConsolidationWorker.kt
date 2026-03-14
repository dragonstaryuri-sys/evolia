package me.rerere.rikkahub.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.core.data.db.dao.ChatEpisodeDAO
import me.rerere.rikkahub.core.data.db.entity.ChatEpisodeEntity
import me.rerere.rikkahub.core.data.model.Assistant
import me.rerere.rikkahub.core.data.model.Conversation
import me.rerere.rikkahub.core.data.repository.ConversationRepository
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private const val TAG = "MemoryConsolidation"

class MemoryConsolidationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {
    private val conversationRepository: ConversationRepository by inject()
    private val settingsStore: SettingsStore by inject()
    private val providerManager: ProviderManager by inject()
    private val chatEpisodeDAO: ChatEpisodeDAO by inject()

    override suspend fun doWork(): Result {
        val isFullScan = inputData.getBoolean("FULL_SCAN", false)
        Log.i(TAG, "Starting memory consolidation (Full Scan: $isFullScan)")

        try {
            val settings = settingsStore.settingsFlow.first()
            val assistants = settings.assistants.filter { it.enableMemoryConsolidation }

            if (assistants.isEmpty()) {
                Log.i(TAG, "No assistants have memory consolidation enabled.")
                return Result.success()
            }

            for (assistant in assistants) {
                consolidateAssistantMemories(assistant, isFullScan)
            }

            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Consolidation failed", e)
            return Result.failure()
        }
    }

    private suspend fun consolidateAssistantMemories(assistant: Assistant, isFullScan: Boolean) {
        // 使用最新快照
        val currentSettings = settingsStore.settingsFlow.first()
        val currentAssistant = currentSettings.assistants.find { it.id == assistant.id } ?: assistant

        val conversations = conversationRepository.getConversationsOfAssistant(currentAssistant.id).first()

        val toConsolidate = conversations.filter { conv ->
            if (conv.isConsolidated) return@filter false
            if (conv.currentMessages.size < 4) return@filter false
            if (isFullScan) return@filter true

            val delayMillis = currentAssistant.consolidationDelayMinutes * 60 * 1000L
            val timeSinceUpdate = System.currentTimeMillis() - conv.updateAt.toEpochMilli()
            timeSinceUpdate >= delayMillis
        }

        if (toConsolidate.isEmpty() && !isFullScan) return

        Log.i(TAG, "Consolidating ${toConsolidate.size} items for ${currentAssistant.name}")

        val modelId = currentAssistant.summarizerModelId ?: currentSettings.chatModelId
        val model = currentSettings.findModelById(modelId) ?: return
        val providerSetting = model.findProvider(currentSettings.providers) ?: return
        val handler = providerManager.getProviderByType(providerSetting)

        var successCount = 0
        for (conv in toConsolidate) {
            try {
                val summary = generateConversationSummary(handler, providerSetting, model, conv)
                if (summary.isNotBlank()) {
                    val episode = ChatEpisodeEntity(
                        assistantId = currentAssistant.id.toString(),
                        conversationId = conv.id.toString(),
                        content = summary,
                        startTime = conv.createAt.toEpochMilli(),
                        endTime = conv.updateAt.toEpochMilli(),
                        significance = 5,
                        lastAccessedAt = System.currentTimeMillis()
                    )
                    chatEpisodeDAO.insertEpisode(episode)
                    conversationRepository.markAsConsolidated(conv.id)
                    successCount++
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to consolidate conversation ${conv.id}", e)
            }
        }

        // ═══════════════════════════════════════════════════════════════════
        // 关键更新：确保回写状态到 DataStore
        // ═══════════════════════════════════════════════════════════════════
        val finalSettings = settingsStore.settingsFlow.first()
        val now = System.currentTimeMillis()
        val resultDesc = if (successCount > 0) "Consolidated $successCount episodes" else "Scan complete, no new items"

        val updatedSettings = finalSettings.copy(
            assistants = finalSettings.assistants.map {
                if (it.id == currentAssistant.id) {
                    it.copy(
                        lastConsolidationTime = now,
                        lastConsolidationResult = resultDesc
                    )
                } else it
            }
        )

        settingsStore.update(updatedSettings)
        Log.i(TAG, "Updated lastConsolidationTime to $now for ${currentAssistant.name}")
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun generateConversationSummary(
        handler: me.rerere.ai.provider.Provider<*>,
        providerSetting: me.rerere.ai.provider.ProviderSetting,
        model: me.rerere.ai.provider.Model,
        conversation: Conversation
    ): String {
        val messages = conversation.currentMessages
        val text = messages.joinToString("\n") { "${it.role}: ${it.toText().take(500)}" }

        val prompt = """
            Summarize the key events, information, and user preferences from the following conversation.
            Focus on specific facts that might be useful for future interactions.
            Keep it concise (1-3 paragraphs).

            Conversation:
            $text

            Summary:
        """.trimIndent()

        val h = handler as me.rerere.ai.provider.Provider<me.rerere.ai.provider.ProviderSetting>
        val resp = h.generateText(
            providerSetting,
            listOf(UIMessage.user(prompt)),
            TextGenerationParams(model = model, temperature = 0.3f, topP = 0f)
        )
        return resp.choices.firstOrNull()?.message?.toContentText()?.trim() ?: ""
    }
}
