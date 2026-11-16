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
        DiaryCommentEntity::class,
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
        ChatMessageEntity::class,
        ProfileHistoryEntity::class,
        AppLockEntity::class,
        CallRecordEntity::class,
        ScheduleEventEntity::class,
        RadioChannelEntity::class,
        RadioEpisodeEntity::class,
        RadioAudioAssetEntity::class,
        RadioEpisodeDeletionLogEntity::class,
        BackgroundTaskEntity::class,
        ChatUnreadMessageEntity::class
    ],
    version = 48,
    exportSchema = true
)
@TypeConverters(
    TokenUsageConverter::class,
    DiaryImageConverter::class
)
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
    abstract fun profileHistoryDao(): ProfileHistoryDAO
    abstract fun appLockDao(): AppLockDAO
    abstract fun callRecordDao(): CallRecordDao
    abstract fun scheduleEventDao(): ScheduleEventDAO
    abstract fun radioChannelDao(): RadioChannelDao
    abstract fun radioEpisodeDao(): RadioEpisodeDao
    abstract fun radioAudioAssetDao(): RadioAudioAssetDao
    abstract fun radioEpisodeDeletionLogDao(): RadioEpisodeDeletionLogDao
    abstract fun backgroundTaskDao(): BackgroundTaskDao
    abstract fun chatUnreadDao(): ChatUnreadDAO

    companion object {
        const val TAG = "AppDatabase"

        /**
         * 25 -> 26: 新建 profile_history 表
         *
         * 用于在 AI 调用 update_profile 工具覆盖档案字段之前，按字段级别
         * 保存旧值快照。每个 target 仅保留最近 3 个版本（batchId），
         * 超出的更早版本会由 ProfileHistoryRepository.trimOldVersions 清理。
         */
        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.v(TAG, "开始 25->26 迁移：新建 profile_history 表（档案历史版本）")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `profile_history` (
                        `id`        INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `targetType` TEXT NOT NULL,
                        `targetId`  TEXT NOT NULL,
                        `fieldKey` TEXT NOT NULL,
                        `oldValue`  TEXT NOT NULL,
                        `newValue`  TEXT NOT NULL,
                        `batchId`   INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_profile_history_targetType_targetId_batchId` " +
                        "ON `profile_history` (`targetType`, `targetId`, `batchId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_profile_history_targetType_targetId_createdAt` " +
                        "ON `profile_history` (`targetType`, `targetId`, `createdAt`)"
                )
            }
        }

        /**
         * 26 -> 27: 将 AssistantExtendedStateEntity.appearance 从结构化对象
         * AssistantAppearance 简化为纯文本 String。
         *
         * 存储层列类型仍然是 TEXT，因此表结构本身不需要变更。旧记录里如果是
         * AssistantAppearance 的 JSON 序列化串，仍会以字符串形式保留在 appearance
         * 列里，用户下次打开编辑页看到旧值后可自行修改/清空。这里只声明一个空迁移
         * 触发版本号前进即可。
         */
        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.v(TAG, "开始 26->27 迁移：助手档案 appearance 结构化 → 纯文本（表结构无变化）")
            }
        }

        /**
         * 27 → 28：为 ConversationEntity 新增 segment_failure_count 列，
         * 记录 L1 自动总结连续失败次数，达到阈值后熔断自动触发。
         */
        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.v(TAG, "开始 27->28 迁移：conversationentity 表新增 segment_failure_count 列")
                db.execSQL(
                    "ALTER TABLE `conversationentity` ADD COLUMN `segment_failure_count` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * 28 → 29：将 L2 归档截断游标从基于数组索引的 `truncate_index`
         * 迁移为基于时间戳的 `last_archived_message_time`，对齐 L1 segment 的
         * `last_summarized_message_time` 机制，修复 loadMoreHistory 前插导致索引错位的 bug。
         *
         * 历史数据迁移：对每条已有归档 episode 的会话，用 ChatEpisodeEntity.end_time
         * 填充 last_archived_message_time。无 episode 的会话保持 0（未启用截断）。
         * truncate_index 列保留不删（兼容旧 schema，避免重建表的高成本）。
         */
        val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.v(TAG, "开始 28->29 迁移：conversationentity 表新增 last_archived_message_time 列，并从 ChatEpisodeEntity 回填历史数据")

                // 1. 新增列：默认值 0（表示未启用截断）
                db.execSQL(
                    "ALTER TABLE `conversationentity` ADD COLUMN `last_archived_message_time` INTEGER NOT NULL DEFAULT 0"
                )

                // 2. 历史数据回填：用每个会话对应 episode 的 end_time 填充
                //    Room 默认表名是类名本身（ChatEpisodeEntity，大写驼峰）
                db.execSQL("""
                    UPDATE `conversationentity`
                    SET `last_archived_message_time` = COALESCE(
                        (SELECT MAX(`ep`.`end_time`)
                         FROM `ChatEpisodeEntity` AS `ep`
                         WHERE `ep`.`conversation_id` = `conversationentity`.`id`),
                        0
                    )
                    WHERE `last_archived_message_time` = 0
                      AND `id` IN (SELECT `conversation_id` FROM `ChatEpisodeEntity` WHERE `conversation_id` IS NOT NULL AND `conversation_id` != '')
                """.trimIndent())

                // 3. truncate_index 列保留：不删除，避免重建表的高成本。
                //    代码层不再读取该列，但 Room schema 校验仍要求 Entity 字段与表列对应，
                //    因此 ConversationEntity 中保留 @Deprecated 的 truncateIndex 字段，
                //    值始终透传为 -1（defaultValue）。

                // 4. 自查：打印迁移后的数据规模，便于线上问题诊断
                val cursor = db.query(
                    "SELECT COUNT(*) AS total, " +
                    "SUM(CASE WHEN `last_archived_message_time` > 0 THEN 1 ELSE 0 END) AS archived " +
                    "FROM `conversationentity`"
                )
                if (cursor.moveToFirst()) {
                    val total = cursor.getInt(cursor.getColumnIndexOrThrow("total"))
                    val archived = cursor.getInt(cursor.getColumnIndexOrThrow("archived"))
                    Log.i(TAG, "28->29 迁移完成：共 $total 个会话，其中 $archived 个已写入 last_archived_message_time")
                }
                cursor.close()
            }
        }

        /**
         * 29 → 30：为 schedules 表新增 agent_task_id 列，
         * 记录基于该日程截止时间自动生成的 agent_task 的 ID。
         * 该字段可为空（无截止时间的日程不会生成 task）。
         * 删除/完成日程时，通过此字段联动删除关联的 agent_task。
         */
        val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.v(TAG, "开始 30->31 迁移：AgentDiaryEntity 新增 last_message_at 列")
                db.execSQL(
                    "ALTER TABLE `AgentDiaryEntity` ADD COLUMN `last_message_at` INTEGER"
                )
                // 历史日记回填锚点：直接用 created_at（日记记录创建时间）。
                // 补生成场景下 createdAt 可能偏离日记 date（9/11 日记在 9/12 凌晨补生成 → createdAt=9/12），
                // 但此时 9/11 当天没有任何日记存在（DiarySchedulerWorker 仅在"今天+昨天都没有"时补昨天），
                // 9/11 日记生成时已取完 9/10 锚点到 9/11 结束的消息，下次从 createdAt=9/12 凌晨起取不会漏。
                db.execSQL(
                    """
                    UPDATE `AgentDiaryEntity`
                    SET `last_message_at` = `created_at`
                    WHERE `last_message_at` IS NULL
                    """
                )
                Log.v(TAG, "完成 30->31 迁移：历史日记 last_message_at 回填完成")
            }
        }

        /**
         * 31 → 32：为 agent_tasks 表新增 model 列，
         * 允许用户为定时任务单独指定执行模型。
         * 为空时使用 assistant 当前对话模型。
         */
        val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.v(TAG, "开始 31->32 迁移：agent_tasks 表新增 model 列")
                db.execSQL(
                    "ALTER TABLE `agent_tasks` ADD COLUMN `model` TEXT"
                )
            }
        }

        /**
         * 32 → 33：新建 app_locks 表，用于应用锁定功能。
         * 记录被锁定的应用包名、锁定时间窗口、解锁条件文案、遮罩图片路径等。
         */
        val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.v(TAG, "开始 32->33 迁移：新建 app_locks 表")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `app_locks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `package_name` TEXT NOT NULL,
                        `app_name` TEXT,
                        `start_time` TEXT,
                        `end_time` TEXT,
                        `unlock_condition` TEXT,
                        `overlay_image_path` TEXT,
                        `emoji` TEXT,
                        `prompt_message` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * 33 -> 34: app_locks 表主键从自增 id 改为 package_name。
         *
         * 修复 bug：原主键为自增 id，导致同一应用可有多条锁定记录，
         * APP_LOCK 不会覆盖旧记录而是新增，APP_UNLOCK 会一次性删除所有同包名记录。
         * 改用 package_name 做主键后，@Insert(REPLACE) 会按包名覆盖，
         * 同一应用永远只有一条锁定记录。
         *
         * 迁移策略：重建表，若存在重复 package_name 则保留 created_at 最大的（最新）。
         */
        val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.v(TAG, "开始 33->34 迁移：app_locks 主键改为 package_name")
                // 1. 创建新表，package_name 做主键
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `app_locks_new` (
                        `package_name` TEXT NOT NULL PRIMARY KEY,
                        `app_name` TEXT,
                        `start_time` TEXT,
                        `end_time` TEXT,
                        `unlock_condition` TEXT,
                        `overlay_image_path` TEXT,
                        `emoji` TEXT,
                        `prompt_message` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                // 2. 迁移数据：若存在重复 package_name，保留 created_at 最大的（最新）
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO `app_locks_new` (
                        `package_name`, `app_name`, `start_time`, `end_time`,
                        `unlock_condition`, `overlay_image_path`, `emoji`,
                        `prompt_message`, `created_at`
                    )
                    SELECT
                        `package_name`, `app_name`, `start_time`, `end_time`,
                        `unlock_condition`, `overlay_image_path`, `emoji`,
                        `prompt_message`, `created_at`
                    FROM `app_locks`
                    WHERE `created_at` = (
                        SELECT MAX(`created_at`) FROM `app_locks` AS sub
                        WHERE sub.`package_name` = `app_locks`.`package_name`
                    )
                    """.trimIndent()
                )
                // 3. 删除旧表，重命名新表
                db.execSQL("DROP TABLE IF EXISTS `app_locks`")
                db.execSQL("ALTER TABLE `app_locks_new` RENAME TO `app_locks`")
            }
        }

        /**
         * 34 → 35：为 app_locks 表新增 assistant_id 列。
         *
         * 记录是哪个 Assistant 锁定的应用。用户打开被锁应用时，
         * EvoliaMonitorService 会向该 Assistant 发送一条 skipContext=true 的 user
         * 系统消息（不显示给用户但实际发送给 AI），让 AI 知道用户触发了锁定。
         * 旧记录无此字段时默认空字符串，仅显示遮罩不发消息。
         */
        val MIGRATION_34_35 = object : Migration(34, 35) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.v(TAG, "开始 34->35 迁移：app_locks 表新增 assistant_id 列")
                db.execSQL(
                    "ALTER TABLE `app_locks` ADD COLUMN `assistant_id` TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /**
         * 35 → 36：app_locks 表结构重构。
         *
         * 1. 删除 start_time / end_time 两列，合并为 valid_time（TEXT，JSON 数组字符串）
         *    格式与 agent_monitor_tasks.conditions.time_periods 一致，支持多时段。
         * 2. 新增 pause_until（INTEGER NOT NULL DEFAULT 0），支持 APP_LOCK_PAUSE 临时暂停功能。
         *
         * SQLite ALTER TABLE 不支持 DROP COLUMN（在 Room 使用的 SQLite 版本上），
         * 且 Room schema 校验要求 entity 字段与表列一致，因此采用重建表策略
         * （参考 MIGRATION_33_34 的写法）。
         *
         * 历史数据迁移：若 start_time 和 end_time 都非空，拼成
         * `[{"start":"<start_time>","end":"<end_time>"}]` 写入 valid_time；否则置 NULL。
         */
        val MIGRATION_35_36 = object : Migration(35, 36) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.v(TAG, "开始 35->36 迁移：app_locks 表 start_time/end_time 合并为 valid_time，新增 pause_until")

                // 1. 创建新表（package_name 主键 + valid_time + pause_until + 其他原字段）
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `app_locks_new` (
                        `package_name` TEXT NOT NULL PRIMARY KEY,
                        `app_name` TEXT,
                        `valid_time` TEXT,
                        `unlock_condition` TEXT,
                        `overlay_image_path` TEXT,
                        `emoji` TEXT,
                        `prompt_message` TEXT NOT NULL,
                        `assistant_id` TEXT NOT NULL DEFAULT '',
                        `pause_until` INTEGER NOT NULL DEFAULT 0,
                        `created_at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                // 2. 历史数据迁移：把 start_time + end_time 拼成 JSON 数组写入 valid_time
                db.execSQL(
                    """
                    INSERT INTO `app_locks_new` (
                        `package_name`, `app_name`, `valid_time`, `unlock_condition`,
                        `overlay_image_path`, `emoji`, `prompt_message`,
                        `assistant_id`, `pause_until`, `created_at`
                    )
                    SELECT
                        `package_name`,
                        `app_name`,
                        CASE
                            WHEN `start_time` IS NOT NULL AND `end_time` IS NOT NULL
                                 AND `start_time` != '' AND `end_time` != ''
                                THEN '[{"start":"' || `start_time` || '","end":"' || `end_time` || '"}]'
                            ELSE NULL
                        END AS `valid_time`,
                        `unlock_condition`,
                        `overlay_image_path`,
                        `emoji`,
                        `prompt_message`,
                        COALESCE(`assistant_id`, ''),
                        0 AS `pause_until`,
                        `created_at`
                    FROM `app_locks`
                    """.trimIndent()
                )

                // 3. 删除旧表，重命名新表
                db.execSQL("DROP TABLE IF EXISTS `app_locks`")
                db.execSQL("ALTER TABLE `app_locks_new` RENAME TO `app_locks`")

                // 自查：打印迁移后的数据规模
                val cursor = db.query("SELECT COUNT(*) AS total FROM `app_locks`")
                if (cursor.moveToFirst()) {
                    val total = cursor.getInt(cursor.getColumnIndexOrThrow("total"))
                    Log.i(TAG, "35->36 迁移完成：app_locks 表共 $total 条记录已迁移到 valid_time + pause_until 结构")
                }
                cursor.close()
            }
        }

        /**
         * 36 → 37：agent_monitor_tasks 表新增 pause_until 列。
         *
         * 支持 peek_user 工具新增的 pause 动作：临时暂停某个监视器 N 分钟，
         * 暂停期间 checkAllMonitors 跳过该监视器，到期自动恢复。
         * 0 表示未暂停。
         */
        val MIGRATION_36_37 = object : Migration(36, 37) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.v(TAG, "开始 36->37 迁移：agent_monitor_tasks 表新增 pause_until 列")
                db.execSQL(
                    "ALTER TABLE `agent_monitor_tasks` ADD COLUMN `pause_until` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * 37 → 38：新建 call_records 表，用于通话记录（含接通/拒绝/超时/失败等所有尝试）。
         *
         * 主键 id 同时作为 callId，关联通话转写消息（UIMessage.callId）。
         * audio 存录音文件的相对路径；audio_expires_at / forever 控制到期清理与永久保存。
         * 仅新增表，不改动任何既有表，历史数据完整保留。
         */
        val MIGRATION_37_38 = object : Migration(37, 38) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.v(TAG, "开始 37->38 迁移：新建 call_records 表（通话记录）")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `call_records` (
                        `id` TEXT NOT NULL,
                        `assistant_id` TEXT NOT NULL,
                        `conversation_id` TEXT NOT NULL,
                        `start_time` INTEGER,
                        `end_time` INTEGER,
                        `audio` TEXT,
                        `create_time` INTEGER NOT NULL,
                        `forever` INTEGER NOT NULL DEFAULT 0,
                        `status` TEXT NOT NULL,
                        `connected_at` INTEGER,
                        `disconnected_at` INTEGER,
                        `duration_ms` INTEGER NOT NULL DEFAULT 0,
                        `end_reason` TEXT,
                        `audio_expires_at` INTEGER,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_call_records_assistant_id_create_time` " +
                        "ON `call_records` (`assistant_id`, `create_time`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_call_records_conversation_id` " +
                        "ON `call_records` (`conversation_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_call_records_status` " +
                        "ON `call_records` (`status`)"
                )
            }
        }

        /**
         * 38 → 39：call_records 表新增 initiator 列，记录通话发起方（user / agent）。
         */
        val MIGRATION_38_39 = object : Migration(38, 39) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.v(TAG, "开始 38->39 迁移：call_records 表新增 initiator 列")
                db.execSQL(
                    "ALTER TABLE `call_records` ADD COLUMN `initiator` TEXT NOT NULL DEFAULT 'user'"
                )
            }
        }

        /**
         * 40 → 41：日记表新增收藏标记 is_favorite。
         *
         * 仅加列 + 默认值 0（未收藏），历史日记全部保持未收藏状态，不改动其他字段。
         */
        val MIGRATION_40_41 = object : Migration(40, 41) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.v(TAG, "开始 40->41 迁移：日记表新增收藏标记 is_favorite")
                db.execSQL(
                    "ALTER TABLE `AgentDiaryEntity` ADD COLUMN `is_favorite` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * 41 → 42：call_records 表新增通话心跳时间列 last_heartbeat_at。
         *
         * 通话接通期间每 30s 由 VoiceCallManager 刷新一次，用于进程异常死亡后的
         * 启动对账（CallRecovery）：以「最后有效心跳时间」估算异常结束时间
         * （误差 ≤ 心跳间隔），避免把 App 再次启动的时间误当作挂断时间。
         * 仅加可空列，历史数据全部为 NULL（视为无心跳），不改动任何现有字段。
         */
        val MIGRATION_41_42 = object : Migration(41, 42) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.v(TAG, "开始 41->42 迁移：call_records 表新增通话心跳时间 last_heartbeat_at")
                db.execSQL(
                    "ALTER TABLE `call_records` ADD COLUMN `last_heartbeat_at` INTEGER"
                )
            }
        }

        /**
         * 42 → 43：AgentDiaryEntity 新增生成状态列 generation_status。
         *
         * 支撑日记生成失败恢复机制（chunk 级游标推进 + 同天 PARTIAL 续写）：
         * - GENERATING：生成任务进行中 / Worker 异常退出残留
         * - PARTIAL：部分 Chunk 已成功保存，仍有消息未处理
         * - COMPLETED：全部处理完成
         * 历史日记全部默认 COMPLETED（视为已完成，不参与续写，保持既有兼容规则）；
         * 历史遗留空壳记录（content 空 + COMPLETED）由 DiaryWorker 复用重建逻辑兜底。
         * 仅加列 + 非空默认值，不改动任何既有字段。
         */
        val MIGRATION_42_43 = object : Migration(42, 43) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.v(TAG, "开始 42->43 迁移：AgentDiaryEntity 新增生成状态 generation_status")
                db.execSQL(
                    "ALTER TABLE `AgentDiaryEntity` ADD COLUMN `generation_status` TEXT NOT NULL DEFAULT 'COMPLETED'"
                )
            }
        }

        /**
         * 43 → 44：新建小机电台 4 张表（radio_channels / radio_episodes /
         * radio_audio_assets / radio_episode_deletion_logs）。
         *
         * 不使用 Room 外键：关联完整性由 RadioService 业务校验（删栏目前必须处理关联节目，
         * 删节目时同步清理音频资产）；删除审计表刻意独立于节目记录存在（节目删除后审计保留）。
         * 仅新增表，不改动任何既有表，历史数据完整保留。
         */
        /**
         * 44 → 45：radio_episodes 表新增点赞标记列 liked。
         * 播放器点赞按钮使用；0 = 未点赞，1 = 已点赞。仅加列 + 非空默认值，
         * 历史节目全部视为未点赞，不改动任何既有字段。
         */
        val MIGRATION_44_45 = object : Migration(44, 45) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.v(TAG, "开始 44->45 迁移：radio_episodes 新增点赞标记 liked")
                db.execSQL(
                    "ALTER TABLE `radio_episodes` ADD COLUMN `liked` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * 45 → 46：通用后台任务机制。
         *
         * 1. 新建 background_tasks 表：任务状态/结果/回调投递状态的唯一事实源
         *    （Agent 查询、UI 任务卡片、回调投递都只读此表）。
         *    - 状态机全部走条件 UPDATE 原子变更，终态不可覆盖（见 BackgroundTaskDao 注释）；
         *    - deadline_at = created_at + 50min，产品定稿语义：任务总生命周期上限（含排队等待）；
         *    - 执行租约 execution_id/lease_expires_at 支撑进程被杀后的失效重认领与断点续传。
         *    不使用 Room 外键：关联完整性由 BackgroundTaskManager 业务校验。
         * 2. radio_audio_assets 新增 task_id 列：任务与音频资产关联，
         *    提交原子边界（committing 状态）崩溃恢复时据此判断资产是否已提交，防止重复合成/重复换备。
         *
         * 仅新增表与可空列，不改动任何既有数据，历史数据完整保留。
         */
        val MIGRATION_45_46 = object : Migration(45, 46) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.v(TAG, "开始 45->46 迁移：新建 background_tasks 表，radio_audio_assets 新增 task_id")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `background_tasks` (
                        `id` TEXT NOT NULL,
                        `task_type` TEXT NOT NULL,
                        `ref_key` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `payload_json` TEXT NOT NULL,
                        `result_json` TEXT,
                        `error_message` TEXT,
                        `agent_id` TEXT,
                        `conversation_id` TEXT,
                        `source_turn_id` TEXT,
                        `completion_policy` TEXT NOT NULL,
                        `callback_state` TEXT NOT NULL,
                        `callback_claimed_at` INTEGER,
                        `callback_attempt_count` INTEGER NOT NULL,
                        `callback_last_error` TEXT,
                        `created_at` INTEGER NOT NULL,
                        `started_at` INTEGER,
                        `completed_at` INTEGER,
                        `deadline_at` INTEGER NOT NULL,
                        `retry_count` INTEGER NOT NULL,
                        `execution_id` TEXT,
                        `lease_expires_at` INTEGER,
                        `progress_json` TEXT,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_background_tasks_status` " +
                        "ON `background_tasks` (`status`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_background_tasks_task_type_ref_key` " +
                        "ON `background_tasks` (`task_type`, `ref_key`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_background_tasks_conversation_id` " +
                        "ON `background_tasks` (`conversation_id`)"
                )
                db.execSQL(
                    "ALTER TABLE `radio_audio_assets` ADD COLUMN `task_id` TEXT"
                )
            }
        }

        /**
         * 46 → 47：radio_episodes 表新增可见性列 visibility。
         * PUBLIC = 所有 Agent 均可查询；PRIVATE = 仅创作 Agent 与 user 可查询。
         * 仅加列 + 非空默认值，历史节目全部视为 PUBLIC，不改动任何既有字段。
         */
        val MIGRATION_46_47 = object : Migration(46, 47) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.v(TAG, "开始 46->47 迁移：radio_episodes 新增可见性列 visibility")
                db.execSQL(
                    "ALTER TABLE `radio_episodes` ADD COLUMN `visibility` TEXT NOT NULL DEFAULT 'PUBLIC'"
                )
            }
        }

        /**
         * 47 → 48：新建未读消息节点表 chat_unread_messages（Home 页 Agent 头像未读角标）。
         *
         * 仅记录「已持久化但用户尚未查看」的 ASSISTANT 可见消息节点：
         * - node_id 主键（= chat_message_nodes.id），重复写入走 INSERT IGNORE 幂等；
         * - FK CASCADE 指向 chat_message_nodes：删除消息节点/会话时未读记录由数据库级联清理；
         * - assistant_id 不落库，统计经 JOIN conversationentity 获取；
         * - 历史消息不做全量回填：迁移后未读计数从零开始，仅统计升级后产生的回复。
         * 仅新增表，不改动任何既有数据，历史数据完整保留。
         */
        val MIGRATION_47_48 = object : Migration(47, 48) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.v(TAG, "开始 47->48 迁移：新建 chat_unread_messages 表（未读角标）")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `chat_unread_messages` (
                        `node_id` TEXT NOT NULL,
                        `conversation_id` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        PRIMARY KEY(`node_id`),
                        FOREIGN KEY(`node_id`) REFERENCES `chat_message_nodes`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chat_unread_messages_conversation_id` " +
                        "ON `chat_unread_messages` (`conversation_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chat_unread_messages_node_id` " +
                        "ON `chat_unread_messages` (`node_id`)"
                )
            }
        }

        val MIGRATION_43_44 = object : Migration(43, 44) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.v(TAG, "开始 43->44 迁移：新建小机电台 4 张表")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `radio_channels` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `cover` TEXT,
                        `icon` TEXT,
                        `system_prompt` TEXT NOT NULL,
                        `creator_id` TEXT NOT NULL,
                        `sort_order` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_radio_channels_sort_order` " +
                        "ON `radio_channels` (`sort_order`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `radio_episodes` (
                        `id` TEXT NOT NULL,
                        `channel_id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `creator_agent_id` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        `published_at` INTEGER,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_radio_episodes_channel_id` " +
                        "ON `radio_episodes` (`channel_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_radio_episodes_creator_agent_id` " +
                        "ON `radio_episodes` (`creator_agent_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_radio_episodes_channel_id_status` " +
                        "ON `radio_episodes` (`channel_id`, `status`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `radio_audio_assets` (
                        `id` TEXT NOT NULL,
                        `episode_id` TEXT NOT NULL,
                        `file_path` TEXT NOT NULL,
                        `provider_id` TEXT NOT NULL,
                        `voice_id` TEXT NOT NULL,
                        `duration_ms` INTEGER NOT NULL,
                        `file_size` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `content_hash` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `expires_at` INTEGER,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_radio_audio_assets_episode_id_status` " +
                        "ON `radio_audio_assets` (`episode_id`, `status`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `radio_episode_deletion_logs` (
                        `id` TEXT NOT NULL,
                        `episode_id` TEXT NOT NULL,
                        `episode_title` TEXT NOT NULL,
                        `channel_id` TEXT,
                        `creator_agent_id` TEXT NOT NULL,
                        `operator_id` TEXT NOT NULL,
                        `operator_type` TEXT NOT NULL,
                        `reason` TEXT NOT NULL,
                        `requested_by_user` INTEGER NOT NULL,
                        `deleted_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_radio_episode_deletion_logs_episode_id` " +
                        "ON `radio_episode_deletion_logs` (`episode_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_radio_episode_deletion_logs_deleted_at` " +
                        "ON `radio_episode_deletion_logs` (`deleted_at`)"
                )
            }
        }

        /**
         * 39 → 40：新建 schedule_events 表（日程安排）。
         *
         * 待办（schedules）与日程（schedule_events）分表存储：一个待办可关联
         * 零条、一条或多条日程，task_id 不设唯一约束。
         * - date 为 epoch day，start_time / end_time 为当天分钟数（0..1439）；
         * - task_id 外键关联 schedules.id，ON DELETE CASCADE：删除待办时
         *   其关联日程随之删除，保证引用完整性；
         * - 仅新增表，不改动 schedules 任何字段，历史待办数据完整保留，
         *   且不为旧待办自动生成日程（旧数据默认视为未排程）。
         */
        val MIGRATION_39_40 = object : Migration(39, 40) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.v(TAG, "开始 39->40 迁移：新建 schedule_events 表（日程安排）")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `schedule_events` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `task_id` INTEGER NOT NULL,
                        `date` INTEGER NOT NULL,
                        `start_time` INTEGER NOT NULL,
                        `end_time` INTEGER NOT NULL,
                        `category` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        FOREIGN KEY(`task_id`) REFERENCES `schedules`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_schedule_events_task_id` " +
                        "ON `schedule_events` (`task_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_schedule_events_date` " +
                        "ON `schedule_events` (`date`)"
                )
            }
        }

        val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.v(TAG, "开始 29->30 迁移：schedules 表新增 agent_task_id 列")
                db.execSQL(
                    "ALTER TABLE `schedules` ADD COLUMN `agent_task_id` INTEGER"
                )
            }
        }

        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.v(TAG, "开始 23->24 迁移：为日记表添加手写日记图片字段（JSON 存储）")
                db.execSQL("ALTER TABLE `AgentDiaryEntity` ADD COLUMN `images` TEXT NOT NULL DEFAULT '[]'")
            }
        }

        /**
         * 24 -> 25: 为 ConversationEntity 增加 last_summarized_message_id 字段，
         * 配合「created_at + id」复合游标分页，修复 L1 Segment 在同毫秒 created_at 的消息组上
         * 因 `created_at > :lastTime` 严格大于条件而产生的边界遗漏问题。
         */
        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.v(TAG, "开始 24->25 迁移：为会话表增加 L1 复合分页游标字段 last_summarized_message_id")
                db.execSQL(
                    "ALTER TABLE `conversationentity` " +
                        "ADD COLUMN `last_summarized_message_id` TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.v(TAG, "开始 22->23 迁移：为日记评论表添加回复目标字段（重建表以包含自引用外键）")
                // SQLite 的 ALTER TABLE ADD COLUMN 无法添加外键约束，
                // 而 Room 启动时会校验 schema 与 entity 定义一致（含外键），
                // 因此必须重建表才能带上 DiaryCommentEntity 自引用外键。
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `DiaryCommentEntity_new` (
                        `id` TEXT NOT NULL,
                        `diary_id` TEXT NOT NULL,
                        `sender_id` TEXT NOT NULL,
                        `reply_to_id` TEXT,
                        `content` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`diary_id`) REFERENCES `AgentDiaryEntity`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`reply_to_id`) REFERENCES `DiaryCommentEntity`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO `DiaryCommentEntity_new` (`id`, `diary_id`, `sender_id`, `content`, `created_at`)
                    SELECT `id`, `diary_id`, `sender_id`, `content`, `created_at` FROM `DiaryCommentEntity`
                """.trimIndent())
                db.execSQL("DROP TABLE `DiaryCommentEntity`")
                db.execSQL("ALTER TABLE `DiaryCommentEntity_new` RENAME TO `DiaryCommentEntity`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_DiaryCommentEntity_diary_id` ON `DiaryCommentEntity` (`diary_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_DiaryCommentEntity_reply_to_id` ON `DiaryCommentEntity` (`reply_to_id`)")
            }
        }

        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.v(TAG, "开始 21->22 迁移：添加日记评论表")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `DiaryCommentEntity` (
                        `id` TEXT NOT NULL,
                        `diary_id` TEXT NOT NULL,
                        `sender_id` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`diary_id`) REFERENCES `AgentDiaryEntity`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_DiaryCommentEntity_diary_id` ON `DiaryCommentEntity` (`diary_id`)")
            }
        }

        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.v(TAG, "开始 20->21 迁移：为消息节点建立稳定时间线游标")
                db.execSQL("ALTER TABLE `chat_message_nodes` ADD COLUMN `created_at` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    UPDATE `chat_message_nodes`
                    SET `created_at` = COALESCE(
                        (SELECT MIN(`created_at`) FROM `chat_messages`
                         WHERE `chat_messages`.`node_id` = `chat_message_nodes`.`id`),
                        0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chat_message_nodes_conversation_id_created_at_id` " +
                        "ON `chat_message_nodes` (`conversation_id`, `created_at`, `id`)"
                )
            }
        }

        // ✨ 19 -> 20: 添加性能优化索引
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.v(TAG, "开始 19->20 迁移：增加性能优化索引")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_message_nodes_conversation_id_order_index` ON `chat_message_nodes` (`conversation_id`, `order_index`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ConversationEntity_assistant_id_update_at` ON `ConversationEntity` (`assistant_id`, `update_at`)")
            }
        }

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
                db.execSQL("INSERT INTO `MemoryEntity_new` (`id`, `assistant_id`, `content`, `keywords`, `embedding`, `embedding_model_id`, `type`, `last_accessed_at`, `created_at`) SELECT `id`, `assistant_id`, `content`, `keywords`, `embedding`, `embedding_model_id`, `type`, `last_accessed_at`, `created_at` FROM `MemoryEntity`")
                db.execSQL("DROP TABLE `MemoryEntity`")
                db.execSQL("ALTER TABLE `MemoryEntity_new` RENAME TO `MemoryEntity`")

                // 2. 处理 chat_segments
                db.execSQL("CREATE TABLE `chat_segments_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `assistant_id` TEXT NOT NULL, `conversation_id` TEXT NOT NULL, `content` TEXT NOT NULL, `keywords` TEXT, `start_index` INTEGER NOT NULL, `end_index` INTEGER NOT NULL, `start_time` INTEGER NOT NULL, `end_time` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `embedding` BLOB, `embedding_model_id` TEXT DEFAULT '', `recall_count` INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("INSERT INTO `chat_segments_new` (`id`, `assistant_id`, `conversation_id`, `content`, `keywords`, `start_index`, `end_index`, `start_time`, `end_time`, `timestamp`, `embedding`, `embedding_model_id`, `recall_count`) SELECT `id`, `assistant_id`, `conversation_id`, `content`, `keywords`, `start_index`, `end_index`, `start_time`, `end_time`, `timestamp`, `embedding`, `embedding_model_id`, `recall_count` FROM `chat_segments`")
                db.execSQL("DROP TABLE `chat_segments`")
                db.execSQL("ALTER TABLE `chat_segments_new` RENAME TO `chat_segments`")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_chat_segments_conversation_id_start_time` ON `chat_segments` (`conversation_id`, `start_time`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_segments_conversation_id` ON `chat_segments` (`conversation_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_segments_assistant_id` ON `chat_segments` (`assistant_id`)")

                // 3. 处理 ChatEpisodeEntity
                db.execSQL("CREATE TABLE `ChatEpisodeEntity_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `assistant_id` TEXT NOT NULL, `content` TEXT NOT NULL, `keywords` TEXT, `embedding` BLOB, `embedding_model_id` TEXT DEFAULT '', `start_time` INTEGER NOT NULL, `end_time` INTEGER NOT NULL, `last_accessed_at` INTEGER NOT NULL DEFAULT 0, `significance` INTEGER NOT NULL DEFAULT 5, `conversation_id` TEXT DEFAULT '')")
                db.execSQL("INSERT INTO `ChatEpisodeEntity_new` (`id`, `assistant_id`, `content`, `keywords`, `embedding`, `embedding_model_id`, `start_time`, `end_time`, `last_accessed_at`, `significance`, `conversation_id`) SELECT `id`, `assistant_id`, `content`, `keywords`, `embedding`, `embedding_model_id`, `start_time`, `end_time`, `last_accessed_at`, `significance`, `conversation_id` FROM `ChatEpisodeEntity`")
                db.execSQL("DROP TABLE `ChatEpisodeEntity`")
                db.execSQL("ALTER TABLE `ChatEpisodeEntity_new` RENAME TO `ChatEpisodeEntity`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ChatEpisodeEntity_assistant_id_end_time` ON `ChatEpisodeEntity` (`assistant_id`, `end_time`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_ChatEpisodeEntity_conversation_id` ON `ChatEpisodeEntity` (`conversation_id`)")

                // 4. 处理缓存表
                db.execSQL("CREATE TABLE IF NOT EXISTS `embedding_cache_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `memory_id` INTEGER NOT NULL, `memory_type` INTEGER NOT NULL, `model_id` TEXT NOT NULL, `embedding` BLOB NOT NULL, `created_at` INTEGER NOT NULL)")
                runCatching {
                    db.execSQL("INSERT INTO `embedding_cache_new` (`memory_id`, `memory_type`, `model_id`, `embedding`, `created_at`) SELECT `memory_id`, `memory_type`, `model_id`, `embedding`, `created_at` FROM `embedding_cache`")
                }
                db.execSQL("DROP TABLE IF EXISTS `embedding_cache`")
                db.execSQL("ALTER TABLE `embedding_cache_new` RENAME TO `embedding_cache`")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_embedding_cache_memory_id_memory_type_model_id` ON `embedding_cache` (`memory_id`, `memory_type`, `model_id`)")
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.v(TAG, "开始 16->17 迁移：清理弃用的虚拟世界索引")
                db.execSQL("DROP INDEX IF EXISTS `index_ConversationEntity_is_virtual` ")
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.v("AppDatabase", "开始 15->16 迁移，当前数据库文件版本为: ${db.version}")
                db.execSQL("DROP INDEX IF EXISTS `index_chat_segments_conversation_id_start_index` ")
                db.execSQL("""
                    UPDATE `chat_segments`
                    SET `start_time` = CAST(`timestamp` AS INTEGER) - 900000,
                        `end_time` = CAST(`timestamp` AS INTEGER)
                    WHERE `start_time` IS NULL
                       OR `start_time` = 0
                       OR ABS(`end_time` - `start_time` - 900000) > 100
                """.trimIndent())
                db.execSQL("""
                    DELETE FROM `chat_segments`
                    WHERE `id` NOT IN (
                        SELECT MAX(`id`)
                        FROM `chat_segments`
                        GROUP BY `conversation_id`, `start_time`
                    )
                """.trimIndent())
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
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_chat_segments_conversation_id_start_time` ON `chat_segments` (`conversation_id`, `start_time`)")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {

            override fun migrate(db: SupportSQLiteDatabase) {
                Log.v("AppDatabase", "开始 14->15 迁移，当前数据库文件版本为: ${db.version}")
                db.execSQL("ALTER TABLE `chat_messages` ADD COLUMN `is_deleted` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `ConversationEntity` ADD COLUMN `last_summarized_message_time` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `chat_segments` ADD COLUMN `start_time` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `chat_segments` ADD COLUMN `end_time` INTEGER NOT NULL DEFAULT 0")
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
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_message_nodes_conversation_id` ON `chat_message_nodes` (`conversation_id`)")
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

object DiaryImageConverter {
    @TypeConverter
    fun fromImages(images: List<DiaryImage>): String = JsonInstant.encodeToString(images)

    @TypeConverter
    fun toImages(json: String): List<DiaryImage> {
        if (json.isBlank()) return emptyList()
        return runCatching { JsonInstant.decodeFromString<List<DiaryImage>>(json) }.getOrDefault(emptyList())
    }
}
