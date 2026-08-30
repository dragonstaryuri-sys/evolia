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
import me.rerere.rikkahub.data.sync.BackupHttpException
import me.rerere.rikkahub.data.sync.WebdavSync
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private const val TAG = "BackupWorker"

/**
 * 超过这个次数就不再重试 WorkManager，改直接失败并通知用户排查。
 * WorkManager 默认重试会指数退避，但最终有 24 小时上限，
 * 这里给一个较低阈值是为了让用户尽快看到问题（403/密码错等）。
 */
private const val MAX_ATTEMPTS = 3

class BackupWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams), KoinComponent {

    private val settingsStore: SettingsStore by inject()
    private val webdavSync: WebdavSync by inject()

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting background backup... (attempt=${runAttemptCount + 1}/$MAX_ATTEMPTS)")
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

            val shouldRetry = shouldRetry(e)
            val outOfAttempts = runAttemptCount + 1 >= MAX_ATTEMPTS

            // 两种情况必须立即通知用户：
            // 1) 错误被判定为不可重试（典型：401 / 403 / 404 / 507 这类配置或配额问题）
            // 2) 可重试但已经用完所有尝试次数
            if (!shouldRetry || outOfAttempts) {
                val reason = e.message?.take(220) ?: e.javaClass.simpleName
                showFailedNotification(
                    reason = reason,
                    isFinalFailure = true
                )
                return Result.failure()
            }

            // 仍在尝试次数内：先通知一次"失败，稍后重试"，让用户知道当前状态异常
            showFailedNotification(
                reason = e.message?.take(160) ?: e.javaClass.simpleName,
                isFinalFailure = false
            )
            return Result.retry()
        }
    }

    private fun shouldRetry(e: Exception): Boolean {
        val httpCode = (e as? BackupHttpException)?.code
        return when {
            httpCode != null -> {
                // 4xx（配置/权限/路径/文件大小/配额）一般重试无意义，直接让用户看提示
                // 5xx、429 等是服务器暂时异常，可以再试几次
                httpCode in 500..599 || httpCode == 429 || httpCode == 408
            }
            // 非 HTTP 异常（网络波动、超时、DNS）留给 WorkManager 再试
            else -> true
        }
    }

    private fun hasNotificationPermission(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    applicationContext,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "Missing POST_NOTIFICATIONS permission, skipping notification")
                return false
            }
        }
        val notificationManager = NotificationManagerCompat.from(applicationContext)
        if (!notificationManager.areNotificationsEnabled()) {
            Log.w(TAG, "Notifications are disabled for this app, skipping notification")
            return false
        }
        return true
    }

    private fun showSuccessNotification() {
        try {
            if (!hasNotificationPermission()) return
            val notificationManager = NotificationManagerCompat.from(applicationContext)

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

    private fun showFailedNotification(reason: String, isFinalFailure: Boolean) {
        try {
            if (!hasNotificationPermission()) {
                // 即便无法弹通知，也要在日志里明确写原因，便于 logcat 排查
                Log.e(
                    TAG,
                    "Backup failed (final=$isFinalFailure): $reason"
                )
                return
            }
            val notificationManager = NotificationManagerCompat.from(applicationContext)

            val title = applicationContext.getString(R.string.backup_notification_error_title)
            val baseDesc = if (isFinalFailure) {
                applicationContext.getString(R.string.backup_notification_error_desc_final)
            } else {
                applicationContext.getString(R.string.backup_notification_error_desc)
            }

            val style = NotificationCompat.BigTextStyle()
                .bigText("$baseDesc\n\n$reason")

            val notification = NotificationCompat.Builder(applicationContext, BACKUP_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(baseDesc)
                .setStyle(style)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()

            // 用同一个 ID 就好，失败的通知会被新的覆盖，避免轰炸
            notificationManager.notify(BACKUP_FAIL_NOTIFY_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show failed notification", e)
        }
    }

    companion object {
        private const val BACKUP_FAIL_NOTIFY_ID = 0x7BA6F01
    }
}
