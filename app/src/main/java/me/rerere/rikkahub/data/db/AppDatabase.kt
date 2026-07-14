package me.rerere.rikkahub.data.db

import android.util.Log
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.ai.core.TokenUsage
import me.rerere.rikkahub.core.data.db.dao.*
import me.rerere.rikkahub.core.data.db.entity.*
import me.rerere.rikkahub.common.JsonInstant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.rikkahub.core.data.model.MessageNode

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
        ChatSegmentEntity::class,
        TokenUsageEntity::class,
        BookEntity::class,
        BookProgressEntity::class,
        AssistantExtendedStateEntity::class,
        MilestoneEntity::class,
        UserDeviceStateEntity::class,
        AgentMonitorTaskEntity::class,
        FavoriteEntity::class,
        ChatMessageNodeEntity::class,
        ChatMessageEntity::class
    ],
    version = 19,
    exportSchema = true
)
@TypeConverters(TokenUsageConverter::class, AssistantExtendedStateConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDAO
    abstract fun chatMessageDao(): ChatMessageDAO
    abstract fun memoryDao(): MemoryDAO
    abstract fun genMediaDao(): GenMediaDAO
    abstract fun chatEpisodeDao(): ChatEpisodeDAO
    abstract fun embeddingCacheDao(): EmbeddingCacheDAO
    abstract fun dailyActivityDao(): DailyActivityDAO
    abstract fun agentDiaryDao(): AgentDiaryDAO
    abstract fun scheduleDao(): ScheduleDAO
    abstract fun agentTaskDao(): AgentTaskDAO
    abstract fun chatSegmentDao(): ChatSegmentDAO
    abstract fun tokenUsageDao(): TokenUsageDAO
    abstract fun bookDao(): BookDAO
    abstract fun assistantExtendedStateDao(): AssistantExtendedStateDAO
    abstract fun milestoneDao(): MilestoneDAO
    abstract fun userDeviceStateDao(): UserDeviceStateDAO
    abstract fun agentMonitorTaskDao(): AgentMonitorTaskDAO
    abstract fun favoriteDao(): FavoriteDAO

    companion object {
        const val TAG = "AppDatabase"

        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.v(TAG, "开始 18->19 迁移：迁移 schedules 表 category 数据 (General -> user)")
                // 将旧有的 General 迁移为 user
                db.execSQL("UPDATE `schedules` SET `category` = 'user' WHERE `category` = 'General'")
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.v(TAG, "开始 17->18 迁移：物理转换向量列为 BLOB")

                // 1. 处理 MemoryEntity
                db.execSQL("CREATE TABLE `MemoryEntity_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `assistant_id` TEXT NOT NULL, `content` TEXT NOT NULL, `keywords` TEXT, `embedding` BLOB, `embedding_model_id` TEXT DEFAULT '', `type` INTEGER NOT NULL DEFAULT 0, `last_accessed_at` INTEGER NOT NULL DEFAULT 0, `created_at` INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("INSERT INTO `MemoryEntity_new` (`id`, `assistant_id`, `content`, `keywords`, `embedding_model_id`, `type`, `last_accessed_at`, `created_at`) SELECT `id`, `assistant_id`, `content`, `keywords`, `embedding_model_id`, `type`, `last_accessed_at`, `created_at` FROM `MemoryEntity`")
                db.execSQL("DROP TABLE `MemoryEntity`")
                db.execSQL("ALTER TABLE `MemoryEntity_new` RENAME TO `MemoryEntity`")

                // 2. 处理 chat_segments
                db.execSQL("CREATE TABLE `chat_segments_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `assistant_id` TEXT NOT NULL, `conversation_id` TEXT NOT NULL, `content` TEXT NOT NULL, `keywords` TEXT, `start_index` INTEGER NOT NULL, `end_index` INTEGER NOT NULL, `start_time` INTEGER NOT NULL, `end_time` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `embedding` BLOB, `embedding_model_id` TEXT DEFAULT '', `recall_count` INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("INSERT INTO `chat_segments_new` (`id`, `assistant_id`, `conversation_id`, `content`, `keywords`, `start_index`, `end_index`, `start_time`, `end_time`, `timestamp`, `embedding_model_id`, `recall_count`) SELECT `id`, `assistant_id`, `conversation_id`, `content`, `keywords`, `start_index`, `end_index`, `start_time`, `end_time`, `timestamp`, `embedding_model_id`, `recall_count` FROM `chat_segments`")
                db.execSQL("DROP TABLE `chat_segments`")
                db.execSQL("ALTER TABLE `chat_segments_new` RENAME TO `chat_segments`")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_chat_segments_conversation_id_start_time` ON `chat_segments` (`conversation_id`, `start_time`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_segments_conversation_id` ON `chat_segments` (`conversation_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_segments_assistant_id` ON `chat_segments` (`assistant_id`)")

                // 3. 处理 ChatEpisodeEntity (修正了导致闪退的默认值定义)
                db.execSQL("CREATE TABLE `ChatEpisodeEntity_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `assistant_id` TEXT NOT NULL, `content` TEXT NOT NULL, `keywords` TEXT, `embedding` BLOB, `embedding_model_id` TEXT DEFAULT '', `start_time` INTEGER NOT NULL, `end_time` INTEGER NOT NULL, `last_accessed_at` INTEGER NOT NULL DEFAULT 0, `significance` INTEGER NOT NULL DEFAULT 5, `conversation_id` TEXT DEFAULT '')")
                db.execSQL("INSERT INTO `ChatEpisodeEntity_new` (`id`, `assistant_id`, `content`, `keywords`, `embedding_model_id`, `start_time`, `end_time`, `last_accessed_at`, `significance`, `conversation_id`) SELECT `id`, `assistant_id`, `content`, `keywords`, `embedding_model_id`, `start_time`, `end_time`, `last_accessed_at`, `significance`, `conversation_id` FROM `ChatEpisodeEntity`")
                db.execSQL("DROP TABLE `ChatEpisodeEntity`")
                db.execSQL("ALTER TABLE `ChatEpisodeEntity_new` RENAME TO `ChatEpisodeEntity`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ChatEpisodeEntity_assistant_id_end_time` ON `ChatEpisodeEntity` (`assistant_id`, `end_time`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_ChatEpisodeEntity_conversation_id` ON `ChatEpisodeEntity` (`conversation_id`)")

                // 4. 重建缓存表
                db.execSQL("DROP TABLE IF EXISTS `embedding_cache`")
                db.execSQL("CREATE TABLE IF NOT EXISTS `embedding_cache` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `memory_id` INTEGER NOT NULL, `memory_type` INTEGER NOT NULL, `model_id` TEXT NOT NULL, `embedding` BLOB NOT NULL, `created_at` INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_embedding_cache_memory_id_memory_type_model_id` ON `embedding_cache` (`memory_id`, `memory_type`, `model_id`)")
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.v(TAG, "开始 16->17 迁移：清理弃用的虚拟世界索引")
                // 物理删除旧索引，Room 在代码中删除索引后，必须在此手动执行 SQL 才能通过启动校验
                db.execSQL("DROP INDEX IF EXISTS `index_ConversationEntity_is_virtual` ")
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.v("AppDatabase", "开始 15->16 迁移，当前数据库文件版本为: ${db.version}")
                // 1. 首先删除旧的、基于索引的唯一约束
                db.execSQL("DROP INDEX IF EXISTS `index_chat_segments_conversation_id_start_index` ")

                // 2. 【强化修复逻辑】：针对差值为 1 或错误的迁移结果进行拨乱反正
                // 如果 end_time - start_time 不等于 900,000 (15min)，说明迁移逻辑错误，强制重置
                db.execSQL("""
                    UPDATE `chat_segments`
                    SET `start_time` = CAST(`timestamp` AS INTEGER) - 900000,
                        `end_time` = CAST(`timestamp` AS INTEGER)
                    WHERE `start_time` IS NULL
                       OR `start_time` = 0
                       OR ABS(`end_time` - `start_time` - 900000) > 100
                """.trimIndent())

                // 3. 【精准去重】：清理因补全可能产生的重复数据
                db.execSQL("""
                    DELETE FROM `chat_segments`
                    WHERE `id` NOT IN (
                        SELECT MAX(`id`)
                        FROM `chat_segments`
                        GROUP BY `conversation_id`, `start_time`
                    )
                """.trimIndent())

                // 4. 【同步进度】：确保 ConversationEntity 知道水位线
                db.execSQL("""
                    UPDATE ConversationEntity
                    SET last_summarized_message_time = (
                        SELECT MAX(end_time)
                        FROM chat_segments
                        WHERE chat_segments.conversation_id = ConversationEntity.id
                    )
                    WHERE (last_summarized_message_time = 0 OR last_summarized_message_time IS NULL)
                    AND id IN (SELECT conversation_id FROM chat_segments)
                """.trimIndent())

                // 5. 最后，创建 unique index (conversation_id, start_time)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_chat_segments_conversation_id_start_time` ON `chat_segments` (`conversation_id`, `start_time`)")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {

            override fun migrate(db: SupportSQLiteDatabase) {
                Log.v("AppDatabase", "开始 14->15 迁移，当前数据库文件版本为: ${db.version}")
                // 1. 为消息表增加逻辑删除字段
                db.execSQL("ALTER TABLE `chat_messages` ADD COLUMN `is_deleted` INTEGER NOT NULL DEFAULT 0")

                // 2. 为会话表增加时间戳进度字段
                db.execSQL("ALTER TABLE `ConversationEntity` ADD COLUMN `last_summarized_message_time` INTEGER NOT NULL DEFAULT 0")

                // 3. 为片段表增加时间范围字段
                db.execSQL("ALTER TABLE `chat_segments` ADD COLUMN `start_time` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `chat_segments` ADD COLUMN `end_time` INTEGER NOT NULL DEFAULT 0")

                // 4. 数据补偿逻辑：显式转换 timestamp 确保读取的是大整数
                db.execSQL("""
                    UPDATE `chat_segments`
                    SET `start_time` = CAST(`timestamp` AS INTEGER) - 900000,
                        `end_time` = CAST(`timestamp` AS INTEGER)
                """.trimIndent())
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.v("AppDatabase", "开始 13->14 迁移，当前数据库文件版本为: ${db.version}")
                // 1. 创建 chat_message_nodes 表
                db.execSQL(
                    """
            CREATE TABLE IF NOT EXISTS `chat_message_nodes` (
                `id` TEXT NOT NULL,
                `conversation_id` TEXT NOT NULL,
                `select_index` INTEGER NOT NULL,
                `order_index` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`conversation_id`) REFERENCES `ConversationEntity`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent()
                )

                // 补上索引
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_message_nodes_conversation_id` ON `chat_message_nodes` (`conversation_id`)")

                // 2. 创建 chat_messages 表
                db.execSQL(
                    """
            CREATE TABLE IF NOT EXISTS `chat_messages` (
                `id` TEXT NOT NULL,
                `node_id` TEXT NOT NULL,
                `conversation_id` TEXT NOT NULL,
                `content_json` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                `order_index` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`node_id`) REFERENCES `chat_message_nodes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_node_id` ON `chat_messages` (`node_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_conversation_id` ON `chat_messages` (`conversation_id`)")

                val cursor = db.query("SELECT id, nodes FROM ConversationEntity")
                if (cursor.moveToFirst()) {
                    do {
                        val convId = cursor.getString(0)
                        val nodesJson = cursor.getString(1)
                        if (nodesJson.isNullOrBlank() || nodesJson == "[]") continue
                        try {
                            val nodes = JsonInstant.decodeFromString<List<MessageNode>>(nodesJson)
                            nodes.forEachIndexed { nodeIdx, node ->
                                db.execSQL(
                                    "INSERT OR REPLACE INTO chat_message_nodes (id, conversation_id, select_index, order_index) VALUES (?, ?, ?, ?)",
                                    arrayOf(node.id.toString(), convId, node.selectIndex, nodeIdx)
                                )
                                node.messages.forEachIndexed { msgIdx, msg ->
                                    val msgJson = JsonInstant.encodeToString(msg)
                                    val timestamp =
                                        msg.createdAt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
                                    db.execSQL(
                                        "INSERT OR REPLACE INTO chat_messages (id, node_id, conversation_id, content_json, created_at, order_index) VALUES (?, ?, ?, ?, ?, ?)",
                                        arrayOf(
                                            msg.id.toString(),
                                            node.id.toString(),
                                            convId,
                                            msgJson,
                                            timestamp,
                                            msgIdx
                                        )
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to migrate conversation nodes for $convId", e)
                        }
                    } while (cursor.moveToNext())
                }
                cursor.close()
                db.execSQL("UPDATE ConversationEntity SET nodes = ''")
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_segments ADD COLUMN embedding_model_id TEXT DEFAULT ''")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `assistant_extended_state` (`assistantId` TEXT NOT NULL, `personality` TEXT NOT NULL, `appearance` TEXT NOT NULL, `preferences` TEXT NOT NULL, `diet` TEXT NOT NULL, `taboos` TEXT NOT NULL, `interactionHabits` TEXT NOT NULL, `relationships` TEXT NOT NULL, PRIMARY KEY(`assistantId`))")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `MilestoneEntity` (`id` TEXT NOT NULL, `assistant_id` TEXT NOT NULL, `time` TEXT NOT NULL, `label` TEXT NOT NULL, `description` TEXT NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `user_device_state` (`id` INTEGER NOT NULL, `foreground_app` TEXT NOT NULL, `foreground_app_name` TEXT NOT NULL, `is_screen_on` INTEGER NOT NULL, `last_updated` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `agent_monitor_tasks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `assistant_id` TEXT NOT NULL, `monitor_name` TEXT NOT NULL, `data_requirements` TEXT NOT NULL, `conditions` TEXT NOT NULL, `actions` TEXT NOT NULL, `is_enabled` INTEGER NOT NULL DEFAULT 1, `created_at` INTEGER NOT NULL)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `user_device_state` ADD COLUMN `today_duration_ms` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `user_device_state` ADD COLUMN `recent_actions` TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `chat_segments` ADD COLUMN `recall_count` INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `user_device_state` ADD COLUMN `screen_context` TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `user_device_state` ADD COLUMN `app_session_start_ms` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `user_device_state` ADD COLUMN `continuous_session_start_ms` INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `user_device_state` ADD COLUMN `latitude` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE `user_device_state` ADD COLUMN `longitude` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE `user_device_state` ADD COLUMN `location_name` TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `favorites` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` INTEGER NOT NULL, `content` TEXT NOT NULL, `sender_name` TEXT NOT NULL DEFAULT '', `agent_name` TEXT NOT NULL DEFAULT '', `user_nickname` TEXT NOT NULL DEFAULT '', `message_time` INTEGER NOT NULL, `create_at` INTEGER NOT NULL)")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM chat_segments WHERE id NOT IN (SELECT MAX(id) FROM chat_segments GROUP BY conversation_id, start_index)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_chat_segments_conversation_id_start_index` ON `chat_segments` (`conversation_id`, `start_index`)")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `user_device_state` ADD COLUMN `wifi_ssid` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `user_device_state` ADD COLUMN `is_wifi_connected` INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}

object TokenUsageConverter {
    @TypeConverter
    fun fromTokenUsage(usage: TokenUsage?): String = JsonInstant.encodeToString(usage)
    @TypeConverter
    fun toTokenUsage(usage: String): TokenUsage? = JsonInstant.decodeFromString(usage)
}

object AssistantExtendedStateConverter {
    @TypeConverter
    fun fromAppearance(appearance: AssistantAppearance): String = JsonInstant.encodeToString(appearance)
    @TypeConverter
    fun toAppearance(json: String): AssistantAppearance = JsonInstant.decodeFromString(json)
}
