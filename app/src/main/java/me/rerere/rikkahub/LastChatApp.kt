package me.rerere.rikkahub

import android.app.Application
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private const val TAG = "LastChatApp"

const val CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID = "chat_completed"
const val BACKUP_NOTIFICATION_CHANNEL_ID = "backup_status"

class LastChatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Application onCreate started!")

        startKoin {
            androidLogger()
            androidContext(this@LastChatApp)
            workManagerFactory()
            modules(appModule, viewModelModule, dataSourceModule, repositoryModule, discoverModule)
        }
        this.createNotificationChannel()

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        DatabaseUtil.setCursorWindowSize(16 * 1024 * 1024)
        deleteTempFiles()

        get<FirebaseRemoteConfig>().apply {
            setConfigSettingsAsync(remoteConfigSettings {
                minimumFetchIntervalInSeconds = 1800
            })
            setDefaultsAsync(R.xml.remote_config_defaults)
            fetchAndActivate()
        }

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
            PeriodicWorkRequestBuilder<SpontaneousWorker>(30, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
        )

        // 修改点：将 UPDATE 改为 REPLACE，确保逻辑变更后任务被重置并执行
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

        get<AppScope>().launch {
            get<SettingsStore>().settingsFlow
                .map { it.consolidationWorkerIntervalMinutes to it.consolidationRequiresDeviceIdle }
                .distinctUntilChanged()
                .collect { (interval, idle) ->
                    val constraints = Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .apply {
                            if (idle) setRequiresDeviceIdle(true)
                        }
                        .build()

                    WorkManager.getInstance(this@LastChatApp).enqueueUniquePeriodicWork(
                        "memory_consolidation",
                        ExistingPeriodicWorkPolicy.UPDATE,
                        PeriodicWorkRequestBuilder<MemoryConsolidationWorker>(
                            interval.toLong().coerceAtLeast(15), TimeUnit.MINUTES
                        )
                            .setConstraints(constraints)
                            .build()
                    )
                }
        }

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
                    WorkManager.getInstance(this@LastChatApp).enqueueUniqueWork(
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
                NotificationManagerCompat.IMPORTANCE_LOW
            )
            .setName(getString(R.string.notification_channel_backup))
            .build()
        notificationManager.createNotificationChannel(backupChannel)
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
