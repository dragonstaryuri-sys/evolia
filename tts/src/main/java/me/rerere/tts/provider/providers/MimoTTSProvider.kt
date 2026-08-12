package me.rerere.tts.provider.providers

import android.content.Context
import android.util.Base64
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
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "MimoTTSProvider"

/**
 * 将音频文件扩展名映射为标准 MIME 类型，用于构造 MiMo voiceclone 的 data URI
 * 参考官方示例：voice.mp3 -> data:audio/mpeg;base64,...
 */
private fun formatToAudioMime(format: String): String = when (format.trim().lowercase()) {
    "mp3", "mpeg" -> "audio/mpeg"
    "wav", "wave" -> "audio/wav"
    "m4a", "aac", "mp4" -> "audio/mp4"
    "flac" -> "audio/flac"
    "ogg", "oga" -> "audio/ogg"
    "opus" -> "audio/opus"
    "webm" -> "audio/webm"
    "amr" -> "audio/amr"
    "3gp" -> "audio/3gpp"
    else -> "audio/${format.trim().lowercase().ifBlank { "wav" }}"
}

class MimoTTSProvider : TTSProvider<TTSProviderSetting.Mimo> {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS) // TTS might take longer
        .build()

    // 预置 MIMO TTS 模型列表
    val presetModels = listOf(
        "mimo-v2.5-tts",
        "mimo-v2.5-tts-voicedesign",
        "mimo-v2.5-tts-voiceclone"
    )

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.Mimo,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val model = providerSetting.model
        val isVoiceDesign = model.contains("voicedesign", ignoreCase = true)
        val isVoiceClone = model.contains("voiceclone", ignoreCase = true)
        val isStandardTTS = !isVoiceDesign && !isVoiceClone

        // MiMo requires chat completion format
        val requestBody = JSONObject().apply {
            put("model", model)

            val messages = JSONArray()

            when {
                // ---- 音色设计 (voicedesign): user 消息 = 音色描述 ----
                isVoiceDesign -> {
                    // 音色设计描述必填，放在 user 消息
                    val designPrompt = providerSetting.voiceDesignPrompt.ifBlank {
                        "default warm female voice"
                    }
                    messages.put(JSONObject().apply {
                        put("role", "user")
                        put("content", designPrompt)
                    })
                    // assistant 消息 = 待合成文本 (若开启 optimize_text_preview 则可省略)
                    if (!providerSetting.optimizeTextPreview) {
                        messages.put(JSONObject().apply {
                            put("role", "assistant")
                            put("content", request.text)
                        })
                    } else if (request.text.isNotBlank()) {
                        // 即使开启了润色，如果有文本也传过去
                        messages.put(JSONObject().apply {
                            put("role", "assistant")
                            put("content", request.text)
                        })
                    }
                }

                // ---- 音色复刻 (voiceclone): 严格对齐官方示例，user 必须为空字符串！----
                isVoiceClone -> {
                    messages.put(JSONObject().apply {
                        put("role", "user")
                        put("content", "") // ⚠️ 必须是空字符串，官方示例。填其他内容易触发服务端校验异常 → 429
                    })
                    messages.put(JSONObject().apply {
                        put("role", "assistant")
                        put("content", request.text)
                    })
                }

                // ---- 标准预置音色 (mimo-v2.5-tts): user 留空，和官方示例保持一致 ----
                else -> {
                    messages.put(JSONObject().apply {
                        put("role", "user")
                        put("content", "") // user 为空字符串（如需风格控制可填指令，但官方示例用空串更稳妥，避免限流）
                    })
                    messages.put(JSONObject().apply {
                        put("role", "assistant")
                        put("content", request.text)
                    })
                }
            }

            put("messages", messages)

            put("audio", JSONObject().apply {
                put("format", "mp3") // 支持 mp3, wav, pcm — 官方示例用 wav，mp3 也可正常使用

                when {
                    // 标准 TTS：预置音色 (⚠️ audio.speed 字段 MIMO 官方尚未开放，已移除)
                    isStandardTTS -> {
                        put("voice", providerSetting.voice)
                    }

                    // 音色设计：可选文本润色 (⚠️ audio.speed 字段 MIMO 官方尚未开放，已移除)
                    isVoiceDesign -> {
                        put("optimize_text_preview", providerSetting.optimizeTextPreview)
                    }

                    // 音色复刻：voice 字段填完整 data URI: data:audio/{mime};base64,{data}
                    // ⚠️ 严格对齐官方 Python 示例：只传 format + voice 两个字段！
                    isVoiceClone -> {
                        if (providerSetting.referenceAudioBase64.isNotBlank()) {
                            val mime = formatToAudioMime(providerSetting.referenceAudioFormat)
                            val voiceDataUri = "data:$mime;base64,${providerSetting.referenceAudioBase64}"
                            put("voice", voiceDataUri)
                        }
                    }
                }
            })

            put("stream", false) // Non-streaming for now
        }

        Log.i(
            TAG,
            "generateSpeech: model=$model, " +
                "isStandard=$isStandardTTS, isVoiceDesign=$isVoiceDesign, isVoiceClone=$isVoiceClone, " +
                "voice=${providerSetting.voice}, speed=${providerSetting.speed}, " +
                "referenceAudioLen=${providerSetting.referenceAudioBase64.length}"
        )

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
                metadata = buildMap {
                    put("provider", "mimo")
                    put("model", model)
                    if (isStandardTTS) put("voice", providerSetting.voice)
                    put("speed", providerSetting.speed.toString())
                }
            )
        )
    }

    override suspend fun getVoices(
        context: Context,
        providerSetting: TTSProviderSetting.Mimo
    ): List<TTSVoice> {
        // 仅标准 TTS 模型有预置音色列表
        return listOf(
            TTSVoice("mimo_default", "MiMo-默认", "zh-CN", "Female", "默认音色，中国集群为冰糖，其他集群为 Mia", emptyList()),
            TTSVoice("冰糖", "冰糖 (Bingtang)", "zh-CN", "Female", "中文女性音色", emptyList()),
            TTSVoice("茉莉", "茉莉 (Moli)", "zh-CN", "Female", "中文女性音色", emptyList()),
            TTSVoice("苏打", "苏打 (Suda)", "zh-CN", "Male", "中文男性音色", emptyList()),
            TTSVoice("白桦", "白桦 (Baihua)", "zh-CN", "Male", "中文男性音色", emptyList()),
            TTSVoice("Mia", "Mia", "en-US", "Female", "英文女性音色", emptyList()),
            TTSVoice("Chloe", "Chloe", "en-US", "Female", "英文女性音色", emptyList()),
            TTSVoice("Milo", "Milo", "en-US", "Male", "英文男性音色", emptyList()),
            TTSVoice("Dean", "Dean", "en-US", "Male", "英文男性音色", emptyList())
        )
    }

    /**
     * 从官方 API 获取包含 "tts" 的模型列表
     * 接口地址: GET {baseUrl 去掉 /chat/completions}/models
     *
     * @return 模型 id 列表，仅包含含 "tts" 的模型
     */
    suspend fun listModels(
        providerSetting: TTSProviderSetting.Mimo
    ): List<String> = withContext(Dispatchers.IO) {
        if (providerSetting.apiKey.isBlank()) return@withContext emptyList()

        val modelsBaseUrl = providerSetting.baseUrl
            .removeSuffix("/")
            .removeSuffix("/chat/completions")

        val request = Request.Builder()
            .url("$modelsBaseUrl/models")
            .addHeader("Authorization", "Bearer ${providerSetting.apiKey}")
            .get()
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "listModels failed: ${response.code} ${response.body.string()}")
                return@withContext emptyList()
            }

            val bodyStr = response.body.string()
            val jsonResponse = JSONObject(bodyStr)
            val data = jsonResponse.optJSONArray("data") ?: return@withContext emptyList()

            val result = mutableListOf<String>()
            for (i in 0 until data.length()) {
                val modelObj = data.optJSONObject(i) ?: continue
                val modelId = modelObj.optString("id")
                if (modelId.isNotBlank() && modelId.contains("tts", ignoreCase = true)) {
                    result.add(modelId)
                }
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "listModels exception", e)
            emptyList()
        }
    }
}
