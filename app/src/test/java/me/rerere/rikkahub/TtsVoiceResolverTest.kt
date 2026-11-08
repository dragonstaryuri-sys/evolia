package me.rerere.rikkahub

import me.rerere.rikkahub.data.ai.TtsVoiceResolver
import me.rerere.rikkahub.data.ai.mergeVoiceOverride
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
    }

    @Test
    fun `merge rejects cross type`() {
        assertNull(mergeVoiceOverride(openAi, mimo))
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
