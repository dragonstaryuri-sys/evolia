package me.rerere.rikkahub.data.radio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileInputStream

/**
 * 16-bit LE mono PCM → AAC m4a 文件编码器（同步模型，与 CallRecorder 的
 * 流式编码共用同样的编解码配置：AAC-LC / 32kbps / 单声道 / 源采样率）。
 *
 * 支持两种输入：
 * - [encode]：内存 ByteArray（UI 短稿路径）；
 * - [encodeFile]：磁盘 PCM 文件流式编码（后台任务长稿路径，50 分钟音频约 144MB PCM，
 *   不可整段载入内存，按 32KB 分块读取喂给编码器）。
 *
 * 编码失败（设备不支持等）时抛出异常，由调用方降级为 WAV 封装。
 */
object PcmAacEncoder {
    private const val MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC
    private const val BITRATE = 32000
    private const val DEQUEUE_TIMEOUT_US = 50_000L
    // 单次喂给编解码器的最大输入字节数（约 1s @48kHz，覆盖常见采样率）
    private const val MAX_INPUT_CHUNK_BYTES = 96_000

    /** 根据总 PCM 字节数计算时长（16-bit mono：每样本 2 字节）。 */
    fun pcmDurationMs(totalPcmBytes: Long, sampleRate: Int): Long {
        if (sampleRate <= 0 || totalPcmBytes <= 0) return 0L
        return totalPcmBytes / 2L * 1000L / sampleRate
    }

    /**
     * 将内存中的 PCM 编码为 m4a 文件。
     *
     * @param pcm 16-bit 小端单声道 PCM 裸数据
     * @param sampleRate PCM 采样率
     * @param outFile 输出文件（.m4a）
     */
    fun encode(pcm: ByteArray, sampleRate: Int, outFile: File) {
        require(sampleRate > 0) { "sampleRate must be positive" }
        var offset = 0
        encodeInternal(sampleRate, outFile) {
            if (offset >= pcm.size) {
                null
            } else {
                val chunk = minOf(pcm.size - offset, MAX_INPUT_CHUNK_BYTES)
                val bytes = pcm.copyOfRange(offset, offset + chunk)
                offset += chunk
                bytes
            }
        }
    }

    /**
     * 将磁盘上的 PCM 裸文件流式编码为 m4a 文件（长稿后台任务用，避免整段载入内存）。
     *
     * @param pcmFile 16-bit 小端单声道 PCM 裸数据文件
     * @param sampleRate PCM 采样率（来自工作目录 manifest，逐句合成时已校验一致）
     * @param outFile 输出文件（.m4a）
     */
    fun encodeFile(pcmFile: File, sampleRate: Int, outFile: File) {
        require(sampleRate > 0) { "sampleRate must be positive" }
        FileInputStream(pcmFile).use { input ->
            encodeInternal(sampleRate, outFile) {
                val buffer = ByteArray(MAX_INPUT_CHUNK_BYTES)
                val read = input.read(buffer)
                if (read <= 0) null else buffer.copyOf(read)
            }
        }
    }

    /**
     * 编码内核：由 [nextChunk] 分块提供输入（返回 null 表示输入结束），
     * 喂入节奏与输出消费循环同既有实现。
     */
    private fun encodeInternal(sampleRate: Int, outFile: File, nextChunk: () -> ByteArray?) {
        val format = MediaFormat.createAudioFormat(MIME_TYPE, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_CHUNK_BYTES)
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
                    val inIdx = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIdx)
                        if (inputBuffer != null) {
                            val chunk = nextChunk()
                            if (chunk == null) {
                                codec.queueInputBuffer(
                                    inIdx, 0, 0, presentationUs,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                inputBuffer.clear()
                                inputBuffer.put(chunk)
                                codec.queueInputBuffer(inIdx, 0, chunk.size, presentationUs, 0)
                                // 每样本 2 字节（16-bit mono）
                                presentationUs += chunk.size.toLong() / 2L * 1_000_000L / sampleRate
                            }
                        } else {
                            codec.queueInputBuffer(inIdx, 0, 0, presentationUs, 0)
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
                        val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
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
}
