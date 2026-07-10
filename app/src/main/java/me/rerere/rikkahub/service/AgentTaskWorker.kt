package me.rerere.rikkahub.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.common.JsonInstant
import me.rerere.rikkahub.core.data.repository.AgentTaskRepository
import me.rerere.rikkahub.core.data.db.entity.AgentDiaryEntity
import me.rerere.rikkahub.core.data.db.dao.AgentDiaryDAO
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.SecretKeyManager
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.*
import javax.mail.*
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import java.text.SimpleDateFormat

private const val TAG = "AgentTaskWorker"

class AgentTaskWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {

    private val agentTaskRepository: AgentTaskRepository by inject()
    private val agentDiaryDAO: AgentDiaryDAO by inject()
    private val settingsStore: SettingsStore by inject()
    private val secretKeyManager: SecretKeyManager by inject()
    private val agentTaskScheduler: AgentTaskScheduler by inject()
    private val chatService: ChatService by inject()

    override suspend fun doWork(): Result {
        val taskId = inputData.getLong("taskId", -1L)
        if (taskId == -1L) {
            Log.e(TAG, "Worker failed: No taskId provided in input data")
            return Result.failure()
        }

        return try {
            // 自动清理：在执行新任务前，先清理数据库中所有已执行完成的记录
            agentTaskRepository.deleteExecutedTasks()
            Log.d(TAG, "Auto cleanup: Deleted all executed tasks from database.")

            val task = agentTaskRepository.getTaskById(taskId)
            if (task == null) {
                Log.e(TAG, "Worker failed: Task with ID $taskId not found in database")
                return Result.failure()
            }

            if (task.isExecuted) {
                Log.d(TAG, "Task $taskId already executed, skipping.")
                return Result.success()
            }

            Log.d(TAG, "Executing task ${task.id} of type [${task.taskType}]")

            val data = JsonInstant.parseToJsonElement(task.taskData) as? JsonObject
            if (data == null) {
                Log.e(TAG, "Task $taskId has invalid JSON data: ${task.taskData}")
                return Result.failure()
            }

            val success = when (task.taskType) {
                "AGENT_TASK", "EMAIL", "NOTIFICATION" -> {
                    Log.d(TAG, "Running ${task.taskType} via ChatService (Instruction Triggered)")
                    chatService.executeAgentTask(task)
                    true
                }
                "DIARY" -> {
                    val content = data["content"]?.jsonPrimitive?.contentOrNull ?: ""
                    val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                    agentDiaryDAO.insertDiary(
                        AgentDiaryEntity(
                            assistantId = task.assistantId,
                            content = content,
                            date = date
                        )
                    )
                    Log.d(TAG, "Saved diary entry for $date")
                    true
                }
                else -> {
                    Log.w(TAG, "Unknown task type: [${task.taskType}] for task $taskId")
                    false
                }
            }

            if (success) {
                // Mark current task as executed
                agentTaskRepository.updateTask(task.copy(isExecuted = true))
                Log.d(TAG, "Task ${task.id} marked as executed.")

                // Handle repeating tasks
                if (task.repeatInterval > 0) {
                    val now = System.currentTimeMillis()
                    val threshold = 5 * 60 * 60 * 1000L // 5 小时阈值

                    // 根据重复间隔决定调度策略
                    val calculatedNextTime = if (task.repeatInterval < threshold) {
                        // 1. 动态间隔模式 (高频任务)：基于“实际执行时间”计算，防止关机后的通知轰炸
                        now + task.repeatInterval
                    } else {
                        // 2. 固定网格模式 (长周期任务)：基于“原定计划时间”计算，保证如每日/每周任务的准时性
                        task.scheduledTime + task.repeatInterval
                    }

                    // 安全检查：如果计算出的下一次时间依然早于或等于现在（比如手机关机时间超过了一个重复周期）
                    // 则强制切换到从“现在”开始计算，确保不会陷入连续补发通知的死循环
                    val finalNextTime = if (calculatedNextTime <= now) {
                        now + task.repeatInterval
                    } else {
                        calculatedNextTime
                    }

                    val nextTask = task.copy(
                        id = 0, // 创建新记录
                        scheduledTime = finalNextTime,
                        isExecuted = false,
                        createdAt = now
                    )
                    val newId = agentTaskRepository.addTask(nextTask)
                    agentTaskScheduler.scheduleTask(nextTask.copy(id = newId))
                    Log.d(TAG, "Scheduled next repeat task: $newId at $finalNextTime (Interval: ${task.repeatInterval}ms)")
                }

                Result.success()
            } else {
                Log.w(TAG, "Task execution failed (success=false), worker will NOT retry to avoid loops if type is unknown")
                Result.failure()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during task $taskId execution: ${e.message}", e)
            Result.retry()
        }
    }

    private fun sendNotification(title: String, content: String) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "agent_task_notification"
        // 使用 strings.xml 中的资源名
        val channel = NotificationChannel(
            channelId,
            applicationContext.getString(R.string.notification_channel_agent_task),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        notificationManager.createNotificationChannel(channel)

        val intent = Intent(applicationContext, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(applicationContext, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

}
