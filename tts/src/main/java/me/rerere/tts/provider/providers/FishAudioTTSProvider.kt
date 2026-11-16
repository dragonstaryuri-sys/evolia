package me.rerere.tts.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.model.TTSVoice
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private const val TAG = "FishAudioTTSProvider"

/**
 * Fish Audio TTS Provider。
 *
 * 官方 API：
 * - 合成：POST https://api.fish.audio/v1/tts（模型通过 `model` 请求头传递）
 * - 音色（模型）列表：GET https://api.fish.audio/model
 *
 * 音频输出固定为 PCM：Fish Audio 的 `format=pcm` 返回 16-bit 小端单声道裸 PCM（无容器头），
 * 采样率显式指定 24kHz（官方支持 8k/16k/24k/32k/44.1k），与项目内其他 24kHz Provider 保持一致。
 */
class FishAudioTTSProvider : TTSProvider<TTSProviderSetting.FishAudio> {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.fish.audio"

        /** 官方 TTS 模型（`model` 请求头枚举值） */
        val presetModels = listOf(
            "s2.1-pro",
            "s2.1-pro-free",
            "s2-pro",
            "drama-3",
            "s1"
        )

        /** 输出 PCM 采样率：官方支持 8k/16k/24k/32k/44.1k */
        const val OUTPUT_SAMPLE_RATE = 24000

        private const val PAGE_SIZE = 100
        private const val MAX_OWN_PAGES = 5
        private const val PUBLIC_PAGE_SIZE = 100
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        // 官方列表接口的可选字段可能返回 null，按默认值处理，避免整页解析失败
        coerceInputValues = true
    }

    // ======================== 语音合成 ========================

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.FishAudio,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val model = providerSetting.model.ifBlank { presetModels.first() }
        val voiceId = providerSetting.voiceId.trim()
        val speed = providerSetting.speed.coerceIn(0.5f, 2.0f)

        val requestBody = buildString {
            append("{")
            append("\"text\":").append(jsonString(request.text)).append(",")
            if (voiceId.isNotBlank()) {
                append("\"reference_id\":").append(jsonString(voiceId)).append(",")
            }
            append("\"format\":\"pcm\",")
            append("\"sample_rate\":").append(OUTPUT_SAMPLE_RATE).append(",")
            append("\"normalize\":true,")
            append("\"prosody\":{\"speed\":").append(speed).append("}")
            append("}")
        }

        Log.i(
            TAG,
            "generateSpeech: model=$model, voiceId=${voiceId.ifBlank { "(默认音色)" }}, " +
                "speed=$speed, textLength=${request.text.length}"
        )

        val httpRequest = Request.Builder()
            .url("$DEFAULT_BASE_URL/v1/tts")
            .addHeader("Authorization", "Bearer ${providerSetting.apiKey}")
            .addHeader("Content-Type", "application/json")
            .addHeader("model", model)
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception(buildHttpError("TTS", response.code, response.body.string()))
            }

            val audioData = response.body.bytes()
            if (audioData.isEmpty()) {
                throw Exception("Fish Audio TTS 返回了空音频数据")
            }

            emit(
                AudioChunk(
                    data = audioData,
                    format = AudioFormat.PCM,
                    sampleRate = OUTPUT_SAMPLE_RATE,
                    isLast = true,
                    metadata = mapOf(
                        "provider" to "fishaudio",
                        "model" to model,
                        "voice" to voiceId,
                        "speed" to speed.toString()
                    )
                )
            )
        }
    }

    // ======================== 音色列表 ========================

    /**
     * 拉取音色列表：
     * 1. 当前账号/工作空间自己的音色（`self=true`，按官方分页参数翻页，最多 [MAX_OWN_PAGES] 页）；
     * 2. 公共音色首页（无 `self`），用于账号下还没有音色时也能选择；
     * 3. 按模型 ID 去重，自己的音色优先展示。
     *
     * 任一请求失败时返回已成功获取的部分；API Key 为空返回空列表。
     */
    override suspend fun getVoices(
        context: Context,
        providerSetting: TTSProviderSetting.FishAudio
    ): List<TTSVoice> = withContext(Dispatchers.IO) {
        if (providerSetting.apiKey.isBlank()) {
            Log.i(TAG, "getVoices: apiKey 为空，跳过请求")
            return@withContext emptyList()
        }

        val voices = LinkedHashMap<String, TTSVoice>()
        var lastError: Throwable? = null

        // 1. 自己的音色：分页拉取
        var page = 1
        var hasMore = true
        while (hasMore && page <= MAX_OWN_PAGES) {
            val result = runCatching {
                fetchModelPage(providerSetting.apiKey, page, PAGE_SIZE, self = true)
            }.onFailure {
                lastError = it
                Log.w(TAG, "getVoices: 拉取自有音色第 $page 页失败", it)
            }.getOrNull() ?: break

            result.items.forEach { model ->
                val voice = model.toTTSVoice(isOwn = true)
                if (voice != null) voices.putIfAbsent(voice.id, voice)
            }
            hasMore = result.hasMore ?: (
                result.items.size >= PAGE_SIZE && page.toLong() * PAGE_SIZE < result.total
            )
            if (result.items.isEmpty()) hasMore = false
            page++
        }

        // 2. 公共音色首页（失败不影响自有音色展示）
        runCatching {
            fetchModelPage(providerSetting.apiKey, 1, PUBLIC_PAGE_SIZE, self = false)
        }.onFailure {
            lastError = it
            Log.w(TAG, "getVoices: 拉取公共音色失败", it)
        }.getOrNull()?.items?.forEach { model ->
            val voice = model.toTTSVoice(isOwn = false)
            if (voice != null) voices.putIfAbsent(voice.id, voice)
        }

        // 两个请求都失败时把明确错误交给 UI 展示（UI 已捕获，不会导致页面崩溃）
        if (voices.isEmpty()) {
            lastError?.let { throw Exception(it.message ?: "获取 Fish Audio 音色列表失败") }
        }

        Log.i(TAG, "getVoices: 共获取 ${voices.size} 个音色")
        voices.values.toList()
    }

    private fun fetchModelPage(
        apiKey: String,
        pageNumber: Int,
        pageSize: Int,
        self: Boolean
    ): FishAudioModelPage {
        val url = "$DEFAULT_BASE_URL/model".toHttpUrl().newBuilder()
            .addQueryParameter("page_number", pageNumber.toString())
            .addQueryParameter("page_size", pageSize.toString())
            .addQueryParameter("self", self.toString())
            .build()

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw Exception(buildHttpError("获取音色列表", response.code, body))
            }
            return json.decodeFromString<FishAudioModelPage>(body)
        }
    }

    private fun FishAudioModelEntity.toTTSVoice(isOwn: Boolean): TTSVoice? {
        if (id.isBlank()) return null
        if (dmcaTakenDown == true) return null
        val visibilityLabel = if (isOwn) {
            if (visibility == "public") "我的音色（公开）" else "我的音色"
        } else {
            "公共音色"
        }
        val desc = description.trim().ifBlank { visibilityLabel }
        return TTSVoice(
            id = id,
            name = title.trim().ifBlank { id },
            locale = languages.firstOrNull()?.ifBlank { null },
            description = desc,
            styles = tags
        )
    }

    private fun buildHttpError(action: String, code: Int, body: String): String {
        val detail = runCatching {
            val parsed = json.decodeFromString<FishAudioErrorResponse>(body)
            listOfNotNull(parsed.message, parsed.reason)
                .filter { it.isNotBlank() }
                .joinToString(" | ")
                .ifBlank { body.take(300) }
        }.getOrElse { body.take(300) }

        val hint = when (code) {
            401, 403 -> "（API Key 无效或无权限）"
            402 -> "（当前模型需要付费额度，可改用 s2.1-pro-free）"
            429 -> "（请求过于频繁，请稍后重试）"
            else -> ""
        }
        return "Fish Audio $action 失败: HTTP $code$hint $detail"
    }

    /** 仅用于拼接请求体，避免手写字符串转义错误 */
    private fun jsonString(value: String): String = JsonPrimitive(value).toString()

    // ======================== 响应结构 ========================

    @Serializable
    private data class FishAudioModelPage(
        val items: List<FishAudioModelEntity> = emptyList(),
        val total: Long = 0L,
        @SerialName("has_more")
        val hasMore: Boolean? = null
    )

    @Serializable
    private data class FishAudioModelEntity(
        @SerialName("_id")
        val id: String = "",
        val title: String = "",
        val description: String = "",
        val visibility: String = "",
        val languages: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
        @SerialName("dmca_taken_down")
        val dmcaTakenDown: Boolean? = null
    )

    @Serializable
    private data class FishAudioErrorResponse(
        val message: String = "",
        val reason: String = ""
    )
}
