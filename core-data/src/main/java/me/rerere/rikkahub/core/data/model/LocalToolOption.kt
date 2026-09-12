package me.rerere.rikkahub.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class LocalToolOption {
    @Serializable
    @SerialName("javascript_engine")
    data object JavascriptEngine : LocalToolOption()

    @Serializable
    @SerialName("device_control")
    data object DeviceControl : LocalToolOption()

    @Serializable
    @SerialName("python_engine")
    data object PythonEngine : LocalToolOption()

    @Serializable
    @SerialName("schedule_management")
    data object ScheduleManagement : LocalToolOption()

    @Serializable
    @SerialName("email_service")
    data object EmailService : LocalToolOption()

    @Serializable
    @SerialName("agent_automation")
    data object AgentAutomation : LocalToolOption()

    @Serializable
    @SerialName("time_sense")
    data object TimeSense : LocalToolOption()

    @Serializable
    @SerialName("update_profile")
    data object UpdateProfile : LocalToolOption()

    @Serializable
    @SerialName("milestone_management")
    data object MilestoneManagement : LocalToolOption()

    @Serializable
    @SerialName("peek_user")
    data object PeekUser : LocalToolOption()

    @Serializable
    @SerialName("web_page_reader")
    data object WebPageReader : LocalToolOption()

    @Serializable
    @SerialName("image_generation")
    data object ImageGeneration : LocalToolOption()

    @Serializable
    @SerialName("call_control")
    data object CallControl : LocalToolOption()

    /**
     * 已废弃：摄像头拍照已合并到 [DeviceControl] 开关下。
     * 保留此枚举仅为兼容旧数据库反序列化；运行时等同于 [DeviceControl]。
     * 新代码不应再使用此枚举。
     */
    @Deprecated("已合并到 DeviceControl，保留仅为旧数据兼容", ReplaceWith("DeviceControl"))
    @Serializable
    @SerialName("camera_capture")
    data object CameraCapture : LocalToolOption()
}
