package me.rerere.rikkahub.data.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 草稿三层校验器单测（2026-11-20 补充需求第四章）。
 * QuickJS 语法检查在 JVM 不可用，统一注入 fake checker / null。
 */
class ExtensionDraftValidatorTest {

    private fun toolManifest(entry: String = "main.js") = ExtensionManifest(
        manifestVersion = 1,
        appId = "test_tool_10001",
        name = "测试工具",
        extensionType = ExtensionType.TOOL,
        version = "1.0.0",
        tools = listOf(ManifestTool(toolId = "main", name = "主工具", entry = entry)),
    )

    private fun miniAppManifest(entry: String = "web/index.html") = ExtensionManifest(
        manifestVersion = 1,
        appId = "test_app_10001",
        name = "测试小应用",
        extensionType = ExtensionType.MINI_APP,
        version = "1.0.0",
        frontend = ManifestFrontend(entry = entry),
    )

    private fun validate(
        manifest: ExtensionManifest,
        files: Map<String, String>,
        jsSyntaxCheck: ExtensionDraftValidator.JsSyntaxChecker? = null,
    ): ExtensionDraftValidator.Result = ExtensionDraftValidator.validate(
        manifest = manifest,
        listFiles = { files.keys.toList() },
        readFile = { files[it]?.encodeToByteArray() },
        fileExists = { files.containsKey(it) },
        parseSchemaFile = { null },
        jsSyntaxCheck = jsSyntaxCheck,
    )

    // ---------- SDK 调用扫描（纯函数） ----------

    @Test
    fun `sdk usage scan maps permissions`() {
        val scan = ExtensionDraftValidator.scanSdkUsage(
            """
            const a = evolia.storage.get({collection: "notes", id: "1"});
            evolia.storage.insert({collection: "notes", data: {}});
            evolia.network.request({url: "https://example.com"});
            evolia.ui.toast({message: "hi"});
            """.trimIndent()
        )
        assertEquals(
            setOf(
                ExtensionPermissions.STORAGE_READ,
                ExtensionPermissions.STORAGE_WRITE,
                ExtensionPermissions.NETWORK_REQUEST,
                ExtensionPermissions.UI_TOAST,
            ),
            scan.usedPermissions,
        )
        assertTrue(scan.unknownApis.isEmpty())
    }

    @Test
    fun `navigation close needs no permission`() {
        val scan = ExtensionDraftValidator.scanSdkUsage("evolia.navigation.close();")
        assertTrue(scan.usedPermissions.isEmpty())
        assertTrue(scan.unknownApis.isEmpty())
    }

    @Test
    fun `unknown sdk api reported`() {
        val scan = ExtensionDraftValidator.scanSdkUsage("evolia.device.lock();")
        assertTrue(scan.usedPermissions.isEmpty())
        assertEquals(listOf("device.lock"), scan.unknownApis)
    }

    // ---------- 本地引用提取（纯函数） ----------

    @Test
    fun `local asset references extracted`() {
        val refs = ExtensionDraftValidator.scanLocalAssetReferences(
            """
            <html>
              <script src="web/app.js"></script>
              <link rel="stylesheet" href="css/style.css">
              <img src='img/logo.png'>
            </html>
            """.trimIndent()
        )
        assertEquals(listOf("web/app.js", "css/style.css", "img/logo.png"), refs)
    }

    @Test
    fun `remote and inline references excluded`() {
        val refs = ExtensionDraftValidator.scanLocalAssetReferences(
            """
            <script src="https://cdn.example.com/lib.js"></script>
            <script src="//cdn.example.com/lib.js"></script>
            <img src="data:image/png;base64,AAAA">
            <a href="#top">top</a>
            <script src="local.js"></script>
            """.trimIndent()
        )
        assertEquals(listOf("local.js"), refs)
    }

    // ---------- 内容哈希（纯函数） ----------

    @Test
    fun `content hash stable across order and excludes workspace meta`() {
        val readFile = { files: Map<String, String> -> { path: String -> files[path]?.encodeToByteArray() } }
        val filesA = mapOf("manifest.json" to "{}", "main.js" to "console.log(1)", "workspace.json" to "{}")
        val filesB = mapOf("main.js" to "console.log(1)", "manifest.json" to "{}")
        val hashA = ExtensionDraftValidator.computeContentHash(filesA.keys.toList(), readFile(filesA))
        val hashB = ExtensionDraftValidator.computeContentHash(filesB.keys.toList(), readFile(filesB))
        // workspace.json 不参与哈希（元数据自身随校验结果写入）
        assertEquals(hashA, hashB)
        // 列表顺序不影响结果
        val hashA2 = ExtensionDraftValidator.computeContentHash(
            filesA.keys.reversed(),
            readFile(filesA),
        )
        assertEquals(hashA, hashA2)
    }

    @Test
    fun `content hash changes with content`() {
        val read1 = { _: String -> "a".encodeToByteArray() }
        val read2 = { _: String -> "b".encodeToByteArray() }
        val h1 = ExtensionDraftValidator.computeContentHash(listOf("main.js"), read1)
        val h2 = ExtensionDraftValidator.computeContentHash(listOf("main.js"), read2)
        assertNotEquals(h1, h2)
    }

    // ---------- 三层校验编排 ----------

    @Test
    fun `structure errors are blocking`() {
        val manifest = toolManifest().copy(name = "") // name 缺失
        val result = validate(manifest, files = mapOf("main.js" to "console.log(1)"))
        assertEquals(ExtensionDraftValidator.Level.FAIL, result.level)
        assertTrue(result.errors.any { it.message.contains("name") })
    }

    @Test
    fun `valid tool extension passes with fake checker`() {
        val result = validate(
            toolManifest(),
            files = mapOf("main.js" to "function main(args) { return args }"),
            jsSyntaxCheck = ExtensionDraftValidator.JsSyntaxChecker { _, _ -> null },
        )
        assertEquals(ExtensionDraftValidator.Level.PASS, result.level)
        assertTrue(result.errors.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `js syntax error is blocking`() {
        val result = validate(
            toolManifest(),
            files = mapOf("main.js" to "function main( { return"),
            jsSyntaxCheck = ExtensionDraftValidator.JsSyntaxChecker { _, file -> "JS 语法错误：$file" },
        )
        assertEquals(ExtensionDraftValidator.Level.FAIL, result.level)
        assertTrue(result.errors.any { it.file == "main.js" && it.message.contains("语法错误") })
    }

    @Test
    fun `undeclared permission usage is warning not blocking`() {
        val result = validate(
            toolManifest(),
            files = mapOf("main.js" to "evolia.network.request({url: 'https://a.com'})"),
            jsSyntaxCheck = ExtensionDraftValidator.JsSyntaxChecker { _, _ -> null },
        )
        assertEquals(ExtensionDraftValidator.Level.WARN, result.level)
        assertTrue(result.warnings.any { it.message.contains(ExtensionPermissions.NETWORK_REQUEST) })
    }

    @Test
    fun `unknown api usage is warning`() {
        val result = validate(
            toolManifest(),
            files = mapOf("main.js" to "evolia.unknown.call();"),
            jsSyntaxCheck = ExtensionDraftValidator.JsSyntaxChecker { _, _ -> null },
        )
        assertEquals(ExtensionDraftValidator.Level.WARN, result.level)
        assertTrue(result.warnings.any { it.message.contains("evolia.unknown.call") })
    }

    @Test
    fun `mini app missing referenced asset is blocking`() {
        val result = validate(
            miniAppManifest(),
            files = mapOf("web/index.html" to "<script src=\"web/app.js\"></script>"),
        )
        assertEquals(ExtensionDraftValidator.Level.FAIL, result.level)
        assertTrue(result.errors.any { it.message.contains("web/app.js") })
    }

    @Test
    fun `mini app undeclared storage usage is warning`() {
        val result = validate(
            miniAppManifest(),
            files = mapOf(
                "web/index.html" to "<html><body>ok</body></html>",
                "web/app.js" to "evolia.storage.get({collection: 'notes', id: '1'});",
            ),
            jsSyntaxCheck = ExtensionDraftValidator.JsSyntaxChecker { _, _ -> null },
        )
        assertEquals(ExtensionDraftValidator.Level.WARN, result.level)
        assertTrue(result.warnings.any { it.message.contains(ExtensionPermissions.STORAGE_READ) })
    }

    // ---------- 版本自动管理归一（ExtensionCreator 纯函数部分） ----------

    @Test
    fun `manifest version normalized to managed version`() {
        val content = """
            {"manifest_version":1,"app_id":"a_b","name":"x","version":"1.0.0"}
        """.trimIndent()
        val normalized = ExtensionCreator.normalizeManifestVersion(content, "1.0.1")
        assertTrue(normalized!!.contains("\"version\": \"1.0.1\""))
        assertFalse(normalized.contains("\"version\": \"1.0.0\""))
    }

    @Test
    fun `manifest version normalization skips matching version`() {
        val content = """{"version":"1.0.1","name":"x"}"""
        assertNull(ExtensionCreator.normalizeManifestVersion(content, "1.0.1"))
        // 无受管版本（旧草稿无元数据）时不动原文
        assertNull(ExtensionCreator.normalizeManifestVersion(content, null))
    }

    @Test
    fun `manifest version rewrite preserves other fields`() {
        val content = """{"version":"1.0.0","app_id":"a_b","description":"保留","tools":[]}"""
        val rewritten = ExtensionCreator.rewriteManifestVersionJson(content, "1.2.4")!!
        assertTrue(rewritten.contains("\"app_id\": \"a_b\""))
        assertTrue(rewritten.contains("保留"))
        assertTrue(rewritten.contains("\"version\": \"1.2.4\""))
        assertNull(ExtensionCreator.rewriteManifestVersionJson("not json", "1.0.1"))
    }

    // ---------- 导出二次开发权限改写（纯函数） ----------

    @Test
    fun `export manifest flag true`() {
        val manifest = toolManifest()
        val exported = ExtensionImportExport.manifestWithDevelopmentFlag(manifest, allowModification = true)
        assertTrue(exported.development?.allowModification == true)
        // 其余字段保留
        assertEquals(manifest.appId, exported.appId)
        assertEquals(manifest.tools.size, exported.tools.size)
    }

    @Test
    fun `export manifest flag false and default allowed`() {
        val manifest = toolManifest()
        val exported = ExtensionImportExport.manifestWithDevelopmentFlag(manifest, allowModification = false)
        assertFalse(exported.isModificationAllowed)
        // 旧包无 development 字段 → 默认允许（历史拓展零迁移保持可编辑）
        val legacy = toolManifest().copy(development = null)
        assertTrue(legacy.isModificationAllowed)
    }

    @Test
    fun `increment patch base cases`() {
        assertEquals("1.0.1", ExtensionRules.incrementPatchVersion("1.0.0"))
        assertNull(ExtensionRules.incrementPatchVersion("bad"))
    }
}
