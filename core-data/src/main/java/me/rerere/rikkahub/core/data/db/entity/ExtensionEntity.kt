package me.rerere.rikkahub.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 已安装扩展元数据表（详设 6.2）。app_id 是扩展的稳定身份（主键）：
 * 更新不改变身份，私有数据通过 app_id 隔离。
 *
 * source_type（当前来源）与 created_origin（最初创建来源）必须分开：
 * 本地创建后导出再导入，created_origin 仍为 LOCAL_CREATED。
 */
@Entity(tableName = "extensions")
data class ExtensionEntity(
    @PrimaryKey
    @ColumnInfo("app_id")
    val appId: String,
    @ColumnInfo("name")
    val name: String,
    @ColumnInfo("description")
    val description: String,
    /** tool / mini_app */
    @ColumnInfo("extension_type")
    val extensionType: String,
    @ColumnInfo("version")
    val version: String,
    /** BUILTIN / LOCAL_CREATED / LOCAL_IMPORT / GITHUB */
    @ColumnInfo("source_type")
    val sourceType: String,
    @ColumnInfo("source_uri")
    val sourceUri: String? = null,
    /** 最初创建来源：LOCAL_CREATED / LOCAL_IMPORT / GITHUB / BUILTIN */
    @ColumnInfo("created_origin")
    val createdOrigin: String,
    @ColumnInfo("manifest_version")
    val manifestVersion: Int,
    /** filesDir 内相对路径，如 extensions/installed/personal_forum */
    @ColumnInfo("install_path")
    val installPath: String,
    /** 当前安装包 SHA-256（Base64），仅完整性校验，不等同身份认证 */
    @ColumnInfo("package_hash")
    val packageHash: String,
    /** 扩展整体启用状态（第一层权限） */
    @ColumnInfo("is_enabled")
    val isEnabled: Boolean = true,
    @ColumnInfo("is_builtin")
    val isBuiltin: Boolean = false,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
    @ColumnInfo("installed_at")
    val installedAt: Long,
)
