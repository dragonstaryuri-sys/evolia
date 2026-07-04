package me.rerere.tts.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "ElevenLabsTTSProvider"

// 全局信号量，确保即使 Provider 被多次创建，并发限制依然生效
// 虽然 ElevenLabs 允许 2 个并发，但设为 1 可以有效避免因为网络抖动或预取机制导致的边缘触发错误
private val globalSemaphore = Semaphore(1)

class ElevenLabsTTSProvider : TTSProvider<TTSProviderSetting.ElevenLabs> {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.ElevenLabs,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        globalSemaphore.withPermit {
            val requestBody = JSONObject().apply {
                put("text", request.text)
                put("model_id", providerSetting.modelId)
            }

            Log.i(TAG, "generateSpeech: voiceId=${providerSetting.voiceId}, model=${providerSetting.modelId}")

            val httpRequest = Request.Builder()
                .url("https://api.elevenlabs.io/v1/text-to-speech/${providerSetting.voiceId}")
                .addHeader("xi-api-key", providerSetting.apiKey)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "audio/mpeg")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(httpRequest).execute()

            response.use { resp ->
                if (!resp.isSuccessful) {
                    val errorBody = resp.body.string()
                    Log.e(TAG, "TTS request failed: ${resp.code} $errorBody")
                    throw Exception("ElevenLabs TTS failed: $errorBody")
                }

                val audioData = resp.body.bytes()

                emit(
                    AudioChunk(
                        data = audioData,
                        format = AudioFormat.MP3,
                        isLast = true,
                        metadata = mapOf(
                            "provider" to "elevenlabs",
                            "model" to providerSetting.modelId,
                            "voice" to providerSetting.voiceId
                        )
                    )
                )
            }
        }
    }
}
