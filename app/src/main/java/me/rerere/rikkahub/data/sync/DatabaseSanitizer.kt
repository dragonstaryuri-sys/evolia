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

        // 关键：关闭外键约束，防止因为拷贝顺序导致插入失败
        targetDbInfo.execSQL("PRAGMA foreign_keys = OFF")

        var totalResult = SanitizationResult()
        var sourceDb: SQLiteDatabase? = null

        try {
            sourceDb = SQLiteDatabase.openDatabase(sourceDbFile.path, null, SQLiteDatabase.OPEN_READONLY)

            val sourceTables = mutableListOf<String>()
            sourceDb.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%'", null).use { c ->
                while (c.moveToNext()) sourceTables.add(c.getString(0))
            }

            val targetTables = mutableListOf<String>()
            targetDbInfo.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%'").use { c ->
                while (c.moveToNext()) targetTables.add(c.getString(0))
            }

            val whiteList = listOf(
                "ConversationEntity", "MemoryEntity", "GenMediaEntity",
                "ChatEpisodeEntity", "chat_episode_entity", "EmbeddingCacheEntity",
                "daily_activity", "AgentDiaryEntity", "schedules", "chat_segments",
                "agent_tasks", "token_usage", "books", "book_progress",
                "MilestoneEntity", "user_device_state", "agent_monitor_tasks",
                "assistant_extended_state", "favorites",
                "chat_messages", "chat_message_nodes"
            )

            for (sourceTableName in sourceTables) {
                val isWhitelisted = whiteList.any { it.replace("_", "").equals(sourceTableName.replace("_", ""), ignoreCase = true) }
                if (isWhitelisted) {
                    val targetTableName = targetTables.find { it.replace("_", "").equals(sourceTableName.replace("_", ""), ignoreCase = true) }

                    if (targetTableName != null) {
                        val rowCount = sourceDb.rawQuery("SELECT COUNT(*) FROM `$sourceTableName`", null).use { c ->
                            if (c.moveToFirst()) c.getInt(0) else 0
                        }

                        if (rowCount > 0) {
                            LogUtil.i(TAG, "迁移表: $sourceTableName -> $targetTableName ($rowCount 行)")
                            val result = copyTable(sourceDb, targetDbInfo, sourceTableName, targetTableName)
                            totalResult += result
                        }
                    }
                }
            }

            // 重新开启外键
            targetDbInfo.execSQL("PRAGMA foreign_keys = ON")

        } catch (e: Exception) {
            LogUtil.e(TAG, "清洗过程中发生严重错误", e)
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
        sourceTableName: String,
        targetTableName: String
    ): SanitizationResult {
        var rows = 0
        var skipped = 0
        try {
            val targetColumnsInfo = mutableMapOf<String, ColumnInfo>()
            target.query("PRAGMA table_info(`$targetTableName`)").use { c ->
                val nameIdx = c.getColumnIndex("name")
                val notNullIdx = c.getColumnIndex("notnull")
                val dfltIdx = c.getColumnIndex("dflt_value")
                val typeIdx = c.getColumnIndex("type")
                while (c.moveToNext()) {
                    val name = c.getString(nameIdx)
                    targetColumnsInfo[name] = ColumnInfo(
                        name = name,
                        type = c.getString(typeIdx).uppercase(),
                        isNotNull = c.getInt(notNullIdx) == 1,
                        hasDefault = !c.isNull(dfltIdx)
                    )
                }
            }

            source.query("`$sourceTableName`", null, null, null, null, null, null).use { cursor ->
                val sourceColumns = cursor.columnNames.toSet()

                while (cursor.moveToNext()) {
                    rows++
                    try {
                        val values = ContentValues()
                        for ((colName, info) in targetColumnsInfo) {
                            if (sourceColumns.contains(colName)) {
                                val idx = cursor.getColumnIndex(colName)
                                if (idx != -1) {
                                    when (cursor.getType(idx)) {
                                        Cursor.FIELD_TYPE_NULL -> values.putNull(colName)
                                        Cursor.FIELD_TYPE_INTEGER -> values.put(colName, cursor.getLong(idx))
                                        Cursor.FIELD_TYPE_FLOAT -> values.put(colName, cursor.getDouble(idx))
                                        Cursor.FIELD_TYPE_STRING -> values.put(colName, cursor.getString(idx))
                                        Cursor.FIELD_TYPE_BLOB -> values.put(colName, cursor.getBlob(idx))
                                    }
                                }
                            } else if (info.isNotNull && !info.hasDefault) {
                                when {
                                    info.type.contains("INT") -> values.put(colName, 0L)
                                    info.type.contains("REAL") || info.type.contains("FLOA") -> values.put(colName, 0.0)
                                    else -> values.put(colName, "")
                                }
                            }
                        }

                        // 针对 v14/v15 chat_segments 唯一索引 (conv_id, start_time) 的特殊补全逻辑
                        if (targetTableName.equals("chat_segments", ignoreCase = true)) {
                            val startTime = values.getAsLong("start_time") ?: 0L
                            if (startTime == 0L) {
                                val timestamp = values.getAsLong("timestamp") ?: System.currentTimeMillis()
                                val idIdx = cursor.getColumnIndex("id")
                                val id = if (idIdx != -1) cursor.getLong(idIdx) else rows.toLong()
                                // 加上 id 偏移量确保 start_time 绝对唯一，防止 CONFLICT_REPLACE 导致数据被覆盖
                                val fixedTime = (if (timestamp > 0) timestamp else System.currentTimeMillis()) + id
                                values.put("start_time", fixedTime)
                                values.put("end_time", fixedTime + 1)
                            }
                        }

                        val result = target.insert(targetTableName, SQLiteDatabase.CONFLICT_REPLACE, values)
                        if (result == -1L) {
                            LogUtil.e(TAG, "[$targetTableName] 第 $rows 行插入失败")
                            skipped++
                        }
                    } catch (e: Exception) {
                        LogUtil.e(TAG, "[$targetTableName] 行处理异常: ${e.message}")
                        skipped++
                    }
                }
            }
        } catch (e: Exception) {
            LogUtil.e(TAG, "拷贝表 $sourceTableName 失败", e)
        }
        return SanitizationResult(totalRows = rows, skippedRows = skipped)
    }

    private data class ColumnInfo(
        val name: String,
        val type: String,
        val isNotNull: Boolean,
        val hasDefault: Boolean
    )
}
