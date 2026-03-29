package me.rerere.rikkahub.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.common.JsonInstant
import me.rerere.rikkahub.core.data.repository.AgentTaskRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import me.rerere.common.android.Logging

class AgentTaskWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {

    private val agentTaskRepository: AgentTaskRepository by inject()
    // Other services will be injected here as needed

    override suspend fun doWork(): Result {
        val taskId = inputData.getLong("taskId", -1L)
        if (taskId == -1L) return Result.failure()

        return try {
            val task = agentTaskRepository.getTaskById(taskId) ?: return Result.failure()
            if (task.isExecuted) return Result.success()

            Logging.log("AgentTaskWorker", "Executing task ${task.id} of type ${task.taskType}")

            val data = JsonInstant.parseToJsonElement(task.taskData) as? JsonObject
                ?: return Result.failure()

            val success = when (task.taskType) {
                "NOTIFICATION" -> {
                    val title = data["title"]?.jsonPrimitive?.contentOrNull ?: "Notification"
                    val content = data["content"]?.jsonPrimitive?.contentOrNull ?: ""
                    // TODO: Implement notification logic (can reuse logic from ScheduledMessageWorker or LocalTools)
                    true
                }
                "EMAIL" -> {
                    // TODO: Implement email sending logic
                    true
                }
                "DIARY" -> {
                    // TODO: Implement diary writing logic
                    true
                }
                else -> false
            }

            if (success) {
                agentTaskRepository.updateTask(task.copy(isExecuted = true))
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
        }
    }
}
