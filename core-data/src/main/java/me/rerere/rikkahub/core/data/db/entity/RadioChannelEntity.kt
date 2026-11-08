package me.rerere.rikkahub.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 电台栏目实体。
 *
 * 栏目归属于用户的电台空间；[creatorId] 仅记录创建者（用户或某个 Agent），
 * 不代表栏目所有权。用户与 Agent 均可通过同一套 RadioService 修改栏目。
 *
 * 音色不配置在栏目级，由创作节目的 Agent 语音配置决定。
 */
@Entity(
    tableName = "radio_channels",
    indices = [
        Index(value = ["sort_order"])
    ]
)
data class RadioChannelEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "description")
    val description: String = "",

    /** 栏目封面文件路径（私有目录相对路径），可为空。 */
    @ColumnInfo(name = "cover")
    val cover: String? = null,

    /** 栏目图标标识（如 Material 图标名），可为空。 */
    @ColumnInfo(name = "icon")
    val icon: String? = null,

    /** 栏目创作提示词，指导 Agent 在该栏目下的节目创作风格。 */
    @ColumnInfo(name = "system_prompt")
    val systemPrompt: String = "",

    /** 创建者 ID（统一字符串命名空间：用户身份或 Agent ID）。 */
    @ColumnInfo(name = "creator_id")
    val creatorId: String,

    /** 排序权重，越小越靠前。 */
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,

    /** 栏目状态，见 [RadioChannelStatus]。 */
    @ColumnInfo(name = "status")
    val status: String = RadioChannelStatus.ACTIVE,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)

/** 栏目状态常量。 */
object RadioChannelStatus {
    const val ACTIVE = "ACTIVE"
}
