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
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.service.notification.NotificationSoundManager
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.core.data.ai.prompts.DEFAULT_DIARY_PROMPT
import me.rerere.rikkahub.core.data.ai.prompts.DIARY_NO_INTERACTION_PROMPT
import me.rerere.rikkahub.core.data.ai.prompts.DIARY_TIME_REFERENCE_PROMPT
import me.rerere.rikkahub.core.data.ai.prompts.applyPlaceholders
import me.rerere.rikkahub.core.data.db.entity.AgentDiaryEntity
import me.rerere.rikkahub.core.data.db.entity.DiaryGenerationStatus
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

/** 第一个有效 assistant 消息（首 token）的等待超时：仅限制"模型启动响应" */
private const val FIRST_CHUNK_TIMEOUT_MS = 360_000L

/** 单个 chunk 的总生成时长上限：15 分钟。
 *  首 token ≤360s + 后续流式输出，整体不超过 15min。
 *  防止模型流式输出卡住（网络 half-open / 模型重复输出 token）导致任务永久挂起。 */
private const val TOTAL_CHUNK_TIMEOUT_MS = 15 * 60 * 1000L

private const val DIARY_MARKDOWN_INSTRUCTION = """

---
**日记格式(Markdown):**
你可以使用markdown格式编写你的日记~
"""

/** 手动生成任务的唯一任务名前缀（DiaryVM 同步使用） */
const val DIARY_MANUAL_WORK_NAME_PREFIX = "diary_gen_"

/** 自动调度任务的唯一任务名前缀（DiarySchedulerWorker 同步使用） */
const val DIARY_AUTO_WORK_NAME_PREFIX = "auto_diary_"

/**
 * 查询某助手+目标日期是否存在活跃（ENQUEUED/RUNNING）的日记生成任务。
 *
 * 覆盖手动（diary_gen_）与自动（auto_diary_）两个唯一任务名，
 * 是 GENERATING 状态防永久卡死与并发双任务的唯一可信判据：
 * 数据库状态只做记录，"任务是否还活着"以 WorkManager 为准。
 *
 * @param excludeWorkId 排除的 WorkInfo id（DiaryWorker 检查时排除自身）
 */
suspend fun hasActiveDiaryGenerationTask(
    workManager: WorkManager,
    assistantId: String,
    targetDate: String,
    excludeWorkId: java.util.UUID? = null
): Boolean {
    val uniqueNames = listOf(
        "$DIARY_MANUAL_WORK_NAME_PREFIX$assistantId",
        "$DIARY_AUTO_WORK_NAME_PREFIX${assistantId}_$targetDate"
    )
    return uniqueNames.any { name ->
        val infos = withContext(Dispatchers.IO) {
            runCatching { workManager.getWorkInfosForUniqueWork(name).get() }
                .getOrDefault(emptyList<WorkInfo>())
        }
        infos.any { info ->
            info.id != excludeWorkId &&
                (info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.RUNNING)
        }
    }
}

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
        // generatedContent：当前日记全文草稿（续写场景初始化为已有正文，chunk 输出为全文重写）
        // hadAnyChunkSuccess：DB 中已存在成功保存的正文（含 PARTIAL 续写时继承自历史 chunk）
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

            // 手动生成始终以今天为目标日期（产品规则：不支持补生成历史日期）
            val targetDateStr = inputData.getString("targetDate")
                ?: LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            val targetDate = runCatching { LocalDate.parse(targetDateStr, DateTimeFormatter.ISO_LOCAL_DATE) }
                .getOrDefault(LocalDate.now())

            // ——————————————————————————————————————————————
            // 入口决策（见 resolveDiaryEntryAction）：
            //   CreateNew        → 新建 skeleton（status=GENERATING）
            //   Resume           → 复用原记录续写 / 重建（PARTIAL 续写、空壳重建、崩溃残留接管）
            //   SkipAlreadyExists→ 已有有效日记（正文或用户图片），绝不删除/覆盖
            //   SkipTaskRunning  → GENERATING 且其他活跃任务在跑，避免双任务并发写同一日记
            // ——————————————————————————————————————————————
            val existingDiary = diaryRepo.getDiaryByDate(assistant.id.toString(), targetDateStr)
            val otherTaskActive = hasActiveDiaryGenerationTask(
                workManager = WorkManager.getInstance(applicationContext),
                assistantId = assistant.id.toString(),
                targetDate = targetDateStr,
                excludeWorkId = id
            )
            var skeletonCreatedAt = System.currentTimeMillis()
            val resumeDiary: AgentDiaryEntity? = when (val action = resolveDiaryEntryAction(existingDiary, otherTaskActive)) {
                DiaryEntryAction.SkipAlreadyExists -> {
                    return if (isManual) {
                        Result.success(workDataOf("skipped" to true, "reason" to "already_exists"))
                    } else {
                        Result.success()
                    }
                }
                DiaryEntryAction.SkipTaskRunning -> {
                    Log.i(TAG, "Diary generation skipped: another active task is running for ${assistant.name} $targetDateStr")
                    return if (isManual) {
                        Result.success(workDataOf("skipped" to true, "reason" to "task_running"))
                    } else {
                        Result.success()
                    }
                }
                DiaryEntryAction.CreateNew -> {
                    val newId = Uuid.random().toString()
                    skeletonCreatedAt = System.currentTimeMillis()
                    diaryRepo.insertDiary(
                        AgentDiaryEntity(
                            id = newId,
                            assistantId = assistant.id.toString(),
                            content = "",
                            date = targetDateStr,
                            createdAt = skeletonCreatedAt,
                            generationStatus = DiaryGenerationStatus.GENERATING
                        )
                    )
                    inProgressDiaryId = newId
                    null
                }
                is DiaryEntryAction.Resume -> {
                    // 复用原记录（保留 id/createdAt 语义），置回 GENERATING 继续或重试
                    inProgressDiaryId = action.diary.id
                    diaryRepo.updateDiaryStatus(action.diary.id, DiaryGenerationStatus.GENERATING)
                    generatedContent = action.diary.content
                    hadAnyChunkSuccess = action.diary.content.isNotBlank()
                    action.diary
                }
            }
            // 入口决策后记录必然已创建/复用，try 内统一使用非空 diaryId
            val diaryId = requireNotNull(inProgressDiaryId) { "diary id not initialized" }

            // —— 消息起点锚点（见 computeStartTimeThreshold）：
            //    续写优先用原日记自身 lastMessageAt；否则回退最近一篇有正文日记；
            //    均无时回退 targetDate 当日 0 点（首次生成沿用现有规则）
            val allSortedDiaries = diaryRepo
                .getDiariesByAssistant(assistant.id.toString())
                .first()
                .filter { it.content.isNotBlank() && it.id != diaryId }
                .sortedByDescending { it.date }
            val startTimeThreshold = computeStartTimeThreshold(
                resumeDiary = resumeDiary,
                otherDiariesSortedByDateDesc = allSortedDiaries,
                fallbackStartOfDayMillis = targetDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            )

            // —— Stage 1（SQL 层粗筛）：只取 update_at >= startTimeThreshold 的会话
            //    避免把该智能体历史所有会话全量加载进内存
            val conversations = conversationRepo.getConversationsOfAssistantAnyModeAfter(
                assistant.id,
                startTimeThreshold,
                loadAllMessages = true
            ).first()
            // —— Stage 2（内存层精筛）：在剩余会话里按消息 createdAt 过滤
            //    处理"会话跨越起点"的情况（旧会话在起点之后又收到新消息）
            val allMessages = conversations.flatMap { conv ->
                conv.messageNodes.mapNotNull { node ->
                    node.messages.getOrNull(node.selectIndex)?.takeIf {
                        it.createdAtMillis() > startTimeThreshold
                    }
                }
            }.sortedBy { it.createdAtMillis() }

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
                // —— 无交互 / 无新消息分支 ——
                if (hadAnyChunkSuccess) {
                    // 续写场景：正文已存在且游标之后没有新消息
                    // （含"最后一个 chunk 成功后进程被杀，状态停留 GENERATING"的恢复）
                    // → 全部消息已处理完毕，落定为 COMPLETED，不再重复生成
                    diaryRepo.updateDiaryStatus(diaryId, DiaryGenerationStatus.COMPLETED)
                    return Result.success(workDataOf("skipped" to true, "reason" to "no_new_messages"))
                }

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
                if (generatedContent.isNotBlank()) {
                    // 单块分支：成功立刻写盘。
                    // 无交互场景没有消息可取，用日记记录的 createdAt 推进游标
                    // （复用记录时保持原 createdAt，符合"无交互日记游标 = 日记创建时间"约定）。
                    // 该时点之前锚点之后无任何消息存在，未来消息必然 > createdAt，严格大于查询不漏。
                    val noInteractionCursor = resumeDiary?.createdAt ?: skeletonCreatedAt
                    diaryRepo.updateDiaryGenerationProgress(diaryId, generatedContent, noInteractionCursor)
                    diaryRepo.updateDiaryStatus(diaryId, DiaryGenerationStatus.COMPLETED)
                    hadAnyChunkSuccess = true
                } else {
                    // 无交互生成返回空 → 清掉空壳避免占位
                    runCatching { diaryRepo.deleteDiaryById(diaryId) }
                    return Result.success(workDataOf("skipped" to true, "reason" to "no_content"))
                }
            } else {
                val messageGroups = buildDiaryMessageGroups(newMessages, CHUNK_SIZE)

                // 续写时首个 chunk 也走"草稿续写"prompt（generatedContent 已初始化为原正文）
                val isResumingDraft = !generatedContent.isBlank()

                messageGroups.forEachIndexed { index, group ->
                    val isFirst = index == 0 && !isResumingDraft
                    val chatContent = group.joinToString("\n") { message ->
                        val time = formatTimestamp(message.createdAtMillis())
                        "[$time] ${message.role}: ${message.toContentText()}"
                    }

                    val firstMsgTime = formatTimestamp(group.first().createdAtMillis())
                    val lastMsgTime = formatTimestamp(group.last().createdAtMillis())

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

                    if (chunkResult.isBlank()) {
                        // —— ★ Chunk 失败（含模型返回空内容）：立即停止后续 chunk（4.3）——
                        //    游标不推进，已保存内容保留，状态由下方收尾逻辑落定
                        Log.w(TAG, "Diary chunk $index returned blank, stop remaining chunks; preservedContent=${generatedContent.isNotBlank()}")
                        return finishWithChunkFailure(isManual, assistantIdStr, diaryId, hadAnyChunkSuccess, "AI 返回了空内容")
                    }

                    // —— ★ 增量落库：每个 chunk 生成成功立刻写 DB，下次断点能续上 ——
                    //    游标只推进到【本 chunk 最后一条消息】的时间戳（而非整批消息的最后一条），
                    //    后续 chunk 失败时未处理消息仍会被下一轮查询命中
                    generatedContent = chunkResult
                    val chunkCursor = chunkCursorMillis(group)
                        ?: error("impossible: chunk group is never empty")
                    diaryRepo.updateDiaryGenerationProgress(diaryId, generatedContent, chunkCursor)
                    hadAnyChunkSuccess = true
                }

                // 全部 chunk 成功 → COMPLETED
                diaryRepo.updateDiaryStatus(diaryId, DiaryGenerationStatus.COMPLETED)
            }

            if (!isManual) {
                showSuccessNotification(assistant.name, assistant.id.toString())
            }
            Result.success(workDataOf("partial" to false))
        } catch (ce: CancellationException) {
            // ——————————————————————————————————————————————
            // CancellationException 原样重抛（含首 token 360s / 单 chunk 15min watchdog 超时）
            //   - WorkManager/协程取消属于正常行为，不弹"失败"通知
            //   - 已有内容 → PARTIAL（下次可续写）；无内容 → 删空壳
            //   - 协程已取消，挂起调用必须包 NonCancellable，否则清理语句本身会被取消而失效
            // ——————————————————————————————————————————————
            Log.i(TAG, "Diary job cancelled, hadAnyChunkSuccess=$hadAnyChunkSuccess, preservedDiaryId=$inProgressDiaryId")
            withContext(NonCancellable) {
                if (hadAnyChunkSuccess && inProgressDiaryId != null) {
                    runCatching { diaryRepo.updateDiaryStatus(inProgressDiaryId, DiaryGenerationStatus.PARTIAL) }
                } else {
                    inProgressDiaryId?.let { runCatching { diaryRepo.deleteDiaryById(it) } }
                }
            }
            throw ce
        } catch (e: Exception) {
            Log.e(TAG, "Diary generation failed, hadAnyChunkSuccess=$hadAnyChunkSuccess", e)
            val errorInfo = e.localizedMessage ?: e.toString()
            return withContext(NonCancellable) {
                when {
                    // —— 部分成功：保留已生成的内容，落定 PARTIAL，提示用户，不记失败不重试 ——
                    hadAnyChunkSuccess && generatedContent.isNotBlank() -> {
                        inProgressDiaryId?.let { id ->
                            runCatching { diaryRepo.updateDiaryStatus(id, DiaryGenerationStatus.PARTIAL) }
                        }
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
                    // —— 完全失败且手动触发：删空壳，直接通知失败，不重试 ——
                    isManual -> {
                        inProgressDiaryId?.let { runCatching { diaryRepo.deleteDiaryById(it) } }
                        val errorMsg = applicationContext.getString(R.string.diary_generate_failed, errorInfo)
                        showSimpleNotification(errorMsg, assistantIdStr)
                        Result.failure(workDataOf("error" to errorInfo))
                    }
                    // —— 完全失败且自动触发：删空壳，按现有策略最多重试 3 次 ——
                    else -> {
                        inProgressDiaryId?.let { runCatching { diaryRepo.deleteDiaryById(it) } }
                        if (runAttemptCount < 3) Result.retry() else Result.failure()
                    }
                }
            }
        }
    }

    /**
     * Chunk 失败（含空白返回）的统一收尾：
     * - 已有任何成功正文（含 PARTIAL 续写继承的历史正文）→ 状态落定 PARTIAL，可同天/跨天恢复
     * - 首个 chunk 即失败且无任何正文 → 删空壳（等价 FAILED：记录不存在即可重试）
     *   手动触发通知失败；自动触发按现有策略 retry（≤3 次）
     *
     * 游标不推进、不向正文写入错误信息（2.2/4.3）。
     */
    private suspend fun finishWithChunkFailure(
        isManual: Boolean,
        assistantIdStr: String?,
        diaryId: String,
        hasPreservedContent: Boolean,
        errorInfo: String
    ): Result = if (hasPreservedContent) {
        Log.i(TAG, "Diary chunk failed with preserved content, diary stays PARTIAL, diaryId=$diaryId")
        runCatching { diaryRepo.updateDiaryStatus(diaryId, DiaryGenerationStatus.PARTIAL) }
        Result.success(
            workDataOf(
                "partial" to true,
                "reason" to "partial_on_blank_chunk",
                "error" to errorInfo
            )
        )
    } else {
        runCatching { diaryRepo.deleteDiaryById(diaryId) }
        if (isManual) {
            val errorMsg = applicationContext.getString(R.string.diary_generate_failed, errorInfo)
            showSimpleNotification(errorMsg, assistantIdStr)
            Result.failure(workDataOf("error" to errorInfo))
        } else {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    /**
     * 执行单轮 AI 文本生成。
     *
     * 超时策略（两级保护）：
     *   1. 首 token 超时（FIRST_CHUNK_TIMEOUT_MS=360s）：首 chunk 必须在 360s 内返回，
     *      否则视为模型不响应 → watchdog 抛 TimeoutCancellationException
     *   2. 总时长兜底（TOTAL_CHUNK_TIMEOUT_MS=15min）：单个 chunk 整体不超过 15 分钟，
     *      防止首 token 之后的流式输出卡住（网络 half-open / 模型重复输出）导致任务永久挂起
     *
     * 实现：watchdog + CompletableDeferred 监控首 token；外层 withTimeout 监控总时长。
     * 任一超时抛 TimeoutCancellationException（CancellationException 子类），
     * 由 doWork 外层 catch(ce: CancellationException) 块统一处理（原样重抛，已有内容不回滚）。
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

        // Watchdog：只监控首 chunk 启动时间，收到后正常结束
        val watchdog = launch {
            withTimeout(FIRST_CHUNK_TIMEOUT_MS) { firstChunkGate.await() }
        }

        try {
            // ★ 总时长兜底：单个 chunk 整体不超过 15 分钟（含首 token + 后续输出）
            withTimeout(TOTAL_CHUNK_TIMEOUT_MS) {
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
                                // 解锁首 chunk 闸门 → watchdog 正常结束 → 进入总时长限制的输出阶段
                                firstChunkGate.complete(Unit)
                            }
                        }
                    }
                }
                // Flow 正常结束但 result 仍为空（模型返回空内容）→ 也把闸门打开让 watchdog 结束
                firstChunkGate.complete(Unit)
                // 等待 watchdog 确认完成，避免作用域提前结束
                watchdog.join()
            }
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

        if (!settingsStore.settingsFlow.value.notificationDiaryEnabled) return
        val notification = NotificationCompat.Builder(applicationContext, NotificationSoundManager.DIARY_CHANNEL_ID)
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
