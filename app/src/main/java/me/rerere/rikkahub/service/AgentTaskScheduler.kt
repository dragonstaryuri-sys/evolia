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
        Log.d(TAG, "checkAndRescheduleOverdueTasks() called from thread: ${Thread.currentThread().name}")

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            try {
                Log.d(TAG, "Coroutine started: Checking for overdue tasks...")

                // 设置 5 秒超时，防止 Flow 永久挂起
                val allTasks = withTimeoutOrNull(5000) {
                    Log.d(TAG, "Fetching tasks from repository...")
                    agentTaskRepository.getAllTasks().firstOrNull()
                } ?: run {
                    Log.w(TAG, "Database query timed out or returned null!")
                    emptyList<AgentTaskEntity>()
                }

                Log.d(TAG, "Total tasks found in DB: ${allTasks.size}")

                val currentTime = System.currentTimeMillis()
                val pendingTasks = allTasks.filter {
                    !it.isExecuted && it.scheduledTime <= currentTime
                }

                if (pendingTasks.isNotEmpty()) {
                    Log.d(TAG, "Found ${pendingTasks.size} overdue tasks, rescheduling now...")
                    pendingTasks.forEach { task ->
                        scheduleTask(task)
                    }
                } else {
                    Log.d(TAG, "No overdue tasks need rescheduling.")
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

        val existingIntent = PendingIntent.getBroadcast(
            context,
            999,
            intent,
            PendingIntent.FLAG_NO_CREATE or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        if (existingIntent == null) {
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                999,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )

            val triggerAt = System.currentTimeMillis() + 30 * 60 * 1000L

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
                Log.d(TAG, "Heartbeat alarm scheduled for +30min")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to setup heartbeat alarm", e)
            }
        } else {
            Log.d(TAG, "Heartbeat alarm already active.")
        }
    }
}
