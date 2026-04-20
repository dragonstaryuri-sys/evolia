package me.rerere.rikkahub.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.firstOrNull
import me.rerere.rikkahub.core.data.db.entity.AgentTaskEntity
import me.rerere.rikkahub.core.data.repository.AgentTaskRepository
import java.util.concurrent.TimeUnit
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "AgentTaskScheduler"

class AgentTaskScheduler(private val context: Context) : KoinComponent {
    private val agentTaskRepository: AgentTaskRepository by inject()

    fun scheduleTask(task: AgentTaskEntity) {
        val delay = task.scheduledTime - System.currentTimeMillis()
        val initialDelay = if (delay < 0) 0L else delay

        val workRequest = OneTimeWorkRequestBuilder<AgentTaskWorker>()
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("taskId" to task.id))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag("agent_task_${task.id}")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "agent_task_${task.id}",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )

        Log.d(TAG, "Scheduled task ${task.id} (Type: ${task.taskType}) with delay $initialDelay ms")
    }

    fun cancelTask(taskId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork("agent_task_$taskId")
    }

    fun checkAndRescheduleOverdueTasks() {
        Log.d(TAG, "checkAndRescheduleOverdueTasks() called")

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            try {
                // 使用 getPendingTasks 直接查询数据库，而不是从 Flow 中过滤，这样更高效且不会因为 Flow 没发射值而挂起
                val pendingTasks = agentTaskRepository.getPendingTasks(System.currentTimeMillis())

                Log.d(TAG, "Found ${pendingTasks.size} overdue tasks in DB")

                if (pendingTasks.isNotEmpty()) {
                    pendingTasks.forEach { task ->
                        scheduleTask(task)
                    }
                }
            } catch (e: CancellationException) {
                Log.w(TAG, "Task check was cancelled")
            } catch (t: Throwable) {
                Log.e(TAG, "CRITICAL ERROR in checkAndRescheduleOverdueTasks: ${t.message}", t)
            }
        }
    }

    fun setupHeartbeatAlarm() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AgentTaskReceiver::class.java).apply {
            action = "me.rerere.rikkahub.ACTION_CHECK_TASKS"
        }

        // 移除 FLAG_NO_CREATE 检查，每次调用都重新设定闹钟。
        // 因为在 App 启动或 Receiver 触发时，我们都会立即执行一次 checkAndRescheduleOverdueTasks()，
        // 所以即使闹钟计时被重置，也已经完成了一次检查，不存在“永远检查不到”的问题。
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            999,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val interval = 30 * 60 * 1000L
        val triggerAt = System.currentTimeMillis() + interval

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }

            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(triggerAt))
            Log.d(TAG, "Heartbeat alarm scheduled to trigger at $timeStr (in 30 mins)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup heartbeat alarm", e)
        }
    }
}
