package me.rerere.asr.provider.providers

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
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

private const val TAG = "OnlineASRProvider"

/**
 * 在线 ASR：本地 Silero VAD 切分语音段 → 编码 WAV → 上传到云端转录 API → 返回文本。
 *
 * 工作流程：
 * 1. AudioRecord 录制 16kHz PCM
 * 2. VAD 检测语音段（用户开始说话 → 说完一句话 → 静音结束）
 * 3. 取出完整语音段，编码为 WAV
 * 4. HTTP POST 到 transcription API（兼容 OpenAI Whisper 接口）
 * 5. 返回识别文本，继续下一轮监听
 *
 * 兼容所有设备，不依赖系统 SpeechRecognizer。
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

        // 初始化 VAD
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
            maxOf(minBuf * 2, WINDOW_SIZE * 4 * 2) // 留足缓冲
        )

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            vad.release()
            close(RuntimeException("AudioRecord initialization failed"))
            return@channelFlow
        }

        Log.i(TAG, "startRecognition: apiUrl=${providerSetting.apiUrl}, model=${providerSetting.model}, lang=${providerSetting.language}")

        try {
            audioRecord.startRecording()
            val buffer = ShortArray(WINDOW_SIZE)

            while (isActive) {
                val read = audioRecord.read(buffer, 0, WINDOW_SIZE)
                if (read <= 0) continue

                val samples = FloatArray(read) { buffer[it] / 32768.0f }
                vad.acceptWaveform(samples)

                // VAD 自动切分语音段：检测到用户说完一句话后会在这里产出 segment
                while (!vad.empty() && isActive) {
                    val segment = vad.front()
                    vad.pop()

                    if (segment.samples.isEmpty()) continue

                    // 停止录音，避免在 HTTP 等待期间缓冲溢出
                    audioRecord.stop()

                    // 语音段 FloatArray → 16bit PCM → WAV
                    val pcm = ShortArray(segment.samples.size) {
                        (segment.samples[it] * 32767f).toInt().toShort()
                    }
                    val wavBytes = pcmToWav(pcm, SAMPLE_RATE)

                    Log.d(TAG, "Transcribing ${pcm.size} samples (${pcm.size * 1000 / SAMPLE_RATE}ms)")

                    // 上传到云端转录 API
                    val text = try {
                        transcribe(wavBytes, providerSetting)
                    } catch (e: Exception) {
                        Log.e(TAG, "Transcribe failed", e)
                        // 转录失败不中断，发出空结果让上层继续
                        ""
                    }

                    if (text.isNotBlank()) {
                        send(ASRResult(text = text.trim(), isFinal = true))
                    }

                    // 重置 VAD 状态，重新开始录音
                    vad.reset()
                    if (isActive) {
                        audioRecord.startRecording()
                    }
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
     */
    private suspend fun transcribe(
        wavBytes: ByteArray,
        setting: ASRProviderSetting.OnlineASR
    ): String {
        val audioBody = wavBytes.toRequestBody("audio/wav".toMediaType())

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "audio.wav", audioBody)
            .addFormDataPart("model", setting.model)
            .addFormDataPart("language", setting.language)
            .addFormDataPart("response_format", "json")
            .build()

        val request = Request.Builder()
            .url(setting.apiUrl)
            .addHeader("Authorization", "Bearer ${setting.apiKey}")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string() ?: ""
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
        private const val WINDOW_SIZE = 512 // Silero VAD 固定 512 样本
        private const val VAD_THRESHOLD = 0.5F
        private const val MIN_SILENCE_DURATION_SEC = 0.5F // 说完一句话后的静音时长（比打断检测更长）
        private const val MIN_SPEECH_DURATION_SEC = 0.3F  // 过滤过短的噪声
        private const val MAX_SPEECH_DURATION_SEC = 30F    // 单次最长 30 秒
    }
}
