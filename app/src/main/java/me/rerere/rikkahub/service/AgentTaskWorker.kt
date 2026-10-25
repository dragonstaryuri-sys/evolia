package me.rerere.rikkahub.service

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
import me.rerere.rikkahub.core.data.db.entity.AgentTaskEntity
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

// 可执行的任务类型白名单：认领前校验，未知类型不推进重复链
private val KNOWN_TASK_TYPES = setOf("AGENT_TASK", "EMAIL", "NOTIFICATION", "DIARY")

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

        // 认领状态：事务提交后置 true；异常分支据此判断重复链是否已推进、还能否安全重试
        var claimed = false

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

            val data = JsonInstant.parseToJsonElement(task.taskData) as? JsonObject
            if (data == null) {
                Log.e(TAG, "Task $taskId has invalid JSON data: ${task.taskData}")
                return Result.failure()
            }

            // 未知类型在认领前直接失败：认领会推进重复链，无法执行的任务不应该被续接
            val isKnownType = task.taskType in KNOWN_TASK_TYPES
            if (!isKnownType) {
                Log.w(TAG, "Unknown task type: [${task.taskType}] for task $taskId")
                return Result.failure()
            }

            // 原子认领 + 事务内创建下一周期记录。
            // 同一 taskId 可能被多条链并发触发（WorkManager 延时链、闹钟立即链、心跳重调度），
            // 只有条件更新成功（claimed=true）的那个实例会继续执行，其余直接退出，
            // 从根源上杜绝「重复执行 + 重复创建下一周期任务」。
            val nextScheduledTime = calculateNextScheduledTime(task)
            val claimResult = agentTaskRepository.claimAndChainNext(task, nextScheduledTime)
            if (!claimResult.claimed) {
                Log.d(TAG, "Task $taskId already claimed by another worker, skipping.")
                return Result.success()
            }
            claimed = true

            Log.d(TAG, "Executing task ${task.id} of type [${task.taskType}]")

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
                else -> false // 前面已做类型白名单校验，理论上不可达
            }

            if (success) {
                // 认领事务已插入下一周期记录，这里只需补上调度（进程若在此前被杀，
                // 心跳闹钟会在到期后通过 getPendingTasks 兜底捞回）
                claimResult.nextTask?.let { nextTask ->
                    agentTaskScheduler.scheduleTask(nextTask)
                    Log.d(TAG, "Scheduled next repeat task: ${nextTask.id} at ${nextTask.scheduledTime} (Interval: ${task.repeatInterval}ms)")
                }
                Result.success()
            } else {
                // 认领事务已提交、重复链已推进，无法回滚重试，仅记录失败
                Log.w(TAG, "Task execution failed (success=false) for task ${task.id}")
                Result.failure()
            }
        } catch (e: Exception) {
            if (claimed) {
                // 已认领：任务已被标记执行，重试只会被 isExecuted 检查跳过，直接失败
                Log.e(TAG, "Exception during claimed task $taskId execution (repeat chain already advanced): ${e.message}", e)
                Result.failure()
            } else {
                // 未认领：状态未受影响，可以安全重试
                Log.e(TAG, "Exception during task $taskId execution: ${e.message}", e)
                Result.retry()
            }
        }
    }

    /**
     * 计算重复任务的下一次执行时间；非重复任务（repeatInterval <= 0）返回 null。
     */
    private fun calculateNextScheduledTime(task: AgentTaskEntity): Long? {
        if (task.repeatInterval <= 0) return null

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
        return if (calculatedNextTime <= now) {
            now + task.repeatInterval
        } else {
            calculatedNextTime
        }
    }

    private fun sendNotification(title: String, content: String) {
        // AI 消息提醒开关（SET）：关闭则不再推送
        if (!settingsStore.settingsFlow.value.notificationAiReplyEnabled) return
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 归入统一的 ai_messages 渠道（渠道类别已精简）
        val channelId = me.rerere.rikkahub.service.notification.NotificationSoundManager.AI_MESSAGES_CHANNEL_ID

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
