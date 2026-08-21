package me.rerere.asr.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.asr.BuildConfig
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
     *
     * 默认 apiUrl / model 预填 SiliconFlow + TeleAI/TeleSpeechASR，用户可自行修改。
     */
    @Serializable
    @SerialName("online")
    data class OnlineASR(
        override var id: Uuid = Uuid.random(),
        override var name: String = "Online ASR",
        override val builtIn: Boolean = true,
        val apiUrl: String = "https://api.siliconflow.cn/v1/audio/transcriptions",
        val apiKey: String = "",
        val model: String = "TeleAI/TeleSpeechASR",
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

    /**
     * Evolia 内置 ASR：SiliconFlow TeleAI/TeleSpeechASR。
     *
     * 与 [OnlineASR] 的区别：
     * - apiKey 不存储在序列化数据中，运行时从 BuildConfig 读取（保密，不上传代码仓）
     * - 用户不可编辑（UI 不显示编辑按钮），仅作为一个可选项
     * - 运行时通过 [toOnlineASR] 转换为 OnlineASR 复用 OnlineASRProvider 全部逻辑
     */
    @Serializable
    @SerialName("evolia")
    data class EvoliaASR(
        override var id: Uuid = Uuid.random(),
        override var name: String = "Evolia ASR",
        override val builtIn: Boolean = true,
        val apiUrl: String = "https://api.siliconflow.cn/v1/audio/transcriptions",
        val model: String = "TeleAI/TeleSpeechASR",
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

        /**
         * 转换为 OnlineASR，apiKey 从 BuildConfig 注入。
         * 复用 OnlineASRProvider 的全部转录逻辑。
         */
        fun toOnlineASR(): OnlineASR = OnlineASR(
            id = id,
            name = name,
            builtIn = builtIn,
            apiUrl = apiUrl,
            apiKey = BuildConfig.EVOLIA_ASR_API_KEY,
            model = model,
            language = language
        )
    }

    /**
     * 本地 ASR：基于 sherpa-onnx OfflineRecognizer + SenseVoice INT8 模型。
     *
     * 完全端侧推理，无需联网，延迟约 70-110ms/10s 音频。
     * 模型文件存储在 context.filesDir/sensevoice/ 下（model.int8.onnx + tokens.txt），
     * 由 SenseVoiceModelManager 负责下载管理。
     *
     * 与 OnlineASR 的区别：
     * - 无 API URL / API Key 配置
     * - 需要 ModelManager 预先下载模型（约 228MB）
     * - 推理在本地 CPU 上进行，使用 VAD 分段 + OfflineRecognizer 一次性转录
     */
    @Serializable
    @SerialName("local_sensevoice")
    data class LocalSenseVoiceASR(
        override var id: Uuid = Uuid.random(),
        override var name: String = "Local SenseVoice",
        override val builtIn: Boolean = true,
        val language: String = "auto",
        val useItn: Boolean = true,
        val numThreads: Int = 2
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
                EvoliaASR::class,
                LocalSenseVoiceASR::class,
            )
        }
    }
}
