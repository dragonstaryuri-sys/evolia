package me.rerere.rikkahub.service

import me.rerere.rikkahub.core.data.db.entity.UserDeviceStateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 监视器设备状态纯决策逻辑测试（不依赖 Android API）。
 *
 * 覆盖：
 * - 屏幕文字需求判定（content_contains 条件 / {screen_context} 模板变量 / 容错）
 * - 触发前重校验状态组装（前台切换、扫描失败、时长回退防护、位置/WiFi 字段来源）
 */
class MonitorDeviceStateLogicTest {

    // ==================== monitorNeedsScreenText ====================

    @Test
    fun `content_contains 非空时判定为需要屏幕文字`() {
        val conditions = """{"foreground_app":"小红书","content_contains":"限时秒杀"}"""
        val actions = """[{"type":"SEND_HIDDEN_MESSAGE","content":"用户在看 {screen_context}"}]"""
        assertTrue(MonitorDeviceStateLogic.monitorNeedsScreenText(conditions, actions))
    }

    @Test
    fun `content_contains 为空串时不算需求`() {
        val conditions = """{"content_contains":""}"""
        val actions = """[{"type":"SEND_HIDDEN_MESSAGE","content":"提醒"}]"""
        assertFalse(MonitorDeviceStateLogic.monitorNeedsScreenText(conditions, actions))
    }

    @Test
    fun `模板包含 screen_context 变量时判定为需要屏幕文字`() {
        val conditions = """{"foreground_app":"com.xingin.xhs"}"""
        val actions = """[{"type":"SEND_HIDDEN_MESSAGE","content":"屏幕内容：{screen_context}"}]"""
        assertTrue(MonitorDeviceStateLogic.monitorNeedsScreenText(conditions, actions))
    }

    @Test
    fun `无 content_contains 且模板不含 screen_context 时不需要`() {
        val conditions = """{"foreground_app":"com.xingin.xhs","time_periods":[{"start":"22:00","end":"23:00"}]}"""
        val actions = """[{"type":"SEND_HIDDEN_MESSAGE","content":"用了 {duration}"}]"""
        assertFalse(MonitorDeviceStateLogic.monitorNeedsScreenText(conditions, actions))
    }

    @Test
    fun `非 SEND_HIDDEN_MESSAGE 动作中的变量不算需求`() {
        val conditions = """{}"""
        val actions = """[{"type":"NOTIFICATION","content":"{screen_context}"}]"""
        assertFalse(MonitorDeviceStateLogic.monitorNeedsScreenText(conditions, actions))
    }

    @Test
    fun `非法 JSON 容错为无需求`() {
        assertFalse(MonitorDeviceStateLogic.monitorNeedsScreenText("not json", "[broken"))
        assertFalse(MonitorDeviceStateLogic.monitorNeedsScreenText("", ""))
    }

    @Test
    fun `任一监视器有需求即整体有需求`() {
        val need = """{"content_contains":"关键词"}""" to """[{"type":"SEND_HIDDEN_MESSAGE","content":"x"}]"""
        val noNeed = """{"screen_status":"ON"}""" to """[{"type":"SEND_HIDDEN_MESSAGE","content":"{duration}"}]"""
        assertTrue(MonitorDeviceStateLogic.anyMonitorNeedsScreenText(listOf(noNeed, need)))
        assertFalse(MonitorDeviceStateLogic.anyMonitorNeedsScreenText(listOf(noNeed, noNeed)))
        assertFalse(MonitorDeviceStateLogic.anyMonitorNeedsScreenText(emptyList()))
    }

    // ==================== buildRevalidatedState ====================

    private fun baseState() = UserDeviceStateEntity(
        foregroundApp = "com.xingin.xhs",
        foregroundAppName = "小红书",
        isScreenOn = true,
        todayDurationMs = 30 * 60_000L,
        recentActions = "[10:00:00] 点击了: 某商品",
        screenContext = "旧扫描文本",
        appSessionStartMs = 1_000L,
        continuousSessionStartMs = 900L,
        latitude = 31.23,
        longitude = 121.47,
        locationName = "上海",
        wifiSsid = "HomeWiFi",
        isWifiConnected = true,
        lastUpdated = 5_000L
    )

    @Test
    fun `前台应用未变时会话起点保留且时长不回退`() {
        val base = baseState()
        val fresh = MonitorDeviceStateLogic.buildRevalidatedState(
            baseState = base,
            freshForegroundPackage = "com.xingin.xhs",
            freshForegroundAppName = "小红书",
            freshTodayDurationMs = 29 * 60_000L, // UsageStats 抖动返回略小值
            freshScreenContext = "",
            freshWifiSsid = "HomeWiFi",
            freshWifiConnected = true,
            now = 10_000L
        )
        assertNotNull(fresh)
        assertEquals(base.appSessionStartMs, fresh!!.appSessionStartMs)
        assertEquals(base.continuousSessionStartMs, fresh.continuousSessionStartMs)
        // 时长取 max，防查询抖动导致回退
        assertEquals(base.todayDurationMs, fresh.todayDurationMs)
        assertEquals(base.locationName, fresh.locationName)
        assertEquals(base.latitude, fresh.latitude, 0.0)
        assertEquals(10_000L, fresh.lastUpdated)
    }

    @Test
    fun `前台应用已切换时会话起点重置为当前时刻`() {
        val fresh = MonitorDeviceStateLogic.buildRevalidatedState(
            baseState = baseState(),
            freshForegroundPackage = "com.tencent.mm",
            freshForegroundAppName = "微信",
            freshTodayDurationMs = 60 * 60_000L,
            freshScreenContext = "",
            freshWifiSsid = "HomeWiFi",
            freshWifiConnected = true,
            now = 100_000L
        )
        assertNotNull(fresh)
        // 连续使用条件基于 now - appSessionStartMs，重置后单应用连续时长自然归零 → 触发被取消
        assertEquals(100_000L, fresh!!.appSessionStartMs)
        assertEquals("com.tencent.mm", fresh.foregroundApp)
        assertEquals("微信", fresh.foregroundAppName)
    }

    @Test
    fun `亮屏下前台应用无法获取时取消触发`() {
        val fresh = MonitorDeviceStateLogic.buildRevalidatedState(
            baseState = baseState(),
            freshForegroundPackage = null,
            freshForegroundAppName = "",
            freshTodayDurationMs = 0L,
            freshScreenContext = "",
            freshWifiSsid = "",
            freshWifiConnected = false,
            now = 10_000L
        )
        // 关键状态无法获取必须明确失败，不得用旧状态冒充
        assertNull(fresh)
    }

    @Test
    fun `依赖屏幕文字但扫描失败时取消触发`() {
        val fresh = MonitorDeviceStateLogic.buildRevalidatedState(
            baseState = baseState(),
            freshForegroundPackage = "com.xingin.xhs",
            freshForegroundAppName = "小红书",
            freshTodayDurationMs = 30 * 60_000L,
            freshScreenContext = null, // 扫描失败
            freshWifiSsid = "HomeWiFi",
            freshWifiConnected = true,
            now = 10_000L
        )
        // 不得用旧 screenContext（"旧扫描文本"）冒充最新扫描结果
        assertNull(fresh)
    }

    @Test
    fun `屏幕文字扫描成功时使用最新文本`() {
        val fresh = MonitorDeviceStateLogic.buildRevalidatedState(
            baseState = baseState(),
            freshForegroundPackage = "com.xingin.xhs",
            freshForegroundAppName = "小红书",
            freshTodayDurationMs = 31 * 60_000L,
            freshScreenContext = "限时秒杀 | 新品首发",
            freshWifiSsid = "HomeWiFi",
            freshWifiConnected = true,
            now = 10_000L
        )
        assertNotNull(fresh)
        assertEquals("限时秒杀 | 新品首发", fresh!!.screenContext)
        assertEquals(31 * 60_000L, fresh.todayDurationMs)
    }

    @Test
    fun `WiFi 最新观测覆盖且位置字段原样保留`() {
        val fresh = MonitorDeviceStateLogic.buildRevalidatedState(
            baseState = baseState(),
            freshForegroundPackage = "com.xingin.xhs",
            freshForegroundAppName = "小红书",
            freshTodayDurationMs = 30 * 60_000L,
            freshScreenContext = "",
            freshWifiSsid = "OfficeWiFi",
            freshWifiConnected = true,
            now = 10_000L
        )
        assertNotNull(fresh)
        assertEquals("OfficeWiFi", fresh!!.wifiSsid)
        assertTrue(fresh.isWifiConnected)
        // 触发前禁止重新定位：位置沿用评估时状态
        assertEquals("上海", fresh.locationName)
        assertEquals(31.23, fresh.latitude, 0.0)
        assertEquals(121.47, fresh.longitude, 0.0)
    }

    @Test
    fun `原会话起点无效时重置为当前时刻`() {
        val base = baseState().copy(appSessionStartMs = 0L, continuousSessionStartMs = 0L)
        val fresh = MonitorDeviceStateLogic.buildRevalidatedState(
            baseState = base,
            freshForegroundPackage = "com.xingin.xhs",
            freshForegroundAppName = "小红书",
            freshTodayDurationMs = 0L,
            freshScreenContext = "",
            freshWifiSsid = "",
            freshWifiConnected = false,
            now = 77_000L
        )
        assertNotNull(fresh)
        assertEquals(77_000L, fresh!!.appSessionStartMs)
        assertEquals(77_000L, fresh.continuousSessionStartMs)
    }
}
