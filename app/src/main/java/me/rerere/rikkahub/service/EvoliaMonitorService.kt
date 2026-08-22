package me.rerere.rikkahub.service

import android.accessibilityservice.AccessibilityService
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Geocoder
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.telephony.TelephonyManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
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
    private var lastLocationCheckTime = 0L
    private var lastCellId: String? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> syncDeviceState(
                    screenTransition = true,
                    screenOn = true
                )

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
            DeviceCommandHub.commands.collectLatest { handleDeviceCommand(it) }
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
                // 恢复 30 秒轮询，确保 WiFi 等非定位状态实时同步
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
        when {
            command == "LOCK_SCREEN" -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            command == "GO_HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
            command == "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
            command == "SHOW_RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            command == "SHOW_NOTIFICATIONS" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            command == "WIFI_ON" -> setWifiEnabled(true)
            command == "WIFI_OFF" -> setWifiEnabled(false)
            command.startsWith("OPEN_APP:") -> {
                val pkg = command.substringAfter("OPEN_APP:")
                if (pkg.isNotBlank()) openApp(pkg)
            }
        }
    }

    private fun setWifiEnabled(enabled: Boolean) {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiManager.isWifiEnabled = enabled
        } catch (e: Exception) {
            Log.e(tag, "Failed to set wifi: $enabled", e)
        }
    }


    private fun openApp(packageName: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to open app: $packageName", e)
        }
    }

    private fun syncDeviceState(
        newPackage: String? = null,
        screenTransition: Boolean = false,
        screenOn: Boolean? = null
    ) {
        val now = System.currentTimeMillis()

        // --- 地理位置专用频率锁：5 分钟 ---
        val canRefreshLocation = now - lastLocationCheckTime >= 5 * 60 * 1000
        if (canRefreshLocation) {
            lastLocationCheckTime = now
        }

        scope.launch {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val oldState = userDeviceStateRepo.getUserDeviceState().run { firstOrNull() } ?: UserDeviceStateEntity()

            val isScreenOn = screenOn ?: powerManager.isInteractive
            val packageName = (if (isScreenOn) newPackage ?: oldState.foregroundApp else "").ifBlank { "" }

            // 1. 更新时长与应用状态 (始终保持 30s 实时)
            var appSessionStart = oldState.appSessionStartMs
            var continuousStart = oldState.continuousSessionStartMs
            if (!isScreenOn) {
                appSessionStart = 0L; continuousStart = 0L
            } else {
                if (continuousStart <= 0L || (screenTransition && screenOn == true)) continuousStart = now
                if (packageName.isNotBlank() && (packageName != oldState.foregroundApp || appSessionStart <= 0L)) appSessionStart =
                    now
            }

            val startTime = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(
                Calendar.SECOND,
                0
            ); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, now)
            val systemTotal =
                if (packageName.isNotBlank()) stats?.find { it.packageName == packageName }?.totalTimeInForeground
                    ?: 0L else 0L
            var realTodayDurationMs = systemTotal
            if (isScreenOn && packageName.isNotBlank() && packageName == oldState.foregroundApp && appSessionStart > 0) {
                realTodayDurationMs = maxOf(systemTotal, oldState.todayDurationMs, now - appSessionStart)
            }

            // 2. 更新地理位置 (只有在满足 5 分钟锁时才真正调用系统调用)
            val locationResult = if (canRefreshLocation) {
                if (ContextCompat.checkSelfPermission(
                        this@EvoliaMonitorService,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                    == android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    try {
                        val currentCell = getCurrentCellId()
                        if (currentCell != null) lastCellId = currentCell

                        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                        val loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) ?: lm.getLastKnownLocation(
                            LocationManager.PASSIVE_PROVIDER
                        )
                        if (loc != null) {
                            val name = withContext(Dispatchers.IO) {
                                try {
                                    val geocoder = Geocoder(this@EvoliaMonitorService, Locale.getDefault())

                                    @Suppress("DEPRECATION")
                                    val addresses = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
                                    addresses?.firstOrNull()?.let { it.getAddressLine(0) ?: it.locality ?: "" } ?: ""
                                } catch (e: Exception) {
                                    ""
                                }
                            }
                            Triple(loc.latitude, loc.longitude, if (name.isNotBlank()) name else oldState.locationName)
                        } else Triple(oldState.latitude, oldState.longitude, oldState.locationName)
                    } catch (e: Exception) {
                        Triple(oldState.latitude, oldState.longitude, oldState.locationName)
                    }
                } else Triple(oldState.latitude, oldState.longitude, oldState.locationName)
            } else {
                // 未到 5 分钟，复用旧位置，不触发请求
                Triple(oldState.latitude, oldState.longitude, oldState.locationName)
            }

            // 3. 更新 WiFi 状态 (始终保持 30s 实时)
            val wifiInfo = getWifiStatus()

            val currentState = oldState.copy(
                foregroundApp = packageName,
                foregroundAppName = if (packageName.isNotBlank()) getAppName(packageName) else "桌面/熄屏",
                isScreenOn = isScreenOn,
                todayDurationMs = realTodayDurationMs,
                screenContext = if (isScreenOn && shoppingApps.contains(packageName)) scanScreenContext() else "",
                appSessionStartMs = appSessionStart,
                continuousSessionStartMs = continuousStart,
                latitude = locationResult.first,
                longitude = locationResult.second,
                locationName = locationResult.third,
                wifiSsid = wifiInfo.first,
                isWifiConnected = wifiInfo.second,
                lastUpdated = now
            )

            userDeviceStateRepo.updateDeviceState(currentState)
            if (isScreenOn || currentState.locationName != oldState.locationName || currentState.wifiSsid != oldState.wifiSsid) {
                checkAllMonitors(currentState, oldState)
            }
        }
    }

    private fun getWifiStatus(): Pair<String, Boolean> {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return "" to false
            val capabilities = cm.getNetworkCapabilities(network) ?: return "" to false

            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val info = wm.connectionInfo
                val ssid = if (info != null && info.networkId != -1) {
                    info.ssid.removePrefix("\"").removeSuffix("\"")
                } else ""
                val isConnected = ssid.isNotBlank() && ssid != "<unknown ssid>"
                ssid to isConnected
            } else {
                "" to false
            }
        } catch (e: Exception) {
            "" to false
        }
    }

    private fun getCurrentCellId(): String? {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return null
        return try {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                tm.allCellInfo?.firstOrNull { it.isRegistered }?.toString()
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun recordAction(action: String) {
        scope.launch {
            val current = userDeviceStateRepo.getUserDeviceState().run { firstOrNull() } ?: UserDeviceStateEntity()
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            val existingActions = current.recentActions.lines().filter { it.isNotBlank() }.take(4)
            val newActions = ("[$time] $action" + "\n" + existingActions.joinToString("\n")).trim()
            userDeviceStateRepo.updateDeviceState(current.copy(recentActions = newActions))
        }
    }

    private suspend fun checkAllMonitors(state: UserDeviceStateEntity, oldState: UserDeviceStateEntity) {
        val tasks = monitorTaskRepo.getAllEnabledTasks().run { firstOrNull() } ?: return
        tasks.forEach { task ->
            try {
                val conditions = json.parseToJsonElement(task.conditions) as? JsonObject ?: return@forEach
                if (evaluateConditions(conditions, state, oldState, task.id)) {
                    triggerMonitor(task, state)
                }
            } catch (e: Exception) {
                Log.e(tag, "Monitor check failed: ${task.monitorName}", e)
            }
        }
    }

    private fun evaluateConditions(
        conditions: JsonObject,
        state: UserDeviceStateEntity,
        oldState: UserDeviceStateEntity,
        taskId: Long
    ): Boolean {
        val now = System.currentTimeMillis()
        val cooldownMin = ((conditions["cooldown_minutes"] as? JsonPrimitive)?.longOrNull ?: 5L).coerceAtLeast(2L)
        val lastTrigger = lastTriggerTimeMap[taskId] ?: 0L
        if (now - lastTrigger < cooldownMin * 60 * 1000) return false

        val locationNameCondition = (conditions["location_name"] as? JsonPrimitive)?.content
        if (locationNameCondition != null) {
            val isNowMatch = state.locationName.contains(locationNameCondition, ignoreCase = true)
            val wasMatch = oldState.locationName.contains(locationNameCondition, ignoreCase = true)
            if (!isNowMatch || wasMatch) return false
        }

        val wifiSsidCondition = (conditions["wifi_ssid"] as? JsonPrimitive)?.content
        if (wifiSsidCondition != null) {
            val isNowMatch = state.wifiSsid.contains(wifiSsidCondition, ignoreCase = true)
            val wasMatch = oldState.wifiSsid.contains(wifiSsidCondition, ignoreCase = true)
            if (!isNowMatch || wasMatch) return false
        }

        val wifiConnectedCondition = (conditions["is_wifi_connected"] as? JsonPrimitive)?.booleanOrNull
        if (wifiConnectedCondition != null && state.isWifiConnected != wifiConnectedCondition) return false
        val timePeriods = conditions["time_periods"] as? JsonArray
        if (timePeriods != null && timePeriods.isNotEmpty()) {
            val isAnyMatched = timePeriods.any { period ->
                val p = period as? JsonObject ?: return@any false
                val start = (p["start"] as? JsonPrimitive)?.content ?: ""
                val end = (p["end"] as? JsonPrimitive)?.content ?: ""
                isCurrentTimeInRange(start, end)
            }
            if (!isAnyMatched) return false // 如果设置了时间段但当前不在任何一个时间段内，则不触发
        }
        val timeRange = conditions["time_range"] as? JsonObject
        if (timeRange != null) {
            val start = (timeRange["start"] as? JsonPrimitive)?.content ?: ""
            val end = (timeRange["end"] as? JsonPrimitive)?.content ?: ""
            if (!isCurrentTimeInRange(start, end)) return false
        }

        val screenStatus = (conditions["screen_status"] as? JsonPrimitive)?.content
        if (screenStatus != null && state.isScreenOn != (screenStatus == "ON")) return false

        val durationThreshold = (conditions["usage_duration_minutes"] as? JsonPrimitive)?.intOrNull
        if (durationThreshold != null && (state.todayDurationMs / 60000) < durationThreshold) return false

        val appContinuousThreshold = (conditions["continuous_usage_minutes"] as? JsonPrimitive)?.intOrNull
        if (appContinuousThreshold != null && state.appSessionStartMs > 0) {
            val continuousMs = now - state.appSessionStartMs
            if (continuousMs / 60000 < appContinuousThreshold) return false
        }

        val totalContinuousThreshold = (conditions["total_continuous_minutes"] as? JsonPrimitive)?.intOrNull
        if (totalContinuousThreshold != null && state.continuousSessionStartMs > 0) {
            val continuousMs = now - state.continuousSessionStartMs
            if (continuousMs / 60000 < totalContinuousThreshold) return false
        }

        val targetApp = (conditions["foreground_app"] as? JsonPrimitive)?.content
        if (targetApp != null && !state.foregroundAppName.contains(targetApp, ignoreCase = true)) return false

        val contentContains = (conditions["content_contains"] as? JsonPrimitive)?.content
        if (contentContains != null && !state.screenContext.contains(contentContains, ignoreCase = true)) return false

        return true
    }

    private suspend fun triggerMonitor(task: AgentMonitorTaskEntity, state: UserDeviceStateEntity) {
        lastTriggerTimeMap[task.id] = System.currentTimeMillis()
        val actions = json.parseToJsonElement(task.actions) as? JsonArray ?: return
        val triggerAction = actions.filterIsInstance<JsonObject>()
            .find { (it["type"] as? JsonPrimitive)?.content == "SEND_HIDDEN_MESSAGE" } ?: return

        val template = (triggerAction["content"] as? JsonPrimitive)?.content ?: ""
        val now = System.currentTimeMillis()
        val durationMin = state.todayDurationMs / 60000
        val appContinuousMin = if (state.appSessionStartMs > 0) (now - state.appSessionStartMs) / 60000 else 0
        val totalContinuousMin =
            if (state.continuousSessionStartMs > 0) (now - state.continuousSessionStartMs) / 60000 else 0
        val currentTime = SimpleDateFormat("HH:mm", Locale.US).format(Date())

        val finalMsg = template
            .replace("{app_name}", state.foregroundAppName)
            .replace("{duration}", "$durationMin 分钟")
            .replace("{continuous_duration}", "$appContinuousMin 分钟")
            .replace("{total_continuous_duration}", "$totalContinuousMin 分钟")
            .replace("{recent_actions}", state.recentActions.ifBlank { "无近期点击" })
            .replace("{screen_context}", state.screenContext.ifBlank { "无上下文" })
            .replace("{current_time}", currentTime)
            .replace("{location}", state.locationName.ifBlank { "未知地点" })
            .replace("{wifi_ssid}", state.wifiSsid.ifBlank { "未连接" })
            .replace("{wifi_connected}", if (state.isWifiConnected) "已连接" else "未连接")

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
    } catch (e: Exception) {
        packageName
    }

    private fun isCurrentTimeInRange(start: String, end: String): Boolean {
        if (start.isBlank() || end.isBlank()) return true
        val now = Calendar.getInstance()
        val current = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        fun parse(s: String) = try {
            s.split(":").let { it[0].toInt() * 60 + it[1].toInt() }
        } catch (e: Exception) { -1 }
        val s = parse(start);
        val e = parse(end)
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
