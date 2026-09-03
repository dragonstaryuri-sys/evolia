package me.rerere.rikkahub.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.delay
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
import me.rerere.rikkahub.core.data.model.AssistantSearchMode
import me.rerere.rikkahub.core.data.repository.ConversationRepository
import me.rerere.rikkahub.core.data.repository.MemoryRepository
import me.rerere.rikkahub.core.data.repository.AgentTaskRepository
import me.rerere.rikkahub.core.data.ai.prompts.DEFAULT_FULL_SUMMARY_PROMPT
import me.rerere.rikkahub.core.data.ai.prompts.DEFAULT_MASTER_MEMORY_COMPRESSION_PROMPT
import me.rerere.rikkahub.core.data.ai.prompts.DEFAULT_MASTER_MEMORY_PROMPT
import me.rerere.rikkahub.core.data.ai.prompts.applyPlaceholders
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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.common.JsonInstant
import java.io.IOException
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.ui.limitContext
private val TAG = "MemoryConsolidation"

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
    private val agentTaskRepository: AgentTaskRepository by inject()
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
        private fun unlock(convId: String) {
            processingConversations.remove(convId)
        }
    }

    override suspend fun doWork(): Result {
        val forceConversationId = inputData.getString("FORCE_CONVERSATION_ID")
        val assistantIdString = inputData.getString("ASSISTANT_ID")
        val forceMaster = inputData.getBoolean("FORCE_MASTER", false)
        val incrementalMaster = inputData.getBoolean("INCREMENTAL_MASTER", false)
        val isManual = inputData.getBoolean("IS_MANUAL", false)

        Log.i(
            TAG,
            "Starting memory consolidation (Force: $forceConversationId, Assistant: $assistantIdString, IncrementalMaster: $incrementalMaster)"
        )

        return try {
            // 自动清理已执行的任务数据
            try {
                agentTaskRepository.deleteExecutedTasks()
                Log.i(TAG, "Garbage collection: Executed agent tasks cleared.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear executed tasks", e)
            }

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
                        return Result.failure(workDataOf("error_tag" to "ERROR_EXCEPTION:Conversation not found"))
                    }

                    val settings = settingsStore.settingsFlow.first()
                    val assistant = settings.assistants.find { it.id == conv.assistantId }
                        ?: return Result.failure(workDataOf("error_tag" to "ERROR_EXCEPTION:Assistant not found"))

                    val errorTag = manualConsolidate(assistant, conv, isManual)
                    if (errorTag == null) {
                        Result.success()
                    } else {
                        Result.failure(workDataOf("error_tag" to errorTag))
                    }
                } finally {
                    unlock(forceConversationId)
                }
            } else {
                val settings = settingsStore.settingsFlow.first()
                val assistants = if (assistantIdString != null) {
                    settings.assistants.filter { it.id.toString() == assistantIdString }
                } else {
                    // 如果是定时器触发，筛选开启了任一记忆整合功能的助理
                    settings.assistants.filter { it.enableMemoryConsolidation || it.enableMasterMemory }
                }

                if (assistants.isEmpty()) {
                    Log.i(TAG, "No assistants to process.")
                    Result.success()
                } else {
                    for (assistant in assistants) {
                        consolidateAssistantMemories(assistant, false, forceMaster, incrementalMaster, isManual)
                    }
                    Result.success()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Consolidation failed", e)
            if (e is IOException || e.cause is IOException) {
                Result.retry()
            } else {
                Result.failure(workDataOf("error_tag" to "ERROR_EXCEPTION:${e.message ?: "Unknown Error"}"))
            }
        }
    }

    private suspend fun <T> retryIO(
        times: Int = 2,
        initialDelay: Long = 2000,
        block: suspend () -> T
    ): T {
        var currentDelay = initialDelay
        repeat(times) {
            try {
                return block()
            } catch (e: Exception) {
                val isNetworkError = e is java.io.IOException ||
                    e.message?.contains("timeout", ignoreCase = true) == true ||
                    e.message?.contains("canceled", ignoreCase = true) == true
                if (isNetworkError) {
                    Log.w(TAG, "Retryable error occurred (Attempt ${it + 1}): ${e.message}")
                    delay(currentDelay)
                    currentDelay *= 2
                } else {
                    throw e
                }
            }
        }
        return block()
    }

    private suspend fun manualConsolidate(assistant: Assistant, conv: Conversation, isManual: Boolean): String? {
        val settings = settingsStore.settingsFlow.first()
        val existingEpisode = chatEpisodeDAO.getEpisodeByConversationId(conv.id.toString())
        val anchorTime = existingEpisode?.endTime ?: 0L

        // 获取当前所有生效（非删除，当前选定版本）的消息
        val currentMessages = conv.currentMessages

        // 过滤掉已经在旧归档中的消息
        val visibleMessages = currentMessages.filter { msg ->
            msg.createdAt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds() > anchorTime
        }

        // 手动归档时：
        // 归档数量 = 未归档总数 - 保留数量(10)
        // 校验：归档数量 >= 10
        val totalUnconsolidated = visibleMessages.size
        val consolidateCount = if (isManual) {
            val messagesToKeep = visibleMessages.limitContext(10)
            totalUnconsolidated - messagesToKeep.size
        } else {
            totalUnconsolidated // 自动任务默认处理全部可见消息
        }

        if (isManual && consolidateCount < 10) {
            return "ERROR_INSUFFICIENT_MESSAGES"
        }

        // 修改：情节记忆使用 summarizerModelId
        val summarizer =
            resolveModel(assistant.summarizerModelId ?: settings.summarizerModelId, settings) ?: return "ERROR_NO_MODEL"

        return try {
            // 执行增量滚动式总结
            val messagesToProcess = if (isManual) {
                visibleMessages.take(consolidateCount)
            } else {
                visibleMessages
            }

            if (messagesToProcess.isEmpty()) return "ERROR_NO_MESSAGES"

            val summary = generateConversationSummary(
                handler = summarizer.handler,
                providerSetting = summarizer.provider,
                model = summarizer.model,
                assistantName = assistant.name,
                previousSummary = existingEpisode?.content,
                messages = messagesToProcess
            )

            if (summary.isNotBlank()) {
                val lastArchivedMessage = messagesToProcess.last()
                val lastArchivedTime = lastArchivedMessage.createdAt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
                val episode = ChatEpisodeEntity(
                    id = existingEpisode?.id ?: 0,
                    assistantId = assistant.id.toString(),
                    conversationId = conv.id.toString(),
                    content = summary,
                    keywords = "",
                    embedding = null,
                    embeddingModelId = null,
                    startTime = messagesToProcess.first().createdAt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds(),
                    endTime = lastArchivedTime,
                    significance = messagesToProcess.size + (existingEpisode?.significance ?: 0),
                    lastAccessedAt = System.currentTimeMillis()
                )

                memoryRepository.saveEpisode(episode)

                // 只有立即整合（手动触发 episode 生成）才更新截断游标。
                // 自动扫描（isManual=false）不设置：自动归档只生成 segment，
                // 不应截断 AI 上下文。episode 与 segment 的时间戳互不影响。
                if (isManual) {
                    conversationRepository.updateLastArchivedMessageTime(conv.id, lastArchivedTime)
                }

                updateLastResult(
                    assistantId = assistant.id,
                    result = "Consolidation Successful",
                    wasUpdated = true
                )
                null
            } else {
                updateLastResult(
                    assistantId = assistant.id,
                    result = "Consolidation Failed: Empty Summary",
                    wasUpdated = false
                )
                "ERROR_EMPTY_SUMMARY"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Manual consolidation failed", e)
            updateLastResult(
                assistantId = assistant.id,
                result = "Consolidation Failed: ${e.message}",
                wasUpdated = false
            )
            "ERROR_EXCEPTION:${e.message ?: "Unknown API Error"}"
        }
    }

    private suspend fun consolidateAssistantMemories(
        assistant: Assistant,
        isFullScan: Boolean,
        forceMaster: Boolean = false,
        incrementalMaster: Boolean = false,
        isManual: Boolean = false
    ) {
        val currentSettings = settingsStore.settingsFlow.first()
        val currentAssistant = currentSettings.assistants.find { it.id == assistant.id } ?: assistant

        val conversations = conversationRepository.getConversationsOfAssistant(currentAssistant.id).first()

        val toConsolidateEpisodes = conversations.filter { conv ->
            if (!currentAssistant.enableMemoryConsolidation) return@filter false
            val existingEpisode = kotlinx.coroutines.runBlocking {
                chatEpisodeDAO.getEpisodeByConversationId(conv.id.toString())
            }
            val anchorTime = existingEpisode?.endTime ?: 0L
            val increment = kotlinx.coroutines.runBlocking {
                conversationRepository.countNewMessages(conv.id.toString(), anchorTime)
            }
            // 自动扫描依然保持 4 条阈值
            if (increment < 4) return@filter false
            val delayMillis = currentAssistant.consolidationDelayMinutes * 60 * 1000L
            val timeSinceUpdate = System.currentTimeMillis() - conv.updateAt.toEpochMilli()
            timeSinceUpdate >= delayMillis
        }

        // 1. 解析情节记忆 (L2) 模型
        val summarizer =
            resolveModel(currentAssistant.summarizerModelId ?: currentSettings.summarizerModelId, currentSettings)
                ?: return
        val background =
            resolveModel(currentAssistant.backgroundModelId ?: currentSettings.backgroundModelId, currentSettings)
                ?: summarizer

        // 2. 解析大师记忆 (L3) 模型
        val memory =
            resolveModel(currentAssistant.memoryModelId ?: currentSettings.memoryModelId, currentSettings) ?: summarizer

        var episodicSuccessCount = 0
        // 仅在非 L3 专项更新时，才执行情节记忆整合
        if (!forceMaster && !incrementalMaster && currentAssistant.enableMemoryConsolidation) {
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
                        val fullConv = conversationRepository.getConversationById(conv.id) ?: conv
                        val anchorTime = existingEpisode?.endTime ?: 0L
                        val newMessages = fullConv.currentMessages.filter {
                            it.createdAt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds() > anchorTime
                        }

                        if (newMessages.isNotEmpty()) {
                            val episode = ChatEpisodeEntity(
                                id = existingEpisode?.id ?: 0,
                                assistantId = assistant.id.toString(),
                                conversationId = convIdString,
                                content = summary,
                                keywords = "",
                                embedding = null,
                                embeddingModelId = null,
                                startTime = newMessages.first().createdAt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds(),
                                endTime = newMessages.last().createdAt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds(),
                                significance = newMessages.size + (existingEpisode?.significance ?: 0),
                                lastAccessedAt = System.currentTimeMillis()
                            )

                            memoryRepository.saveEpisode(episode)
                            conversationRepository.markAsConsolidated(conv.id)
                            episodicSuccessCount++
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to consolidate conversation ${conv.id} to episode", e)
                } finally {
                    unlock(convIdString)
                }
            }
        }

        // --- Process Master Memory (L3) ---
        var updatedMasterContent: String? = null
        var wasCompressed = false
        var masterError: String? = null
        // 仅在明确传入指令且开启了功能时更新
        if ((forceMaster || incrementalMaster) && currentAssistant.enableMasterMemory) {
            // 不管手动还是自动触发，都只取最新的 5 个会话作为素材
            val targetConversations = conversations
                .filter { it.currentMessages.size >= 2 }
                .sortedByDescending { it.updateAt.toEpochMilli() }
                .take(5)
                .reversed() // 恢复时间正序排列，利于 AI 理解

            // 触发条件：强制更新 或 最新会话有增量消息
            val latestConversationTime = targetConversations.lastOrNull()?.updateAt?.toEpochMilli() ?: 0L
            val shouldUpdate = forceMaster || (latestConversationTime > currentAssistant.lastMasterMemoryUpdate)

            if (shouldUpdate && targetConversations.isNotEmpty()) {
                try {
                    val contextParts = mutableListOf<String>()
                    for (conv in targetConversations) {
                        val summary = chatEpisodeDAO.getEpisodeByConversationId(conv.id.toString())?.content
                        if (!summary.isNullOrBlank()) {
                            contextParts.add("Conversation Summary: $summary")
                        } else {
                            // 对应需求：未生成 L2 则取最后 10 条，每条限 1000 字符
                            val messagesText = conv.currentMessages.takeLast(10).joinToString("\n") {
                                "${it.role}: ${it.toContentText().take(1000)}"
                            }
                            contextParts.add("Recent Messages:\n$messagesText")
                        }
                    }

                    val recentContext = contextParts.joinToString("\n\n---\n\n")

                    updatedMasterContent = updateMasterMemory(
                        handler = memory.handler,
                        providerSetting = memory.provider,
                        model = memory.model,
                        assistantName = currentAssistant.name,
                        existingArchive = currentAssistant.masterMemoryContent,
                        newContext = recentContext.ifBlank { "No new recent conversations." },
                        systemPrompt = DEFAULT_MASTER_MEMORY_PROMPT,
                        thinkingBudget = 2048
                    )

                    if (updatedMasterContent.length > 1500) {
                        updatedMasterContent = compressMasterMemory(
                            handler = memory.handler,
                            providerSetting = memory.provider,
                            model = memory.model,
                            archiveToCompress = updatedMasterContent,
                            thinkingBudget = 2048
                        )
                        wasCompressed = true
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update Master Memory", e)
                    masterError = e.message ?: "Unknown error"
                    if (e is IOException || e.cause is IOException) throw e
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
                        lastConsolidationTime = if (episodicSuccessCount > 0 || updatedMasterContent != null) now else assistantItem.lastConsolidationTime,
                        lastConsolidationResult = when {
                            masterError != null -> "Master Memory Error: $masterError"
                            updatedMasterContent != null && forceMaster -> "Master Memory updated manually"
                            updatedMasterContent != null && incrementalMaster -> "Daily Master Memory sync successful"
                            episodicSuccessCount > 0 -> "Consolidated $episodicSuccessCount items automatically"
                            else -> assistantItem.lastConsolidationResult
                        },
                        masterMemoryContent = updatedMasterContent ?: assistantItem.masterMemoryContent,
                        lastMasterMemoryUpdate = if (updatedMasterContent != null) now else assistantItem.lastMasterMemoryUpdate
                    )
                } else {
                    assistantItem
                }
            }
        )
        settingsStore.update(updatedSettings)

        // 发送通知
        if (updatedMasterContent != null) {
            val content = if (wasCompressed) {
                applicationContext.getString(R.string.master_memory_update_compressed, currentAssistant.name)
            } else {
                applicationContext.getString(R.string.master_memory_update_success, currentAssistant.name)
            }
            sendNotification(
                title = applicationContext.getString(R.string.master_memory_update_done),
                content = content
            )
        } else if (masterError != null) {
            sendNotification(
                title = applicationContext.getString(R.string.master_memory_update_done) + " (Failed)",
                content = "Error updating ${currentAssistant.name}: $masterError"
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun resolveModel(modelId: Uuid, settings: Settings): ResolvedModel? {
        val model = settings.findModelById(modelId) ?: return null
        val providerSetting = model.findProvider(settings.providers) ?: return null
        val handler =
            providerManager.getProviderByType(providerSetting) as? me.rerere.ai.provider.Provider<me.rerere.ai.provider.ProviderSetting>
                ?: return null
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
        val anchorTime = existingEpisode?.endTime ?: 0L
        val fullConv = conversationRepository.getConversationById(conv.id) ?: conv
        val newMessages = fullConv.currentMessages.filter {
            it.createdAt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds() > anchorTime
        }

        return if (newMessages.isEmpty() && existingEpisode != null) {
            existingEpisode.content
        } else {
            generateConversationSummary(
                handler = handler,
                providerSetting = providerSetting,
                model = model,
                assistantName = assistant.name,
                previousSummary = existingEpisode?.content,
                messages = newMessages
            )
        }
    }

    private suspend fun updateLastResult(assistantId: Uuid, result: String, wasUpdated: Boolean) {
        val settings = settingsStore.settingsFlow.first()
        val now = System.currentTimeMillis()
        val updated = settings.copy(
            assistants = settings.assistants.map { assistantItem ->
                if (assistantItem.id == assistantId) {
                    assistantItem.copy(
                        lastConsolidationTime = if (wasUpdated) now else assistantItem.lastConsolidationTime,
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
        messages: List<UIMessage>
    ): String {
        val messagesText = messages.takeLast(100).joinToString("\n") {
            "${it.role}: ${it.toContentText().take(5000)}"
        }
        val locale = Locale.getDefault().displayName
        val prompt = DEFAULT_FULL_SUMMARY_PROMPT.applyPlaceholders(
            "previous_summary" to (previousSummary ?: "None"),
            "new_messages" to messagesText,
            "locale" to locale,
            "char" to assistantName
        )
        val h = handler as me.rerere.ai.provider.Provider<me.rerere.ai.provider.ProviderSetting>
        val resp = retryIO(times = 2) {
            h.generateText(
                providerSetting = providerSetting,
                messages = listOf(UIMessage.user(prompt)),
                params = TextGenerationParams(
                    model = model,
                    temperature = 0.3f,
                    topP = 0.5f,
                    maxTokens = 1024,
                    thinkingBudget = 0 // 显式关闭深度思考，防止干扰总结输出
                )
            )
        }
        return resp.choices.firstOrNull()?.message?.toContentText()?.trim() ?: ""
    }


    private fun mergeKeywords(ai: String, local: String): String {
        val aiList = ai.split(Regex("[,，、；;]")).map { it.trim().lowercase() }.filter { it.isNotBlank() }
        if (aiList.isNotEmpty()) return aiList.distinct().joinToString(",")
        val localList = local.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }
        return localList.distinct().joinToString(",")
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun updateMasterMemory(
        handler: me.rerere.ai.provider.Provider<*>,
        providerSetting: me.rerere.ai.provider.ProviderSetting,
        model: me.rerere.ai.provider.Model,
        assistantName: String,
        existingArchive: String,
        newContext: String,
        systemPrompt: String,
        thinkingBudget: Int?
    ): String {
        val locale = Locale.getDefault().displayName
        val finalSystemPrompt = systemPrompt.applyPlaceholders("char" to assistantName, "locale" to locale)
        val inputPrompt = """
            Current Date: ${LocalDate.now()}
            # 当前记忆档案:
            ${existingArchive.ifBlank { "(Empty)" }}
            # 新的对话内容:
            $newContext
            Please provide the fully updated Memory Archive incorporating all relevant new information.
        """.trimIndent()
        val h = handler as me.rerere.ai.provider.Provider<me.rerere.ai.provider.ProviderSetting>
        val resp = retryIO(times = 1, initialDelay = 30000) {
            h.generateText(
                providerSetting = providerSetting,
                messages = listOf(UIMessage.system(finalSystemPrompt), UIMessage.user(inputPrompt)),
                params = TextGenerationParams(
                    model = model,
                    temperature = 0.2f,
                    topP = 0.5f,
                    maxTokens = 12000,
                    thinkingBudget = thinkingBudget
                )
            )
        }
        return resp.choices.firstOrNull()?.message?.toContentText()?.trim() ?: ""
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun compressMasterMemory(
        handler: me.rerere.ai.provider.Provider<*>,
        providerSetting: me.rerere.ai.provider.ProviderSetting,
        model: me.rerere.ai.provider.Model,
        archiveToCompress: String,
        thinkingBudget: Int?
    ): String {
        val locale = Locale.getDefault().displayName
        val sysPrompt = DEFAULT_MASTER_MEMORY_COMPRESSION_PROMPT.applyPlaceholders("locale" to locale)
        val userPrompt = "Memory Archive to Compress:\n$archiveToCompress"
        val h = handler as me.rerere.ai.provider.Provider<me.rerere.ai.provider.ProviderSetting>
        val resp = retryIO(times = 2, initialDelay = 30000) {
            h.generateText(
                providerSetting = providerSetting,
                messages = listOf(UIMessage.system(sysPrompt), UIMessage.user(userPrompt)),
                params = TextGenerationParams(
                    model = model,
                    temperature = 0.3f,
                    topP = 0.5f,
                    maxTokens = 6000,
                    thinkingBudget = thinkingBudget
                )
            )
        }
        return resp.choices.firstOrNull()?.message?.toContentText()?.trim() ?: archiveToCompress
    }

    private fun sendNotification(title: String, content: String) {
        val notificationManager = NotificationManagerCompat.from(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        try {
            notificationManager.notify(System.currentTimeMillis().toInt(), notification)
        } catch (e: SecurityException) {
        }
    }
}
