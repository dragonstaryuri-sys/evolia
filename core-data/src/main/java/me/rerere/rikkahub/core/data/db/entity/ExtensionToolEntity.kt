package me.rerere.rikkahub.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 扩展工具本地运行配置表（详设 6.3）。
 *
 * 只存用户运行配置（启用状态 + 自定义描述）；Tool 名称、原始描述、Schema、
 * 执行代码属于扩展包定义，位于安装目录 manifest 内。更新扩展包时刷新
 * 包内定义，但保留本表配置；新版本删除的 Tool 其配置一并清理。
 */
@Entity(
    tableName = "extension_tools",
    indices = [
        Index("app_id"),
        Index(value = ["app_id", "tool_id"], unique = true),
    ],
)
data class ExtensionToolEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo("id")
    val id: Long = 0,
    @ColumnInfo("app_id")
    val appId: String,
    @ColumnInfo("tool_id")
    val toolId: String,
    /** Tool 全局启用状态（第二层权限） */
    @ColumnInfo("enable")
    val enable: Boolean = true,
    /** 用户自定义描述，null = 未自定义（有效描述 = 原始描述兜底） */
    @ColumnInfo("custom_description")
    val customDescription: String? = null,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)
