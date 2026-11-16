package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.common.JsonInstant
import me.rerere.rikkahub.core.data.model.Assistant
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.tts.provider.TTSProviderSetting
import kotlin.uuid.Uuid

/**
 * 统一 TTS 语音选择结果。
 *
 * @property providerSetting 可直接用于 TTSManager.generateSpeech 的 Provider 配置
 *   （已按 Agent 声音副本合并）；null 表示无可用 Provider。
 * @property fallbackNotice 发生回退时的用户提示文案；正常命中为 null。
 */
data class TtsVoiceResolution(
    val providerSetting: TTSProviderSetting?,
    val fallbackNotice: String?
) {
    val isFallback: Boolean get() = fallbackNotice != null
}

/**
 * 统一语音选择（所有 TTS 调用点都必须经此解析，见 AGENTS.md 12.5）：
 *
 * 1. Agent 专属语音：[Assistant.voiceProviderId] 引用全局语音服务中的 Provider，
 *    [Assistant.voiceSettingJson] 是该 Provider 的声音配置副本（音色/语速/模型可独立调整）；
 *    合并时 **apiKey/baseUrl/region/groupId 等连接类字段始终取全局最新值**
 *    （全局改 key，Agent 自动生效），声音字段取副本；
 * 2. Agent 引用的 Provider 已被删除 / 副本类型不匹配 → 回退全局默认并提示；
 * 3. 未配置专属语音 → 直接使用设置页选中的 Provider/音色（selectedTTSProvider）。
 *
 * 音色库机制已移除：可用音色由「助手详情 → 语音配置」的设置声音表单直接编辑，无需登记。
 */
object TtsVoiceResolver {

    fun resolveForAssistant(settings: Settings, assistant: Assistant?): TtsVoiceResolution {
        return resolve(
            settings = settings,
            assistantProviderId = assistant?.voiceProviderId,
            assistantVoiceSettings = assistant?.voiceSettings
        )
    }

    fun resolve(
        settings: Settings,
        assistantProviderId: Uuid?,
        assistantVoiceSettings: Map<String, String>?
    ): TtsVoiceResolution {
        val globalDefault = settings.getSelectedTTSProvider()
        if (assistantProviderId == null) {
            return TtsVoiceResolution(providerSetting = globalDefault, fallbackNotice = null)
        }
        val referenced = settings.ttsProviders.find { it.id == assistantProviderId }
        if (referenced == null) {
            return TtsVoiceResolution(
                providerSetting = globalDefault,
                fallbackNotice = "助手配置的语音 Provider 已被删除，已回退全局默认音色"
            )
        }
        val override = decodeVoiceSetting(assistantVoiceSettings?.get(assistantProviderId.toString()))
        if (override == null || override::class != referenced::class) {
            // 该 Provider 尚未生成过声音副本或类型不匹配 → 直接用全局原样
            return TtsVoiceResolution(providerSetting = referenced, fallbackNotice = null)
        }
        return TtsVoiceResolution(
            providerSetting = mergeVoiceOverride(referenced, override),
            fallbackNotice = null
        )
    }

    /** 声音副本 JSON 反序列化（失败返回 null）。 */
    fun decodeVoiceSetting(json: String?): TTSProviderSetting? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            JsonInstant.decodeFromString<TTSProviderSetting>(json)
        }.getOrNull()
    }
}

/**
 * 把 Agent 的声音配置副本合并到全局最新 Provider 配置上：
 * 连接类字段（apiKey/baseUrl/region/groupId 等鉴权与网络信息）取 [global]，
 * 声音类字段（voice/voiceName/voiceId/speed/model/style/pitch/emotion 等）取 [override]。
 *
 * 两边必须是同一 Provider 子类（调用方保证），否则返回 null。
 */
fun mergeVoiceOverride(global: TTSProviderSetting, override: TTSProviderSetting): TTSProviderSetting? {
    if (global::class != override::class) return null
    return when (global) {
        is TTSProviderSetting.OpenAI -> {
            override as TTSProviderSetting.OpenAI
            global.copy(
                voice = override.voice,
                speed = override.speed,
                model = override.model,
                name = override.name.ifBlank { global.name }
            )
        }

        is TTSProviderSetting.Custom -> {
            override as TTSProviderSetting.Custom
            global.copy(
                voice = override.voice,
                model = override.model,
                name = override.name.ifBlank { global.name }
            )
        }

        is TTSProviderSetting.Mimo -> {
            override as TTSProviderSetting.Mimo
            global.copy(
                voice = override.voice,
                speed = override.speed,
                model = override.model,
                voiceDesignPrompt = override.voiceDesignPrompt,
                optimizeTextPreview = override.optimizeTextPreview,
                name = override.name.ifBlank { global.name }
            )
        }

        is TTSProviderSetting.Gemini -> {
            override as TTSProviderSetting.Gemini
            global.copy(
                voiceName = override.voiceName,
                model = override.model,
                name = override.name.ifBlank { global.name }
            )
        }

        is TTSProviderSetting.SystemTTS -> {
            override as TTSProviderSetting.SystemTTS
            global.copy(
                voiceName = override.voiceName,
                speechRate = override.speechRate,
                pitch = override.pitch,
                name = override.name.ifBlank { global.name }
            )
        }

        is TTSProviderSetting.MiniMax -> {
            override as TTSProviderSetting.MiniMax
            global.copy(
                voiceType = override.voiceType,
                voiceId = override.voiceId,
                emotion = override.emotion,
                speed = override.speed,
                model = override.model,
                designPrompt = override.designPrompt,
                designedVoiceId = override.designedVoiceId,
                cloneVoiceId = override.cloneVoiceId,
                cloneFileId = override.cloneFileId,
                clonePromptAudioFileId = override.clonePromptAudioFileId,
                clonePromptText = override.clonePromptText,
                cloneNeedNoiseReduction = override.cloneNeedNoiseReduction,
                cloneNeedVolumeNormalization = override.cloneNeedVolumeNormalization,
                name = override.name.ifBlank { global.name }
            )
        }

        is TTSProviderSetting.ElevenLabs -> {
            override as TTSProviderSetting.ElevenLabs
            global.copy(
                voiceId = override.voiceId,
                modelId = override.modelId,
                speed = override.speed,
                name = override.name.ifBlank { global.name }
            )
        }

        is TTSProviderSetting.Azure -> {
            override as TTSProviderSetting.Azure
            global.copy(
                voiceName = override.voiceName,
                style = override.style,
                speed = override.speed,
                name = override.name.ifBlank { global.name }
            )
        }

        is TTSProviderSetting.FishAudio -> {
            override as TTSProviderSetting.FishAudio
            global.copy(
                voiceId = override.voiceId,
                model = override.model,
                speed = override.speed,
                name = override.name.ifBlank { global.name }
            )
        }
    }
}

/**
 * Mimo 当前模型实际生效的音色标识（纯函数，单测固化；UI 摘要与电台资产记录共用）：
 * - voicedesign：没有音色 ID 概念（音色由「设计描述」定义，合成请求也不发送 voice 字段），
 *   `voice` 字段此时只是标准模型的残留值，必须返回设计描述；
 * - voiceclone：以参考音频文件名为标识；
 * - 标准 TTS：返回预置音色名。
 */
fun mimoEffectiveVoiceId(setting: TTSProviderSetting.Mimo): String = when {
    setting.model.contains("voicedesign", ignoreCase = true) ->
        setting.voiceDesignPrompt.trim().ifBlank { "(未填写音色描述)" }

    setting.model.contains("voiceclone", ignoreCase = true) ->
        setting.referenceAudioFileName.ifBlank { "(未上传参考音频)" }

    else -> setting.voice
}

/**
 * 生成 Agent 声音配置副本时清空连接类字段（apiKey/baseUrl/region/groupId 等）：
 * 副本仅用于持久化声音参数，连接信息始终运行时取全局最新值，不落副本存储。
 */
fun stripConnectionFields(setting: TTSProviderSetting): TTSProviderSetting = when (setting) {
    is TTSProviderSetting.OpenAI -> setting.copy(apiKey = "")
    is TTSProviderSetting.Custom -> setting.copy(apiKey = "")
    is TTSProviderSetting.Mimo -> setting.copy(apiKey = "")
    is TTSProviderSetting.Gemini -> setting.copy(apiKey = "")
    is TTSProviderSetting.MiniMax -> setting.copy(apiKey = "", groupId = "")
    is TTSProviderSetting.ElevenLabs -> setting.copy(apiKey = "")
    is TTSProviderSetting.Azure -> setting.copy(apiKey = "")
    is TTSProviderSetting.FishAudio -> setting.copy(apiKey = "")
    is TTSProviderSetting.SystemTTS -> setting
}
