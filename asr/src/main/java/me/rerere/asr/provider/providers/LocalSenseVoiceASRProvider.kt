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
        // 校验模型是否就绪（首次使用时自动从 assets 复制内置模型）
        ensureBuiltinModel(context)
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
            // 连续帧计数：用于 onset 判定（防敲桌子等冲击声触发 inSpeech）
            var consecutiveOnsetFrames = 0
            // RMS 诊断日志节流
            var lastDiagLogMs = 0L
            // RMS 滑动窗口（最近 ~1s 的帧，周期性诊断输出 min/max/avg + 超阈值帧数）
            val recentRms = ArrayDeque<Float>()
            val diagWindowFrames = SAMPLE_RATE / WINDOW_SIZE // 约 1s 的帧数（16k/512 = 31）
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
                // RMS 分布统计（min/max/avg + 语音帧数），用于调参诊断
                var preRollMinRms = Float.MAX_VALUE
                var preRollMaxRms = 0f
                var preRollRmsSum = 0.0
                var preRollSpeechFrames = 0
                for (frame in preRollPcm) {
                    if (frame.isEmpty()) continue
                    val samples = FloatArray(frame.size) { frame[it] / 32768.0f }
                    // 喂给 VAD（让 VAD 看到这段音频的语音/静音结构）
                    vad.acceptWaveform(samples)
                    // 同时累积到 accumulatedPcm（这样 VAD 产出 segment 时能拿到完整音频）
                    accumulatedPcm.addAll(samples.asList())
                    preRollFrames++
                    preRollSamples += frame.size

                    // 同步更新 RMS 状态（双阈值迟滞：onset 刷新能量，offset 维持不刷新）
                    var sumSq = 0.0
                    for (s in samples) sumSq += (s * s).toDouble()
                    val rms = sqrt(sumSq / samples.size).toFloat()
                    if (rms < preRollMinRms) preRollMinRms = rms
                    if (rms > preRollMaxRms) preRollMaxRms = rms
                    preRollRmsSum += rms
                    if (rms > RMS_SPEECH_THRESHOLD) {
                        preRollSpeechFrames++
                        consecutiveOnsetFrames++
                        if (!inSpeech && consecutiveOnsetFrames >= SPEECH_ONSET_FRAMES) {
                            inSpeech = true
                        }
                        if (inSpeech) {
                            lastSpeechEnergyAtMs = System.currentTimeMillis()
                            if (!listeningHintSent && isActive) {
                                listeningHintSent = true
                                send(ASRResult(text = "", isFinal = false))
                            }
                        }
                    } else if (rms > RMS_SPEECH_OFFSET_THRESHOLD) {
                        // offset 区间：维持 inSpeech 但不刷新 lastSpeechEnergyAtMs
                        if (inSpeech && System.currentTimeMillis() - lastSpeechEnergyAtMs > 600L) {
                            inSpeech = false
                        }
                    } else {
                        consecutiveOnsetFrames = 0
                    }
                }
                val preRollMs = preRollSamples * 1000 / SAMPLE_RATE
                val preRollAvgRms = if (preRollFrames > 0) (preRollRmsSum / preRollFrames).toFloat() else 0f
                val safeMinRms = if (preRollFrames > 0) preRollMinRms else 0f
                Log.i(TAG, "[ASRDiag][PreRoll] 喂入 ${preRollFrames}帧/${preRollMs}ms inSpeech=$inSpeech " +
                    "rms[min=${"%.4f".format(safeMinRms)} max=${"%.4f".format(preRollMaxRms)} avg=${"%.4f".format(preRollAvgRms)}] " +
                    "speechFrames=$preRollSpeechFrames " +
                    "thr=${RMS_SPEECH_THRESHOLD} offsetThr=${RMS_SPEECH_OFFSET_THRESHOLD} onsetFrames=${SPEECH_ONSET_FRAMES} " +
                    "consecOnset=$consecutiveOnsetFrames cost=${(System.nanoTime() - preRollStartNs) / 1_000_000}ms")

                // 立即尝试消费 VAD 已产出的 segment（用户可能预卷里就说完一句）
                while (!vad.empty() && isActive) {
                    val segment = vad.front()
                    vad.pop()
                    if (segment.samples.isNotEmpty() || accumulatedPcm.isNotEmpty()) {
                        // 优先使用 VAD segment 的音频（权威来源），避免 RMS 能量门控导致丢帧
                        val finalSamples = if (segment.samples.isNotEmpty()) segment.samples else accumulatedPcm.toFloatArray()
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

                // --- 1. 轻量 RMS 能量检测（双阈值迟滞：onset 刷新能量，offset 维持不刷新） ---
                val inGracePeriod = now < finalGracePeriodUntil
                var sumSq = 0.0
                for (s in samples) sumSq += (s * s).toDouble()
                val rms = sqrt(sumSq / samples.size).toFloat()
                // 更新 RMS 滑动窗口（保留最近 ~1s 的帧）
                recentRms.addLast(rms)
                while (recentRms.size > diagWindowFrames) recentRms.removeFirst()
                // [ASRDiag] 周期性诊断日志（每 1s 一次）
                // logcat 搜 "ASRDiag" 可看到全部调参日志（周期诊断 + 预卷喂入 + 过滤结果 + 打断保留）
                if (now - lastDiagLogMs > 1000) {
                    lastDiagLogMs = now
                    val accMs = accumulatedPcm.size * 1000 / SAMPLE_RATE
                    val silenceMs = if (inSpeech) now - lastSpeechEnergyAtMs else 0L
                    val winMin = if (recentRms.isNotEmpty()) recentRms.min() else 0f
                    val winMax = if (recentRms.isNotEmpty()) recentRms.max() else 0f
                    val winAvg = if (recentRms.isNotEmpty()) recentRms.average().toFloat() else 0f
                    val overThresh = recentRms.count { it > RMS_SPEECH_THRESHOLD }
                    Log.i(TAG, "[ASRDiag] rms=${"%.4f".format(rms)} " +
                        "win[min=${"%.4f".format(winMin)} max=${"%.4f".format(winMax)} avg=${"%.4f".format(winAvg)} overThr=$overThresh/${recentRms.size}] " +
                        "thr=$RMS_SPEECH_THRESHOLD offsetThr=$RMS_SPEECH_OFFSET_THRESHOLD onsetReq=$SPEECH_ONSET_FRAMES " +
                        "inSpeech=$inSpeech consecOnset=$consecutiveOnsetFrames " +
                        "accum=${accMs}ms silence=${silenceMs}ms grace=${if (inGracePeriod) "Y" else "N"}")
                }
                if (!inGracePeriod && rms > RMS_SPEECH_THRESHOLD) {
                    consecutiveOnsetFrames++
                    if (!inSpeech && consecutiveOnsetFrames >= SPEECH_ONSET_FRAMES) {
                        Log.d(TAG, "Speech started at ${accumulatedPcm.size * 1000 / SAMPLE_RATE}ms")
                        inSpeech = true
                    }
                    if (inSpeech) {
                        lastSpeechEnergyAtMs = now
                        if (!listeningHintSent && isActive) {
                            listeningHintSent = true
                            send(ASRResult(text = "", isFinal = false))
                        }
                    }
                } else if (!inGracePeriod && inSpeech && rms > RMS_SPEECH_OFFSET_THRESHOLD) {
                    // offset 区间：维持 inSpeech 但不刷新 lastSpeechEnergyAtMs → 停顿能被检测到
                    if (now - lastSpeechEnergyAtMs > 600L) {
                        inSpeech = false
                    }
                } else {
                    consecutiveOnsetFrames = 0
                    if (inSpeech && now - lastSpeechEnergyAtMs > 600L) {
                        inSpeech = false
                    }
                }

                // --- 2. 累积 PCM（始终累积，不依赖 RMS 能量门控） ---
                // 旧逻辑仅在 RMS 判定为语音时累积，但 RMS 可能漏检轻声/气声，
                // 而 VAD 已通过 acceptWaveform 收到完整音频并在 segment.samples 中返回。
                // 这里始终累积作为 fallback，segment.samples 优先使用。
                accumulatedPcm.addAll(samples.asList())

                // --- 3. VAD 产出完整 segment：唯一一次整段推理 + 发 Final ---
                //    不再做 Partial 伪流式推理：只等 VAD 判定用户说完，才解码整段。
                //    SenseVoice 推理速度 17x 实时（10s 音频 ~100ms），体感延迟可接受。
                while (!vad.empty() && isActive) {
                    val segment = vad.front()
                    vad.pop()

                    if (segment.samples.isEmpty() && accumulatedPcm.isEmpty()) continue

                    // 优先使用 VAD segment 的音频（权威来源），避免 RMS 能量门控导致丢帧
                    val finalSamples = if (segment.samples.isNotEmpty()) segment.samples else accumulatedPcm.toFloatArray()
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
        // 首次使用时自动从 assets 复制内置模型
        ensureBuiltinModel(context)
        val modelFile = File(context.filesDir, "$MODEL_DIR/$MODEL_FILE_NAME")
        val tokensFile = File(context.filesDir, "$MODEL_DIR/$TOKENS_FILE_NAME")
        if (!modelFile.exists() || !tokensFile.exists()) {
            throw RuntimeException("SenseVoice 模型未就绪，请确认知内置模型已正确打包")
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

        // VAD final 句子端点：1.0s 静音才判定用户说完了
        // （与 OnlineASR 对齐，0.8s 会把思考停顿误判为说完）
        private const val MIN_SILENCE_DURATION_SEC = 1.0F
        private const val MIN_SPEECH_DURATION_SEC = 0.3F
        private const val MAX_SPEECH_DURATION_SEC = 600F // 10min，通话模式不强制截断用户语音

        // RMS 能量阈值（双阈值迟滞）
        // LocalASR 使用 VOICE_COMMUNICATION 音频源，系统 AEC/NS 会衰减信号 → RMS 偏低
        // onset 0.001, offset 0.0005, 连续 4 帧判定
        private const val RMS_SPEECH_THRESHOLD = 0.001F
        private const val RMS_SPEECH_OFFSET_THRESHOLD = 0.0005F
        private const val SPEECH_ONSET_FRAMES = 4

        // Final 后静默期：3s 内忽略 RMS 能量，防止噪声触发新识别（与 OnlineASR 对齐）
        private const val FINAL_GRACE_PERIOD_MS = 3000L

        // 模型文件路径常量（与 SenseVoiceModelManager 保持一致）
        const val MODEL_DIR = "sensevoice"
        const val MODEL_FILE_NAME = "model.int8.onnx"
        const val TOKENS_FILE_NAME = "tokens.txt"

        // ===== 内置模型（打包在 assets 中）=====
        // assets 下的内置模型路径，首次使用时自动复制到 filesDir
        private const val ASSETS_MODEL_DIR = "sensevoice"
        private const val ASSETS_MODEL_FILE = "model.int8.onnx"
        private const val ASSETS_TOKENS_FILE = "tokens.txt"

        /**
         * 确保内置模型已复制到 filesDir（首次使用时自动执行）。
         * 如果 filesDir 中模型不存在但 assets 中有，则复制过去。
         * @return true 如果模型已就绪（filesDir 或 assets 中存在）
         */
        fun ensureBuiltinModel(context: Context): Boolean {
            val modelFile = File(context.filesDir, "$MODEL_DIR/$MODEL_FILE_NAME")
            val tokensFile = File(context.filesDir, "$MODEL_DIR/$TOKENS_FILE_NAME")
            if (modelFile.exists() && modelFile.length() > 0 &&
                tokensFile.exists() && tokensFile.length() > 0
            ) {
                return true // filesDir 已有模型
            }
            // 尝试从 assets 复制
            val assets = context.assets
            val modelDir = File(context.filesDir, MODEL_DIR)
            modelDir.mkdirs()
            return try {
                // 检查 assets 中是否有模型文件
                val files = assets.list(ASSETS_MODEL_DIR) ?: emptyArray()
                if (files.contains(ASSETS_MODEL_FILE) && files.contains(ASSETS_TOKENS_FILE)) {
                    Log.i(TAG, "Copying built-in model from assets to filesDir...")
                    // 复制 tokens.txt（小文件）
                    assets.open("$ASSETS_MODEL_DIR/$ASSETS_TOKENS_FILE").use { input ->
                        File(modelDir, TOKENS_FILE_NAME).outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    // 复制 model.int8.onnx（大文件）
                    assets.open("$ASSETS_MODEL_DIR/$ASSETS_MODEL_FILE").use { input ->
                        File(modelDir, MODEL_FILE_NAME).outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    val ok = modelFile.exists() && modelFile.length() > 0 &&
                        tokensFile.exists() && tokensFile.length() > 0
                    Log.i(TAG, "Built-in model copied: ok=$ok, modelSize=${modelFile.length()}")
                    ok
                } else {
                    Log.w(TAG, "Built-in model not found in assets: ${files.toList()}")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy built-in model from assets", e)
                false
            }
        }
    }
}
