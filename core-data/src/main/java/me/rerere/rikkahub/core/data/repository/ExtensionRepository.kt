package me.rerere.rikkahub.core.data.repository

import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.core.data.db.dao.ExtensionDAO
import me.rerere.rikkahub.core.data.db.dao.MiniAppDataDAO
import me.rerere.rikkahub.core.data.db.dao.MiniAppVersionDAO
import me.rerere.rikkahub.core.data.db.dao.ExtensionToolConfigDAO
import me.rerere.rikkahub.core.data.db.entity.ExtensionEntity
import me.rerere.rikkahub.core.data.db.entity.ExtensionInstallOperationEntity
import me.rerere.rikkahub.core.data.db.entity.ExtensionPermissionEntity
import me.rerere.rikkahub.core.data.db.entity.ExtensionToolEntity
import me.rerere.rikkahub.core.data.db.entity.MiniAppDataEntity
import me.rerere.rikkahub.core.data.db.entity.MiniAppVersionEntity

/**
 * 扩展元数据/工具配置/权限/Mini App 数据的统一 Repository（thin delegation）。
 */
class ExtensionRepository(
    private val extensionDAO: ExtensionDAO,
    private val toolConfigDAO: ExtensionToolConfigDAO,
    private val miniAppDataDAO: MiniAppDataDAO,
    private val miniAppVersionDAO: MiniAppVersionDAO,
) {

    // ---------- extensions ----------

    fun observeAllExtensions(): Flow<List<ExtensionEntity>> = extensionDAO.observeAll()

    suspend fun getAllExtensions(): List<ExtensionEntity> = extensionDAO.getAll()

    suspend fun getExtension(appId: String): ExtensionEntity? = extensionDAO.getByAppId(appId)

    suspend fun upsertExtension(extension: ExtensionEntity) = extensionDAO.upsert(extension)

    suspend fun setExtensionEnabled(appId: String, enabled: Boolean, updatedAt: Long) =
        extensionDAO.setEnabled(appId, enabled, updatedAt)

    suspend fun deleteExtension(appId: String) = extensionDAO.deleteByAppId(appId)

    // ---------- install operations ----------

    suspend fun getPendingInstallOperations(): List<ExtensionInstallOperationEntity> =
        extensionDAO.getPendingOperations()

    suspend fun getInstallOperation(operationId: String): ExtensionInstallOperationEntity? =
        extensionDAO.getOperation(operationId)

    suspend fun upsertInstallOperation(operation: ExtensionInstallOperationEntity) =
        extensionDAO.upsertOperation(operation)

    suspend fun updateInstallOperationState(operationId: String, state: String, updatedAt: Long) =
        extensionDAO.updateOperationState(operationId, state, updatedAt)

    suspend fun deleteInstallOperation(operationId: String) = extensionDAO.deleteOperation(operationId)

    // ---------- tool configs ----------

    fun observeAllToolConfigs(): Flow<List<ExtensionToolEntity>> = toolConfigDAO.observeAllTools()

    suspend fun getToolConfigs(appId: String): List<ExtensionToolEntity> = toolConfigDAO.getToolsByAppId(appId)

    suspend fun getToolConfig(appId: String, toolId: String): ExtensionToolEntity? =
        toolConfigDAO.getToolConfig(appId, toolId)

    suspend fun upsertToolConfig(config: ExtensionToolEntity) = toolConfigDAO.upsertTool(config)

    suspend fun setToolEnable(appId: String, toolId: String, enable: Boolean, updatedAt: Long) =
        toolConfigDAO.setToolEnable(appId, toolId, enable, updatedAt)

    suspend fun setToolCustomDescription(appId: String, toolId: String, description: String?, updatedAt: Long) =
        toolConfigDAO.setToolCustomDescription(appId, toolId, description, updatedAt)

    suspend fun deleteToolConfigsNotIn(appId: String, keepToolIds: List<String>) =
        toolConfigDAO.deleteToolsNotIn(appId, keepToolIds)

    suspend fun deleteToolConfigsByAppId(appId: String) = toolConfigDAO.deleteToolsByAppId(appId)

    // ---------- permissions ----------

    fun observeAllPermissions(): Flow<List<ExtensionPermissionEntity>> = toolConfigDAO.observeAllPermissions()

    suspend fun getPermissions(appId: String): List<ExtensionPermissionEntity> =
        toolConfigDAO.getPermissionsByAppId(appId)

    suspend fun upsertPermission(permission: ExtensionPermissionEntity) = toolConfigDAO.upsertPermission(permission)

    suspend fun setPermissionGranted(appId: String, permission: String, granted: Boolean, updatedAt: Long) =
        toolConfigDAO.setPermissionGranted(appId, permission, granted, updatedAt)

    suspend fun deletePermissionsByAppId(appId: String) = toolConfigDAO.deletePermissionsByAppId(appId)

    // ---------- mini app data ----------

    suspend fun getMiniAppData(appId: String, collection: String, recordId: String): MiniAppDataEntity? =
        miniAppDataDAO.get(appId, collection, recordId)

    suspend fun listMiniAppData(appId: String, collection: String, limit: Int, offset: Int): List<MiniAppDataEntity> =
        miniAppDataDAO.list(appId, collection, limit, offset)

    suspend fun countMiniAppData(appId: String, collection: String): Int =
        miniAppDataDAO.count(appId, collection)

    fun observeMiniAppCollection(appId: String, collection: String): Flow<List<MiniAppDataEntity>> =
        miniAppDataDAO.observeCollection(appId, collection)

    suspend fun insertMiniAppData(entity: MiniAppDataEntity): Long = miniAppDataDAO.insert(entity)

    suspend fun updateMiniAppData(entity: MiniAppDataEntity) = miniAppDataDAO.update(entity)

    suspend fun deleteMiniAppData(appId: String, collection: String, recordId: String) =
        miniAppDataDAO.delete(appId, collection, recordId)

    suspend fun deleteMiniAppDataByAppId(appId: String) = miniAppDataDAO.deleteByAppId(appId)

    // ---------- mini app versions ----------

    suspend fun getLatestMiniAppVersion(appId: String): MiniAppVersionEntity? =
        miniAppVersionDAO.getLatest(appId)

    suspend fun insertMiniAppVersion(version: MiniAppVersionEntity) = miniAppVersionDAO.insert(version)

    suspend fun deleteMiniAppVersionsByAppId(appId: String) = miniAppVersionDAO.deleteByAppId(appId)
}
