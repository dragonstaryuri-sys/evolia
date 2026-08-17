package me.rerere.asr.provider.providers

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.util.Log
import androidx.core.net.toFile
import java.io.File
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.asr.model.ASRResult
import me.rerere.asr.provider.ASRProvider
import me.rerere.asr.provider.ASRProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

private const val TAG = "OnlineASRProvider"

/**
 * 在线 ASR（伪流式 · 分段切片 + Prompt 续接）：
 *
 * 设计目标：在普通 Whisper 兼容"整段上传"API 上实现"边说边出字"的实时效果。
 *
 * 工作原理：
 *  1. AudioRecord 永不停歇，彻底消除上传期间丢字。
 *  2. 录音数据持续投喂 Silero VAD 做句子端点切分（final）。
 *  3. Partial 触发（isFinal=false）—— 分段切片模式：
 *     - 当前切片 PCM 累积到 ≥ MIN_SLICE_MS（1s）时，
 *       要么遇到 ≥ SHORT_SILENCE_SLICE_MS（200ms）的小停顿 → 自然断点切片
 *       要么连续说话超过 MAX_SLICE_MS（3s）→ 强制超时切片
 *     - 切片时：把当前切片的 PCM 独立编码 WAV 上传 Whisper，
 *       并把之前所有切片的识别结果作为 `prompt` 参数传给 Whisper（上下文续接）。
 *     - Whisper 返回后：追加到 recognizedText，拼接结果作为 partial 发出 → UI 实时显示。
 *  4. Final 触发（isFinal=true）：VAD 检测到 300ms 静音，
 *     把 VAD segment 内剩余 PCM 作为最后一个切片上传 → 拼接 → 发 final →
 *     清空切片缓冲和 recognizedText，开始下一句话。
 *
 * 对比"累积快照"模式（每次重新上传从句首到当前的全部音频）：
 *  - 每次只上传 1-3s 切片，API 延迟稳定（不再随说话时长线性增长）
 *  - 用 prompt 参数做上下文续接，拼接可靠
 */
class OnlineASRProvider : ASRProvider<ASRProviderSetting.OnlineASR> {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class TranscriptionResponse(val text: String = "")

    override fun startRecognition(
        context: Context,
        providerSetting: ASRProviderSetting.OnlineASR
    ) = channelFlow {
        // 校验配置
        if (providerSetting.apiKey.isBlank()) {
            close(RuntimeException("Online ASR: API Key is empty, please configure it in ASR settings"))
            return@channelFlow
        }

        // 初始化 VAD：minSilence 从 0.5s 降到 0.3s，配合伪流式端点检测
        val vad = Vad(
            assetManager = context.assets,
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = "silero_vad.onnx",
                    threshold = VAD_THRESHOLD,
                    minSilenceDuration = MIN_SILENCE_DURATION_SEC,
                    minSpeechDuration = MIN_SPEECH_DURATION_SEC,
                    windowSize = WINDOW_SIZE,
                    maxSpeechDuration = MAX_SPEECH_DURATION_SEC
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
                debug = false
            )
        )

        // 初始化 AudioRecord
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            // 更大的缓冲：伪流式期间持续录音，防止后台上传时缓冲溢出
            maxOf(minBuf * 4, WINDOW_SIZE * 16 * 2)
        )

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            vad.release()
            close(RuntimeException("AudioRecord initialization failed"))
            return@channelFlow
        }

        Log.i(TAG, "startRecognition (slice+prompt): apiUrl=${providerSetting.apiUrl}, model=${providerSetting.model}, lang=${providerSetting.language}")

        try {
            audioRecord.startRecording()
            val buffer = ShortArray(WINDOW_SIZE)

            // ===== 分段切片状态 =====
            // 当前切片正在累积的 PCM（Float，-1..1）
            val slicePcm = ArrayList<Float>(SAMPLE_RATE * 4) // 预分配 4s 容量
            // 已识别并确认的文本（之前所有切片的拼接结果）
            val recognizedText = AtomicReference("")
            // 轻量 RMS 辅助判定：当前 32ms window 是否含有语音能量
            var inSpeechAux = false
            var lastSpeechEnergyAtMs = 0L
            // 切片开始的毫秒时间戳（用于计算切片时长）
            var sliceStartMs = 0L
            val scope = this // channelFlow 的 ProducerScope，用于 launch 异步上传

            while (isActive) {
                val read = audioRecord.read(buffer, 0, WINDOW_SIZE)
                if (read <= 0) continue

                val samples = FloatArray(read) { buffer[it] / 32768.0f }
                vad.acceptWaveform(samples)

                val now = System.currentTimeMillis()

                // --- 1. 轻量 RMS 能量检测 ---
                var sumSq = 0.0
                for (s in samples) sumSq += (s * s).toDouble()
                val rms = sqrt(sumSq / samples.size).toFloat()
                if (rms > RMS_SPEECH_THRESHOLD) {
                    if (!inSpeechAux) {
                        // 语音开始，记录切片起点
                        sliceStartMs = now
                    }
                    inSpeechAux = true
                    lastSpeechEnergyAtMs = now
                } else if (inSpeechAux && now - lastSpeechEnergyAtMs > 600L) {
                    inSpeechAux = false
                }

                // --- 2. 累积当前切片 PCM ---
                if (inSpeechAux || slicePcm.isNotEmpty()) {
                    slicePcm.addAll(samples.asList())
                }

                // --- 2.1 切片诊断日志：每累积 5s 打一行帮助排查 ---
                val sliceMs = slicePcm.size * 1000 / SAMPLE_RATE
                val silenceMs = now - lastSpeechEnergyAtMs
                if (sliceMs >= 5000 && sliceMs % 5000 < 32) {
                    Log.d(TAG, "[切片诊断] 已累积=${sliceMs}ms 静音=${silenceMs}ms RMS=${"%.4f".format(rms)}" +
                        " inSpeech=$inSpeechAux 阈值RMS=$RMS_SPEECH_THRESHOLD 切片条件: ≥${MIN_SLICE_MS}ms且停顿≥${SHORT_SILENCE_SLICE_MS}ms")
                }

                // --- 3. 触发切片上传（Partial）：停顿触发 + 超长安全保护切片 ---
                // 主要触发：检测到 ≥SHORT_SILENCE_SLICE_MS 的自然停顿（用户换气/断句）
                // 安全触发：累积 ≥MAX_SLICE_MS 并且当前帧 RMS 低于阈值（至少是换气间隙），强制切
                val pauseTriggered = sliceMs >= MIN_SLICE_MS && silenceMs >= SHORT_SILENCE_SLICE_MS
                val maxLenTriggered = sliceMs >= MAX_SLICE_MS && rms <= RMS_SPEECH_THRESHOLD
                val shouldSlice = (pauseTriggered || maxLenTriggered) && slicePcm.isNotEmpty()
                if (shouldSlice && slicePcm.isNotEmpty()) {
                    // 切片快照
                    val sliceSamples = slicePcm.toFloatArray()
                    slicePcm.clear()
                    sliceStartMs = now
                    // 当前已识别文本快照（作为 prompt 传给 Whisper）
                    val promptText = recognizedText.get()
                    val triggerReason = if (pauseTriggered) "停顿触发" else "超长保护触发"

                    scope.launch(Dispatchers.IO) {
                        val sliceText = try {
                            val pcm = ShortArray(sliceSamples.size) {
                                (sliceSamples[it] * 32767f).toInt().toShort()
                            }
                            val wavBytes = pcmToWav(pcm, SAMPLE_RATE)
                            val sliceMsValue = sliceSamples.size * 1000 / SAMPLE_RATE
                            Log.i(TAG, "┌─[Partial 切片上传][$triggerReason] 时长=${sliceMsValue}ms 停顿=${silenceMs}ms" +
                                " prompt=\"${promptText.take(30)}\"")
                            val result = transcribe(wavBytes, providerSetting, promptText)
                            Log.i(TAG, "└─[Partial 切片返回] 识别=\"${result.trim()}\" 累积=\"${(promptText + result.trim()).trim()}\"")
                            result
                        } catch (e: Exception) {
                            Log.w(TAG, "Slice transcribe failed", e)
                            ""
                        }
                        if (sliceText.isNotBlank() && isActive) {
                            // 追加到已识别文本（prompt 续接模式下，直接拼接）
                            val newText = if (promptText.isEmpty()) sliceText.trim()
                                         else (promptText + sliceText.trim())
                            recognizedText.set(newText)
                            // 作为 partial 发出 → UI 实时显示
                            if (isActive) {
                                send(ASRResult(text = newText, isFinal = false))
                            }
                        }
                    }.invokeOnCompletion { /* 协程结束，不管成功失败 */ }
                }

                // --- 4. VAD 产出完整 segment：Final ---
                //    VAD segment 只作为"用户说完了"的信号，不用它的音频数据。
                //    因为每帧 PCM 同时投喂了 VAD 和 slicePcm，两者包含的音频是重复的。
                //    如果合并上传，Whisper 会收到两遍同样的音频，导致识别结果重复。
                //    所以只用 slicePcm 里累积的音频（切片后的剩余部分，或没切片时的完整一句）。
                while (!vad.empty() && isActive) {
                    val segment = vad.front()
                    vad.pop()

                    if (segment.samples.isEmpty()) continue

                    // 只用 slicePcm，不用 VAD segment 的音频
                    val finalSamples = slicePcm.toFloatArray()
                    slicePcm.clear()
                    val finalMs = finalSamples.size * 1000 / SAMPLE_RATE
                    val promptText = recognizedText.get()
                    val hadPartial = promptText.isNotEmpty()
                    Log.i(TAG, "┌─[Final 句子结束] 剩余切片=${finalMs}ms" +
                        " 已有Partial=${if (hadPartial) "是" else "否"}" +
                        " 已识别=\"${promptText.take(30)}\"")

                    scope.launch(Dispatchers.IO) {
                        if (finalSamples.isEmpty()) {
                            // slicePcm 为空（刚切片完用户就没再说话），直接用已识别文本作为 final
                            if (promptText.isNotBlank() && isActive) {
                                Log.i(TAG, "└─[Final 直接发送] (无剩余音频) 最终文本=\"$promptText\"")
                                send(ASRResult(text = promptText, isFinal = true))
                            }
                            return@launch
                        }
                        val pcm = ShortArray(finalSamples.size) {
                            (finalSamples[it] * 32767f).toInt().toShort()
                        }
                        val wavBytes = pcmToWav(pcm, SAMPLE_RATE)
                        val text = try {
                            transcribe(wavBytes, providerSetting, promptText)
                        } catch (e: Exception) {
                            Log.e(TAG, "Final transcribe failed", e)
                            ""
                        }
                        if (text.isNotBlank() && isActive) {
                            val finalText = if (promptText.isEmpty()) text.trim()
                                           else (promptText + text.trim())
                            Log.i(TAG, "└─[Final 最终发送] 新增=\"${text.trim()}\" 最终文本=\"$finalText\"")
                            send(ASRResult(text = finalText, isFinal = true))
                        }
                    }

                    // 重置状态
                    recognizedText.set("")
                    inSpeechAux = false
                    sliceStartMs = 0L
                    vad.reset()
                    // NOTE: 音频录制永不停止！
                }
            }
        } finally {
            try { audioRecord.stop() } catch (_: Exception) {}
            audioRecord.release()
            vad.release()
            Log.i(TAG, "Recognition stopped, resources released")
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 上传 WAV 音频到云端转录 API（兼容 OpenAI Whisper 接口格式）。
     * [prompt] 用于上下文续接：把之前切片的识别结果传给 Whisper，让后续切片有上下文。
     */
    private suspend fun transcribe(
        wavBytes: ByteArray,
        setting: ASRProviderSetting.OnlineASR,
        prompt: String = ""
    ): String = transcribeBytes(
        bytes = wavBytes,
        filename = "audio.wav",
        mime = "audio/wav",
        setting = setting,
        prompt = prompt
    )

    /**
     * 整段式转录：读取 [uri] 指向的音频文件，上传到云端 Whisper 兼容 API。
     * 支持常见格式（m4a/mp3/wav/webm 等，OpenAI Whisper 官方支持 mp3/mp4/mpeg/mpga/m4a/wav/webm）。
     */
    override suspend fun transcribeFile(
        context: Context,
        uri: Uri,
        providerSetting: ASRProviderSetting.OnlineASR
    ): String = withContext(Dispatchers.IO) {
        if (providerSetting.apiKey.isBlank()) {
            throw RuntimeException("Online ASR: API Key is empty, please configure it in ASR settings")
        }
        val (bytes, filename, mime) = readAudioFile(context, uri)
        // 文件转录场景不需要 prompt 续接，整段上传
        transcribeBytes(bytes, filename, mime, providerSetting, prompt = "")
    }

    /**
     * 读取音频文件为字节数组，并推断文件名与 mime。
     *
     * 注意：
     *  - Uri.lastPathSegment 在路径/文件名含特殊字符或 uuid 段时偶发拿到截断片段（不带扩展名），
     *    导致 Whisper 兼容服务端按"无扩展名"拒绝（HTTP 400）。
     *  - 优先走 `Uri.toFile()`（file:// scheme）直接拿 File.getName，再回退 lastPathSegment，
     *    并确保最终 filename **自带正确扩展名**（若缺失就按 mime 补上）。
     *  - .m4a 使用 `audio/x-m4a`（部分服务端不识别 `audio/mp4`，会视为视频容器）。
     */
    private fun readAudioFile(context: Context, uri: Uri): Triple<ByteArray, String, String> {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw RuntimeException("无法读取音频文件: $uri")

        val rawName = runCatching {
            if (uri.scheme == "file") uri.toFile().name else null
        }.getOrNull()
            ?: uri.lastPathSegment
            ?: "audio.m4a"

        val lower = rawName.lowercase()
        val mime = when {
            lower.endsWith(".wav") -> "audio/wav"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".m4a") -> "audio/x-m4a"
            lower.endsWith(".aac") -> "audio/aac"
            lower.endsWith(".mp4") -> "audio/x-m4a"
            lower.endsWith(".webm") -> "audio/webm"
            lower.endsWith(".ogg") -> "audio/ogg"
            lower.endsWith(".flac") -> "audio/flac"
            else -> {
                // 拿不到扩展名，回退查 contentResolver
                val resolverMime = context.contentResolver.getType(uri).orEmpty()
                if (resolverMime.startsWith("audio/")) resolverMime else "audio/x-m4a"
            }
        }

        // 确保 filename 带上正确扩展名，避免服务端按无扩展名拒绝
        val ext = when (mime) {
            "audio/wav" -> ".wav"
            "audio/mpeg" -> ".mp3"
            "audio/x-m4a" -> ".m4a"
            "audio/aac" -> ".aac"
            "audio/webm" -> ".webm"
            "audio/ogg" -> ".ogg"
            "audio/flac" -> ".flac"
            else -> ".m4a"
        }
        val filename = if (File(rawName).extension.isNotBlank()) rawName else (rawName + ext)

        Log.d(TAG, "readAudioFile: uri=$uri, filename=$filename, mime=$mime, size=${bytes.size}")
        return Triple(bytes, filename, mime)
    }

    /**
     * 上传音频字节到云端转录 API（兼容 OpenAI Whisper 接口格式）。
     * [prompt] 用于上下文续接（可选）：把之前切片的识别结果作为 prompt 传给 Whisper，
     * 让后续切片的识别有上下文。仅在非空时添加到 multipart 表单。
     */
    private suspend fun transcribeBytes(
        bytes: ByteArray,
        filename: String,
        mime: String,
        setting: ASRProviderSetting.OnlineASR,
        prompt: String = ""
    ): String {
        val audioBody = bytes.toRequestBody(mime.toMediaType())

        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", filename, audioBody)
            .addFormDataPart("model", setting.model)
            .addFormDataPart("language", setting.language)
            .addFormDataPart("response_format", "json")

        // prompt 参数：Whisper 官方文档推荐的上下文续接方式
        // 把前一段的识别结果作为 prompt，Whisper 会据此调整识别方向
        if (prompt.isNotBlank()) {
            // Whisper prompt 上限约 224 tokens，这里取最后 200 字符做简单截断
            val truncatedPrompt = prompt.takeLast(200)
            builder.addFormDataPart("prompt", truncatedPrompt)
        }

        val requestBody = builder.build()

        val request = Request.Builder()
            .url(setting.apiUrl)
            .addHeader("Authorization", "Bearer ${setting.apiKey}")
            .post(requestBody)
            .build()

        Log.d(TAG, "transcribeBytes: POST ${setting.apiUrl} file=$filename mime=$mime bytes=${bytes.size} model=${setting.model} promptLen=${prompt.length}")
        val response = client.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string() ?: ""
                val headersDump = resp.headers.toMultimap().entries.joinToString(";") { (k, v) -> "$k=${v.firstOrNull()}" }
                Log.e(TAG, "transcribeBytes: FAILED code=${resp.code} headers=$headersDump errBody=$errBody")
                throw RuntimeException("ASR API ${resp.code}: ${errBody.take(200)}")
            }
            val body = resp.body?.string() ?: ""
            return json.decodeFromString<TranscriptionResponse>(body).text
        }
    }

    /**
     * PCM 16bit mono → WAV bytes（添加标准 WAV 头）。
     */
    private fun pcmToWav(pcm: ShortArray, sampleRate: Int): ByteArray {
        val numChannels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * numChannels * bitsPerSample / 8
        val blockAlign = numChannels * bitsPerSample / 8
        val dataSize = pcm.size * 2
        val chunkSize = 36 + dataSize
        val buffer = ByteArray(44 + dataSize)
        var pos = 0

        // RIFF header
        buffer[pos++] = 'R'.code.toByte()
        buffer[pos++] = 'I'.code.toByte()
        buffer[pos++] = 'F'.code.toByte()
        buffer[pos++] = 'F'.code.toByte()
        writeInt32(buffer, pos, chunkSize); pos += 4
        buffer[pos++] = 'W'.code.toByte()
        buffer[pos++] = 'A'.code.toByte()
        buffer[pos++] = 'V'.code.toByte()
        buffer[pos++] = 'E'.code.toByte()

        // fmt subchunk
        buffer[pos++] = 'f'.code.toByte()
        buffer[pos++] = 'm'.code.toByte()
        buffer[pos++] = 't'.code.toByte()
        buffer[pos++] = ' '.code.toByte()
        writeInt32(buffer, pos, 16); pos += 4   // subchunk1 size
        writeInt16(buffer, pos, 1); pos += 2     // audio format = PCM
        writeInt16(buffer, pos, numChannels); pos += 2
        writeInt32(buffer, pos, sampleRate); pos += 4
        writeInt32(buffer, pos, byteRate); pos += 4
        writeInt16(buffer, pos, blockAlign); pos += 2
        writeInt16(buffer, pos, bitsPerSample); pos += 2

        // data subchunk
        buffer[pos++] = 'd'.code.toByte()
        buffer[pos++] = 'a'.code.toByte()
        buffer[pos++] = 't'.code.toByte()
        buffer[pos++] = 'a'.code.toByte()
        writeInt32(buffer, pos, dataSize); pos += 4

        // PCM samples (little-endian)
        for (i in pcm.indices) {
            val v = pcm[i].toInt()
            buffer[pos++] = (v and 0xFF).toByte()
            buffer[pos++] = ((v shr 8) and 0xFF).toByte()
        }
        return buffer
    }

    private fun writeInt32(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeInt16(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val WINDOW_SIZE = 512 // Silero VAD 固定 512 样本（≈32ms @ 16k）
        private const val VAD_THRESHOLD = 0.5F
        // VAD final 句子端点：800ms 静音才判定用户说完了
        // （500ms 容易把思考型停顿误判为说完了；800ms 给用户更多缓冲，反正 partial 已经出字了不卡体验）
        private const val MIN_SILENCE_DURATION_SEC = 0.8F
        private const val MIN_SPEECH_DURATION_SEC = 0.3F  // 过滤过短的噪声
        private const val MAX_SPEECH_DURATION_SEC = 30F    // 单次最长 30 秒

        // ===== 分段切片（Slice + Prompt 续接）参数 =====
        // RMS 能量阈值（≈-38dBFS，-46dBFS 太灵敏了，呼吸声/底噪都算语音）
        // 调大后只有真正说话的音量才会刷新 lastSpeechEnergyAtMs，避免静音时长一直被刷新
        private const val RMS_SPEECH_THRESHOLD = 0.012F
        // 切片最小时长：累积至少 500ms 才允许切片上传，避免过短片段浪费 API 调用
        private const val MIN_SLICE_MS = 500
        // 切片最大时长：累积 ≥5s 并且当前帧 RMS 低于阈值（换气间隙），强制切片防止长句不切
        // 这个是"安全网"，主要触发还是靠停顿；所以必须同时满足 RMS 低（至少是换气间隙）
        private const val MAX_SLICE_MS = 5000
        // 切片停顿阈值：检测到 ≥60ms 的自然停顿就切片
        // （逗号停顿 50-100ms，60ms 抓大部分换气；句号停顿 300+ms 直接命中；
        //  阈值明显短于 VAD final 的 800ms，因此停顿时一定先 partial 后 final。）
        private const val SHORT_SILENCE_SLICE_MS = 60
    }
}
