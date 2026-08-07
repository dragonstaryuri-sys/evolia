package me.rerere.rikkahub.data.ai.transformers

import android.util.Log
import me.rerere.ai.provider.Modality
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.common.jsonPrimitiveOrNull

private const val TAG = "AudioToTextTransformer"

/**
 * 输入消息转换器：当模型不支持音频输入时，把 [UIMessagePart.Audio] 转成文本。
 *
 * - 优先使用 Audio Part metadata 里的 `transcription` 字段（由 ChatVM.sendVoiceMessage 在发送前 ASR 转写好）。
 * - 没有 transcription 时回退为占位文本 `[语音消息]`，保证上下文里至少有一句话占位。
 *
 * 模型支持音频输入（[Modality.AUDIO]）时直接透传，由 Provider 层负责编码音频。
 */
object AudioToTextTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>
    ): List<UIMessage> {
        if (ctx.model.inputModalities.contains(Modality.AUDIO)) {
            return messages
        }
        var transformedCount = 0
        val result = messages.map { message ->
            val hasAudio = message.parts.any { it is UIMessagePart.Audio }
            if (!hasAudio) return@map message
            message.copy(
                parts = message.parts.map { part ->
                    if (part !is UIMessagePart.Audio) return@map part
                    transformedCount++
                    val transcription = part.metadata?.get(METADATA_TRANSCRIPTION)
                        ?.jsonPrimitiveOrNull
                        ?.content
                        ?.takeIf { it.isNotBlank() }
                    // 有转写结果时拼接提示前缀，让模型明确这是用户发来的语音消息
                    val fallback = "[语音消息]"
                    val text = if (transcription != null) {
                        "$VOICE_MESSAGE_TEXT_PREFIX$transcription"
                    } else {
                        fallback
                    }
                    UIMessagePart.Text(text = text)
                }
            )
        }
        if (transformedCount > 0) {
            Log.i(TAG, "transform: converted $transformedCount audio part(s) to text (model lacks AUDIO modality)")
        }
        return result
    }

    /** Audio Part metadata 中存储 ASR 转写文本的字段名。 */
    const val METADATA_TRANSCRIPTION = "transcription"
    /** Audio Part metadata 中存储录音时长（毫秒）的字段名。 */
    const val METADATA_DURATION_MS = "durationMs"
    /** 当模型不支持音频输入时，拼接在 ASR 转写文本前的提示前缀。 */
    const val VOICE_MESSAGE_TEXT_PREFIX = "用户给你发送了一条语音消息，内容是："
}
