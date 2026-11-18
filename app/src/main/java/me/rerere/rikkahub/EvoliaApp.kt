package me.rerere.rikkahub

import android.app.Application
import android.util.Log
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
import me.rerere.rikkahub.service.CallAudioCleanupWorker
import me.rerere.rikkahub.service.DiarySchedulerWorker
import me.rerere.rikkahub.service.AgentTaskScheduler
import me.rerere.rikkahub.service.notification.NotificationSoundManager
import me.rerere.rikkahub.service.voice.CallRecovery
import me.rerere.rikkahub.service.voice.VoiceCallManager
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

        // 通用后台任务启动恢复（异步，不阻塞启动）：WorkManager 丢失的任务重入队、
        // 租约过期的执行中任务重置 queued、过期 DELIVERING 回调回 PENDING 并重试投递
        try {
            val backgroundTaskManager: me.rerere.rikkahub.data.backgroundtask.BackgroundTaskManager = get()
            get<AppScope>().launch(Dispatchers.IO) {
                runCatching { backgroundTaskManager.recoverTasks() }
                    .onFailure { Log.e(TAG, "BackgroundTaskManager.recoverTasks failed", it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize BackgroundTaskManager recovery", e)
        }

        // 扩展安装操作启动恢复（异步，不阻塞启动）：扫描未完成的安装/更新操作，
        // 按操作状态恢复文件与 Registry 一致（详设 8.3），并清理孤儿 staging/backup/安装目录
        try {
            val extensionInstaller: me.rerere.rikkahub.data.extensions.ExtensionInstaller = get()
            get<AppScope>().launch(Dispatchers.IO) {
                runCatching { extensionInstaller.recoverPendingOperations() }
                    .onFailure { Log.e(TAG, "ExtensionInstaller.recoverPendingOperations failed", it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize extension install recovery", e)
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

        // 通话录音 7 天过期清理：启动时检查一次 + 每 24h 周期清理
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "call_audio_cleanup",
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<CallAudioCleanupWorker>(24, TimeUnit.HOURS).build()
        )

        // 电台音频清理：备用音频 3 天过期 + 孤儿文件，每 24h 周期清理
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "radio_audio_cleanup",
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<me.rerere.rikkahub.service.RadioAudioCleanupWorker>(24, TimeUnit.HOURS).build()
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

        // 通话异常对账：恢复遗留 CONNECTED 记录（估算结束时间）+ 清理孤儿录音。
        // IO 协程异步执行，不阻塞启动；内部幂等，单条失败仅记日志。
        // VoiceCallManager → ChatService 构造含 ProcessLifecycleOwner.addObserver（主线程约束），
        // 必须在主线程解析，否则 IO 竞态先到时 Koin 创建失败、本轮对账被跳过（仅记错误日志）。
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                val activeCallId = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    get<VoiceCallManager>().currentActiveCallId
                }
                CallRecovery.reconcile(
                    context = applicationContext,
                    repository = get(),
                    activeCallId = activeCallId
                )
            }.onFailure { Log.e(TAG, "Call recovery reconcile failed", it) }
        }

        // 预置电台默认栏目（幂等：栏目空间为空时补入早安/晚安频道，允许用户删除/修改）
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching { get<me.rerere.rikkahub.data.radio.RadioService>().ensureDefaultChannels() }
                .onFailure { Log.e(TAG, "ensure default radio channels failed", it) }
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
                    // 原子事务：锁内基于最新快照重验开关后落时间戳，避免陈旧快照覆盖并发写
                    settingsStore.update { current ->
                        if (current.autoBackupOnStart && current.webDavConfig.url.isNotBlank()) {
                            current.copy(lastAutoBackupTime = System.currentTimeMillis())
                        } else {
                            current
                        }
                    }
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
        // 精简为 4 个应用自建渠道 + 语音通话（由 VoiceCallForegroundService 创建），
        // 并顺带清理历史遗留渠道，避免系统通知设置里类别过多。
        NotificationSoundManager(this).setupChannels()
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
