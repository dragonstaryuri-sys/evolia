package me.rerere.rikkahub.data.db

import android.util.Log
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.core.data.db.dao.ChatEpisodeDAO
import me.rerere.rikkahub.core.data.db.dao.ConversationDAO
import me.rerere.rikkahub.core.data.db.dao.DailyActivityDAO
import me.rerere.rikkahub.core.data.db.dao.EmbeddingCacheDAO
import me.rerere.rikkahub.core.data.db.dao.GenMediaDAO
import me.rerere.rikkahub.core.data.db.dao.MemoryDAO
import me.rerere.rikkahub.core.data.db.dao.AgentDiaryDAO
import me.rerere.rikkahub.core.data.db.dao.ScheduleDAO
import me.rerere.rikkahub.core.data.db.dao.AgentTaskDAO
import me.rerere.rikkahub.core.data.db.dao.ChatSegmentDAO
import me.rerere.rikkahub.core.data.db.entity.ChatEpisodeEntity
import me.rerere.rikkahub.core.data.db.entity.ConversationEntity
import me.rerere.rikkahub.core.data.db.entity.DailyActivityEntity
import me.rerere.rikkahub.core.data.db.entity.EmbeddingCacheEntity
import me.rerere.rikkahub.core.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.core.data.db.entity.MemoryEntity
import me.rerere.rikkahub.core.data.db.entity.AgentDiaryEntity
import me.rerere.rikkahub.core.data.db.entity.ScheduleEntity
import me.rerere.rikkahub.core.data.db.entity.AgentTaskEntity
import me.rerere.rikkahub.core.data.db.entity.ChatSegmentEntity
import me.rerere.rikkahub.core.data.model.MessageNode
import me.rerere.rikkahub.common.JsonInstant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Database(
    entities = [
        ConversationEntity::class,
        MemoryEntity::class,
        GenMediaEntity::class,
        ChatEpisodeEntity::class,
        EmbeddingCacheEntity::class,
        DailyActivityEntity::class,
        AgentDiaryEntity::class,
        ScheduleEntity::class,
        AgentTaskEntity::class,
        ChatSegmentEntity::class
    ],
    version = 31, // 升级版本：增加 ConversationEntity.isVirtual
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9, spec = Migration_8_9::class),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 13, to = 14),
        AutoMigration(from = 16, to = 17),
        AutoMigration(from = 17, to = 18),
        AutoMigration(from = 18, to = 19),
        AutoMigration(from = 19, to = 20),
        AutoMigration(from = 20, to = 21),
        AutoMigration(from = 21, to = 22),
        AutoMigration(from = 23, to = 24),
        AutoMigration(from = 24, to = 25),
        AutoMigration(from = 27, to = 28),
        AutoMigration(from = 28, to = 29),
        AutoMigration(from = 29, to = 30),
        AutoMigration(from = 30, to = 31), // 新增自动迁移
    ]
)
@TypeConverters(TokenUsageConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDAO

    abstract fun memoryDao(): MemoryDAO

    abstract fun genMediaDao(): GenMediaDAO

    abstract fun chatEpisodeDao(): ChatEpisodeDAO

    abstract fun embeddingCacheDao(): EmbeddingCacheDAO

    abstract fun dailyActivityDao(): DailyActivityDAO

    abstract fun agentDiaryDao(): AgentDiaryDAO

    abstract fun scheduleDao(): ScheduleDAO

    abstract fun agentTaskDao(): AgentTaskDAO

    abstract fun chatSegmentDao(): ChatSegmentDAO

    companion object {
        const val TAG = "AppDatabase"

        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "migrate: creating agent_tasks table")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `agent_tasks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `assistant_id` TEXT NOT NULL,
                        `task_type` TEXT NOT NULL,
                        `task_data` TEXT NOT NULL,
                        `scheduled_time` INTEGER NOT NULL,
                        `end_time` INTEGER,
                        `repeat_interval` INTEGER NOT NULL DEFAULT 0,
                        `is_executed` INTEGER NOT NULL DEFAULT 0,
                        `created_at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "migrate: updating schedules table")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `schedules_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `start_time` INTEGER NOT NULL,
                        `end_time` INTEGER,
                        `reminder_time` INTEGER,
                        `priority` INTEGER NOT NULL DEFAULT 1,
                        `urgency` INTEGER NOT NULL DEFAULT 1,
                        `difficulty` INTEGER NOT NULL DEFAULT 1,
                        `is_completed` INTEGER NOT NULL,
                        `category` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `schedules_new` (id, title, content, start_time, end_time, reminder_time, priority, is_completed, category, created_at, updated_at)
                    SELECT id, title, content, start_time, end_time, reminder_time, priority, is_completed, category, created_at, updated_at FROM schedules
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE schedules")
                db.execSQL("ALTER TABLE schedules_new RENAME TO schedules")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "migrate: start migrate from 11 to 12")
                db.execSQL("ALTER TABLE MemoryEntity ADD COLUMN embedding TEXT")
                db.execSQL("ALTER TABLE MemoryEntity ADD COLUMN type INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE MemoryEntity ADD COLUMN last_accessed_at INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE MemoryEntity ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ChatEpisodeEntity` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `assistant_id` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `embedding` TEXT,
                        `start_time` INTEGER NOT NULL,
                        `end_time` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "migrate: start migrate from 12 to 13")
                val cursor = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='ChatEpisodeEntity'")
                val tableExists = cursor.count > 0
                cursor.close()
                if (tableExists) {
                    val columnCursor = db.query("PRAGMA table_info(ChatEpisodeEntity)")
                    var hasColumn = false
                    while (columnCursor.moveToNext()) {
                        if (columnCursor.getString(1) == "last_accessed_at") {
                            hasColumn = true
                            break
                        }
                    }
                    columnCursor.close()
                    if (!hasColumn) {
                        db.execSQL("ALTER TABLE ChatEpisodeEntity ADD COLUMN last_accessed_at INTEGER NOT NULL DEFAULT 0")
                    }
                }
            }
        }

        val MIGRATION_14_16 = object : Migration(14, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "migrate: start migrate from 14 to 16")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ChatEpisodeEntity_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `assistant_id` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `embedding` TEXT,
                        `start_time` INTEGER NOT NULL,
                        `end_time` INTEGER NOT NULL,
                        `last_accessed_at` INTEGER NOT NULL DEFAULT 0,
                        `significance` INTEGER NOT NULL DEFAULT 5,
                        `conversation_id` TEXT DEFAULT ''
                    )
                    """.trimIndent()
                )

                val cursor = db.query("PRAGMA table_info(ChatEpisodeEntity)")
                var hasConversationId = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == "conversation_id") {
                        hasConversationId = true
                        break
                    }
                }
                cursor.close()

                if (hasConversationId) {
                    db.execSQL(
                        """
                        INSERT INTO ChatEpisodeEntity_new (id, assistant_id, content, embedding, start_time, end_time, last_accessed_at, significance, conversation_id)
                        SELECT id, assistant_id, content, embedding, start_time, end_time, last_accessed_at, significance, conversation_id FROM ChatEpisodeEntity
                        """.trimIndent()
                    )
                } else {
                    db.execSQL(
                        """
                        INSERT INTO ChatEpisodeEntity_new (id, assistant_id, content, embedding, start_time, end_time, last_accessed_at, significance)
                        SELECT id, assistant_id, content, embedding, start_time, end_time, last_accessed_at, significance FROM ChatEpisodeEntity
                        """.trimIndent()
                    )
                }

                db.execSQL("DROP TABLE ChatEpisodeEntity")
                db.execSQL("ALTER TABLE ChatEpisodeEntity_new RENAME TO ChatEpisodeEntity")

                val memoryColumns = mutableListOf<String>()
                val memCursor = db.query("PRAGMA table_info(MemoryEntity)")
                while (memCursor.moveToNext()) {
                    memoryColumns.add(memCursor.getString(1))
                }
                memCursor.close()

                if (!memoryColumns.contains("type")) {
                    db.execSQL("ALTER TABLE MemoryEntity ADD COLUMN type INTEGER NOT NULL DEFAULT 0")
                }
                if (!memoryColumns.contains("last_accessed_at")) {
                    db.execSQL("ALTER TABLE MemoryEntity ADD COLUMN last_accessed_at INTEGER NOT NULL DEFAULT 0")
                }
                if (!memoryColumns.contains("created_at")) {
                    db.execSQL("ALTER TABLE MemoryEntity ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "migrate: start migrate from 22 to 23")
                val cursor = db.query("SELECT id, nodes FROM ConversationEntity")

                var updateCount = 0
                db.beginTransaction()
                try {
                    val statement = db.compileStatement("UPDATE ConversationEntity SET nodes = ? WHERE id = ?")
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0)
                        val nodes = cursor.getString(1)
                        val newNodes = migrateLegacyNodesJson(nodes)
                        if (newNodes != nodes) {
                            statement.bindString(1, newNodes)
                            statement.bindString(2, id)
                            statement.execute()
                            statement.clearBindings()
                            updateCount++
                        }
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                    cursor.close()
                }
                Log.i(TAG, "migrate: migrate from 22 to 23 success ($updateCount conversations updated)")
            }

            private fun migrateLegacyNodesJson(json: String): String {
                try {
                    val element = JsonInstant.parseToJsonElement(json)
                    if (element !is JsonArray) return json

                    val newArray = buildJsonArray {
                        element.jsonArray.forEach { node ->
                            if (node !is JsonObject) {
                                add(node)
                                return@forEach
                            }
                            add(buildJsonObject {
                                node.entries.forEach { (key, value) ->
                                    if (key == "messages" && value is JsonArray) {
                                        put("messages", buildJsonArray {
                                            value.jsonArray.forEach { message ->
                                                if (message !is JsonObject) {
                                                    add(message)
                                                    return@forEach
                                                }
                                                add(buildJsonObject {
                                                    message.entries.forEach { (msgKey, msgValue) ->
                                                        if (msgKey == "parts" && msgValue is JsonArray) {
                                                            put("parts", buildJsonArray {
                                                                msgValue.jsonArray.forEach { part ->
                                                                    if (part !is JsonObject) {
                                                                        add(part)
                                                                        return@forEach
                                                                    }
                                                                    val type = part["type"]?.jsonPrimitive?.content
                                                                    if (type == "me.rerere.ai.ui.UIMessagePart.Thinking") {
                                                                        add(buildJsonObject {
                                                                            put("type", "me.rerere.ai.ui.UIMessagePart.Reasoning")
                                                                            part.entries.forEach { (partKey, partValue) ->
                                                                                when (partKey) {
                                                                                    "type" -> { /* skip */ }
                                                                                    "thinking" -> put("reasoning", partValue)
                                                                                    else -> put(partKey, partValue)
                                                                                }
                                                                            }
                                                                        })
                                                                    } else {
                                                                        add(part)
                                                                    }
                                                                }
                                                            })
                                                        } else {
                                                            put(msgKey, msgValue)
                                                        }
                                                    }
                                                })
                                            }
                                        })
                                    } else {
                                        put(key, value)
                                    }
                                }
                            })
                        }
                    }
                    return newArray.toString()
                } catch (e: Exception) {
                    e.printStackTrace()
                    return json
                }
            }
        }
    }
}

object TokenUsageConverter {
    @TypeConverter
    fun fromTokenUsage(usage: TokenUsage?): String {
        return JsonInstant.encodeToString(usage)
    }

    @TypeConverter
    fun toTokenUsage(usage: String): TokenUsage? {
        return JsonInstant.decodeFromString(usage)
    }
}

val Migration_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(AppDatabase.TAG, "migrate: start migrate from 6 to 7")
        db.beginTransaction()
        try {
            db.execSQL(
                """
                CREATE TABLE ConversationEntity_new (
                    id TEXT NOT NULL PRIMARY KEY,
                    assistant_id TEXT NOT NULL DEFAULT '0950e2dc-9bd5-4801-afa3-aa887aa36b4e',
                    title TEXT NOT NULL,
                    nodes TEXT NOT NULL,
                    usage TEXT,
                    create_at INTEGER NOT NULL,
                    update_at INTEGER NOT NULL,
                    truncate_index INTEGER NOT NULL DEFAULT -1
                )
            """.trimIndent()
            )

            val cursor =
                db.query("SELECT id, assistant_id, title, messages, usage, create_at, update_at, truncate_index FROM ConversationEntity")
            val updates = mutableListOf<Array<Any?>>()

            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val assistantId = cursor.getString(1)
                val title = cursor.getString(2)
                val messagesJson = cursor.getString(3)
                val usage = cursor.getString(4)
                val createAt = cursor.getLong(5)
                val updateAt = cursor.getLong(6)
                val truncateIndex = cursor.getInt(7)

                try {
                    val oldMessages = JsonInstant.decodeFromString<List<UIMessage>>(messagesJson)
                    val newMessages = oldMessages.map { message ->
                        MessageNode.of(message)
                    }
                    val newMessagesJson = JsonInstant.encodeToString(newMessages)
                    updates.add(
                        arrayOf(
                            id,
                            assistantId,
                            title,
                            newMessagesJson,
                            usage,
                            createAt,
                            updateAt,
                            truncateIndex
                        )
                    )
                } catch (e: Exception) {
                    error("Failed to migrate messages for conversation $id: ${e.message}")
                }
            }
            cursor.close()

            updates.forEach { values ->
                db.execSQL(
                    "INSERT INTO ConversationEntity_new (id, assistant_id, title, nodes, usage, create_at, update_at, truncate_index) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    values
                )
            }

            db.execSQL("DROP TABLE ConversationEntity")
            db.execSQL("ALTER TABLE ConversationEntity_new RENAME TO ConversationEntity")
            db.setTransactionSuccessful()
            Log.i(AppDatabase.TAG, "migrate: migrate from 6 to 7 success (${updates.size} conversations updated)")
        } finally {
            db.endTransaction()
        }
    }
}

@DeleteColumn(tableName = "ConversationEntity", columnName = "usage")
class Migration_8_9 : AutoMigrationSpec
