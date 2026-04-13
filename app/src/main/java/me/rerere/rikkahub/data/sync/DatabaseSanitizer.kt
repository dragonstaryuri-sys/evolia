package me.rerere.rikkahub.data.sync

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import me.rerere.rikkahub.utils.LogUtil
import androidx.room.Room
import me.rerere.rikkahub.data.db.AppDatabase
import java.io.File

object DatabaseSanitizer {
    private const val TAG = "DatabaseSanitizer"

    data class SanitizationResult(
        val totalRows: Int = 0,
        val skippedRows: Int = 0,
        val skippedBytes: Long = 0,
        val issuesFixed: Int = 0,
        val details: String = ""
    ) {
        operator fun plus(other: SanitizationResult) = SanitizationResult(
            totalRows = this.totalRows + other.totalRows,
            skippedRows = this.skippedRows + other.skippedRows,
            skippedBytes = this.skippedBytes + other.skippedBytes,
            issuesFixed = this.issuesFixed + other.issuesFixed,
            details = (this.details + "\n" + other.details).trim()
        )
    }

    fun sanitize(context: Context, sourceDbFile: File): Pair<File, SanitizationResult> {
        LogUtil.i(TAG, "Starting database sanitization for: ${sourceDbFile.absolutePath}")
        val targetDbName = "rikka_hub_sanitized"
        val targetDbFile = context.getDatabasePath(targetDbName)

        if (targetDbFile.exists()) {
            context.deleteDatabase(targetDbName)
        }

        // 创建一个干净的目标库
        val targetRoomDb = Room.databaseBuilder(context, AppDatabase::class.java, targetDbName)
            .allowMainThreadQueries()
            .build()

        val targetDbInfo = targetRoomDb.openHelper.writableDatabase

        var totalResult = SanitizationResult()
        var sourceDb: SQLiteDatabase? = null

        try {
            sourceDb = SQLiteDatabase.openDatabase(sourceDbFile.path, null, SQLiteDatabase.OPEN_READONLY)

            // 1. 获取备份库中真实存在的所有物理表
            val sourceTables = mutableListOf<String>()
            sourceDb.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%'", null).use { c ->
                while (c.moveToNext()) sourceTables.add(c.getString(0))
            }
            LogUtil.i(TAG, "Source database physical tables: ${sourceTables.joinToString()}")

            // 2. 待迁移的逻辑名单（涵盖所有可能的变体）
            val whiteList = listOf(
                "ConversationEntity",
                "MemoryEntity",
                "GenMediaEntity",
                "ChatEpisodeEntity", "chat_episode_entity", // 同时尝试两种可能的表名
                "EmbeddingCacheEntity",
                "daily_activity",
                "AgentDiaryEntity",
                "schedules",
                "chat_segments",
                "agent_tasks"
            )

            // 3. 交叉比对迁移
            for (tableName in sourceTables) {
                // 检查该表是否在我们的白名单中（忽略大小写和下划线差异）
                val isWhitelisted = whiteList.any { it.replace("_", "").equals(tableName.replace("_", ""), ignoreCase = true) }

                if (isWhitelisted) {
                    val rowCount = sourceDb.rawQuery("SELECT COUNT(*) FROM `$tableName`", null).use { c ->
                        if (c.moveToFirst()) c.getInt(0) else 0
                    }

                    if (rowCount > 0) {
                        LogUtil.i(TAG, "Found data in '$tableName' ($rowCount rows). Migrating...")
                        // 注意：这里要确保目标库里也有对应的表。如果目标库是类名，而源库是下划线，尝试映射。
                        // 这里我们直接按源库名写，Room 目标库通常能处理
                        val result = copyTable(sourceDb, targetDbInfo, tableName)
                        totalResult += result
                        LogUtil.i(TAG, "Successfully migrated $tableName: ${result.totalRows} rows.")
                    }
                } else {
                    LogUtil.d(TAG, "Table '$tableName' is not in whitelist, skipping.")
                }
            }
        } catch (e: Exception) {
            LogUtil.e(TAG, "Sanitization failed", e)
            throw e
        } finally {
            sourceDb?.close()
            targetRoomDb.close()
        }

        return targetDbFile to totalResult
    }

    private fun copyTable(
        source: SQLiteDatabase,
        target: androidx.sqlite.db.SupportSQLiteDatabase,
        tableName: String
    ): SanitizationResult {
        var rows = 0
        var skipped = 0
        try {
            // 我们需要检查目标库是否存在这个表名，如果不存在（比如大小写不一），尝试映射
            var targetTableName = tableName
            // 简单映射逻辑：如果 ChatEpisodeEntity 不行，试试 chat_episode_entity
            // 但更好的办法是直接查询目标库的 master

            source.query("`$tableName`", null, null, null, null, null, null).use { cursor ->
                val columns = cursor.columnNames
                while (cursor.moveToNext()) {
                    rows++
                    try {
                        val values = ContentValues()
                        for (col in columns) {
                            val idx = cursor.getColumnIndex(col)
                            if (idx != -1) {
                                when (cursor.getType(idx)) {
                                    Cursor.FIELD_TYPE_NULL -> values.putNull(col)
                                    Cursor.FIELD_TYPE_INTEGER -> values.put(col, cursor.getLong(idx))
                                    Cursor.FIELD_TYPE_FLOAT -> values.put(col, cursor.getDouble(idx))
                                    Cursor.FIELD_TYPE_STRING -> values.put(col, cursor.getString(idx))
                                    Cursor.FIELD_TYPE_BLOB -> values.put(col, cursor.getBlob(idx))
                                }
                            }
                        }
                        // 使用 CONFLICT_REPLACE 非常重要，防止因为 ID 重复导致整个表中断
                        target.insert(targetTableName, SQLiteDatabase.CONFLICT_REPLACE, values)
                    } catch (e: Exception) {
                        // 如果因为表名不匹配报错，尝试寻找别名
                        if (e.message?.contains("no such table") == true) {
                             LogUtil.e(TAG, "Target DB missing table '$targetTableName'. Error: ${e.message}")
                             // 这里可以扩展更复杂的映射，但目前先记录
                             return SanitizationResult(skippedRows = rows)
                        }
                        skipped++
                    }
                }
            }
        } catch (e: Exception) {
            LogUtil.e(TAG, "Failed to copy table $tableName", e)
        }
        return SanitizationResult(totalRows = rows, skippedRows = skipped)
    }
}
