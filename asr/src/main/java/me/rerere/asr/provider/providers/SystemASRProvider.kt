package me.rerere.asr.provider.providers

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import me.rerere.asr.model.ASRResult
import me.rerere.asr.provider.ASRProvider
import me.rerere.asr.provider.ASRProviderSetting

private const val TAG = "SystemASRProvider"

class SystemASRProvider : ASRProvider<ASRProviderSetting.SystemASR> {

    override fun startRecognition(
        context: Context,
        providerSetting: ASRProviderSetting.SystemASR
    ): Flow<ASRResult> = callbackFlow {
        // 诊断: 检查设备上是否有可用的语音识别服务组件
        val serviceIntent = Intent("android.speech.RecognitionService")
        val services = context.packageManager.queryIntentServices(serviceIntent, 0)
        Log.i(
            TAG,
            "RecognitionService components: ${
                services.joinToString { "${it.serviceInfo.packageName}/${it.serviceInfo.name}" }
                    .ifEmpty { "(none)" }
            }"
        )
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            close(RuntimeException("Speech recognition is not available on this device"))
            return@callbackFlow
        }
        if (services.isEmpty()) {
            // 部分 ROM 的 isRecognitionAvailable 返回 true 但实际没有服务组件, 导致 error(9).
            close(RuntimeException("Speech recognition is not available: no RecognitionService installed on this device"))
            return@callbackFlow
        }

        // SpeechRecognizer 必须在主线程创建与调用，此处由 flowOn(Main.immediate) 保证
        // 显式指定默认 RecognitionService 组件, 避免某些 ROM 的 createSpeechRecognizer(context) 选错服务
        val defaultService = services.firstOrNull { it.isDefault } ?: services.firstOrNull()
        val component = defaultService?.let {
            ComponentName(it.serviceInfo.packageName, it.serviceInfo.name)
        }
        Log.i(TAG, "Creating SpeechRecognizer with component: $component, offline=${providerSetting.enableOffline}")
        val recognizer = if (component != null) {
            SpeechRecognizer.createSpeechRecognizer(context, component)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, providerSetting.language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, providerSetting.enableOffline)
        }

        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                val message = errorMessage(error)
                Log.w(TAG, "onError: code=$error ($message), component=$component, offline=${providerSetting.enableOffline}")
                // NO_MATCH / SPEECH_TIMEOUT 视为"未识别到语音"，发空最终结果后正常关闭，
                // 避免因用户短暂沉默而以异常方式中断通话
                val isSilence = error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                if (isSilence) {
                    trySend(ASRResult(text = "", isFinal = true))
                    channel.close()
                } else {
                    close(RuntimeException("ASR error($error): $message"))
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotEmpty()) {
                    trySend(ASRResult(text = text, isFinal = false))
                }
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                trySend(ASRResult(text = text, isFinal = true))
                channel.close()
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }

        recognizer.setRecognitionListener(listener)
        Log.i(
            TAG,
            "startRecognition: language=${providerSetting.language}, offline=${providerSetting.enableOffline}"
        )
        recognizer.startListening(intent)

        awaitClose {
            Log.d(TAG, "awaitClose: stopListening + destroy")
            recognizer.stopListening()
            recognizer.destroy()
        }
    }.flowOn(Dispatchers.Main.immediate)

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
        SpeechRecognizer.ERROR_NETWORK -> "network"
        SpeechRecognizer.ERROR_AUDIO -> "audio"
        SpeechRecognizer.ERROR_SERVER -> "server"
        SpeechRecognizer.ERROR_CLIENT -> "client"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "insufficient permissions"
        SpeechRecognizer.ERROR_NO_MATCH -> "no match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "language not supported"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "language unavailable"
        else -> "unknown($error)"
    }
}
