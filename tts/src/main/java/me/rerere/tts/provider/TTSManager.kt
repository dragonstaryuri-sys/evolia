package me.rerere.tts.provider

import android.content.Context
import kotlinx.coroutines.flow.Flow
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.model.TTSVoice
import me.rerere.tts.provider.providers.AzureTTSProvider
import me.rerere.tts.provider.providers.ElevenLabsTTSProvider
import me.rerere.tts.provider.providers.GeminiTTSProvider
import me.rerere.tts.provider.providers.MiniMaxTTSProvider
import me.rerere.tts.provider.providers.OpenAITTSProvider
import me.rerere.tts.provider.providers.SystemTTSProvider

class TTSManager(private val context: Context) {
    private val openAIProvider = OpenAITTSProvider()
    private val geminiProvider = GeminiTTSProvider()
    private val systemProvider = SystemTTSProvider()
    private val miniMaxProvider = MiniMaxTTSProvider()
    private val elevenLabsProvider = ElevenLabsTTSProvider()
    private val azureProvider = AzureTTSProvider()

    fun generateSpeech(
        providerSetting: TTSProviderSetting,
        request: TTSRequest
    ): Flow<AudioChunk> {
        return when (providerSetting) {
            is TTSProviderSetting.OpenAI -> openAIProvider.generateSpeech(context, providerSetting, request)
            is TTSProviderSetting.Gemini -> geminiProvider.generateSpeech(context, providerSetting, request)
            is TTSProviderSetting.SystemTTS -> systemProvider.generateSpeech(context, providerSetting, request)
            is TTSProviderSetting.MiniMax -> miniMaxProvider.generateSpeech(context, providerSetting, request)
            is TTSProviderSetting.ElevenLabs -> elevenLabsProvider.generateSpeech(context, providerSetting, request)
            is TTSProviderSetting.Azure -> azureProvider.generateSpeech(context, providerSetting, request)
        }
    }

    suspend fun getVoices(
        providerSetting: TTSProviderSetting
    ): List<TTSVoice> {
        return when (providerSetting) {
            is TTSProviderSetting.OpenAI -> openAIProvider.getVoices(context, providerSetting)
            is TTSProviderSetting.Gemini -> geminiProvider.getVoices(context, providerSetting)
            is TTSProviderSetting.SystemTTS -> systemProvider.getVoices(context, providerSetting)
            is TTSProviderSetting.MiniMax -> miniMaxProvider.getVoices(context, providerSetting)
            is TTSProviderSetting.ElevenLabs -> elevenLabsProvider.getVoices(context, providerSetting)
            is TTSProviderSetting.Azure -> azureProvider.getVoices(context, providerSetting)
        }
    }
}
