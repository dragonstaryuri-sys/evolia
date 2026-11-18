package me.rerere.rikkahub.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 扩展权限表（详设 6.4）。Manifest 申请状态与用户授权状态分离：
 * 安装时登记申请，granted 由用户授权决定；更新时新增权限必须重新申请，
 * 不得自动授予。
 */
@Entity(
    tableName = "extension_permissions",
    indices = [
        Index("app_id"),
        Index(value = ["app_id", "permission"], unique = true),
    ],
)
data class ExtensionPermissionEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo("id")
    val id: Long = 0,
    @ColumnInfo("app_id")
    val appId: String,
    @ColumnInfo("permission")
    val permission: String,
    @ColumnInfo("granted")
    val granted: Boolean = false,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)
