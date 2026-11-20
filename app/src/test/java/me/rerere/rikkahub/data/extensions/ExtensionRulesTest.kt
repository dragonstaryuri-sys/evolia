package me.rerere.rikkahub.data.extensions

import me.rerere.rikkahub.common.JsonInstant
import me.rerere.rikkahub.core.data.db.entity.ExtensionEntity
import me.rerere.rikkahub.core.data.db.entity.ExtensionPermissionEntity
import me.rerere.rikkahub.core.data.db.entity.ExtensionToolEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扩展纯决策规则单测：版本比较 / 安装判定 / 更新配置合并 / 崩溃恢复决策 /
 * 工具可用性判定（详设 8.4 / 8.5 / 8.3 / 13.3）。
 */
class ExtensionRulesTest {

    // ---------- 版本比较 ----------

    @Test
    fun `semver comparison`() {
        assertEquals(-1, ExtensionRules.compareVersions("1.0.0", "1.0.1"))
        assertEquals(0, ExtensionRules.compareVersions("1.0.0", "1.0.0"))
        assertEquals(1, ExtensionRules.compareVersions("1.2.0", "1.1.9"))
        assertEquals(1, ExtensionRules.compareVersions("2.0.0", "1.9.9"))
        // 数值逐段比较：10 > 9（非字典序）
        assertEquals(1, ExtensionRules.compareVersions("1.10.0", "1.9.0"))
        assertEquals(-1, ExtensionRules.compareVersions("1.9.0", "1.10.0"))
    }

    @Test
    fun `prerelease lower than release`() {
        assertEquals(-1, ExtensionRules.compareVersions("1.0.0-alpha", "1.0.0"))
        assertEquals(-1, ExtensionRules.compareVersions("1.0.0-alpha", "1.0.0-beta"))
        assertEquals(-1, ExtensionRules.compareVersions("1.0.0-alpha.1", "1.0.0-alpha.2"))
        // 数字段低于字母段
        assertEquals(-1, ExtensionRules.compareVersions("1.0.0-1", "1.0.0-alpha"))
    }

    @Test
    fun `build metadata ignored`() {
        assertEquals(0, ExtensionRules.compareVersions("1.0.0+build1", "1.0.0+build2"))
    }

    @Test
    fun `invalid versions treated as zero`() {
        assertEquals(0, ExtensionRules.compareVersions("abc", "x.y.z"))
        assertEquals(1, ExtensionRules.compareVersions("1.0.0", ""))
    }

    // ---------- 安装判定 ----------

    private fun entity(
        type: String = "mini_app",
        version: String = "1.0.0",
    ) = ExtensionEntity(
        appId = "personal_forum",
        name = "个人论坛",
        description = "",
        extensionType = type,
        version = version,
        sourceType = ExtensionSourceType.LOCAL_IMPORT,
        sourceUri = null,
        createdOrigin = ExtensionSourceType.LOCAL_CREATED,
        manifestVersion = 1,
        installPath = "extensions/installed/personal_forum",
        packageHash = "x",
        createdAt = 0,
        updatedAt = 0,
        installedAt = 0,
    )

    @Test
    fun `install decisions`() {
        assertEquals(
            ExtensionRules.InstallDecision.FRESH_INSTALL,
            ExtensionRules.decideInstallAction(null, "mini_app", "1.0.0")
        )
        assertEquals(
            ExtensionRules.InstallDecision.UPDATE,
            ExtensionRules.decideInstallAction(entity(version = "1.0.0"), "mini_app", "1.1.0")
        )
        assertEquals(
            ExtensionRules.InstallDecision.SAME_VERSION,
            ExtensionRules.decideInstallAction(entity(version = "1.0.0"), "mini_app", "1.0.0")
        )
        assertEquals(
            ExtensionRules.InstallDecision.DOWNGRADE_BLOCKED,
            ExtensionRules.decideInstallAction(entity(version = "2.0.0"), "mini_app", "1.9.0")
        )
        assertEquals(
            ExtensionRules.InstallDecision.TYPE_CONFLICT,
            ExtensionRules.decideInstallAction(entity(type = "mini_app"), "tool", "3.0.0")
        )
    }

    // ---------- 更新配置合并 ----------

    @Test
    fun `mergeToolConfigsOnUpdate keeps existing and drops removed`() {
        val now = 1000L
        val old = listOf(
            ExtensionToolEntity(appId = "a", toolId = "keep_me", enable = false,
                customDescription = "自定义", createdAt = 1, updatedAt = 1),
            ExtensionToolEntity(appId = "a", toolId = "removed_tool", enable = true,
                customDescription = null, createdAt = 1, updatedAt = 1),
        )
        val merged = ExtensionRules.mergeToolConfigsOnUpdate(old, setOf("keep_me", "new_tool"), now)
        // 只保留仍存在的 Tool 配置（新 Tool 由 Installer 生成默认配置）
        assertEquals(1, merged.size)
        assertEquals("keep_me", merged[0].toolId)
        assertFalse(merged[0].enable)
        assertEquals("自定义", merged[0].customDescription)
    }

    @Test
    fun `mergePermissionsOnUpdate keeps granted and adds new as ungranted`() {
        val now = 1000L
        val old = listOf(
            ExtensionPermissionEntity(appId = "a", permission = "storage.read", granted = true, createdAt = 1, updatedAt = 1),
            ExtensionPermissionEntity(appId = "a", permission = "ui.toast", granted = true, createdAt = 1, updatedAt = 1),
        )
        val merged = ExtensionRules.mergePermissionsOnUpdate(
            "a", old, listOf("storage.read", "network.request"), now
        )
        // ui.toast 已不在新版本声明中 → 丢弃；network.request 为新增 → granted=false
        assertEquals(2, merged.size)
        assertTrue(merged.first { it.permission == "storage.read" }.granted)
        assertFalse(merged.first { it.permission == "network.request" }.granted)
        assertTrue(merged.none { it.permission == "ui.toast" })
    }

    @Test
    fun `mergePermissionsOnUpdate grantNew authorizes new permissions`() {
        val now = 1000L
        val old = listOf(
            ExtensionPermissionEntity(appId = "a", permission = "storage.read", granted = true, createdAt = 1, updatedAt = 1),
        )
        val merged = ExtensionRules.mergePermissionsOnUpdate(
            "a", old, listOf("storage.read", "network.request"), now, grantNew = true
        )
        // 既有授权保留；用户已在安装弹窗中同意 → 新增权限随安装直接授权
        assertTrue(merged.first { it.permission == "storage.read" }.granted)
        assertTrue(merged.first { it.permission == "network.request" }.granted)
    }

    @Test
    fun `permissionsRequiringGrant returns newly declared only`() {
        // 全新安装：全部声明权限（manifest 顶层 + 工具级，去重）都需申请
        assertEquals(
            listOf("storage.read", "network.request", "ui.toast"),
            ExtensionRules.permissionsRequiringGrant(
                previouslyDeclared = emptySet(),
                manifestPermissions = listOf("storage.read", "network.request"),
                toolPermissions = listOf("network.request", "ui.toast"),
            )
        )
        // 更新：只弹新增权限
        assertEquals(
            listOf("ui.toast"),
            ExtensionRules.permissionsRequiringGrant(
                previouslyDeclared = setOf("storage.read", "network.request"),
                manifestPermissions = listOf("storage.read", "network.request"),
                toolPermissions = listOf("ui.toast"),
            )
        )
        // 无新增 → 空
        assertTrue(
            ExtensionRules.permissionsRequiringGrant(
                previouslyDeclared = setOf("storage.read"),
                manifestPermissions = listOf("storage.read"),
                toolPermissions = emptyList(),
            ).isEmpty()
        )
    }

    // ---------- 崩溃恢复决策 ----------

    @Test
    fun `recovery decisions by operation state`() {
        // 切换前中断 → 仅清理 staging
        assertEquals(
            ExtensionRules.RecoveryAction.CLEANUP_STAGING,
            ExtensionRules.decideRecovery("UPDATE", ExtensionInstallOperationState.PREPARING, true, true, true)
        )
        assertEquals(
            ExtensionRules.RecoveryAction.CLEANUP_STAGING,
            ExtensionRules.decideRecovery("INSTALL", ExtensionInstallOperationState.FILES_STAGED, false, false, false)
        )
        // UPDATE 切换后中断 → 恢复旧版本
        assertEquals(
            ExtensionRules.RecoveryAction.RESTORE_OLD_VERSION,
            ExtensionRules.decideRecovery("UPDATE", ExtensionInstallOperationState.FILES_SWITCHED, true, true, true)
        )
        // UPDATE 切换后中断且 backup 丢失但新目录存在 → 仍恢复（用现存目录判断）
        assertEquals(
            ExtensionRules.RecoveryAction.RESTORE_OLD_VERSION,
            ExtensionRules.decideRecovery("UPDATE", ExtensionInstallOperationState.ROLLBACK_REQUIRED, true, false, true)
        )
        // INSTALL 切换后中断 → 删除新目录
        assertEquals(
            ExtensionRules.RecoveryAction.REMOVE_NEW_INSTALL,
            ExtensionRules.decideRecovery("INSTALL", ExtensionInstallOperationState.FILES_SWITCHED, false, false, true)
        )
        // DB 已提交 → 保留新版本仅清理
        assertEquals(
            ExtensionRules.RecoveryAction.FINISH_CLEANUP,
            ExtensionRules.decideRecovery("UPDATE", ExtensionInstallOperationState.DATABASE_COMMITTED, true, true, true)
        )
    }

    // ---------- 工具可用性（三层权限） ----------

    private fun available(
        installed: Boolean = true,
        extEnabled: Boolean = true,
        toolEnable: Boolean? = true,
        perms: Boolean = true,
        isMiniAppTool: Boolean = false,
        assistantEnabled: Boolean = false,
    ) = ExtensionRules.isExtensionToolAvailable(
        installed, extEnabled, toolEnable, perms, isMiniAppTool, assistantEnabled
    )

    @Test
    fun `tool availability matrix`() {
        // 2026-11-19：Mini App 工具与独立 Tool Extension 均需助手级授权
        //（目录注入与执行路径使用同一判定，未授权工具的任何信息不进上下文）
        assertTrue(available(assistantEnabled = true))
        assertFalse(available(assistantEnabled = false))
        assertFalse(available(isMiniAppTool = true, assistantEnabled = false))
        assertTrue(available(isMiniAppTool = true, assistantEnabled = true))
        // 各层关闭都不行
        assertFalse(available(extEnabled = false, assistantEnabled = true))
        assertFalse(available(toolEnable = false, assistantEnabled = true))
        assertFalse(available(toolEnable = null, assistantEnabled = true))
        assertFalse(available(perms = false, assistantEnabled = true))
        assertFalse(available(installed = false, assistantEnabled = true))
    }

    @Test
    fun `effectiveDescription prefers custom`() {
        assertEquals("自定义", ExtensionRules.effectiveDescription("自定义", "原始"))
        assertEquals("原始", ExtensionRules.effectiveDescription(null, "原始"))
        assertEquals("原始", ExtensionRules.effectiveDescription("  ", "原始"))
    }

    // ---------- Manifest 路径安全 ----------

    @Test
    fun `safe relative path checks`() {
        assertTrue(ManifestValidator.isSafeRelativePath("web/index.html"))
        assertTrue(ManifestValidator.isSafeRelativePath("tools/get_weather.js"))
        assertFalse(ManifestValidator.isSafeRelativePath("../secret.txt"))
        assertFalse(ManifestValidator.isSafeRelativePath("a/../b"))
        assertFalse(ManifestValidator.isSafeRelativePath("/etc/passwd"))
        assertFalse(ManifestValidator.isSafeRelativePath("C:\\windows\\system32"))
        assertFalse(ManifestValidator.isSafeRelativePath("a\\b"))
        assertFalse(ManifestValidator.isSafeRelativePath("a/b/"))
        assertFalse(ManifestValidator.isSafeRelativePath("./x"))
        assertFalse(ManifestValidator.isSafeRelativePath(""))
        // 段内空格是合法文件名（assets 常见），允许；仅拒绝首尾带空格的段
        assertTrue(ManifestValidator.isSafeRelativePath("a b.txt"))
        assertFalse(ManifestValidator.isSafeRelativePath(" leading"))
    }

    // ---------- Manifest 校验 ----------

    private fun manifestJson(json: String) = JsonInstant.decodeFromString<ExtensionManifest>(json)

    private val validWeatherManifest = """
    {
      "manifest_version": 1,
      "app_id": "weather_tools",
      "name": "天气工具",
      "description": "为 Agent 提供天气查询能力",
      "extension_type": "tool",
      "version": "1.0.0",
      "tools": [
        {
          "tool_id": "get_weather",
          "name": "查询天气",
          "description": "查询指定城市的天气信息",
          "entry": "tools/get_weather.js",
          "permissions": ["network.request"]
        }
      ],
      "permissions": ["network.request"]
    }
    """.trimIndent()

    @Test
    fun `valid tool manifest passes`() {
        val manifest = manifestJson(validWeatherManifest)
        val errors = ManifestValidator.validate(
            manifest,
            fileExists = { it == "tools/get_weather.js" },
        )
        assertTrue(errors.joinToString("\n"), errors.isEmpty())
    }

    @Test
    fun `invalid app_id rejected`() {
        val manifest = manifestJson(validWeatherManifest).copy(appId = "9Bad-Id")
        val errors = ManifestValidator.validate(manifest, fileExists = { true })
        assertTrue(errors.any { it.contains("app_id") })
    }

    @Test
    fun `invalid version rejected`() {
        val manifest = manifestJson(validWeatherManifest).copy(version = "1.0")
        val errors = ManifestValidator.validate(manifest, fileExists = { true })
        assertTrue(errors.any { it.contains("version") })
    }

    @Test
    fun `unsupported manifest version rejected`() {
        val manifest = manifestJson(validWeatherManifest).copy(manifestVersion = 2)
        val errors = ManifestValidator.validate(manifest, fileExists = { true })
        assertTrue(errors.any { it.contains("manifest_version") })
    }

    @Test
    fun `unknown permission rejected`() {
        val manifest = manifestJson(validWeatherManifest).copy(permissions = listOf("filesystem.root"))
        val errors = ManifestValidator.validate(manifest, fileExists = { true })
        assertTrue(errors.any { it.contains("filesystem.root") })
    }

    @Test
    fun `missing entry file rejected`() {
        val manifest = manifestJson(validWeatherManifest)
        val errors = ManifestValidator.validate(manifest, fileExists = { false })
        assertTrue(errors.any { it.contains("执行入口不存在") })
    }

    @Test
    fun `duplicate tool ids rejected`() {
        val json = """
        {
          "manifest_version": 1,
          "app_id": "dup_tools",
          "name": "重复工具",
          "extension_type": "tool",
          "version": "1.0.0",
          "tools": [
            {"tool_id": "same", "name": "A", "entry": "tools/a.js"},
            {"tool_id": "same", "name": "B", "entry": "tools/b.js"}
          ]
        }
        """.trimIndent()
        val errors = ManifestValidator.validate(manifestJson(json), fileExists = { true })
        assertTrue(errors.any { it.contains("不得重复") })
    }

    @Test
    fun `tool extension requires at least one tool`() {
        val json = """
        {
          "manifest_version": 1,
          "app_id": "no_tools",
          "name": "无工具",
          "extension_type": "tool",
          "version": "1.0.0"
        }
        """.trimIndent()
        val errors = ManifestValidator.validate(manifestJson(json), fileExists = { true })
        assertTrue(errors.any { it.contains("至少包含一个有效 Tool") })
    }

    @Test
    fun `mini app requires frontend entry`() {
        val json = """
        {
          "manifest_version": 1,
          "app_id": "no_frontend",
          "name": "无前端",
          "extension_type": "mini_app",
          "version": "1.0.0"
        }
        """.trimIndent()
        val errors = ManifestValidator.validate(manifestJson(json), fileExists = { true })
        assertTrue(errors.any { it.contains("frontend.entry") })
    }

    @Test
    fun `mini app with valid frontend and collection passes`() {
        val json = """
        {
          "manifest_version": 1,
          "app_id": "personal_forum",
          "name": "个人论坛",
          "extension_type": "mini_app",
          "version": "1.0.0",
          "frontend": { "entry": "web/index.html" },
          "storage": {
            "schema_version": 1,
            "collections": [
              { "name": "posts", "schema": "schemas/posts.schema.json" }
            ]
          },
          "permissions": ["storage.read", "storage.write"]
        }
        """.trimIndent()
        val errors = ManifestValidator.validate(
            manifestJson(json),
            fileExists = { it == "web/index.html" || it == "schemas/posts.schema.json" },
            parseSchemaFile = { JsonInstant.parseToJsonElement("""{"type":"object","properties":{"title":{"type":"string"}},"required":["title"]}""") as kotlinx.serialization.json.JsonObject }
        )
        assertTrue(errors.joinToString("\n"), errors.isEmpty())
    }

    @Test
    fun `collection with bad schema structure fails`() {
        val json = """
        {
          "manifest_version": 1,
          "app_id": "bad_schema",
          "name": "坏 Schema",
          "extension_type": "mini_app",
          "version": "1.0.0",
          "frontend": { "entry": "web/index.html" },
          "storage": {
            "schema_version": 1,
            "collections": [
              { "name": "posts", "schema": "schemas/posts.schema.json" }
            ]
          }
        }
        """.trimIndent()
        val errors = ManifestValidator.validate(
            manifestJson(json),
            fileExists = { true },
            parseSchemaFile = { JsonInstant.parseToJsonElement("""{"type": "unsupported_type"}""") as kotlinx.serialization.json.JsonObject }
        )
        assertTrue(errors.any { it.contains("unsupported_type") })
    }

    // ---------- 版本自动管理（2026-11-20 补充需求第三章） ----------

    @Test
    fun `increment patch version`() {
        assertEquals("1.0.1", ExtensionRules.incrementPatchVersion("1.0.0"))
        assertEquals("1.0.2", ExtensionRules.incrementPatchVersion("1.0.1"))
        assertEquals("1.2.4", ExtensionRules.incrementPatchVersion("1.2.3"))
        assertEquals("2.0.1", ExtensionRules.incrementPatchVersion("2.0.0"))
    }

    @Test
    fun `increment patch ignores prerelease and build`() {
        // prerelease / build 元数据不参与递增
        assertEquals("1.0.1", ExtensionRules.incrementPatchVersion("1.0.0-beta"))
        assertEquals("1.0.1", ExtensionRules.incrementPatchVersion("1.0.0-beta+build.1"))
        assertEquals("1.0.1", ExtensionRules.incrementPatchVersion("1.0.0+20261120"))
    }

    @Test
    fun `increment patch rejects invalid versions`() {
        assertNull(ExtensionRules.incrementPatchVersion(""))
        assertNull(ExtensionRules.incrementPatchVersion("1"))
        assertNull(ExtensionRules.incrementPatchVersion("1.0"))
        assertNull(ExtensionRules.incrementPatchVersion("a.b.c"))
        assertNull(ExtensionRules.incrementPatchVersion("1.0.x"))
        assertNull(ExtensionRules.incrementPatchVersion("1.0.0.0"))
    }
}
