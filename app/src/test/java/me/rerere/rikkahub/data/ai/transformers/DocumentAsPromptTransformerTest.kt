package me.rerere.rikkahub.data.ai.transformers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 附件转 Prompt 的容错测试（历史 bug：文件被「清理缓存」删除后，
 * DocumentAsPromptTransformer 裸读 file.readText() 抛 ENOENT，
 * 导致该会话每次发消息生成请求直接中断、助手无法回复）。
 */
class DocumentAsPromptTransformerTest {

    private fun tempTextFile(content: String): File =
        File.createTempFile("doc_test", ".txt").apply {
            writeText(content)
            deleteOnExit()
        }

    @Test
    fun `readable file produces prompt with content`() {
        val file = tempTextFile("hello attachment body")
        val prompt = buildDocumentPrompt("notes.txt", "text/plain", file)
        assertTrue(prompt.contains("## user sent a file: notes.txt"))
        assertTrue(prompt.contains("hello attachment body"))
        assertTrue(prompt.contains("<content>"))
    }

    @Test
    fun `missing file degrades to placeholder instead of throwing ENOENT`() {
        // 清理缓存删除 upload/ 后的典型场景：消息里的 file:// 引用指向不存在的文件
        val missing = File(File(System.getProperty("java.io.tmpdir")), "no_such_file_${System.nanoTime()}.txt")
        val prompt = buildDocumentPrompt("report.pdf", "application/pdf", missing)
        assertTrue(prompt.contains("附件内容无法读取"))
        assertTrue(prompt.contains("report.pdf"))
        // 占位提示不携带 <content> 模板（避免模型把空壳当真实附件）
        assertFalse(prompt.contains("<content>"))
    }

    @Test
    fun `unreadable file degrades to placeholder`() {
        // 用目录冒充文件：readText() 必抛 IOException，验证解析/读取失败同样被兜住
        val dir = File(System.getProperty("java.io.tmpdir"), "dir_as_file_${System.nanoTime()}").apply {
            mkdirs()
            deleteOnExit()
        }
        val prompt = buildDocumentPrompt("broken.txt", "text/plain", dir)
        assertTrue(prompt.contains("附件内容无法读取"))
        assertFalse(prompt.contains("<content>"))
    }

    @Test
    fun `pdf branch failure also degrades instead of throwing`() {
        val missing = File(File(System.getProperty("java.io.tmpdir")), "no_such_pdf_${System.nanoTime()}")
        val prompt = buildDocumentPrompt("doc.pdf", "application/pdf", missing)
        assertTrue(prompt.contains("附件内容无法读取"))
    }
}
