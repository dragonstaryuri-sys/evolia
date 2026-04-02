package me.rerere.rikkahub.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.rikkahub.core.data.db.entity.AgentTaskEntity
import me.rerere.rikkahub.core.data.repository.AgentTaskRepository
import java.util.concurrent.TimeUnit
import me.rerere.common.android.Logging
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class AgentTaskScheduler(private val context: Context) : KoinComponent {
    private val agentTaskRepository: AgentTaskRepository by inject()

    /**
     * Schedule a specific task using WorkManager
     */
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

        Logging.log("AgentTaskScheduler", "Scheduled task ${task.id} with delay $initialDelay ms")
    }

    fun cancelTask(taskId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork("agent_task_$taskId")
    }

    /**
     * Check for overdue tasks and reschedule them immediately.
     * This is useful for app launch or heartbeat triggers.
     */
    fun checkAndRescheduleOverdueTasks() {
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            try {
                val currentTime = System.currentTimeMillis()
                val allTasks = agentTaskRepository.getAllTasks().first()
                val pendingTasks = allTasks.filter {
                    !it.isExecuted && it.scheduledTime <= currentTime
                }

                if (pendingTasks.isNotEmpty()) {
                    Logging.log("AgentTaskScheduler", "Found ${pendingTasks.size} overdue tasks on check, rescheduling...")
                    pendingTasks.forEach { task ->
                        scheduleTask(task)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Set up a heartbeat alarm to check for overdue tasks every 30 minutes.
     */
    fun setupHeartbeatAlarm() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AgentTaskReceiver::class.java).apply {
            action = "me.rerere.rikkahub.ACTION_CHECK_TASKS"
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            999,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 30 minutes interval
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
            Logging.log("AgentTaskScheduler", "Heartbeat alarm scheduled for 30 minutes later")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
