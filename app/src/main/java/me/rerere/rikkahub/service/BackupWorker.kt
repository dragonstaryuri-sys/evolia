package me.rerere.rikkahub.service

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import me.rerere.rikkahub.BACKUP_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.sync.WebdavSync
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private const val TAG = "BackupWorker"

class BackupWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams), KoinComponent {

    private val settingsStore: SettingsStore by inject()
    private val webdavSync: WebdavSync by inject()

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting background backup...")
        return try {
            val settings = settingsStore.settingsFlow.value
            if (settings.webDavConfig.url.isBlank()) {
                Log.w(TAG, "WebDAV URL is blank, skipping backup.")
                return Result.success()
            }

            webdavSync.backupToWebDav(settings.webDavConfig)
            Log.i(TAG, "Background backup completed successfully.")

            showSuccessNotification()

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Background backup failed", e)
            Result.retry()
        }
    }

    private fun showSuccessNotification() {
        try {
            // 1. 检查通知权限（适配 Android 13+）
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                        applicationContext,
                        android.Manifest.permission.POST_NOTIFICATIONS
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    Log.w(TAG, "Missing POST_NOTIFICATIONS permission, skipping notification")
                    return
                }
            }

            val notificationManager = NotificationManagerCompat.from(applicationContext)

            // 2. 检查应用通知开关是否打开
            if (!notificationManager.areNotificationsEnabled()) {
                Log.w(TAG, "Notifications are disabled for this app, skipping notification")
                return
            }

            val notification = NotificationCompat.Builder(applicationContext, BACKUP_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(applicationContext.getString(R.string.backup_notification_success_title))
                .setContentText(applicationContext.getString(R.string.backup_notification_success_desc))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(System.currentTimeMillis().toInt(), notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show notification", e)
        }
    }
}
