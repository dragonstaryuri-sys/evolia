package me.rerere.rikkahub.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Mini App 版本表（详设 6.6）。扩展代码版本与数据 Schema 版本独立管理：
 * 数据迁移以本表的 schema_version 为准，与 extensions.version 无关。
 */
@Entity(
    tableName = "mini_app_versions",
    indices = [Index("app_id")],
)
data class MiniAppVersionEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo("id")
    val id: Long = 0,
    @ColumnInfo("app_id")
    val appId: String,
    @ColumnInfo("version")
    val version: String,
    @ColumnInfo("schema_version")
    val schemaVersion: Int,
    @ColumnInfo("installed_at")
    val installedAt: Long,
)
