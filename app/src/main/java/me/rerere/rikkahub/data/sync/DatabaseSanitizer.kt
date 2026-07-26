package me.rerere.rikkahub.data.sync

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import me.rerere.rikkahub.common.JsonInstant
import me.rerere.rikkahub.core.data.model.MessageNode
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.utils.LogUtil
import androidx.room.Room
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.core.data.utils.VectorUtils
import java.io.File
import kotlin.math.abs

object DatabaseSanitizer {
    private const val TAG = "DatabaseSanitizer"

    data class SanitizationResult(
        val totalRows: Int = 0,
        val skippedRows: Int = 0,
        val skippedBytes: Long = 0,
        val issuesFixed: Int = 0,
        val details: String = "",
        val modifiedSettings: Settings? = null
    ) {
        operator fun plus(other: SanitizationResult) = SanitizationResult(
            totalRows = this.totalRows + other.totalRows,
            skippedRows = this.skippedRows + other.skippedRows,
            skippedBytes = this.skippedBytes + other.skippedBytes,
            issuesFixed = this.issuesFixed + other.issuesFixed,
            details = (this.details + "\n" + other.details).trim(),
            modifiedSettings = other.modifiedSettings ?: this.modifiedSettings
        )
    }

    fun sanitize(context: Context, sourceDbFile: File, settings: Settings? = null): Pair<File, SanitizationResult> {
        LogUtil.i(TAG, "开始数据库物理清洗与逻辑同步: ${sourceDbFile.absolutePath}")
        val targetDbName = "rikka_hub_sanitized"
        val targetDbFile = context.getDatabasePath(targetDbName)

        if (targetDbFile.exists()) {
            context.deleteDatabase(targetDbName)
        }

        val targetRoomDb = Room.databaseBuilder(context, AppDatabase::class.java, targetDbName)
            .allowMainThreadQueries()
            .build()

        val targetDbInfo = targetRoomDb.openHelper.writableDatabase
        targetDbInfo.execSQL("PRAGMA foreign_keys = OFF")

        var totalResult = SanitizationResult()
        var sourceDb: SQLiteDatabase? = null
        var finalSettings = settings

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

            // 白名单：包含类名和物理表名，确保能匹配上
            val whiteList = listOf(
                "ConversationEntity", "MemoryEntity", "GenMediaEntity",
                "ChatEpisodeEntity", "EmbeddingCacheEntity", "embedding_cache",
                "daily_activity", "AgentDiaryEntity", "schedules", "chat_segments",
                "agent_tasks", "token_usage", "books", "book_progress",
                "MilestoneEntity", "user_device_state", "agent_monitor_tasks",
                "assistant_extended_state", "favorites",
                "chat_messages", "chat_message_nodes"
            )

            for (sourceTableName in sourceTables) {
                val isWhitelisted = whiteList.any {
                    val cleanWhite = it.replace("_", "").lowercase()
                    val cleanSource = sourceTableName.replace("_", "").lowercase()
                    cleanWhite == cleanSource || cleanWhite == cleanSource + "entity" || cleanSource == cleanWhite + "entity"
                }

                if (isWhitelisted) {
                    val targetTableName = targetTables.find {
                        val cleanTarget = it.replace("_", "").lowercase()
                        val cleanSource = sourceTableName.replace("_", "").lowercase()
                        cleanTarget == cleanSource
                    }

                    if (targetTableName != null) {
                        val rowCount = sourceDb.rawQuery("SELECT COUNT(*) FROM `$sourceTableName`", null).use { c ->
                            if (c.moveToFirst()) c.getInt(0) else 0
                        }

                        if (rowCount > 0) {
                            LogUtil.i(TAG, "清洗迁移表: $sourceTableName -> $targetTableName ($rowCount 行)")
                            val result = copyTable(sourceDb, targetDbInfo, sourceTableName, targetTableName)
                            totalResult += result
                        }
                    }
                }
            }

            // 从 Settings 中提取智能体待办并同步到 schedules 表 (适配旧备份导入)
            settings?.let { s ->
                val newAssistants = s.assistants.map { assistant ->
                    if (assistant.isMain && assistant.masterMemoryContent.contains("约定与待办")) {
                        val masterContent = assistant.masterMemoryContent
                        val section = masterContent.substringAfter("约定与待办")
                            .substringBefore("##")
                            .trim()

                        val lines = section.split("\n")
                            .map { it.trim().trimStart('-', '*', ' ', '1', '2', '3', '4', '5', '6', '7', '8', '9', '.', ':', '：') }
                            .filter { it.isNotBlank() && !it.startsWith("#") }

                        lines.forEach { line ->
                            val values = ContentValues().apply {
                                put("title", line)
                                put("content", "来自原记忆档案提取")
                                put("start_time", System.currentTimeMillis())
                                put("is_completed", 0)
                                put("category", "assistant")
                                put("created_at", System.currentTimeMillis())
                                put("updated_at", System.currentTimeMillis())
                                put("priority", 1)
                                put("urgency", 1)
                                put("difficulty", 1)
                            }
                            targetDbInfo.insert("schedules", SQLiteDatabase.CONFLICT_IGNORE, values)
                        }

                        val textToFind = "约定与待办"
                        val textIndex = masterContent.indexOf(textToFind)
                        val beforeText = masterContent.substring(0, textIndex)
                        val hashIndex = beforeText.lastIndexOf("##")
                        val startIndex = if (hashIndex != -1 && (textIndex - hashIndex) < 20) hashIndex else textIndex
                        val nextHashIndex = masterContent.indexOf("##", textIndex + textToFind.length)

                        val newContent = if (nextHashIndex != -1) {
                            (masterContent.substring(0, startIndex).trimEnd() + "\n\n" + masterContent.substring(nextHashIndex).trimStart()).trim()
                        } else {
                            masterContent.substring(0, startIndex).trim()
                        }
                        assistant.copy(masterMemoryContent = newContent)
                    } else assistant
                }
                finalSettings = s.copy(assistants = newAssistants)
            }

            // 【宝宝老师的强力修复】：全量溯源校准备份数据
            // 不再只修 0，而是对比节点时间与消息真实时间，只要对不上就修！
            LogUtil.i(TAG, "正在深度校准备份数据中的消息节点时间线...")
            targetDbInfo.execSQL("""
                UPDATE `chat_message_nodes`
                SET `created_at` = (
                    SELECT MIN(`created_at`) FROM `chat_messages`
                    WHERE `chat_messages`.`node_id` = `chat_message_nodes`.`id`
                )
                WHERE `id` IN (
                    SELECT n.id FROM `chat_message_nodes` n
                    INNER JOIN `chat_messages` m ON n.id = m.node_id
                    GROUP BY n.id
                    HAVING ABS(MIN(m.created_at) - n.created_at) > 1000
                )
            """.trimIndent())

            // 同步进度水位线
            targetDbInfo.execSQL("""
                UPDATE `ConversationEntity`
                SET `last_summarized_message_time` = (
                    SELECT MAX(`end_time`)
                    FROM `chat_segments`
                    WHERE `chat_segments`.`conversation_id` = `ConversationEntity`.`id`
                )
                WHERE (`last_summarized_message_time` = 0 OR `last_summarized_message_time` IS NULL)
                AND `id` IN (SELECT `conversation_id` FROM `chat_segments`)
            """.trimIndent())

            targetDbInfo.execSQL("PRAGMA foreign_keys = ON")

        } catch (e: Exception) {
            LogUtil.e(TAG, "清洗过程中发生严重错误", e)
            throw e
        } finally {
            sourceDb?.close()
            targetRoomDb.close()
        }
        return targetDbFile to totalResult.copy(modifiedSettings = finalSettings)
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
                val timestampIdx = cursor.getColumnIndex("timestamp")
                val idIdx = cursor.getColumnIndex("id")

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
                                        Cursor.FIELD_TYPE_STRING -> {
                                            var strValue = cursor.getString(idx)
                                            if (targetTableName.equals("schedules", ignoreCase = true) && colName == "category") {
                                                if (strValue == "General") strValue = "user"
                                            }

                                            if (colName == "embedding" && info.type == "BLOB" && strValue.startsWith("[")) {
                                                try {
                                                    val floats = JsonInstant.decodeFromString<List<Float>>(strValue)
                                                    values.put(colName, VectorUtils.fromList(floats))
                                                } catch (e: Exception) {
                                                    LogUtil.w(TAG, "解析旧向量字符串失败: $strValue")
                                                    values.put(colName, strValue)
                                                }
                                            } else {
                                                values.put(colName, strValue)
                                            }
                                        }
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

                        if (targetTableName.equals("ConversationEntity", ignoreCase = true)) {
                            val nodesJson = values.getAsString("nodes")
                            if (!nodesJson.isNullOrBlank() && nodesJson != "[]") {
                                try {
                                    val convId = values.getAsString("id")
                                    val nodes = JsonInstant.decodeFromString<List<MessageNode>>(nodesJson)
                                    nodes.forEachIndexed { nodeIdx, node ->
                                        val nodeValues = ContentValues().apply {
                                            put("id", node.id.toString())
                                            put("conversation_id", convId)
                                            put("select_index", node.selectIndex)
                                            put("order_index", nodeIdx)
                                            put("created_at", node.timelineCreatedAt)
                                        }
                                        target.insert("chat_message_nodes", SQLiteDatabase.CONFLICT_REPLACE, nodeValues)

                                        node.messages.forEachIndexed { msgIdx, msg ->
                                            val msgJson = JsonInstant.encodeToString(msg)
                                            val timestamp = msg.createdAt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
                                            val msgValues = ContentValues().apply {
                                                put("id", msg.id.toString())
                                                put("node_id", node.id.toString())
                                                put("conversation_id", convId)
                                                put("content_json", msgJson)
                                                put("created_at", timestamp)
                                                put("order_index", msgIdx)
                                            }
                                            target.insert("chat_messages", SQLiteDatabase.CONFLICT_REPLACE, msgValues)
                                        }
                                    }
                                    values.put("nodes", "")
                                } catch (e: Exception) {
                                    LogUtil.e(TAG, "消息拆解失败: ${e.message}")
                                }
                            }
                        }

                        if (targetTableName.equals("chat_segments", ignoreCase = true)) {
                            val originalTimestamp = if (timestampIdx != -1) cursor.getLong(timestampIdx) else 0L
                            val finalTimestamp = if (originalTimestamp > 1000000L) originalTimestamp else System.currentTimeMillis()
                            val startVal = values.getAsLong("start_time") ?: 0L
                            val endVal = values.getAsLong("end_time") ?: 0L
                            if (startVal == 0L || endVal == 0L || abs(endVal - startVal) < 500) {
                                val computedEndTime: Long = finalTimestamp
                                val computedStartTime: Long = computedEndTime - 3600000L
                                values.put("end_time", computedEndTime)
                                values.put("start_time", computedStartTime)
                            }
                        }

                        val result = target.insert(targetTableName, SQLiteDatabase.CONFLICT_REPLACE, values)
                        if (result == -1L) {
                            LogUtil.e(TAG, "[$targetTableName] 插入失败")
                            skipped++
                        }
                    } catch (e: Exception) {
                        LogUtil.e(TAG, "[$targetTableName] 处理异常: ${e.message}")
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
