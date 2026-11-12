package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.AudioToTextTransformer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * buildLastUserMessageParts（最后一条 USER 消息 parts 重组）的行为验证：
 * 按模型音频输入能力决定 Audio Part 去留，且不破坏既有 Quote/Text 配对格式。
 */
class BuildLastUserMessagePartsTest {

    private fun audioPart(transcription: String? = "你好呀") = UIMessagePart.Audio(
        url = "file:///voice/test.m4a",
        metadata = if (transcription == null) {
            buildJsonObject { put(AudioToTextTransformer.METADATA_DURATION_MS, 1200L) }
        } else {
            buildJsonObject {
                put(AudioToTextTransformer.METADATA_DURATION_MS, 1200L)
                put(AudioToTextTransformer.METADATA_TRANSCRIPTION, transcription)
            }
        }
    )

    private fun quotePart() = UIMessagePart.Quote(
        senderName = "Evolia",
        content = "今天过得怎么样？",
        isUser = false
    )

    private fun textOf(parts: List<UIMessagePart>): String =
        parts.filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n") { it.text }
            // appendLine 使用 System.lineSeparator()，Windows JVM 上为 \r\n（Android 上为 \n），归一化后断言
            .replace("\r\n", "\n")

    // ========== 情况 A：模型支持 Modality.AUDIO ==========

    @Test
    fun `supports audio - keeps raw audio even when transcription exists`() {
        val result = buildLastUserMessageParts(
            parts = listOf(audioPart(transcription = "你好呀")),
            supportsAudioInput = true,
            dynamicContext = "",
        )
        // 原始 Audio 必须保留（即使 metadata 已有 transcription）
        assertEquals(1, result.size)
        val audio = result.filterIsInstance<UIMessagePart.Audio>().single()
        assertEquals("file:///voice/test.m4a", audio.url)
        // 转写文本不得拼入 prompt（避免与音频重复）
        assertEquals("", textOf(result))
    }

    @Test
    fun `supports audio - keeps audio without transcription`() {
        val result = buildLastUserMessageParts(
            parts = listOf(audioPart(transcription = null)),
            supportsAudioInput = true,
            dynamicContext = "",
        )
        assertEquals(1, result.filterIsInstance<UIMessagePart.Audio>().size)
    }

    @Test
    fun `supports audio - quote converted to reference text, audio kept`() {
        val result = buildLastUserMessageParts(
            parts = listOf(quotePart(), audioPart(transcription = "挺好的")),
            supportsAudioInput = true,
            dynamicContext = "",
        )
        // Quote 转为引用结构文本（无"用户回复："尾巴，回复由音频承载）
        assertTrue(textOf(result).contains("引用你刚才说的内容："))
        assertTrue(textOf(result).contains("今天过得怎么样？"))
        // 转写不进 prompt
        assertTrue(!textOf(result).contains("挺好的"))
        // Audio 保留
        assertEquals(1, result.filterIsInstance<UIMessagePart.Audio>().size)
    }

    @Test
    fun `supports audio - text and audio mix keeps both`() {
        val result = buildLastUserMessageParts(
            parts = listOf(
                UIMessagePart.Text("看看这个"),
                audioPart(transcription = "看看这个"),
            ),
            supportsAudioInput = true,
            dynamicContext = "",
        )
        assertTrue(textOf(result).contains("看看这个"))
        assertEquals(1, result.filterIsInstance<UIMessagePart.Audio>().size)
    }

    // ========== 情况 B：模型不支持 Modality.AUDIO ==========

    @Test
    fun `no audio support - transcription used as text, audio dropped`() {
        val result = buildLastUserMessageParts(
            parts = listOf(audioPart(transcription = "你好呀")),
            supportsAudioInput = false,
            dynamicContext = "",
        )
        // Audio 被剔除，转写作为文本
        assertEquals(0, result.filterIsInstance<UIMessagePart.Audio>().size)
        assertEquals(1, result.size)
        assertEquals("你好呀", textOf(result))
    }

    @Test
    fun `no audio support - asr failed keeps audio for downstream fallback`() {
        val result = buildLastUserMessageParts(
            parts = listOf(audioPart(transcription = null)),
            supportsAudioInput = false,
            dynamicContext = "",
        )
        // ASR 失败：保留 Audio Part 交由 AudioToTextTransformer 输出降级文案，不伪造转写
        assertEquals(1, result.filterIsInstance<UIMessagePart.Audio>().size)
        // 且不再塞入空 Text Part（修复当轮语音变成空消息）
        assertEquals(0, result.filterIsInstance<UIMessagePart.Text>().size)
    }

    @Test
    fun `no audio support - blank transcription treated as missing`() {
        val result = buildLastUserMessageParts(
            parts = listOf(audioPart(transcription = "  ")),
            supportsAudioInput = false,
            dynamicContext = "",
        )
        assertEquals(1, result.filterIsInstance<UIMessagePart.Audio>().size)
    }

    @Test
    fun `no audio support - quote paired with transcription like text`() {
        val result = buildLastUserMessageParts(
            parts = listOf(quotePart(), audioPart(transcription = "挺好的")),
            supportsAudioInput = false,
            dynamicContext = "",
        )
        // 与 Text 引用配对格式完全一致
        assertEquals(
            "引用你刚才说的内容：\n> 今天过得怎么样？\n用户回复：挺好的",
            textOf(result)
        )
        assertEquals(0, result.filterIsInstance<UIMessagePart.Audio>().size)
    }

    // ========== 动态上下文注入 ==========

    @Test
    fun `dynamic context is injected as system prefix`() {
        val result = buildLastUserMessageParts(
            parts = listOf(audioPart(transcription = "你好呀")),
            supportsAudioInput = false,
            dynamicContext = "## 相关记忆片段",
        )
        val text = textOf(result)
        // 现行格式：用户转写文本在前，动态上下文后置（builtText 直接拼 "System:\n[...]"，无换行间隔）
        assertTrue(text.startsWith("你好呀System:\n[## 相关记忆片段]"))
        assertTrue(text.contains("你好呀"))
        assertEquals(0, result.filterIsInstance<UIMessagePart.Audio>().size)
    }

    @Test
    fun `supports audio with dynamic context - audio kept after text part`() {
        val result = buildLastUserMessageParts(
            parts = listOf(audioPart(transcription = "你好呀")),
            supportsAudioInput = true,
            dynamicContext = "## 相关记忆片段",
        )
        // 动态上下文注入 + Audio 保留，两部分共存（builtText 为空时文本以 System: 开头）
        assertTrue(textOf(result).startsWith("System:\n[## 相关记忆片段]"))
        assertEquals(1, result.filterIsInstance<UIMessagePart.Audio>().size)
        assertTrue(!textOf(result).contains("你好呀"))
    }

    // ========== 非音频 part 透传 ==========

    @Test
    fun `non audio parts like image are passed through`() {
        val image = UIMessagePart.Image(url = "file:///img/a.png")
        val result = buildLastUserMessageParts(
            parts = listOf(image, audioPart(transcription = "看图")),
            supportsAudioInput = false,
            dynamicContext = "",
        )
        assertTrue(result.contains(image))
        assertEquals("看图", textOf(result))
    }
}
