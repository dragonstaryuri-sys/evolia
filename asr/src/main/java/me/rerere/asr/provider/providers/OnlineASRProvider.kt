package me.rerere.asr.provider.providers

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toFile
import java.io.File
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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

    @SuppressLint("MissingPermission")
    override fun startRecognition(
        context: Context,
        providerSetting: ASRProviderSetting.OnlineASR,
        preRollPcm: List<ShortArray>?
    ) = channelFlow @androidx.annotation.RequiresPermission(Manifest.permission.RECORD_AUDIO) {
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

        // 权限检查：确保调用方已授予 RECORD_AUDIO
        val hasRecordPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasRecordPermission) {
            vad.release()
            close(SecurityException("缺少RECORD_AUDIO权限，请先授予麦克风权限。"))
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
                // 更大的缓冲：伪流式期间持续录音，防止后台上传时缓冲溢出
                maxOf(minBuf * 4, WINDOW_SIZE * 16 * 2)
            )
        } catch (e: SecurityException) {
            vad.release()
            close(e)
            return@channelFlow
        }

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
            // 连续帧计数：用于 onset 判定（防敲桌子等冲击声触发 inSpeechAux）
            var consecutiveOnsetFrames = 0
            // 切片开始的毫秒时间戳（用于计算切片时长）
            var sliceStartMs = 0L
            val scope = this // channelFlow 的 ProducerScope，用于 launch 异步上传

            // ★ 并发控制：同一时间只允许一个切片在 API 请求中
            //   前一个切片未返回时，新的切片不会触发，避免请求堆积和竞态
            //   ★ 超时放行：如果 API 卡了 >3s，强制放行新切片（旧切片用 CAS 防覆盖）
            val sliceInFlight = AtomicBoolean(false)
            var sliceInFlightStartMs = 0L

            // ★ 竞态保护：每次 Final 时递增 epoch，切片返回时检查 epoch 是否过期
            //   如果切片上传后 epoch 变了，说明 Final 已经执行了，丢弃该切片结果
            val sliceEpoch = AtomicInteger(0)

            // ★ Final 非阻塞改造：当 VAD 产出 segment 时，read 循环不阻塞等待 in-flight slice；
            //   而是立刻 snapshot 状态 + 换 epoch，把旧 epoch 下 in-flight slice 返回的"新增文本"
            //   临时捕获到这里（CAS 循环追加），供异步 Final 协程拼接最终 prompt 用。
            //   null=未在等待 Final；非 null=正在等待，值是已经捕获到的 bonus 文本（可能为空串）。
            val pendingFinalInFlightBonus: AtomicReference<String?> = AtomicReference(null)

            // ★ Final 后静默期：Final 后一段时间内忽略噪声，不累积 slicePcm
            //   防止环境噪声/回声持续触发 inSpeechAux → 空切片循环
            var finalGracePeriodUntil = 0L

            // ★ 连续空结果计数：连续 N 次切片返回空文本 → 判定为纯噪声，停止上传
            var consecutiveEmptyCount = 0

            // === 预卷喂入：打断场景下，把上一轮打断检测收集的用户开头音频喂给 VAD + slicePcm ===
            // 关键：不再使用能量门控筛选，而是全量塞入，确保“话头”绝对完整。
            if (!preRollPcm.isNullOrEmpty()) {
                val preRollStartNs = System.nanoTime()
                var preRollFramesCount = 0 // 计数器，用于打日志
                var preRollSamplesCount = 0
                val now0 = System.currentTimeMillis()

                // 预置开始时间，防止首个切片时长计算为 0
                sliceStartMs = now0

                for ((idx, frame) in preRollPcm.withIndex()) {
                    if (frame.isEmpty()) continue
                    val samples = FloatArray(frame.size) { frame[it] / 32768.0f }

                    // 1. 依然喂给 VAD，保持 VAD 状态机对后续音频的连贯判定
                    vad.acceptWaveform(samples)

                    // 2. 更新基础 RMS 状态，为后续的切片停顿检测做准备
                    var sumSq = 0.0
                    for (s in samples) sumSq += (s * s).toDouble()
                    val rms = sqrt(sumSq / samples.size).toFloat()
                    val fakeNow = now0 + idx * (frame.size * 1000 / SAMPLE_RATE)

                    // 更新 onset/offset 状态，但不再作为累积的前提
                    if (rms > RMS_SPEECH_THRESHOLD) {
                        consecutiveOnsetFrames++
                        if (!inSpeechAux && consecutiveOnsetFrames >= SPEECH_ONSET_FRAMES) {
                            inSpeechAux = true
                        }
                        if (inSpeechAux) lastSpeechEnergyAtMs = fakeNow
                    } else if (rms > RMS_SPEECH_OFFSET_THRESHOLD) {
                        if (inSpeechAux && fakeNow - lastSpeechEnergyAtMs > 600L) {
                            inSpeechAux = false
                        }
                    } else {
                        consecutiveOnsetFrames = 0
                        if (inSpeechAux && fakeNow - lastSpeechEnergyAtMs > 600L) {
                            inSpeechAux = false
                        }
                    }

                    // 3. 【核心修改】全量累积预卷音频，不再判断 inSpeechAux
                    slicePcm.addAll(samples.asList())

                    preRollFramesCount++
                    preRollSamplesCount += frame.size
                }

                // 4. 强制开启说话状态：既然有预卷数据，就认为用户已经开始说话了
                inSpeechAux = true
                if (lastSpeechEnergyAtMs == 0L) lastSpeechEnergyAtMs = System.currentTimeMillis()

                val preRollMs = preRollSamplesCount * 1000 / SAMPLE_RATE
            }

            while (isActive) {
                val read = audioRecord.read(buffer, 0, WINDOW_SIZE)
                if (read <= 0) continue

                val samples = FloatArray(read) { buffer[it] / 32768.0f }
                vad.acceptWaveform(samples)

                val now = System.currentTimeMillis()

                // --- 1. 轻量 RMS 能量检测（双阈值迟滞 + 连续帧判定） ---
                // ★ Final 静默期内强制忽略能量，防止噪声/回声触发 inSpeechAux
                val inGracePeriod = now < finalGracePeriodUntil
                var sumSq = 0.0
                for (s in samples) sumSq += (s * s).toDouble()
                val rms = sqrt(sumSq / samples.size).toFloat()
                if (!inGracePeriod && rms > RMS_SPEECH_THRESHOLD) {
                    // 高于 onset 阈值：实际语音 → 刷新 lastSpeechEnergyAtMs
                    consecutiveOnsetFrames++
                    if (!inSpeechAux && consecutiveOnsetFrames >= SPEECH_ONSET_FRAMES) {
                        sliceStartMs = now
                        inSpeechAux = true
                    }
                    if (inSpeechAux) lastSpeechEnergyAtMs = now
                } else if (!inGracePeriod && inSpeechAux && rms > RMS_SPEECH_OFFSET_THRESHOLD) {
                    // offset 区间（onset > rms > offset）：维持 inSpeechAux = true（不中断 PCM 累积），
                    // 但**不刷新 lastSpeechEnergyAtMs** → silenceMs 会增长 → 停顿能被检测到 → 切片能触发。
                    // 之前这里也刷新了 lastSpeechEnergyAtMs → 环境噪音（如 0.0107）一直刷 → silenceMs 永远=0 → 永远不切片。
                    // 600ms 无实际语音 → 关闭 inSpeechAux（防噪音无限维持）
                    if (now - lastSpeechEnergyAtMs > 600L) {
                        inSpeechAux = false
                    }
                } else {
                    consecutiveOnsetFrames = 0
                    if (inSpeechAux && now - lastSpeechEnergyAtMs > 600L) {
                        inSpeechAux = false
                    }
                }

                // --- 2. 累积当前切片 PCM ---
                if (inSpeechAux || slicePcm.isNotEmpty()) {
                    slicePcm.addAll(samples.asList())
                }

                // --- 2.1 切片诊断日志：每累积 5s 打一行帮助排查 ---
                val sliceMs = slicePcm.size * 1000 / SAMPLE_RATE
                val silenceMs = now - lastSpeechEnergyAtMs

                // --- 3. 触发切片上传（Partial）：停顿触发 + 超长安全保护切片 ---
                // 主要触发：检测到 ≥SHORT_SILENCE_SLICE_MS 的自然停顿（用户换气/断句）
                // 安全触发：累积 ≥MAX_SLICE_MS 并且当前帧 RMS 低于阈值（至少是换气间隙），强制切
                // ★ 并发控制 + 超时放行：
                //   前一个切片 API 未返回时，不触发新切片 → 避免请求堆积
                //   但如果 API 卡了 >3s，强制放行（旧切片用前缀检查防覆盖）
                //   ★ 噪声保护：连续 ≥2 次空结果且没有已识别文本 → 停止无意义的空切片上传
                //     但当用户开始新一句话（VAD 产出 segment → 重置计数器）时恢复
                val pauseTriggered = sliceMs >= MIN_SLICE_MS && silenceMs >= SHORT_SILENCE_SLICE_MS
                // 超长保护：累积 ≥MAX_SLICE_MS 且当前帧低于 offset 阈值（确认不在说话了）才切
                // 用 offset 而非 onset：避免说话中短暂低音量帧触发误切
                val maxLenTriggered = sliceMs >= MAX_SLICE_MS && rms <= RMS_SPEECH_OFFSET_THRESHOLD
                val inflightTimedOut = sliceInFlight.get() && (now - sliceInFlightStartMs) > SLICE_INFLIGHT_TIMEOUT_MS
                if (inflightTimedOut) {
                    sliceInFlight.set(false)
                }
                // 噪声保护：连续空切片时暂停，但只在 promptText 为空时（没有已识别内容）
                val noisePaused = consecutiveEmptyCount >= 2 && recognizedText.get().isEmpty()
                val canSlice = !sliceInFlight.get() && !noisePaused
                val shouldSlice = (pauseTriggered || maxLenTriggered) && canSlice && slicePcm.isNotEmpty()
                if (shouldSlice) {
                    // 切片快照
                    val sliceSamples = slicePcm.toFloatArray()
                    slicePcm.clear()
                    sliceInFlight.set(true)
                    sliceInFlightStartMs = now
                    // 当前已识别文本快照（作为 prompt 传给 Whisper）
                    val promptText = recognizedText.get()
                    // 捕获当前 epoch，用于返回时检查是否已被 Final 取代
                    val capturedEpoch = sliceEpoch.get()
                    val triggerReason = if (pauseTriggered) "停顿触发" else "超长保护触发"

                    scope.launch(Dispatchers.IO) {
                        try {
                            val sliceText = try {
                                val pcm = ShortArray(sliceSamples.size) {
                                    (sliceSamples[it] * 32767f).toInt().toShort()
                                }
                                val wavBytes = pcmToWav(pcm, SAMPLE_RATE)
                                val result = transcribe(wavBytes, providerSetting, promptText)
                                result
                            } catch (e: Exception) {
                                Log.w(TAG, "Slice transcribe failed", e)
                                ""
                            }
                            // ★ 竞态检查：如果 epoch 变了，说明 Final 已经执行了
                            if (capturedEpoch != sliceEpoch.get()) {
                                // --- Final 非阻塞改造：如果有 pending Final 正在等待旧 epoch 的 in-flight 结果，
                                //     把该切片的"新增识别文本"CAS 追加到 bonus 里，供 Final 协程最后拼接。
                                if (sliceText.isNotBlank()) {
                                    val bonusHolder = pendingFinalInFlightBonus
                                    var cur = bonusHolder.get()
                                    while (cur != null) {
                                        // cur != null 表示 Final 正在等待（有哨兵），追加新增文本
                                        val newBonus = if (cur.isEmpty()) sliceText.trim() else (cur + sliceText.trim())
                                        if (bonusHolder.compareAndSet(cur, newBonus)) {
                                            Log.i(TAG, "[SliceBonus] captured bonus \"${sliceText.take(20)}\" → pendingFinal total=${newBonus.length}")
                                            break
                                        }
                                        cur = bonusHolder.get() // CAS 失败，重读并重试
                                    }
                                }
                                return@launch
                            }
                            if (sliceText.isNotBlank() && isActive) {
                                consecutiveEmptyCount = 0  // 有内容，重置空计数器
                                // ★ 前缀检查追加（替代 CAS）：
                                //   超时放行后，两个切片可能并发返回。
                                //   切片A上传时 promptText="foo"，切片B也上传时 promptText="foo"
                                //   切片A先返回 → recognizedText = "foobar"
                                //   切片B返回时：检查 recognizedText 是否以 "foo" 开头
                                //     → 是 → 追加到当前 recognizedText 末尾："foobar" + "baz" = "foobarbaz"
                                //     → 否 → epoch mismatch，丢弃
                                val currentText = recognizedText.get()
                                val newText = when {
                                    // 正常情况：recognizedText 没变
                                    currentText == promptText -> {
                                        if (promptText.isEmpty()) sliceText.trim()
                                        else (promptText + sliceText.trim())
                                    }
                                    // 超时放行：前一个切片已更新 recognizedText，追加到当前末尾
                                    promptText.isNotEmpty() && currentText.startsWith(promptText) -> {
                                        currentText + sliceText.trim()
                                    }
                                    // epoch mismatch：recognizedText 被清空或不匹配，丢弃
                                    else -> {
                                        null
                                    }
                                }
                                if (newText != null) {
                                    recognizedText.set(newText)
                                    if (isActive) {
                                        send(ASRResult(text = newText, isFinal = false))
                                    }
                                }
                            } else {
                                // 空结果：噪声。递增计数器
                                consecutiveEmptyCount++
                                // ★ 无条件清理：不管 promptText 是否为空都清。
                                //   之前只有 promptText.isEmpty() 才清 → 用户说完一句话后，
                                //   噪声切片返回空但不清 slicePcm → 噪声持续累积 → Whisper 把噪声识别成 "嗯""ja."
                                slicePcm.clear()
                                inSpeechAux = false
                                consecutiveOnsetFrames = 0
                            }
                        } finally {
                            sliceInFlight.set(false)
                        }
                    }
                }

                // --- 4. VAD 产出完整 segment：Final（★ 非阻塞改造版）---
                //    原实现缺陷：当 sliceInFlight=true 时，read 循环 while+delay(20) 阻塞最多 3s，
                //       → AudioRecord.read() 不执行 → 缓冲溢出 → 用户音频丢帧。
                //    新方案（三阶段）：
                //       (1) snapshot 阶段（read 循环内，微秒级）：立刻换 epoch + 原子拷贝状态 +
                //           清空原缓冲 → read 循环立刻继续录音，不丢帧。
                //       (2) bonus 捕获：旧 epoch 下 in-flight slice 返回时，因 epoch mismatch
                //           触发 bonus CAS 追加 → 新增识别文字被抢救回来，不被丢弃。
                //       (3) 异步 Final 协程：最多等 3s 让 sliceInFlight settle（此时 read 循环
                //           已在处理新的一轮音频，互不影响） → 拼接 bonus → 上传 Final。
                while (!vad.empty() && isActive) {
                    val segment = vad.front()
                    vad.pop()

                    if (segment.samples.isEmpty()) continue

                    // =============================================
                    // (1) SNAPSHOT：read 循环内只做最轻量的原子拷贝，绝不阻塞
                    // =============================================
                    // 换 epoch：旧 in-flight slice 返回时 capturedEpoch != sliceEpoch.get() → 走 bonus 捕获分支
                    val oldEpoch = sliceEpoch.getAndIncrement()
                    // 原子抢出当前 prompt（之前所有已返回切片的拼接文本），同时清空留給下一轮
                    val snapshotPrompt = recognizedText.getAndSet("")
                    // 拷贝剩余尾巴 PCM，并清空原缓冲留給下一轮
                    val snapshotTailSamples = slicePcm.toFloatArray()
                    slicePcm.clear()
                    val snapshotTailMs = snapshotTailSamples.size * 1000 / SAMPLE_RATE
                    val hadPartial = snapshotPrompt.isNotEmpty()

                    // 立刻设置 pending Final 的 bonus 哨兵：in-flight slice 返回时会 CAS 追加进来
                    // 注意：必须在换 epoch 之后、启动异步协程之前设置，避免窗口漏捕获
                    pendingFinalInFlightBonus.set("")

                    // 立刻清理 read 循环侧的状态（让下一轮录音从零开始，不依赖异步 Final 协程的时序）
                    inSpeechAux = false
                    consecutiveOnsetFrames = 0
                    consecutiveEmptyCount = 0
                    val snapshotFinalGraceMs = System.currentTimeMillis() + FINAL_GRACE_PERIOD_MS
                    finalGracePeriodUntil = snapshotFinalGraceMs
                    vad.reset()
                    // NOTE：**不碰 sliceInFlight**！旧 in-flight slice 返回时会在 finally 里把它设 false；
                    //       期间 read 循环侧 canSlice = !sliceInFlight 会自动阻止新一轮切片上传，
                    //       既不堆积请求，也不会影响 read() 本身的执行。

                    Log.i(TAG, "┌─[Final 句子结束] snapshot epoch=$oldEpoch→${sliceEpoch.get()} " +
                        "tailMs=$snapshotTailMs prompt=\"${snapshotPrompt.take(30)}\" " +
                        "inFlight=${sliceInFlight.get()} → 异步处理")

                    // =============================================
                    // (3) 启动异步 Final 协程（IO 线程，不阻塞 read 循环）
                    // =============================================
                    scope.launch(Dispatchers.IO) {
                        // --- Step A. 等待 sliceInFlight settle（最多 SLICE_INFLIGHT_TIMEOUT_MS）---
                        if (sliceInFlight.get()) {
                            val waitStart = System.currentTimeMillis()
                            var waited = 0L
                            while (sliceInFlight.get() && isActive) {
                                if (waited >= SLICE_INFLIGHT_TIMEOUT_MS) {
                                    Log.w(TAG, "[FinalAsync] In-flight slice timeout (${waited}ms), proceeding")
                                    break
                                }
                                delay(20)
                                waited += 20
                            }
                            Log.i(TAG, "[FinalAsync] In-flight slice settled after ${System.currentTimeMillis() - waitStart}ms")
                        }
                        // 关 bonus 哨兵（取的同时置 null，避免后续 epoch 的 slice 被错误捕获）
                        val inFlightBonus = pendingFinalInFlightBonus.getAndSet(null).orEmpty()

                        // --- Step B. 组合最终 prompt：snapshot 的文本 + in-flight 抢救回来的 bonus ---
                        val finalPrompt = when {
                            snapshotPrompt.isEmpty() && inFlightBonus.isEmpty() -> ""
                            snapshotPrompt.isEmpty() -> inFlightBonus
                            inFlightBonus.isEmpty() -> snapshotPrompt
                            else -> snapshotPrompt + inFlightBonus  // 直接拼接（都是按顺序识别的）
                        }
                        if (inFlightBonus.isNotEmpty()) {
                            Log.i(TAG, "[FinalAsync] captured in-flight bonus len=${inFlightBonus.length} \"${inFlightBonus.take(20)}\"")
                        }

                        // --- Step C. 发 Final（两种情况：有尾巴音频 / 无尾巴音频直接用 prompt）---
                        if (snapshotTailSamples.isEmpty()) {
                            if (finalPrompt.isNotBlank() && isActive) {
                                Log.i(TAG, "└─[Final 直接发送] (无剩余音频) hadPartial=$hadPartial final=\"$finalPrompt\"")
                                send(ASRResult(text = finalPrompt, isFinal = true))
                            }
                            return@launch
                        }
                        val pcm = ShortArray(snapshotTailSamples.size) {
                            (snapshotTailSamples[it] * 32767f).toInt().toShort()
                        }
                        val wavBytes = pcmToWav(pcm, SAMPLE_RATE)
                        val tailText = try {
                            transcribe(wavBytes, providerSetting, finalPrompt)
                        } catch (e: Exception) {
                            Log.e(TAG, "Final transcribe failed", e)
                            ""
                        }
                        val finalText = when {
                            tailText.isNotBlank() && finalPrompt.isEmpty() -> tailText.trim()
                            tailText.isNotBlank() && finalPrompt.isNotEmpty() -> (finalPrompt + tailText.trim())
                            tailText.isBlank() && finalPrompt.isNotEmpty() -> {
                                Log.i(TAG, "Final transcribe returned empty, falling back to finalPrompt")
                                finalPrompt
                            }
                            else -> null
                        }
                        if (finalText != null && isActive) {
                            Log.i(TAG, "└─[Final 最终发送] tail=\"${tailText.trim().take(20)}\" final=\"$finalText\"")
                            send(ASRResult(text = finalText, isFinal = true))
                        }
                    }
                    // NOTE: read 循环立即继续，绝不等待上面的异步协程！
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
     * 上传音频字节到云端转录 API。
     *
     * 根据 URL 自动选择请求模式：
     *  - 若 URL 路径包含 `/chat/completions`（例如 DashScope /compatible-mode/v1/chat/completions）：
     *    使用 Chat Completions 的 `input_audio` content 格式，音频以 Data URI（Base64）嵌入 JSON body 发送。
     *  - 其他情况（标准 `/audio/transcriptions` 等 Whisper 兼容端点）：
     *    使用 multipart/form-data 上传音频文件。
     *
     * [prompt] 用于上下文续接（可选）：把之前切片的识别结果作为 prompt 传给 Whisper，
     * 让后续切片的识别有上下文。仅在非空时添加到请求。
     */
    private suspend fun transcribeBytes(
        bytes: ByteArray,
        filename: String,
        mime: String,
        setting: ASRProviderSetting.OnlineASR,
        prompt: String = ""
    ): String {
        val url = setting.apiUrl.trimEnd('/')
        val useChatCompletionsMode = url.endsWith("/chat/completions", ignoreCase = true)

        return if (useChatCompletionsMode) {
            transcribeChatCompletions(bytes, filename, mime, setting, url, prompt)
        } else {
            transcribeWhisperMultipart(bytes, filename, mime, setting, url, prompt)
        }
    }

    /**
     * 标准 Whisper 兼容模式：multipart/form-data 上传（适用于 OpenAI / SiliconFlow / Groq 等）。
     */
    private fun transcribeWhisperMultipart(
        bytes: ByteArray,
        filename: String,
        mime: String,
        setting: ASRProviderSetting.OnlineASR,
        url: String,
        prompt: String
    ): String {
        val audioBody = bytes.toRequestBody(mime.toMediaType())

        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", filename, audioBody)
            .addFormDataPart("model", setting.model)
            .addFormDataPart("language", setting.language)
            .addFormDataPart("response_format", "json")

        if (prompt.isNotBlank()) {
            val truncatedPrompt = prompt.takeLast(200)
            builder.addFormDataPart("prompt", truncatedPrompt)
        }

        val requestBody = builder.build()
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${setting.apiKey}")
            .post(requestBody)
            .build()

        Log.d(TAG, "transcribeWhisperMultipart: POST $url file=$filename mime=$mime bytes=${bytes.size} model=${setting.model} promptLen=${prompt.length}")
        val response = client.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string() ?: ""
                val headersDump = resp.headers.toMultimap().entries.joinToString(";") { (k, v) -> "$k=${v.firstOrNull()}" }
                Log.e(TAG, "transcribeWhisperMultipart: FAILED code=${resp.code} headers=$headersDump errBody=$errBody")
                val hint = buildModelCompatibilityHint(setting.model, errBody)
                val msgPrefix = "ASR API ${resp.code}: ${errBody.take(200)}"
                throw RuntimeException(if (hint.isNotBlank()) "$msgPrefix\n$hint" else msgPrefix)
            }
            val body = resp.body?.string() ?: ""
            return json.decodeFromString<TranscriptionResponse>(body).text
        }
    }

    /**
     * DashScope 兼容模式：通过 Chat Completions 的 `input_audio` content 类型发送音频。
     * 音频编码为 Data URI（data:<mime>;base64,<data>），作为 user message 的 content 发送。
     *
     * 参考：DashScope / Alibaba QwenCloud ASR OpenAI Compatible 文档
     *  - POST /compatible-mode/v1/chat/completions
     *  - messages[0].content[0].type = "input_audio"
     *  - messages[0].content[0].input_audio.data = "<Data URI>"
     *  - 响应：choices[0].message.content 即为识别文本
     */
    private fun transcribeChatCompletions(
        bytes: ByteArray,
        filename: String,
        mime: String,
        setting: ASRProviderSetting.OnlineASR,
        url: String,
        prompt: String
    ): String {
        val base64 = Base64.getEncoder().encodeToString(bytes)
        val dataUri = "data:$mime;base64,$base64"
        // 从 mime 推导音频格式标识（DashScope input_audio.format 必填）
        // DashScope 文档支持的格式: wav, mp3, aac, flac, opus, ogg, amr, webm, pcm
        val audioFormat = deriveAudioFormat(mime, filename)

        // 构造消息结构
        val inputAudioObj = JSONObject().apply {
            put("data", dataUri)
            put("format", audioFormat)
            // input_audio.language 也可以在文档里放这里，和 asr_options.language 二选一就行
            if (setting.language.isNotBlank()) {
                put("language", setting.language)
            }
        }
        val contentItem = JSONObject().apply {
            put("type", "input_audio")
            put("input_audio", inputAudioObj)
        }
        // 如果有 prompt（上下文续接），作为第二条 text content 项一起传入（多模态并行支持）
        val contentArray = JSONArray().apply {
            put(contentItem)
            if (prompt.isNotBlank()) {
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", "上下文（上一段识别结果前缀）：${prompt.takeLast(200)}")
                })
            }
        }
        val message = JSONObject().apply {
            put("role", "user")
            put("content", contentArray)
        }

        // asr_options：语言 + ITN 配置（非标准参数，DashScope 兼容模式下通过外层键透传）
        val asrOptions = JSONObject().apply {
            if (setting.language.isNotBlank()) put("language", setting.language)
            // prompt 参数在兼容模式下也可以放入 asr_options
            if (prompt.isNotBlank()) {
                // 注意：asr_options 一般没有 prompt 字段，这里还是用 content 里的 text 做上下文更稳妥
            }
        }

        val payload = JSONObject().apply {
            put("model", setting.model)
            put("stream", false)
            put("messages", JSONArray().put(message))
            if (asrOptions.length() > 0) {
                put("asr_options", asrOptions)
            }
        }

        val jsonMediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            ?: "application/json".toMediaType()
        val requestBody: RequestBody = payload.toString().toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${setting.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        Log.d(TAG, "transcribeChatCompletions: POST $url mime=$mime format=$audioFormat bytes=${bytes.size} base64Len=${base64.length} model=${setting.model} lang=${setting.language}")
        val response = client.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string() ?: ""
                val headersDump = resp.headers.toMultimap().entries.joinToString(";") { (k, v) -> "$k=${v.firstOrNull()}" }
                Log.e(TAG, "transcribeChatCompletions: FAILED code=${resp.code} headers=$headersDump errBody=$errBody")
                val hint = buildModelCompatibilityHint(setting.model, errBody)
                val msgPrefix = "ASR API ${resp.code}: ${errBody.take(200)}"
                throw RuntimeException(if (hint.isNotBlank()) "$msgPrefix\n$hint" else msgPrefix)
            }
            val body = resp.body?.string() ?: ""
            return parseChatCompletionsText(body)
        }
    }

    /**
     * 从 Chat Completions 响应 JSON 中提取识别文本。
     * 标准路径: `choices[0].message.content`
     * 兼容 DashScope 的附加路径: `output.text` / `output.output.sentence.text`
     */
    private fun parseChatCompletionsText(body: String): String {
        if (body.isBlank()) return ""
        return try {
            val root = JSONObject(body)
            // 1) 标准 OpenAI Chat Completions
            val choices = root.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val firstChoice = choices.optJSONObject(0) ?: return ""
                val msg = firstChoice.optJSONObject("message") ?: return ""
                return msg.optString("content", "").trim()
            }
            // 2) DashScope 同步多模态响应: output.text
            val output = root.optJSONObject("output")
            if (output != null) {
                val topText = output.optString("text", "").trim()
                if (topText.isNotBlank()) return topText
                // 3) output.output.sentence.text
                val innerOutput = output.optJSONObject("output")
                if (innerOutput != null) {
                    val sentence = innerOutput.optJSONObject("sentence")
                    if (sentence != null) {
                        val s = sentence.optString("text", "").trim()
                        if (s.isNotBlank()) return s
                    }
                }
            }
            // 4) 回退顶层 text 字段
            root.optString("text", "").trim()
        } catch (e: Exception) {
            Log.w(TAG, "parseChatCompletionsText error: ${e.message}, body=${body.take(200)}")
            ""
        }
    }

    /**
     * 根据当前所选模型名 + 错误体，给出更友好的兼容性提示（用于 ASR 报错时）。
     *
     * DashScope 的 ASR 模型按调用方式分四类：
     *  1. OpenAI 兼容 chat/completions（本地 DataURL 上传）—— 仅 qwen3-asr-flash 非 realtime 系列 ✅
     *  2. DashScope 原生 multimodal-generation 同步 —— qwen-audio-3.0-asr-flash / fun-asr-flash-* ❌
     *  3. WebSocket 实时流式 —— *-realtime 系列 ❌
     *  4. 异步任务轮询 —— *-filetrans / fun-asr / paraformer-* 系列 ❌
     *
     * 只有第 1 类在本实现下可用。其他类别会报 400/500，需切换到 qwen3-asr-flash。
     */
    private fun buildModelCompatibilityHint(model: String, errBody: String): String {
        val lowerModel = model.lowercase()
        val lowerErr = errBody.lowercase()
        val hints = mutableListOf<String>()

        // 模型级建议（按 DashScope 文档分类给出具体原因）
        when {
            lowerModel.contains("realtime") -> hints.add(
                "提示：realtime 系列（如 qwen3-asr-flash-realtime）必须用 WebSocket 流式接口（wss://dashscope.aliyuncs.com/api-ws/v1/realtime），不支持 HTTP POST 本地文件上传。请改用 qwen3-asr-flash（非 realtime 版本）。"
            )
            lowerModel.contains("fun-asr") -> hints.add(
                "提示：fun-asr 系列仅支持 DashScope 原生 multimodal-generation 同步接口（/api/v1/services/aigc/multimodal-generation/generation），响应结构非标准，本应用暂未适配。请改用 qwen3-asr-flash。"
            )
            lowerModel.contains("qwen-audio-3.0-asr-flash") && !lowerModel.contains("filetrans") -> hints.add(
                "提示：qwen-audio-3.0-asr-flash 仅支持 DashScope 原生 multimodal-generation 同步接口，响应结构非标准（无 choices 字段），本应用暂未适配。请改用 qwen3-asr-flash。"
            )
            lowerModel.contains("filetrans") -> hints.add(
                "提示：*-filetrans 是异步长音频转写模型，仅支持公网 URL + 任务轮询，不能本地传文件。请改用 qwen3-asr-flash。"
            )
            lowerModel.contains("paraformer") -> hints.add(
                "提示：paraformer 系列仅支持 DashScope 原生 /services/audio/asr/recognition 接口，需公网音频 URL，不兼容本地文件上传。请改用 qwen3-asr-flash。"
            )
        }

        // 错误关键字级建议
        when {
            lowerErr.contains("unsupported_format") || lowerErr.contains("format is empty") -> hints.add(
                "排查：部分旧版 ASR 模型强制要求 `input_audio.format` 字段（wav/mp3 等）。请升级到最新版 qwen3-asr-flash 或确认音频格式。"
            )
            lowerErr.contains("model_not_found") || lowerErr.contains("model not found") -> hints.add(
                "排查：该 Provider 侧不存在此模型 ID。请检查模型名是否正确，或从下拉里选一个。"
            )
            lowerErr.contains("invalid_api_key") || lowerErr.contains("incorrect_api_key") || lowerErr.contains("invalid authentication") || lowerErr.contains("unauthorized") -> hints.add(
                "排查：API Key 错误或无权限，请检查是否填了对应 Provider 的 Key（注意 DashScope 和其他 Provider Key 不通用）。"
            )
            lowerErr.contains("quota") || lowerErr.contains("too many") || lowerErr.contains("rate limit") -> hints.add(
                "排查：触发调用频率限制或余额不足，稍后重试或检查账户额度。"
            )
            lowerErr.contains("internal_error") || errBody.contains("An internal error") -> hints.add(
                "排查：服务端内部错误（500）。通常意味着该模型与当前调用方式不兼容（如 realtime 需 WebSocket、fun-asr 需 multimodal-generation 端点）。请切换到 qwen3-asr-flash 再试。"
            )
        }

        return hints.joinToString("\n")
    }

    /**
     * 根据 mime type + 文件名推导 DashScope 要求的 input_audio.format 标识。
     * DashScope 文档支持的格式: wav, mp3, aac, flac, opus, ogg, amr, webm, pcm, m4a
     */
    private fun deriveAudioFormat(mime: String, filename: String): String {
        val lowerMime = mime.lowercase()
        // 先按 mime 匹配
        return when {
            lowerMime.contains("wav") || lowerMime.contains("wave") -> "wav"
            lowerMime.contains("mpeg") || lowerMime.contains("mp3") -> "mp3"
            lowerMime.contains("x-m4a") || lowerMime.contains("mp4") && lowerMime.contains("audio") -> "m4a"
            lowerMime.contains("aac") -> "aac"
            lowerMime.contains("flac") -> "flac"
            lowerMime.contains("opus") -> "opus"
            lowerMime.contains("ogg") || lowerMime.contains("vorbis") -> "ogg"
            lowerMime.contains("amr") -> "amr"
            lowerMime.contains("webm") -> "webm"
            lowerMime.contains("pcm") || lowerMime.contains("l16") -> "pcm"
            else -> {
                // mime 匹配不上，按文件名后缀兜底
                val ext = filename.substringAfterLast('.', "").lowercase()
                when (ext) {
                    "wav" -> "wav"
                    "mp3" -> "mp3"
                    "m4a" -> "m4a"
                    "aac" -> "aac"
                    "flac" -> "flac"
                    "opus" -> "opus"
                    "ogg", "oga" -> "ogg"
                    "amr" -> "amr"
                    "webm" -> "webm"
                    "pcm" -> "pcm"
                    else -> {
                        Log.w(TAG, "deriveAudioFormat: unrecognized mime=$mime filename=$filename, fallback to wav")
                        "wav"
                    }
                }
            }
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
        // VAD final 句子端点：1.0s 静音才判定用户说完了
        // （0.8s 会把思考型停顿/换气误判为说完了 → 断句奇怪；1.0s 给用户更多缓冲）
        private const val MIN_SILENCE_DURATION_SEC = 1.0F
        private const val MIN_SPEECH_DURATION_SEC = 0.3F  // 过滤过短的噪声
        private const val MAX_SPEECH_DURATION_SEC = 30F    // 单次最长 30 秒

        // ===== 分段切片（Slice + Prompt 续接）参数 =====
        // RMS 能量阈值（双阈值迟滞 Hysteresis）
        // ONSET（开启语音）：0.012 ≈ -38dBFS。
        //   大音量扬声器场景下，AEC 残余回声 + 环境噪音 RMS ≈ 0.010-0.011，
        //   0.012 刚好高于这个噪声层，同时正常说话（0.02-0.05+）轻松通过。
        // OFFSET（维持语音）：0.006 ≈ -44dBFS。
        //   高于安静环境底噪（0.003-0.005），低于说话中的轻声/换气（0.008+）。
        //   说话中音量波动不会断 inSpeechAux，但停顿时 silenceMs 能正确增长。
        //   ★ offset 分支不刷新 lastSpeechEnergyAtMs → 停顿可被检测到 → 切片能触发。
        private const val RMS_SPEECH_THRESHOLD = 0.012F         // onset：开启语音的阈值
        private const val RMS_SPEECH_OFFSET_THRESHOLD = 0.006F   // offset：维持语音的阈值
        // 连续帧计数：至少 4 帧连续超过 onset 阈值才算"真正开始说话"（4 × 32ms = 128ms）
        private const val SPEECH_ONSET_FRAMES = 4
        // 切片最小时长：累积至少 500ms 才允许切片上传，避免过短片段浪费 API 调用
        private const val MIN_SLICE_MS = 500
        // 切片最大时长：累积 ≥3s 并且当前帧 RMS 低于阈值（换气间隙），强制切片防止长句不切
        // 这个是"安全网"，主要触发还是靠停顿；所以必须同时满足 RMS 低（至少是换气间隙）
        // 从 5s 降到 3s：减小单次上传体积，API 响应更快
        private const val MAX_SLICE_MS = 3000
        // 切片停顿阈值：检测到 ≥400ms 的自然停顿就切片
        // （250ms 会把逗号停顿/换气误判为断句 → 断句奇怪；400ms 只抓句号级停顿，
        //  明显短于 VAD final 的 1000ms，因此停顿时一定先 partial 后 final。）
        private const val SHORT_SILENCE_SLICE_MS = 400
        // Final 后静默期：3s 内忽略所有 RMS 能量，防止回声/环境音触发空切片循环
        // （1.5s 太短：用户说完后噪声仍能触发新切片 → "嗯。" "ja." 凭空出现）
        private const val FINAL_GRACE_PERIOD_MS = 3000L
        // 切片 API 超时放行：如果前一个切片 API 卡了 >3s 还没返回，强制放行新切片
        // （旧切片返回时用 CAS 检查 recognizedText 是否已被更新，避免覆盖新结果）
        // 防止一个慢 API 请求阻塞整个识别链路，导致音频堆积成巨大 Final
        private const val SLICE_INFLIGHT_TIMEOUT_MS = 3000L
    }
}
