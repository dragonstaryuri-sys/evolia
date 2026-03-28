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
}
