package me.rerere.rikkahub.data.radio

import android.content.Context
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.common.JsonInstant
import me.rerere.rikkahub.data.backgroundtask.ChunkResumeRules
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

    // ====================================================================
    // chunk 断点续传路径（通用后台任务执行器使用）
    // ====================================================================

    /**
     * 分句 chunk 断点续传生成（50 分钟长稿用，见 AGENTS.md 通用后台任务章节）：
     *
     * - 每句独立合成，先写 `chunk_{i}.part` 再原子 rename 为 `chunk_{i}.pcm`，
     *   rename 的原子性即「句子已完成」事实源；崩溃窗口最多留下一个 .part 残留；
     * - 正确性不依赖任务行的 progress_json（仅作进度展示），恢复决策见 [ChunkResumeRules]；
     * - 恢复时从首个未完成句继续，不重复合成已完成内容；
     * - 单句失败立即重试 1 次，仍失败返回 Failure（**保留已完成 chunk** 供任务级重试续跑）；
     * - 全部完成后按序合并（句间 200ms 静音）→ 流式编码 m4a → 清理工作目录；
     * - AAC 编码失败降级 WAV（流式写头 + 拷贝，不整段载入内存）。
     *
     * @param workDir 本任务专属工作目录（radio_audio/tmp/{taskId}/），chunk 与合并中间文件都在此
     * @param onSentenceCompleted 每句完成回调（Worker 据此续租约 + 更新进度 + 检查 deadline）
     */
    suspend fun generateChunked(
        content: String,
        providerSetting: TTSProviderSetting,
        providerId: String,
        workDir: File,
        outFile: File,
        maxChunkLength: Int = 160,
        onSentenceCompleted: suspend (completed: Int, total: Int, sampleRate: Int) -> Unit = { _, _, _ -> },
        /** 阶段切换回调（合成后还有合并/编码两段无逐句粒度的重活，供调用方续租约+更新阶段进度） */
        onPhaseChange: suspend (phase: String) -> Unit = {}
    ): Result {
        return withContext(Dispatchers.IO) {
            val chunks = TextChunker(maxChunkLength).split(content)
            if (chunks.isEmpty()) {
                cleanupWorkDir(workDir)
                return@withContext Result.Failure("文字稿没有可朗读的内容")
            }
            workDir.mkdirs()

            // 恢复决策（纯函数）：FRESH / RESUME / RESTART
            val dirFileNames = workDir.list()?.toSet() ?: emptySet()
            val manifest = readChunkManifest(workDir)
            val decision = ChunkResumeRules.decideChunkResume(
                dirFileNames = dirFileNames,
                manifest = manifest,
                expectedTotalSentences = chunks.size,
                expectedProviderId = providerId
            )
            decision.filesToDelete.forEach { name ->
                runCatching { File(workDir, name).delete() }
            }
            var completedCount = if (decision.verdict == ChunkResumeRules.Verdict.RESUME) decision.completedCount else 0
            var sampleRate = if (completedCount > 0) manifest?.sampleRate ?: 0 else 0

            // 逐句合成（从首个未完成句继续）
            for (index in completedCount until chunks.size) {
                val pcm = synthesizeSentenceWithRetry(providerSetting, index, chunks.size, chunks[index].text)
                    ?: return@withContext Result.Failure("第 ${index + 1}/${chunks.size} 句语音合成失败，已中止本次生成")
                val (pcmBytes, rate) = pcm
                if (sampleRate == 0) {
                    sampleRate = rate
                } else if (sampleRate != rate) {
                    return@withContext Result.Failure("分句采样率不一致（$sampleRate/$rate），已中止本次生成")
                }

                // 原子落盘：先写 .part 再 rename，rename 即完成事实源
                val partFile = File(workDir, ChunkResumeRules.partFileName(index + 1))
                val doneFile = File(workDir, ChunkResumeRules.chunkFileName(index + 1))
                try {
                    partFile.parentFile?.mkdirs()
                    partFile.writeBytes(pcmBytes)
                    if (!partFile.renameTo(doneFile)) {
                        return@withContext Result.Failure("分句音频写入失败（rename 失败），已中止本次生成")
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    runCatching { partFile.delete() }
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "分句音频写盘失败 index=$index", e)
                    runCatching { partFile.delete() }
                    return@withContext Result.Failure("分句音频写入失败，已中止本次生成")
                }

                // 首句完成后写 manifest（原子 rename），恢复决策从此有依据
                if (index == 0 || manifest == null) {
                    if (!writeChunkManifest(workDir, chunks.size, providerId, sampleRate)) {
                        return@withContext Result.Failure("工作目录清单写入失败，已中止本次生成")
                    }
                }

                completedCount = index + 1
                onSentenceCompleted(completedCount, chunks.size, sampleRate)
            }

            // 合并：按序拼接 chunk + 句间 200ms 静音，流式写盘（50 分钟 ≈ 144MB，不载入内存）
            onPhaseChange(me.rerere.rikkahub.data.backgroundtask.RadioAudioTaskProgress.PHASE_MERGING)
            val mergedFile = File(workDir, "merged.pcm")
            val totalPcmBytes = try {
                mergeChunksToFile(workDir, chunks.size, sampleRate, mergedFile)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "chunk 合并失败", e)
                mergedFile.delete()
                return@withContext Result.Failure("音频合并失败")
            }
            if (totalPcmBytes <= 0L) {
                mergedFile.delete()
                return@withContext Result.Failure("合成的音频内容为空")
            }
            val durationMs = PcmAacEncoder.pcmDurationMs(totalPcmBytes, sampleRate)

            // 编码：优先 AAC m4a，失败降级 WAV（流式）
            onPhaseChange(me.rerere.rikkahub.data.backgroundtask.RadioAudioTaskProgress.PHASE_ENCODING)
            val encoded = runCatching {
                outFile.delete()
                PcmAacEncoder.encodeFile(mergedFile, sampleRate, outFile)
            }.isSuccess

            val target = if (encoded) {
                outFile
            } else {
                Log.w(TAG, "AAC 编码失败，降级 WAV 封装: ${outFile.name}")
                val wavFile = File(outFile.parentFile, outFile.nameWithoutExtension + ".wav")
                wavFile.delete()
                if (!writePcmFileAsWav(mergedFile, sampleRate, wavFile)) {
                    mergedFile.delete()
                    return@withContext Result.Failure("音频文件写入失败")
                }
                outFile.delete()
                wavFile
            }

            if (!target.exists() || target.length() <= 0L) {
                mergedFile.delete()
                return@withContext Result.Failure("音频文件写入失败")
            }

            // 成功：清理工作目录（chunk/manifest/merged 全部回收）
            cleanupWorkDir(workDir)
            Result.Success(
                file = target,
                durationMs = durationMs,
                sampleRate = sampleRate
            )
        }
    }

    /** 单句合成（含 1 次立即重试；取消沿调用方协程传播）。 */
    private suspend fun synthesizeSentenceWithRetry(
        providerSetting: TTSProviderSetting,
        index: Int,
        total: Int,
        text: String
    ): Pair<ByteArray, Int>? {
        repeat(2) { attempt ->
            val response = try {
                kotlinx.coroutines.withTimeout(90_000L) {
                    ttsManager.generateSpeech(providerSetting, TTSRequest(text = text))
                        .collectToPcm()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "分句 TTS 合成失败 index=$index attempt=$attempt/$total", e)
                null
            }
            if (response != null && response.first.isNotEmpty()) return response
        }
        return null
    }

    private fun readChunkManifest(workDir: File): ChunkResumeRules.ChunkManifest? {
        val file = File(workDir, ChunkResumeRules.MANIFEST_FILE_NAME)
        if (!file.exists()) return null
        return runCatching {
            JsonInstant.decodeFromString(ChunkResumeRules.ChunkManifest.serializer(), file.readText())
        }.getOrNull()
    }

    /** manifest 原子写入：临时文件 + rename。 */
    private fun writeChunkManifest(workDir: File, total: Int, providerId: String, sampleRate: Int): Boolean {
        val manifest = ChunkResumeRules.ChunkManifest(
            totalSentences = total,
            providerId = providerId,
            sampleRate = sampleRate
        )
        val partFile = File(workDir, ChunkResumeRules.MANIFEST_FILE_NAME + ".part")
        val doneFile = File(workDir, ChunkResumeRules.MANIFEST_FILE_NAME)
        return try {
            partFile.writeText(JsonInstant.encodeToString(ChunkResumeRules.ChunkManifest.serializer(), manifest))
            if (!partFile.renameTo(doneFile)) {
                partFile.delete()
                false
            } else true
        } catch (e: Exception) {
            Log.e(TAG, "chunk manifest 写入失败", e)
            runCatching { partFile.delete() }
            false
        }
    }

    /** 按序合并全部 chunk（句间 200ms 静音），返回总 PCM 字节数。 */
    private fun mergeChunksToFile(workDir: File, total: Int, sampleRate: Int, outFile: File): Long {
        val silence = ByteArray((sampleRate.toLong() * 2L * GAP_BETWEEN_CHUNKS_MS / 1000L).toInt())
        var totalBytes = 0L
        java.io.BufferedOutputStream(java.io.FileOutputStream(outFile)).use { out ->
            for (i in 1..total) {
                val chunkFile = File(workDir, ChunkResumeRules.chunkFileName(i))
                chunkFile.inputStream().use { input ->
                    input.copyTo(out)
                }
                totalBytes += chunkFile.length()
                if (i < total && silence.isNotEmpty()) {
                    out.write(silence)
                    totalBytes += silence.size
                }
            }
        }
        return totalBytes
    }

    /** WAV 降级封装（流式：44 字节头 + PCM 拷贝，不整段载入内存）。 */
    private fun writePcmFileAsWav(pcmFile: File, sampleRate: Int, outFile: File): Boolean {
        return try {
            val pcmLength = pcmFile.length()
            java.io.DataOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(outFile))).use { out ->
                val byteRate = sampleRate * 2
                // RIFF 头
                out.writeBytes("RIFF")
                out.writeIntLe((36 + pcmLength).toInt())
                out.writeBytes("WAVE")
                out.writeBytes("fmt ")
                out.writeIntLe(16)
                out.writeShortLe(1)          // PCM
                out.writeShortLe(1)          // mono
                out.writeIntLe(sampleRate)
                out.writeIntLe(byteRate)
                out.writeShortLe(2)          // block align (16-bit mono)
                out.writeShortLe(16)         // bits per sample
                out.writeBytes("data")
                out.writeIntLe(pcmLength.toInt())
                pcmFile.inputStream().use { it.copyTo(out) }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "WAV 封装失败", e)
            false
        }
    }

    private fun java.io.DataOutputStream.writeIntLe(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun java.io.DataOutputStream.writeShortLe(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }

    /** 清理任务工作目录（chunk/manifest/merged 全删；目录删除失败留待清理任务兜底）。 */
    fun cleanupWorkDir(workDir: File) {
        runCatching {
            workDir.listFiles()?.forEach { it.delete() }
            workDir.delete()
        }
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

        /** 后台任务 chunk 工作目录：radio_audio/tmp/{taskId}/。 */
        fun taskWorkDir(context: Context, taskId: String): File =
            File(audioDir(context), "tmp/$taskId")

        /** 解析资产相对路径为真实文件（与 CallRecorder.resolveAudioFile 同约定：`/` 开头为绝对路径）。 */
        fun resolveAudioFile(context: Context, storedPath: String): File {
            return if (storedPath.startsWith("/")) File(storedPath)
            else File(context.filesDir, storedPath)
        }
    }
}
