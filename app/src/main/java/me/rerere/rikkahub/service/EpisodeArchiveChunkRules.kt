package me.rerere.rikkahub.service

import me.rerere.ai.ui.UIMessage

/**
 * 手动整合（L2 episode）的分批规则（纯函数，单测见 EpisodeArchiveChunkRulesTest）。
 *
 * 分批约束：
 * - 单批字符总量与消息条数双上限，任一超出即切批；
 * - 「同毫秒时间戳组」不可被批边界拆开（微信模式分句同毫秒落库多条）：
 *   批末条的 createdAt 会成为 episode.endTime，下次 `createdAt > endTime` 的
 *   严格大于查询会漏掉同毫秒后继消息（与 DiaryGenerationLogic.buildDiaryMessageGroups
 *   同一约束）；
 * - 单条超限消息独立成批、不截断内容（2026-11 按产品决策移除消息截断）。
 */
internal fun splitEpisodeArchiveChunks(
    messages: List<UIMessage>,
    maxChars: Int,
    maxMessages: Int
): List<List<UIMessage>> {
    val groups = mutableListOf<List<UIMessage>>()
    var currentGroup = mutableListOf<UIMessage>()
    var currentChars = 0

    for (msg in messages) {
        val textChars = msg.toContentText().length
        val msgTs = msg.createdAtMillis()
        // 与当前批末条同毫秒时禁止切批（该消息落入新批首位会导致同 ts 跨批）
        val sameTimestampAsGroupLast = currentGroup.isNotEmpty() &&
            currentGroup.last().createdAtMillis() == msgTs
        val overflow = currentChars + textChars > maxChars ||
            currentGroup.size + 1 > maxMessages

        if (overflow && currentGroup.isNotEmpty() && !sameTimestampAsGroupLast) {
            groups.add(currentGroup)
            currentGroup = mutableListOf()
            currentChars = 0
        }
        currentGroup.add(msg)
        currentChars += textChars
    }
    if (currentGroup.isNotEmpty()) groups.add(currentGroup)
    return groups
}
