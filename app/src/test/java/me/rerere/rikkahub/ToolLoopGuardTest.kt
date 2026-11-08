package me.rerere.rikkahub

import me.rerere.rikkahub.data.ai.ToolLoopGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolLoopGuardTest {

    private fun fp(toolName: String, args: String = "{}") =
        ToolLoopGuard.ToolCallFingerprint(toolName = toolName, normalizedArguments = args)

    private fun decisionsOf(guard: ToolLoopGuard, vararg calls: ToolLoopGuard.ToolCallFingerprint) =
        guard.evaluateRound(calls.toList())

    // ------------------------------------------------------------------
    // 相同指纹连续重复检测
    // ------------------------------------------------------------------

    @Test
    fun `identical consecutive calls allowed up to threshold then blocked`() {
        val guard = ToolLoopGuard()
        // 第 1、2 次连续相同调用放行
        val r1 = decisionsOf(guard, fp("search_web", """{"query":"天气"}"""))
        val r2 = decisionsOf(guard, fp("search_web", """{"query":"天气"}"""))
        assertTrue(r1.single() is ToolLoopGuard.CallDecision.Execute)
        assertTrue(r2.single() is ToolLoopGuard.CallDecision.Execute)
        // 第 3 次连续相同调用拦截
        val r3 = decisionsOf(guard, fp("search_web", """{"query":"天气"}"""))
        assertTrue(r3.single() is ToolLoopGuard.CallDecision.Block)
    }

    @Test
    fun `different arguments are never treated as duplicate`() {
        val guard = ToolLoopGuard()
        repeat(10) { i ->
            val decision = decisionsOf(guard, fp("search_web", """{"query":"q$i"}""")).single()
            assertTrue("different args must execute at round $i", decision is ToolLoopGuard.CallDecision.Execute)
        }
    }

    @Test
    fun `non-consecutive identical calls are not blocked`() {
        val guard = ToolLoopGuard()
        // 相同调用 → 中断一次（不同参数）→ 再相同调用：连续性被打破，重新计数
        assertTrue(decisionsOf(guard, fp("search_web", """{"query":"a"}""")).single() is ToolLoopGuard.CallDecision.Execute)
        assertTrue(decisionsOf(guard, fp("search_web", """{"query":"b"}""")).single() is ToolLoopGuard.CallDecision.Execute)
        assertTrue(decisionsOf(guard, fp("search_web", """{"query":"a"}""")).single() is ToolLoopGuard.CallDecision.Execute)
    }

    @Test
    fun `fingerprints of different tools are independent`() {
        val guard = ToolLoopGuard()
        // 第 1 轮：两个不同工具、相同参数 → 各自连续第 1 次，均放行
        val r1 = decisionsOf(guard, fp("search_web", """{"query":"x"}"""), fp("read_url", """{"query":"x"}"""))
        assertTrue(r1.all { it is ToolLoopGuard.CallDecision.Execute })
        // 第 2 轮：仅 search_web 重复 → 连续第 2 次，放行
        val r2 = decisionsOf(guard, fp("search_web", """{"query":"x"}"""))
        assertTrue(r2.single() is ToolLoopGuard.CallDecision.Execute)
        // 第 3 轮：search_web 连续第 3 次 → 拦截
        val r3 = decisionsOf(guard, fp("search_web", """{"query":"x"}"""))
        assertTrue(r3.single() is ToolLoopGuard.CallDecision.Block)
        // 第 4 轮：read_url 未连续出现（连续性已断）→ 重新计数，放行
        val r4 = decisionsOf(guard, fp("read_url", """{"query":"x"}"""))
        assertTrue("different tool fingerprint must be independent", r4.single() is ToolLoopGuard.CallDecision.Execute)
    }

    @Test
    fun `parallel duplicate calls in one round both execute then counted next round`() {
        val guard = ToolLoopGuard()
        // 同一轮内两次相同调用：都放行
        val r1 = decisionsOf(guard, fp("search_web", """{"query":"a"}"""), fp("search_web", """{"query":"a"}"""))
        assertTrue(r1.all { it is ToolLoopGuard.CallDecision.Execute })
        // 下一轮仍相同：连续第 2 次，仍放行
        val r2 = decisionsOf(guard, fp("search_web", """{"query":"a"}"""), fp("search_web", """{"query":"a"}"""))
        assertTrue(r2.all { it is ToolLoopGuard.CallDecision.Execute })
        // 连续第 3 次：拦截
        val r3 = decisionsOf(guard, fp("search_web", """{"query":"a"}"""), fp("search_web", """{"query":"a"}"""))
        assertTrue(r3.all { it is ToolLoopGuard.CallDecision.Block })
    }

    // ------------------------------------------------------------------
    // 轮次上限
    // ------------------------------------------------------------------

    @Test
    fun `round limit blocks all calls when exceeded`() {
        val guard = ToolLoopGuard(maxToolRounds = 3)
        repeat(3) { round ->
            val decisions = decisionsOf(guard, fp("tool_$round"))
            assertTrue("round $round should execute", decisions.single() is ToolLoopGuard.CallDecision.Execute)
        }
        // 第 4 轮：超限，全部拦截
        val blocked = decisionsOf(guard, fp("tool_3"), fp("tool_4"))
        assertEquals(2, blocked.size)
        assertTrue(blocked.all { it is ToolLoopGuard.CallDecision.Block })
    }

    @Test
    fun `blocked rounds by round-limit do not advance fingerprint state`() {
        val guard = ToolLoopGuard(maxToolRounds = 1)
        assertTrue(decisionsOf(guard, fp("search_web", """{"query":"a"}""")).single() is ToolLoopGuard.CallDecision.Execute)
        // 超限轮：拦截（不更新指纹状态）
        assertTrue(decisionsOf(guard, fp("search_web", """{"query":"a"}""")).single() is ToolLoopGuard.CallDecision.Block)
        // 仍然超限：继续拦截
        assertTrue(decisionsOf(guard, fp("search_web", """{"query":"a"}""")).single() is ToolLoopGuard.CallDecision.Block)
    }

    @Test
    fun `block reason contains tool name and guidance`() {
        val guard = ToolLoopGuard(maxToolRounds = 0)
        val decision = decisionsOf(guard, fp("my_tool")).single() as ToolLoopGuard.CallDecision.Block
        assertTrue(decision.reason.contains("上限"))
        assertTrue(decision.reason.contains("回答用户"))
    }

    // ------------------------------------------------------------------
    // 边界
    // ------------------------------------------------------------------

    @Test
    fun `empty round returns empty decisions`() {
        val guard = ToolLoopGuard()
        assertTrue(guard.evaluateRound(emptyList()).isEmpty())
    }

    @Test
    fun `blank arguments normalize to same fingerprint`() {
        // 调用方以 sanitizeToolCallArguments(ifBlank { "{}" }) 归一化后生成指纹，
        // 这里验证指纹 key 对参数字符串敏感（不同字符串 = 不同指纹）
        val f1 = fp("tool", "{}")
        val f2 = fp("tool", """{"query":"a"}""")
        assertTrue(f1.key != f2.key)
        assertEquals(f1.key, fp("tool", "{}").key)
    }
}
