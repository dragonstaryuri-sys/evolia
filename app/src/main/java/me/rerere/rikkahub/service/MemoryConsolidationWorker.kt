package me.rerere.rikkahub.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
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
import java.time.LocalDate
import java.util.Locale

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
        val assistantId = inputData.getString("ASSISTANT_ID")
        Log.i(TAG, "Starting memory consolidation (Full Scan: $isFullScan, Target: $assistantId)")

        try {
            val settings = settingsStore.settingsFlow.first()
            val assistants = if (assistantId != null) {
                settings.assistants.filter { it.id.toString() == assistantId }
            } else {
                settings.assistants.filter { it.enableMemoryConsolidation || it.enableMasterMemory }
            }

            if (assistants.isEmpty()) {
                Log.i(TAG, "No assistants to process.")
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
        val currentSettings = settingsStore.settingsFlow.first()
        val currentAssistant = currentSettings.assistants.find { it.id == assistant.id } ?: assistant

        val conversations = conversationRepository.getConversationsOfAssistant(currentAssistant.id).first()

        // 1. Process Episodic Memories (Consolidation Track A)
        val toConsolidateEpisodes = conversations.filter { conv ->
            if (!currentAssistant.enableMemoryConsolidation) return@filter false
            if (conv.isConsolidated) return@filter false
            if (conv.currentMessages.size < 4) return@filter false
            if (isFullScan) return@filter true

            val delayMillis = currentAssistant.consolidationDelayMinutes * 60 * 1000L
            val timeSinceUpdate = System.currentTimeMillis() - conv.updateAt.toEpochMilli()
            timeSinceUpdate >= delayMillis
        }

        val modelId = currentAssistant.summarizerModelId ?: currentSettings.chatModelId
        val model = currentSettings.findModelById(modelId) ?: return
        val providerSetting = model.findProvider(currentSettings.providers) ?: return
        val handler = providerManager.getProviderByType(providerSetting)

        var episodicSuccessCount = 0
        for (conv in toConsolidateEpisodes) {
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
                    episodicSuccessCount++
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to consolidate conversation ${conv.id} to episode", e)
            }
        }

        // 2. Process Master Memory (Archive Track B)
        var updatedMasterContent: String? = null
        var masterMemorySkipReason: String? = null
        if (currentAssistant.enableMasterMemory) {
            val newConversations = conversations.filter {
                it.updateAt.toEpochMilli() > currentAssistant.lastMasterMemoryUpdate && it.currentMessages.size >= 2
            }.sortedBy { it.updateAt }

            if (newConversations.isNotEmpty() || isFullScan) {
                Log.i(TAG, "Updating Master Memory for ${currentAssistant.name}")
                try {
                    val contextParts = mutableListOf<String>()
                    for (conv in newConversations) {
                        val summary = chatEpisodeDAO.getEpisodeByConversationId(conv.id.toString())?.content
                        if (!summary.isNullOrBlank()) {
                            contextParts.add("Conversation Summary: $summary")
                        } else {
                            val messagesText = conv.currentMessages.takeLast(10).joinToString("\n") {
                                "${it.role}: ${it.toText().take(300)}"
                            }
                            contextParts.add("Recent Messages:\n$messagesText")
                        }
                    }

                    val recentContext = contextParts.joinToString("\n\n---\n\n")

                    if (recentContext.isNotBlank() || isFullScan) {
                        updatedMasterContent = updateMasterMemory(
                            handler, providerSetting, model,
                            currentAssistant.masterMemoryContent,
                            recentContext,
                            currentAssistant.masterMemoryPrompt.ifBlank { DEFAULT_MASTER_MEMORY_PROMPT }
                        )
                    } else {
                        masterMemorySkipReason = applicationContext.getString(R.string.assistant_memory_master_no_new_content)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update Master Memory", e)
                }
            } else {
                masterMemorySkipReason = applicationContext.getString(R.string.assistant_memory_master_no_new_content)
            }
        }

        // 3. Finalize and Update Settings
        val finalSettings = settingsStore.settingsFlow.first()
        val now = System.currentTimeMillis()

        val resultDesc = buildString {
            if (episodicSuccessCount > 0) {
                append("Consolidated $episodicSuccessCount episodes. ")
            } else if (currentAssistant.enableMemoryConsolidation && toConsolidateEpisodes.isEmpty()) {
                append(applicationContext.getString(R.string.assistant_memory_consolidation_insufficient_data))
                append(" ")
            }

            if (updatedMasterContent != null) {
                append("Master Memory updated. ")
            } else if (currentAssistant.enableMasterMemory && masterMemorySkipReason != null) {
                append(masterMemorySkipReason)
                append(" ")
            }

            if (isEmpty()) append("Scan complete, no new items.")
        }.trim()

        val updatedSettings = finalSettings.copy(
            assistants = finalSettings.assistants.map {
                if (it.id == currentAssistant.id) {
                    it.copy(
                        lastConsolidationTime = now,
                        lastConsolidationResult = resultDesc,
                        masterMemoryContent = updatedMasterContent ?: it.masterMemoryContent,
                        lastMasterMemoryUpdate = if (updatedMasterContent != null) now else it.lastMasterMemoryUpdate
                    )
                } else it
            }
        )

        settingsStore.update(updatedSettings)
        Log.i(TAG, "Updated consolidation stats for ${currentAssistant.name}: $resultDesc")
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

        val locale = Locale.getDefault().displayName
        val prompt = DEFAULT_EPISODIC_CONSOLIDATION_PROMPT
            .replace("{{text}}", text)
            .replace("{{locale}}", locale)

        val h = handler as me.rerere.ai.provider.Provider<me.rerere.ai.provider.ProviderSetting>
        val resp = h.generateText(
            providerSetting,
            listOf(UIMessage.user(prompt)),
            TextGenerationParams(model = model, temperature = 0.3f, topP = 0f)
        )
        return resp.choices.firstOrNull()?.message?.toContentText()?.trim() ?: ""
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun updateMasterMemory(
        handler: me.rerere.ai.provider.Provider<*>,
        providerSetting: me.rerere.ai.provider.ProviderSetting,
        model: me.rerere.ai.provider.Model,
        existingArchive: String,
        newContext: String,
        systemPrompt: String
    ): String {
        val inputPrompt = """
            Current Date: ${LocalDate.now()}

            # Existing Memory Archive:
            ${existingArchive.ifBlank { "(Empty)" }}

            # New Conversation Context:
            $newContext

            Please provide the fully updated Memory Archive incorporating all relevant new information.
        """.trimIndent()

        val h = handler as me.rerere.ai.provider.Provider<me.rerere.ai.provider.ProviderSetting>
        val resp = h.generateText(
            providerSetting,
            listOf(
                UIMessage.system(systemPrompt),
                UIMessage.user(inputPrompt)
            ),
            TextGenerationParams(model = model, temperature = 0.2f, topP = 0f)
        )
        return resp.choices.firstOrNull()?.message?.toContentText()?.trim() ?: ""
    }
}
