package me.rerere.rikkahub.service

import me.rerere.rikkahub.core.data.model.Conversation
import kotlin.uuid.Uuid

/**
 * 删除 / 重生成路径的定向水合规则(纯函数,有单测)。
 *
 * 背景:内存会话默认只水合「助手全局最新 200 个节点」的消息内容
 * (`ConversationRepository.fetchFullConversations` 的 loadLimit),更早的历史
 * 只保留空占位节点。空占位节点中的消息无法参与内存变换——
 * `Conversation.deleteMessages` 与 regenerate 的节点查找都会跳过它们,
 * 导致「与最新消息距离超过水合深度的消息」删除 / 重生成静默失败
 * (transform 返回 null,UI 无任何反馈)。
 *
 * 修复方式:执行变换前检查目标消息是否落在空占位节点中,若是则按
 * targetMessageId 深度定向水合(复用搜索跳转的加载机制),再以合并后的
 * 会话作为 transform 基底。
 */
object ConversationHydrationRules {

    /**
     * 返回 [targetMessageIds] 中「不在任何已水合(非空)节点中」的消息 id。
     * 这些消息只可能位于空占位节点(未加载历史)或根本不存在于会话中。
     */
    fun findUnloadedTargetIds(conversation: Conversation, targetMessageIds: Set<Uuid>): Set<Uuid> {
        if (targetMessageIds.isEmpty()) return emptySet()
        val hydratedMessageIds = conversation.messageNodes
            .asSequence()
            .filter { it.messages.isNotEmpty() }
            .flatMap { it.messages.asSequence() }
            .map { it.id }
            .toSet()
        return targetMessageIds.filterNot { it in hydratedMessageIds }.toSet()
    }

    /**
     * 将 [hydrated](DB 定向加载结果)中的已水合节点按节点 id 合并进 [previous]。
     *
     * 只替换 previous 中的**空占位节点**——空节点是未加载历史的占位,不含任何
     * 内存新内容(流式 / 未落库消息),按节点 id 替换总是安全的。已水合节点
     * (可能持有流式期间的新内容)一律保持不动,防止 DB 快照覆盖内存新状态。
     *
     * hydrated 中存在但 previous 中没有的节点会被忽略:previous 的节点列表
     * 来自 DB 全量节点查询,理论上是全量占位;若真的缺失属于另一类数据异常,
     * 静默忽略不会丢内容(transform 会照常返回 null,与既有失败语义一致)。
     */
    fun mergePlaceholderNodes(previous: Conversation, hydrated: Conversation): Conversation {
        if (hydrated.messageNodes.isEmpty()) return previous
        val hydratedById = hydrated.messageNodes.associateBy { it.id }
        var changed = false
        val mergedNodes = previous.messageNodes.map { node ->
            if (node.messages.isNotEmpty()) return@map node
            val replacement = hydratedById[node.id]
            if (replacement == null || replacement.messages.isEmpty()) {
                node
            } else {
                changed = true
                replacement
            }
        }
        return if (changed) previous.copy(messageNodes = mergedNodes) else previous
    }
}
