package me.rerere.rikkahub.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.rikkahub.core.data.repository.AgentTaskRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import me.rerere.common.android.Logging

class AgentTaskReceiver : BroadcastReceiver(), KoinComponent {
    private val agentTaskRepository: AgentTaskRepository by inject()
    private val agentTaskScheduler: AgentTaskScheduler by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Logging.log("AgentTaskReceiver", "Received action: $action")

        if (action == "me.rerere.rikkahub.ACTION_CHECK_TASKS" || action == Intent.ACTION_BOOT_COMPLETED) {
            val scope = CoroutineScope(Dispatchers.IO)
            scope.launch {
                try {
                    val currentTime = System.currentTimeMillis()
                    // 获取所有未执行的任务
                    // 从 Flow 中获取当前列表并过滤
                    val allTasks = agentTaskRepository.getAllTasks().first()
                    val pendingTasks = allTasks.filter {
                        !it.isExecuted && it.scheduledTime <= currentTime
                    }

                    if (pendingTasks.isNotEmpty()) {
                        Logging.log("AgentTaskReceiver", "Found ${pendingTasks.size} overdue tasks, rescheduling...")
                        pendingTasks.forEach { task ->
                            // 重新调度，WorkManager 会检查约束条件
                            agentTaskScheduler.scheduleTask(task)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    // 无论如何，设定下一个小时的闹钟，维持心跳
                    agentTaskScheduler.setupHeartbeatAlarm()
                }
            }
        }
    }
}
