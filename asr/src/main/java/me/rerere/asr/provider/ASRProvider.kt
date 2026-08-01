package me.rerere.asr.provider

import android.content.Context
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
}
