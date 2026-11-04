package me.rerere.rikkahub.service

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.core.data.db.entity.AgentDiaryEntity
import me.rerere.rikkahub.core.data.db.entity.DiaryGenerationStatus

/**
 * 日记生成失败恢复的纯决策逻辑（无 Android API，可 JVM 单测）。
 *
 * 核心约定（详见 AGENTS.md 日记模块章节）：
 * 1. lastMessageAt 只代表「已成功持久化的最后一条消息」，chunk 成功才推进；
 * 2. 同一毫秒时间戳的消息组不可被 chunk 边界拆开，保证 `createdAt > cursor`
 *    的严格大于查询不漏消息；
 * 3. 用户内容（含图片日记）永不被 AI 生成流程删除或覆盖。
 */

/** 消息时间戳统一换算（epoch ms），与游标存储精度一致。 */
fun UIMessage.createdAtMillis(): Long =
    createdAt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()

/** 日记生成入口决策动作。 */
sealed interface DiaryEntryAction {
    /** 无当日记录 → 新建 skeleton 并生成。 */
    data object CreateNew : DiaryEntryAction

    /** 当日已有有效日记（正文或用户图片）→ 跳过，不重复生成（产品规则：每日一篇）。 */
    data object SkipAlreadyExists : DiaryEntryAction

    /** 状态为 GENERATING 且存在其他活跃生成任务 → 跳过，避免双任务并发写同一日记。 */
    data object SkipTaskRunning : DiaryEntryAction

    /** 复用当日已有记录续写 / 重试（PARTIAL 续写、空壳重建、崩溃残留接管）。 */
    data class Resume(val diary: AgentDiaryEntity) : DiaryEntryAction
}

/**
 * 决定本次 DiaryWorker 对目标日期日记的处理方式。
 *
 * 判定优先级：
 * 1. 无记录 → CreateNew；
 * 2. images 非空 → 一律视为用户手动内容（扫描日记正文可为空），跳过且绝不删除；
 * 3. COMPLETED 且正文非空 → 已完成，跳过（同天不重复追加，6.1）；
 * 4. GENERATING 且其他活跃任务存在 → 跳过（并发保护，9）；
 * 5. 其余（PARTIAL / 空壳 COMPLETED 遗留 / 崩溃残留 GENERATING / 未知状态）
 *    → Resume 复用原记录，保留 id 与 createdAt 语义，不删除重建。
 */
fun resolveDiaryEntryAction(
    existingDiary: AgentDiaryEntity?,
    isOtherGenerationTaskActive: Boolean
): DiaryEntryAction {
    if (existingDiary == null) return DiaryEntryAction.CreateNew

    val hasUserImages = existingDiary.images.isNotEmpty()
    if (hasUserImages) return DiaryEntryAction.SkipAlreadyExists

    val isCompletedWithContent = existingDiary.generationStatus == DiaryGenerationStatus.COMPLETED &&
        existingDiary.content.isNotBlank()
    if (isCompletedWithContent) return DiaryEntryAction.SkipAlreadyExists

    val isGeneratingWithActiveTask = existingDiary.generationStatus == DiaryGenerationStatus.GENERATING &&
        isOtherGenerationTaskActive
    if (isGeneratingWithActiveTask) return DiaryEntryAction.SkipTaskRunning

    return DiaryEntryAction.Resume(existingDiary)
}

/**
 * 自动调度（DiarySchedulerWorker）判定：当天是否需要补跑生成任务。
 *
 * 与 [resolveDiaryEntryAction] 的差异：不接管 GENERATING（调度侧只负责发现，
 * 接管与否由 DiaryWorker 启动时结合活跃任务判定），仅决定是否入队。
 *
 * | 当天 AI 日记状态           | 结果 |
 * |---------------------------|------|
 * | 不存在                    | true |
 * | 用户图片日记              | false |
 * | COMPLETED 且正文非空      | false |
 * | PARTIAL                   | true（恢复剩余消息） |
 * | 空壳（COMPLETED/无正文）  | true（复用重试，不被空记录阻塞） |
 * | GENERATING 且任务已死     | true（重新调度） |
 * | GENERATING 且任务活跃     | false |
 */
fun shouldAutoTriggerDiary(
    todayDiary: AgentDiaryEntity?,
    isGenerationTaskActive: Boolean
): Boolean {
    if (todayDiary == null) return true
    if (todayDiary.images.isNotEmpty()) return false

    val isCompletedWithContent = todayDiary.generationStatus == DiaryGenerationStatus.COMPLETED &&
        todayDiary.content.isNotBlank()
    if (isCompletedWithContent) return false

    val isGeneratingWithActiveTask = todayDiary.generationStatus == DiaryGenerationStatus.GENERATING &&
        isGenerationTaskActive
    if (isGeneratingWithActiveTask) return false

    return true
}

/**
 * 计算本次生成的消息起点锚点（消息 createdAt 严格大于该值才纳入）。
 *
 * 优先级：
 * 1. 续写场景且原日记有正文 → 原日记自身的 lastMessageAt（上次实际处理到的位置）；
 * 2. 原日记游标为 null（历史兼容 / 空壳重建）→ 最近一篇有正文日记的 lastMessageAt
 *    （保持既有兼容链，不回溯更早日记，历史日记缺游标时的行为与旧逻辑一致）；
 * 3. 均无 → fallbackThreshold（目标日期 0 点，首次生成沿用现有规则）。
 *
 * @param resumeDiary 复用续写的当日日记（CreateNew / 空壳重建时传 null 或空正文记录）
 * @param otherDiariesSortedByDateDesc 该智能体其余有正文日记，按 date 降序（须排除目标日记自身）
 * @param fallbackStartOfDayMillis 目标日期 0 点的 epoch ms
 */
fun computeStartTimeThreshold(
    resumeDiary: AgentDiaryEntity?,
    otherDiariesSortedByDateDesc: List<AgentDiaryEntity>,
    fallbackStartOfDayMillis: Long
): Long {
    val anchorDiary = resumeDiary?.takeIf { it.content.isNotBlank() }
        ?: otherDiariesSortedByDateDesc.firstOrNull()
    return anchorDiary?.lastMessageAt ?: fallbackStartOfDayMillis
}

/**
 * 将消息按体积上限分 chunk，并保证「同毫秒时间戳组」不被 chunk 边界拆开。
 *
 * 严格大于游标查询（`createdAt > lastMessageAt`）的正确性依赖此约束：
 * 若同 ts 消息被拆到相邻两个 chunk，前一个 chunk 成功后游标 = ts，
 * 下一轮查询会漏掉同 ts 的后继消息（微信模式分句存储同毫秒落库多条，风险真实存在）。
 * 允许单 chunk 略微超出 chunkSize 以保持同 ts 组完整。
 */
fun buildDiaryMessageGroups(messages: List<UIMessage>, chunkSize: Int): List<List<UIMessage>> {
    val groups = mutableListOf<List<UIMessage>>()
    var currentGroup = mutableListOf<UIMessage>()
    var currentLen = 0

    for (msg in messages) {
        val text = msg.toContentText()
        val msgTs = msg.createdAtMillis()
        // 与当前组最后一条同毫秒时禁止切组（该消息落在新组首位会导致同 ts 跨组）
        val sameTimestampAsGroupLast = currentGroup.isNotEmpty() &&
            currentGroup.last().createdAtMillis() == msgTs
        val overflow = currentLen + text.length > chunkSize

        if (overflow && currentGroup.isNotEmpty() && !sameTimestampAsGroupLast) {
            groups.add(currentGroup)
            currentGroup = mutableListOf()
            currentLen = 0
        }
        currentGroup.add(msg)
        currentLen += text.length
    }
    if (currentGroup.isNotEmpty()) groups.add(currentGroup)
    return groups
}

/**
 * Chunk 成功持久化后应推进到的游标值：该 chunk 最后一条消息的 epoch ms。
 * 返回 null 仅当 chunk 为空（调用方不应在空 chunk 上推进游标）。
 */
fun chunkCursorMillis(group: List<UIMessage>): Long? =
    group.lastOrNull()?.createdAtMillis()
