package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.core.data.model.LocalToolOption

/**
 * 临时会话工具白名单（纯决策，无 Android 依赖，有单测固化）。
 *
 * 临时会话记忆只读、退出即销毁，任何会写入正式数据（DB/DataStore/文件）或产生
 * 现实副作用（真实邮件/设备动作/通话/打印机）的工具都必须禁用（2026-11-12 与用户
 * 确认的清单）。两层拦截：
 * 1. 装配层：[filterOptions] 过滤 LocalToolOption，工具根本不注册给模型；
 * 2. 执行层：[blockedToolNames] 在 GenerationHandler 工具循环兜底，防模型幻觉调用已下线工具。
 */
object TemporarySessionToolRules {

    /**
     * 临时会话禁用的工具选项：
     * - CallControl：拨号即写 call_records 正式表 + 铃声/录音文件
     * - AgentAutomation：AgentTaskEntity 落库 + WorkManager 定时唤起（未来副作用）
     * - ScheduleManagement：schedule_events 落库并联动创建提醒任务
     * - UpdateProfile：settingsStore.update 持久化用户/助手档案
     * - MilestoneManagement：MilestoneRepository 落库（关系档案，资料维护同族）
     * - RadioStation：radio_* 表 + 音频文件 + 后台任务
     * - PeekUser：add/pause 写 agent_monitor_tasks 正式表（未来自动发隐藏消息）
     * - EmailService：SMTP 发真实邮件（不可撤回的现实副作用）
     * - DeviceControl：真实设备动作（锁屏/回桌面/开 App）+ 静默设系统闹钟/定时器
     * - CameraCapture：已废弃，运行时等同于 DeviceControl（getTools 中同一开关会连带
     *   注册设备控制工具组，必须与 DeviceControl 同禁）
     */
    val blockedOptions: Set<LocalToolOption> = setOf(
        LocalToolOption.CallControl,
        LocalToolOption.AgentAutomation,
        LocalToolOption.ScheduleManagement,
        LocalToolOption.UpdateProfile,
        LocalToolOption.MilestoneManagement,
        LocalToolOption.RadioStation,
        LocalToolOption.PeekUser,
        LocalToolOption.EmailService,
        LocalToolOption.DeviceControl,
        @Suppress("DEPRECATION") LocalToolOption.CameraCapture,
    )

    /**
     * 执行层兜底黑名单（工具名）：被 [blockedOptions] 过滤后模型上下文中不存在这些工具，
     * 但模型仍可能幻觉调用——统一在此返回明确错误 ToolResult。
     * 包含被禁选项注册的全部工具名 + 记忆写入类（create/edit/delete_memory）。
     */
    val blockedToolNames: Set<String> = setOf(
        // CallControl
        "call_control",
        // AgentAutomation
        "agent_task_manager",
        // ScheduleManagement
        "schedule_manager",
        // UpdateProfile
        "update_profile",
        // MilestoneManagement
        "milestone_manager",
        // PeekUser
        "peek_user",
        // EmailService
        "qq_email_service",
        // DeviceControl（含同选项下的只读辅助与闹钟/定时器）
        "device_control",
        "device_alarm_timer_manager",
        "list_apps",
        "list_app_locks",
        // CameraCapture（运行时等同 DeviceControl）
        "camera_capture",
        // RadioStation（按需 Schema 固定双工具）
        "get_radio_tool_schema",
        "call_radio_tool",
        // Extensions（按需 Schema 固定双工具）：扩展工具可能修改正式 Mini App
        // 数据 / 扩展配置 / 外部业务数据，第一阶段默认禁止临时会话调用（详设 10.5）
        "get_extension_tool_schema",
        "call_extension_tool",
        // 记忆写入（记忆只读原则）
        "create_memory",
        "edit_memory",
        "delete_memory",
    )

    /** 装配层过滤：移除临时会话禁用的工具选项（保留沙盒/只读/用户确认保留的能力） */
    fun filterOptions(options: List<LocalToolOption>): List<LocalToolOption> =
        options.filterNot { it in blockedOptions }
}
