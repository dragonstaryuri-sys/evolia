package me.rerere.rikkahub.data.backgroundtask

import me.rerere.rikkahub.core.data.db.entity.BackgroundTaskCallbackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通用后台任务纯决策规则（状态机 / deadline / 冲突 / 回调投递与恢复 / 授权解析）。
 */
class BackgroundTaskRulesTest {

    // ==================== deadline ====================

    @Test
    fun `deadline is creation time plus total budget`() {
        val createdAt = 1_000_000L
        assertEquals(createdAt + BackgroundTaskRules.TASK_TOTAL_DEADLINE_MS, BackgroundTaskRules.deadlineFromCreation(createdAt))
    }

    @Test
    fun `deadline exceeded at or after boundary`() {
        val deadline = 5_000L
        assertFalse(BackgroundTaskRules.isDeadlineExceeded(deadline, 4_999L))
        assertTrue(BackgroundTaskRules.isDeadlineExceeded(deadline, 5_000L))
        assertTrue(BackgroundTaskRules.isDeadlineExceeded(deadline, 5_001L))
    }

    // ==================== 创建冲突决策 ====================

    private fun candidate(
        id: String,
        hash: String,
        createdAt: Long = 0,
        executing: Boolean = false
    ) = BackgroundTaskRules.ConflictCandidate(
        taskId = id, contentHash = hash, createdAt = createdAt, isExecuting = executing
    )

    @Test
    fun `no active tasks proceeds`() {
        assertEquals(BackgroundTaskRules.ConflictDecision.Proceed, BackgroundTaskRules.decideConflict(emptyList(), "h1"))
    }

    @Test
    fun `same hash reuses existing task preferring executing one`() {
        val queued = candidate("queued-1", "h1", createdAt = 100, executing = false)
        val running = candidate("running-1", "h1", createdAt = 50, executing = true)
        val decision = BackgroundTaskRules.decideConflict(listOf(queued, running), "h1")
        assertEquals(BackgroundTaskRules.ConflictDecision.Reuse("running-1"), decision)
    }

    @Test
    fun `same hash reuses newest among queued tasks`() {
        val older = candidate("old", "h1", createdAt = 10)
        val newer = candidate("new", "h1", createdAt = 20)
        val decision = BackgroundTaskRules.decideConflict(listOf(older, newer), "h1")
        assertEquals(BackgroundTaskRules.ConflictDecision.Reuse("new"), decision)
    }

    @Test
    fun `different hash rejects with existing task id`() {
        val existing = candidate("existing", "h-old", createdAt = 10, executing = true)
        val decision = BackgroundTaskRules.decideConflict(listOf(existing), "h-new")
        assertEquals(BackgroundTaskRules.ConflictDecision.Reject("existing"), decision)
    }

    // ==================== 回调投递决策 ====================

    @Test
    fun `notify_agent delivers when idle and pending`() {
        assertTrue(
            BackgroundTaskRules.shouldDeliverCallback(
                callbackState = BackgroundTaskCallbackState.PENDING.name,
                completionPolicy = CompletionPolicies.NOTIFY_AGENT,
                conversationBusy = false
            )
        )
    }

    @Test
    fun `busy conversation defers delivery`() {
        assertFalse(
            BackgroundTaskRules.shouldDeliverCallback(
                callbackState = BackgroundTaskCallbackState.PENDING.name,
                completionPolicy = CompletionPolicies.NOTIFY_AGENT,
                conversationBusy = true
            )
        )
    }

    @Test
    fun `notify_user never wakes agent`() {
        assertFalse(
            BackgroundTaskRules.shouldDeliverCallback(
                callbackState = BackgroundTaskCallbackState.PENDING.name,
                completionPolicy = CompletionPolicies.NOTIFY_USER,
                conversationBusy = false
            )
        )
    }

    @Test
    fun `non-pending callback state never delivers`() {
        assertFalse(
            BackgroundTaskRules.shouldDeliverCallback(
                callbackState = BackgroundTaskCallbackState.COMPLETED.name,
                completionPolicy = CompletionPolicies.NOTIFY_AGENT,
                conversationBusy = false
            )
        )
    }

    // ==================== INJECTED 恢复决策 ====================

    @Test
    fun `injected with assistant response marks completed`() {
        assertEquals(
            BackgroundTaskRules.InjectedRecovery.MARK_COMPLETED,
            BackgroundTaskRules.decideInjectedRecovery(hasAssistantResponseAfterCallback = true, isCallbackMessageLastNode = false)
        )
    }

    @Test
    fun `injected without response and last node resumes generation without reinject`() {
        assertEquals(
            BackgroundTaskRules.InjectedRecovery.RESUME_GENERATION,
            BackgroundTaskRules.decideInjectedRecovery(hasAssistantResponseAfterCallback = false, isCallbackMessageLastNode = true)
        )
    }

    @Test
    fun `injected without response but user continued marks completed conservatively`() {
        assertEquals(
            BackgroundTaskRules.InjectedRecovery.MARK_COMPLETED,
            BackgroundTaskRules.decideInjectedRecovery(hasAssistantResponseAfterCallback = false, isCallbackMessageLastNode = false)
        )
    }

    // ==================== 回调消息稳定 ID ====================

    @Test
    fun `callback message id is deterministic per task`() {
        val id1 = BackgroundTaskRules.callbackMessageId("task-a")
        val id2 = BackgroundTaskRules.callbackMessageId("task-a")
        val id3 = BackgroundTaskRules.callbackMessageId("task-b")
        assertEquals(id1, id2)
        assertTrue(id1 != id3)
    }

    // ==================== 回调指令构造与解析 ====================

    private fun buildInstruction(
        status: String = "succeeded",
        policy: String = CompletionPolicies.CONTINUE_WORKFLOW,
        followUp: String? = "完成后发布节目",
        resultJson: String? = """{"audioId":"a1","durationMs":1000}"""
    ): String = BackgroundTaskRules.buildCallbackInstruction(
        taskId = "task-1",
        taskType = BackgroundTaskTypes.RADIO_AUDIO_GENERATION,
        refKey = "radio_audio:ep-1",
        status = status,
        completionPolicy = policy,
        resultJson = resultJson,
        errorMessage = if (status == "failed") "第 3/10 句语音合成失败" else null,
        followUpInstruction = followUp,
        userInstruction = "帮我生成并直接发布节目",
    )

    @Test
    fun `instruction round trip preserves context and authorization`() {
        val text = buildInstruction()
        val parsed = BackgroundTaskRules.parseCallbackInstruction(text) ?: error("解析失败")
        assertEquals("task-1", parsed.taskId)
        assertEquals(BackgroundTaskTypes.RADIO_AUDIO_GENERATION, parsed.taskType)
        assertEquals("radio_audio:ep-1", parsed.refKey)
        assertEquals("succeeded", parsed.status)
        assertEquals(CompletionPolicies.CONTINUE_WORKFLOW, parsed.completionPolicy)
        assertEquals("publish_episode:ep-1", parsed.authorizedPublishEpisodeAction)
        // callbackId 与稳定派生 ID 一致
        assertEquals(BackgroundTaskRules.callbackMessageId("task-1").toString(), parsed.callbackId)
    }

    @Test
    fun `no publish authorization without continue_workflow`() {
        val text = buildInstruction(policy = CompletionPolicies.NOTIFY_AGENT)
        val parsed = BackgroundTaskRules.parseCallbackInstruction(text) ?: error("解析失败")
        assertNull(parsed.authorizedPublishEpisodeAction)
    }

    @Test
    fun `no publish authorization with empty follow up`() {
        val text = buildInstruction(followUp = null)
        val parsed = BackgroundTaskRules.parseCallbackInstruction(text) ?: error("解析失败")
        assertNull(parsed.authorizedPublishEpisodeAction)
    }

    @Test
    fun `failed instruction carries error and no authorization without follow up`() {
        val text = buildInstruction(status = "failed", followUp = null)
        val parsed = BackgroundTaskRules.parseCallbackInstruction(text) ?: error("解析失败")
        assertEquals("failed", parsed.status)
        assertNull(parsed.authorizedPublishEpisodeAction)
        assertTrue(text.contains("第 3/10 句语音合成失败"))
    }

    @Test
    fun `parse rejects non-callback text`() {
        assertNull(BackgroundTaskRules.parseCallbackInstruction("用户随便说的一句话"))
        assertNull(BackgroundTaskRules.parseCallbackInstruction(""))
    }

    @Test
    fun `header is present for ui filtering reference`() {
        assertNotNull(BackgroundTaskCallbackHeader.INSTRUCTION_HEADER)
    }

    // ==================== refKey 工具 ====================

    @Test
    fun `ref key round trip`() {
        val refKey = BackgroundTaskTypes.refKeyForRadioAudio("ep-9")
        assertEquals("radio_audio:ep-9", refKey)
        assertEquals("ep-9", BackgroundTaskTypes.episodeIdFromRadioAudioRefKey(refKey))
        assertNull(BackgroundTaskTypes.episodeIdFromRadioAudioRefKey("other:1"))
    }

    // ==================== 任务卡片挂载 ID 校验 ====================

    @Test
    fun `uuid task id passes mount guard`() {
        val uuid = BackgroundTaskRules.callbackMessageId("any").toString()
        assertTrue(BackgroundTaskRules.isBackgroundTaskId(uuid))
    }

    @Test
    fun `numeric task id from agent_tasks is rejected`() {
        // agent_task_manager add 返回的 Long 自增 ID（误挂载根因）
        assertFalse(BackgroundTaskRules.isBackgroundTaskId("12"))
        assertFalse(BackgroundTaskRules.isBackgroundTaskId("0"))
    }

    @Test
    fun `null blank and malformed ids are rejected`() {
        assertFalse(BackgroundTaskRules.isBackgroundTaskId(null))
        assertFalse(BackgroundTaskRules.isBackgroundTaskId(""))
        assertFalse(BackgroundTaskRules.isBackgroundTaskId("   "))
        assertFalse(BackgroundTaskRules.isBackgroundTaskId("not-a-uuid"))
        assertFalse(BackgroundTaskRules.isBackgroundTaskId("radio_audio:ep-9"))
    }

    @Test
    fun `uppercase hyphenated uuid is accepted`() {
        val lower = BackgroundTaskRules.callbackMessageId("case").toString()
        assertTrue(BackgroundTaskRules.isBackgroundTaskId(lower.uppercase()))
    }
}
