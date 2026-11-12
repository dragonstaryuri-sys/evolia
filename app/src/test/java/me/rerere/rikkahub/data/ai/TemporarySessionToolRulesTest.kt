package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import me.rerere.rikkahub.core.data.model.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.TemporarySessionToolRules

/**
 * 临时会话工具白名单规则单测（2026-11-12 与用户确认的禁用清单）。
 * 禁用原则：写正式数据（DB/DataStore/文件）或现实副作用（真实邮件/设备动作/通话）的工具
 * 一律不可用；沙盒计算与只读工具保留；图片生成/打印/MCP 经用户确认保留。
 */
class TemporarySessionToolRulesTest {

    /** Release 模式主助手强制注入的 coreTools（ChatService.handleMessageComplete 同款清单） */
    private val releaseCoreTools = listOf(
        LocalToolOption.ScheduleManagement,
        LocalToolOption.JavascriptEngine,
        LocalToolOption.DeviceControl,
        LocalToolOption.PythonEngine,
        LocalToolOption.AgentAutomation,
        LocalToolOption.WebPageReader,
        LocalToolOption.PeekUser,
        LocalToolOption.UpdateProfile,
        LocalToolOption.TimeSense,
        LocalToolOption.RadioStation,
    )

    @Test
    fun `release coreTools filtered for temporary session keeps only sandbox and read tools`() {
        val filtered = TemporarySessionToolRules.filterOptions(releaseCoreTools)
        // 保留：沙盒计算 + 只读
        assertTrue(filtered.contains(LocalToolOption.JavascriptEngine))
        assertTrue(filtered.contains(LocalToolOption.PythonEngine))
        assertTrue(filtered.contains(LocalToolOption.WebPageReader))
        assertTrue(filtered.contains(LocalToolOption.TimeSense))
        // 禁用：写正式数据/现实副作用
        assertFalse(filtered.contains(LocalToolOption.ScheduleManagement))
        assertFalse(filtered.contains(LocalToolOption.DeviceControl))
        assertFalse(filtered.contains(LocalToolOption.AgentAutomation))
        assertFalse(filtered.contains(LocalToolOption.PeekUser))
        assertFalse(filtered.contains(LocalToolOption.UpdateProfile))
        assertFalse(filtered.contains(LocalToolOption.RadioStation))
    }

    @Test
    fun `filterOptions removes user named write tools from full option list`() {
        val allOptions = listOf(
            LocalToolOption.CallControl,
            LocalToolOption.AgentAutomation,
            LocalToolOption.ScheduleManagement,
            LocalToolOption.UpdateProfile,
            LocalToolOption.MilestoneManagement,
            LocalToolOption.RadioStation,
            LocalToolOption.PeekUser,
            LocalToolOption.EmailService,
            LocalToolOption.DeviceControl,
            LocalToolOption.JavascriptEngine,
            LocalToolOption.PythonEngine,
            LocalToolOption.WebPageReader,
            LocalToolOption.TimeSense,
            LocalToolOption.ImageGeneration,
            LocalToolOption.PrintContent,
        )
        val filtered = TemporarySessionToolRules.filterOptions(allOptions)
        val expectedKept = setOf(
            LocalToolOption.JavascriptEngine,
            LocalToolOption.PythonEngine,
            LocalToolOption.WebPageReader,
            LocalToolOption.TimeSense,
            LocalToolOption.ImageGeneration,
            LocalToolOption.PrintContent,
        )
        assertEquals(expectedKept, filtered.toSet())
    }

    @Test
    fun `deprecated CameraCapture is blocked together with DeviceControl`() {
        // CameraCapture 运行时等同 DeviceControl（getTools 同一开关连带注册设备工具组）
        val filtered = TemporarySessionToolRules.filterOptions(
            listOf(@Suppress("DEPRECATION") LocalToolOption.CameraCapture)
        )
        assertTrue(filtered.isEmpty())
        assertTrue("camera_capture 必须在执行层黑名单", "camera_capture" in TemporarySessionToolRules.blockedToolNames)
    }

    @Test
    fun `blockedToolNames covers all tools of blocked options and memory writes`() {
        val expected = setOf(
            "call_control",
            "agent_task_manager",
            "schedule_manager",
            "update_profile",
            "milestone_manager",
            "peek_user",
            "qq_email_service",
            "device_control",
            "device_alarm_timer_manager",
            "list_apps",
            "list_app_locks",
            "camera_capture",
            "get_radio_tool_schema",
            "call_radio_tool",
            "create_memory",
            "edit_memory",
            "delete_memory",
        )
        assertEquals(expected, TemporarySessionToolRules.blockedToolNames)
    }

    @Test
    fun `kept tools are not in blocked tool names`() {
        val keptToolNames = setOf(
            "eval_javascript",
            "eval_python",
            "list_sandbox_files",
            "read_sandbox_file",
            "write_sandbox_file",
            "delete_sandbox_file",
            "import_attachment",
            "fetch_url_content",
            "search_web",
            "generate_image",
            "print_content",
            "get_mcp_tool_schema",
            "call_mcp_tool",
            "get_background_task",
            "cancel_background_task",
            "retrieve_memory",
        )
        keptToolNames.forEach { name ->
            assertFalse("保留工具 $name 不应出现在黑名单", name in TemporarySessionToolRules.blockedToolNames)
        }
    }
}
