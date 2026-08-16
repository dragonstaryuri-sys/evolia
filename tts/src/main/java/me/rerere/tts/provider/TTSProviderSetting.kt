package me.rerere.tts.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.common.R
import kotlin.uuid.Uuid

@Serializable
sealed class TTSProviderSetting {
    abstract val id: Uuid
    abstract val name: String
    abstract val builtIn: Boolean

    abstract fun copyProvider(
        id: Uuid = this.id,
        name: String = this.name,
        builtIn: Boolean = this.builtIn
    ): TTSProviderSetting

    @Serializable
    @SerialName("openai")
    data class OpenAI(
        override var id: Uuid = Uuid.random(),
        override var name: String = "OpenAI TTS",
        override val builtIn: Boolean = false,
        val apiKey: String = "",
        val baseUrl: String = "https://api.openai.com/v1",
        val model: String = "gpt-4o-mini-tts",
        val voice: String = "alloy",
        val speed: Float = 1.0f
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
            builtIn: Boolean
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
                builtIn = builtIn
            )
        }
    }

    // 新增：小米 MiMo TTS
    @Serializable
    @SerialName("mimo")
    data class Mimo(
        override var id: Uuid = Uuid.random(),
        override var name: String = "MiMo TTS",
        override val builtIn: Boolean = false,
        val apiKey: String = "",
        val baseUrl: String = "https://api.xiaomimimo.com/v1/chat/completions",
        val model: String = "mimo-v2.5-tts",
        val voice: String = "Dean",
        val speed: Float = 1.0f,
        // mimo-v2.5-tts-voicedesign: 音色设计描述文本 (放在 user message 中)
        val voiceDesignPrompt: String = "",
        // mimo-v2.5-tts-voicedesign: 是否智能润色目标文本
        val optimizeTextPreview: Boolean = false,
        // mimo-v2.5-tts-voiceclone: 参考音频 base64 (不含 data:audio/xxx;base64, 前缀)
        val referenceAudioBase64: String = "",
        // mimo-v2.5-tts-voiceclone: 参考音频文件名 (仅显示用)
        val referenceAudioFileName: String = "",
        // mimo-v2.5-tts-voiceclone: 参考音频格式 (wav/mp3/m4a 等)
        val referenceAudioFormat: String = ""
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
            builtIn: Boolean
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
                builtIn = builtIn
            )
        }
    }

    @Serializable
    @SerialName("custom")
    data class Custom(
        override var id: Uuid = Uuid.random(),
        override var name: String = "自定义TTS(openAI兼容)",
        override val builtIn: Boolean = false,
        val apiKey: String = "",
        val baseUrl: String = "",
        val model: String = "",
        val voice: String = ""
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
            builtIn: Boolean
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
                builtIn = builtIn
            )
        }
    }

    @Serializable
    @SerialName("gemini")
    data class Gemini(
        override var id: Uuid = Uuid.random(),
        override var name: String = "Gemini TTS",
        override val builtIn: Boolean = false,
        val apiKey: String = "",
        val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
        val model: String = "gemini-2.5-flash-preview-tts",
        val voiceName: String = "Kore"
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
            builtIn: Boolean
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
                builtIn = builtIn
            )
        }
    }

    @Serializable
    @SerialName("system")
    data class SystemTTS(
        override var id: Uuid = Uuid.random(),
        override var name: String = "System TTS",
        override val builtIn: Boolean = true,
        val speechRate: Float = 1.0f,
        val pitch: Float = 1.0f,
        val voiceName: String? = null
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
            builtIn: Boolean
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
                builtIn = builtIn
            )
        }
    }

    /**
     * MiniMax 音色来源类型
     */
    @Serializable
    enum class MiniMaxVoiceType {
        /** 系统预置音色 */
        DEFAULT,
        /** 音色设计：通过文本描述生成个性化音色 */
        DESIGN,
        /** 音色复刻：基于音频样本快速复刻音色 */
        CLONE
    }

    @Serializable
    @SerialName("minimax")
    data class MiniMax(
        override var id: Uuid = Uuid.random(),
        override var name: String = "MiniMax TTS",
        override val builtIn: Boolean = false,
        val apiKey: String = "",
        val baseUrl: String = "https://api.minimaxi.com/v1",
        val groupId: String = "",
        val model: String = "speech-2.5-hd-preview",
        /** 音色来源类型：预置 / 音色设计 / 音色复刻 */
        val voiceType: MiniMaxVoiceType = MiniMaxVoiceType.DEFAULT,
        // ===== 系统预置音色 =====
        val voiceId: String = "female-shaonv",
        val emotion: String = "calm",
        val speed: Float = 1.0f,
        // ===== 音色设计 (Voice Design) =====
        /** 音色描述 prompt（音色设计必填） */
        val designPrompt: String = "",
        /** 试听文本（音色设计必填，用于生成 trial_audio） */
        val designPreviewText: String = "夜深了，古屋里只有他一人。窗外传来若有若无的脚步声。",
        /** 选中的音色设计 voice_id（可能是 API 新生成的 designedVoiceId，也可能是从已有 voice_generation 列表选的） */
        val designedVoiceId: String = "",
        /** 音色设计返回的试听音频 hex（仅预览，不持久化用于TTS） */
        val designedTrialAudioHex: String = "",
        // ===== 音色复刻 (Voice Clone) =====
        /** 用户自定义的复刻 voice_id（8-256字符，首字母英文，复刻必填） */
        val cloneVoiceId: String = "",
        /** 复刻音频上传后得到的 file_id（复刻必填） */
        val cloneFileId: Long = 0L,
        /** 复刻音频的本地文件名（仅UI展示用） */
        val cloneAudioFileName: String = "",
        /** 可选：示例音频 file_id（用于 clone_prompt.prompt_audio，增强相似度） */
        val clonePromptAudioFileId: Long = 0L,
        /** 可选：示例音频对应文本（clone_prompt.prompt_text，句末需有标点） */
        val clonePromptText: String = "",
        /** 可选：示例音频文件名（仅UI展示用） */
        val clonePromptAudioFileName: String = "",
        /** 是否开启降噪 */
        val cloneNeedNoiseReduction: Boolean = false,
        /** 是否开启音量归一化 */
        val cloneNeedVolumeNormalization: Boolean = false,
        /** 复刻试听用的模型（传text+model时返回demo_audio），如 speech-2.5-hd-preview */
        val clonePreviewModel: String = "speech-2.5-hd-preview",
        /** 复刻试听文本（可选，限制2000字内） */
        val clonePreviewText: String = "",
        /** 复刻返回的 demo_audio 试听链接（仅预览） */
        val cloneDemoAudioUrl: String = ""
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
            builtIn: Boolean
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
                builtIn = builtIn
            )
        }
    }

    @Serializable
    @SerialName("elevenlabs")
    data class ElevenLabs(
        override var id: Uuid = Uuid.random(),
        override var name: String = "ElevenLabs TTS",
        override val builtIn: Boolean = false,
        val apiKey: String = "",
        val voiceId: String = "21m00Tcm4TlvDq8ikWAM",
        val modelId: String = "eleven_multilingual_v2",
        val speed: Float = 1.0f
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
            builtIn: Boolean
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
                builtIn = builtIn
            )
        }
    }

    @Serializable
    @SerialName("azure")
    data class Azure(
        override var id: Uuid = Uuid.random(),
        override var name: String = "Azure TTS",
        override val builtIn: Boolean = false,
        val apiKey: String = "",
        val region: String = "eastus",
        val voiceName: String = "zh-CN-XiaoxiaoNeural",
        val style: String = "general",
        val speed: Float = 1.0f
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
            builtIn: Boolean
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
                builtIn = builtIn
            )
        }
    }

    companion object {
        val Types by lazy {
            listOf(
                OpenAI::class,
                Mimo::class,
                Custom::class,
                Gemini::class,
                SystemTTS::class,
                MiniMax::class,
                ElevenLabs::class,
                Azure::class,
            )
        }
    }
}
