package me.rerere.rikkahub

import me.rerere.rikkahub.data.ai.TtsVoiceResolver
import me.rerere.rikkahub.data.ai.mergeVoiceOverride
import me.rerere.rikkahub.data.ai.mimoEffectiveVoiceId
import me.rerere.rikkahub.data.ai.stripConnectionFields
import me.rerere.rikkahub.common.JsonInstant
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.datastore.ensureMediaBackupItems
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 统一语音选择逻辑（AGENTS.md 12.5）：
 * Agent 专属（providerId + 声音配置副本，连接字段取全局）→ 全局默认（设置页选中）。
 */
class TtsVoiceResolverTest {

    private val openAiId = Uuid.random()
    private val mimoId = Uuid.random()

    private val openAi = TTSProviderSetting.OpenAI(
        id = openAiId, name = "OpenAI",
        apiKey = "sk-global", baseUrl = "https://api.openai.com/v1",
        model = "gpt-4o-mini-tts", voice = "alloy", speed = 1.0f
    )
    private val mimo = TTSProviderSetting.Mimo(id = mimoId, name = "MiMo", voice = "Dean")

    private fun settingsOf(
        providers: List<TTSProviderSetting> = listOf(openAi, mimo),
        selectedId: Uuid = openAiId
    ): me.rerere.rikkahub.data.datastore.Settings {
        return me.rerere.rikkahub.data.datastore.Settings(
            ttsProviders = providers,
            selectedTTSProviderId = selectedId
        )
    }

    private fun encode(setting: TTSProviderSetting): String = JsonInstant.encodeToString(setting)

    // ==================== 未配置 → 全局默认 ====================

    @Test
    fun `no agent config uses selected provider as-is`() {
        val result = TtsVoiceResolver.resolve(settingsOf(), null, null)
        assertNull(result.fallbackNotice)
        assertEquals("alloy", (result.providerSetting as TTSProviderSetting.OpenAI).voice)
        assertEquals("sk-global", (result.providerSetting as TTSProviderSetting.OpenAI).apiKey)
    }

    // ==================== Agent 专属：声音字段取副本 ====================

    @Test
    fun `agent override keeps sound fields from copy`() {
        val copy = openAi.copy(voice = "nova", speed = 1.5f, model = "gpt-4o-mini-tts-2025")
        val result = TtsVoiceResolver.resolve(settingsOf(), openAiId, mapOf(openAiId.toString() to encode(copy)))
        assertNull(result.fallbackNotice)
        val provider = result.providerSetting as TTSProviderSetting.OpenAI
        assertEquals("nova", provider.voice)
        assertEquals(1.5f, provider.speed, 0.001f)
        assertEquals("gpt-4o-mini-tts-2025", provider.model)
    }

    @Test
    fun `agent override takes connection fields from global`() {
        // 全局后来换了 key / baseUrl → Agent 副本的旧 key 不生效
        val updatedGlobal = openAi.copy(apiKey = "sk-new", baseUrl = "https://proxy.example.com/v1")
        val copy = openAi.copy(voice = "nova", apiKey = "sk-stale", baseUrl = "https://stale.example.com")
        val settings = settingsOf(providers = listOf(updatedGlobal, mimo))
        val result = TtsVoiceResolver.resolve(settings, openAiId, mapOf(openAiId.toString() to encode(copy)))
        val provider = result.providerSetting as TTSProviderSetting.OpenAI
        assertEquals("sk-new", provider.apiKey)
        assertEquals("https://proxy.example.com/v1", provider.baseUrl)
        assertEquals("nova", provider.voice)
    }

    // ==================== 回退 ====================

    @Test
    fun `deleted referenced provider falls back with notice`() {
        val copy = openAi.copy(voice = "nova")
        val result = TtsVoiceResolver.resolve(settingsOf(), Uuid.random(), mapOf(Uuid.random().toString() to encode(copy)))
        assertNotNull(result.fallbackNotice)
        assertEquals(openAiId, result.providerSetting?.id)
    }

    @Test
    fun `type mismatch falls back to global provider as-is`() {
        // 副本是 Mimo，引用的是 OpenAI → 类型不匹配，直接用全局原样
        val result = TtsVoiceResolver.resolve(settingsOf(), openAiId, mapOf(openAiId.toString() to encode(mimo)))
        assertNull(result.fallbackNotice)
        assertEquals("alloy", (result.providerSetting as TTSProviderSetting.OpenAI).voice)
    }

    @Test
    fun `blank json treated as no override`() {
        val result = TtsVoiceResolver.resolve(settingsOf(), openAiId, emptyMap())
        assertNull(result.fallbackNotice)
        assertEquals("alloy", (result.providerSetting as TTSProviderSetting.OpenAI).voice)
    }

    @Test
    fun `no provider at all returns null safely`() {
        val result = TtsVoiceResolver.resolve(
            settingsOf(providers = emptyList()), null, null
        )
        assertNull(result.providerSetting)
    }

    // ==================== mergeVoiceOverride 字段映射 ====================

    @Test
    fun `merge maps each provider subclass`() {
        // Gemini
        val gemini = TTSProviderSetting.Gemini(id = Uuid.random(), apiKey = "k2", voiceName = "Kore")
        val geminiCopy = gemini.copy(voiceName = "Puck")
        assertEquals("Puck", (mergeVoiceOverride(gemini, geminiCopy) as TTSProviderSetting.Gemini).voiceName)
        assertEquals("k2", (mergeVoiceOverride(gemini, geminiCopy) as TTSProviderSetting.Gemini).apiKey)

        // Azure：region 取全局
        val azure = TTSProviderSetting.Azure(id = Uuid.random(), apiKey = "az", region = "eastus")
        val azureCopy = azure.copy(voiceName = "zh-CN-YunxiNeural", region = "westeu")
        val mergedAzure = mergeVoiceOverride(azure, azureCopy) as TTSProviderSetting.Azure
        assertEquals("zh-CN-YunxiNeural", mergedAzure.voiceName)
        assertEquals("eastus", mergedAzure.region)

        // MiniMax：groupId 取全局，voiceType/voiceId 取副本
        val minimax = TTSProviderSetting.MiniMax(id = Uuid.random(), apiKey = "mm", groupId = "g1")
        val minimaxCopy = minimax.copy(voiceId = "female-shaonv", groupId = "stale")
        val mergedMiniMax = mergeVoiceOverride(minimax, minimaxCopy) as TTSProviderSetting.MiniMax
        assertEquals("female-shaonv", mergedMiniMax.voiceId)
        assertEquals("g1", mergedMiniMax.groupId)

        // SystemTTS
        val system = TTSProviderSetting.SystemTTS(id = Uuid.random())
        val systemCopy = system.copy(voiceName = "cmn-CN-YunxiNeural", speechRate = 1.2f)
        assertEquals(1.2f, (mergeVoiceOverride(system, systemCopy) as TTSProviderSetting.SystemTTS).speechRate, 0.001f)

        // ElevenLabs
        val eleven = TTSProviderSetting.ElevenLabs(id = Uuid.random(), apiKey = "el")
        val elevenCopy = eleven.copy(voiceId = "v2", modelId = "eleven_turbo_v2")
        assertEquals("v2", (mergeVoiceOverride(eleven, elevenCopy) as TTSProviderSetting.ElevenLabs).voiceId)
        assertEquals("el", (mergeVoiceOverride(eleven, elevenCopy) as TTSProviderSetting.ElevenLabs).apiKey)

        // FishAudio：apiKey 取全局，voiceId/model/speed 取副本
        val fish = TTSProviderSetting.FishAudio(
            id = Uuid.random(),
            apiKey = "fish-key",
            model = "s2.1-pro",
            voiceId = "global-voice"
        )
        val fishCopy = fish.copy(
            apiKey = "",
            model = "s2.1-pro-free",
            voiceId = "agent-voice",
            speed = 1.3f
        )
        val mergedFish = mergeVoiceOverride(fish, fishCopy) as TTSProviderSetting.FishAudio
        assertEquals("fish-key", mergedFish.apiKey)
        assertEquals("agent-voice", mergedFish.voiceId)
        assertEquals("s2.1-pro-free", mergedFish.model)
        assertEquals(1.3f, mergedFish.speed, 0.001f)
    }

    @Test
    fun `merge rejects cross type`() {
        assertNull(mergeVoiceOverride(openAi, mimo))
    }

    // ==================== Fish Audio ====================

    @Test
    fun `fish audio setting round trips and agent copy strips api key`() {
        val setting = TTSProviderSetting.FishAudio(
            id = Uuid.random(),
            apiKey = "fish-key",
            model = "s2.1-pro-free",
            voiceId = "voice-model-id",
            speed = 1.2f
        )

        val decoded = JsonInstant.decodeFromString<TTSProviderSetting>(encode(setting))
        assertTrue(decoded is TTSProviderSetting.FishAudio)
        val fish = decoded as TTSProviderSetting.FishAudio
        assertEquals("fish-key", fish.apiKey)
        assertEquals("s2.1-pro-free", fish.model)
        assertEquals("voice-model-id", fish.voiceId)
        assertEquals(1.2f, fish.speed, 0.001f)

        val stripped = stripConnectionFields(fish) as TTSProviderSetting.FishAudio
        assertEquals("", stripped.apiKey)
        assertEquals("fish-key", fish.apiKey)
    }

    // ==================== Mimo voicedesign / voiceclone 音色标识 ====================

    @Test
    fun `agent mimo voicedesign override keeps model and design prompt`() {
        // 用户核心疑问的回归验证：切换到 voicedesign 后，副本确实存上了 model + 设计描述，
        // 播放链路用的是设计描述；voice 字段残留标准模型值属预期（便于切回时恢复选择）
        val copy = mimo.copy(
            model = "mimo-v2.5-tts-voicedesign",
            voice = "Dean",
            voiceDesignPrompt = "年迈的老先生，北方口音，语速缓慢"
        )
        val result = TtsVoiceResolver.resolve(settingsOf(), mimoId, mapOf(mimoId.toString() to encode(copy)))
        assertNull(result.fallbackNotice)
        val merged = result.providerSetting as TTSProviderSetting.Mimo
        assertEquals("mimo-v2.5-tts-voicedesign", merged.model)
        assertEquals("年迈的老先生，北方口音，语速缓慢", merged.voiceDesignPrompt)
    }

    @Test
    fun `mimo voicedesign effective voice id is design prompt not stale voice`() {
        val setting = mimo.copy(
            model = "mimo-v2.5-tts-voicedesign",
            voice = "Dean",
            voiceDesignPrompt = "年轻女声 ASMR"
        )
        assertEquals("年轻女声 ASMR", mimoEffectiveVoiceId(setting))
    }

    @Test
    fun `mimo voicedesign without prompt returns placeholder`() {
        val setting = mimo.copy(model = "mimo-v2.5-tts-voicedesign", voice = "Dean", voiceDesignPrompt = "  ")
        assertEquals("(未填写音色描述)", mimoEffectiveVoiceId(setting))
    }

    @Test
    fun `mimo voiceclone effective voice id is reference audio file name`() {
        val setting = mimo.copy(
            model = "mimo-v2.5-tts-voiceclone",
            voice = "Dean",
            referenceAudioFileName = "sample.wav"
        )
        assertEquals("sample.wav", mimoEffectiveVoiceId(setting))
    }

    @Test
    fun `mimo standard model effective voice id is voice field`() {
        val setting = mimo.copy(model = "mimo-v2.5-tts", voice = "Dean", voiceDesignPrompt = "unused")
        assertEquals("Dean", mimoEffectiveVoiceId(setting))
    }

    // ==================== 备份项迁移（保持回归覆盖）====================

    @Test
    fun `old backup config gains media items`() {
        val old = WebDavConfig(items = listOf(WebDavConfig.BackupItem.DATABASE, WebDavConfig.BackupItem.FILES))
        val migrated = ensureMediaBackupItems(old)
        assertTrue(WebDavConfig.BackupItem.CALL_RECORDINGS in migrated.items)
        assertTrue(WebDavConfig.BackupItem.RADIO_AUDIO in migrated.items)
        assertTrue(WebDavConfig.BackupItem.TTS_CACHE !in migrated.items)
        assertEquals(migrated, ensureMediaBackupItems(migrated))
    }
}
