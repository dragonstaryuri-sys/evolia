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

    /**
     * Sanitizes the given source database file by copying valid data to a new database.
     * Returns the path to the sanitized database file.
     */
    fun sanitize(context: Context, sourceDbFile: File): Pair<File, SanitizationResult> {
        LogUtil.i(TAG, "Starting database sanitization for: ${sourceDbFile.absolutePath}")
        val targetDbName = "rikka_hub_sanitized"
        val targetDbFile = context.getDatabasePath(targetDbName)

        // Ensure clean state for target
        if (targetDbFile.exists()) {
            context.deleteDatabase(targetDbName)
            LogUtil.i(TAG, "Deleted existing sanitized database file.")
        }

        // Initialize Target DB using Room to ensure schema creation
        val targetRoomDb = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            targetDbName
        )
            .allowMainThreadQueries() // Only for migration utility
            .build()

        // Force Open to create tables
        val targetDbInfo = targetRoomDb.openHelper.writableDatabase
        LogUtil.i(TAG, "Target database initialized and tables created.")

        var totalResult = SanitizationResult()
        var sourceDb: SQLiteDatabase? = null

        try {
            val db = SQLiteDatabase.openDatabase(
                sourceDbFile.path,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
            sourceDb = db

            val tables = listOf(
                "ConversationEntity",
                "MemoryEntity",
                "GenMediaEntity",
                "ChatEpisodeEntity",
                "EmbeddingCacheEntity",
                "daily_activity",
                "AgentDiaryEntity",
            )

            for (table in tables) {
                // Check if table exists in source
                try {
                    val cursor = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table))
                    val exists = cursor.count > 0
                    cursor.close()

                    if (exists) {
                        LogUtil.i(TAG, "Processing table: $table")
                        val result = copyTable(db, targetDbInfo, table)
                        totalResult += result
                        LogUtil.i(TAG, "Sanitized table $table: rows=${result.totalRows}, skipped=${result.skippedRows}")
                    } else {
                        LogUtil.w(TAG, "Table $table NOT FOUND in source database, skipping")
                    }
                } catch (e: Exception) {
                    LogUtil.e(TAG, "Failed to check/process table $table", e)
                }
            }

        } catch (e: Exception) {
            LogUtil.e(TAG, "Critical error during sanitization", e)
            throw e
        } finally {
            sourceDb?.close()
            targetRoomDb.close()
        }

        LogUtil.i(TAG, "Sanitization finished. Total rows processed: ${totalResult.totalRows}")
        return targetDbFile to totalResult
    }

    private fun copyTable(
        source: SQLiteDatabase,
        target: androidx.sqlite.db.SupportSQLiteDatabase,
        tableName: String
    ): SanitizationResult {
        var rows = 0
        var skipped = 0
        var skippedBytes = 0L

        var cursor: Cursor? = null
        try {
            cursor = source.query(tableName, null, null, null, null, null, null)

            // Get column names
            val columnNames = cursor.columnNames
            LogUtil.d(TAG, "Table $tableName columns: ${columnNames.joinToString()}")

            while (cursor.moveToNext()) {
                rows++
                try {
                    val values = ContentValues()
                    var rowBytes = 0L

                    for (colName in columnNames) {
                        val index = cursor.getColumnIndex(colName)
                        if (index < 0) continue

                        when (cursor.getType(index)) {
                            Cursor.FIELD_TYPE_NULL -> values.putNull(colName)
                            Cursor.FIELD_TYPE_INTEGER -> values.put(colName, cursor.getLong(index))
                            Cursor.FIELD_TYPE_FLOAT -> values.put(colName, cursor.getDouble(index))
                            Cursor.FIELD_TYPE_STRING -> {
                                val str = cursor.getString(index)
                                values.put(colName, str)
                                rowBytes += str.length
                            }
                            Cursor.FIELD_TYPE_BLOB -> {
                                val blob = cursor.getBlob(index)
                                values.put(colName, blob)
                                rowBytes += blob.size
                            }
                        }
                    }

                    val insertId = target.insert(tableName, SQLiteDatabase.CONFLICT_REPLACE, values)
                    if (insertId == -1L) {
                        LogUtil.w(TAG, "Failed to insert row $rows in $tableName")
                        skipped++
                    }

                } catch (e: Exception) {
                    LogUtil.w(TAG, "Error copying row $rows in $tableName: ${e.message}")
                    skipped++
                }
            }
        } catch (e: Exception) {
            LogUtil.e(TAG, "Failed to query table $tableName", e)
            return SanitizationResult(totalRows = rows, skippedRows = rows, details = "Failed to read table $tableName: ${e.message}")
        } finally {
            cursor?.close()
        }

        return SanitizationResult(
            totalRows = rows,
            skippedRows = skipped,
            skippedBytes = skippedBytes,
            details = if (skipped > 0) "Skipped $skipped rows in $tableName" else ""
        )
    }
}
