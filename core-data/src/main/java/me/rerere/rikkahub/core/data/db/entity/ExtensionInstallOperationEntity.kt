package me.rerere.rikkahub.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 扩展安装操作记录表（详设 6.7 / 8.3）。
 *
 * 文件系统 rename 与 Room 事务不是同一事务，本表用于 App 异常退出后的
 * 安装状态恢复：启动时扫描非 COMPLETED 记录，按 operation_state 决定
 * 恢复旧目录或完成提交/清理，保证文件版本与 Registry 记录一致。
 */
@Entity(
    tableName = "extension_install_operations",
    indices = [Index("app_id")],
)
data class ExtensionInstallOperationEntity(
    @PrimaryKey
    @ColumnInfo("operation_id")
    val operationId: String,
    @ColumnInfo("app_id")
    val appId: String,
    /** INSTALL / UPDATE / UNINSTALL */
    @ColumnInfo("operation_type")
    val operationType: String,
    @ColumnInfo("old_version")
    val oldVersion: String? = null,
    @ColumnInfo("target_version")
    val targetVersion: String? = null,
    /** staging 目录（相对 filesDir） */
    @ColumnInfo("staging_path")
    val stagingPath: String? = null,
    /** 旧版本备份目录（相对 filesDir），UPDATE 时记录 */
    @ColumnInfo("backup_path")
    val backupPath: String? = null,
    /** PREPARING / FILES_STAGED / FILES_SWITCHED / DATABASE_COMMITTED / COMPLETED / ROLLBACK_REQUIRED */
    @ColumnInfo("operation_state")
    val operationState: String,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)
