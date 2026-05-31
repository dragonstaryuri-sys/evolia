package me.rerere.rikkahub.service

import android.accessibilityservice.AccessibilityService
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import me.rerere.rikkahub.core.data.db.entity.AgentMonitorTaskEntity
import me.rerere.rikkahub.core.data.db.entity.UserDeviceStateEntity
import me.rerere.rikkahub.core.data.repository.AgentMonitorTaskRepository
import me.rerere.rikkahub.core.data.repository.UserDeviceStateRepository
import me.rerere.rikkahub.data.ai.tools.DeviceCommandHub
import org.koin.android.ext.android.inject
import java.text.SimpleDateFormat
import java.util.*

class EvoliaMonitorService : AccessibilityService() {
    private val tag = "EvoliaMonitor"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val userDeviceStateRepo by inject<UserDeviceStateRepository>()
    private val monitorTaskRepo by inject<AgentMonitorTaskRepository>()
    private val chatService by inject<ChatService>()
    private val json = Json { ignoreUnknownKeys = true }

    // 电商 App 包名列表 (可根据需要扩展)
    private val shoppingApps = listOf(
        "com.taobao.taobao",
        "com.jingdong.app.mall",
        "com.xunmeng.pinduoduo",
        "com.tmall.android",
        "com.xingin.xhs",
        "com.sankuai.meituan",
        "com.tmall.wireless",
        "com.dianping.v1",
        "ailand.lastchat.rikkafork.cocolal"
    )

    // 缓存各任务的最后触发时间，实现冷却逻辑
    private val lastTriggerTimeMap = mutableMapOf<Long, Long>()

    override fun onServiceConnected() {
        Log.d(tag, "Evolia Accessibility Service connected")
        // 监听来自 LocalTool 的即时控制指令 (LOCK_SCREEN, GO_HOME 等)
        scope.launch {
            DeviceCommandHub.commands.collectLatest { command ->
                handleDeviceCommand(command)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val packageName = event.packageName?.toString() ?: return
                updateForegroundApp(packageName, getAppName(packageName))
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val source = event.source
                // 同时检查 text 和 contentDescription，解决 recent_actions 为空的问题
                val text = source?.text?.toString() ?: source?.contentDescription?.toString() ?: ""
                if (text.isNotBlank()) recordAction("点击了: $text")
            }
            else -> {}
        }
    }

    override fun onInterrupt() {}

    private fun handleDeviceCommand(command: String) {
        Log.d(tag, "Executing command: $command")
        when (command) {
            "LOCK_SCREEN" -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            "GO_HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "SHOW_RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "SHOW_NOTIFICATIONS" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        }
    }

    private fun updateForegroundApp(packageName: String, appName: String) {
        scope.launch {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

            val endTime = System.currentTimeMillis()
            val startTime = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            }.timeInMillis

            val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
            val durationMs = stats?.find { it.packageName == packageName }?.totalTimeInForeground ?: 0L

            // 如果是电商 App，则抓取屏幕上下文
            val contextText = if (shoppingApps.contains(packageName)) {
                scanScreenContext()
            } else ""

            val currentState = UserDeviceStateEntity(
                id = 0,
                foregroundApp = packageName,
                foregroundAppName = appName,
                isScreenOn = powerManager.isInteractive,
                todayDurationMs = durationMs,
                screenContext = contextText, // 保存屏幕内容
                lastUpdated = System.currentTimeMillis()
            )
            userDeviceStateRepo.updateDeviceState(currentState)
            checkAllMonitors(currentState)
        }
    }

    private fun recordAction(action: String) {
        scope.launch {
            val current = userDeviceStateRepo.getUserDeviceState().run { firstOrNull() } ?: UserDeviceStateEntity()
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            // 只保留最近 5 条操作描述
            val existingActions = current.recentActions.lines().filter { it.isNotBlank() }.take(4)
            val newActions = ("[$time] $action" + "\n" + existingActions.joinToString("\n")).trim()
            userDeviceStateRepo.updateDeviceState(current.copy(recentActions = newActions))
        }
    }

    private suspend fun checkAllMonitors(state: UserDeviceStateEntity) {
        val tasks = monitorTaskRepo.getAllEnabledTasks().run { firstOrNull() } ?: return

        tasks.forEach { task ->
            try {
                val conditions = json.parseToJsonElement(task.conditions).jsonObject
                if (evaluateConditions(conditions, state, task.id)) {
                    triggerMonitor(task, state)
                }
            } catch (e: Exception) {
                Log.e(tag, "Monitor check failed: ${task.monitorName}", e)
            }
        }
    }

    private fun evaluateConditions(conditions: JsonObject, state: UserDeviceStateEntity, taskId: Long): Boolean {
        // 1. 冷却时间判断
        val cooldownMin = conditions["cooldown_minutes"]?.jsonPrimitive?.longOrNull ?: 5L
        val lastTrigger = lastTriggerTimeMap[taskId] ?: 0L
        if (System.currentTimeMillis() - lastTrigger < cooldownMin * 60 * 1000) return false

        // 2. 时间范围
        val timeRange = conditions["time_range"]?.jsonObject
        if (timeRange != null) {
            val start = timeRange["start"]?.jsonPrimitive?.content ?: ""
            val end = timeRange["end"]?.jsonPrimitive?.content ?: ""
            if (!isCurrentTimeInRange(start, end)) return false
        }

        // 3. 屏幕状态
        val screenStatus = conditions["screen_status"]?.jsonPrimitive?.content
        if (screenStatus != null && state.isScreenOn != (screenStatus == "ON")) return false

        // 4. 时长阈值
        val durationThreshold = conditions["usage_duration_minutes"]?.jsonPrimitive?.intOrNull
        if (durationThreshold != null && (state.todayDurationMs / 60000) < durationThreshold) return false

        // 5. 特定应用过滤
        val targetApp = conditions["foreground_app"]?.jsonPrimitive?.content
        if (targetApp != null && !state.foregroundAppName.contains(targetApp, ignoreCase = true)) return false

        return true
    }

    private suspend fun triggerMonitor(task: AgentMonitorTaskEntity, state: UserDeviceStateEntity) {
        lastTriggerTimeMap[task.id] = System.currentTimeMillis()

        // 解析模板
        val actions = json.parseToJsonElement(task.actions).jsonArray
        val triggerAction = actions.find { it.jsonObject["type"]?.jsonPrimitive?.content == "SEND_HIDDEN_MESSAGE" } ?: return

        val template = triggerAction.jsonObject["content"]?.jsonPrimitive?.content ?: ""
        val durationMin = state.todayDurationMs / 60000
        val currentTime = SimpleDateFormat("HH:mm", Locale.US).format(Date())

        val finalMsg = template
            .replace("{app_name}", state.foregroundAppName)
            .replace("{duration}", "$durationMin 分钟")
            .replace("{recent_actions}", state.recentActions.ifBlank { "无近期点击" })
            .replace("{screen_context}", state.screenContext.ifBlank { "未抓取到有用文字" }) // 新增占位符支持
            .replace("{current_time}", currentTime)

        chatService.executeAgentTask(
            me.rerere.rikkahub.core.data.db.entity.AgentTaskEntity(
                assistantId = task.assistantId,
                taskType = "MONITOR_TRIGGER",
                taskData = buildJsonObject {
                    put("instruction", finalMsg)
                    put("monitor_name", task.monitorName)
                }.toString(),
                scheduledTime = System.currentTimeMillis()
            )
        )
    }

    private fun getAppName(packageName: String): String = try {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
    } catch (e: Exception) { packageName }

    private fun isCurrentTimeInRange(start: String, end: String): Boolean {
        val now = Calendar.getInstance()
        val current = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        fun parse(s: String) = s.split(":").let { it[0].toInt() * 60 + it[1].toInt() }
        val s = parse(start); val e = parse(end)
        return if (s <= e) current in s..e else current >= s || current <= e
    }

    /**
     * 遍历当前窗口的所有节点，抓取可见文本
     */
    private fun scanScreenContext(): String {
        val rootNode = rootInActiveWindow ?: return ""
        val texts = mutableListOf<String>()

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (!node.isVisibleToUser) return // 只抓取用户可见的

            val text = node.text?.toString() ?: node.contentDescription?.toString()
            if (!text.isNullOrBlank() && text.length > 1) {
                // 简单过滤掉太短的（如“OK”, “>”）或纯数字
                texts.add(text.trim())
            }

            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }

        traverse(rootNode)
        // 使用去重和限制长度，防止文本爆炸
        return texts.distinct().take(30).joinToString(" | ")
    }
}
