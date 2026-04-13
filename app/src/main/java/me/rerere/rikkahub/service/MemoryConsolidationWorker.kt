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
import me.rerere.rikkahub.core.data.db.dao.ChatSegmentDAO
import me.rerere.rikkahub.core.data.db.entity.ChatEpisodeEntity
import me.rerere.rikkahub.core.data.model.Assistant
import me.rerere.rikkahub.core.data.model.Conversation
import me.rerere.rikkahub.core.data.repository.ConversationRepository
import me.rerere.rikkahub.core.data.repository.MemoryRepository
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_EPISODIC_CONSOLIDATION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_FULL_SUMMARY_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_KEYWORD_EXTRACTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_MASTER_MEMORY_PROMPT
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.LocalDate
import java.util.Locale
import kotlin.uuid.Uuid
import me.rerere.rikkahub.core.data.ai.EmbeddingService
import me.rerere.rikkahub.common.JsonInstant
import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.core.data.utils.KeywordExtractor

private const val TAG = "MemoryConsolidation"

class MemoryConsolidationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {
    private val conversationRepository: ConversationRepository by inject()
    private val settingsStore: SettingsStore by inject()
    private val providerManager: ProviderManager by inject()
    private val chatEpisodeDAO: ChatEpisodeDAO by inject()
    private val chatSegmentDAO: ChatSegmentDAO by inject()
    private val memoryRepository: MemoryRepository by inject()
    private val embeddingService: EmbeddingService by inject()

    private class ResolvedModel(
        val handler: me.rerere.ai.provider.Provider<me.rerere.ai.provider.ProviderSetting>,
        val provider: me.rerere.ai.provider.ProviderSetting,
        val model: me.rerere.ai.provider.Model
    )

    companion object {
        // 全局静态锁，防止同一个会话同时被多个 Worker 处理
        private val processingConversations = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        private fun tryLock(convId: String): Boolean = processingConversations.add(convId)
        private fun unlock(convId: String) { processingConversations.remove(convId) }
    }

    override suspend fun doWork(): Result {
        val forceConversationId = inputData.getString("FORCE_CONVERSATION_ID")
        val assistantIdString = inputData.getString("ASSISTANT_ID")

        Log.i(TAG, "Starting memory consolidation (Force: $forceConversationId, Assistant: $assistantIdString)")

        try {
            if (forceConversationId != null) {
                if (!tryLock(forceConversationId)) {
                    Log.i(TAG, "Conversation $forceConversationId is already being processed, skipping.")
                    return Result.success()
                }
                try {
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
                } finally {
                    unlock(forceConversationId)
                }
            }

            val settings = settingsStore.settingsFlow.first()
            val assistants = if (assistantIdString != null) {
                settings.assistants.filter { it.id.toString() == assistantIdString }
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

        // 校验：新消息达到阈值 (4条)
        if (existingEpisode != null) {
            val increment = messageCount - existingEpisode.significance
            if (increment < 4) {
                updateLastResult(
                    assistantId = assistant.id,
                    result = applicationContext.getString(R.string.assistant_memory_consolidation_insufficient_data)
                )
                return false
            }
        } else if (messageCount < 4) {
            updateLastResult(
                assistantId = assistant.id,
                result = applicationContext.getString(R.string.assistant_memory_consolidation_insufficient_data)
            )
            return false
        }

        // 修改：情节记忆使用 summarizerModelId
        val summarizer = resolveModel(assistant.summarizerModelId ?: settings.summarizerModelId, settings) ?: return false
        val background = resolveModel(assistant.backgroundModelId ?: settings.backgroundModelId, settings) ?: summarizer

        return try {
            // 执行增量滚动式总结
            val summary = generateRollingSummary(
                handler = summarizer.handler,
                providerSetting = summarizer.provider,
                model = summarizer.model,
                assistant = assistant,
                conv = conv,
                existingEpisode = existingEpisode
            )

            if (summary.isNotBlank()) {
                // 1. AI 提取关键词 (使用背景模型)
                val aiKeywords = extractKeywords(
                    handler = background.handler,
                    providerSetting = background.provider,
                    model = background.model,
                    summary = summary
                )

                // 2. 本地算法提取关键词
                val localKeywords = KeywordExtractor.extract(summary)

                // 3. 合并关键词并去重
                val mergedKeywords = mergeKeywords(aiKeywords, localKeywords)

                // 生成向量（补充逻辑）
                val effectiveContent = if (mergedKeywords.isNotBlank()) {
                    "Keywords: $mergedKeywords\nContent: $summary"
                } else {
                    summary
                }
                val embeddingResult = try {
                    embeddingService.embedWithModelId(effectiveContent, assistant.id.toString())
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to generate embedding", e)
                    null
                }

                val episode = ChatEpisodeEntity(
                    id = existingEpisode?.id ?: 0,
                    assistantId = assistant.id.toString(),
                    conversationId = conv.id.toString(),
                    content = summary,
                    keywords = mergedKeywords,
                    embedding = embeddingResult?.embeddings?.firstOrNull()?.let { JsonInstant.encodeToString(it) },
                    embeddingModelId = embeddingResult?.modelId,
                    startTime = conv.createAt.toEpochMilli(),
                    endTime = conv.updateAt.toEpochMilli(),
                    significance = messageCount,
                    lastAccessedAt = System.currentTimeMillis()
                )

                chatEpisodeDAO.insertEpisode(episode)
                conversationRepository.markAsConsolidated(conv.id)

                updateLastResult(
                    assistantId = assistant.id,
                    result = "Consolidation Successful"
                )
                true
            } else {
                updateLastResult(
                    assistantId = assistant.id,
                    result = "Consolidation Failed: Empty Summary"
                )
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Manual consolidation failed", e)
            updateLastResult(
                assistantId = assistant.id,
                result = "Consolidation Failed: ${e.message}"
            )
            false
        }
    }

    private suspend fun consolidateAssistantMemories(assistant: Assistant, isFullScan: Boolean) {
        val currentSettings = settingsStore.settingsFlow.first()
        val currentAssistant = currentSettings.assistants.find { it.id == assistant.id } ?: assistant

        val conversations = conversationRepository.getConversationsOfAssistant(currentAssistant.id).first()

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

        // 1. 解析情节记忆 (L2) 模型
        val summarizer = resolveModel(currentAssistant.summarizerModelId ?: currentSettings.summarizerModelId, currentSettings) ?: return
        val background = resolveModel(currentAssistant.backgroundModelId ?: currentSettings.backgroundModelId, currentSettings) ?: summarizer

        // 2. 解析大师记忆 (L3) 模型
        val memory = resolveModel(currentAssistant.memoryModelId ?: currentSettings.memoryModelId, currentSettings) ?: summarizer

        var episodicSuccessCount = 0
        for (conv in toConsolidateEpisodes) {
            val convIdString = conv.id.toString()
            if (!tryLock(convIdString)) {
                Log.i(TAG, "Conversation $convIdString is already being processed by another worker, skipping.")
                continue
            }
            try {
                val existingEpisode = chatEpisodeDAO.getEpisodeByConversationId(convIdString)

                val summary = generateRollingSummary(
                    handler = summarizer.handler,
                    providerSetting = summarizer.provider,
                    model = summarizer.model,
                    assistant = currentAssistant,
                    conv = conv,
                    existingEpisode = existingEpisode
                )

                if (summary.isNotBlank()) {
                    // 1. AI 提取关键词
                    val aiKeywords = extractKeywords(
                        handler = background.handler,
                        providerSetting = background.provider,
                        model = background.model,
                        summary = summary
                    )

                    // 2. 本地提取关键词
                    val localKeywords = KeywordExtractor.extract(summary)

                    // 3. 合并
                    val mergedKeywords = mergeKeywords(aiKeywords, localKeywords)

                    // 生成向量（补充逻辑）
                    val effectiveContent = if (mergedKeywords.isNotBlank()) {
                        "Keywords: $mergedKeywords\nContent: $summary"
                    } else {
                        summary
                    }
                    val embeddingResult = try {
                        embeddingService.embedWithModelId(effectiveContent, currentAssistant.id.toString())
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to generate embedding", e)
                        null
                    }

                    val episode = ChatEpisodeEntity(
                        id = existingEpisode?.id ?: 0,
                        assistantId = currentAssistant.id.toString(),
                        conversationId = convIdString,
                        content = summary,
                        keywords = mergedKeywords,
                        embedding = embeddingResult?.embeddings?.firstOrNull()?.let { JsonInstant.encodeToString(it) },
                        embeddingModelId = embeddingResult?.modelId,
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
            } finally {
                unlock(convIdString)
            }
        }

        // --- Process Master Memory ---
        var updatedMasterContent: String? = null
        if (currentAssistant.enableMasterMemory) {
            val newConversations = conversations.filter {
                val updateTime = it.updateAt.toEpochMilli()
                updateTime > currentAssistant.lastMasterMemoryUpdate && it.currentMessages.size >= 4
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
                                "${it.role}: ${it.toText().take(1000)}"
                            }
                            contextParts.add("Recent Messages:\n$messagesText")
                        }
                    }

                    val recentContext = contextParts.joinToString("\n\n---\n\n")

                    if (recentContext.isNotBlank()) {
                        updatedMasterContent = updateMasterMemory(
                            handler = memory.handler,
                            providerSetting = memory.provider,
                            model = memory.model,
                            existingArchive = currentAssistant.masterMemoryContent,
                            newContext = recentContext,
                            systemPrompt = currentAssistant.masterMemoryPrompt.ifBlank { DEFAULT_MASTER_MEMORY_PROMPT }
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update Master Memory", e)
                }
            }
        }

        // --- Finalize Result Update ---
        val finalSettings = settingsStore.settingsFlow.first()
        val now = System.currentTimeMillis()

        val updatedSettings = finalSettings.copy(
            assistants = finalSettings.assistants.map { assistantItem ->
                if (currentAssistant.id == assistantItem.id) {
                    assistantItem.copy(
                        lastConsolidationTime = now,
                        lastConsolidationResult = if (episodicSuccessCount > 0) {
                            "Consolidated $episodicSuccessCount items automatically"
                        } else {
                            assistantItem.lastConsolidationResult
                        },
                        masterMemoryContent = updatedMasterContent ?: assistantItem.masterMemoryContent,
                        lastMasterMemoryUpdate = if (updatedMasterContent != null) {
                            now
                        } else {
                            assistantItem.lastMasterMemoryUpdate
                        }
                    )
                } else {
                    assistantItem
                }
            }
        )
        settingsStore.update(updatedSettings)
    }

    @Suppress("UNCHECKED_CAST")
    private fun resolveModel(modelId: Uuid, settings: Settings): ResolvedModel? {
        val model = settings.findModelById(modelId) ?: return null
        val providerSetting = model.findProvider(settings.providers) ?: return null
        val handler = providerManager.getProviderByType(providerSetting) as? me.rerere.ai.provider.Provider<me.rerere.ai.provider.ProviderSetting> ?: return null
        return ResolvedModel(handler, providerSetting, model)
    }

    private suspend fun generateRollingSummary(
        handler: me.rerere.ai.provider.Provider<*>,
        providerSetting: me.rerere.ai.provider.ProviderSetting,
        model: me.rerere.ai.provider.Model,
        assistant: Assistant,
        conv: Conversation,
        existingEpisode: ChatEpisodeEntity?
    ): String {
        // 1. 获取进度
        val episodeSignificance = existingEpisode?.significance ?: 0
        val summarySignificance = if (conv.contextSummary != null) {
            conv.contextSummaryUpToIndex + 1
        } else {
            0
        }

        // 2. 选取最佳基准
        val (baseSummary, skipCount) = if (summarySignificance >= episodeSignificance) {
            conv.contextSummary to summarySignificance
        } else {
            existingEpisode?.content to episodeSignificance
        }

        val newMessages = conv.currentMessages.drop(skipCount)

        return if (newMessages.isEmpty() && baseSummary != null) {
            Log.i(TAG, "generateRollingSummary: No new messages, reusing base summary.")
            baseSummary
        } else {
            Log.i(TAG, "generateRollingSummary: Rolling summary from index $skipCount.")
            generateConversationSummary(
                handler = handler,
                providerSetting = providerSetting,
                model = model,
                assistantName = assistant.name,
                previousSummary = baseSummary,
                messages = newMessages,
                temporarySummaries = conv.temporarySummaries
            )
        }
    }

    private suspend fun updateLastResult(assistantId: Uuid, result: String) {
        val settings = settingsStore.settingsFlow.first()
        val updated = settings.copy(
            assistants = settings.assistants.map { assistantItem ->
                if (assistantItem.id == assistantId) {
                    assistantItem.copy(
                        lastConsolidationTime = System.currentTimeMillis(),
                        lastConsolidationResult = result
                    )
                } else {
                    assistantItem
                }
            }
        )
        settingsStore.update(updated)
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun generateConversationSummary(
        handler: me.rerere.ai.provider.Provider<*>,
        providerSetting: me.rerere.ai.provider.ProviderSetting,
        model: me.rerere.ai.provider.Model,
        assistantName: String,
        previousSummary: String?,
        messages: List<UIMessage>,
        temporarySummaries: List<String> = emptyList()
    ): String {
        val messagesText = messages.takeLast(100).joinToString("\n") {
            "${it.role}: ${it.toText().take(5000)}"
        }

        val detailText = if (temporarySummaries.isNotEmpty()) {
            "\n### Tactical Details from Recent Segments:\n" +
                temporarySummaries.joinToString("\n") { "- $it" }
        } else ""

        val locale = Locale.getDefault().displayName

        val prompt = if (previousSummary != null) {
            DEFAULT_FULL_SUMMARY_PROMPT
                .replace("{{previous_summary}}", previousSummary + detailText)
                .replace("{{new_messages}}", messagesText)
                .replace("{{locale}}", locale)
                .replace("{{char}}", assistantName)
        } else {
            DEFAULT_EPISODIC_CONSOLIDATION_PROMPT
                .replace("{{text}}", detailText + "\n" + messagesText)
                .replace("{{locale}}", locale)
                .replace("{{char}}", assistantName)
        }

        val h = handler as me.rerere.ai.provider.Provider<me.rerere.ai.provider.ProviderSetting>
        val resp = h.generateText(
            providerSetting = providerSetting,
            messages = listOf(UIMessage.user(prompt)),
            params = TextGenerationParams(
                model = model,
                temperature = 0.3f,
                topP = 0f,
                maxTokens = 1024
            )
        )
        return resp.choices.firstOrNull()?.message?.toContentText()?.trim() ?: ""
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun extractKeywords(
        handler: me.rerere.ai.provider.Provider<*>,
        providerSetting: me.rerere.ai.provider.ProviderSetting,
        model: me.rerere.ai.provider.Model,
        summary: String
    ): String {
        val locale = Locale.getDefault().displayName
        val prompt = DEFAULT_KEYWORD_EXTRACTION_PROMPT
            .replace("{{summary}}", summary)
            .replace("{{locale}}", locale)

        val h = handler as me.rerere.ai.provider.Provider<me.rerere.ai.provider.ProviderSetting>
        val resp = h.generateText(
            providerSetting = providerSetting,
            messages = listOf(UIMessage.user(prompt)),
            params = TextGenerationParams(
                model = model,
                temperature = 0.3f,
                topP = 0f,
                maxTokens = 256
            )
        )
        return resp.choices.firstOrNull()?.message?.toContentText()?.trim() ?: ""
    }

    private fun mergeKeywords(ai: String, local: String): String {
        val aiList = ai.split(Regex("[,，、；;]")).map { it.trim().lowercase() }.filter { it.isNotBlank() }
        val localList = local.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }
        return (aiList + localList).distinct().joinToString(",")
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
            providerSetting = providerSetting,
            messages = listOf(
                UIMessage.system(systemPrompt),
                UIMessage.user(inputPrompt)
            ),
            params = TextGenerationParams(
                model = model,
                temperature = 0.2f,
                topP = 0f,
                maxTokens = 2048
            )
        )
        return resp.choices.firstOrNull()?.message?.toContentText()?.trim() ?: ""
    }
}
