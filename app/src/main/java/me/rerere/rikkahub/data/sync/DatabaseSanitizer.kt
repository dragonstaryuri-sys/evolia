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
        LogUtil.i(TAG, "开始数据库物理清洗: ${sourceDbFile.absolutePath}")
        val targetDbName = "rikka_hub_sanitized"
        val targetDbFile = context.getDatabasePath(targetDbName)

        if (targetDbFile.exists()) {
            context.deleteDatabase(targetDbName)
        }

        val targetRoomDb = Room.databaseBuilder(context, AppDatabase::class.java, targetDbName)
            .allowMainThreadQueries()
            .build()

        val targetDbInfo = targetRoomDb.openHelper.writableDatabase
        var totalResult = SanitizationResult()
        var sourceDb: SQLiteDatabase? = null

        try {
            sourceDb = SQLiteDatabase.openDatabase(sourceDbFile.path, null, SQLiteDatabase.OPEN_READONLY)

            val sourceTables = mutableListOf<String>()
            sourceDb.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%'", null).use { c ->
                while (c.moveToNext()) sourceTables.add(c.getString(0))
            }

            // 白名单中必须包含新消息表
            val whiteList = listOf(
                "ConversationEntity", "MemoryEntity", "GenMediaEntity",
                "ChatEpisodeEntity", "chat_episode_entity", "EmbeddingCacheEntity",
                "daily_activity", "AgentDiaryEntity", "schedules", "chat_segments",
                "agent_tasks", "token_usage", "books", "book_progress",
                "MilestoneEntity", "user_device_state", "agent_monitor_tasks",
                "assistant_extended_state", "favorites",
                "chat_messages",
                "chat_message_nodes"
            )

            for (tableName in sourceTables) {
                val isWhitelisted = whiteList.any { it.replace("_", "").equals(tableName.replace("_", ""), ignoreCase = true) }
                if (isWhitelisted) {
                    // 恢复 rowCount 检查，用于精细日志输出并跳过空表
                    val rowCount = sourceDb.rawQuery("SELECT COUNT(*) FROM `$tableName`", null).use { c ->
                        if (c.moveToFirst()) c.getInt(0) else 0
                    }

                    if (rowCount > 0) {
                        LogUtil.i(TAG, "发现数据表 '$tableName' ($rowCount 行). 正在执行物理拷贝...")
                        val result = copyTable(sourceDb, targetDbInfo, tableName)
                        totalResult += result
                    }
                }
            }
        } catch (e: Exception) {
            LogUtil.e(TAG, "物理清洗失败", e)
            throw e
        } finally {
            sourceDb?.close()
            targetRoomDb.close()
        }
        return targetDbFile to totalResult
    }

    private fun copyTable(source: SQLiteDatabase, target: androidx.sqlite.db.SupportSQLiteDatabase, tableName: String): SanitizationResult {
        var rows = 0
        var skipped = 0
        try {
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
                        target.insert(tableName, SQLiteDatabase.CONFLICT_REPLACE, values)
                    } catch (e: Exception) {
                        // 恢复针对特定“表不存在”错误的容错逻辑
                        if (e.message?.contains("no such table") == true) {
                             LogUtil.e(TAG, "目标库缺失物理表 '$tableName'，跳过内容迁移")
                             return SanitizationResult(skippedRows = rows)
                        }
                        skipped++
                    }
                }
            }
        } catch (e: Exception) {
            LogUtil.e(TAG, "物理拷贝 $tableName 失败", e)
        }
        return SanitizationResult(totalRows = rows, skippedRows = skipped)
    }
}
