package me.rerere.rikkahub.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.core.data.db.entity.ExtensionPermissionEntity
import me.rerere.rikkahub.core.data.db.entity.ExtensionToolEntity

/**
 * 扩展工具运行配置与扩展权限 DAO（详设 6.3 / 6.4）。
 */
@Dao
interface ExtensionToolConfigDAO {

    // ---------- extension_tools ----------

    @Query("SELECT * FROM extension_tools")
    fun observeAllTools(): Flow<List<ExtensionToolEntity>>

    @Query("SELECT * FROM extension_tools WHERE app_id = :appId")
    suspend fun getToolsByAppId(appId: String): List<ExtensionToolEntity>

    @Query("SELECT * FROM extension_tools WHERE app_id = :appId AND tool_id = :toolId")
    suspend fun getToolConfig(appId: String, toolId: String): ExtensionToolEntity?

    @Upsert
    suspend fun upsertTool(config: ExtensionToolEntity)

    @Query("UPDATE extension_tools SET enable = :enable, updated_at = :updatedAt WHERE app_id = :appId AND tool_id = :toolId")
    suspend fun setToolEnable(appId: String, toolId: String, enable: Boolean, updatedAt: Long)

    @Query("UPDATE extension_tools SET custom_description = :description, updated_at = :updatedAt WHERE app_id = :appId AND tool_id = :toolId")
    suspend fun setToolCustomDescription(appId: String, toolId: String, description: String?, updatedAt: Long)

    /** 清理新版本中已不存在的 Tool 配置 */
    @Query("DELETE FROM extension_tools WHERE app_id = :appId AND tool_id NOT IN (:keepToolIds)")
    suspend fun deleteToolsNotIn(appId: String, keepToolIds: List<String>)

    @Query("DELETE FROM extension_tools WHERE app_id = :appId")
    suspend fun deleteToolsByAppId(appId: String)

    // ---------- extension_permissions ----------

    @Query("SELECT * FROM extension_permissions")
    fun observeAllPermissions(): Flow<List<ExtensionPermissionEntity>>

    @Query("SELECT * FROM extension_permissions WHERE app_id = :appId")
    suspend fun getPermissionsByAppId(appId: String): List<ExtensionPermissionEntity>

    @Upsert
    suspend fun upsertPermission(permission: ExtensionPermissionEntity)

    @Query("UPDATE extension_permissions SET granted = :granted, updated_at = :updatedAt WHERE app_id = :appId AND permission = :permission")
    suspend fun setPermissionGranted(appId: String, permission: String, granted: Boolean, updatedAt: Long)

    @Query("DELETE FROM extension_permissions WHERE app_id = :appId")
    suspend fun deletePermissionsByAppId(appId: String)
}
