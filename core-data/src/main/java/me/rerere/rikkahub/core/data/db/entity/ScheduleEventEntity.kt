package me.rerere.rikkahub.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 日程安排（待办在某一天某个时间段的具体排期）
 *
 * 一个待办（schedules 表）可以关联零条、一条或多条日程，因此 task_id 不设唯一约束。
 * 任务名称、重要度、紧急度、难度、完成状态等信息均从关联待办读取，此处不冗余存储。
 *
 * 时间存储规则：
 * - [date] 使用 epoch day（LocalDate.toEpochDay()），表示日程所属的"自然日"，
 *   按系统本地时区计算，跨时区不会漂移；
 * - [startTime] / [endTime] 使用"当天分钟数"（0..1439），即墙钟时间，
 *   结束时间必须晚于开始时间，且与开始时间属于同一天（不支持跨天日程）。
 *
 * category 与关联待办的 category 保持一致（本期日程规划仅面向 user 类待办，
 * 该字段为未来 assistant 日程预留）。
 */
@Entity(
    tableName = "schedule_events",
    foreignKeys = [
        ForeignKey(
            entity = ScheduleEntity::class,
            parentColumns = ["id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("task_id"),
        Index("date")
    ]
)
data class ScheduleEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "task_id")
    val taskId: Long,
    @ColumnInfo(name = "date")
    val date: Long,
    @ColumnInfo(name = "start_time")
    val startTime: Int,
    @ColumnInfo(name = "end_time")
    val endTime: Int,
    @ColumnInfo(name = "category")
    val category: String = "user",
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
