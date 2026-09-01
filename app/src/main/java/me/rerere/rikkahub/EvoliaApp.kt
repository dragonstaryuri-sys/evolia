package me.rerere.rikkahub

import android.app.Application
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.di.appModule
import me.rerere.rikkahub.di.dataSourceModule
import me.rerere.rikkahub.di.repositoryModule
import me.rerere.rikkahub.di.viewModelModule
import me.rerere.rikkahub.discover.di.discoverModule
import me.rerere.rikkahub.utils.DatabaseUtil
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.datastore.SettingsStore
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import androidx.work.workDataOf
import me.rerere.rikkahub.service.MemoryConsolidationWorker
import me.rerere.rikkahub.service.SpontaneousWorker
import me.rerere.rikkahub.service.BackupWorker
import me.rerere.rikkahub.service.DiarySchedulerWorker
import me.rerere.rikkahub.service.AgentTaskScheduler
import java.util.concurrent.TimeUnit
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.tencent.bugly.crashreport.CrashReport
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar

private const val TAG = "EvoliaApp"

const val CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID = "chat_completed"
const val BACKUP_NOTIFICATION_CHANNEL_ID = "backup_status"
const val AGENT_TASK_NOTIFICATION_CHANNEL_ID = "agent_task_notification"

class EvoliaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Application onCreate started!")

        // 安全初始化：从 BuildConfig 读取由 local.properties 注入的 ID
        val buglyAppId = BuildConfig.BUGLY_APP_ID
        if (buglyAppId.isNotBlank()) {
            // 第三个参数为是否开启调试模式，建议在 Debug 包开启以查看上报日志
            CrashReport.initCrashReport(applicationContext, buglyAppId, BuildConfig.DEBUG)
            Log.d(TAG, "Bugly initialized with ID from local.properties")
        } else {
            Log.w(TAG, "Bugly App ID is missing! Please add 'bugly.appid' to your local.properties file.")
        }

        startKoin {
            androidLogger()
            androidContext(this@EvoliaApp)
            workManagerFactory()
            modules(appModule, viewModelModule, dataSourceModule, repositoryModule, discoverModule)
        }
        this.createNotificationChannel()

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        DatabaseUtil.setCursorWindowSize(16 * 1024 * 1024)
        deleteTempFiles()

        try {
            val agentTaskScheduler: AgentTaskScheduler = get()
            agentTaskScheduler.setupHeartbeatAlarm()
            agentTaskScheduler.checkAndRescheduleOverdueTasks()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AgentTaskScheduler", e)
        }

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "spontaneous_notification",
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<SpontaneousWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
        )

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "diary_scheduler",
            ExistingPeriodicWorkPolicy.REPLACE,
            PeriodicWorkRequestBuilder<DiarySchedulerWorker>(30, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
        )

        // Schedule Master Memory (L3) Daily Consolidation at 3:00 AM
        scheduleDailyMasterMemorySync()

        val appShortcutManager = me.rerere.rikkahub.utils.AppShortcutManager(this)
        get<AppScope>().launch {
            get<SettingsStore>().settingsFlow
                .map { Triple(it.recentlyUsedAssistants, it.assistants, it.init) }
                .distinctUntilChanged()
                .collect { (recentlyUsed, assistants, isInit) ->
                    if (!isInit) {
                        appShortcutManager.updateAssistantShortcuts(recentlyUsed, assistants)
                    }
                }
        }

        get<AppScope>().launch(Dispatchers.IO) {
            val prefs = getSharedPreferences("app_migrations", MODE_PRIVATE)
            if (!prefs.getBoolean("daily_activity_migrated_v1", false)) {
                try {
                    val conversationRepo = get<me.rerere.rikkahub.core.data.repository.ConversationRepository>()
                    conversationRepo.migrateConversationDatesToActivity()
                    prefs.edit().putBoolean("daily_activity_migrated_v1", true).apply()
                } catch (e: Exception) {
                    Log.e(TAG, "Daily activity migration failed", e)
                }
            }
        }

        get<AppScope>().launch {
            val settingsStore = get<SettingsStore>()
            val settings = settingsStore.settingsFlow.first { !it.init }
            if (settings.autoBackupOnStart && settings.webDavConfig.url.isNotBlank()) {
                val lastBackupTime = settings.lastAutoBackupTime
                val lastBackupDate = Instant.ofEpochMilli(lastBackupTime).atZone(ZoneId.systemDefault()).toLocalDate()
                val today = LocalDate.now()

                if (lastBackupDate.isBefore(today)) {
                    WorkManager.getInstance(this@EvoliaApp).enqueueUniqueWork(
                        "auto_backup_on_start",
                        ExistingWorkPolicy.REPLACE,
                        OneTimeWorkRequestBuilder<BackupWorker>()
                            .setConstraints(
                                Constraints.Builder()
                                    .setRequiredNetworkType(NetworkType.CONNECTED)
                                    .build()
                            )
                            .build()
                    )
                    settingsStore.update(settings.copy(lastAutoBackupTime = System.currentTimeMillis()))
                }
            }
        }
    }

    private fun scheduleDailyMasterMemorySync() {
        val calendar = Calendar.getInstance()
        val now = calendar.timeInMillis
        calendar.set(Calendar.HOUR_OF_DAY, 3)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        if (calendar.timeInMillis <= now) {
            calendar.add(Calendar.DAY_OF_MONTH, 1)
        }
        val initialDelay = calendar.timeInMillis - now

        val masterMemoryWork = PeriodicWorkRequestBuilder<MemoryConsolidationWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("INCREMENTAL_MASTER" to true))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "master_memory_daily_sync",
            ExistingPeriodicWorkPolicy.UPDATE,
            masterMemoryWork
        )
        Log.i(TAG, "Scheduled Master Memory daily sync at 3:00 AM (Initial delay: ${initialDelay / 1000 / 60} mins)")
    }

    private fun deleteTempFiles() {
        get<AppScope>().launch(Dispatchers.IO) {
            val dir = appTempFolder
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        }
    }

    private fun createNotificationChannel() {
        val notificationManager = NotificationManagerCompat.from(this)

        val chatCompletedChannel = NotificationChannelCompat
            .Builder(
                CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
            )
            .setName(getString(R.string.notification_channel_chat_completed))
            .setVibrationEnabled(true)
            .build()
        notificationManager.createNotificationChannel(chatCompletedChannel)

        val backupChannel = NotificationChannelCompat
            .Builder(
                BACKUP_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH // 提升为高优先级以支持悬浮通知
            )
            .setName(getString(R.string.notification_channel_backup))
            .setVibrationEnabled(true) // 开启震动
            .build()
        notificationManager.createNotificationChannel(backupChannel)

        val agentTaskChannel = NotificationChannelCompat
            .Builder(
                AGENT_TASK_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_DEFAULT
            )
            .setName(getString(R.string.notification_channel_agent_task))
            .setVibrationEnabled(true)
            .build()
        notificationManager.createNotificationChannel(agentTaskChannel)
    }

    override fun onTerminate() {
        super.onTerminate()
        get<AppScope>().cancel()
    }
}

class AppScope : CoroutineScope by CoroutineScope(
    SupervisorJob()
        + Dispatchers.Default
        + CoroutineName("AppScope")
        + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "AppScope exception", e)
        }
)
