package me.rerere.rikkahub.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Mini App 私有数据表（详设 6.5）。所有查询必须绑定当前 app_id；
 * 扩展不能直接执行 SQL，只能经 MiniAppStorageService 访问。
 *
 * record_id 由存储服务生成（UUID），一个 collection 内以 (app_id, collection,
 * record_id) 唯一；data 为业务 JSON 字符串。
 */
@Entity(
    tableName = "mini_app_data",
    indices = [
        Index(value = ["app_id", "collection", "record_id"], unique = true),
        Index(value = ["app_id", "collection"]),
    ],
)
data class MiniAppDataEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo("id")
    val id: Long = 0,
    @ColumnInfo("app_id")
    val appId: String,
    @ColumnInfo("collection")
    val collection: String,
    @ColumnInfo("record_id")
    val recordId: String,
    @ColumnInfo("data")
    val data: String,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)
