package me.rerere.tts.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

private const val TAG = "MiniMaxTTSProvider"

// ======================== 响应数据类 ========================

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

@Serializable
private data class MiniMaxBaseResp(
    val status_code: Long = 0L,
    val status_msg: String = "success"
)

/** 上传文件响应 */
@Serializable
private data class MiniMaxFileUploadResponse(
    val file: MiniMaxFileInfo? = null,
    val base_resp: MiniMaxBaseResp = MiniMaxBaseResp()
)

@Serializable
private data class MiniMaxFileInfo(
    val file_id: Long = 0L,
    val file_name: String = "",
    val purpose: String = ""
)

/** 音色设计响应 */
@Serializable
private data class MiniMaxVoiceDesignResponse(
    val voice_id: String = "",
    val trial_audio: String = "",
    val base_resp: MiniMaxBaseResp = MiniMaxBaseResp()
)

/** 音色复刻响应 */
@Serializable
private data class MiniMaxVoiceCloneResponse(
    val input_sensitive: Boolean = false,
    val input_sensitive_type: Int = 0,
    val demo_audio: String = "",
    val base_resp: MiniMaxBaseResp = MiniMaxBaseResp()
)

/**
 * /v1/get_voice 通用响应
 * 所有列表字段都声明为 JsonElement?，因为 MiniMax 有时会显式返回 null 而非 []，
 * 必须先拿到原始 JSON 节点再手动解析成 List<MiniMaxVoiceItem>，避免 JsonDecodingException。
 */
@Serializable
private data class MiniMaxGetVoiceResponse(
    val system_voice: JsonElement? = null,
    val voice_cloning: JsonElement? = null,
    val voice_generation: JsonElement? = null,
    val base_resp: MiniMaxBaseResp = MiniMaxBaseResp()
)

/**
 * system_voice / voice_cloning / voice_generation 三个数组里的元素结构基本一致，
 * 只是 description、voice_name 等字段偶尔会缺省，用这个统一结构。
 */
@Serializable
private data class MiniMaxVoiceItem(
    val voice_id: String = "",
    val voice_name: String = "",
    val description: JsonElement? = null,
    val created_time: String = ""
)

/** 对外暴露的简化音色条目 */
data class MiniMaxSimpleVoice(
    val voiceId: String,
    val voiceName: String,
    val description: String,
    val createdTime: String
)

// ======================== Provider 实现 ========================

class MiniMaxTTSProvider : TTSProvider<TTSProviderSetting.MiniMax> {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ======================== TTS 生成 ========================

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.MiniMax,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        // 根据音色类型决定实际使用的 voice_id
        val actualVoiceId = when (providerSetting.voiceType) {
            TTSProviderSetting.MiniMaxVoiceType.DEFAULT -> providerSetting.voiceId
            TTSProviderSetting.MiniMaxVoiceType.DESIGN -> providerSetting.designedVoiceId.ifBlank {
                throw IllegalStateException("尚未完成音色设计，请先在提供商编辑页面生成音色 (voice_id 为空)")
            }
            TTSProviderSetting.MiniMaxVoiceType.CLONE -> providerSetting.cloneVoiceId.ifBlank {
                throw IllegalStateException("尚未完成音色复刻，请先在提供商编辑页面完成复刻 (clone_voice_id 为空)")
            }
        }

        val requestBody = buildJsonObject {
            put("model", providerSetting.model)
            put("text", request.text)
            put("stream", true)
            put("output_format", "hex")
            put("stream_options", buildJsonObject {
                put("exclude_aggregated_audio", true)
            })
            put("voice_setting", buildJsonObject {
                put("voice_id", actualVoiceId)
                put("emotion", providerSetting.emotion)
                put("speed", providerSetting.speed)
            })
        }

        Log.i(
            TAG,
            "generateSpeech: model=${providerSetting.model}, " +
                "voiceType=${providerSetting.voiceType}, " +
                "voiceId=$actualVoiceId, emotion=${providerSetting.emotion}, " +
                "textLength=${request.text.length}"
        )

        val urlBuilder = StringBuilder("${providerSetting.baseUrl}/t2a_v2")
        if (providerSetting.groupId.isNotBlank()) {
            urlBuilder.append("?GroupId=${providerSetting.groupId}")
        }

        val httpRequest = Request.Builder()
            .url(urlBuilder.toString())
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
                                format = AudioFormat.MP3,
                                sampleRate = 32000,
                                isLast = false,
                                metadata = mapOf(
                                    "provider" to "minimax",
                                    "model" to providerSetting.model,
                                    "voice" to actualVoiceId,
                                    "voiceType" to providerSetting.voiceType.name,
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
                    if (hasEmittedAudio) {
                        emit(
                            AudioChunk(
                                data = byteArrayOf(),
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
        // 没有 API Key 时使用内置列表兜底
        if (providerSetting.apiKey.isBlank()) {
            Log.i(TAG, "getVoices: apiKey 为空，使用内置音色列表兜底")
            return buildInMiniMaxVoices()
        }

        return runCatching { fetchFullVoiceBundle(providerSetting).systemVoices }
            .onFailure { Log.e(TAG, "getVoices: 拉取在线音色列表失败，回退内置列表", it) }
            .getOrElse { buildInMiniMaxVoices() }
    }

    /**
     * 拉取用户已有的【音色设计(voice_generation)】音色列表
     */
    suspend fun getVoiceGeneration(
        providerSetting: TTSProviderSetting.MiniMax
    ): List<MiniMaxSimpleVoice> {
        if (providerSetting.apiKey.isBlank()) return emptyList()
        return runCatching { fetchFullVoiceBundle(providerSetting).generationVoices }
            .onFailure { Log.e(TAG, "getVoiceGeneration 失败", it) }
            .getOrElse { emptyList() }
    }

    /**
     * 拉取用户已有的【音色复刻(voice_cloning)】音色列表
     * 注意：快速复刻得到的音色为未激活状态，需正式调用一次才可在本接口查询到
     */
    suspend fun getVoiceCloning(
        providerSetting: TTSProviderSetting.MiniMax
    ): List<MiniMaxSimpleVoice> {
        if (providerSetting.apiKey.isBlank()) return emptyList()
        return runCatching { fetchFullVoiceBundle(providerSetting).cloningVoices }
            .onFailure { Log.e(TAG, "getVoiceCloning 失败", it) }
            .getOrElse { emptyList() }
    }

    /** 通用情感列表：speech-2.x 模型的 voice_setting.emotion 通用支持的情感值 */
    private val commonEmotions = listOf("calm", "happy", "sad", "angry", "fearful", "disgusted", "surprised")

    private data class VoiceBundle(
        val systemVoices: List<TTSVoice>,
        val generationVoices: List<MiniMaxSimpleVoice>,
        val cloningVoices: List<MiniMaxSimpleVoice>
    )

    /**
     * 统一调用 /v1/get_voice voice_type=all，一次性拿到 system / generation / cloning 三份列表，
     * 避免重复 HTTP 请求，各调用方按分类取自己那份。
     */
    private suspend fun fetchFullVoiceBundle(
        providerSetting: TTSProviderSetting.MiniMax
    ): VoiceBundle = withContext(Dispatchers.IO) {
        val urlBuilder = StringBuilder("${providerSetting.baseUrl}/get_voice")
        if (providerSetting.groupId.isNotBlank()) {
            urlBuilder.append("?GroupId=${providerSetting.groupId}")
        }

        val requestBody = buildJsonObject {
            put("voice_type", "all")
        }

        val request = Request.Builder()
            .url(urlBuilder.toString())
            .addHeader("Authorization", "Bearer ${providerSetting.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .build()

        Log.i(TAG, "fetchFullVoiceBundle: calling $urlBuilder")

        val response = httpClient.newCall(request).execute()
        val bodyStr = response.body.string()
        if (!response.isSuccessful) {
            throw Exception("MiniMax get_voice failed: HTTP ${response.code}, body=$bodyStr")
        }

        val parsed = json.decodeFromString<MiniMaxGetVoiceResponse>(bodyStr)
        if (parsed.base_resp.status_code != 0L) {
            throw Exception("MiniMax get_voice error: ${parsed.base_resp.status_msg} (code=${parsed.base_resp.status_code})")
        }

        // ---- 三份数组分别安全解析 ----
        val systemItems = parseVoiceItemArray(parsed.system_voice)
        val generationItems = parseVoiceItemArray(parsed.voice_generation)
        val cloningItems = parseVoiceItemArray(parsed.voice_cloning)

        // 系统音色 -> TTSVoice（预置音色选择器用）
        val systemTTS = systemItems.mapNotNull { item ->
            if (item.voiceId.isBlank()) return@mapNotNull null
            val gender = inferGender(item.voiceId, item.voiceName, item.description)
            val locale = inferLocale(item.voiceId, item.voiceName, item.description)
            TTSVoice(
                id = item.voiceId,
                name = item.voiceName.ifBlank { item.voiceId },
                locale = locale,
                gender = gender,
                description = item.description,
                styles = commonEmotions
            )
        }

        Log.i(
            TAG,
            "fetchFullVoiceBundle: system=${systemTTS.size}, generation=${generationItems.size}, cloning=${cloningItems.size}"
        )
        VoiceBundle(
            systemVoices = systemTTS.ifEmpty { buildInMiniMaxVoices() },
            generationVoices = generationItems,
            cloningVoices = cloningItems
        )
    }

    /** 从 JsonElement 里安全地提取字符串：必须是 JsonPrimitive 且 isString */
    private fun JsonElement?.safeString(): String {
        if (this == null) return ""
        if (this is JsonPrimitive && this.isString) return this.content
        return ""
    }

    /**
     * 将 MiniMax 返回的 JsonElement?(null 或 array) 解析成 List<MiniMaxSimpleVoice>
     */
    private fun parseVoiceItemArray(element: JsonElement?): List<MiniMaxSimpleVoice> {
        if (element == null || element !is JsonArray) return emptyList()
        return element.mapNotNull { node ->
            if (node !is kotlinx.serialization.json.JsonObject) return@mapNotNull null
            val obj = node.jsonObject
            val voiceId = obj["voice_id"].safeString()
            if (voiceId.isBlank()) return@mapNotNull null
            val voiceName = obj["voice_name"].safeString()
            val createdTime = obj["created_time"].safeString()
            val desc = parseDescription(obj["description"])
            MiniMaxSimpleVoice(
                voiceId = voiceId,
                voiceName = voiceName.ifBlank { voiceId },
                description = desc,
                createdTime = createdTime
            )
        }
    }

    /** 兼容 description 为 null / ["xxx"] / 纯字符串三种情况 */
    private fun parseDescription(descEl: JsonElement?): String = when {
        descEl == null -> ""
        descEl is JsonArray -> descEl.firstOrNull()
            ?.let { if (it is JsonPrimitive && it.isString) it.content else null }
            .orEmpty()
        descEl is JsonPrimitive && descEl.isString -> descEl.content
        else -> ""
    }

    /** 内置兜底音色列表（和原来的一致） */
    private fun buildInMiniMaxVoices(): List<TTSVoice> {
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

    /**
     * 根据 voice_id 关键词 + voice_name / description 文本推断性别
     */
    private fun inferGender(voiceId: String, voiceName: String, description: String): String {
        val idLower = voiceId.lowercase()
        val combined = "$voiceName $description"

        // 1. voice_id 精确关键词（官方命名，优先级最高）
        if (idLower.contains("female")) return "Female"
        if (idLower.contains("male")) return "Male"

        // 2. 中文文本关键词匹配
        val femaleRegex = Regex("女性|女生|女孩|少女|御姐|女士|姐姐|阿姨|妈妈|女声|她|主播|新闻女")
        val maleRegex = Regex("男性|男生|男孩|少年|青年|先生|哥哥|叔叔|爸爸|男声|他|总裁|高管|播音员|老先生|弟弟")

        if (femaleRegex.containsMatchIn(combined)) return "Female"
        if (maleRegex.containsMatchIn(combined)) return "Male"

        // 3. 兜底：Unknown（在 UI 的 "自定义" 分类下显示）
        return "Unknown"
    }

    /**
     * 粗略推断 locale：含 Chinese/Mandarin/中文 关键词或全中文名字 => zh-CN，其他暂时默认 zh-CN
     */
    private fun inferLocale(voiceId: String, voiceName: String, description: String): String {
        val combined = "$voiceId $voiceName $description"
        val hasChineseMark = combined.contains(Regex("Chinese|Mandarin|普通话|中文"))
        val hasChineseChar = combined.any { it.code in 0x4E00..0x9FFF }
        return if (hasChineseMark || hasChineseChar) "zh-CN" else "en-US"
    }

    // ======================== 文件上传 ========================

    /**
     * 上传文件到 MiniMax File 接口
     *
     * @param providerSetting MiniMax 配置（含 apiKey / groupId / baseUrl）
     * @param file 本地文件
     * @param purpose 用途："voice_clone"（复刻音频）或 "prompt_audio"（示例音频）
     * @return file_id
     */
    suspend fun uploadFile(
        providerSetting: TTSProviderSetting.MiniMax,
        file: File,
        purpose: String
    ): Long = withContext(Dispatchers.IO) {
        require(purpose == "voice_clone" || purpose == "prompt_audio") {
            "purpose 仅支持 voice_clone 或 prompt_audio"
        }

        val urlBuilder = StringBuilder("${providerSetting.baseUrl}/files/upload")
        if (providerSetting.groupId.isNotBlank()) {
            urlBuilder.append("?GroupId=${providerSetting.groupId}")
        }

        val mimeType = when (file.extension.lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("purpose", purpose)
            .addFormDataPart(
                "file",
                file.name,
                file.asRequestBody(mimeType.toMediaTypeOrNull())
            )
            .build()

        val request = Request.Builder()
            .url(urlBuilder.toString())
            .addHeader("Authorization", "Bearer ${providerSetting.apiKey}")
            .post(requestBody)
            .build()

        Log.i(TAG, "uploadFile: purpose=$purpose, file=${file.name}, size=${file.length()}")

        val response = httpClient.newCall(request).execute()
        val bodyStr = response.body.string()
        if (!response.isSuccessful) {
            throw Exception("MiniMax uploadFile failed: HTTP ${response.code}, body=$bodyStr")
        }

        val parsed = runCatching { json.decodeFromString<MiniMaxFileUploadResponse>(bodyStr) }
            .getOrElse {
                // fallback：手动解析 JSON
                val element = json.parseToJsonElement(bodyStr)
                val fileObj = element.jsonObject["file"]?.jsonObject
                val fileId = fileObj?.get("file_id")?.jsonPrimitive?.long ?: 0L
                MiniMaxFileUploadResponse(
                    file = MiniMaxFileInfo(file_id = fileId),
                    base_resp = MiniMaxBaseResp()
                )
            }

        if (parsed.base_resp.status_code != 0L) {
            throw Exception("MiniMax uploadFile error: ${parsed.base_resp.status_msg} (code=${parsed.base_resp.status_code})")
        }

        val fileId = parsed.file?.file_id ?: 0L
        if (fileId == 0L) {
            throw Exception("MiniMax uploadFile 返回的 file_id 为空")
        }
        Log.i(TAG, "uploadFile success: file_id=$fileId")
        fileId
    }

    // ======================== 音色设计 ========================

    /**
     * 调用 MiniMax Voice Design 接口生成个性化音色
     *
     * @return Pair(voice_id, trial_audio_hex)
     */
    suspend fun voiceDesign(
        providerSetting: TTSProviderSetting.MiniMax
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        require(providerSetting.designPrompt.isNotBlank()) { "音色描述 prompt 不能为空" }
        require(providerSetting.designPreviewText.isNotBlank()) { "试听文本不能为空" }

        val url = "${providerSetting.baseUrl}/voice_design"
        val requestBody = buildJsonObject {
            put("prompt", providerSetting.designPrompt)
            put("preview_text", providerSetting.designPreviewText)
            if (providerSetting.groupId.isNotBlank()) {
                put("GroupId", providerSetting.groupId)
            }
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${providerSetting.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .build()

        Log.i(
            TAG,
            "voiceDesign: promptLen=${providerSetting.designPrompt.length}, " +
                "previewLen=${providerSetting.designPreviewText.length}"
        )

        val response = httpClient.newCall(request).execute()
        val bodyStr = response.body.string()
        if (!response.isSuccessful) {
            throw Exception("MiniMax voiceDesign failed: HTTP ${response.code}, body=$bodyStr")
        }

        val parsed = json.decodeFromString<MiniMaxVoiceDesignResponse>(bodyStr)
        if (parsed.base_resp.status_code != 0L) {
            throw Exception("MiniMax voiceDesign error: ${parsed.base_resp.status_msg} (code=${parsed.base_resp.status_code})")
        }
        if (parsed.voice_id.isBlank()) {
            throw Exception("MiniMax voiceDesign 返回的 voice_id 为空")
        }

        Log.i(TAG, "voiceDesign success: voice_id=${parsed.voice_id}, trialAudioLen=${parsed.trial_audio.length}")
        Pair(parsed.voice_id, parsed.trial_audio)
    }

    // ======================== 音色复刻 ========================

    /**
     * 调用 MiniMax Voice Clone 接口进行快速音色复刻
     *
     * @return 试听音频 demo_audio 的 URL（如果传了 text+model 的话）
     */
    suspend fun voiceClone(
        providerSetting: TTSProviderSetting.MiniMax
    ): String = withContext(Dispatchers.IO) {
        require(providerSetting.cloneFileId != 0L) { "请先上传复刻音频 (clone_file_id 为空)" }
        require(providerSetting.cloneVoiceId.isNotBlank()) { "自定义 voice_id 不能为空" }
        validateVoiceId(providerSetting.cloneVoiceId)

        val urlBuilder = StringBuilder("${providerSetting.baseUrl}/voice_clone")
        if (providerSetting.groupId.isNotBlank()) {
            urlBuilder.append("?GroupId=${providerSetting.groupId}")
        }

        val requestBody = buildJsonObject {
            put("file_id", providerSetting.cloneFileId)
            put("voice_id", providerSetting.cloneVoiceId)

            // clone_prompt（可选：增强相似度和稳定性）
            val hasPromptAudio = providerSetting.clonePromptAudioFileId != 0L
            val hasPromptText = providerSetting.clonePromptText.isNotBlank()
            if (hasPromptAudio || hasPromptText) {
                put("clone_prompt", buildJsonObject {
                    if (hasPromptAudio) {
                        put("prompt_audio", providerSetting.clonePromptAudioFileId)
                    }
                    if (hasPromptText) {
                        put("prompt_text", providerSetting.clonePromptText)
                    }
                })
            }

            put("need_noise_reduction", providerSetting.cloneNeedNoiseReduction)
            put("need_volume_normalization", providerSetting.cloneNeedVolumeNormalization)

            // 试听参数（可选）
            if (providerSetting.clonePreviewText.isNotBlank()) {
                put("text", providerSetting.clonePreviewText)
                put("model", providerSetting.clonePreviewModel)
            }
        }

        val request = Request.Builder()
            .url(urlBuilder.toString())
            .addHeader("Authorization", "Bearer ${providerSetting.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .build()

        val log = buildString {
            append("voiceClone: voice_id=${providerSetting.cloneVoiceId}, ")
            append("file_id=${providerSetting.cloneFileId}, ")
            append("promptAudio=${providerSetting.clonePromptAudioFileId != 0L}, ")
            append("promptText=${providerSetting.clonePromptText.isNotBlank()}, ")
            append("preview=${providerSetting.clonePreviewText.isNotBlank()}")
        }
        Log.i(TAG, log)

        val response = httpClient.newCall(request).execute()
        val bodyStr = response.body.string()
        if (!response.isSuccessful) {
            throw Exception("MiniMax voiceClone failed: HTTP ${response.code}, body=$bodyStr")
        }

        val parsed = json.decodeFromString<MiniMaxVoiceCloneResponse>(bodyStr)
        if (parsed.base_resp.status_code != 0L) {
            throw Exception("MiniMax voiceClone error: ${parsed.base_resp.status_msg} (code=${parsed.base_resp.status_code})")
        }
        if (parsed.input_sensitive) {
            throw Exception("MiniMax voiceClone: 输入音频命中风控 (type=${parsed.input_sensitive_type})，请更换音频")
        }

        Log.i(
            TAG,
            "voiceClone success: demo_audioLen=${parsed.demo_audio.length}, sensitive=${parsed.input_sensitive}"
        )
        parsed.demo_audio
    }

    /**
     * 校验 MiniMax voice_id 格式规则：
     * 1. 长度 [8, 256]
     * 2. 首字符必须是英文字母
     * 3. 允许数字、字母、-、_
     * 4. 末位字符不可为 -、_
     */
    fun validateVoiceId(voiceId: String) {
        require(voiceId.length in 8..256) {
            "voice_id 长度必须在 8-256 之间（当前=${voiceId.length}）"
        }
        require(voiceId[0].isLetter()) {
            "voice_id 首字符必须是英文字母"
        }
        val last = voiceId.last()
        require(last != '-' && last != '_') {
            "voice_id 末位字符不能是 - 或 _"
        }
        require(voiceId.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "voice_id 仅允许字母、数字、-、_"
        }
    }
}

// ======================== 工具函数 ========================

private fun hexStringToBytes(hexString: String): ByteArray {
    val cleanHex = hexString.replace("\\s+".toRegex(), "")
    val length = cleanHex.length

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
