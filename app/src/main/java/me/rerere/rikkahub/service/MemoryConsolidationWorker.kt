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
import kotlin.uuid.Uuid

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
        val forceConversationId = inputData.getString("FORCE_CONVERSATION_ID")
        val assistantId = inputData.getString("ASSISTANT_ID")

        Log.i(TAG, "Starting memory consolidation (Force: $forceConversationId, Target Assistant: $assistantId)")

        try {
            if (forceConversationId != null) {
                val convId = Uuid.parse(forceConversationId)
                val conv = conversationRepository.getConversationById(convId)
                if (conv == null) {
                    Log.e(TAG, "Conversation not found: $forceConversationId")
                    return Result.failure()
                }
                val settings = settingsStore.settingsFlow.first()
                val assistant = settings.assistants.find { it.id == conv.assistantId } ?: return Result.failure()

                val success = manualConsolidate(assistant, conv)
                return if (success) Result.success() else Result.failure()
            }

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
                consolidateAssistantMemories(assistant, false)
            }

            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Consolidation failed", e)
            return Result.failure()
        }
    }

    private suspend fun manualConsolidate(assistant: Assistant, conv: Conversation): Boolean {
        val settings = settingsStore.settingsFlow.first()
        val messageCount = conv.currentMessages.size
        val existingEpisode = chatEpisodeDAO.getEpisodeByConversationId(conv.id.toString())

        // 校验：新消息达到4条
        if (existingEpisode != null) {
            if (messageCount - existingEpisode.significance < 4) {
                updateLastResult(assistant.id, applicationContext.getString(R.string.assistant_memory_consolidation_insufficient_data))
                return false
            }
        } else if (messageCount < 4) {
            updateLastResult(assistant.id, applicationContext.getString(R.string.assistant_memory_consolidation_insufficient_data))
            return false
        }

        // 使用 memoryModelId 进行整合
        val modelId = assistant.memoryModelId ?: settings.memoryModelId
        val model = settings.findModelById(modelId) ?: return false
        val providerSetting = model.findProvider(settings.providers) ?: return false
        val handler = providerManager.getProviderByType(providerSetting)

        return try {
            val summary = generateConversationSummary(handler, providerSetting, model, conv)
            if (summary.isNotBlank()) {
                val episode = ChatEpisodeEntity(
                    id = existingEpisode?.id ?: 0,
                    assistantId = assistant.id.toString(),
                    conversationId = conv.id.toString(),
                    content = summary,
                    startTime = conv.createAt.toEpochMilli(),
                    endTime = conv.updateAt.toEpochMilli(),
                    significance = messageCount,
                    lastAccessedAt = System.currentTimeMillis()
                )
                chatEpisodeDAO.insertEpisode(episode)
                conversationRepository.markAsConsolidated(conv.id)
                updateLastResult(assistant.id, "Consolidation Successful")
                true
            } else {
                updateLastResult(assistant.id, "Consolidation Failed: Empty Summary")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Manual consolidation failed", e)
            updateLastResult(assistant.id, "Consolidation Failed: ${e.message}")
            false
        }
    }

    private suspend fun updateLastResult(assistantId: Uuid, result: String) {
        val settings = settingsStore.settingsFlow.first()
        val updated = settings.copy(
            assistants = settings.assistants.map {
                if (it.id == assistantId) it.copy(lastConsolidationResult = result, lastConsolidationTime = System.currentTimeMillis())
                else it
            }
        )
        settingsStore.update(updated)
    }

    private suspend fun consolidateAssistantMemories(assistant: Assistant, isFullScan: Boolean) {
        val currentSettings = settingsStore.settingsFlow.first()
        val currentAssistant = currentSettings.assistants.find { it.id == assistant.id } ?: assistant

        val conversations = conversationRepository.getConversationsOfAssistant(currentAssistant.id).first()

        // 1. Process Episodic Memories
        val toConsolidateEpisodes = conversations.filter { conv ->
            if (!currentAssistant.enableMemoryConsolidation) return@filter false

            val existingEpisode = chatEpisodeDAO.getEpisodeByConversationId(conv.id.toString())
            val messageCount = conv.currentMessages.size

            if (existingEpisode != null) {
                if (messageCount - existingEpisode.significance < 4) return@filter false
            } else {
                if (messageCount < 4) return@filter false
            }

            val delayMillis = currentAssistant.consolidationDelayMinutes * 60 * 1000L
            val timeSinceUpdate = System.currentTimeMillis() - conv.updateAt.toEpochMilli()
            timeSinceUpdate >= delayMillis
        }

        if (toConsolidateEpisodes.isEmpty()) return

        // 使用 memoryModelId 进行整合
        val modelId = currentAssistant.memoryModelId ?: currentSettings.memoryModelId
        val model = currentSettings.findModelById(modelId) ?: return
        val providerSetting = model.findProvider(currentSettings.providers) ?: return
        val handler = providerManager.getProviderByType(providerSetting)

        var episodicSuccessCount = 0
        for (conv in toConsolidateEpisodes) {
            try {
                val summary = generateConversationSummary(handler, providerSetting, model, conv)
                if (summary.isNotBlank()) {
                    val existingEpisode = chatEpisodeDAO.getEpisodeByConversationId(conv.id.toString())
                    val episode = ChatEpisodeEntity(
                        id = existingEpisode?.id ?: 0,
                        assistantId = currentAssistant.id.toString(),
                        conversationId = conv.id.toString(),
                        content = summary,
                        startTime = conv.createAt.toEpochMilli(),
                        endTime = conv.updateAt.toEpochMilli(),
                        significance = conv.currentMessages.size,
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

        // 2. Process Master Memory
        var updatedMasterContent: String? = null
        if (currentAssistant.enableMasterMemory) {
            val newConversations = conversations.filter {
                it.updateAt.toEpochMilli() > currentAssistant.lastMasterMemoryUpdate &&
                    it.currentMessages.size >= 4
            }.sortedBy { it.updateAt }

            if (newConversations.isNotEmpty()) {
                try {
                    val contextParts = mutableListOf<String>()
                    for (conv in newConversations) {
                        val summary = chatEpisodeDAO.getEpisodeByConversationId(conv.id.toString())?.content
                        if (!summary.isNullOrBlank()) {
                            contextParts.add("Conversation Summary: $summary")
                        } else {
                            val messagesText = conv.currentMessages.takeLast(20).joinToString("\n") {
                                "${it.role}: ${it.toText().take(300)}"
                            }
                            contextParts.add("Recent Messages:\n$messagesText")
                        }
                    }

                    val recentContext = contextParts.joinToString("\n\n---\n\n")

                    if (recentContext.isNotBlank()) {
                        updatedMasterContent = updateMasterMemory(
                            handler, providerSetting, model,
                            currentAssistant.masterMemoryContent,
                            recentContext,
                            currentAssistant.masterMemoryPrompt.ifBlank { DEFAULT_MASTER_MEMORY_PROMPT }
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update Master Memory", e)
                }
            }
        }

        // 3. Finalize
        val finalSettings = settingsStore.settingsFlow.first()
        val now = System.currentTimeMillis()

        val updatedSettings = finalSettings.copy(
            assistants = finalSettings.assistants.map {
                if (it.id == currentAssistant.id) {
                    it.copy(
                        lastConsolidationTime = now,
                        lastConsolidationResult = if (episodicSuccessCount > 0) "Consolidated $episodicSuccessCount items automatically" else it.lastConsolidationResult,
                        masterMemoryContent = updatedMasterContent ?: it.masterMemoryContent,
                        lastMasterMemoryUpdate = if (updatedMasterContent != null) now else it.lastMasterMemoryUpdate
                    )
                } else it
            }
        )
        settingsStore.update(updatedSettings)
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
