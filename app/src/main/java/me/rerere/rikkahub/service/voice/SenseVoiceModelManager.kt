package me.rerere.rikkahub.service.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * SenseVoice 本地 ASR 模型管理器。
 *
 * 负责 model.int8.onnx (~228MB) 和 tokens.txt (~308KB) 的下载、校验和删除。
 * 模型文件存储在 context.filesDir/sensevoice/ 下。
 *
 * 下载源优先级（国内友好）：
 *   1. ModelScope（魔搭，阿里国内 CDN，速度最佳）
 *   2. hf-mirror.com（HuggingFace 国内镜像）
 *   3. HuggingFace 官方（国际，兜底）
 *
 * 与 WakeWordModelManager 的区别：
 *   - 直接下载独立文件，无需 tar.bz2 解压
 *   - 模型体积大（~228MB），需要进度显示
 *   - 无 assets 内置方案（文件太大不适合打包进 APK）
 */
class SenseVoiceModelManager(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
) {
    companion object {
        private const val TAG = "SenseVoiceModelMgr"

        const val MODEL_DIR = "sensevoice"
        const val MODEL_FILE = "model.int8.onnx"
        const val TOKENS_FILE = "tokens.txt"

        val MODEL_FILES = listOf(MODEL_FILE, TOKENS_FILE)

        // ===== 下载源（按优先级排序）=====
        // ModelScope（国内首选，阿里 CDN）
        private const val MODELSCOPE_BASE =
            "https://modelscope.cn/models/pengzhendong/sherpa-onnx-sense-voice-zh-en-ja-ko-yue/resolve/master"
        // hf-mirror.com（HuggingFace 国内镜像）
        private const val HF_MIRROR_BASE =
            "https://hf-mirror.com/pengzhendong/sherpa-onnx-sense-voice-zh-en-ja-ko-yue/resolve/main"
        // HuggingFace 官方（国际，兜底）
        private const val HF_OFFICIAL_BASE =
            "https://huggingface.co/pengzhendong/sherpa-onnx-sense-voice-zh-en-ja-ko-yue/resolve/main"

        // 预期模型大小（用于校验，约值）
        const val EXPECTED_MODEL_SIZE = 228L * 1024 * 1024 // ~228MB
        const val EXPECTED_TOKENS_SIZE = 308L * 1024 // ~308KB
        // 最小有效大小（低于此值视为不完整）
        const val MIN_MODEL_SIZE = 200L * 1024 * 1024
    }

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    val modelDir: File get() = File(context.filesDir, MODEL_DIR)
    val modelFile: File get() = File(modelDir, MODEL_FILE)
    val tokensFile: File get() = File(modelDir, TOKENS_FILE)

    /**
     * 检查模型是否已就绪（文件存在且大小合理）。
     */
    fun isModelReady(): Boolean {
        val dir = modelDir
        if (!dir.exists()) return false
        val model = File(dir, MODEL_FILE)
        val tokens = File(dir, TOKENS_FILE)
        return model.exists() && model.length() >= MIN_MODEL_SIZE &&
            tokens.exists() && tokens.length() > 0
    }

    /**
     * 获取已下载模型的总大小（字节），用于 UI 显示。
     */
    fun getModelSize(): Long {
        if (!modelDir.exists()) return 0
        return MODEL_FILES.sumOf { File(modelDir, it).length() }
    }

    /**
     * 下载模型文件。
     * 先下载 tokens.txt（小文件，秒下），再下载 model.int8.onnx（大文件，带进度）。
     */
    suspend fun downloadModel(): Result<Unit> = withContext(Dispatchers.IO) {
        if (isModelReady()) {
            _downloadState.value = DownloadState.Done
            return@withContext Result.success(Unit)
        }
        if (_downloadState.value is DownloadState.DownloadingFile) {
            return@withContext Result.failure(IllegalStateException("下载进行中"))
        }

        try {
            modelDir.mkdirs()
            val lastEx = AtomicReference<Exception?>(null)

            // --- 1. 下载 tokens.txt（小文件，快速） ---
            _downloadState.value = DownloadState.DownloadingFile(TOKENS_FILE, 0f)
            val tokensOk = downloadSingleFile(TOKENS_FILE)
            if (!tokensOk) {
                _downloadState.value = DownloadState.Error("tokens.txt 下载失败")
                return@withContext Result.failure(java.io.IOException("tokens.txt 下载失败"))
            }
            Log.i(TAG, "tokens.txt 下载完成: ${tokensFile.length()} bytes")

            // --- 2. 下载 model.int8.onnx（大文件，带进度） ---
            val modelOk = downloadSingleFile(MODEL_FILE)
            if (!modelOk) {
                _downloadState.value = DownloadState.Error("model.int8.onnx 下载失败")
                return@withContext Result.failure(java.io.IOException("model.int8.onnx 下载失败"))
            }
            Log.i(TAG, "model.int8.onnx 下载完成: ${modelFile.length()} bytes")

            // --- 3. 校验 ---
            if (!isModelReady()) {
                _downloadState.value = DownloadState.Error("模型文件校验失败")
                return@withContext Result.failure(java.io.IOException("模型文件不完整"))
            }

            _downloadState.value = DownloadState.Done
            Log.i(TAG, "SenseVoice 模型下载完成: ${modelDir.absolutePath}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "下载失败: ${e.message}", e)
            _downloadState.value = DownloadState.Error(e.message ?: "下载失败")
            Result.failure(e)
        }
    }

    /**
     * 从多个源下载单个文件，逐个尝试直到成功。
     */
    private suspend fun downloadSingleFile(fileName: String): Boolean {
        val sources = listOf(
            "$MODELSCOPE_BASE/$fileName",
            "$HF_MIRROR_BASE/$fileName",
            "$HF_OFFICIAL_BASE/$fileName"
        )

        for ((idx, url) in sources.withIndex()) {
            val srcName = when (idx) {
                0 -> "ModelScope"
                1 -> "hf-mirror"
                2 -> "HuggingFace"
                else -> "源$idx"
            }
            try {
                Log.i(TAG, "下载[$srcName] → $fileName")
                val success = downloadFromUrl(url, fileName)
                if (success) {
                    Log.i(TAG, "下载成功[$srcName]: $fileName")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "下载失败[$srcName] $fileName: ${e.message}")
            }
        }
        return false
    }

    /**
     * 从单个 URL 下载文件，带进度回调。
     */
    private fun downloadFromUrl(url: String, fileName: String): Boolean {
        val target = File(modelDir, fileName)
        val tmp = File(modelDir, "$fileName.tmp")

        val req = Request.Builder().url(url).build()
        okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw java.io.IOException("HTTP ${resp.code}")
            }
            val body = resp.body ?: throw java.io.IOException("响应体为空")
            val contentLen = body.contentLength()
            var written = 0L

            body.byteStream().use { ins ->
                tmp.outputStream().use { outs ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        outs.write(buf, 0, n)
                        written += n
                        // 更新下载进度
                        if (contentLen > 0) {
                            val pct = (written.toFloat() / contentLen.toFloat()).coerceIn(0f, 1f)
                            _downloadState.value = DownloadState.DownloadingFile(fileName, pct)
                        }
                    }
                }
            }

            if (tmp.length() <= 0) {
                tmp.delete()
                throw java.io.IOException("空文件")
            }

            // 原子替换
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                // renameTo 在跨挂载点时可能失败，用拷贝兜底
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            return true
        }
    }

    /**
     * 删除模型文件。
     */
    fun deleteModel() {
        if (modelDir.exists()) modelDir.deleteRecursively()
        _downloadState.value = DownloadState.Idle
    }

    sealed class DownloadState {
        data object Idle : DownloadState()
        data class DownloadingFile(
            val fileName: String,
            val progress: Float
        ) : DownloadState()
        data object Done : DownloadState()
        data class Error(val message: String) : DownloadState()
    }
}
