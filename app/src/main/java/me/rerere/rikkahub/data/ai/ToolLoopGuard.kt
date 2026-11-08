package me.rerere.rikkahub.data.ai

/**
 * 工具调用循环保护（纯决策逻辑，不依赖 Android API，可单元测试）。
 *
 * 设计目标（AGENTS.md 工具调用治理）：不是阻止模型正常使用工具，而是在工具调用链路
 * 异常时及时停止无意义的重复调用，避免单次用户请求内无限循环消耗 Token：
 *
 * - **轮次上限**：单次用户请求内允许「执行工具调用的轮次」不超过 [maxToolRounds]
 *   （一轮 = 一次包含工具调用的模型响应）。达到上限后不再执行工具，改为向模型返回
 *   引导性错误结果，让模型基于已有信息直接作答，循环随之自然结束。
 * - **相同调用指纹连续重复检测**：指纹 = 工具名 + 规范化参数字符串。连续（上一轮与
 *   本轮都出现）且完全相同的指纹连续执行超过 [maxIdenticalRepeats] 次后拦截。
 *   不同参数的调用（如换关键词的再次搜索）不受影响，属于合理行为。
 *
 * 拦截不抛异常：由调用方将 [CallDecision.Block.reason] 作为 ToolResult 返回给模型，
 * 保证 Assistant 工具调用与 Tool 消息始终配对（协议完整性优先）。
 */
class ToolLoopGuard(
    private val maxToolRounds: Int = DEFAULT_MAX_TOOL_ROUNDS,
    private val maxIdenticalRepeats: Int = DEFAULT_MAX_IDENTICAL_REPEATS,
) {
    /** 一次待决策的工具调用指纹 */
    data class ToolCallFingerprint(val toolName: String, val normalizedArguments: String) {
        val key: String get() = "$toolName#$normalizedArguments"
    }

    sealed interface CallDecision {
        data object Execute : CallDecision

        /** 拦截原因，将作为错误 ToolResult 内容返回给模型 */
        data class Block(val reason: String) : CallDecision
    }

    private var executedRounds = 0
    private var previousRoundKeys: Set<String> = emptySet()
    private val consecutiveRepeatCounts = mutableMapOf<String, Int>()

    /**
     * 对本轮（一次模型响应中）的全部工具调用做执行/拦截决策。
     * 必须按轮次顺序调用；返回顺序与 [calls] 一一对应。
     */
    fun evaluateRound(calls: List<ToolCallFingerprint>): List<CallDecision> {
        if (calls.isEmpty()) return emptyList()
        val roundLimitReached = executedRounds >= maxToolRounds
        // 快照上一轮状态：同一轮内的重复调用必须基于「上一轮」的计数判定，
        // 不能读到本轮前面调用刚写入的计数（否则轮内重复会相互累积，第 2 轮就被误拦）
        val previousKeys = previousRoundKeys
        val previousCountsSnapshot = consecutiveRepeatCounts.toMap()
        val currentRoundCounts = mutableMapOf<String, Int>()
        val currentRoundKeys = mutableSetOf<String>()
        val decisions = calls.map { call ->
            when {
                roundLimitReached -> CallDecision.Block(
                    "工具调用轮次已达上限（$maxToolRounds 轮）。请立即停止调用工具，" +
                        "直接基于已获得的工具结果回答用户。"
                )

                else -> {
                    val consecutiveCount = if (call.key in previousKeys) {
                        (previousCountsSnapshot[call.key] ?: 1) + 1
                    } else {
                        1
                    }
                    currentRoundCounts[call.key] = consecutiveCount
                    currentRoundKeys.add(call.key)
                    if (consecutiveCount > maxIdenticalRepeats) {
                        CallDecision.Block(
                            "已连续 $consecutiveCount 次以完全相同的参数调用工具「${call.toolName}」，" +
                                "疑似调用结果未被正确消费或陷入循环。请勿再次以相同参数调用，" +
                                "直接基于已有工具结果回答用户；如确需继续检索，请调整参数。"
                        )
                    } else {
                        CallDecision.Execute
                    }
                }
            }
        }
        // 轮次上限拦截的轮次不产生实际执行，不更新指纹状态（不计入连续重复序列）
        if (!roundLimitReached) {
            previousRoundKeys = currentRoundKeys
            consecutiveRepeatCounts.clear()
            consecutiveRepeatCounts.putAll(currentRoundCounts)
            executedRounds++
        }
        return decisions
    }

    companion object {
        /**
         * 单次用户请求允许的最大工具调用轮次。
         * 正常多步任务（搜索→读网页→再搜索→回答）约 2~6 轮，16 轮为充足余量；
         * 上限远低于 maxSteps（模型请求次数）上限，仅约束「带工具调用的轮次」。
         */
        const val DEFAULT_MAX_TOOL_ROUNDS = 16

        /** 相同工具 + 相同参数连续允许执行的次数上限（第 N+1 次起拦截） */
        const val DEFAULT_MAX_IDENTICAL_REPEATS = 2
    }
}
