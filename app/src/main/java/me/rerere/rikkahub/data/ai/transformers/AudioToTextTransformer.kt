package me.rerere.rikkahub.data.ai.transformers

import android.util.Log
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.common.jsonPrimitiveOrNull

private const val TAG = "AudioToTextTransformer"

/**
 * 输入消息转换器：把 [UIMessagePart.Audio] 按策略转换为 Text。
 *
 * 策略：
 * 1. **模型支持音频输入（Modality.AUDIO）**：
 *    - 仅"最后一条 USER 消息"中的 Audio Part **保留**，让模型直接接收音频（当轮真正"听语音"）。
 *    - 其他所有 Audio（历史 USER 消息、所有 ASSISTANT 消息、其他角色消息）**转为文本**，
 *      节省上下文 token 并保证 RAG 检索阶段能命中（因为转写是更精准的语义表达）。
 * 2. **模型不支持音频输入**：
 *    - 所有 Audio 一律转 Text。历史数据中有转写结果就用它（拼"用户给你发送了一条语音消息，内容是："前缀）；
 *      无转写结果回退为 "[语音消息]"。
 *
 * 转写结果来源：Audio Part metadata.transcription（ChatVM.sendVoiceMessage 发送前统一 ASR 后写入，
 * 无论模型是否支持音频都会做 ASR 并存入 chat_messages）。
 */
object AudioToTextTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>
    ): List<UIMessage> {
        val supportsAudio = ctx.model.inputModalities.contains(Modality.AUDIO)
        val lastUserIndex = messages.indexOfLast { it.role == MessageRole.USER }

        var transformedCount = 0
        val result = messages.mapIndexed { index, message ->
            val hasAudio = message.parts.any { it is UIMessagePart.Audio }
            if (!hasAudio) return@mapIndexed message

            // 模型支持音频时：最后一条 USER 消息保留 Audio，不做转换
            val keepAudio = supportsAudio && index == lastUserIndex && message.role == MessageRole.USER
            if (keepAudio) return@mapIndexed message

            // 其他情况：把 Audio Part 转为 Text Part
            message.copy(
                parts = message.parts.map { part ->
                    if (part !is UIMessagePart.Audio) return@map part
                    transformedCount++
                    val transcription = part.metadata?.get(METADATA_TRANSCRIPTION)
                        ?.jsonPrimitiveOrNull
                        ?.content
                        ?.takeIf { it.isNotBlank() }
                    // 有转写结果：拼前缀让 AI 明确知道是语音消息内容
                    // 无转写结果：占位文本（避免上下文空洞）
                    val text = if (transcription != null) {
                        "用户给你发送了一条语音消息，内容是：$transcription"
                    } else {
                        "[语音消息]"
                    }
                    UIMessagePart.Text(text = text)
                }
            )
        }
        if (transformedCount > 0) {
            Log.i(
                TAG,
                "transform: converted $transformedCount audio part(s) to text; " +
                    "supportsAudio=$supportsAudio; lastUserIndex=$lastUserIndex; " +
                    "lastUserKeptAudio=${supportsAudio && lastUserIndex >= 0}"
            )
        }
        return result
    }

    /** Audio Part metadata 中存储 ASR 转写文本的字段名。 */
    const val METADATA_TRANSCRIPTION = "transcription"
    /** Audio Part metadata 中存储录音时长（毫秒）的字段名。 */
    const val METADATA_DURATION_MS = "durationMs"
}
