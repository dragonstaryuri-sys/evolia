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
 * 设计原则（与 OnlineASRProvider 的伪流式明确切割）：
 *  SenseVoice 模型本身**不支持 prompt / 跨段上下文续接**，因此**放弃伪流式**，
 *  只在 VAD 判定一句话完整结束后再做一次整段推理。
 *
 * 优势：
 *  - 推理一次就出最终结果，没有中间态跳变 → "拼接问题"彻底消失
 *  - CPU 消耗固定（一句话只推理一次），长句不会越说越慢
 *  - SenseVoice 17x 实时推理速度（10s 音频 ≈100ms），用户说完 800ms + 100ms ≈ 900ms 出字，
 *    体感与 OnlineASR 流式无异
 * 劣势：
 *  - 说话期间 UI 无 partial 文字反馈（用"正在听…"占位 partial 驱动 UI 动画即可）
 *
 * 工作原理：
 *  1. AudioRecord 持续采集 16kHz PCM（VOICE_COMMUNICATION 源，与打断检测链路一致）
 *  2. Silero VAD 做端点检测（minSilence=800ms 才算一句说完）
 *  3. 期间只做 PCM 累积，不触发任何推理
 *  4. VAD segment 产出（用户说完）→ 整段推理 → 发 final
 *
 * 与 OnlineASRProvider 的核心区别：
 *  - 无 HTTP 上传 / 无 API Key / 无网络依赖
 *  - 无 Slice + Prompt 切片续接（SenseVoice 不支持）
 *  - 无 Partial 中间态推理（取消伪流式，避免跳变体验）
 *  - transcribeFile 直接本地解码，无需上传
 */
class LocalSenseVoiceASRProvider : ASRProvider<ASRProviderSetting.LocalSenseVoiceASR> {

    /**
     * 实时识别：VAD 分段 → 只在句子结束时做一次整段本地推理。
     * 说明：复用 OnlineASRProvider 的 AudioRecord + VAD 框架，
     * 但去掉切片/Partial 伪流式逻辑，只用 VAD segment 触发 final。
     */
    @SuppressLint("MissingPermission")
    override fun startRecognition(
        context: Context,
        providerSetting: ASRProviderSetting.LocalSenseVoiceASR,
        preRollPcm: List<ShortArray>?
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

        // 初始化 VAD（与 OnlineASRProvider 框架一致）
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

        // 权限检查：确保调用方已授予 RECORD_AUDIO
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
                // 使用 VOICE_COMMUNICATION 与打断检测链路一致，避免 HAL 层模式切换造成的音量畸变
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
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

        Log.i(TAG, "startRecognition (non-streaming): lang=${providerSetting.language}, useItn=${providerSetting.useItn}, threads=${providerSetting.numThreads}")

        try {
            audioRecord.startRecording()
            val buffer = ShortArray(WINDOW_SIZE)

            // 累积 PCM 缓冲区（Float，-1..1）
            val accumulatedPcm = ArrayList<Float>(SAMPLE_RATE * 4)
            // 轻量 RMS 能量检测：判断是否有语音
            var inSpeech = false
            var lastSpeechEnergyAtMs = 0L
            // 开始说话后给 UI 发一次占位 partial（isFinal=false, text 空），
            // 用于通话界面的"正在听…"提示和波形动画驱动
            var listeningHintSent = false
            // Final 后静默期，防止回声/环境噪声触发空识别
            var finalGracePeriodUntil = 0L

            // === 预卷喂入：打断场景下，把上一轮打断检测收集的用户开头音频喂给 VAD ===
            // 关键：这一步必须在 read 循环开始前完成，否则用户抢话的开头字会被新开 AudioRecord 丢失
            if (!preRollPcm.isNullOrEmpty()) {
                val preRollStartNs = System.nanoTime()
                var preRollFrames = 0
                var preRollSamples = 0
                for (frame in preRollPcm) {
                    if (frame.isEmpty()) continue
                    val samples = FloatArray(frame.size) { frame[it] / 32768.0f }
                    // 喂给 VAD（让 VAD 看到这段音频的语音/静音结构）
                    vad.acceptWaveform(samples)
                    // 同时累积到 accumulatedPcm（这样 VAD 产出 segment 时能拿到完整音频）
                    accumulatedPcm.addAll(samples.asList())
                    preRollFrames++
                    preRollSamples += frame.size

                    // 同步更新 RMS 状态：如果预卷里就有语音能量，把 inSpeech 置 true
                    var sumSq = 0.0
                    for (s in samples) sumSq += (s * s).toDouble()
                    val rms = sqrt(sumSq / samples.size).toFloat()
                    if (rms > RMS_SPEECH_THRESHOLD) {
                        inSpeech = true
                        lastSpeechEnergyAtMs = System.currentTimeMillis()
                        if (!listeningHintSent && isActive) {
                            listeningHintSent = true
                            send(ASRResult(text = "", isFinal = false))
                        }
                    }
                }
                val preRollMs = preRollSamples * 1000 / SAMPLE_RATE
                Log.i(TAG, "[PreRoll] 喂入 ${preRollFrames} 帧 / ${preRollMs}ms, inSpeech=$inSpeech, cost=${(System.nanoTime() - preRollStartNs) / 1_000_000}ms")

                // 立即尝试消费 VAD 已产出的 segment（用户可能预卷里就说完一句）
                while (!vad.empty() && isActive) {
                    val segment = vad.front()
                    vad.pop()
                    if (segment.samples.isNotEmpty()) {
                        val finalSamples = accumulatedPcm.toFloatArray()
                        accumulatedPcm.clear()
                        val decodeStartNs = System.nanoTime()
                        val finalText = decodeSamples(recognizer, finalSamples)
                        val decodeCostMs = (System.nanoTime() - decodeStartNs) / 1_000_000
                        if (finalText.isNotBlank() && isActive) {
                            Log.i(TAG, "[PreRoll Final] 预卷内已产出句子 cost=${decodeCostMs}ms 结果=\"$finalText\"")
                            send(ASRResult(text = finalText, isFinal = true))
                        }
                        inSpeech = false
                        listeningHintSent = false
                        finalGracePeriodUntil = System.currentTimeMillis() + FINAL_GRACE_PERIOD_MS
                        vad.reset()
                    }
                }
            }

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
                    // 首次检测到语音 → 发一个空的占位 partial，
                    // 让通话 UI 知道"听到声音了"，可以点亮波形动画/显示"正在听…"
                    if (!listeningHintSent && isActive) {
                        listeningHintSent = true
                        send(ASRResult(text = "", isFinal = false))
                    }
                } else if (inSpeech && now - lastSpeechEnergyAtMs > 600L) {
                    inSpeech = false
                }

                // --- 2. 累积 PCM（仅在语音期间或已有累积时） ---
                if (inSpeech || accumulatedPcm.isNotEmpty()) {
                    accumulatedPcm.addAll(samples.asList())
                }

                // --- 3. VAD 产出完整 segment：唯一一次整段推理 + 发 Final ---
                //    不再做 Partial 伪流式推理：只等 VAD 判定用户说完，才解码整段。
                //    SenseVoice 推理速度 17x 实时（10s 音频 ~100ms），体感延迟可接受。
                while (!vad.empty() && isActive) {
                    val segment = vad.front()
                    vad.pop()

                    if (segment.samples.isEmpty() && accumulatedPcm.isEmpty()) continue

                    val finalSamples = accumulatedPcm.toFloatArray()
                    accumulatedPcm.clear()

                    val finalMs = finalSamples.size * 1000 / SAMPLE_RATE
                    Log.i(TAG, "[Final] 句子结束, 音频=${finalMs}ms, 开始本地推理...")
                    val decodeStartNs = System.nanoTime()

                    val finalText = decodeSamples(recognizer, finalSamples)
                    val decodeCostMs = (System.nanoTime() - decodeStartNs) / 1_000_000

                    if (finalText.isNotBlank() && isActive) {
                        Log.i(TAG, "[Final] 推理完成 cost=${decodeCostMs}ms 结果=\"$finalText\"")
                        send(ASRResult(text = finalText, isFinal = true))
                    } else if (finalMs > 500) {
                        Log.d(TAG, "[Final] 音频 ${finalMs}ms 但识别为空，可能是噪声（cost=${decodeCostMs}ms）")
                    }

                    // 重置状态
                    inSpeech = false
                    listeningHintSent = false
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
        private const val MAX_SPEECH_DURATION_SEC = 120F

        // RMS 能量阈值（适配通话场景：近距离拿手机说话音量偏小、可能经 AEC 处理，阈值低于普通录音）
        //  注：transcribeFile 不走 RMS，降低此阈值不影响语音消息的文件转录准确性
        private const val RMS_SPEECH_THRESHOLD = 0.005F

        // Final 后静默期：1.0s（Final 后立刻开始下一句时的回声/噪声保护）
        private const val FINAL_GRACE_PERIOD_MS = 800L

        // 模型文件路径常量（与 SenseVoiceModelManager 保持一致）
        const val MODEL_DIR = "sensevoice"
        const val MODEL_FILE_NAME = "model.int8.onnx"
        const val TOKENS_FILE_NAME = "tokens.txt"
    }
}
