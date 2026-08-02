package me.rerere.asr.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
sealed class ASRProviderSetting {
    abstract val id: Uuid
    abstract val name: String
    abstract val builtIn: Boolean

    abstract fun copyProvider(
        id: Uuid = this.id,
        name: String = this.name,
        builtIn: Boolean = this.builtIn
    ): ASRProviderSetting

    /**
     * 系统 ASR：基于 Android SpeechRecognizer。
     * 实时监听式识别，音频不上传网络，支持离线（取决于设备是否已下载离线语音包）。
     * 适合作为通话场景的默认 ASR，无需额外配置即可使用。
     */
    @Serializable
    @SerialName("system")
    data class SystemASR(
        override var id: Uuid = Uuid.random(),
        override var name: String = "System ASR",
        override val builtIn: Boolean = true,
        val language: String = "zh-CN",
        val enableOffline: Boolean = false
    ) : ASRProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
            builtIn: Boolean
        ): ASRProviderSetting = copy(
            id = id,
            name = name,
            builtIn = builtIn
        )
    }

    /**
     * 在线 ASR：本地 VAD 切分语音段 → 上传到云端转录 API（如 OpenAI Whisper）→ 返回文本。
     * 不依赖系统 SpeechRecognizer，兼容所有设备（含国产 ROM）。
     * 需要联网，延迟约 1-2 秒（取决于网络和 API 响应速度）。
     */
    @Serializable
    @SerialName("online")
    data class OnlineASR(
        override var id: Uuid = Uuid.random(),
        override var name: String = "Online ASR",
        override val builtIn: Boolean = true,
        val apiUrl: String = "https://api.openai.com/v1/audio/transcriptions",
        val apiKey: String = "",
        val model: String = "whisper-1",
        val language: String = "zh"
    ) : ASRProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
            builtIn: Boolean
        ): ASRProviderSetting = copy(
            id = id,
            name = name,
            builtIn = builtIn
        )
    }

    companion object {
        val Types by lazy {
            listOf(
                SystemASR::class,
                OnlineASR::class,
            )
        }
    }
}
