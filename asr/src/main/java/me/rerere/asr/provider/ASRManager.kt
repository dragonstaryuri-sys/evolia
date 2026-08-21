package me.rerere.asr.provider

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.Flow
import me.rerere.asr.model.ASRResult
import me.rerere.asr.provider.providers.LocalSenseVoiceASRProvider
import me.rerere.asr.provider.providers.OnlineASRProvider
import me.rerere.asr.provider.providers.SystemASRProvider

class ASRManager {
    private val systemProvider = SystemASRProvider()
    private val onlineProvider = OnlineASRProvider()
    private val localSenseVoiceProvider = LocalSenseVoiceASRProvider()

    fun startRecognition(
        providerSetting: ASRProviderSetting,
        context: Context,
        preRollPcm: List<ShortArray>? = null
    ): Flow<ASRResult> {
        return when (providerSetting) {
            is ASRProviderSetting.SystemASR -> systemProvider.startRecognition(context, providerSetting, preRollPcm)
            is ASRProviderSetting.OnlineASR -> onlineProvider.startRecognition(context, providerSetting, preRollPcm)
            is ASRProviderSetting.EvoliaASR -> onlineProvider.startRecognition(context, providerSetting.toOnlineASR(), preRollPcm)
            is ASRProviderSetting.LocalSenseVoiceASR -> localSenseVoiceProvider.startRecognition(context, providerSetting, preRollPcm)
        }
    }

    /**
     * 整段式转录：读取已有音频文件并一次性转文字。
     * 仅 [ASRProviderSetting.OnlineASR] / [ASRProviderSetting.EvoliaASR] 原生支持；
     * SystemASR 不支持文件转录（仅流式麦克风识别），会抛带明确说明的 [UnsupportedOperationException]。
     *
     * 调用方建议：若选中 SystemASR，应在调用本函数前主动 fallback 到 EvoliaASR 或 OnlineASR（若可用），
     * 避免用户体验报错。参见 ChatVM.sendVoiceMessage 的处理逻辑。
     */
    suspend fun transcribeFile(
        providerSetting: ASRProviderSetting,
        context: Context,
        uri: Uri
    ): String {
        return when (providerSetting) {
            is ASRProviderSetting.SystemASR -> {
                throw UnsupportedOperationException(
                    "SystemASR (Android SpeechRecognizer) 不支持音频文件转录，" +
                        "仅支持从麦克风实时流式识别。请改用 EvoliaASR 或配置了 API Key 的 OnlineASR。"
                )
            }
            is ASRProviderSetting.OnlineASR -> onlineProvider.transcribeFile(context, uri, providerSetting)
            is ASRProviderSetting.EvoliaASR -> onlineProvider.transcribeFile(context, uri, providerSetting.toOnlineASR())
            is ASRProviderSetting.LocalSenseVoiceASR -> localSenseVoiceProvider.transcribeFile(context, uri, providerSetting)
        }
    }
}
