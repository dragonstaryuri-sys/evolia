package me.rerere.rikkahub.service

import android.content.Context
import androidx.work.*
import me.rerere.rikkahub.core.data.db.entity.AgentTaskEntity
import java.util.concurrent.TimeUnit

class AgentTaskScheduler(private val context: Context) {
    fun scheduleTask(task: AgentTaskEntity) {
        val delay = task.scheduledTime - System.currentTimeMillis()
        if (delay < 0) return // Already passed

        val workRequest = OneTimeWorkRequestBuilder<AgentTaskWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("taskId" to task.id))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED) // Many tasks (Email, AI) need network
                    .build()
            )
            .addTag("agent_task_${task.id}")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "agent_task_${task.id}",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    fun cancelTask(taskId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork("agent_task_$taskId")
    }
}
