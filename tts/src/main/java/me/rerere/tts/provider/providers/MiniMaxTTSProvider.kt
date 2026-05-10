package me.rerere.tts.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.common.http.SseEvent
import me.rerere.common.http.sseFlow
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
import java.util.concurrent.TimeUnit

private const val TAG = "MiniMaxTTSProvider"

@Serializable
private data class MiniMaxResponseData(
    val audio: String,
    val status: Int,
    val ced: String
)

@Serializable
private data class MiniMaxResponse(
    val data: MiniMaxResponseData
)

class MiniMaxTTSProvider : TTSProvider<TTSProviderSetting.MiniMax> {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.MiniMax,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val requestBody = buildJsonObject {
            put("model", providerSetting.model)
            put("text", request.text)
            put("stream", true)
            put("output_format", "hex")
            put("stream_options", buildJsonObject {
                put("exclude_aggregated_audio", true)
            })
            put("voice_setting", buildJsonObject {
                put("voice_id", providerSetting.voiceId)
                put("emotion", providerSetting.emotion)
                put("speed", providerSetting.speed)
            })
        }

        Log.i(
            TAG,
            "generateSpeech: model=${providerSetting.model}, " +
                "voice=${providerSetting.voiceId}, emotion=${providerSetting.emotion}, " +
                "textLength=${request.text.length}"
        )

        val httpRequest = Request.Builder()
            .url("${providerSetting.baseUrl}/t2a_v2")
            .addHeader("Authorization", "Bearer ${providerSetting.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .build()

        var hasEmittedAudio = false

        httpClient.sseFlow(httpRequest).collect {
            when (it) {
                is SseEvent.Open -> Log.i(TAG, "SSE connection opened")
                is SseEvent.Event -> {
                    try {
                        val data = json.decodeFromString<MiniMaxResponse>(it.data)

                        // Convert hex string to bytes
                        val audioBytes = hexStringToBytes(data.data.audio)

                        emit(
                            AudioChunk(
                                data = audioBytes,
                                format = AudioFormat.MP3, // MiniMax returns MP3 format
                                sampleRate = 32000, // Default sample rate from MiniMax
                                isLast = false, // Will be set to true on last chunk
                                metadata = mapOf(
                                    "provider" to "minimax",
                                    "model" to providerSetting.model,
                                    "voice" to providerSetting.voiceId,
                                    "status" to data.data.status.toString(),
                                    "ced" to data.data.ced
                                )
                            )
                        )
                        hasEmittedAudio = true
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to process audio chunk", e)
                    }
                }

                is SseEvent.Closed -> {
                    Log.i(TAG, "SSE connection closed")
                    // Emit final chunk if we haven't already
                    if (hasEmittedAudio) {
                        emit(
                            AudioChunk(
                                data = byteArrayOf(), // Empty data for last chunk
                                format = AudioFormat.MP3,
                                sampleRate = 32000,
                                isLast = true,
                                metadata = mapOf("provider" to "minimax")
                            )
                        )
                    }
                }

                is SseEvent.Failure -> {
                    Log.e(TAG, "SSE connection failed", it.throwable)
                    throw it.throwable ?: Exception("MiniMax TTS streaming failed")
                }
            }
        }
    }

    override suspend fun getVoices(
        context: Context,
        providerSetting: TTSProviderSetting.MiniMax
    ): List<TTSVoice> {
        // MiniMax 官方目前没有提供公开的系统语音列表 API，这里返回预定义的精品语音列表
        val commonEmotions = listOf("calm", "happy", "sad", "angry", "fearful", "disgusted", "surprised")

        return listOf(
            TTSVoice("female-shaonv", "少女 (Shaonv)", "zh-CN", "Female", "甜美少女音", commonEmotions),
            TTSVoice("female-yujie", "御姐 (Yujie)", "zh-CN", "Female", "成熟女性音", commonEmotions),
            TTSVoice("female-chengshu", "成熟女性 (Chengshu)", "zh-CN", "Female", "知性女性音", commonEmotions),
            TTSVoice("female-tianmei", "甜美女性 (Tianmei)", "zh-CN", "Female", "温柔甜美音", commonEmotions),
            TTSVoice("male-qn-qingse", "青涩青少 (Qingse)", "zh-CN", "Male", "阳光少年音", commonEmotions),
            TTSVoice("male-qn-jingying", "精英青年 (Jingying)", "zh-CN", "Male", "稳重青年音", commonEmotions),
            TTSVoice("male-qn-badao", "霸道总裁 (Badao)", "zh-CN", "Male", "磁性男声", commonEmotions),
            TTSVoice("male-qn-daxuesheng", "大学生 (Daxuesheng)", "zh-CN", "Male", "清爽男声", commonEmotions),
            TTSVoice("audiobook_male_1", "叙事男声 (Audiobook)", "zh-CN", "Male", "适合朗读", commonEmotions),
            TTSVoice("audiobook_female_1", "叙事女声 (Audiobook)", "zh-CN", "Female", "适合朗读", commonEmotions),
            TTSVoice("cartoon_pig", "猪小屁 (Cartoon)", "zh-CN", "Male", "卡通音", listOf("calm"))
        )
    }
}

private fun hexStringToBytes(hexString: String): ByteArray {
    val cleanHex = hexString.replace("\\s+".toRegex(), "")
    val length = cleanHex.length

    // Check for even number of characters
    if (length % 2 != 0) {
        throw IllegalArgumentException("Hex string must have even number of characters")
    }

    val bytes = ByteArray(length / 2)
    for (i in 0 until length step 2) {
        val hexByte = cleanHex.substring(i, i + 2)
        bytes[i / 2] = hexByte.toInt(16).toByte()
    }
    return bytes
}
