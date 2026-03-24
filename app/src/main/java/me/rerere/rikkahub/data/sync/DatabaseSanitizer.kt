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

        val targetRoomDb = Room.databaseBuilder(context, AppDatabase::class.java, targetDbName)
            .allowMainThreadQueries()
            .build()

        val targetDbInfo = targetRoomDb.openHelper.writableDatabase

        var totalResult = SanitizationResult()
        var sourceDb: SQLiteDatabase? = null

        try {
            sourceDb = SQLiteDatabase.openDatabase(sourceDbFile.path, null, SQLiteDatabase.OPEN_READWRITE)

            // 【关键日志】：打印出备份库中真实存在的所有表
            val allTables = mutableListOf<String>()
            sourceDb.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
                while (c.moveToNext()) allTables.add(c.getString(0))
            }
            LogUtil.i(TAG, "Source database tables: ${allTables.joinToString()}")

            val tablesToMigrate = listOf(
                "ConversationEntity", "MemoryEntity", "GenMediaEntity",
                "ChatEpisodeEntity", "EmbeddingCacheEntity", "daily_activity",
                "AgentDiaryEntity", "schedules"
            )

            for (table in tablesToMigrate) {
                // 不区分大小写查找表名
                val actualTableName = allTables.find { it.equals(table, ignoreCase = true) }

                if (actualTableName != null) {
                    // 【关键日志】：打印该表在备份包里的行数
                    val rowCount = sourceDb.rawQuery("SELECT COUNT(*) FROM `$actualTableName`", null).use { c ->
                        if (c.moveToFirst()) c.getInt(0) else 0
                    }
                    LogUtil.i(TAG, "Table '$actualTableName' found. Row count in backup: $rowCount")

                    if (rowCount > 0) {
                        val result = copyTable(sourceDb, targetDbInfo, actualTableName)
                        totalResult += result
                        LogUtil.i(TAG, "Successfully migrated $actualTableName: ${result.totalRows} rows.")
                    }
                } else {
                    LogUtil.w(TAG, "Table '$table' is NOT present in the backup file.")
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
        source.query("`$tableName`", null, null, null, null, null, null).use { cursor ->
            val columns = cursor.columnNames
            while (cursor.moveToNext()) {
                rows++
                try {
                    val values = ContentValues()
                    for (col in columns) {
                        val idx = cursor.getColumnIndex(col)
                        when (cursor.getType(idx)) {
                            Cursor.FIELD_TYPE_NULL -> values.putNull(col)
                            Cursor.FIELD_TYPE_INTEGER -> values.put(col, cursor.getLong(idx))
                            Cursor.FIELD_TYPE_FLOAT -> values.put(col, cursor.getDouble(idx))
                            Cursor.FIELD_TYPE_STRING -> values.put(col, cursor.getString(idx))
                            Cursor.FIELD_TYPE_BLOB -> values.put(col, cursor.getBlob(idx))
                        }
                    }
                    target.insert(tableName, SQLiteDatabase.CONFLICT_REPLACE, values)
                } catch (e: Exception) {
                    skipped++
                }
            }
        }
        return SanitizationResult(totalRows = rows, skippedRows = skipped)
    }
}
