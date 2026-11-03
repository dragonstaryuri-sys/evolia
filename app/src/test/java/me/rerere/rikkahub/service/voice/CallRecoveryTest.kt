package me.rerere.rikkahub.service.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CallRecovery] 纯函数单元测试：异常结束时间锚点、时长计算、孤儿录音判定。
 *
 * 约定：时间统一用相对毫秒值，避免绝对时间戳耦合。
 */
class CallRecoveryTest {

    // ===== computeRecoveredEndAnchor =====

    @Test
    fun `anchor - 心跳与转写同时存在时取较晚者`() {
        // 心跳 2000、末条转写 2200 → 取 2200（末条转写晚于最后一次心跳）
        val anchor = CallRecovery.computeRecoveredEndAnchor(
            connectedAt = 1000L, lastHeartbeatAt = 2000L, endTime = 2200L, recoveryNow = 100_000L
        )
        assertEquals(2200L, anchor)
    }

    @Test
    fun `anchor - 心跳晚于转写时取心跳`() {
        val anchor = CallRecovery.computeRecoveredEndAnchor(
            connectedAt = 1000L, lastHeartbeatAt = 6000L, endTime = 2200L, recoveryNow = 100_000L
        )
        assertEquals(6000L, anchor)
    }

    @Test
    fun `anchor - 仅有心跳时用心跳`() {
        val anchor = CallRecovery.computeRecoveredEndAnchor(
            connectedAt = 100L, lastHeartbeatAt = 3000L, endTime = null, recoveryNow = 100_000L
        )
        assertEquals(3000L, anchor)
    }

    @Test
    fun `anchor - 仅有转写时间时用转写时间`() {
        val anchor = CallRecovery.computeRecoveredEndAnchor(
            connectedAt = 100L, lastHeartbeatAt = null, endTime = 2000L, recoveryNow = 100_000L
        )
        assertEquals(2000L, anchor)
    }

    @Test
    fun `anchor - 两者皆无时以接通时间兜底（时长记 0）`() {
        // 进程在首个心跳间隔内死亡且无任何转写
        val anchor = CallRecovery.computeRecoveredEndAnchor(
            connectedAt = 100L, lastHeartbeatAt = null, endTime = null, recoveryNow = 100_000L
        )
        assertEquals(100L, anchor)
    }

    @Test
    fun `anchor - 心跳早于接通时间的脏数据被 clamp 到接通时间`() {
        val anchor = CallRecovery.computeRecoveredEndAnchor(
            connectedAt = 1000L, lastHeartbeatAt = 500L, endTime = null, recoveryNow = 100_000L
        )
        assertEquals(1000L, anchor)
    }

    @Test
    fun `anchor - 锚点晚于恢复时刻被 clamp 到恢复时刻`() {
        val anchor = CallRecovery.computeRecoveredEndAnchor(
            connectedAt = 1000L, lastHeartbeatAt = 50_000L, endTime = null, recoveryNow = 10_000L
        )
        assertEquals(10_000L, anchor)
    }

    @Test
    fun `anchor - connectedAt 为空或非法时返回 null`() {
        assertNull(
            CallRecovery.computeRecoveredEndAnchor(
                connectedAt = null, lastHeartbeatAt = 100L, endTime = 100L, recoveryNow = 1000L
            )
        )
        assertNull(
            CallRecovery.computeRecoveredEndAnchor(
                connectedAt = 0L, lastHeartbeatAt = 100L, endTime = 100L, recoveryNow = 1000L
            )
        )
        assertNull(
            CallRecovery.computeRecoveredEndAnchor(
                connectedAt = -5L, lastHeartbeatAt = 100L, endTime = 100L, recoveryNow = 1000L
            )
        )
    }

    @Test
    fun `anchor - 心跳或转写为非正数时视为无效`() {
        val anchor = CallRecovery.computeRecoveredEndAnchor(
            connectedAt = 100L, lastHeartbeatAt = 0L, endTime = -1L, recoveryNow = 100_000L
        )
        assertEquals(100L, anchor)
    }

    @Test
    fun `anchor - 恢复时刻早于接通时间（时钟回拨）不抛异常且不小于接通时间`() {
        val anchor = CallRecovery.computeRecoveredEndAnchor(
            connectedAt = 10_000L, lastHeartbeatAt = 20_000L, endTime = null, recoveryNow = 5_000L
        )
        assertEquals(10_000L, anchor)
    }

    // ===== computeRecoveredDurationMs =====

    @Test
    fun `duration - 正常计算 endedAt - connectedAt`() {
        val duration = CallRecovery.computeRecoveredDurationMs(connectedAt = 1000L, endedAt = 65_000L)
        assertEquals(64_000L, duration)
    }

    @Test
    fun `duration - endedAt 早于 connectedAt 时记 0（不为负）`() {
        val duration = CallRecovery.computeRecoveredDurationMs(connectedAt = 5000L, endedAt = 1000L)
        assertEquals(0L, duration)
    }

    @Test
    fun `duration - 任一时间为空时记 0`() {
        assertEquals(0L, CallRecovery.computeRecoveredDurationMs(null, 1000L))
        assertEquals(0L, CallRecovery.computeRecoveredDurationMs(1000L, null))
        assertEquals(0L, CallRecovery.computeRecoveredDurationMs(null, null))
    }

    // ===== isOrphanRecordingFile =====

    @Test
    fun `orphan - 被 DB 引用的文件不删除`() {
        val isOrphan = CallRecovery.isOrphanRecordingFile(
            fileName = "call-1.m4a",
            referencedFileNames = setOf("call-1.m4a", "call-2.m4a"),
            activeCallId = null,
            fileLastModifiedMs = 0L,
            nowMs = 100_000L
        )
        assertFalse(isOrphan)
    }

    @Test
    fun `orphan - 无引用且超过保留期时判定为孤儿`() {
        val nowMs = 100_000_000L
        val isOrphan = CallRecovery.isOrphanRecordingFile(
            fileName = "orphan.m4a",
            referencedFileNames = emptySet(),
            activeCallId = null,
            fileLastModifiedMs = nowMs - CallRecovery.ORPHAN_RETENTION_MS - 1L,
            nowMs = nowMs
        )
        assertTrue(isOrphan)
    }

    @Test
    fun `orphan - 恰好等于保留期时不删除（严格大于）`() {
        val nowMs = 100_000_000L
        val isOrphan = CallRecovery.isOrphanRecordingFile(
            fileName = "orphan.m4a",
            referencedFileNames = emptySet(),
            activeCallId = null,
            fileLastModifiedMs = nowMs - CallRecovery.ORPHAN_RETENTION_MS,
            nowMs = nowMs
        )
        assertFalse(isOrphan)
    }

    @Test
    fun `orphan - 保留期内的新文件不删除`() {
        val nowMs = 100_000_000L
        val isOrphan = CallRecovery.isOrphanRecordingFile(
            fileName = "fresh.m4a",
            referencedFileNames = emptySet(),
            activeCallId = null,
            fileLastModifiedMs = nowMs - 60_000L,
            nowMs = nowMs
        )
        assertFalse(isOrphan)
    }

    @Test
    fun `orphan - 活跃通话的录音文件即使无 DB 引用也不删除`() {
        // 录音进行中 audio 字段尚未写入，仅靠 callId 守卫
        val isOrphan = CallRecovery.isOrphanRecordingFile(
            fileName = "active-call.m4a",
            referencedFileNames = emptySet(),
            activeCallId = "active-call",
            fileLastModifiedMs = 0L,
            nowMs = 100_000_000L
        )
        assertFalse(isOrphan)
    }

    @Test
    fun `orphan - 最后修改时间不可读时保守保留`() {
        val isOrphan = CallRecovery.isOrphanRecordingFile(
            fileName = "unknown-mtime.m4a",
            referencedFileNames = emptySet(),
            activeCallId = null,
            fileLastModifiedMs = 0L,
            nowMs = 100_000_000L
        )
        assertFalse(isOrphan)
    }
}
