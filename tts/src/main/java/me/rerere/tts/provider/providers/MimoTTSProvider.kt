package me.rerere.tts.provider.providers

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
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
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "MimoTTSProvider"

/**
 * 将音频文件扩展名映射为标准 MIME 类型，用于构造 MiMo voiceclone 的 data URI
 * 参考官方示例：voice.mp3 -> data:audio/mpeg;base64,...
 *
 * ⚠️ MiMo 服务端通过 data URI 中的 MIME 子类型匹配扩展名白名单 (mp3/flac/m4a/wav/ogg)
 * - mp3 → audio/mpeg (官方示例，服务器识别 mpeg→mp3)
 * - m4a → audio/m4a (不能用 audio/mp4！服务器不认 mp4，只认 m4a)
 * - wav → audio/wav
 * - flac → audio/flac
 * - ogg → audio/ogg
 */
private fun formatToAudioMime(format: String): String = when (format.trim().lowercase()) {
    "mp3", "mpeg" -> "audio/mpeg"
    "wav", "wave" -> "audio/wav"
    "m4a" -> "audio/m4a"
    "flac" -> "audio/flac"
    "ogg", "oga" -> "audio/ogg"
    // 以下格式 MiMo 官方不支持，仅做兜底映射（服务器会拒绝）
    "aac", "mp4" -> "audio/mp4"
    "opus" -> "audio/opus"
    "webm" -> "audio/webm"
    "amr" -> "audio/amr"
    "3gp" -> "audio/3gpp"
    else -> "audio/${format.trim().lowercase().ifBlank { "wav" }}"
}

class MimoTTSProvider : TTSProvider<TTSProviderSetting.Mimo> {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS) // 流式 SSE 更久的读超时
        .build()

    // 预置 MIMO TTS 模型列表
    val presetModels = listOf(
        "mimo-v2.5-tts",
        "mimo-v2.5-tts-voicedesign",
        "mimo-v2.5-tts-voiceclone"
    )

    // MIMO 流式输出统一为 24kHz PCM16LE mono (匹配官方 Python 示例的 np.frombuffer(..., dtype=np.int16) + samplerate=24000)
    private val MIMO_STREAM_SAMPLE_RATE = 24000

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.Mimo,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val model = providerSetting.model
        val isVoiceDesign = model.contains("voicedesign", ignoreCase = true)
        val isVoiceClone = model.contains("voiceclone", ignoreCase = true)
        val isStandardTTS = !isVoiceDesign && !isVoiceClone

        // 严格对齐官方 Python 示例：stream=True + SSE
        val requestBody = JSONObject().apply {
            put("model", model)
            put("stream", true) // ⚠️ 流式调用 (所有 3 个官方示例全是 stream=True)

            val messages = JSONArray()

            when {
                // ---- 音色设计 (voicedesign): user=音色描述，assistant=待合成文本 (必传！即使 optimize_text_preview=true) ----
                isVoiceDesign -> {
                    val designPrompt = providerSetting.voiceDesignPrompt.ifBlank {
                        "default warm female voice"
                    }
                    messages.put(JSONObject().apply {
                        put("role", "user")
                        put("content", designPrompt)
                    })
                    // ⚠️ 官方示例 optimize_text_preview=True 时仍然传 assistant 消息；必须始终带上
                    messages.put(JSONObject().apply {
                        put("role", "assistant")
                        put("content", request.text)
                    })
                }

                // ---- 音色复刻 (voiceclone): 严格对齐官方示例，user=""，assistant=合成文本 ----
                isVoiceClone -> {
                    messages.put(JSONObject().apply {
                        put("role", "user")
                        put("content", "") // 必须是空字符串
                    })
                    messages.put(JSONObject().apply {
                        put("role", "assistant")
                        put("content", request.text)
                    })
                }

                // ---- 标准预置音色 (mimo-v2.5-tts): user 可填风格指令 (示例就是这么用的)，空串也兼容 ----
                else -> {
                    messages.put(JSONObject().apply {
                        put("role", "user")
                        put("content", "") // 留空 (如需风格指令 UI 扩展可后续接入)
                    })
                    messages.put(JSONObject().apply {
                        put("role", "assistant")
                        put("content", request.text)
                    })
                }
            }

            put("messages", messages)

            put("audio", JSONObject().apply {
                // 请求的 format 严格匹配官方示例
                // 但 SSE 流式响应的 delta.audio.data 统一都是 PCM16LE 原始采样 (24kHz mono)，这一点已由 Python 示例 int16.frombuffer 佐证
                when {
                    // 标准 TTS: format=pcm16 (官方示例)
                    isStandardTTS -> {
                        put("format", "pcm16")
                        put("voice", providerSetting.voice)
                    }

                    // 音色设计: format=pcm16 (官方示例) + optimize_text_preview
                    isVoiceDesign -> {
                        put("format", "pcm16")
                        put("optimize_text_preview", providerSetting.optimizeTextPreview)
                    }

                    // 音色复刻: format=wav (官方示例) + voice = Data URI
                    isVoiceClone -> {
                        put("format", "wav")
                        if (providerSetting.referenceAudioBase64.isNotBlank()) {
                            val mime = formatToAudioMime(providerSetting.referenceAudioFormat)
                            val voiceDataUri = "data:$mime;base64,${providerSetting.referenceAudioBase64}"
                            put("voice", voiceDataUri)
                        }
                    }
                }
            })
        }

        val requestLog = buildString {
            append("model=$model")
            append(", stream=true")
            append(", isStandard=$isStandardTTS")
            append(", isVoiceDesign=$isVoiceDesign")
            append(", isVoiceClone=$isVoiceClone")
            if (isStandardTTS) append(", voice=${providerSetting.voice}")
            if (isVoiceDesign) append(", optimizeTextPreview=${providerSetting.optimizeTextPreview}")
            if (isVoiceClone) {
                append(", refAudioFormat=${providerSetting.referenceAudioFormat}")
                append(", refAudioMime=${formatToAudioMime(providerSetting.referenceAudioFormat)}")
                append(", refAudioLen=${providerSetting.referenceAudioBase64.length}")
            }
            append(", textLen=${request.text.length}")
        }
        Log.i(TAG, "generateSpeech(stream=True): $requestLog")

        val httpRequest = Request.Builder()
            .url(providerSetting.baseUrl)
            .addHeader("Authorization", "Bearer ${providerSetting.apiKey}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream") // SSE
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        var hasEmittedAudio = false

        // 走 SSE 流式 (httpClient.sseFlow 已封装 SSE 事件解析)
        httpClient.sseFlow(httpRequest).collect { event ->
            when (event) {
                is SseEvent.Open -> {
                    Log.d(TAG, "MIMO SSE connection opened")
                }

                is SseEvent.Event -> {
                    val raw = event.data.trim()
                    if (raw.equals("[DONE]", ignoreCase = true)) {
                        Log.d(TAG, "MIMO SSE got [DONE]")
                        return@collect
                    }
                    if (raw.isBlank()) return@collect

                    runCatching {
                        val json = JSONObject(raw)
                        val choices = json.optJSONArray("choices")
                        if (choices == null || choices.length() == 0) return@runCatching

                        val delta = choices.getJSONObject(0).optJSONObject("delta")
                            ?: return@runCatching
                        val audio = delta.optJSONObject("audio")
                            ?: return@runCatching
                        val base64Data = audio.optString("data")
                        if (base64Data.isNullOrEmpty()) return@runCatching

                        val pcmBytes = Base64.decode(base64Data, Base64.NO_WRAP)

                        // 流式 SSE: 官方 Python 示例用 np.frombuffer(..., dtype=np.int16) + samplerate=24000
                        // 所以 delta.audio.data 统一都是 PCM16LE 原始字节
                        emit(
                            AudioChunk(
                                data = pcmBytes,
                                format = AudioFormat.PCM,
                                sampleRate = MIMO_STREAM_SAMPLE_RATE,
                                isLast = false,
                                metadata = buildMap {
                                    put("provider", "mimo")
                                    put("model", model)
                                    put("stream", "true")
                                    if (isStandardTTS) put("voice", providerSetting.voice)
                                }
                            )
                        )
                        hasEmittedAudio = true
                    }.onFailure { e ->
                        Log.w(TAG, "MIMO SSE chunk parse skipped: ${e.message}, raw_preview=${raw.take(80)}")
                    }
                }

                is SseEvent.Closed -> {
                    Log.d(TAG, "MIMO SSE connection closed, emittedAudio=$hasEmittedAudio")
                    if (hasEmittedAudio) {
                        // 发送 isLast 结尾块 (与 MiniMax 模式保持一致)
                        emit(
                            AudioChunk(
                                data = byteArrayOf(),
                                format = AudioFormat.PCM,
                                sampleRate = MIMO_STREAM_SAMPLE_RATE,
                                isLast = true,
                                metadata = mapOf("provider" to "mimo", "stream" to "done")
                            )
                        )
                    } else {
                        throw Exception("MIMO SSE stream ended with no audio chunks received (可能是 429 限流或鉴权失败)")
                    }
                }

                is SseEvent.Failure -> {
                    Log.e(TAG, "MIMO SSE failure", event.throwable)
                    val parts = mutableListOf<String>()
                    event.throwable?.message?.let { parts.add(it) }
                    event.response?.code?.let { parts.add("HTTP $it") }
                    event.errorBody?.take(300)?.let { parts.add("body=$it") }
                    val msg = if (parts.isNotEmpty()) parts.joinToString(" | ") else "SSE stream failed"
                    // 关键修复：始终抛出包含 HTTP code + 错误体的异常
                    // 之前 throw event.throwable ?: Exception(...) 会丢失 msg 中的 "HTTP 429" 诊断信息
                    // 导致 TtsController 的 429 识别失效，15s 长退避不会触发
                    throw Exception("MIMO streaming failed: $msg", event.throwable)
                }
            }
        }
    }

    override suspend fun getVoices(
        context: Context,
        providerSetting: TTSProviderSetting.Mimo
    ): List<TTSVoice> {
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
