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
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "CallRecorder"

/**
 * 通话双向录音器：把「用户侧麦克风 PCM（near-end）」和「AI 侧 TTS PCM（far-end）」
 * 混成单声道 16kHz 音频，AAC 编码后封装为 `filesDir/call_recordings/{callId}.m4a`。
 *
 * 设计要点（满足技术方案 §8.2）：
 * - **不得阻塞采集/播放线程**：两个 feed 入口只做一次加性混音（synchronized + 数组相加）
 *   并 `trySend` 到有界队列；编码在独立 IO 协程消费。队列满时丢弃该帧并计数（背压策略）。
 * - **混音模型**：维护固定长度（100ms）混音累加帧 `mixBuf` 与写游标 `mixPos`。
 *   near/far 的样本按到达顺序叠加到同一时间位置（overlap-add）。
 *   通话期间 AI 说话时 near-end 由已消回声的打断检测链路提供（近乎静音），
 *   用户说话时 far-end 静音，因此叠加结果不会出现双份 AI 声音。
 * - **采样率/声道统一**：far-end 若为 24k/48k 或立体声，先降混 + 线性重采样到 16k mono。
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
    }

    private val appContext = context.applicationContext
    private val outFile: File =
        File(File(appContext.filesDir, RELATIVE_DIR).apply { mkdirs() }, "$callId.m4a")

    private val outChannel = Channel<ShortArray>(CHANNEL_CAPACITY)
    private val mixBuf = ShortArray(MIX_FRAME_SIZE)
    private val mixLock = Any()
    private var mixPos = 0

    private val framesFed = AtomicLong(0L)
    private val framesDropped = AtomicLong(0L)

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

    /** 混入用户侧麦克风 PCM（16kHz / 16-bit / mono）。 */
    fun feedNearEnd(frame: ShortArray, length: Int = frame.size) {
        addSamples(frame, length.coerceAtMost(frame.size), NEAR_GAIN)
    }

    /** 混入 AI 侧 TTS PCM（原始字节，16-bit LE，可能非 16k/多声道）。 */
    fun feedFarEnd(pcm: ByteArray, sampleRateHz: Int, channels: Int) {
        if (pcm.isEmpty()) return
        if (!started || stopping) return
        // 重采样/降混在 IO 协程执行，避免阻塞 TTS 播放线程（该回调在主线程调用）
        scope.launch(Dispatchers.IO) {
            runCatching {
                val mono = bytesToMonoShorts(pcm, channels.coerceAtLeast(1))
                val resampled = if (sampleRateHz != SAMPLE_RATE) {
                    resampleLinear(mono, sampleRateHz, SAMPLE_RATE)
                } else {
                    mono
                }
                addSamples(resampled, resampled.size, FAR_GAIN)
            }.onFailure { Log.w(TAG, "feedFarEnd failed callId=$callId", it) }
        }
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
        Log.i(
            TAG,
            "CallRecorder stopped callId=$callId frames=$framesFed dropped=$framesDropped " +
                "exists=${outFile.exists()} size=${outFile.length()}B"
        )
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
        runCatching { outFile.delete() }
        started = false
        Log.i(TAG, "CallRecorder discarded callId=$callId")
    }

    /** 把残留的不足一帧的混音数据补齐后送出，避免尾音丢失。 */
    private fun flushPartialFrame() {
        synchronized(mixLock) {
            if (mixPos <= 0) return
            val tail = mixBuf.copyOf(mixPos)
            java.util.Arrays.fill(mixBuf, 0)
            mixPos = 0
            framesFed.incrementAndGet()
            if (!outChannel.trySend(tail).isSuccess) framesDropped.incrementAndGet()
        }
    }

    private fun addSamples(src: ShortArray, length: Int, gain: Float) {
        if (!started || stopping || length <= 0) return
        synchronized(mixLock) {
            var i = 0
            while (i < length) {
                val v = (src[i] * gain).toInt()
                val sum = (mixBuf[mixPos].toInt() + v).coerceIn(-32768, 32767)
                mixBuf[mixPos] = sum.toShort()
                mixPos++
                i++
                if (mixPos == MIX_FRAME_SIZE) {
                    val out = mixBuf.copyOf()
                    java.util.Arrays.fill(mixBuf, 0)
                    mixPos = 0
                    framesFed.incrementAndGet()
                    if (!outChannel.trySend(out).isSuccess) {
                        framesDropped.incrementAndGet()
                    }
                }
            }
        }
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
