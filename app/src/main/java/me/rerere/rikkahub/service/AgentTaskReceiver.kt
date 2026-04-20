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

        if (action == "me.rerere.rikkahub.ACTION_CHECK_TASKS" || action == Intent.ACTION_BOOT_COMPLETED) {
            // 1. 立即执行一次过期任务检查
            agentTaskScheduler.checkAndRescheduleOverdueTasks()

            // 2. 无论如何，设定下一个周期的闹钟，维持心跳循环
            agentTaskScheduler.setupHeartbeatAlarm()
        }
    }
}
