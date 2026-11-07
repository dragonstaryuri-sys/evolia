package me.rerere.ai.core

import kotlinx.serialization.Serializable

@Serializable
data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cachedTokens: Int = 0,
    val totalTokens: Int = 0,
)

fun TokenUsage?.merge(other: TokenUsage): TokenUsage {
    val promptTokens = if (other.promptTokens > 0) {
        other.promptTokens
    } else {
        this?.promptTokens ?: 0
    }
    val completionTokens = if (other.completionTokens > 0) {
        other.completionTokens
    } else {
        this?.completionTokens ?: 0
    }
    val totalTokens = promptTokens + completionTokens
    val cachedTokens = if (other.cachedTokens > 0) {
        other.cachedTokens
    } else {
        this?.cachedTokens ?: 0
    }
    return TokenUsage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
        cachedTokens = cachedTokens
    )
}

/**
 * 跨多次模型请求的 [TokenUsage] 求和，用于多步 Agent 任务（工具调用循环）的累计记账。
 *
 * 注意：跨步骤累计禁止使用 [merge]——merge 是「非零覆盖」语义，适配的是同一请求内
 * 流式 chunk 的 usage 合并；跨步骤累计若用 merge，cached=0（缓存未命中）的步骤会被
 * 前一步的旧值污染。
 */
fun Iterable<TokenUsage>.sumUsage(): TokenUsage {
    var prompt = 0
    var completion = 0
    var cached = 0
    for (usage in this) {
        prompt += usage.promptTokens
        completion += usage.completionTokens
        cached += usage.cachedTokens
    }
    return TokenUsage(
        promptTokens = prompt,
        completionTokens = completion,
        cachedTokens = cached,
        totalTokens = prompt + completion
    )
}
