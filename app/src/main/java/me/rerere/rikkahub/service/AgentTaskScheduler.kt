package me.rerere.rikkahub.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.*
import me.rerere.rikkahub.core.data.db.entity.AgentTaskEntity
import me.rerere.rikkahub.core.data.repository.AgentTaskRepository
import java.util.concurrent.TimeUnit
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "AgentTaskScheduler"
const val ACTION_TRIGGER_TASK = "me.rerere.rikkahub.ACTION_TRIGGER_TASK"

class AgentTaskScheduler(private val context: Context) : KoinComponent {
    private val agentTaskRepository: AgentTaskRepository by inject()

    fun scheduleTask(task: AgentTaskEntity) {
        val delay = task.scheduledTime - System.currentTimeMillis()
        val initialDelay = if (delay < 0) 0L else delay

        // 1. WorkManager 调度 (作为保底)
        // 注意：这里去掉了 NetworkType.CONNECTED 约束，防止息屏时因网络判定延迟任务
        val workRequest = OneTimeWorkRequestBuilder<AgentTaskWorker>()
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("taskId" to task.id))
            .addTag("agent_task_${task.id}")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "agent_task_${task.id}",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )

        // 2. AlarmManager 精确调度 (核心修复：解决息屏不触发)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AgentTaskReceiver::class.java).apply {
            action = ACTION_TRIGGER_TASK
            putExtra("taskId", task.id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            task.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // 针对 Android 12+ 的精确闹钟权限检查
                val canScheduleExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    alarmManager.canScheduleExactAlarms()
                } else true

                if (canScheduleExact) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        task.scheduledTime,
                        pendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        task.scheduledTime,
                        pendingIntent
                    )
                }
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    task.scheduledTime,
                    pendingIntent
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule exact alarm for task ${task.id}", e)
        }

        Log.d(TAG, "Scheduled task ${task.id} (Type: ${task.taskType}) at ${task.scheduledTime} (delay $initialDelay ms) with AlarmManager + WorkManager")
    }

    /**
     * 立即执行指定任务
     */
    fun executeTaskImmediately(taskId: Long) {
        val workRequest = OneTimeWorkRequestBuilder<AgentTaskWorker>()
            .setInputData(workDataOf("taskId" to taskId))
            .build()
        WorkManager.getInstance(context).enqueue(workRequest)
        Log.d(TAG, "Immediately enqueued task $taskId via WorkManager")
    }

    fun cancelTask(taskId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork("agent_task_$taskId")

        // 同时取消闹钟
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AgentTaskReceiver::class.java).apply {
            action = ACTION_TRIGGER_TASK
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            taskId.toInt(),
            intent,
            PendingIntent.FLAG_NO_CREATE or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
        }
    }

    fun checkAndRescheduleOverdueTasks() {
        Log.d(TAG, "checkAndRescheduleOverdueTasks() called")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            try {
                val pendingTasks = agentTaskRepository.getPendingTasks(System.currentTimeMillis())
                Log.d(TAG, "Found ${pendingTasks.size} overdue tasks in DB")
                if (pendingTasks.isNotEmpty()) {
                    pendingTasks.forEach { task ->
                        scheduleTask(task)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking overdue tasks", e)
            }
        }
    }

    fun setupHeartbeatAlarm() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AgentTaskReceiver::class.java).apply {
            action = "me.rerere.rikkahub.ACTION_CHECK_TASKS"
        }

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
            Log.d(TAG, "Heartbeat alarm scheduled (in 30 mins)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup heartbeat alarm", e)
        }
    }
}
