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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import me.rerere.rikkahub.data.ai.GenerationChunk
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

/** 第一个有效 assistant 消息（首 token）的等待超时：仅限制"模型启动响应"，后续输出内容不受时间限制 */
private const val FIRST_CHUNK_TIMEOUT_MS = 120_000L

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

        // —— 生成过程中使用的共享状态（用于失败时保留已有内容） ——
        var inProgressDiaryId: String? = null
        var generatedContent = ""
        var hadAnyChunkSuccess = false

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

            // 优先使用传入的 targetDate（自动补发昨日时使用），否则用当前日期
            val targetDateStr = inputData.getString("targetDate")
                ?: LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            val targetDate = runCatching { LocalDate.parse(targetDateStr, DateTimeFormatter.ISO_LOCAL_DATE) }
                .getOrDefault(LocalDate.now())

            // —— 目标日期是否已有有效日记：空内容 diary 视为失败残留，不算"已生成" ——
            val existingDiary = diaryRepo.getDiaryByDate(assistant.id.toString(), targetDateStr)
            val hasValidDiary = existingDiary != null && existingDiary.content.isNotBlank()
            if (hasValidDiary) {
                // 目标日期已有非空内容的日记 → 无论自动还是手动都直接跳过
                // 手动生成只用于"自动生成失败/当日没有日记"的补漏场景
                return if (isManual) {
                    Result.success(workDataOf("skipped" to true, "reason" to "already_exists"))
                } else {
                    Result.success()
                }
            }

            // ——————————————————————————————————————————————
            // skeleton 占位 / 清理策略：
            //   - 空壳残留（existingDiary 非空但 content 空）→ 先删掉再新建，避免占位卡住
            //   - 无旧日记 → 新建空 skeleton
            // ——————————————————————————————————————————————
            val skeletonId = if (existingDiary != null && existingDiary.content.isBlank()) {
                runCatching { diaryRepo.deleteDiaryById(existingDiary.id) }
                val newId = Uuid.random().toString()
                diaryRepo.insertDiary(
                    AgentDiaryEntity(
                        id = newId,
                        assistantId = assistant.id.toString(),
                        content = "",
                        date = targetDateStr,
                        createdAt = System.currentTimeMillis()
                    )
                )
                newId
            } else {
                val newId = Uuid.random().toString()
                diaryRepo.insertDiary(
                    AgentDiaryEntity(
                        id = newId,
                        assistantId = assistant.id.toString(),
                        content = "",
                        date = targetDateStr,
                        createdAt = System.currentTimeMillis()
                    )
                )
                newId
            }
            inProgressDiaryId = skeletonId

            // —— 消息起点：目标日期 0 点之前的"最近一篇有内容的日记"的 createdAt ——
            //    注意：新建的 skeleton createdAt 就在现在，会排到最前，必须过滤掉
            //    （以及任何空内容的残留日记），否则起点=当下，取不到任何新消息。
            val allSortedDiaries = diaryRepo
                .getDiariesByAssistant(assistant.id.toString())
                .first()
                .filter { it.content.isNotBlank() && it.id != skeletonId }
                .sortedByDescending { it.createdAt }
            val lastMeaningfulDiary = allSortedDiaries.firstOrNull()
            val startTimeThreshold = lastMeaningfulDiary?.createdAt
                ?: targetDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

            // —— Stage 1（SQL 层粗筛）：只取 update_at >= startTimeThreshold 的会话
            //    避免把该智能体历史所有会话全量加载进内存
            val conversations = conversationRepo.getConversationsOfAssistantAnyModeAfter(
                assistant.id,
                startTimeThreshold
            ).first()
            // —— Stage 2（内存层精筛）：在剩余会话里按消息 createdAt 过滤
            //    处理"会话跨越起点"的情况（旧会话在起点之后又收到新消息）
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

                generatedContent = performGeneration(currentSettings, model, assistant, finalPrompt)
                // 单块分支：成功就立刻写盘
                if (generatedContent.isNotBlank()) {
                    diaryRepo.updateDiary(
                        diaryRepo.getDiaryById(skeletonId)!!.copy(content = generatedContent)
                    )
                    hadAnyChunkSuccess = true
                }
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

                    val chunkResult = performGeneration(currentSettings, model, assistant, finalPrompt)

                    // —— ★ 增量落库：每个 chunk 生成成功立刻写 DB，下次断点能续上 ——
                    if (chunkResult.isNotBlank()) {
                        generatedContent = chunkResult
                        diaryRepo.updateDiary(
                            diaryRepo.getDiaryById(skeletonId)!!.copy(content = generatedContent)
                        )
                        hadAnyChunkSuccess = true
                    }
                }
            }

            if (generatedContent.isNotBlank()) {
                if (!isManual) {
                    showSuccessNotification(assistant.name, assistant.id.toString())
                }
                Result.success(workDataOf("partial" to false))
            } else {
                // 没有生成任何内容 → 清掉空壳避免占位
                inProgressDiaryId?.let { diaryRepo.deleteDiaryById(it) }
                Result.success(workDataOf("skipped" to true, "reason" to "no_content"))
            }
        } catch (ce: CancellationException) {
            // ——————————————————————————————————————————————
            // ★ 修复 ①：CancellationException 必须原样重抛
            //   - WorkManager/协程取消属于正常行为（用户重复点击、切后台被杀、系统回收）
            //   - 不弹"失败"通知，不误导用户
            //   - 如果已有部分内容写入 DB，保留不回滚（用户仍能看到进度）
            // ——————————————————————————————————————————————
            Log.i(TAG, "Diary job cancelled, hadAnyChunkSuccess=$hadAnyChunkSuccess, preservedDiaryId=$inProgressDiaryId")
            if (!hadAnyChunkSuccess) {
                // 完全没产出：删掉 skeleton，避免留下空日记占今日位置
                inProgressDiaryId?.let { runCatching { diaryRepo.deleteDiaryById(it) } }
            }
            throw ce
        } catch (e: Exception) {
            Log.e(TAG, "Diary generation failed, hadAnyChunkSuccess=$hadAnyChunkSuccess", e)
            val errorInfo = e.localizedMessage ?: e.toString()
            return when {
                // —— 部分成功：保留已生成的内容，提示用户，不记失败不重试 ——
                hadAnyChunkSuccess && generatedContent.isNotBlank() -> {
                    if (isManual) {
                        val partialMsg = applicationContext.getString(
                            R.string.discover_page_diary_generate_partial,
                            errorInfo
                        )
                        showSimpleNotification(partialMsg, assistantIdStr)
                    }
                    Result.success(
                        workDataOf(
                            "partial" to true,
                            "reason" to "partial_on_error",
                            "error" to errorInfo
                        )
                    )
                }
                // —— 完全失败且手动触发：直接通知失败，不重试 ——
                isManual -> {
                    inProgressDiaryId?.let { runCatching { diaryRepo.deleteDiaryById(it) } }
                    val errorMsg = applicationContext.getString(R.string.diary_generate_failed, errorInfo)
                    showSimpleNotification(errorMsg, assistantIdStr)
                    Result.failure(workDataOf("error" to errorInfo))
                }
                // —— 完全失败且自动触发：按现有策略最多重试 3 次 ——
                else -> {
                    inProgressDiaryId?.let { runCatching { diaryRepo.deleteDiaryById(it) } }
                    if (runAttemptCount < 3) Result.retry() else Result.failure()
                }
            }
        }
    }

    /**
     * 执行单轮 AI 文本生成。
     *
     * ★ 修复 ②：超时策略由"整个生成 ≤180s/300s"改为"首 token ≤120s"
     *   - 首 chunk（第一个 assistant 有效内容）必须在 120s 内返回，否则视为模型不响应 → 超时取消
     *   - 一旦收到首 chunk，后续输出内容不受总时长限制（消息再多也能慢慢生成完）
     *   - 通过 watchdog + CompletableDeferred 实现：任何一条子协程异常都会让 coroutineScope 取消另一条
     */
    private suspend fun performGeneration(
        settings: me.rerere.rikkahub.data.datastore.Settings,
        model: me.rerere.ai.provider.Model,
        assistant: me.rerere.rikkahub.core.data.model.Assistant,
        prompt: String
    ): String = coroutineScope {
        var result = ""
        // 首 chunk 闸门：收到第一个非空 assistant 内容后 complete
        val firstChunkGate = CompletableDeferred<Unit>()

        // Watchdog：只监控首 chunk 启动时间，收到后自动进入无限制模式
        val watchdog = launch {
            withTimeout(FIRST_CHUNK_TIMEOUT_MS) { firstChunkGate.await() }
        }

        try {
            generationHandler.generateText(
                settings = settings,
                model = model,
                messages = listOf(UIMessage.user(prompt)),
                assistant = assistant.copy(
                    temperature = 0.8f,
                    enableMemory = false,
                    enabledLorebookIds = emptySet(),
                    includeDiariesInContext = false,
                    localTools = emptyList()
                ),
                enabledModeIds = emptySet()
            ).collect { chunk ->
                if (chunk is GenerationChunk.Messages) {
                    val lastMessage = chunk.messages.lastOrNull()
                    if (lastMessage?.role == MessageRole.ASSISTANT) {
                        val text = lastMessage.toContentText()
                        result = text
                        if (text.isNotBlank()) {
                            // 解锁首 chunk 闸门 → watchdog 正常结束 → 进入无限制输出阶段
                            firstChunkGate.complete(Unit)
                        }
                    }
                }
            }
            // Flow 正常结束但 result 仍为空（模型返回空内容）→ 也把闸门打开让 watchdog 结束
            firstChunkGate.complete(Unit)
            // 等待 watchdog 确认完成，避免作用域提前结束
            watchdog.join()
        } finally {
            // 兜底：无论成功失败/异常，确保 watchdog 不会泄漏
            watchdog.cancel()
        }

        result
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
