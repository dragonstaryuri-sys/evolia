package me.rerere.rikkahub.data.radio

import android.content.Context
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSManager
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.controller.TextChunker
import java.io.File

/**
 * 电台节目音频生成器：整篇文字稿 → 分句合成 → PCM 拼接 → AAC(m4a) 文件。
 *
 * 生成策略（AGENTS.md 12.3）：
 * - TextChunker 按句读切分（160 字上限），逐句调用 TTSManager 合成 16-bit LE mono PCM；
 * - 句间插入 200ms 静音（自然停顿）后拼接为完整音轨；
 * - 编码 AAC（32kbps mono，采样率随 Provider）；编码器初始化失败时降级 WAV 封装；
 * - 任一分句合成失败即中止并删除半成品文件，由调用方保证不落任何资产记录。
 */
class RadioAudioGenerator(
    private val context: Context,
    private val ttsManager: TTSManager
) {
    suspend fun generate(
        content: String,
        providerSetting: TTSProviderSetting,
        outFile: File,
        maxChunkLength: Int = 160
    ): Result {
        return withContext(Dispatchers.IO) {
            val chunks = TextChunker(maxChunkLength).split(content)
            if (chunks.isEmpty()) {
                return@withContext Result.Failure("文字稿没有可朗读的内容")
            }

            // 逐句合成 PCM
            val pcmParts = ArrayList<ByteArray>(chunks.size)
            var sampleRate = 0
            chunks.forEachIndexed { index, chunk ->
                val response = try {
                    kotlinx.coroutines.withTimeout(90_000L) {
                        ttsManager.generateSpeech(providerSetting, TTSRequest(text = chunk.text))
                            .collectToPcm()
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e(TAG, "分句 TTS 合成失败 index=$index", e)
                    null
                }
                if (response == null || response.first.isEmpty()) {
                    return@withContext Result.Failure("第 ${index + 1}/${chunks.size} 句语音合成失败，已中止本次生成")
                }
                val (pcm, rate) = response
                if (sampleRate == 0) {
                    sampleRate = rate
                } else if (sampleRate != rate) {
                    return@withContext Result.Failure("分句采样率不一致（$sampleRate/$rate），已中止本次生成")
                }
                pcmParts.add(pcm)
            }

            val (fullPcm, durationMs) = concatPcmWithGaps(pcmParts, sampleRate, GAP_BETWEEN_CHUNKS_MS)
            if (fullPcm.isEmpty()) {
                return@withContext Result.Failure("合成的音频内容为空")
            }

            // 编码：优先 AAC m4a，失败降级 WAV
            val encoded = runCatching {
                outFile.delete()
                PcmAacEncoder.encode(fullPcm, sampleRate, outFile)
            }.isSuccess

            val target = if (encoded) {
                outFile
            } else {
                Log.w(TAG, "AAC 编码失败，降级 WAV 封装: ${outFile.name}")
                val wavFile = File(outFile.parentFile, outFile.nameWithoutExtension + ".wav")
                wavFile.delete()
                if (!wavFile.writeBytesSafely(wrapPcmInWav(fullPcm, sampleRate))) {
                    return@withContext Result.Failure("音频文件写入失败")
                }
                outFile.delete()
                wavFile
            }

            if (!target.exists() || target.length() <= 0L) {
                return@withContext Result.Failure("音频文件写入失败")
            }

            Result.Success(
                file = target,
                durationMs = durationMs,
                sampleRate = sampleRate
            )
        }
    }

    sealed interface Result {
        data class Success(
            val file: File,
            val durationMs: Long,
            val sampleRate: Int
        ) : Result

        data class Failure(val reason: String) : Result
    }

    /**
     * 聚合 TTS Flow 为完整 PCM（suspend，非 PCM 通道直接判失败）。
     */
    private suspend fun kotlinx.coroutines.flow.Flow<me.rerere.tts.model.AudioChunk>.collectToPcm(): Pair<ByteArray, Int>? {
        var format: AudioFormat? = null
        var rate = 0
        val buffers = ArrayList<ByteArray>()
        try {
            collect { chunk ->
                if (format == null) format = chunk.format
                if (chunk.format != AudioFormat.PCM) return@collect
                if (rate == 0) rate = chunk.sampleRate ?: 0
                if (chunk.data.isNotEmpty()) buffers.add(chunk.data)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "TTS 音频流收集失败", e)
            return null
        }
        if (format != AudioFormat.PCM || rate <= 0) return null
        val total = buffers.sumOf { it.size }
        if (total <= 0) return null
        val merged = ByteArray(total)
        var offset = 0
        buffers.forEach {
            System.arraycopy(it, 0, merged, offset, it.size)
            offset += it.size
        }
        return merged to rate
    }

    private fun File.writeBytesSafely(bytes: ByteArray): Boolean {
        return try {
            parentFile?.mkdirs()
            writeBytes(bytes)
            true
        } catch (e: Exception) {
            Log.e(TAG, "写文件失败: $name", e)
            false
        }
    }

    companion object {
        private const val TAG = "RadioAudioGenerator"
        const val GAP_BETWEEN_CHUNKS_MS = 200
        const val RELATIVE_DIR = "radio_audio"

        /** 音频文件所在私有目录。 */
        fun audioDir(context: Context): File = File(context.filesDir, RELATIVE_DIR)

        /** 解析资产相对路径为真实文件（与 CallRecorder.resolveAudioFile 同约定：`/` 开头为绝对路径）。 */
        fun resolveAudioFile(context: Context, storedPath: String): File {
            return if (storedPath.startsWith("/")) File(storedPath)
            else File(context.filesDir, storedPath)
        }
    }
}
