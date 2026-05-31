package me.rerere.rikkahub.service

import android.accessibilityservice.AccessibilityService
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
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

    private val shoppingApps = listOf(
        "com.taobao.taobao", "com.jingdong.app.mall", "com.xunmeng.pinduoduo",
        "com.tmall.android", "com.xingin.xhs", "com.sankuai.meituan",
        "com.tmall.wireless", "com.dianping.v1", "ailand.lastchat.rikkafork.cocolal",
        "com.android.chrome", "com.tencent.mtt", "com.quark.browser",
        "com.netease.cloudmusic", "com.tencent.mm", "cn.missevan",
        "com.luyuan.custom", "com.openai.chatgpt", "com.larus.nova",
        "com.anthropic.claude", "ai.x.grok", "com.dragon.read",
        "com.instagram.android", "com.ss.android.ugc.aweme",
        "tv.danmaku.bili", "com.tencent.weread"
    )

    private val lastTriggerTimeMap = mutableMapOf<Long, Long>()
    private var pollingJob: Job? = null

    // 屏幕状态监听：AccessibilityService 无法直接感知熄屏，需靠广播
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> syncDeviceState(screenTransition = true, screenOn = true)
                Intent.ACTION_SCREEN_OFF -> syncDeviceState(screenTransition = true, screenOn = false)
            }
        }
    }

    override fun onServiceConnected() {
        Log.d(tag, "Evolia Accessibility Service connected")

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)

        scope.launch {
            DeviceCommandHub.commands.collectLatest { command ->
                handleDeviceCommand(command)
            }
        }
        startPolling()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(screenReceiver)
        pollingJob?.cancel()
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                delay(30_000)
                syncDeviceState()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val packageName = event.packageName?.toString() ?: return
                syncDeviceState(newPackage = packageName)
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val source = event.source
                val text = source?.text?.toString() ?: source?.contentDescription?.toString() ?: ""
                if (text.isNotBlank()) recordAction("点击了: $text")
            }
            else -> {}
        }
    }

    override fun onInterrupt() {}

    private fun handleDeviceCommand(command: String) {
        when (command) {
            "LOCK_SCREEN" -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            "GO_HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "SHOW_RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "SHOW_NOTIFICATIONS" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        }
    }

    private fun syncDeviceState(
        newPackage: String? = null,
        screenTransition: Boolean = false,
        screenOn: Boolean? = null
    ) {
        scope.launch {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val oldState = userDeviceStateRepo.getUserDeviceState().run { firstOrNull() } ?: UserDeviceStateEntity()

            val now = System.currentTimeMillis()
            val isScreenOn = screenOn ?: powerManager.isInteractive
            val packageName = (if (isScreenOn) newPackage ?: oldState.foregroundApp else "").ifBlank { "" }

            // 1. 处理起始时间点 (Session Start)
            var appSessionStart = oldState.appSessionStartMs
            var continuousStart = oldState.continuousSessionStartMs

            if (!isScreenOn) {
                appSessionStart = 0L
                continuousStart = 0L
            } else {
                // 屏幕刚刚亮起或初次运行
                if (continuousStart <= 0L || (screenTransition && screenOn == true)) {
                    continuousStart = now
                }
                // App 切换或初次运行
                if (packageName.isNotBlank() && (packageName != oldState.foregroundApp || appSessionStart <= 0L)) {
                    appSessionStart = now
                }
            }

            // 2. 统计今日累计时长 (Duration Compensation)
            val startTime = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, now)
            val systemTotal = if (packageName.isNotBlank()) {
                stats?.find { it.packageName == packageName }?.totalTimeInForeground ?: 0L
            } else 0L

            // 抖音等 App 补偿算法：UsageStatsManager 数据延迟严重
            // 如果处于活跃状态且 App 没变，我们将“系统记录”与“本地计时器”取大值
            var realTodayDurationMs = systemTotal
            if (isScreenOn && packageName.isNotBlank() && packageName == oldState.foregroundApp && appSessionStart > 0) {
                val sessionElapsed = now - appSessionStart
                // 补偿逻辑：系统记录可能还没刷新，我们用本地估算的累加值
                realTodayDurationMs = maxOf(systemTotal, oldState.todayDurationMs, sessionElapsed)
            }

            val contextText = if (isScreenOn && shoppingApps.contains(packageName)) scanScreenContext() else ""

            val currentState = oldState.copy(
                foregroundApp = packageName,
                foregroundAppName = if (packageName.isNotBlank()) getAppName(packageName) else "桌面/熄屏",
                isScreenOn = isScreenOn,
                todayDurationMs = realTodayDurationMs,
                screenContext = contextText,
                appSessionStartMs = appSessionStart,
                continuousSessionStartMs = continuousStart,
                lastUpdated = now
            )

            userDeviceStateRepo.updateDeviceState(currentState)
            if (isScreenOn) checkAllMonitors(currentState)
        }
    }

    private fun recordAction(action: String) {
        scope.launch {
            val current = userDeviceStateRepo.getUserDeviceState().run { firstOrNull() } ?: UserDeviceStateEntity()
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            val existingActions = current.recentActions.lines().filter { it.isNotBlank() }.take(4)
            val newActions = ("[$time] $action" + "\n" + existingActions.joinToString("\n")).trim()
            userDeviceStateRepo.updateDeviceState(current.copy(recentActions = newActions))
            // 动作后顺便同步一下状态和检查监控
            syncDeviceState()
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
        val now = System.currentTimeMillis()
        val cooldownMin = (conditions["cooldown_minutes"]?.jsonPrimitive?.longOrNull ?: 5L).coerceAtLeast(2L)
        val lastTrigger = lastTriggerTimeMap[taskId] ?: 0L
        if (now - lastTrigger < cooldownMin * 60 * 1000) return false

        val timeRange = conditions["time_range"]?.jsonObject
        if (timeRange != null) {
            val start = timeRange["start"]?.jsonPrimitive?.content ?: ""
            val end = timeRange["end"]?.jsonPrimitive?.content ?: ""
            if (!isCurrentTimeInRange(start, end)) return false
        }

        val screenStatus = conditions["screen_status"]?.jsonPrimitive?.content
        if (screenStatus != null && state.isScreenOn != (screenStatus == "ON")) return false

        // 4. 每日累计时长阈值
        val durationThreshold = conditions["usage_duration_minutes"]?.jsonPrimitive?.intOrNull
        if (durationThreshold != null && (state.todayDurationMs / 60000) < durationThreshold) return false

        // 4.1 单次持续使用时长阈值 (Continuous app usage)
        val appContinuousThreshold = conditions["continuous_usage_minutes"]?.jsonPrimitive?.intOrNull
        if (appContinuousThreshold != null && state.appSessionStartMs > 0) {
            val continuousMs = now - state.appSessionStartMs
            if (continuousMs / 60000 < appContinuousThreshold) return false
        }

        // 4.2 手机持续使用时长阈值 (Continuous screen on)
        val totalContinuousThreshold = conditions["total_continuous_minutes"]?.jsonPrimitive?.intOrNull
        if (totalContinuousThreshold != null && state.continuousSessionStartMs > 0) {
            val continuousMs = now - state.continuousSessionStartMs
            if (continuousMs / 60000 < totalContinuousThreshold) return false
        }

        val targetApp = conditions["foreground_app"]?.jsonPrimitive?.content
        if (targetApp != null && !state.foregroundAppName.contains(targetApp, ignoreCase = true)) return false

        val contentContains = conditions["content_contains"]?.jsonPrimitive?.content
        if (contentContains != null && !state.screenContext.contains(contentContains, ignoreCase = true)) return false

        return true
    }

    private suspend fun triggerMonitor(task: AgentMonitorTaskEntity, state: UserDeviceStateEntity) {
        lastTriggerTimeMap[task.id] = System.currentTimeMillis()
        val actions = json.parseToJsonElement(task.actions).jsonArray
        val triggerAction = actions.find { it.jsonObject["type"]?.jsonPrimitive?.content == "SEND_HIDDEN_MESSAGE" } ?: return

        val template = triggerAction.jsonObject["content"]?.jsonPrimitive?.content ?: ""
        val now = System.currentTimeMillis()
        val durationMin = state.todayDurationMs / 60000
        val appContinuousMin = if (state.appSessionStartMs > 0) (now - state.appSessionStartMs) / 60000 else 0
        val totalContinuousMin = if (state.continuousSessionStartMs > 0) (now - state.continuousSessionStartMs) / 60000 else 0
        val currentTime = SimpleDateFormat("HH:mm", Locale.US).format(Date())

        val finalMsg = template
            .replace("{app_name}", state.foregroundAppName)
            .replace("{duration}", "$durationMin 分钟")
            .replace("{continuous_duration}", "$appContinuousMin 分钟")
            .replace("{total_continuous_duration}", "$totalContinuousMin 分钟")
            .replace("{recent_actions}", state.recentActions.ifBlank { "无近期点击" })
            .replace("{screen_context}", state.screenContext.ifBlank { "无上下文" })
            .replace("{current_time}", currentTime)

        chatService.executeAgentTask(
            me.rerere.rikkahub.core.data.db.entity.AgentTaskEntity(
                assistantId = task.assistantId,
                taskType = "MONITOR_TRIGGER",
                taskData = buildJsonObject {
                    put("instruction", finalMsg)
                    put("monitor_name", task.monitorName)
                }.toString(),
                scheduledTime = now
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

    private fun scanScreenContext(): String {
        val rootNode = rootInActiveWindow ?: return ""
        val texts = mutableListOf<String>()
        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null || !node.isVisibleToUser) return
            val text = node.text?.toString() ?: node.contentDescription?.toString()
            if (!text.isNullOrBlank() && text.length > 1) texts.add(text.trim())
            for (i in 0 until node.childCount) traverse(node.getChild(i))
        }
        traverse(rootNode)
        return texts.distinct().take(30).joinToString(" | ")
    }
}
