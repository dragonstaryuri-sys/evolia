package me.rerere.rikkahub.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.*
import me.rerere.rikkahub.core.data.db.entity.UserDeviceStateEntity

/**
 * 监视器设备状态纯决策逻辑（不依赖 Android API，可 JVM 单测）。
 *
 * 与 [EvoliaMonitorService] 的分工：
 * - 本文件只做「输入 → 决策」的纯计算（JSON 解析、状态组装、取消判定）；
 * - Service 负责系统观测（PowerManager / AccessibilityService / UsageStats / WiFi）与数据库读写。
 */
object MonitorDeviceStateLogic {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 判断单个监视器是否依赖屏幕文字。满足任一即为依赖：
     * - conditions 中存在非空 `content_contains`；
     * - SEND_HIDDEN_MESSAGE 的模板中包含 `{screen_context}`。
     *
     * JSON 解析失败按「无依赖」处理（不触发扫描，宁可不扫也不误扫）。
     */
    fun monitorNeedsScreenText(conditionsJson: String, actionsJson: String): Boolean {
        val conditions = runCatching { json.parseToJsonElement(conditionsJson) }.getOrNull() as? JsonObject
        if (conditions != null) {
            val contentContains = (conditions["content_contains"] as? JsonPrimitive)?.content
            if (!contentContains.isNullOrBlank()) return true
        }
        val actions = runCatching { json.parseToJsonElement(actionsJson) }.getOrNull() as? JsonArray
        if (actions != null) {
            val templateNeeds = actions.filterIsInstance<JsonObject>().any { action ->
                val type = (action["type"] as? JsonPrimitive)?.content
                val template = (action["content"] as? JsonPrimitive)?.content ?: ""
                type == "SEND_HIDDEN_MESSAGE" && template.contains("{screen_context}")
            }
            if (templateNeeds) return true
        }
        return false
    }

    /**
     * 判断一组启用中的监视器里是否存在任何屏幕文字依赖。
     * [tasks] 为 (conditionsJson, actionsJson) 对。
     */
    fun anyMonitorNeedsScreenText(tasks: List<Pair<String, String>>): Boolean =
        tasks.any { (conditions, actions) -> monitorNeedsScreenText(conditions, actions) }

    /**
     * 触发前重校验：基于「亮屏 + 最新观测」组装最终确认状态。
     *
     * 字段来源策略：
     * - 观测刷新：前台应用、屏幕状态、今日时长、屏幕文字、WiFi；
     * - 沿用评估时状态：位置（触发前禁止重新定位）、recent_actions；
     * - 会话起点换算：前台应用已切换 → 新会话从当前时刻开始（连续使用条件自然失败）；
     *   应用未变且原起点有效 → 保留原起点；
     * - 时长取 max(最新查询, 评估时值)，防 UsageStats 查询抖动导致回退。
     *
     * 返回 null 表示关键状态无法获取（前台拿不到 / 屏幕扫描失败），调用方必须取消触发，
     * 不得用旧状态冒充最新状态。
     *
     * 注意：本函数只处理亮屏路径；熄屏路径由 Service 直接沿用评估时状态重新评估（保留既有语义）。
     */
    fun buildRevalidatedState(
        baseState: UserDeviceStateEntity,
        freshForegroundPackage: String?,
        freshForegroundAppName: String,
        freshTodayDurationMs: Long,
        freshScreenContext: String?,
        freshWifiSsid: String,
        freshWifiConnected: Boolean,
        now: Long
    ): UserDeviceStateEntity? {
        // 亮屏下前台应用无法获取 → 明确失败，取消触发
        if (freshForegroundPackage == null) return null
        val appUnchanged = freshForegroundPackage == baseState.foregroundApp
        val appSessionStartMs = when {
            appUnchanged && baseState.appSessionStartMs > 0 -> baseState.appSessionStartMs
            else -> now
        }
        val continuousSessionStartMs =
            if (baseState.continuousSessionStartMs > 0) baseState.continuousSessionStartMs else now
        // 屏幕文字依赖方传入 null 表示扫描失败 → 取消；无依赖方传入 "" → 正常
        val screenContext = freshScreenContext ?: return null
        return baseState.copy(
            foregroundApp = freshForegroundPackage,
            foregroundAppName = freshForegroundAppName,
            isScreenOn = true,
            todayDurationMs = maxOf(freshTodayDurationMs, baseState.todayDurationMs),
            screenContext = screenContext,
            appSessionStartMs = appSessionStartMs,
            continuousSessionStartMs = continuousSessionStartMs,
            // 位置字段禁止在触发前重查，原样保留
            latitude = baseState.latitude,
            longitude = baseState.longitude,
            locationName = baseState.locationName,
            wifiSsid = freshWifiSsid,
            isWifiConnected = freshWifiConnected,
            lastUpdated = now
        )
    }
}
