package me.rerere.rikkahub.data.db

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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

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
        AgentMonitorTaskEntity::class
    ],
    version = 8,
    exportSchema = true
)
@TypeConverters(TokenUsageConverter::class, AssistantExtendedStateConverter::class)
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

    abstract fun tokenUsageDao(): TokenUsageDAO

    abstract fun bookDao(): BookDAO

    abstract fun assistantExtendedStateDao(): AssistantExtendedStateDAO

    abstract fun milestoneDao(): MilestoneDAO

    abstract fun userDeviceStateDao(): UserDeviceStateDAO

    abstract fun agentMonitorTaskDao(): AgentMonitorTaskDAO

    companion object {
        const val TAG = "AppDatabase"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_segments ADD COLUMN embedding_model_id TEXT DEFAULT ''")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `assistant_extended_state` (
                        `assistantId` TEXT NOT NULL,
                        `personality` TEXT NOT NULL,
                        `appearance` TEXT NOT NULL,
                        `preferences` TEXT NOT NULL,
                        `diet` TEXT NOT NULL,
                        `taboos` TEXT NOT NULL,
                        `interactionHabits` TEXT NOT NULL,
                        `relationships` TEXT NOT NULL,
                        PRIMARY KEY(`assistantId`)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `MilestoneEntity` (
                        `id` TEXT NOT NULL,
                        `assistant_id` TEXT NOT NULL,
                        `time` TEXT NOT NULL,
                        `label` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `user_device_state` (
                        `id` INTEGER NOT NULL,
                        `foreground_app` TEXT NOT NULL,
                        `foreground_app_name` TEXT NOT NULL,
                        `is_screen_on` INTEGER NOT NULL,
                        `last_updated` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `agent_monitor_tasks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `assistant_id` TEXT NOT NULL,
                        `monitor_name` TEXT NOT NULL,
                        `data_requirements` TEXT NOT NULL,
                        `conditions` TEXT NOT NULL,
                        `actions` TEXT NOT NULL,
                        `is_enabled` INTEGER NOT NULL DEFAULT 1,
                        `created_at` INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 为 user_device_state 表增加今日累计时长和最近动作字段
                db.execSQL("ALTER TABLE `user_device_state` ADD COLUMN `today_duration_ms` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `user_device_state` ADD COLUMN `recent_actions` TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 为 chat_segments 表增加召回次数统计字段
                db.execSQL("ALTER TABLE `chat_segments` ADD COLUMN `recall_count` INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 为 user_device_state 表增加屏幕上下文文字字段
                db.execSQL("ALTER TABLE `user_device_state` ADD COLUMN `screen_context` TEXT NOT NULL DEFAULT ''")
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

object AssistantExtendedStateConverter {
    @TypeConverter
    fun fromAppearance(appearance: AssistantAppearance): String {
        return JsonInstant.encodeToString(appearance)
    }

    @TypeConverter
    fun toAppearance(json: String): AssistantAppearance {
        return JsonInstant.decodeFromString(json)
    }
}
