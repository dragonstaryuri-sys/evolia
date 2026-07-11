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
        val voice: String = "alloy"
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

    @Serializable
    @SerialName("minimax")
    data class MiniMax(
        override var id: Uuid = Uuid.random(),
        override var name: String = "MiniMax TTS",
        override val builtIn: Boolean = false,
        val apiKey: String = "",
        val baseUrl: String = "https://api.minimaxi.com/v1",
        val model: String = "speech-2.5-hd-preview",
        val voiceId: String = "female-shaonv",
        val emotion: String = "calm",
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
    @SerialName("elevenlabs")
    data class ElevenLabs(
        override var id: Uuid = Uuid.random(),
        override var name: String = "ElevenLabs TTS",
        override val builtIn: Boolean = false,
        val apiKey: String = "",
        val voiceId: String = "21m00Tcm4TlvDq8ikWAM",
        val modelId: String = "eleven_multilingual_v2"
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
