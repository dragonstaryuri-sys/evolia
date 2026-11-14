package me.rerere.rikkahub.data.ai

/**
 * search_web 空结果重试守卫（纯决策逻辑，不依赖 Android API，可单元测试）。
 *
 * 解决「AI 一直搜一直搜」的空转问题：
 * - 搜索无结果时，向模型返回引导信息（无结果 + 换关键词重搜），并累计连续空结果次数；
 * - 连续无结果达到 [maxConsecutiveEmptyResults] 后，拒绝再实际执行网络搜索
 *   （shouldBlockSearch），返回「终止搜索、直接告知用户」的强制性错误结果；
 * - 一旦某次搜索返回有效结果，连续计数归零（重新开始计数）。
 */
class SearchRetryGuard(
    private val maxConsecutiveEmptyResults: Int = DEFAULT_MAX_CONSECUTIVE_EMPTY_RESULTS,
) {
    var consecutiveEmptyResults: Int = 0
        private set

    /** 连续空结果是否已达上限（达上限后本次调用不执行网络请求，直接返回终止提示） */
    fun shouldBlockSearch(): Boolean = consecutiveEmptyResults >= maxConsecutiveEmptyResults

    /** 搜索返回空结果：累计计数，返回给模型的引导/终止提示文本 */
    fun noteEmptyResult(): String {
        consecutiveEmptyResults++
        return if (consecutiveEmptyResults >= maxConsecutiveEmptyResults) {
            blockedMessage()
        } else {
            retryMessage()
        }
    }

    /** 搜索返回有效结果：重置连续空结果计数 */
    fun noteResultsFound() {
        consecutiveEmptyResults = 0
    }

    /** 未达上限时的空结果提示：告知无结果并引导换关键词重试 */
    fun retryMessage(): String =
        "本次搜索没有返回任何结果。请更换关键词后重新搜索试试" +
            "（已连续无结果 $consecutiveEmptyResults 次，连续 $maxConsecutiveEmptyResults 次后将不再执行搜索）。"

    /** 达到上限时的终止提示：要求停止调用搜索工具，直接告知用户 */
    fun blockedMessage(): String =
        "已连续 $consecutiveEmptyResults 次搜索均无结果，请终止搜索，不要再调用搜索工具，" +
            "直接告诉用户搜不到相关信息，并基于已有信息或自身知识回答用户。"

    companion object {
        /** 连续空结果允许的最大次数，超过后不再执行实际搜索 */
        const val DEFAULT_MAX_CONSECUTIVE_EMPTY_RESULTS = 5
    }
}
