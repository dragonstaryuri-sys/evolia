package me.rerere.rikkahub.data.ai.transformers

import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.document.DocxParser
import me.rerere.document.PdfParser
import java.io.File

object DocumentAsPromptTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return withContext(Dispatchers.IO) {
            messages.map { message ->
                message.copy(
                    parts = message.parts.toMutableList().apply {
                        val documents = filterIsInstance<UIMessagePart.Document>()
                        if (documents.isNotEmpty()) {
                            documents.forEach { document ->
                                val file = document.url.toUri().toFile()
                                // 附件文件可能已被「清理缓存」删除或损坏（消息里的 file:// 引用是永久性的），
                                // 读取失败必须降级为占位提示而不是抛异常——裸抛会中断整次生成，
                                // 表现为「发送消息就报 ENOENT，助手无法回复」且该会话每次都复发
                                val prompt = buildDocumentPrompt(document.fileName, document.mime, file)
                                add(0, UIMessagePart.Text(prompt))
                            }
                        }
                    }
                )
            }
        }
    }

    private fun parsePdfAsText(file: File): String {
        return PdfParser.parserPdf(file)
    }

    private fun parseDocxAsText(file: File): String {
        return DocxParser.parse(file)
    }
}

/**
 * 构建附件内容的 Prompt（纯 JVM 函数，可单测）。
 * 文件缺失 / 解析失败时返回占位提示，不抛异常——保证单条附件损坏不影响整次生成。
 */
internal fun buildDocumentPrompt(fileName: String, mime: String, file: File): String {
    val content = runCatching {
        when (mime) {
            "application/pdf" -> PdfParser.parserPdf(file)
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> DocxParser.parse(file)
            else -> file.readText()
        }
    }.getOrElse {
        return "## user sent a file: $fileName\n（附件内容无法读取：文件已被清理或损坏，本次对话不包含该附件的内容）"
    }
    return """
                  ## user sent a file: $fileName
                  <content>
                  ```
                  $content
                  ```
                  </content>
                  """.trimMargin()
}
