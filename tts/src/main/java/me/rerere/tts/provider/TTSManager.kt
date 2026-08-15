package me.rerere.tts.provider

import android.content.Context
import kotlinx.coroutines.flow.Flow
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.model.TTSVoice
import me.rerere.tts.provider.providers.AzureTTSProvider
import me.rerere.tts.provider.providers.CustomTTSProvider
import me.rerere.tts.provider.providers.ElevenLabsTTSProvider
import me.rerere.tts.provider.providers.GeminiTTSProvider
import me.rerere.tts.provider.providers.MimoTTSProvider
import me.rerere.tts.provider.providers.MiniMaxTTSProvider
import me.rerere.tts.provider.providers.OpenAITTSProvider
import me.rerere.tts.provider.providers.SystemTTSProvider

class TTSManager(private val context: Context) {
    private val openAIProvider = OpenAITTSProvider()
    private val mimoProvider = MimoTTSProvider()
    private val customProvider = CustomTTSProvider()
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
            is TTSProviderSetting.Mimo -> mimoProvider.generateSpeech(context, providerSetting, request)
            is TTSProviderSetting.Custom -> customProvider.generateSpeech(context, providerSetting, request)
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
            is TTSProviderSetting.Mimo -> mimoProvider.getVoices(context, providerSetting)
            is TTSProviderSetting.Custom -> customProvider.getVoices(context, providerSetting)
            is TTSProviderSetting.Gemini -> geminiProvider.getVoices(context, providerSetting)
            is TTSProviderSetting.SystemTTS -> systemProvider.getVoices(context, providerSetting)
            is TTSProviderSetting.MiniMax -> miniMaxProvider.getVoices(context, providerSetting)
            is TTSProviderSetting.ElevenLabs -> elevenLabsProvider.getVoices(context, providerSetting)
            is TTSProviderSetting.Azure -> azureProvider.getVoices(context, providerSetting)
        }
    }

    /**
     * MiMo TTS 专用：从官方 API 获取包含 "tts" 的模型列表
     */
    suspend fun listMimoModels(
        providerSetting: TTSProviderSetting.Mimo
    ): List<String> {
        return mimoProvider.listModels(providerSetting)
    }

    // ================== MiniMax 音色设计 & 复刻 专用接口 ==================

    /**
     * MiniMax 专用：上传音频文件（用于音色复刻的主音频或示例音频）
     *
     * @param providerSetting MiniMax 配置
     * @param file 本地音频文件（mp3/m4a/wav，主音频 10s-5min，示例音频<8s）
     * @param purpose "voice_clone"（复刻主音频）或 "prompt_audio"（示例音频）
     * @return MiniMax 返回的 file_id
     */
    suspend fun miniMaxUploadFile(
        providerSetting: TTSProviderSetting.MiniMax,
        file: java.io.File,
        purpose: String
    ): Long {
        return miniMaxProvider.uploadFile(providerSetting, file, purpose)
    }

    /**
     * MiniMax 专用：音色设计（Voice Design），通过文本描述生成个性化音色
     *
     * @return Pair(voice_id, trial_audio_hex)
     */
    suspend fun miniMaxVoiceDesign(
        providerSetting: TTSProviderSetting.MiniMax
    ): Pair<String, String> {
        return miniMaxProvider.voiceDesign(providerSetting)
    }

    /**
     * MiniMax 专用：音色复刻（Voice Clone），基于音频样本快速复刻
     *
     * @return 试听音频 URL（若配置了试听文本），否则空串
     */
    suspend fun miniMaxVoiceClone(
        providerSetting: TTSProviderSetting.MiniMax
    ): String {
        return miniMaxProvider.voiceClone(providerSetting)
    }

    /**
     * MiniMax 专用：校验自定义 voice_id 格式是否合法
     */
    fun miniMaxValidateVoiceId(voiceId: String): Result<Unit> {
        return runCatching { miniMaxProvider.validateVoiceId(voiceId) }
    }
}
