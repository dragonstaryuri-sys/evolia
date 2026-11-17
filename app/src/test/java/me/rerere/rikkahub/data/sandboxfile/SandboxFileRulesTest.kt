package me.rerere.rikkahub.data.sandboxfile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxFileRulesTest {

    // ------------------------------------------------------------------
    // shouldSkipEntry：隐藏文件 / Python 缓存 / 临时产物
    // ------------------------------------------------------------------

    @Test
    fun `skip hidden files and pycache`() {
        assertTrue(SandboxFileRules.shouldSkipEntry(".hidden"))
        assertTrue(SandboxFileRules.shouldSkipEntry("."))
        assertTrue(SandboxFileRules.shouldSkipEntry("__pycache__"))
        assertTrue(SandboxFileRules.shouldSkipEntry("__PYCACHE__"))
        assertTrue(SandboxFileRules.shouldSkipEntry("module.pyc"))
        assertTrue(SandboxFileRules.shouldSkipEntry("module.pyo"))
        assertTrue(SandboxFileRules.shouldSkipEntry("output.tmp"))
        assertTrue(SandboxFileRules.shouldSkipEntry("partial.part"))
        assertTrue(SandboxFileRules.shouldSkipEntry(""))
        assertTrue(SandboxFileRules.shouldSkipEntry("   "))
    }

    @Test
    fun `keep normal user visible files`() {
        assertFalse(SandboxFileRules.shouldSkipEntry("result.png"))
        assertFalse(SandboxFileRules.shouldSkipEntry("data.csv"))
        assertFalse(SandboxFileRules.shouldSkipEntry("report.pdf"))
        // 大小写不敏感地匹配临时后缀
        assertTrue(SandboxFileRules.shouldSkipEntry("SCRIPT.PYC"))
    }

    // ------------------------------------------------------------------
    // 路径规范化与稳定 ID
    // ------------------------------------------------------------------

    @Test
    fun `normalize relative path`() {
        assertEquals("a/b.png", SandboxFileRules.normalizeRelativePath("a/b.png"))
        assertEquals("a/b.png", SandboxFileRules.normalizeRelativePath("/a/b.png"))
        assertEquals("a/b.png", SandboxFileRules.normalizeRelativePath("a\\b.png"))
        assertEquals("a/b.png", SandboxFileRules.normalizeRelativePath("/a//b.png/"))
    }

    @Test
    fun `stable id keeps case sensitivity`() {
        assertEquals(
            SandboxFileRules.buildStableFileId(SandboxFileSource.PYTHON_SANDBOX, "A/B.png"),
            SandboxFileRules.buildStableFileId(SandboxFileSource.PYTHON_SANDBOX, "A/B.png")
        )
        // 大小写敏感文件系统上两个不同文件不得撞号
        assertNotEquals(
            SandboxFileRules.buildStableFileId(SandboxFileSource.PYTHON_SANDBOX, "conv/A.png"),
            SandboxFileRules.buildStableFileId(SandboxFileSource.PYTHON_SANDBOX, "conv/a.png")
        )
    }

    @Test
    fun `stable id isolates sources`() {
        // 两个来源目录的同名相对路径不得撞号
        assertNotEquals(
            SandboxFileRules.buildStableFileId(SandboxFileSource.PYTHON_SANDBOX, "conv1/out.png"),
            SandboxFileRules.buildStableFileId(SandboxFileSource.GENERATED_IMAGE, "conv1/out.png")
        )
    }

    @Test
    fun `derive conversation id from first level dir`() {
        assertEquals("conv1", SandboxFileRules.deriveConversationId("conv1/out.png"))
        assertEquals("conv1", SandboxFileRules.deriveConversationId("conv1/sub/out.png"))
        assertNull(SandboxFileRules.deriveConversationId("direct_under_root.png"))
    }

    // ------------------------------------------------------------------
    // isPathInsideSandbox：路径安全（禁止穿越/根目录/兄弟目录前缀）
    // ------------------------------------------------------------------

    @Test
    fun `path inside sandbox accepted`() {
        val root = "/data/user/0/app/files/python_sandbox"
        assertTrue(
            SandboxFileRules.isPathInsideSandbox(
                root, "$root/conv1/out.png"
            )
        )
        assertTrue(
            SandboxFileRules.isPathInsideSandbox(
                root, "$root/conv1/sub/dir/deep.bin"
            )
        )
        // 带尾斜杠的根目录也应接受
        assertTrue(
            SandboxFileRules.isPathInsideSandbox(
                "$root/", "$root/conv1/out.png"
            )
        )
    }

    @Test
    fun `root itself and sibling prefix rejected`() {
        val root = "/data/user/0/app/files/python_sandbox"
        // 根目录本身不允许删除
        assertFalse(SandboxFileRules.isPathInsideSandbox(root, root))
        // 前缀撞车的兄弟目录（/python_sandbox2）不允许
        assertFalse(
            SandboxFileRules.isPathInsideSandbox(
                root, "/data/user/0/app/files/python_sandbox2/x.png"
            )
        )
    }

    @Test
    fun `path traversal rejected`() {
        val root = "/data/user/0/app/files/python_sandbox"
        // canonical 化后逃逸出根目录的路径
        assertFalse(
            SandboxFileRules.isPathInsideSandbox(
                root, "/data/user/0/app/files/upload/fake.png"
            )
        )
        assertFalse(
            SandboxFileRules.isPathInsideSandbox(
                root, "/data/user/0/app/files/python_sandbox_evil/x.png"
            )
        )
        // 空路径
        assertFalse(SandboxFileRules.isPathInsideSandbox(root, ""))
        assertFalse(SandboxFileRules.isPathInsideSandbox("", root))
    }

    // ------------------------------------------------------------------
    // categoryFromHeader / decideCategory：文件头优先分类
    // ------------------------------------------------------------------

    private val pngHeader = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )
    private val jpegHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
    private val gifHeader = "GIF89a".toByteArray()
    private val bmpHeader = byteArrayOf(0x42, 0x4D)
    private val webpHeader = byteArrayOf(
        0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00,
        0x57, 0x45, 0x42, 0x50
    )
    private val heicHeader = byteArrayOf(
        0x00, 0x00, 0x00, 0x20, 0x66, 0x74, 0x79, 0x70,
        0x68, 0x65, 0x69, 0x63
    )
    private val pdfHeader = "%PDF-1.7".toByteArray()
    private val zipHeader = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val textHeader = "hello world".toByteArray()

    @Test
    fun `image magic bytes identified as image`() {
        assertEquals(SandboxFileCategory.IMAGE, SandboxFileRules.categoryFromHeader(pngHeader))
        assertEquals(SandboxFileCategory.IMAGE, SandboxFileRules.categoryFromHeader(jpegHeader))
        assertEquals(SandboxFileCategory.IMAGE, SandboxFileRules.categoryFromHeader(gifHeader))
        assertEquals(SandboxFileCategory.IMAGE, SandboxFileRules.categoryFromHeader(bmpHeader))
        assertEquals(SandboxFileCategory.IMAGE, SandboxFileRules.categoryFromHeader(webpHeader))
        assertEquals(SandboxFileCategory.IMAGE, SandboxFileRules.categoryFromHeader(heicHeader))
    }

    @Test
    fun `non image magic bytes identified as other`() {
        assertEquals(SandboxFileCategory.OTHER, SandboxFileRules.categoryFromHeader(pdfHeader))
        assertEquals(SandboxFileCategory.OTHER, SandboxFileRules.categoryFromHeader(zipHeader))
        assertEquals(SandboxFileCategory.OTHER, SandboxFileRules.categoryFromHeader(textHeader))
    }

    @Test
    fun `unknown header returns null`() {
        assertNull(SandboxFileRules.categoryFromHeader(null))
        assertNull(SandboxFileRules.categoryFromHeader(byteArrayOf(0x01, 0x02)))
        assertNull(SandboxFileRules.categoryFromHeader(byteArrayOf()))
    }

    @Test
    fun `header takes priority over extension`() {
        // 扩展名声称是图片，内容是 ZIP → 其他文件（防伪装）
        assertEquals(
            SandboxFileCategory.OTHER,
            SandboxFileRules.decideCategory("png", "image/png", zipHeader)
        )
        // 扩展名未知，内容是图片 → 图片
        assertEquals(
            SandboxFileCategory.IMAGE,
            SandboxFileRules.decideCategory(null, null, pngHeader)
        )
    }

    @Test
    fun `fallback to extension and mime when header unknown`() {
        // 无头信息时按扩展名判断
        assertEquals(
            SandboxFileCategory.IMAGE,
            SandboxFileRules.decideCategory("png", "image/png", byteArrayOf(0x01, 0x02))
        )
        assertEquals(
            SandboxFileCategory.IMAGE,
            SandboxFileRules.decideCategory(null, "image/jpeg", null)
        )
        assertEquals(
            SandboxFileCategory.IMAGE,
            SandboxFileRules.decideCategory("heic", null, null)
        )
        // 非图片扩展名与 MIME → 其他文件
        assertEquals(
            SandboxFileCategory.OTHER,
            SandboxFileRules.decideCategory("pdf", "application/pdf", null)
        )
        // 完全无法识别 → 其他文件（不忽略）
        assertEquals(
            SandboxFileCategory.OTHER,
            SandboxFileRules.decideCategory(null, null, null)
        )
    }

    @Test
    fun `image mime helpers`() {
        assertTrue(SandboxFileRules.isImageMime("image/png"))
        assertFalse(SandboxFileRules.isImageMime("application/pdf"))
        assertFalse(SandboxFileRules.isImageMime(null))
        assertTrue(SandboxFileRules.isImageExtension("PNG"))
        assertTrue(SandboxFileRules.isImageExtension(".jpg"))
        assertFalse(SandboxFileRules.isImageExtension("pdf"))
        assertFalse(SandboxFileRules.isImageExtension(null))
    }
}
