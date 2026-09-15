package me.rerere.rikkahub.data.ai.transformers

import android.util.Log
import io.pebbletemplates.pebble.PebbleEngine
import io.pebbletemplates.pebble.loader.Loader
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.toLocalDate
import me.rerere.rikkahub.utils.toLocalTime
import java.io.Reader
import java.io.StringReader
import java.io.StringWriter
import java.time.Instant

private const val TAG = "TemplateTransformer"
private const val DEFAULT_MESSAGE_TEMPLATE = "{{ message }}"

class TemplateTransformer(
    private val engine: PebbleEngine,
    private val settingsStore: SettingsStore
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val template = engine.getTemplate(ctx.assistant.id.toString())
        return messages.map { message ->
            message.copy(
                parts = message.parts.map { part ->
                    when (part) {
                        is UIMessagePart.Text -> {
                            val result = StringWriter()
                            template.evaluate(
                                result, mapOf(
                                    "message" to part.text,
                                    "role" to message.role.name.lowercase(),
                                    "time" to Instant.now().toLocalTime(),
                                    "date" to Instant.now().toLocalDate(),
                                )
                            )
                            val evaluatedText = result.toString()
                            // 防御：模板评估产出空字符串但原文本非空时，保留原文，
                            // 避免 messageTemplate 配置异常（如被清空）导致所有消息被掏空。
                            val finalText = if (evaluatedText.isBlank() && part.text.isNotBlank()) {
                                Log.w(
                                    TAG,
                                    "Template produced empty output for non-empty text " +
                                        "(assistantId=${ctx.assistant.id}); falling back to original text."
                                )
                                part.text
                            } else {
                                evaluatedText
                            }
                            part.copy(text = finalText)
                        }

                        else -> part
                    }
                }
            )
        }
    }
}

class AssistantTemplateLoader(private val settingsStore: SettingsStore) : Loader<String> {
    override fun getReader(cacheKey: String?): Reader? {
        val content = settingsStore.settingsFlow.value.assistants
            .find { it.id.toString() == cacheKey }?.messageTemplate
            ?: return null
        // messageTemplate 为空白时回退到默认模板，避免空模板把所有 Text part 评估成空串
        val effectiveContent = content.takeIf { it.isNotBlank() } ?: DEFAULT_MESSAGE_TEMPLATE
        return StringReader(effectiveContent)
    }

    override fun setCharset(charset: String?) {}

    override fun setPrefix(prefix: String?) {}

    override fun setSuffix(suffix: String?) {}

    override fun resolveRelativePath(
        relativePath: String?,
        anchorPath: String?
    ): String? {
        return relativePath
    }

    override fun createCacheKey(templateName: String?): String? {
        return templateName
    }

    override fun resourceExists(templateName: String?): Boolean {
        return settingsStore.settingsFlow.value.assistants.any { it.id.toString() == templateName }
    }
}
