package me.rerere.asr.provider

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.Flow
import me.rerere.asr.model.ASRResult

interface ASRProvider<T : ASRProviderSetting> {
    /**
     * 启动实时识别。返回 Flow，会发射 partial(非最终) 与 final(最终) 结果，
     * 识别结束或出错时流终止。调用方可在流收集过程中取消以停止识别。
     *
     * 该接口面向"实时监听式"ASR（System SpeechRecognizer / 后续 Zipformer 流式），
     * 由 provider 自行管理麦克风采集。整段式 ASR（如 OpenAI Whisper）后续以扩展方式接入。
     */
    fun startRecognition(
        context: Context,
        providerSetting: T
    ): Flow<ASRResult>

    /**
     * 整段式转录：读取已有音频文件并一次性转文字。
     *
     * 用于"按住说话 → 松开 → 转文字发送"的语音消息场景。
     * 默认抛出 [UnsupportedOperationException]，仅支持整段转录的 Provider（如 Whisper 兼容 API）重写。
     *
     * @return 识别文本；若 Provider 不支持则抛异常。
     */
    suspend fun transcribeFile(
        context: Context,
        uri: Uri,
        providerSetting: T
    ): String = throw UnsupportedOperationException("整段式 ASR 转录未实现，请切换到 Online ASR (Whisper)")
}

