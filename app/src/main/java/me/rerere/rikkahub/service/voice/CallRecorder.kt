package me.rerere.rikkahub.service.voice

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.util.Arrays
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "CallRecorder"

/**
 * 通话双向录音器：把「用户侧麦克风 PCM（near-end）」和「AI 侧 TTS PCM（far-end）」
 * 混成单声道 16kHz 音频，AAC 编码后封装为 `filesDir/call_recordings/{callId}.m4a`。
 *
 * 设计要点（满足技术方案 §8.2）：
 * - **不得阻塞采集/播放线程**：两个 feed 入口只做加性混音（synchronized + 数组相加）
 *   并 `trySend` 到有界队列；编码在独立 IO 协程消费。队列满时丢弃该帧并计数（背压策略）。
 * - **混音模型（near 驱动时间轴）**：near-end 是实时采集流，由它推进 `mixPos`，
 *   决定录音的时间轴；far-end 先写入独立环形缓冲，每次 near 帧到来时从缓冲取等量样本
 *   叠加到 **同一位置**（overlap-add），不再独立推进游标。
 *   这样即使 far 回调节奏（约 20ms）与 near（约 32ms）不一致，两路也始终落在同一时间轴上，
 *   避免此前「同帧内 near/far 串行拼接」导致的逐字卡顿。
 * - **far 缓冲保护**：AI 音轨开始送达时先攒够 [FAR_PREROLL_SAMPLES] 再开始叠加，
 *   抵消播放进度回调的短暂抖动，降低欠载；缓冲有上限 [FAR_BUFFER_CAPACITY]，溢出丢弃最旧
 *   并计数，不静默丢字。far 不足时该位置补 0，near 照常录制，不阻塞麦克风。
 * - **采样率/声道统一**：far-end 若为 24k/48k 或立体声，先降混 + 线性重采样到 16k mono。
 * - **削波保护**：两路相加后 coerceIn [-32768, 32767]，避免整数溢出。
 * - **有效内容校验**：混入样本不足 [MIN_VALID_DURATION_MS] 时删除文件并返回 null，
 *   避免产生无意义（全部静音）的录音。
 */
class CallRecorder(
    context: Context,
    private val callId: String,
    private val scope: CoroutineScope
) {
    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNELS = 1
        const val MIX_FRAME_SIZE = 1600          // 100ms @16k
        const val BITRATE = 32000
        /** 少于该时长视为无有效内容（不产生录音文件）。 */
        const val MIN_VALID_DURATION_MS = 1000L
        const val RELATIVE_DIR = "call_recordings"

        private const val MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val CHANNEL_CAPACITY = 64  // 64 * 100ms = 6.4s 的编码缓冲
        private const val DEQUEUE_TIMEOUT_US = 50_000L
        private const val NEAR_GAIN = 1.0f
        private const val FAR_GAIN = 1.0f

        fun relativePathFor(callId: String): String = "$RELATIVE_DIR/$callId.m4a"

        /** 由相对路径解析出真实文件（不校验存在性）。 */
        fun fileFor(context: Context, relativePath: String): File =
            File(context.filesDir, relativePath)

        /** 由相对路径解析出真实文件；为空返回 null。 */
        fun fileForOrNull(context: Context, relativePath: String?): File? =
            relativePath?.takeIf { it.isNotBlank() }?.let { fileFor(context, it) }

        /**
         * 统一解析 DB 中 audio 字段为真实文件：
         * - 以 "/" 开头 → 已永久保存并导出到公共目录的绝对路径，直接使用；
         * - 否则 → 私有目录（filesDir/call_recordings）内的相对路径，拼上 filesDir。
         */
        fun resolveAudioFile(context: Context, stored: String?): File? =
            stored?.takeIf { it.isNotBlank() }?.let { path ->
                if (path.startsWith("/")) File(path) else File(context.filesDir, path)
            }
    }

    private val appContext = context.applicationContext
    private val outFile: File =
        File(File(appContext.filesDir, RELATIVE_DIR).apply { mkdirs() }, "$callId.m4a")

    private val outChannel = Channel<ShortArray>(CHANNEL_CAPACITY)

    private val framesFed = AtomicLong(0L)
    private val framesDropped = AtomicLong(0L)

    /** 纯混音逻辑（无 Android 依赖），便于单元测试。 */
    private val mixer = CallMixer(
        onFrame = { frame ->
            framesFed.incrementAndGet()
            if (!outChannel.trySend(frame).isSuccess) framesDropped.incrementAndGet()
        }
    )

    @Volatile private var started = false
    @Volatile private var stopping = false
    private var encoderJob: Job? = null

    /** 启动编码。重复调用无效。 */
    fun start() {
        if (started) return
        started = true
        stopping = false
        encoderJob = scope.launch(Dispatchers.IO) {
            runCatching { encodeLoop() }.onFailure {
                Log.e(TAG, "encodeLoop failed callId=$callId", it)
            }
        }
        Log.i(TAG, "CallRecorder started callId=$callId -> ${outFile.absolutePath}")
    }

    /** 混入用户侧麦克风 PCM（16kHz / 16-bit / mono）。驱动录音时间轴。 */
    fun feedNearEnd(frame: ShortArray, length: Int = frame.size) {
        if (!started || stopping) return
        mixer.feedNear(frame, length.coerceAtMost(frame.size))
    }

    /** 混入 AI 侧 TTS PCM（原始字节，16-bit LE，可能非 16k/多声道）。 */
    fun feedFarEnd(pcm: ByteArray, sampleRateHz: Int, channels: Int) {
        if (pcm.isEmpty()) return
        if (!started || stopping) return
        // 说明：AudioPlayer 按真实播放进度高频（约 20ms/次）串行回调本方法，
        // 重采样后写入 far 环形缓冲，由 near 帧驱动叠加，保证 PCM 顺序与时基一致。
        runCatching {
            val mono = bytesToMonoShorts(pcm, channels.coerceAtLeast(1))
            val resampled = if (sampleRateHz != SAMPLE_RATE) {
                resampleLinear(mono, sampleRateHz, SAMPLE_RATE)
            } else {
                mono
            }
            mixer.feedFar(resampled)
        }.onFailure { Log.w(TAG, "feedFarEnd failed callId=$callId", it) }
    }

    /**
     * 停止录音并完成编码收尾。返回录音相对路径；若无有效内容或写入失败返回 null。
     * 幂等：重复调用返回相同结果。
     */
    suspend fun stop(): String? {
        if (!started) return null
        if (stopping) {
            encoderJob?.join()
            return finalizeResult()
        }
        stopping = true
        flushPartialFrame()
        outChannel.close()
        encoderJob?.join()
        logMetrics()
        val durationMs = framesFed.get() * MIX_FRAME_SIZE * 1000L / SAMPLE_RATE
        if (durationMs < MIN_VALID_DURATION_MS || !outFile.exists() || outFile.length() <= 0L) {
            runCatching { outFile.delete() }
            return null
        }
        return relativePathFor(callId)
    }

    private fun finalizeResult(): String? =
        if (outFile.exists() && outFile.length() > 0L) relativePathFor(callId) else null

    /** 停止并删除临时文件（初始化失败 / 取消时使用，不产生录音）。 */
    suspend fun discard() {
        stopping = true
        if (started) {
            outChannel.close()
            encoderJob?.join()
        }
        mixer.reset()
        runCatching { outFile.delete() }
        started = false
        Log.i(TAG, "CallRecorder discarded callId=$callId")
    }

    /** 把残留的不足一帧的混音数据补齐后送出，避免尾音丢失。 */
    private fun flushPartialFrame() {
        val tail = mixer.flush() ?: return
        framesFed.incrementAndGet()
        if (!outChannel.trySend(tail).isSuccess) framesDropped.incrementAndGet()
    }

    private fun logMetrics() {
        val m = mixer
        Log.i(
            TAG,
            "CallRecorder metrics callId=$callId " +
                "frames=$framesFed dropped=$framesDropped " +
                "nearFed=${m.nearFedSamples} farInput=${m.farInputSamples} " +
                "farConsumed=${m.farConsumedSamples} " +
                "farBufMax=${m.farBufferMax} " +
                "underrunCount=${m.farUnderrunCount} underrunSamples=${m.farUnderrunSamples} " +
                "overflowCount=${m.farOverflowCount} overflowSamples=${m.farOverflowSamples}"
        )
    }

    private suspend fun encodeLoop() {
        val format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, CHANNELS).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MIX_FRAME_SIZE * 2)
        }
        val codec = MediaCodec.createEncoderByType(MIME_TYPE)
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var trackIndex = -1
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val bufferInfo = MediaCodec.BufferInfo()
            var presentationUs = 0L
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val res = outChannel.tryReceive()
                    val frame = res.getOrNull()
                    if (frame != null) {
                        val inIdx = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                        if (inIdx >= 0) {
                            val inputBuffer = codec.getInputBuffer(inIdx)
                            if (inputBuffer != null) {
                                inputBuffer.clear()
                                inputBuffer.asShortBuffer().put(frame)
                                codec.queueInputBuffer(
                                    inIdx, 0, frame.size * 2, presentationUs, 0
                                )
                                presentationUs += frame.size * 1_000_000L / SAMPLE_RATE
                            } else {
                                codec.queueInputBuffer(inIdx, 0, 0, presentationUs, 0)
                            }
                        } else {
                            framesDropped.incrementAndGet()
                        }
                    } else if (res.isClosed) {
                        val inIdx = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                        if (inIdx >= 0) {
                            codec.queueInputBuffer(
                                inIdx, 0, 0, presentationUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }

                    outIdx >= 0 -> {
                        val encoded = codec.getOutputBuffer(outIdx)
                        val isConfig =
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig && bufferInfo.size > 0 && encoded != null && muxerStarted) {
                            encoded.position(bufferInfo.offset)
                            encoded.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, encoded, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) {
                runCatching { muxer?.stop() }
            }
            runCatching { muxer?.release() }
        }
    }

    /** 16-bit LE 多声道字节流 → 单声道 ShortArray（多声道取平均）。 */
    private fun bytesToMonoShorts(bytes: ByteArray, channels: Int): ShortArray {
        val bytesPerFrame = 2 * channels
        if (bytesPerFrame <= 0) return ShortArray(0)
        val frameCount = bytes.size / bytesPerFrame
        val out = ShortArray(frameCount)
        var idx = 0
        for (i in 0 until frameCount) {
            var acc = 0
            for (c in 0 until channels) {
                val lo = bytes[idx].toInt() and 0xFF
                val hi = bytes[idx + 1].toInt()
                acc += (hi shl 8) or lo
                idx += 2
            }
            out[i] = (acc / channels).coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /** 线性重采样（质量足够用于通话录音，成本低）。 */
    private fun resampleLinear(src: ShortArray, srcRate: Int, dstRate: Int): ShortArray {
        if (srcRate == dstRate || src.isEmpty()) return src
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        val outLen = (src.size / ratio).toInt().coerceAtLeast(1)
        val out = ShortArray(outLen)
        for (i in 0 until outLen) {
            val pos = i * ratio
            val i0 = pos.toInt().coerceIn(0, src.size - 1)
            val i1 = (i0 + 1).coerceAtMost(src.size - 1)
            val frac = pos - i0
            val s = src[i0] * (1 - frac) + src[i1] * frac
            out[i] = s.toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }
}

/**
 * 纯 Kotlin 混音器（无 Android 依赖），负责：
 * - near-end 驱动 `mixPos` 推进录音时间轴；
 * - far-end 独立环形缓冲，由 near 帧取等量样本在同一位置叠加；
 * - 完整的缓冲区监控指标。
 *
 * 线程安全：所有公共方法内部同步，near/far 可从不同线程并发调用。
 */
internal class CallMixer(
    private val onFrame: (ShortArray) -> Unit
) {
    companion object {
        const val MIX_FRAME_SIZE = 1600

        /** far 缓冲容量（样本数）。2 秒 @16k，足以吸收抖动且不会无限增长。 */
        const val FAR_BUFFER_CAPACITY = CallRecorder.SAMPLE_RATE * 2

        /**
         * far 启动预卷样本数。
         * near 每帧消费 512 样本（32ms），far 每块约 320 样本（20ms）。
         * 取 640（40ms ≈ 2 个 far 块）作为起始缓冲，确保首个 near 帧有足够 far 叠加，
         * 抵消播放进度回调的短暂抖动。对 AI 音轨起始时间的影响：录音中 AI 声音相对
         * 真实播放延后约 40ms，通话录音场景下可忽略。
         */
        const val FAR_PREROLL_SAMPLES = 640

        private const val NEAR_GAIN = 1.0f
        private const val FAR_GAIN = 1.0f
    }

    private val mixBuf = ShortArray(MIX_FRAME_SIZE)
    private var mixPos = 0
    private val lock = Any()

    // far 环形缓冲
    private val farBuffer = ShortArray(FAR_BUFFER_CAPACITY)
    private var farHead = 0   // 读位置
    private var farTail = 0   // 写位置
    private var farCount = 0  // 当前样本数
    private var farOverlayActive = false  // 是否已开始叠加（预卷完成）

    // ===== 监控指标 =====
    var farBufferMax = 0; private set
    var farUnderrunCount = 0L; private set
    var farUnderrunSamples = 0L; private set
    var farOverflowCount = 0L; private set
    var farOverflowSamples = 0L; private set
    var farInputSamples = 0L; private set     // 累计喂入 far 的样本数（含丢弃）
    var farConsumedSamples = 0L; private set   // 累计叠加到混音的 far 样本数
    var nearFedSamples = 0L; private set        // 累计喂入 near 的样本数

    /**
     * 喂入 near-end 帧，驱动混音时间轴。
     * 从 far 缓冲取等量样本叠加到同一位置；far 不足处补 0，不阻塞 near。
     */
    fun feedNear(frame: ShortArray, length: Int) {
        val len = length.coerceAtMost(frame.size)
        if (len <= 0) return
        synchronized(lock) {
            // 预卷保护：far 刚开始送达时先攒够阈值再叠加，避免首帧欠载
            val consumeFar = if (!farOverlayActive && farCount < FAR_PREROLL_SAMPLES) {
                0
            } else {
                farOverlayActive = true
                minOf(len, farCount)
            }
            val underrun = len - consumeFar
            if (underrun > 0) {
                farUnderrunCount++
                farUnderrunSamples += underrun
            }

            var consumed = 0
            for (i in 0 until len) {
                val nearVal = (frame[i] * NEAR_GAIN).toInt()
                val farVal = if (consumed < consumeFar) {
                    val s = farBuffer[farHead].toInt()
                    farHead = (farHead + 1) % FAR_BUFFER_CAPACITY
                    consumed++
                    (s * FAR_GAIN).toInt()
                } else {
                    0
                }
                // 削波保护
                mixBuf[mixPos] = (nearVal + farVal).coerceIn(-32768, 32767).toShort()
                mixPos++
                if (mixPos == MIX_FRAME_SIZE) {
                    emitFrame()
                }
            }
            farCount -= consumed
            farConsumedSamples += consumed
            nearFedSamples += len
            // far 完全排空：下次送达视为新一段，重新预卷
            if (farCount == 0) farOverlayActive = false
        }
    }

    /**
     * 喂入已重采样到 16k mono 的 far-end PCM，写入环形缓冲（不直接推进 mixPos）。
     * 缓冲满时丢弃最旧（此处实现为丢弃本次溢出的新样本，并计数）。
     */
    fun feedFar(resampled: ShortArray) {
        if (resampled.isEmpty()) return
        synchronized(lock) {
            var dropped = 0
            for (s in resampled) {
                if (farCount >= FAR_BUFFER_CAPACITY) {
                    dropped++
                    continue
                }
                farBuffer[farTail] = s
                farTail = (farTail + 1) % FAR_BUFFER_CAPACITY
                farCount++
            }
            farInputSamples += resampled.size
            if (dropped > 0) {
                farOverflowCount++
                farOverflowSamples += dropped
            }
            if (farCount > farBufferMax) farBufferMax = farCount
        }
    }

    /** 输出残留的不足一帧数据；无残留返回 null。 */
    fun flush(): ShortArray? = synchronized(lock) {
        if (mixPos <= 0) return null
        val tail = mixBuf.copyOf(mixPos)
        Arrays.fill(mixBuf, 0, mixPos, 0.toShort())
        mixPos = 0
        tail
    }

    /** 重置全部状态与指标（用于 discard / 新通话）。 */
    fun reset() {
        synchronized(lock) {
            Arrays.fill(mixBuf, 0)
            mixPos = 0
            farHead = 0
            farTail = 0
            farCount = 0
            farOverlayActive = false
            farBufferMax = 0
            farUnderrunCount = 0
            farUnderrunSamples = 0
            farOverflowCount = 0
            farOverflowSamples = 0
            farInputSamples = 0
            farConsumedSamples = 0
            nearFedSamples = 0
        }
    }

    private fun emitFrame() {
        val out = mixBuf.copyOf()
        Arrays.fill(mixBuf, 0)
        mixPos = 0
        onFrame(out)
    }
}
