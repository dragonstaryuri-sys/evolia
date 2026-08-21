package me.rerere.asr.provider.providers

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import me.rerere.asr.model.ASRResult
import me.rerere.asr.provider.ASRProvider
import me.rerere.asr.provider.ASRProviderSetting
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

private const val TAG = "LocalSenseVoiceProvider"

/**
 * 本地 ASR Provider：基于 sherpa-onnx OfflineRecognizer + SenseVoice INT8 模型。
 *
 * 工作原理：
 *  1. AudioRecord 持续采集 16kHz PCM（与 OnlineASRProvider 一致）
 *  2. Silero VAD 做端点检测（与 OnlineASRProvider 一致）
 *  3. Partial 触发：每累积 1.5s 音频，用 OfflineRecognizer 解码一次累积 PCM
 *     → SenseVoice 推理速度 17x 实时，10s 音频仅需 70-110ms，足够实时
 *  4. Final 触发：VAD 检测到 800ms 静音（用户说完），解码整段 PCM
 *
 * 与 OnlineASRProvider 的核心区别：
 *  - 无 HTTP 上传，无 API Key，无网络依赖
 *  - 无 prompt 续接（SenseVoice 不支持跨段上下文）
 *  - 推理在本地 CPU 上进行，使用 OfflineRecognizer.decode()
 *  - transcribeFile 直接本地解码，无需上传
 */
class LocalSenseVoiceASRProvider : ASRProvider<ASRProviderSetting.LocalSenseVoiceASR> {

    /**
     * 实时识别：VAD 分段 + OfflineRecognizer 本地推理。
     * 复用 OnlineASRProvider 的 AudioRecord + VAD 框架，替换 transcribe 方法。
     */
    @SuppressLint("MissingPermission")
    override fun startRecognition(
        context: Context,
        providerSetting: ASRProviderSetting.LocalSenseVoiceASR
    ) = channelFlow @androidx.annotation.RequiresPermission(android.Manifest.permission.RECORD_AUDIO) {
        // 校验模型是否就绪
        val modelFile = File(context.filesDir, "$MODEL_DIR/$MODEL_FILE_NAME")
        val tokensFile = File(context.filesDir, "$MODEL_DIR/$TOKENS_FILE_NAME")
        if (!modelFile.exists() || !tokensFile.exists()) {
            close(RuntimeException("SenseVoice 模型未下载，请在 ASR 设置中点击下载模型"))
            return@channelFlow
        }

        // 初始化 OfflineRecognizer（加载模型，约 500ms）
        Log.i(TAG, "Initializing OfflineRecognizer: model=${modelFile.absolutePath}, lang=${providerSetting.language}")
        val recognizer = try {
            createRecognizer(modelFile, tokensFile, providerSetting)
        } catch (e: Exception) {
            close(e)
            return@channelFlow
        }

        // 初始化 VAD（与 OnlineASRProvider 配置一致）
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

        // 权限检查
        val hasRecordPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasRecordPermission) {
            recognizer.release()
            vad.release()
            close(SecurityException("缺少 RECORD_AUDIO 权限，请先授予麦克风权限"))
            return@channelFlow
        }

        // 初始化 AudioRecord
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 4, WINDOW_SIZE * 16 * 2)
            )
        } catch (e: SecurityException) {
            recognizer.release()
            vad.release()
            close(e)
            return@channelFlow
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            recognizer.release()
            vad.release()
            close(RuntimeException("AudioRecord initialization failed"))
            return@channelFlow
        }

        Log.i(TAG, "startRecognition: lang=${providerSetting.language}, useItn=${providerSetting.useItn}, threads=${providerSetting.numThreads}")

        try {
            audioRecord.startRecording()
            val buffer = ShortArray(WINDOW_SIZE)

            // 累积 PCM 缓冲区（Float，-1..1）
            val accumulatedPcm = ArrayList<Float>(SAMPLE_RATE * 4)
            // 上一次 partial 发送的时间戳
            var lastPartialSentMs = 0L
            // 轻量 RMS 能量检测，判断是否有语音
            var inSpeech = false
            var lastSpeechEnergyAtMs = 0L
            // Final 后静默期，防止回声/环境噪声触发空识别
            var finalGracePeriodUntil = 0L

            while (isActive) {
                val read = audioRecord.read(buffer, 0, WINDOW_SIZE)
                if (read <= 0) continue

                val samples = FloatArray(read) { buffer[it] / 32768.0f }
                vad.acceptWaveform(samples)

                val now = System.currentTimeMillis()

                // --- 1. 轻量 RMS 能量检测 ---
                val inGracePeriod = now < finalGracePeriodUntil
                var sumSq = 0.0
                for (s in samples) sumSq += (s * s).toDouble()
                val rms = sqrt(sumSq / samples.size).toFloat()
                if (!inGracePeriod && rms > RMS_SPEECH_THRESHOLD) {
                    if (!inSpeech) {
                        Log.d(TAG, "Speech started at ${accumulatedPcm.size * 1000 / SAMPLE_RATE}ms")
                    }
                    inSpeech = true
                    lastSpeechEnergyAtMs = now
                } else if (inSpeech && now - lastSpeechEnergyAtMs > 600L) {
                    inSpeech = false
                }

                // --- 2. 累积 PCM（仅在语音期间或已有累积时） ---
                if (inSpeech || accumulatedPcm.isNotEmpty()) {
                    accumulatedPcm.addAll(samples.asList())
                }

                // --- 3. Partial 触发：每 1.5s 解码一次累积 PCM ---
                // SenseVoice 推理速度 17x 实时，10s 音频仅需 ~100ms
                // 与 OnlineASR 不同：无 prompt 续接，每次重新解码整段累积音频
                val accumulatedMs = accumulatedPcm.size * 1000 / SAMPLE_RATE
                val partialIntervalOk = now - lastPartialSentMs >= PARTIAL_INTERVAL_MS
                if (accumulatedMs >= PARTIAL_MIN_MS && partialIntervalOk && accumulatedPcm.isNotEmpty()) {
                    val partialSamples = accumulatedPcm.toFloatArray()
                    val partialText = decodeSamples(recognizer, partialSamples)
                    if (partialText.isNotBlank() && isActive) {
                        send(ASRResult(text = partialText, isFinal = false))
                    }
                    lastPartialSentMs = now
                }

                // --- 4. VAD 产出完整 segment：Final ---
                while (!vad.empty() && isActive) {
                    val segment = vad.front()
                    vad.pop()

                    if (segment.samples.isEmpty() && accumulatedPcm.isEmpty()) continue

                    // 解码整段累积 PCM（包含 partial 后新增的音频）
                    val finalSamples = accumulatedPcm.toFloatArray()
                    accumulatedPcm.clear()

                    val finalMs = finalSamples.size * 1000 / SAMPLE_RATE
                    Log.i(TAG, "[Final] 句子结束, 音频=${finalMs}ms")

                    val finalText = decodeSamples(recognizer, finalSamples)
                    if (finalText.isNotBlank() && isActive) {
                        Log.i(TAG, "[Final] 识别结果: \"$finalText\"")
                        send(ASRResult(text = finalText, isFinal = true))
                    } else if (finalMs > 500) {
                        // 有音频但识别为空，可能是噪声，记录日志
                        Log.d(TAG, "[Final] 音频 ${finalMs}ms 但识别为空，可能是噪声")
                    }

                    // 重置状态
                    inSpeech = false
                    lastPartialSentMs = 0L
                    finalGracePeriodUntil = System.currentTimeMillis() + FINAL_GRACE_PERIOD_MS
                    vad.reset()
                }
            }
        } finally {
            try { audioRecord.stop() } catch (_: Exception) {}
            audioRecord.release()
            vad.release()
            recognizer.release()
            Log.i(TAG, "Recognition stopped, resources released")
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 整段式转录：读取音频文件 → 解码为 PCM → OfflineRecognizer 推理。
     * 支持常见音频格式（m4a/mp3/wav 等），通过 MediaExtractor 解码。
     */
    override suspend fun transcribeFile(
        context: Context,
        uri: Uri,
        providerSetting: ASRProviderSetting.LocalSenseVoiceASR
    ): String = withContext(Dispatchers.IO) {
        val modelFile = File(context.filesDir, "$MODEL_DIR/$MODEL_FILE_NAME")
        val tokensFile = File(context.filesDir, "$MODEL_DIR/$TOKENS_FILE_NAME")
        if (!modelFile.exists() || !tokensFile.exists()) {
            throw RuntimeException("SenseVoice 模型未下载，请在 ASR 设置中点击下载模型")
        }

        Log.i(TAG, "transcribeFile: uri=$uri")
        val recognizer = createRecognizer(modelFile, tokensFile, providerSetting)
        try {
            // 解码音频文件为 16kHz mono FloatArray PCM
            val samples = decodeAudioFile(context, uri)
            Log.d(TAG, "Decoded audio: ${samples.size} samples (${samples.size * 1000 / SAMPLE_RATE}ms)")

            val text = decodeSamples(recognizer, samples)
            Log.i(TAG, "transcribeFile result: \"$text\"")
            text
        } finally {
            recognizer.release()
        }
    }

    /**
     * 创建 OfflineRecognizer 实例。
     * 注意：sherpa-onnx 1.13.4 的类名为 OfflineSenseVoiceModelConfig，
     * 参数名为 useInverseTextNormalization（非 useItn）。
     */
    private fun createRecognizer(
        modelFile: File,
        tokensFile: File,
        setting: ASRProviderSetting.LocalSenseVoiceASR
    ): OfflineRecognizer {
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(SAMPLE_RATE, FEATURE_DIM, 0f),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(),
                paraformer = OfflineParaformerModelConfig(),
                whisper = OfflineWhisperModelConfig(),
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = modelFile.absolutePath,
                    language = setting.language,
                    useInverseTextNormalization = setting.useItn
                ),
                tokens = tokensFile.absolutePath,
                numThreads = setting.numThreads,
                debug = false,
                provider = "cpu"
            )
        )
        return OfflineRecognizer(config = config)
    }

    /**
     * 用 OfflineRecognizer 解码一段 PCM 音频。
     * 创建 stream → 喂入音频 → 解码 → 获取结果 → 释放 stream。
     */
    private fun decodeSamples(
        recognizer: OfflineRecognizer,
        samples: FloatArray
    ): String {
        if (samples.isEmpty()) return ""
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            return result.text.trim()
        } catch (e: Exception) {
            Log.e(TAG, "decodeSamples failed", e)
            return ""
        } finally {
            stream.release()
        }
    }

    /**
     * 解码音频文件为 16kHz mono FloatArray PCM。
     * 使用 Android MediaExtractor + MediaCodec，支持 m4a/mp3/wav/aac 等常见格式。
     */
    private fun decodeAudioFile(context: Context, uri: Uri): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        val trackCount = extractor.trackCount
        if (trackCount == 0) {
            extractor.release()
            throw RuntimeException("音频文件无轨道: $uri")
        }

        // 找到音频轨道
        var audioTrackIndex = -1
        var inputFormat: MediaFormat? = null
        for (i in 0 until trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                inputFormat = format
                break
            }
        }

        if (audioTrackIndex < 0 || inputFormat == null) {
            extractor.release()
            throw RuntimeException("音频文件无音频轨道: $uri")
        }

        extractor.selectTrack(audioTrackIndex)

        val srcSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val srcChannelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!

        Log.d(TAG, "decodeAudioFile: srcSampleRate=$srcSampleRate, channels=$srcChannelCount, mime=$mime")

        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(inputFormat, null, null, 0)
        decoder.start()

        val pcmSamples = ArrayList<Short>(srcSampleRate * 10) // 预分配 10s 容量
        val info = MediaCodec.BufferInfo()
        val timeoutUs = 10000L

        try {
            // 解码循环
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                // 喂入输入数据
                if (!inputDone) {
                    val inputBufferIndex = decoder.dequeueInputBuffer(timeoutUs)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputBufferIndex)
                        val sampleTime = extractor.sampleTime
                        if (sampleTime < 0) {
                            decoder.queueInputBuffer(
                                inputBufferIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            val sampleSize = extractor.readSampleData(inputBuffer!!, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(
                                    inputBufferIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(
                                    inputBufferIndex, 0, sampleSize, sampleTime, 0
                                )
                                extractor.advance()
                            }
                        }
                    }
                }

                // 读取解码输出
                val outputBufferIndex = decoder.dequeueOutputBuffer(info, timeoutUs)
                if (outputBufferIndex >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                    val outputBuffer = decoder.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null && info.size > 0) {
                        // PCM 16-bit little-endian
                        val shortBuffer = outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val chunk = ShortArray(shortBuffer.remaining())
                        shortBuffer.get(chunk)
                        for (s in chunk) {
                            pcmSamples.add(s)
                        }
                    }
                    decoder.releaseOutputBuffer(outputBufferIndex, false)
                }
            }
        } finally {
            decoder.stop()
            decoder.release()
            extractor.release()
        }

        // 转换为 FloatArray [-1, 1]，并处理多声道 → mono
        val monoSamples = if (srcChannelCount > 1) {
            // 多声道混合为 mono：每 srcChannelCount 个样本取平均
            val monoLen = pcmSamples.size / srcChannelCount
            FloatArray(monoLen) { i ->
                var sum = 0
                for (ch in 0 until srcChannelCount) {
                    sum += pcmSamples[i * srcChannelCount + ch].toInt()
                }
                (sum / srcChannelCount) / 32768.0f
            }
        } else {
            FloatArray(pcmSamples.size) { pcmSamples[it] / 32768.0f }
        }

        // 重采样到 16kHz（线性插值）
        val finalSamples = if (srcSampleRate != SAMPLE_RATE) {
            resampleLinear(monoSamples, srcSampleRate, SAMPLE_RATE)
        } else {
            monoSamples
        }

        Log.d(TAG, "decodeAudioFile done: ${finalSamples.size} samples at ${SAMPLE_RATE}Hz")
        return finalSamples
    }

    /**
     * 线性插值重采样。
     */
    private fun resampleLinear(
        input: FloatArray,
        srcRate: Int,
        dstRate: Int
    ): FloatArray {
        if (srcRate == dstRate || input.isEmpty()) return input
        val ratio = srcRate.toFloat() / dstRate.toFloat()
        val outputLength = (input.size / ratio).toInt()
        val output = FloatArray(outputLength)
        for (i in output.indices) {
            val srcPos = i * ratio
            val srcIndex = srcPos.toInt()
            val frac = srcPos - srcIndex
            if (srcIndex + 1 < input.size) {
                output[i] = input[srcIndex] * (1 - frac) + input[srcIndex + 1] * frac
            } else if (srcIndex < input.size) {
                output[i] = input[srcIndex]
            }
        }
        return output
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val WINDOW_SIZE = 512 // Silero VAD 固定 512 样本（≈32ms @ 16k）
        private const val FEATURE_DIM = 80
        private const val VAD_THRESHOLD = 0.5F

        // VAD final 句子端点：800ms 静音才判定用户说完了
        private const val MIN_SILENCE_DURATION_SEC = 0.8F
        private const val MIN_SPEECH_DURATION_SEC = 0.3F
        private const val MAX_SPEECH_DURATION_SEC = 30F

        // RMS 能量阈值（与 OnlineASRProvider 一致）
        private const val RMS_SPEECH_THRESHOLD = 0.012F

        // Partial 触发间隔：每 1.5s 解码一次
        private const val PARTIAL_INTERVAL_MS = 1500L
        // Partial 最小累积时长：至少 500ms 才触发 partial
        private const val PARTIAL_MIN_MS = 500

        // Final 后静默期：1.5s 内忽略噪声
        private const val FINAL_GRACE_PERIOD_MS = 1500L

        // 模型文件路径常量（与 SenseVoiceModelManager 保持一致）
        const val MODEL_DIR = "sensevoice"
        const val MODEL_FILE_NAME = "model.int8.onnx"
        const val TOKENS_FILE_NAME = "tokens.txt"
    }
}
