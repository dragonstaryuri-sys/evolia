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
                "AGENT_TASK" -> {
                    Log.d(TAG, "Running AGENT_TASK via ChatService")
                    chatService.executeAgentTask(task)
                    true
                }
                "NOTIFICATION" -> {
                    val title = data["title"]?.jsonPrimitive?.contentOrNull ?: "Notification"
                    val content = data["content"]?.jsonPrimitive?.contentOrNull ?: ""
                    Log.d(TAG, "Sending notification: $title")
                    sendNotification(title, content)
                    true
                }
                "EMAIL" -> {
                    val to = data["to"]?.jsonPrimitive?.contentOrNull ?: ""
                    val subject = data["subject"]?.jsonPrimitive?.contentOrNull ?: ""
                    val content = data["content"]?.jsonPrimitive?.contentOrNull ?: ""
                    Log.d(TAG, "Sending email to: $to")
                    sendEmail(to, subject, content)
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
                    val nextTime = task.scheduledTime + task.repeatInterval
                    val nextTask = task.copy(
                        id = 0, // Create as new record
                        scheduledTime = nextTime,
                        isExecuted = false,
                        createdAt = System.currentTimeMillis()
                    )
                    val newId = agentTaskRepository.addTask(nextTask)
                    agentTaskScheduler.scheduleTask(nextTask.copy(id = newId))
                    Log.d(TAG, "Scheduled next repeat task: $newId at $nextTime")
                }

                Result.success()
            } else {
                Log.w(TAG, "Task execution failed (success=false), worker will NOT retry to avoid loops if type is unknown")
                // 如果是类型未知导致的失败，不要重试，直接标记为失败或结束，避免死循环
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
        val channel = NotificationChannel(channelId, "Agent Tasks", NotificationManager.IMPORTANCE_DEFAULT)
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

    private fun sendEmail(to: String, subject: String, content: String): Boolean {
        val settings = settingsStore.settingsFlow.value
        val emailAccount = settings.emailConfig.account
        val authCode = secretKeyManager.getEmailPassword("")

        if (!settings.emailConfig.enabled || emailAccount.isBlank() || authCode.isBlank()) {
            Log.w(TAG, "Email not sent: Email service is disabled or not configured.")
            return false
        }

        return try {
            val props = Properties().apply {
                put("mail.smtp.host", "smtp.qq.com")
                put("mail.smtp.port", "465")
                put("mail.smtp.auth", "true")
                put("mail.smtp.ssl.enable", "true")
                put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
            }

            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication(emailAccount, authCode)
                }
            })

            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(emailAccount))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
                setSubject(subject)
                setText(content)
            }

            Transport.send(message)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send email", e)
            false
        }
    }
}
