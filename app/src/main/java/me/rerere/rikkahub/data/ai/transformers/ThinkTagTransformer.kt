package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.time.Clock

// Matches <think>...</think> or <thinking>...</thinking> with optional closing tag
private val THINKING_REGEX = Regex("<think(?:ing)?>([\\s\\S]*?)(?:</think(?:ing)?>|$)", RegexOption.DOT_MATCHES_ALL)

// Matches orphaned closing tags: content followed by </think> or </thinking> without opening tag
private val ORPHAN_CLOSE_TAG_REGEX = Regex("^([\\s\\S]*?)</think(?:ing)?>", RegexOption.DOT_MATCHES_ALL)

// 部分供应商不会返回reasoning parts, 所以需要这个transformer
// Some models output malformed tags (missing opening tag, or using <thinking> instead of <think>)
object ThinkTagTransformer : OutputMessageTransformer {
    override suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages.map { message ->
            if (message.role == MessageRole.ASSISTANT && message.hasPart<UIMessagePart.Text>()) {
                message.copy(
                    parts = message.parts.flatMap { part ->
                        if (part is UIMessagePart.Text) {
                            transformTextPart(part)
                        } else {
                            listOf(part)
                        }
                    }
                )
            } else {
                message
            }
        }
    }

    private fun transformTextPart(part: UIMessagePart.Text): List<UIMessagePart> {
        val text = part.text

        // Case 1: Standard format - text contains <think> or <thinking> opening tag
        if (text.contains("<think>") || text.contains("<thinking>")) {
            val reasoning = THINKING_REGEX.find(text)?.groupValues?.getOrNull(1)?.trim() ?: ""
            val stripped = text.replace(THINKING_REGEX, "").trim()

            if (reasoning.isNotEmpty()) {
                val now = Clock.System.now()
                return listOf(
                    UIMessagePart.Reasoning(
                        reasoning = reasoning,
                        finishedAt = now,
                        createdAt = now,
                    ),
                    part.copy(text = stripped),
                )
            }
        }

        // Case 2: Orphaned closing tag - only </think> or </thinking> present (missing opening tag)
        if (text.contains("</think>") || text.contains("</thinking>")) {
            val orphanMatch = ORPHAN_CLOSE_TAG_REGEX.find(text)
            if (orphanMatch != null) {
                val reasoning = orphanMatch.groupValues.getOrNull(1)?.trim() ?: ""
                val stripped = text.replace(ORPHAN_CLOSE_TAG_REGEX, "").trim()

                if (reasoning.isNotEmpty()) {
                    val now = Clock.System.now()
                    return listOf(
                        UIMessagePart.Reasoning(
                            reasoning = reasoning,
                            finishedAt = now,
                            createdAt = now,
                        ),
                        part.copy(text = stripped),
                    )
                }
            }
        }

        // No transformation needed
        return listOf(part)
    }
}

