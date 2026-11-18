package me.rerere.rikkahub.data.extensions

import me.rerere.rikkahub.common.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.runBlocking
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ZIP 扩展包读取器安全单测（详设 5.5）：
 * ZIP Slip / 重复路径 / 压缩炸弹 / 缺 manifest / 正常解压与 Manifest 解析。
 */
class ZipPackageReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val manifestJson = """
    {
      "manifest_version": 1,
      "app_id": "zip_test",
      "name": "ZIP 测试",
      "extension_type": "tool",
      "version": "1.0.0",
      "tools": [
        {"tool_id": "noop", "name": "空操作", "entry": "tools/noop.js"}
      ]
    }
    """.trimIndent()

    private fun buildZip(target: File, entries: List<Pair<String, String>>) {
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }

    @Test
    fun `normal package extracts and parses manifest`() {
        val zip = tmp.newFile("good.evolia")
        buildZip(zip, listOf(
            "manifest.json" to manifestJson,
            "tools/noop.js" to "return {};",
        ))
        val target = tmp.newFolder("staging")
        val content = runBlocking { ZipPackageReader().extractTo(zip, target) }

        assertEquals("zip_test", content.manifest.appId)
        assertTrue(content.packageHash.isNotBlank())
        assertTrue(content.manifestHash.isNotBlank())
        assertEquals(2, content.fileCount)
        assertTrue(File(target, "manifest.json").isFile)
        assertTrue(File(target, "tools/noop.js").isFile)
    }

    @Test
    fun `zip slip path traversal rejected`() {
        val zip = tmp.newFile("evil.evolia")
        buildZip(zip, listOf(
            "manifest.json" to manifestJson,
            "../evil.txt" to "pwned",
        ))
        val target = tmp.newFolder("staging")
        runCatching { runBlocking { ZipPackageReader().extractTo(zip, target) } }.let { result ->
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("不安全"))
        }
        // 包外文件不存在
        assertFalse(tmp.root.resolve("evil.txt").exists())
    }

    @Test
    fun `absolute and backslash entries rejected`() {
        val zip = tmp.newFile("abs.evolia")
        buildZip(zip, listOf(
            "manifest.json" to manifestJson,
            "/etc/abs.txt" to "x",
        ))
        runCatching { runBlocking { ZipPackageReader().extractTo(zip, tmp.newFolder("s1")) } }
            .onSuccess { assertTrue("绝对路径条目应被拒绝", false) }
            .onFailure { /* expected */ }

        val zip2 = tmp.newFile("bs.evolia")
        buildZip(zip2, listOf(
            "manifest.json" to manifestJson,
            "a\\b.txt" to "x",
        ))
        runCatching { runBlocking { ZipPackageReader().extractTo(zip2, tmp.newFolder("s2")) } }
            .onSuccess { assertTrue("反斜杠条目应被拒绝", false) }
            .onFailure { /* expected */ }
    }

    /**
     * 手写 raw ZIP（Java ZipOutputStream 自身拒绝重复条目，恶意包需手工构造）。
     * 仅支持 STORED 条目。
     */
    private fun buildRawZipWithDuplicates(target: File, entries: List<Pair<String, String>>) {
        val buf = java.io.ByteArrayOutputStream()
        val le = { v: Int -> java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(v).array() }
        val leShort = { v: Int -> java.nio.ByteBuffer.allocate(2).order(java.nio.ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array() }
        val offsetByName = mutableListOf<Int>()
        val nameBytesList = entries.map { it.first.toByteArray(Charsets.UTF_8) }
        entries.forEachIndexed { index, (name, content) ->
            val data = content.toByteArray(Charsets.UTF_8)
            val nameBytes = nameBytesList[index]
            val crc = java.util.zip.CRC32().apply { update(data) }
            offsetByName.add(buf.size())
            buf.write(le(0x04034b50))
            buf.write(leShort(20)); buf.write(leShort(0)); buf.write(leShort(0))
            buf.write(leShort(0)); buf.write(leShort(0x21))
            buf.write(le(crc.value.toInt()))
            buf.write(le(data.size)); buf.write(le(data.size))
            buf.write(leShort(nameBytes.size)); buf.write(leShort(0))
            buf.write(nameBytes)
            buf.write(data)
        }
        val cdStart = buf.size()
        entries.forEachIndexed { index, (name, content) ->
            val data = content.toByteArray(Charsets.UTF_8)
            val nameBytes = nameBytesList[index]
            val crc = java.util.zip.CRC32().apply { update(data) }
            buf.write(le(0x02014b50))
            buf.write(leShort(20)); buf.write(leShort(20)); buf.write(leShort(0)); buf.write(leShort(0))
            buf.write(leShort(0)); buf.write(leShort(0x21))
            buf.write(le(crc.value.toInt()))
            buf.write(le(data.size)); buf.write(le(data.size))
            buf.write(leShort(nameBytes.size)); buf.write(leShort(0)); buf.write(leShort(0))
            buf.write(leShort(0)); buf.write(leShort(0)); buf.write(le(0))
            buf.write(le(offsetByName[index]))
            buf.write(nameBytes)
        }
        val cdSize = buf.size() - cdStart
        buf.write(le(0x06054b50))
        buf.write(leShort(0)); buf.write(leShort(0))
        buf.write(leShort(entries.size)); buf.write(leShort(entries.size))
        buf.write(le(cdSize)); buf.write(le(cdStart))
        buf.write(leShort(0))
        target.writeBytes(buf.toByteArray())
    }

    @Test
    fun `duplicate entry paths rejected`() {
        val zip = tmp.newFile("dup.evolia")
        buildRawZipWithDuplicates(zip, listOf(
            "manifest.json" to manifestJson,
            "tools/noop.js" to "a;",
            "tools/noop.js" to "b;",
        ))
        val result = runCatching { runBlocking { ZipPackageReader().extractTo(zip, tmp.newFolder("staging")) } }
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("重复"))
    }

    @Test
    fun `missing manifest rejected`() {
        val zip = tmp.newFile("nomanifest.evolia")
        buildZip(zip, listOf("tools/noop.js" to "return {};"))
        val result = runCatching { runBlocking { ZipPackageReader().extractTo(zip, tmp.newFolder("staging")) } }
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("manifest"))
    }

    @Test
    fun `compressed bomb ratio rejected`() {
        // 真实压缩炸弹：20MB 全零 DEFLATED 后 ≈20KB，解压比例远超上限
        val zip = tmp.newFile("bomb.evolia")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            out.putNextEntry(ZipEntry("manifest.json"))
            out.write(manifestJson.toByteArray())
            out.closeEntry()

            out.putNextEntry(ZipEntry("bomb.bin"))
            out.write(ByteArray(20 * 1024 * 1024))
            out.closeEntry()
        }
        val result = runCatching { runBlocking { ZipPackageReader().extractTo(zip, tmp.newFolder("staging")) } }
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("解压比例"))
    }

    @Test
    fun `sanitizeEntryName rejects unsafe names`() {
        assertNull(ZipPackageReader.sanitizeEntryName("../a"))
        assertNull(ZipPackageReader.sanitizeEntryName("/abs"))
        assertNull(ZipPackageReader.sanitizeEntryName("a\\b"))
        assertNull(ZipPackageReader.sanitizeEntryName("C:/x"))
        assertNull(ZipPackageReader.sanitizeEntryName("a/./b"))
        assertNull(ZipPackageReader.sanitizeEntryName("a//b"))
        assertNull(ZipPackageReader.sanitizeEntryName(""))
        assertEquals("web/index.html", ZipPackageReader.sanitizeEntryName("web/index.html"))
        assertEquals("a/b.txt", ZipPackageReader.sanitizeEntryName("a/b.txt"))
    }

    @Test
    fun `invalid manifest json gives friendly error`() {
        val zip = tmp.newFile("badjson.evolia")
        buildZip(zip, listOf("manifest.json" to "{ not json"))
        val result = runCatching { runBlocking { ZipPackageReader().extractTo(zip, tmp.newFolder("staging")) } }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ExtensionPackageException)
    }

    @Test
    fun `parseManifestFromDirectory works for drafts`() {
        val dir = tmp.newFolder("draft")
        File(dir, "manifest.json").writeText(manifestJson)
        val manifest = ZipPackageReader().parseManifestFromDirectory(dir)
        assertEquals("zip_test", manifest.appId)
        assertNotNull(manifest)
    }
}
