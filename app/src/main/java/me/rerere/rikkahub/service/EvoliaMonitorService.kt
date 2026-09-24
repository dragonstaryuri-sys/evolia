package me.rerere.rikkahub.service

import android.accessibilityservice.AccessibilityService
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.location.Geocoder
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telephony.TelephonyManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.json.*
import me.rerere.rikkahub.core.data.db.entity.AgentMonitorTaskEntity
import me.rerere.rikkahub.core.data.db.entity.AppLockEntity
import me.rerere.rikkahub.core.data.db.entity.UserDeviceStateEntity
import me.rerere.rikkahub.core.data.repository.AgentMonitorTaskRepository
import me.rerere.rikkahub.core.data.repository.UserDeviceStateRepository
import me.rerere.rikkahub.core.data.repository.AppLockRepository

import me.rerere.rikkahub.data.ai.tools.DeviceCommandHub
import org.koin.android.ext.android.inject
import java.text.SimpleDateFormat
import java.util.*

class EvoliaMonitorService : AccessibilityService() {
    private val tag = "EvoliaMonitor"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val userDeviceStateRepo by inject<UserDeviceStateRepository>()
    private val monitorTaskRepo by inject<AgentMonitorTaskRepository>()
    private val appLockRepository by inject<AppLockRepository>()
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

    // 应用锁定遮罩：用 TYPE_ACCESSIBILITY_OVERLAY 覆盖在前台应用之上
    private var lockOverlayView: View? = null
    private var currentLockedPackage: String? = null
    // 延迟移除遮罩，避免被锁应用内部窗口切换导致闪烁
    private val overlayHandler = Handler(Looper.getMainLooper())
    private var pendingHideRunnable: Runnable? = null
    // 保护期：遮罩添加后 2 秒内忽略所有窗口事件，避免 overlay 产生的连锁假事件导致闪烁
    private var overlayProtectUntil = 0L
    // AppLock 主动轮询：作为无障碍事件延迟投递的兜底，每 2 秒通过 UsageStats 主动检测前台应用变化
    private var appLockPollingJob: Job? = null
    // 上次轮询到的前台包名，仅在与上次不同时才触发检查，避免重复查 DB
    private var lastPolledForegroundPackage: String? = null
    // 应用锁定触发通知的冷却表：packageName → 上次发送系统消息的时间戳
    // 用户在被锁应用中切来切去可能反复触发，60 秒内同一包名只发一次
    private val appLockNotifyCooldownMap = mutableMapOf<String, Long>()
    // 应用锁定触发的冷却时长（毫秒）
    private val appLockNotifyCooldownMs = 60_000L
    // 遮罩字体：柚子 UI（GWMSansUI-Regular），从 assets/fonts 加载，失败回退默认
    private val overlayTypeface: Typeface? by lazy {
        runCatching { Typeface.createFromAsset(assets, "fonts/GWMSansUI-Regular.ttf") }.getOrNull()
    }

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
        startAppLockPolling()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(screenReceiver)
        pollingJob?.cancel()
        appLockPollingJob?.cancel()
        // 服务销毁时移除遮罩和待执行的回调，避免悬浮窗泄漏
        cancelPendingHide()
        hideLockOverlay()
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

    /**
     * AppLock 主动轮询：每 2 秒通过 UsageStatsManager 查询最近进入前台的应用。
     * 无障碍事件在 MIUI/HyperOS 等系统上可能被延迟投递（例如打开小红书时事件被批量延后，
     * 切换回 Evolia 时才补投，导致遮罩不及时），主动轮询作为兜底机制确保遮罩及时显示。
     * 仅当轮询到的前台包名与上次不同时才触发检查，避免重复查数据库。
     */
    private fun startAppLockPolling() {
        appLockPollingJob?.cancel()
        appLockPollingJob = scope.launch {
            while (isActive) {
                delay(2_000)
                val fg = getForegroundPackageByUsageStats() ?: continue
                // 包名变化时才触发检查，避免重复查询
                if (fg != lastPolledForegroundPackage) {
                    lastPolledForegroundPackage = fg
                    withContext(Dispatchers.Main) {
                        checkAppLockAndShowOverlay(fg)
                    }
                }
            }
        }
    }

    /**
     * 通过 UsageStatsManager.queryEvents 获取最近 60 秒内最后一个进入前台的应用包名。
     * MOVE_TO_FOREGROUND / ACTIVITY_RESUMED 事件代表 Activity 切换到前台。
     * 此方法不依赖无障碍事件，避免被系统延迟投递影响。
     * 60 秒窗口可保证用户停留在同一应用较长时间时仍能取到最近的前台事件。
     */
    private fun getForegroundPackageByUsageStats(): String? {
        return try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return null
            val now = System.currentTimeMillis()
            val events = usageStatsManager.queryEvents(now - 60_000, now)
            var lastForegroundPackage: String? = null
            var lastForegroundTime = 0L
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val type = event.eventType
                // MOVE_TO_FOREGROUND 在 API 29+ 被 ACTIVITY_RESUMED 取代，但值相同
                if (type == UsageEvents.Event.MOVE_TO_FOREGROUND
                    || type == UsageEvents.Event.ACTIVITY_RESUMED
                ) {
                    // 取时间戳最大的（即最近一次进入前台）
                    if (event.timeStamp > lastForegroundTime) {
                        lastForegroundTime = event.timeStamp
                        lastForegroundPackage = event.packageName?.toString()
                    }
                }
            }
            // 注：原本有 15 秒时效性校验，但用户停留在同一应用超过 15 秒时会导致轮询失效，已移除
            // 60 秒窗口内的最后一个 MOVE_TO_FOREGROUND 事件即为当前前台应用，无需额外时效性校验
            lastForegroundPackage
        } catch (e: Exception) {
            Log.e(tag, "[AppLock] getForegroundPackageByUsageStats failed", e)
            null
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val packageName = event.packageName?.toString() ?: return
                syncDeviceState(newPackage = packageName)
                // 检测是否为被锁定的应用，若是则显示遮罩，否则隐藏遮罩
                checkAppLockAndShowOverlay(packageName)
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val source = event.source
                val text = source?.text?.toString() ?: source?.contentDescription?.toString() ?: ""
                if (text.isNotBlank()) recordAction("点击了: $text")
            }

            else -> {}
        }
    }

    /**
     * 检查指定包名的应用是否处于锁定状态。
     * 若锁定 → 显示全屏遮罩悬浮窗（TYPE_ACCESSIBILITY_OVERLAY）
     * 若未锁定 → 延迟移除遮罩（避免被锁应用内部窗口切换导致闪烁）
     */
    private fun checkAppLockAndShowOverlay(packageName: String) {
        Log.d(tag, "[AppLock] 事件: packageName=$packageName, overlayShown=${lockOverlayView != null}, currentLocked=$currentLockedPackage")
        // 同步轮询状态：事件路径与轮询路径共享 lastPolledForegroundPackage，避免重复检查同一应用
        lastPolledForegroundPackage = packageName
        // 保护期内忽略所有窗口事件，避免 overlay 添加后产生的连锁假事件（Evolia → 桌面）导致闪烁
        val now = System.currentTimeMillis()
        if (lockOverlayView != null && now < overlayProtectUntil) {
            Log.d(tag, "[AppLock] 保护期内忽略: $packageName")
            return
        }
        // 跳过 Evolia 自身
        if (packageName == this.packageName) {
            Log.d(tag, "[AppLock] 跳过 Evolia 自身，调度移除遮罩")
            scheduleHideOverlay()
            return
        }
        scope.launch {
            val lock = appLockRepository.getByPackageName(packageName)
            if (lock == null || !isLockedNow(lock)) {
                Log.d(tag, "[AppLock] $packageName 未锁定 (lock=${lock != null}, locked=${lock?.let { isLockedNow(it) }})，调度移除遮罩")
                withContext(Dispatchers.Main) { scheduleHideOverlay() }
                return@launch
            }
            // 该应用被锁定，取消任何待执行的移除，确保遮罩显示
            Log.d(tag, "[AppLock] $packageName 处于锁定状态，取消待移除并显示遮罩")
            withContext(Dispatchers.Main) {
                cancelPendingHide()
                showLockOverlay(lock.overlayImagePath, packageName)
            }
            // 向锁定该应用的 Assistant 发送 skipContext 系统消息，让 AI 知道用户触发了锁定
            notifyAssistantOfAppLockTrigger(lock, packageName)
        }
    }

    /**
     * 向锁定该应用的 Assistant 发送一条 skipContext=true 的 user 系统消息。
     *
     * 消息内容（由 ChatService.executeAgentTask 的 APP_LOCK_TRIGGER 分支拼接）：
     * - 有 unlock_condition：你和user约定了要等条件「X」达成后才能解锁。
     * - 仅有时间窗口：你和user约定了在 HH:mm 到 HH:mm 之间不能打开 应用名。
     *
     * 防刷屏：60 秒内同一包名只发一次。用户在被锁应用里反复切窗/桌面来回切会反复触发事件，
     * 没有冷却会刷爆 AI 上下文。
     *
     * 旧锁定记录无 assistantId（空字符串）时仅显示遮罩，不发消息。
     */
    private suspend fun notifyAssistantOfAppLockTrigger(lock: AppLockEntity, packageName: String) {
        // 旧记录无 assistantId：仅显示遮罩，不发消息
        if (lock.assistantId.isBlank()) {
            Log.d(tag, "[AppLock] 锁定记录无 assistantId，跳过发送系统消息: $packageName")
            return
        }
        // 冷却期内跳过，避免刷屏
        val now = System.currentTimeMillis()
        val lastSent = appLockNotifyCooldownMap[packageName]
        if (lastSent != null && now - lastSent < appLockNotifyCooldownMs) {
            Log.d(tag, "[AppLock] 系统消息冷却中，跳过: $packageName, 距上次 ${now - lastSent}ms")
            return
        }
        appLockNotifyCooldownMap[packageName] = now

        // 解析应用名（DB 里有就用 DB 里的，没有就现查 PackageManager）
        val appName = lock.appName ?: getAppName(packageName)
        // 构造 taskData：传 app_name / unlock_condition / start_time / end_time
        // ChatService.executeAgentTask 的 APP_LOCK_TRIGGER 分支会根据这些字段拼接消息
        val taskData = buildJsonObject {
            put("instruction", "") // APP_LOCK_TRIGGER 分支自己拼接系统提示，instruction 留空
            put("app_name", appName)
            if (!lock.unlockCondition.isNullOrBlank()) {
                put("unlock_condition", lock.unlockCondition)
            }
            if (!lock.startTime.isNullOrBlank()) put("start_time", lock.startTime)
            if (!lock.endTime.isNullOrBlank()) put("end_time", lock.endTime)
            put("trigger_package", packageName)
        }.toString()

        // 调用 executeAgentTask（suspend）——它内部用 appScope.launch 包裹实际的消息写入和 AI 回复触发，
        // 调用本身会快速返回。这里在 scope.launch 协程里直接调即可。
        try {
            chatService.executeAgentTask(
                me.rerere.rikkahub.core.data.db.entity.AgentTaskEntity(
                    assistantId = lock.assistantId,
                    taskType = "APP_LOCK_TRIGGER",
                    taskData = taskData,
                    scheduledTime = now
                )
            )
            Log.i(tag, "[AppLock] 已发送 APP_LOCK_TRIGGER 系统消息: app=$appName, pkg=$packageName, assistantId=${lock.assistantId}")
        } catch (e: Exception) {
            Log.e(tag, "[AppLock] 发送 APP_LOCK_TRIGGER 系统消息失败: ${e.message}", e)
        }
    }

    /**
     * 延迟 1000ms 移除遮罩。
     * 被锁应用内部窗口切换时可能产生非锁定 packageName 的事件，
     * 延迟移除可以在 1000ms 内收到被锁应用的新事件时取消移除，避免闪烁。
     */
    private fun scheduleHideOverlay() {
        pendingHideRunnable?.let { overlayHandler.removeCallbacks(it) }
        val runnable = Runnable {
            Log.d(tag, "[AppLock] 延迟移除到期，执行 hideLockOverlay")
            hideLockOverlay()
        }
        pendingHideRunnable = runnable
        overlayHandler.postDelayed(runnable, 1000)
    }

    private fun cancelPendingHide() {
        pendingHideRunnable?.let { overlayHandler.removeCallbacks(it) }
        pendingHideRunnable = null
    }

    /**
     * 用 WindowManager 添加全屏遮罩悬浮窗。
     * TYPE_ACCESSIBILITY_OVERLAY 是无障碍服务的特权窗口类型，
     * 无需 SYSTEM_ALERT_WINDOW 权限，可覆盖在任何应用之上。
     * 遮罩包含背景图 + 退出按钮，点击退出按钮回桌面。
     */
    private fun showLockOverlay(imagePath: String?, packageName: String) {
        // 避免重复添加
        if (lockOverlayView != null) {
            if (packageName == currentLockedPackage) {
                Log.d(tag, "[AppLock] 遮罩已显示且同一应用，跳过")
                return
            }
            hideLockOverlayInternal()
        }

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density

        // 根容器：全屏 FrameLayout，背景图填满
        // 兜底色用深墨绿 HSV(140, 0.25, 0.38)，与背景图保持一致，防止图片未加载时露出灰白色
        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.HSVToColor(floatArrayOf(140f, 0.25f, 0.38f)))
        }

        // 背景遮罩图
        val bgImageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            if (!imagePath.isNullOrBlank()) {
                val file = java.io.File(imagePath)
                if (file.exists()) {
                    setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
                }
            }
        }
        val bgParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        container.addView(bgImageView, bgParams)

        // 退出按钮：底部居中，半透明白底圆角
        val exitButton = TextView(this).apply {
            text = "退出应用"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            typeface = overlayTypeface ?: Typeface.DEFAULT
            gravity = Gravity.CENTER
            setPadding((24 * density).toInt(), (10 * density).toInt(), (24 * density).toInt(), (10 * density).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 24f * density
                setColor(0x66FFFFFF)
            }
            setOnClickListener {
                Log.d(tag as String?, "[AppLock] 用户点击退出按钮，执行 GO_HOME")
                performGlobalAction(GLOBAL_ACTION_HOME)
                hideLockOverlay()
            }
        }
        val exitParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            bottomMargin = (48 * density).toInt()
        }
        container.addView(exitButton, exitParams)

        // FLAG_NOT_TOUCH_MODAL: 触摸不会穿透到下面的应用
        // 但退出按钮可以接收点击
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.RGBA_8888
        )

        try {
            wm.addView(container, params)
            lockOverlayView = container
            currentLockedPackage = packageName
            // 设置 2 秒保护期，屏蔽 overlay 添加后产生的连锁假事件
            overlayProtectUntil = System.currentTimeMillis() + 2000
            Log.d(tag, "[AppLock] 遮罩添加成功，packageName=$packageName，保护期 2 秒")
        } catch (e: Exception) {
            Log.e(tag, "[AppLock] 添加遮罩悬浮窗失败", e)
        }
    }

    private fun hideLockOverlay() {
        if (lockOverlayView == null) return
        Log.d(tag, "[AppLock] hideLockOverlay: 移除遮罩，currentLocked=$currentLockedPackage")
        hideLockOverlayInternal()
    }

    private fun hideLockOverlayInternal() {
        val view = lockOverlayView ?: return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            wm.removeView(view)
        } catch (e: Exception) {
            Log.e(tag, "[AppLock] 移除遮罩悬浮窗失败", e)
        }
        lockOverlayView = null
        currentLockedPackage = null
    }

    /**
     * 判断锁定记录当前是否生效。
     * - 仅有解锁条件（无时间窗口）：始终生效，直到 AI 调用 APP_UNLOCK
     * - 仅有时间窗口：当前时间在窗口内才生效（支持跨夜）
     * - 两者都有：时间窗口内生效
     */
    private fun isLockedNow(lock: AppLockEntity): Boolean {
        val hasTimeWindow = !lock.startTime.isNullOrBlank() && !lock.endTime.isNullOrBlank()
        if (!hasTimeWindow) {
            // 仅有解锁条件，始终锁定
            return true
        }
        val start = lock.startTime!!
        val end = lock.endTime!!
        val now = Calendar.getInstance()
        val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val startMinutes = parseTimeToMinutes(start)
        val endMinutes = parseTimeToMinutes(end)
        return if (startMinutes <= endMinutes) {
            // 同一天窗口
            nowMinutes in startMinutes..endMinutes
        } else {
            // 跨夜窗口
            nowMinutes >= startMinutes || nowMinutes <= endMinutes
        }
    }

    private fun parseTimeToMinutes(time: String): Int {
        val parts = time.split(":")
        if (parts.size != 2) return 0
        val hour = parts[0].toIntOrNull() ?: 0
        val minute = parts[1].toIntOrNull() ?: 0
        return hour * 60 + minute
    }

    override fun onInterrupt() {}

    private fun handleDeviceCommand(command: String) {
        when {
            command == "LOCK_SCREEN" -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            command == "GO_HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
            command == "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
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
        // 支持 time_periods：数组（多时段）或单个对象（单时段）
        val timePeriodsElement = conditions["time_periods"]
        val periodsList: List<JsonObject> = when {
            timePeriodsElement is JsonArray -> timePeriodsElement.mapNotNull { it as? JsonObject }
            timePeriodsElement is JsonObject -> listOf(timePeriodsElement)
            else -> emptyList()
        }
        if (periodsList.isNotEmpty()) {
            val isAnyMatched = periodsList.any { p ->
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

        // 兜底：兼容 AI 可能直接把 start/end 放在 conditions 顶层的格式
        // 例如 {"start":"22:35","end":"23:30","screen_status":"ON",...}
        val flatStart = (conditions["start"] as? JsonPrimitive)?.content
        val flatEnd = (conditions["end"] as? JsonPrimitive)?.content
        if (!flatStart.isNullOrBlank() || !flatEnd.isNullOrBlank()) {
            if (!isCurrentTimeInRange(flatStart ?: "", flatEnd ?: "")) return false
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
        if (targetApp != null) {
            // 同时支持包名(com.xingin.xhs)与应用名称(小红书)匹配
            val isAppNameMatch = state.foregroundAppName.contains(targetApp, ignoreCase = true)
            val isPackageNameMatch = state.foregroundApp.contains(targetApp, ignoreCase = true)
            if (!isAppNameMatch && !isPackageNameMatch) return false
        }

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
            // 新命名（与 data_requirements 字段一一对应）
            .replace("{foreground_app}", state.foregroundAppName)
            .replace("{screen_status}", if (state.isScreenOn) "亮屏" else "熄屏")
            .replace("{duration}", "$durationMin 分钟")
            .replace("{app_session_duration}", "$appContinuousMin 分钟")
            .replace("{total_continuous_duration}", "$totalContinuousMin 分钟")
            .replace("{recent_actions}", state.recentActions.ifBlank { "无近期点击" })
            .replace("{screen_context}", state.screenContext.ifBlank { "无上下文" })
            .replace("{current_time}", currentTime)
            .replace("{location}", state.locationName.ifBlank { "未知地点" })
            // 旧命名兼容（逐步淘汰，仅为已保存模板兜底）
            .replace("{app_name}", state.foregroundAppName)
            .replace("{continuous_duration}", "$appContinuousMin 分钟")
            .replace("{today_usage_duration}", "$durationMin 分钟")
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
        val maxDepth = 60
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(rootNode to 0)
        while (stack.isNotEmpty()) {
            val (node, depth) = stack.removeLast()
            if (!node.isVisibleToUser) continue
            val text = node.text?.toString() ?: node.contentDescription?.toString()
            if (!text.isNullOrBlank() && text.length > 1) texts.add(text.trim())
            if (depth >= maxDepth) continue
            val childCount = node.childCount
            for (i in childCount - 1 downTo 0) {
                val child = node.getChild(i) ?: continue
                stack.addLast(child to depth + 1)
            }
        }
        return texts.distinct().take(30).joinToString(" | ")
    }
}
