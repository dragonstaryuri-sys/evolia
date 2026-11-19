package me.rerere.rikkahub.data.extensions

import me.rerere.rikkahub.core.data.db.entity.ExtensionEntity
import me.rerere.rikkahub.core.data.db.entity.ExtensionPermissionEntity
import me.rerere.rikkahub.core.data.db.entity.ExtensionToolEntity

/**
 * 扩展体系纯决策函数集（详设 8.4 / 8.5 / 8.3 / 13.3）。
 *
 * 版本比较、安装判定、更新配置合并、崩溃恢复决策、工具可用性判定
 * 全部抽为纯函数并单元测试固化；不得内联散落在 Installer/Registry 中。
 */
object ExtensionRules {

    // ---------- 版本比较（8.4） ----------

    /**
     * SemVer 兼容比较：major/minor/patch 逐段数值比较，prerelease 低于正式版，
     * 无 prerelease 段时按字典序比较各段。非法格式按 0.0.0 处理。
     * 返回负数 / 0 / 正数 = a 低于 / 等于 / 高于 b。
     */
    fun compareVersions(a: String, b: String): Int {
        val pa = parseVersion(a)
        val pb = parseVersion(b)
        for (i in 0 until 3) {
            val cmp = pa.numeric[i].compareTo(pb.numeric[i])
            if (cmp != 0) return cmp
        }
        // prerelease：无 prerelease > 有 prerelease（1.0.0-alpha < 1.0.0）
        val preA = pa.preRelease
        val preB = pb.preRelease
        if (preA == null && preB == null) return 0
        if (preA == null) return 1
        if (preB == null) return -1
        return comparePreRelease(preA, preB)
    }

    private data class ParsedVersion(val numeric: List<Int>, val preRelease: String?)

    private fun parseVersion(version: String): ParsedVersion {
        val (pre, core) = version.split('-', limit = 2).let { parts ->
            val cleanCore = parts[0].substringBefore('+').trim()
            parts.getOrNull(1)?.substringBefore('+') to cleanCore
        }
        val nums = core.split('.').map { it.trim().toIntOrNull() ?: 0 }
        val numeric = List(3) { nums.getOrNull(it) ?: 0 }
        return ParsedVersion(numeric, pre)
    }

    private fun comparePreRelease(a: String, b: String): Int {
        val sa = a.split('.')
        val sb = b.split('.')
        val len = minOf(sa.size, sb.size)
        for (i in 0 until len) {
            val cmp = comparePreSegment(sa[i], sb[i])
            if (cmp != 0) return cmp
        }
        return sa.size.compareTo(sb.size)
    }

    private fun comparePreSegment(x: String, y: String): Int {
        val nx = x.toIntOrNull()
        val ny = y.toIntOrNull()
        return when {
            nx != null && ny != null -> nx.compareTo(ny)
            nx != null -> -1 // 数字段低于字母数字段
            ny != null -> 1
            else -> x.compareTo(y)
        }
    }

    // ---------- 安装/更新判定（8.4 / 15.4） ----------

    enum class InstallDecision { FRESH_INSTALL, UPDATE, SAME_VERSION, DOWNGRADE_BLOCKED, TYPE_CONFLICT }

    /**
     * 同 ID 重复安装判定。
     */
    fun decideInstallAction(
        installed: ExtensionEntity?,
        newType: String,
        newVersion: String,
    ): InstallDecision {
        if (installed == null) return InstallDecision.FRESH_INSTALL
        if (installed.extensionType != newType) return InstallDecision.TYPE_CONFLICT
        val cmp = compareVersions(newVersion, installed.version)
        return when {
            cmp == 0 -> InstallDecision.SAME_VERSION
            cmp < 0 -> InstallDecision.DOWNGRADE_BLOCKED
            else -> InstallDecision.UPDATE
        }
    }

    // ---------- 更新配置保留（8.5，纯函数有单测） ----------

    /**
     * 更新时合并 Tool 运行配置：保留仍然存在的 Tool 的启用状态与自定义描述，
     * 删除新版本中已不存在的 Tool 配置。
     */
    fun mergeToolConfigsOnUpdate(
        oldConfigs: List<ExtensionToolEntity>,
        newToolIds: Set<String>,
        now: Long,
    ): List<ExtensionToolEntity> {
        return oldConfigs.filter { it.toolId in newToolIds }
    }

    /**
     * 更新时合并权限记录：保留仍然声明权限的授权状态，
     * 新增权限默认重新申请（granted = false），不得自动授予。
     * [grantNew] = true 仅用于「用户在安装/更新弹窗中已明确授权」的场景，
     * 此时新增权限随安装直接落为已授权。
     */
    fun mergePermissionsOnUpdate(
        appId: String,
        oldPermissions: List<ExtensionPermissionEntity>,
        newPermissions: List<String>,
        now: Long,
        grantNew: Boolean = false,
    ): List<ExtensionPermissionEntity> {
        val oldMap = oldPermissions.associateBy { it.permission }
        return newPermissions.map { permission ->
            val existing = oldMap[permission]
            if (existing != null) {
                existing.copy(updatedAt = now)
            } else {
                ExtensionPermissionEntity(
                    appId = appId,
                    permission = permission,
                    granted = grantNew,
                    createdAt = now,
                    updatedAt = now,
                )
            }
        }
    }

    // ---------- 安装/更新期权限确认（2026-11-19 需求） ----------

    /**
     * 计算安装/更新前需要用户授权的权限清单（纯函数）。
     *
     * 包声明权限 = manifest 顶层 permissions + 全部 Tool 的 permissions（去重）。
     * 减去此前已声明过的权限（[previouslyDeclared]，全新安装传空集），
     * 剩余即本次安装/更新需要向用户申明的新增权限。
     */
    fun permissionsRequiringGrant(
        previouslyDeclared: Set<String>,
        manifestPermissions: List<String>,
        toolPermissions: List<String>,
    ): List<String> {
        return (manifestPermissions + toolPermissions)
            .distinct()
            .filter { it !in previouslyDeclared }
    }

    // ---------- 安装崩溃恢复决策（8.3，纯函数有单测） ----------

    enum class RecoveryAction {
        /** 仅清理 staging，文件与 DB 均无需变更（INSTALL/UPDATE 在切换前中断） */
        CLEANUP_STAGING,

        /** 恢复旧版本目录后清理（UPDATE 在切换后、DB 提交前中断） */
        RESTORE_OLD_VERSION,

        /** 删除新安装目录后清理（INSTALL 在切换后、DB 提交前中断） */
        REMOVE_NEW_INSTALL,

        /** DB 已提交：保留新版本，仅完成清理（staging / backup / 操作记录） */
        FINISH_CLEANUP,

        /** 无待恢复操作 */
        NOTHING,
    }

    /**
     * 根据操作记录状态决定恢复动作。
     *
     * @param backupExists 旧版本备份目录是否存在（UPDATE 场景）
     * @param installDirExists 正式安装目录当前是否存在（可能已被新版本覆盖或为空）
     */
    fun decideRecovery(
        operationType: String,
        operationState: String,
        isUpdate: Boolean,
        backupExists: Boolean,
        installDirExists: Boolean,
    ): RecoveryAction = when (operationState) {
        ExtensionInstallOperationState.PREPARING,
        ExtensionInstallOperationState.FILES_STAGED,
        -> RecoveryAction.CLEANUP_STAGING

        ExtensionInstallOperationState.FILES_SWITCHED,
        ExtensionInstallOperationState.ROLLBACK_REQUIRED,
        ->
            if (isUpdate && backupExists) RecoveryAction.RESTORE_OLD_VERSION
            else if (isUpdate && installDirExists) RecoveryAction.RESTORE_OLD_VERSION
            else if (!isUpdate && installDirExists) RecoveryAction.REMOVE_NEW_INSTALL
            else RecoveryAction.FINISH_CLEANUP

        ExtensionInstallOperationState.DATABASE_COMMITTED -> RecoveryAction.FINISH_CLEANUP

        else -> RecoveryAction.NOTHING
    }

    // ---------- 工具可用性判定（13.3，三层权限） ----------

    /**
     * 判定扩展 Tool 当前是否可被调用 / 是否可进入上下文目录（两者必须使用
     * 同一判定，2026-11-19 修复：目录此前对独立 Tool Extension 跳过助手级
     * 检查，导致未授权工具的描述仍注入上下文）。
     *
     * @param extensionInstalled 扩展已注册
     * @param extensionEnabled 扩展整体启用（第一层）
     * @param toolConfigEnable Tool 全局启用（第二层，null = 无配置行，按未启用处理）
     * @param permissionsGranted Tool 所需权限已全部授权
     * @param isMiniAppTool 是否属于 Mini App 的 Tool
     * @param assistantEnabled 该助手是否启用了所属扩展（第三层：Mini App 看
     *   enabledMiniAppIds，Tool Extension 看 enabledToolExtensionIds）
     */
    fun isExtensionToolAvailable(
        extensionInstalled: Boolean,
        extensionEnabled: Boolean,
        toolConfigEnable: Boolean?,
        permissionsGranted: Boolean,
        isMiniAppTool: Boolean,
        assistantEnabled: Boolean,
    ): Boolean {
        val installedAndEnabled = extensionInstalled && extensionEnabled
        val toolEnabled = toolConfigEnable == true
        val assistantLevelOk = assistantEnabled
        return installedAndEnabled && toolEnabled && permissionsGranted && assistantLevelOk
    }

    /** 有效描述 = 用户自定义优先，原始描述兜底 */
    fun effectiveDescription(customDescription: String?, originalDescription: String): String =
        customDescription?.takeIf { it.isNotBlank() } ?: originalDescription
}
