package me.rerere.rikkahub.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import me.rerere.rikkahub.R

class VoiceCallForegroundService : Service() {
    companion object {
        private const val TAG = "VoiceCallFgService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "voice_call_foreground"
        private var wakeLock: PowerManager.WakeLock? = null

        fun start(context: Context) {
            val intent = Intent(context, VoiceCallForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start foreground service: ${e.message}")
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, VoiceCallForegroundService::class.java)
            context.stopService(intent)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = createNotification()

        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    0
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to startForeground: ${e.message}")
        }

        // 获取 WakeLock，防止息屏后 CPU 休眠导致通话中断
        acquireWakeLock()

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Evolia::VoiceCallWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(30 * 60 * 1000L) // 30 分钟自动释放兜底
        }
        Log.i(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.i(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_voice_call_active))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        val notificationManager = NotificationManagerCompat.from(this)
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.notification_channel_voice_call))
            .build()
        notificationManager.createNotificationChannel(channel)
    }
}
