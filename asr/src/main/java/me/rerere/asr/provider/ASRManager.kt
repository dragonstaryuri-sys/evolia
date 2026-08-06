package me.rerere.asr.provider

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.Flow
import me.rerere.asr.model.ASRResult
import me.rerere.asr.provider.providers.OnlineASRProvider
import me.rerere.asr.provider.providers.SystemASRProvider

class ASRManager {
    private val systemProvider = SystemASRProvider()
    private val onlineProvider = OnlineASRProvider()

    fun startRecognition(
        providerSetting: ASRProviderSetting,
        context: Context
    ): Flow<ASRResult> {
        return when (providerSetting) {
            is ASRProviderSetting.SystemASR -> systemProvider.startRecognition(context, providerSetting)
            is ASRProviderSetting.OnlineASR -> onlineProvider.startRecognition(context, providerSetting)
        }
    }

    /**
     * 整段式转录：读取已有音频文件并一次性转文字。
     * 仅 [ASRProviderSetting.OnlineASR] 支持；SystemASR 会抛 [UnsupportedOperationException]。
     */
    suspend fun transcribeFile(
        providerSetting: ASRProviderSetting,
        context: Context,
        uri: Uri
    ): String {
        return when (providerSetting) {
            is ASRProviderSetting.SystemASR -> systemProvider.transcribeFile(context, uri, providerSetting)
            is ASRProviderSetting.OnlineASR -> onlineProvider.transcribeFile(context, uri, providerSetting)
        }
    }
}
