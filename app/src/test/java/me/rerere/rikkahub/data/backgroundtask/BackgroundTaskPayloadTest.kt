package me.rerere.rikkahub.data.backgroundtask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 版本化 payload/result 序列化往返与向前兼容（JsonInstant ignoreUnknownKeys + 默认值）。
 */
class BackgroundTaskPayloadTest {

    @Test
    fun `payload round trip`() {
        val payload = RadioAudioTaskPayload(
            episodeId = "ep-1",
            contentHash = "hash-1",
            episodeTitle = "午夜电台",
            userInstruction = "生成并发布",
            followUpInstruction = "完成后发布",
        )
        val json = RadioAudioTaskCodec.encodePayload(payload)
        val decoded = RadioAudioTaskCodec.decodePayload(json) ?: error("解码失败")
        assertEquals(payload, decoded)
    }

    @Test
    fun `old payload without new fields decodes with defaults`() {
        // 模拟旧版本写入的 JSON（缺少后加字段）
        val oldJson = """{"version":1,"episodeId":"ep-2","contentHash":"hash-2"}"""
        val decoded = RadioAudioTaskCodec.decodePayload(oldJson) ?: error("解码失败")
        assertEquals("ep-2", decoded.episodeId)
        assertEquals("hash-2", decoded.contentHash)
        assertNull(decoded.followUpInstruction)
        assertNull(decoded.userInstruction)
    }

    @Test
    fun `new payload with unknown fields decodes ignoring extras`() {
        // 模拟更新版本写入的 JSON（多出未知字段）
        val newJson = """{"version":1,"episodeId":"ep-3","contentHash":"h","futureField":"x"}"""
        val decoded = RadioAudioTaskCodec.decodePayload(newJson) ?: error("解码失败")
        assertEquals("ep-3", decoded.episodeId)
    }

    @Test
    fun `corrupted payload decodes to null`() {
        assertNull(RadioAudioTaskCodec.decodePayload("not a json"))
        assertNull(RadioAudioTaskCodec.decodePayload("""{"episodeId":123}"""))
    }

    @Test
    fun `result round trip and fallback notice`() {
        val result = RadioAudioTaskResult(audioId = "audio-1", durationMs = 1234L, voiceFallbackNotice = "音色已回退")
        val decoded = RadioAudioTaskCodec.decodeResult(RadioAudioTaskCodec.encodeResult(result)) ?: error("解码失败")
        assertEquals(result, decoded)
        val minimal = RadioAudioTaskCodec.decodeResult("""{"audioId":"a"}""") ?: error("解码失败")
        assertEquals("a", minimal.audioId)
        assertEquals(0L, minimal.durationMs)
        assertNull(minimal.voiceFallbackNotice)
    }
}
