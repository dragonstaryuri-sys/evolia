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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

private const val TAG = "MimoTTSProvider"

/**
 * 参考音频格式 → Data URI MIME 子类型
 * 严格对齐官方 Python 示例和 GitHub mimo_tts_example.py:
 *   mp3 -> audio/mpeg
 *   wav -> audio/wav
 *   flac -> audio/flac
 *   ogg -> audio/ogg
 *   m4a -> audio/mp4 (标准 MIME，服务端白名单虽写 m4a 但通过 audio/mp4 识别 m4a 内容)
 */
private fun referenceAudioMime(format: String): String = when (format.trim().lowercase()) {
    "mp3", "mpeg" -> "audio/mpeg"
    "wav", "wave" -> "audio/wav"
    "flac" -> "audio/flac"
    "ogg", "oga" -> "audio/ogg"
    "m4a", "aac", "mp4" -> "audio/mp4"
    else -> "audio/mpeg"
}

/**
 * 非流式 voiceclone 返回的 audio.data base64 解码后是一个**完整 WAV 文件** (官方 Python
 * 示例直接把 bytes 写到 audio_file.wav)。这里解析 RIFF header 提取出 raw PCM payload
 * 和真正的 sampleRate，交给后续 AudioChunk 直接播放。
 *
 * 如果 bytes 不带 RIFF header (比如是 raw PCM)，就原样返回 + fallback 采样率。
 */
private data class WavExtract(
    val pcm: ByteArray,
    val sampleRate: Int,
    val fromWav: Boolean
)

private fun extractPcmFromMaybeWav(bytes: ByteArray, fallbackSampleRate: Int): WavExtract {
    if (bytes.size < 44) {
        return WavExtract(bytes, fallbackSampleRate, false)
    }
    val riff = String(bytes, 0, 4, Charsets.US_ASCII)
    val wave = String(bytes, 8, 4, Charsets.US_ASCII)
    if (riff != "RIFF" || wave != "WAVE") {
        return WavExtract(bytes, fallbackSampleRate, false)
    }

    var offset = 12
    var dataOffset = -1
    var dataSize = 0
    var sampleRate = fallbackSampleRate
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    // 遍历子 chunk 找 fmt (读 sampleRate) 和 data (取 payload)
    while (offset + 8 <= bytes.size) {
        val chunkId = String(bytes, offset, 4, Charsets.US_ASCII)
        val chunkSize = buf.getInt(offset + 4)
        val chunkDataStart = offset + 8
        if (chunkId == "fmt " && chunkDataStart + 12 <= bytes.size) {
            // fmt chunk: bytes[0:2] audioFormat, bytes[2:4] channels, bytes[4:8] sampleRate
            sampleRate = buf.getInt(chunkDataStart + 4)
        } else if (chunkId == "data") {
            dataOffset = chunkDataStart
            dataSize = chunkSize
        }
        // 对齐到 2 字节
        val alignedSize = if (chunkSize % 2 == 1) chunkSize + 1 else chunkSize
        offset = chunkDataStart + alignedSize
        if (chunkId == "data") break
    }

    if (dataOffset < 0 || dataSize <= 0) {
        return WavExtract(bytes, fallbackSampleRate, false)
    }
    val end = (dataOffset + dataSize).coerceAtMost(bytes.size)
    val pcm = bytes.copyOfRange(dataOffset, end)
    return WavExtract(pcm, sampleRate, true)
}

class MimoTTSProvider : TTSProvider<TTSProviderSetting.Mimo> {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    val presetModels = listOf(
        "mimo-v2.5-tts",
        "mimo-v2.5-tts-voicedesign",
        "mimo-v2.5-tts-voiceclone"
    )

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

        // ========== 构造 messages ==========
        val messages = JSONArray()
        when {
            isVoiceDesign -> {
                val designPrompt = providerSetting.voiceDesignPrompt.ifBlank {
                    "default warm female voice"
                }
                messages.put(JSONObject().apply {
                    put("role", "user")
                    put("content", designPrompt)
                })
                messages.put(JSONObject().apply {
                    put("role", "assistant")
                    put("content", request.text)
                })
            }

            isVoiceClone -> {
                // 严格对齐官方 + GitHub 示例: user="", assistant=合成文本
                messages.put(JSONObject().apply {
                    put("role", "user")
                    put("content", "")
                })
                messages.put(JSONObject().apply {
                    put("role", "assistant")
                    put("content", request.text)
                })
            }

            else -> {
                messages.put(JSONObject().apply {
                    put("role", "user")
                    put("content", "")
                })
                messages.put(JSONObject().apply {
                    put("role", "assistant")
                    put("content", request.text)
                })
            }
        }

        // ========== 构造 audio 对象 ==========
        val audio = JSONObject()
        val refFormat = providerSetting.referenceAudioFormat.trim().lowercase()
        val refMime = referenceAudioMime(refFormat)
        when {
            isStandardTTS -> {
                audio.put("format", "pcm16")
                audio.put("voice", providerSetting.voice)
            }
            isVoiceDesign -> {
                // 官方/GitHub 示例 voicedesign 使用 format=wav (非 pcm16)
                audio.put("format", "wav")
                audio.put("optimize_text_preview", providerSetting.optimizeTextPreview)
            }
            isVoiceClone -> {
                // 严格对齐官方/GitHub 示例: 无论参考音频格式，response audio.format 固定为 "wav"
                audio.put("format", "wav")
                if (providerSetting.referenceAudioBase64.isNotBlank()) {
                    val voiceDataUri = "data:$refMime;base64,${providerSetting.referenceAudioBase64}"
                    audio.put("voice", voiceDataUri)
                }
            }
        }

        // ========== 先尝试 SSE 流式 (stream=True) ==========
        val streamBody = JSONObject().apply {
            put("model", model)
            put("stream", true)
            put("messages", messages)
            put("audio", audio)
        }

        // 打印完整请求结构 (voice 截断便于排查)，仅打印一次
        val debugBody = JSONObject(streamBody.toString())
        if (isVoiceClone) {
            val debugAudio = debugBody.optJSONObject("audio")
            if (debugAudio != null) {
                val v = debugAudio.optString("voice")
                if (v.isNotBlank()) {
                    debugAudio.put("voice", v.take(80) + "...[total ${v.length} chars]")
                }
            }
        }
        val requestPreview = debugBody.toString(2)
        Log.i(TAG, "═══════════════════════════════════════")
        Log.i(TAG, "MiMo FULL REQUEST PREVIEW:\n$requestPreview")
        Log.i(TAG, "═══════════════════════════════════════")

        val authHeader = "Bearer ${providerSetting.apiKey}"

        suspend fun buildHttpRequest(stream: Boolean, body: JSONObject): Request {
            return Request.Builder()
                .url(providerSetting.baseUrl)
                .addHeader("Authorization", authHeader)
                .addHeader("Content-Type", "application/json")
                .apply { if (stream) addHeader("Accept", "text/event-stream") }
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
        }

        var emitted = false
        var sseError: Exception? = null

        // ========== 根据模型类型选择请求策略 ==========
        // 实测:
        //   - voiceclone + stream=true  →  HTTP 400 "invalid audio format" (服务器 SSE 路径参数校验不兼容)
        //   - voiceclone + stream=false →  HTTP 429 (参数校验通过，只是限流)
        //   - standard mimo-v2.5-tts + stream=true  →  按官方文档正常工作
        //   - voicedesign + stream=true →  正常工作 (用户确认 voicedesign 没问题)
        // 所以只有 voiceclone 直接走 non-stream，其他都走 SSE
        val useStreaming = !isVoiceClone

        if (useStreaming) {
            // ---- Path 1: SSE streaming (仅标准 mimo-v2.5-tts) ----
            try {
                val httpReq = buildHttpRequest(true, streamBody)
                httpClient.sseFlow(httpReq).collect { event ->
                    when (event) {
                        is SseEvent.Open -> Log.d(TAG, "MIMO SSE opened")

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
                                    ?: return@runCatching
                                if (choices.length() == 0) return@runCatching
                                val delta = choices.getJSONObject(0).optJSONObject("delta")
                                    ?: return@runCatching
                                val aud = delta.optJSONObject("audio")
                                    ?: return@runCatching
                                val b64 = aud.optString("data")
                                if (b64.isNullOrEmpty()) return@runCatching
                                val pcm = Base64.decode(b64, Base64.NO_WRAP)
                                emit(AudioChunk(
                                    data = pcm,
                                    format = AudioFormat.PCM,
                                    sampleRate = MIMO_STREAM_SAMPLE_RATE,
                                    isLast = false,
                                    metadata = mapOf("provider" to "mimo", "model" to model, "stream" to "true")
                                ))
                                emitted = true
                            }.onFailure { e ->
                                Log.w(TAG, "MIMO SSE chunk skipped: ${e.message}, raw_head=${raw.take(60)}")
                            }
                        }

                        is SseEvent.Closed -> {
                            Log.d(TAG, "MIMO SSE closed, emitted=$emitted")
                            if (emitted) {
                                emit(AudioChunk(
                                    data = byteArrayOf(),
                                    format = AudioFormat.PCM,
                                    sampleRate = MIMO_STREAM_SAMPLE_RATE,
                                    isLast = true,
                                    metadata = mapOf("provider" to "mimo", "stream" to "done")
                                ))
                            } else {
                                sseError = Exception("SSE stream ended with no audio chunks")
                            }
                        }

                        is SseEvent.Failure -> {
                            val parts = mutableListOf<String>()
                            event.throwable?.message?.let { parts.add(it) }
                            event.response?.code?.let { parts.add("HTTP $it") }
                            event.errorBody?.take(500)?.let { parts.add("body=$it") }
                            val msg = if (parts.isNotEmpty()) parts.joinToString(" | ") else "SSE stream failed"
                            Log.e(TAG, "MIMO SSE FAILURE: $msg", event.throwable)
                            sseError = Exception("MIMO SSE failed: $msg", event.throwable)
                        }
                    }
                }
            } catch (e: Exception) {
                if (sseError == null) sseError = e
            }

            // ---- SSE 有音频产出就直接结束 ----
            if (emitted) return@flow

            // ---- SSE 失败，fallback 到 non-stream (至少把错误带过去) ----
            val lastMsg = sseError?.message ?: "(no streaming error)"
            Log.w(TAG, "SSE failed → fallback to non-stream. SSE error: $lastMsg")
        } else {
            Log.i(TAG, "Non-stream request directly (voiceclone skips SSE per server behavior)")
        }

        // ========== Non-streaming 请求 (voiceclone 直连，或 standard/voicedesign SSE 失败时) ==========
        // 注意：这里故意 *不* 写 stream 字段，完全匹配官方 Python 示例的默认值
        val nonStreamBody = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("audio", audio)
        }

        try {
            val nonStreamReq = buildHttpRequest(false, nonStreamBody)
            val resp = withContext(Dispatchers.IO) { httpClient.newCall(nonStreamReq).execute() }
            val bodyStr = resp.body?.string().orEmpty()
            Log.i(TAG, "Non-stream response: HTTP ${resp.code}, body_head=${bodyStr.take(400)}")

            if (!resp.isSuccessful) {
                throw Exception("Non-stream failed: HTTP ${resp.code} | $bodyStr")
            }

            val json = JSONObject(bodyStr)
            val choices = json.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                throw Exception("Non-stream response has no choices: ${bodyStr.take(500)}")
            }
            val message = choices.getJSONObject(0).optJSONObject("message")
                ?: throw Exception("Non-stream choices[0] missing 'message': ${bodyStr.take(500)}")
            val aud = message.optJSONObject("audio")
                ?: throw Exception("Non-stream message missing 'audio' field: ${bodyStr.take(500)}")
            val b64 = aud.optString("data")
            if (b64.isNullOrEmpty()) {
                throw Exception("Non-stream audio.data empty: ${bodyStr.take(500)}")
            }
            val rawBytes = Base64.decode(b64, Base64.NO_WRAP)
            // 官方 Python 示例直接把 bytes 写到 audio_file.wav，说明这里返回的是
            // 带 RIFF header 的完整 WAV 文件。提取 raw PCM 再喂给播放器。
            val extracted = extractPcmFromMaybeWav(rawBytes, MIMO_STREAM_SAMPLE_RATE)
            Log.i(TAG, "Non-stream audio: total ${rawBytes.size} bytes, " +
                "fromWav=${extracted.fromWav}, sampleRate=${extracted.sampleRate}, " +
                "pcm=${extracted.pcm.size} bytes")

            emit(AudioChunk(
                data = extracted.pcm,
                format = AudioFormat.PCM,
                sampleRate = extracted.sampleRate,
                isLast = false,
                metadata = mapOf(
                    "provider" to "mimo",
                    "model" to model,
                    "stream" to "false",
                    "fromWav" to extracted.fromWav.toString()
                )
            ))
            emit(AudioChunk(
                data = byteArrayOf(),
                format = AudioFormat.PCM,
                sampleRate = extracted.sampleRate,
                isLast = true,
                metadata = mapOf("provider" to "mimo", "stream" to "done")
            ))
            emitted = true
        } catch (e: Exception) {
            Log.e(TAG, "Non-stream request failed", e)
            val combined = buildString {
                if (sseError != null) {
                    append("Both SSE and non-stream failed. ")
                    append("SSE: ${sseError!!.message?.take(200)}. ")
                } else {
                    append("Non-stream request failed. ")
                }
                append("Error: ${e.message?.take(300) ?: "(none)"}")
            }
            throw Exception(combined, sseError ?: e)
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
