package me.rerere.ai.core

import org.junit.Assert.assertEquals
import org.junit.Test

class UsageTest {

    @Test
    fun `sumUsage sums empty list to zero usage`() {
        val result = emptyList<TokenUsage>().sumUsage()
        assertEquals(TokenUsage(), result)
    }

    @Test
    fun `sumUsage sums all fields across steps`() {
        val result = listOf(
            TokenUsage(promptTokens = 1000, completionTokens = 200, cachedTokens = 800),
            TokenUsage(promptTokens = 1200, completionTokens = 150, cachedTokens = 1000),
            TokenUsage(promptTokens = 900, completionTokens = 300, cachedTokens = 0),
        ).sumUsage()
        assertEquals(3100, result.promptTokens)
        assertEquals(650, result.completionTokens)
        assertEquals(1800, result.cachedTokens)
        assertEquals(3750, result.totalTokens)
    }

    @Test
    fun `sumUsage keeps zero cached step instead of inheriting previous value`() {
        // 回归：跨步骤累计禁止用 merge 的「非零覆盖」语义——
        // 第 3 步缓存未命中（cached=0）必须如实累计为 0 的贡献，而不是继承第 2 步的 1000
        val mergedWrongly = listOf(
            TokenUsage(promptTokens = 100, cachedTokens = 1000),
            TokenUsage(promptTokens = 100, cachedTokens = 0),
        ).map { it.cachedTokens }.sum()
        assertEquals(1000, mergedWrongly)

        val result = listOf(
            TokenUsage(promptTokens = 100, cachedTokens = 1000),
            TokenUsage(promptTokens = 100, cachedTokens = 0),
        ).sumUsage()
        assertEquals(1000, result.cachedTokens)
        assertEquals(200, result.promptTokens)
    }

    @Test
    fun `merge keeps last non-zero value for stream chunks`() {
        // merge 语义保持不变：同一请求内流式 chunk 合并，后者非零覆盖
        val usage: TokenUsage? = null
        val result = usage.merge(TokenUsage(promptTokens = 100, completionTokens = 50, cachedTokens = 80))
        assertEquals(
            TokenUsage(promptTokens = 100, completionTokens = 50, cachedTokens = 80, totalTokens = 150),
            result
        )

        val first = usage.merge(TokenUsage(promptTokens = 100, cachedTokens = 80))
        val second = first.merge(TokenUsage(promptTokens = 120, cachedTokens = 0))
        // cached=0 不覆盖旧值（merge 既有语义，仅限单请求流式场景使用）
        assertEquals(80, second.cachedTokens)
        assertEquals(120, second.promptTokens)
    }
}
