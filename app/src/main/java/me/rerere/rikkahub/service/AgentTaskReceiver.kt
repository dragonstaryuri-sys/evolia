package me.rerere.rikkahub.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import me.rerere.common.android.Logging
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class AgentTaskReceiver : BroadcastReceiver(), KoinComponent {
    private val agentTaskScheduler: AgentTaskScheduler by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Logging.log("AgentTaskReceiver", "Received action: $action")

        when (action) {
            // 处理精确任务触发闹钟
            ACTION_TRIGGER_TASK -> {
                val taskId = intent.getLongExtra("taskId", -1L)
                if (taskId != -1L) {
                    Logging.log("AgentTaskReceiver", "Triggering task $taskId immediately via AlarmManager wakeup")
                    agentTaskScheduler.executeTaskImmediately(taskId)
                }
            }

            // 处理心跳检查和开机启动
            "me.rerere.rikkahub.ACTION_CHECK_TASKS", Intent.ACTION_BOOT_COMPLETED -> {
                // 1. 立即执行一次过期任务检查
                agentTaskScheduler.checkAndRescheduleOverdueTasks()

                // 2. 无论如何，设定下一个周期的闹钟，维持心跳循环
                agentTaskScheduler.setupHeartbeatAlarm()
            }
        }
    }
}
