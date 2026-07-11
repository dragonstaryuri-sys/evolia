package me.rerere.tts.provider.providers

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "MimoTTSProvider"

class MimoTTSProvider : TTSProvider<TTSProviderSetting.Mimo> {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS) // TTS might take longer
        .build()

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.Mimo,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        // MiMo requires chat completion format
        val requestBody = JSONObject().apply {
            put("model", providerSetting.model)

            val messages = JSONArray().apply {
                // MiMo requires an assistant message for the text to be spoken
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "Speech synthesis request")
                })
                put(JSONObject().apply {
                    put("role", "assistant")
                    put("content", request.text)
                })
            }
            put("messages", messages)

            put("audio", JSONObject().apply {
                put("format", "mp3") // Support mp3, wav, pcm
                put("voice", providerSetting.voice)
                put("speed", providerSetting.speed)
            })

            put("stream", false) // Non-streaming for now as per simple implementation
        }

        Log.i(TAG, "generateSpeech: model=${providerSetting.model}, voice=${providerSetting.voice}, speed=${providerSetting.speed}")

        val httpRequest = Request.Builder()
            .url(providerSetting.baseUrl)
            .addHeader("Authorization", "Bearer ${providerSetting.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(httpRequest).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body.string()
            Log.e(TAG, "Mimo TTS request failed: ${response.code} $errorBody")
            throw Exception("Mimo TTS failed: $errorBody")
        }

        val responseBody = response.body.string()
        val jsonResponse = JSONObject(responseBody)

        // Audio is in choices[0].message.audio.data as Base64
        val choices = jsonResponse.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            throw Exception("Mimo TTS: Invalid response format, no choices found")
        }

        val message = choices.getJSONObject(0).optJSONObject("message")
        val audio = message?.optJSONObject("audio")
        val base64Data = audio?.optString("data")

        if (base64Data.isNullOrEmpty()) {
            Log.e(TAG, "Mimo TTS: No audio data in response: $responseBody")
            throw Exception("Mimo TTS: No audio data in response")
        }

        val audioData = Base64.decode(base64Data, Base64.DEFAULT)

        emit(
            AudioChunk(
                data = audioData,
                format = AudioFormat.MP3,
                isLast = true,
                metadata = mapOf(
                    "provider" to "mimo",
                    "model" to providerSetting.model,
                    "voice" to providerSetting.voice,
                    "speed" to providerSetting.speed.toString()
                )
            )
        )
    }
}
