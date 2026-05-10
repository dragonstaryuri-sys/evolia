package me.rerere.tts.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.model.TTSVoice
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.util.concurrent.TimeUnit

private const val TAG = "AzureTTSProvider"

class AzureTTSProvider : TTSProvider<TTSProviderSetting.Azure> {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.Azure,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val ssml = """
            <speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xmlns:mstts='https://www.w3.org/2001/mstts' xml:lang='zh-CN'>
                <voice name='${providerSetting.voiceName}'>
                    <mstts:express-as style='${providerSetting.style}'>
                        <prosody rate='${providerSetting.speed}'>
                            ${request.text}
                        </prosody>
                    </mstts:express-as>
                </voice>
            </speak>
        """.trimIndent()

        Log.i(
            TAG,
            "generateSpeech: region=${providerSetting.region}, " +
                "voice=${providerSetting.voiceName}, style=${providerSetting.style}, speed=${providerSetting.speed}, textLength=${request.text.length}"
        )

        val url = "https://${providerSetting.region}.tts.speech.microsoft.com/cognitiveservices/v1"

        val httpRequest = Request.Builder()
            .url(url)
            .addHeader("Ocp-Apim-Subscription-Key", providerSetting.apiKey)
            .addHeader("Content-Type", "application/ssml+xml")
            .addHeader("X-Microsoft-OutputFormat", "audio-24khz-160kbitrate-mono-mp3")
            .addHeader("User-Agent", "Evolia")
            .post(ssml.toRequestBody("application/ssml+xml".toMediaType()))
            .build()

        val response = withContext(Dispatchers.IO) {
            httpClient.newCall(httpRequest).execute()
        }

        if (!response.isSuccessful) {
            val errorBody = response.body.string()
            Log.e(TAG, "Azure TTS request failed: ${response.code} $errorBody")
            throw Exception("Azure TTS failed: ${response.code} $errorBody")
        }

        val audioData = response.body.bytes()

        emit(
            AudioChunk(
                data = audioData,
                format = AudioFormat.MP3,
                isLast = true,
                metadata = mapOf(
                    "provider" to "azure",
                    "region" to providerSetting.region,
                    "voice" to providerSetting.voiceName,
                    "style" to providerSetting.style,
                    "speed" to providerSetting.speed.toString()
                )
            )
        )
    }

    override suspend fun getVoices(
        context: Context,
        providerSetting: TTSProviderSetting.Azure
    ): List<TTSVoice> = withContext(Dispatchers.IO) {
        Log.i(TAG, "Fetching voices for region: ${providerSetting.region}")
        val url = "https://${providerSetting.region}.tts.speech.microsoft.com/cognitiveservices/voices/list"
        val httpRequest = Request.Builder()
            .url(url)
            .addHeader("Ocp-Apim-Subscription-Key", providerSetting.apiKey)
            .addHeader("User-Agent", "Evolia")
            .get()
            .build()

        try {
            val response = httpClient.newCall(httpRequest).execute()
            if (!response.isSuccessful) {
                val errorBody = response.body.string()
                Log.e(TAG, "Azure TTS get voices failed: ${response.code} $errorBody")
                return@withContext emptyList()
            }

            val responseBody = response.body.string()
            val jsonArray = JSONArray(responseBody)
            val voices = mutableListOf<TTSVoice>()
            for (i in 0 until jsonArray.length()) {
                val voiceObj = jsonArray.getJSONObject(i)
                val styles = mutableListOf<String>()
                if (voiceObj.has("StyleList")) {
                    val stylesArray = voiceObj.getJSONArray("StyleList")
                    for (j in 0 until stylesArray.length()) {
                        styles.add(stylesArray.getString(j))
                    }
                }
                voices.add(
                    TTSVoice(
                        id = voiceObj.getString("ShortName"),
                        name = voiceObj.optString("DisplayName", voiceObj.optString("LocalName", voiceObj.getString("ShortName"))),
                        locale = voiceObj.optString("Locale"),
                        gender = voiceObj.optString("Gender"),
                        description = voiceObj.optString("Name"),
                        styles = styles
                    )
                )
            }
            Log.i(TAG, "Successfully fetched ${voices.size} voices")
            voices
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching voices", e)
            emptyList()
        }
    }
}
