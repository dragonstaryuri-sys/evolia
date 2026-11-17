package me.rerere.rikkahub.data.extensions

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.core.data.db.entity.ExtensionEntity
import me.rerere.rikkahub.core.data.db.entity.ExtensionInstallOperationEntity
import me.rerere.rikkahub.core.data.db.entity.ExtensionToolEntity
import me.rerere.rikkahub.core.data.db.entity.MiniAppVersionEntity
import me.rerere.rikkahub.data.db.AppDatabase
import java.io.File
import kotlin.uuid.Uuid

/**
 * 扩展安装器（详设第八章）。
 *
 * 负责：staging 解压校验 → 安装操作记录 → 正式目录原子切换 → Room 事务提交
 * → 清理。任何一步失败不得留下半安装状态；App 异常退出后由
 * [recoverPendingOperations] 在启动时恢复一致状态。
 *
 * 目录布局（相对 filesDir）：
 * ```
 * extensions/installed/{app_id}/   正式安装目录
 * extensions/staging/{op}/         解压校验区
 * extensions/backups/{op}/         旧版本备份（更新时）
 * extensions/workspaces/{draft}/   本地创建草稿
 * ```
 */
class ExtensionInstaller(
    private val context: Context,
    private val db: AppDatabase,
    private val repository: me.rerere.rikkahub.core.data.repository.ExtensionRepository,
) {
    companion object {
        const val TAG = "ExtensionInstaller"
        const val ROOT_DIR = "extensions"
        const val INSTALLED_DIR = "installed"
        const val STAGING_DIR = "staging"
        const val BACKUPS_DIR = "backups"
        const val WORKSPACES_DIR = "workspaces"

        fun extensionsRoot(context: Context): File = File(context.filesDir, ROOT_DIR)
        fun installedDir(context: Context): File = File(extensionsRoot(context), INSTALLED_DIR)
        fun stagingRoot(context: Context): File = File(extensionsRoot(context), STAGING_DIR)
        fun backupsRoot(context: Context): File = File(extensionsRoot(context), BACKUPS_DIR)
        fun workspacesRoot(context: Context): File = File(extensionsRoot(context), WORKSPACES_DIR)

        fun installPathOf(appId: String): String = "$ROOT_DIR/$INSTALLED_DIR/$appId"
    }

    private val installMutex = Mutex()

    /** 安装失败异常，message 面向用户 */
    class InstallException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * 安装或更新一个扩展包。
     *
     * @param sourceType LOCAL_CREATED / LOCAL_IMPORT / GITHUB / BUILTIN
     */
    suspend fun installFromZip(
        zipFile: File,
        sourceType: String,
        sourceUri: String? = null,
    ): ExtensionEntity = installMutex.withLock {
        withContext(Dispatchers.IO) { installInternal(zipFile = zipFile, sourceDir = null, sourceType, sourceUri) }
    }

    /**
     * 从工作区目录安装（本地创建草稿 → 安装）。目录结构须满足包规范
     * （manifest.json + 资源）。
     */
    suspend fun installFromDirectory(
        sourceDir: File,
        sourceType: String = ExtensionSourceType.LOCAL_CREATED,
    ): ExtensionEntity = installMutex.withLock {
        withContext(Dispatchers.IO) { installInternal(zipFile = null, sourceDir = sourceDir, sourceType, null) }
    }

    private suspend fun installInternal(
        zipFile: File?,
        sourceDir: File?,
        sourceType: String,
        sourceUri: String?,
    ): ExtensionEntity {
        val now = System.currentTimeMillis()
        val operationId = Uuid.random().toString()
        val stagingDir = File(stagingRoot(context), operationId)

        // 1. staging 解压（ZIP 场景）或复制（目录场景）
        val content: ZipPackageReader.PackageContent
        try {
            stagingDir.mkdirs()
            content = if (zipFile != null) {
                ZipPackageReader().extractTo(zipFile, stagingDir)
            } else {
                sourceDir!!.copyRecursively(stagingDir, overwrite = true)
                val manifest = ZipPackageReader().parseManifestFromDirectory(stagingDir)
                ZipPackageReader.PackageContent(
                    manifest = manifest,
                    manifestHash = ZipPackageReader.sha256(File(stagingDir, "manifest.json").readBytes()),
                    packageHash = "",
                    fileCount = 0,
                    totalUncompressedBytes = 0,
                )
            }
        } catch (e: Exception) {
            stagingDir.deleteRecursively()
            throw InstallException(e.message ?: "扩展包解析失败", e)
        }

        val manifest = content.manifest

        // 2. Manifest 校验（存在性基于 staging）
        val validationErrors = ManifestValidator.validate(
            manifest = manifest,
            fileExists = { relative -> File(stagingDir, relative).isFile },
            parseSchemaFile = { relative ->
                val f = File(stagingDir, relative)
                if (f.isFile) runCatching {
                    me.rerere.rikkahub.common.JsonInstant.parseToJsonElement(f.readText()) as? kotlinx.serialization.json.JsonObject
                }.getOrNull() else null
            },
        )
        if (validationErrors.isNotEmpty()) {
            stagingDir.deleteRecursively()
            throw InstallException("扩展包校验失败：\n" + validationErrors.joinToString("\n") { "· $it" })
        }

        val resolvedTools = ManifestValidator.resolveTools(manifest) { relative ->
            val f = File(stagingDir, relative)
            if (f.isFile) runCatching {
                me.rerere.rikkahub.common.JsonInstant.parseToJsonElement(f.readText()) as? kotlinx.serialization.json.JsonObject
            }.getOrNull() else null
        }
        val resolvedCollections = ManifestValidator.resolveCollections(manifest) { relative ->
            val f = File(stagingDir, relative)
            if (f.isFile) runCatching {
                me.rerere.rikkahub.common.JsonInstant.parseToJsonElement(f.readText()) as? kotlinx.serialization.json.JsonObject
            }.getOrNull() else null
        }

        // 3. 身份/版本判定
        val existing = repository.getExtension(manifest.appId)
        val decision = ExtensionRules.decideInstallAction(existing, manifest.extensionType, manifest.version)
        val installedName = existing?.name ?: ""
        val installedType = existing?.extensionType ?: ""
        val installedVersion = existing?.version ?: ""
        when (decision) {
            ExtensionRules.InstallDecision.TYPE_CONFLICT ->
                throw InstallException(
                    "扩展身份冲突：已安装「$installedName」（$installedType）与待安装类型" +
                        "「${manifest.extensionType}」不一致，禁止覆盖"
                )

            ExtensionRules.InstallDecision.SAME_VERSION ->
                throw InstallException(
                    "已安装相同版本 ${manifest.version}，如需重新安装请先卸载"
                )

            ExtensionRules.InstallDecision.DOWNGRADE_BLOCKED ->
                throw InstallException(
                    "待安装版本 ${manifest.version} 低于当前版本 $installedVersion，默认禁止降级"
                )

            else -> Unit
        }
        val isUpdate = decision == ExtensionRules.InstallDecision.UPDATE

        // 4. 创建安装操作记录（FILES_STAGED）
        val oldVersion = existing?.version
        val filesDirRoot = context.filesDir
        repository.upsertInstallOperation(
            ExtensionInstallOperationEntity(
                operationId = operationId,
                appId = manifest.appId,
                operationType = if (isUpdate) "UPDATE" else "INSTALL",
                oldVersion = oldVersion,
                targetVersion = manifest.version,
                stagingPath = stagingDir.relativeTo(filesDirRoot).path,
                backupPath = null,
                operationState = ExtensionInstallOperationState.FILES_STAGED,
                createdAt = now,
                updatedAt = now,
            )
        )

        val targetDir = File(installedDir(context), manifest.appId)
        val backupDir = File(backupsRoot(context), operationId)
        // 父目录兜底：首次安装时 extensions/installed、extensions/backups 尚不存在，
        // renameTo 的目标父目录缺失会让 rename(2) 静默返回 false（模拟器实测踩坑）
        targetDir.parentFile?.mkdirs()
        backupDir.parentFile?.mkdirs()

        // 5. 正式目录切换（原子 rename；更新时先备份旧目录）
        try {
            if (targetDir.exists()) {
                if (isUpdate) {
                    if (!targetDir.renameTo(backupDir)) {
                        throw InstallException("旧版本备份失败，已中止更新")
                    }
                    repository.updateInstallOperationState(
                        operationId, ExtensionInstallOperationState.FILES_STAGED, System.currentTimeMillis()
                    )
                    // backup_path 补记
                    val operationRecord = repository.getInstallOperation(operationId)
                    if (operationRecord != null) {
                        repository.upsertInstallOperation(
                            operationRecord.copy(
                                backupPath = backupDir.relativeTo(filesDirRoot).path,
                                updatedAt = System.currentTimeMillis(),
                            )
                        )
                    }
                } else {
                    targetDir.deleteRecursively()
                }
            }
            if (!stagingDir.renameTo(targetDir)) {
                throw InstallException("安装目录切换失败")
            }
            repository.updateInstallOperationState(
                operationId, ExtensionInstallOperationState.FILES_SWITCHED, System.currentTimeMillis()
            )
        } catch (e: Exception) {
            // 切换失败：尽力恢复旧目录，不留半安装状态
            if (isUpdate && backupDir.exists() && !targetDir.exists()) {
                backupDir.renameTo(targetDir)
            }
            stagingDir.deleteRecursively()
            backupDir.deleteRecursively()
            repository.deleteInstallOperation(operationId)
            throw if (e is InstallException) e else InstallException(e.message ?: "安装失败", e)
        }

        // 6. Room 事务提交（Registry + 工具配置 + 权限 + 版本记录）
        val newToolIds = resolvedTools.map { it.toolId }.toSet()
        val entity = ExtensionEntity(
            appId = manifest.appId,
            name = manifest.name,
            description = manifest.description,
            extensionType = manifest.extensionType,
            version = manifest.version,
            sourceType = sourceType,
            sourceUri = sourceUri,
            createdOrigin = existing?.createdOrigin ?: sourceType,
            manifestVersion = manifest.manifestVersion,
            installPath = installPathOf(manifest.appId),
            packageHash = content.packageHash,
            isEnabled = existing?.isEnabled ?: true,
            isBuiltin = existing?.isBuiltin ?: (sourceType == ExtensionSourceType.BUILTIN),
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            installedAt = now,
        )
        try {
            db.withTransaction {
                repository.upsertExtension(entity)

                // 工具配置：保留既有 enable / custom_description，删除已下线 Tool
                val oldConfigs = repository.getToolConfigs(manifest.appId)
                val oldMap = oldConfigs.associateBy { it.toolId }
                resolvedTools.forEach { tool ->
                    val old = oldMap[tool.toolId]
                    repository.upsertToolConfig(
                        ExtensionToolEntity(
                            appId = manifest.appId,
                            toolId = tool.toolId,
                            enable = old?.enable ?: true,
                            customDescription = old?.customDescription,
                            createdAt = old?.createdAt ?: now,
                            updatedAt = now,
                        )
                    )
                }
                repository.deleteToolConfigsNotIn(manifest.appId, newToolIds.toList())

                // 权限：保留仍声明权限的授权状态；新增权限重新申请（granted=false）
                val mergedPermissions = ExtensionRules.mergePermissionsOnUpdate(
                    appId = manifest.appId,
                    oldPermissions = repository.getPermissions(manifest.appId),
                    newPermissions = manifest.permissions,
                    now = now,
                )
                mergedPermissions.forEach { repository.upsertPermission(it) }

                // Mini App 数据 Schema 版本记录（扩展版本与数据版本独立管理）
                if (manifest.extensionType == ExtensionType.MINI_APP) {
                    repository.insertMiniAppVersion(
                        MiniAppVersionEntity(
                            appId = manifest.appId,
                            version = manifest.version,
                            schemaVersion = manifest.storage?.schemaVersion ?: 1,
                            installedAt = now,
                        )
                    )
                }
            }
            repository.updateInstallOperationState(
                operationId, ExtensionInstallOperationState.DATABASE_COMMITTED, System.currentTimeMillis()
            )
        } catch (e: Exception) {
            // DB 提交失败：恢复旧版本文件
            targetDir.deleteRecursively()
            if (isUpdate && backupDir.exists()) {
                backupDir.renameTo(targetDir)
            }
            stagingDir.deleteRecursively()
            backupDir.deleteRecursively()
            repository.deleteInstallOperation(operationId)
            throw InstallException("安装数据写入失败：${e.message}", e)
        }

        // 7. 清理
        stagingDir.deleteRecursively()
        backupDir.deleteRecursively()
        repository.updateInstallOperationState(
            operationId, ExtensionInstallOperationState.COMPLETED, System.currentTimeMillis()
        )
        repository.deleteInstallOperation(operationId)

        Log.i(TAG, "扩展安装完成：${manifest.appId} v${manifest.version} (update=$isUpdate)")
        return entity
    }

    /**
     * 卸载扩展。用户数据默认保留；[deleteMiniAppData] = true 时一并删除
     * （UI 需二次确认）。
     */
    suspend fun uninstall(appId: String, deleteMiniAppData: Boolean = false) = installMutex.withLock {
        withContext(Dispatchers.IO) {
            // 先清注册（调用入口即刻失效），再删文件
            db.withTransaction {
                repository.deleteToolConfigsByAppId(appId)
                repository.deletePermissionsByAppId(appId)
                repository.deleteMiniAppVersionsByAppId(appId)
                if (deleteMiniAppData) {
                    repository.deleteMiniAppDataByAppId(appId)
                }
                repository.deleteExtension(appId)
            }
            File(installedDir(context), appId).deleteRecursively()
            Log.i(TAG, "扩展卸载完成：$appId (deleteData=$deleteMiniAppData)")
        }
    }

    /**
     * 启动恢复：扫描未完成的安装操作，按状态决策恢复文件与 Registry 一致
     * （详设 8.3）。同时清理孤儿 staging。
     */
    suspend fun recoverPendingOperations() = withContext(Dispatchers.IO) {
        val pending = repository.getPendingInstallOperations()
        for (op in pending) {
            runCatching { recoverOne(op) }.onFailure {
                Log.e(TAG, "安装操作恢复失败：${op.operationId} ${op.appId}", it)
            }
        }
        // 孤儿 staging 清理
        stagingRoot(context).listFiles()?.forEach { it.deleteRecursively() }
        backupsRoot(context).listFiles()?.forEach { it.deleteRecursively() }
        // 孤儿安装目录清理：无 Registry 记录且无待恢复操作覆盖
        val registered = repository.getAllExtensions().map { it.appId }.toSet()
        val pendingAppIds = pending.map { it.appId }.toSet()
        installedDir(context).listFiles()?.forEach { dir ->
            if (dir.name !in registered && dir.name !in pendingAppIds) {
                Log.w(TAG, "清理孤儿安装目录：${dir.name}")
                dir.deleteRecursively()
            }
        }
    }

    private suspend fun recoverOne(op: ExtensionInstallOperationEntity) {
        val isUpdate = op.operationType == "UPDATE"
        val stagingDir = op.stagingPath?.let { File(context.filesDir, it) }
        val backupDir = op.backupPath?.let { File(context.filesDir, it) }
        val targetDir = File(installedDir(context), op.appId)

        val action = ExtensionRules.decideRecovery(
            operationType = op.operationType,
            operationState = op.operationState,
            isUpdate = isUpdate,
            backupExists = backupDir?.exists() == true,
            installDirExists = targetDir.exists(),
        )
        Log.i(TAG, "恢复安装操作 ${op.operationId}（${op.operationState}）→ $action")
        when (action) {
            ExtensionRules.RecoveryAction.RESTORE_OLD_VERSION -> {
                if (targetDir.exists()) targetDir.deleteRecursively()
                if (backupDir?.exists() == true) {
                    backupDir.renameTo(targetDir)
                }
            }

            ExtensionRules.RecoveryAction.REMOVE_NEW_INSTALL -> {
                targetDir.deleteRecursively()
            }

            else -> Unit
        }
        stagingDir?.deleteRecursively()
        backupDir?.deleteRecursively()
        repository.deleteInstallOperation(op.operationId)
    }
}
