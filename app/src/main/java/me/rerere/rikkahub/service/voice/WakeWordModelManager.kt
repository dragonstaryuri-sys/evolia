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

/**
 * 唤醒词 KWS 模型管理器。
 *
 * 优先从 APK assets/kws/ 目录拷贝（推荐打包内置，用户零感知），
 * 拷贝失败时 fallback 到从 HuggingFace 下载。
 * 模型采用 zipformer-wenetspeech（3.3M），总大小约 4MB。
 *
 * 运行时文件路径：context.filesDir/kws/
 * 打包路径：app/src/main/assets/kws/
 */
class WakeWordModelManager(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
) {
    companion object {
        private const val TAG = "WakeWordModelMgr"

        // sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2023-11-15
        private const val MODEL_BASE_URL =
            "https://huggingface.co/k2-fsa/sherpa-onnx-kws-models/resolve/main/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2023-11-15"

        // 需要的文件列表
        val MODEL_FILES = listOf(
            "encoder-epoch=12-avg=2.onnx",
            "decoder-epoch=12-avg=2.onnx",
            "joiner-epoch=12-avg=2.onnx",
            "tokens.txt"
        )

        const val KWS_DIR_NAME = "kws"
        const val ASSETS_KWS_DIR = "kws"
    }

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    /** KWS 模型目录的绝对路径 */
    val modelDir: File
        get() = File(context.filesDir, KWS_DIR_NAME)

    /** encoder 模型文件路径 */
    val encoderFile: File
        get() = File(modelDir, "encoder-epoch=12-avg=2.onnx")

    /** decoder 模型文件路径 */
    val decoderFile: File
        get() = File(modelDir, "decoder-epoch=12-avg=2.onnx")

    /** joiner 模型文件路径 */
    val joinerFile: File
        get() = File(modelDir, "joiner-epoch=12-avg=2.onnx")

    /** tokens 文件路径 */
    val tokensFile: File
        get() = File(modelDir, "tokens.txt")

    /** 检查模型文件是否全部就绪 */
    fun isModelReady(): Boolean {
        val dir = modelDir
        if (!dir.exists()) return false
        return MODEL_FILES.all { File(dir, it).exists() && File(dir, it).length() > 0 }
    }

    /**
     * 检查 assets 目录是否内置了 KWS 模型。
     */
    fun hasBundledAssets(): Boolean {
        return try {
            val assetFiles = context.assets.list(ASSETS_KWS_DIR) ?: emptyArray()
            MODEL_FILES.all { it in assetFiles }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 确保模型就绪：优先从 assets 拷贝 → 失败则 fallback 到网络下载。
     */
    suspend fun ensureReady(): Result<Unit> {
        if (isModelReady()) {
            _downloadState.value = DownloadState.Done
            return Result.success(Unit)
        }
        // 1. 优先尝试从 assets 拷贝（用户零感知）
        val copyResult = copyFromAssets()
        if (copyResult.isSuccess && isModelReady()) {
            _downloadState.value = DownloadState.Done
            return copyResult
        }
        // 2. fallback 到网络下载
        return downloadModel()
    }

    /**
     * 从 APK assets/kws/ 拷贝模型文件到 filesDir/kws/。
     */
    suspend fun copyFromAssets(): Result<Unit> = withContext(Dispatchers.IO) {
        if (!hasBundledAssets()) {
            return@withContext Result.failure(
                java.io.IOException("APK 未内置 KWS 模型 (assets/kws/)")
            )
        }
        if (_downloadState.value is DownloadState.Downloading) {
            return@withContext Result.failure(IllegalStateException("处理中"))
        }

        try {
            modelDir.mkdirs()
            val totalFiles = MODEL_FILES.size

            MODEL_FILES.forEachIndexed { index, fileName ->
                _downloadState.value = DownloadState.CopyingFromAssets(
                    fileName = fileName,
                    fileIndex = index + 1,
                    totalFiles = totalFiles
                )
                copyAssetFile(fileName)
            }

            if (!isModelReady()) {
                _downloadState.value = DownloadState.Error("模型文件校验失败")
                return@withContext Result.failure(java.io.IOException("拷贝后模型文件不完整"))
            }

            _downloadState.value = DownloadState.Done
            Log.i(TAG, "KWS 模型从 assets 拷贝完成: ${modelDir.absolutePath}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "从 assets 拷贝模型失败: ${e.message}", e)
            _downloadState.value = DownloadState.Error(e.message ?: "拷贝失败")
            Result.failure(e)
        }
    }

    private fun copyAssetFile(fileName: String) {
        val assetPath = "$ASSETS_KWS_DIR/$fileName"
        val targetFile = File(modelDir, fileName)
        val tmpFile = File(modelDir, "$fileName.tmp")

        Log.i(TAG, "拷贝 assets: $assetPath → ${targetFile.absolutePath}")

        context.assets.open(assetPath).use { input ->
            tmpFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        if (targetFile.exists()) targetFile.delete()
        tmpFile.renameTo(targetFile)
    }

    /**
     * 从网络下载 KWS 模型文件（fallback 路径）。
     * 已就绪则跳过；下载中则不重复触发。
     */
    suspend fun downloadModel(): Result<Unit> = withContext(Dispatchers.IO) {
        if (isModelReady()) {
            _downloadState.value = DownloadState.Done
            return@withContext Result.success(Unit)
        }
        if (_downloadState.value is DownloadState.Downloading) {
            return@withContext Result.failure(IllegalStateException("已在下载中"))
        }

        try {
            modelDir.mkdirs()
            val totalFiles = MODEL_FILES.size

            MODEL_FILES.forEachIndexed { index, fileName ->
                _downloadState.value = DownloadState.Downloading(
                    fileName = fileName,
                    fileIndex = index + 1,
                    totalFiles = totalFiles
                )
                downloadFile(fileName)
            }

            if (!isModelReady()) {
                _downloadState.value = DownloadState.Error("模型文件校验失败")
                return@withContext Result.failure(java.io.IOException("模型文件不完整"))
            }

            _downloadState.value = DownloadState.Done
            Log.i(TAG, "KWS 模型下载完成: ${modelDir.absolutePath}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "模型下载失败: ${e.message}", e)
            _downloadState.value = DownloadState.Error(e.message ?: "未知错误")
            Result.failure(e)
        }
    }

    /** 删除已下载的模型文件 */
    fun deleteModel() {
        if (modelDir.exists()) {
            modelDir.deleteRecursively()
            Log.i(TAG, "KWS 模型已删除")
        }
        _downloadState.value = DownloadState.Idle
    }

    private fun downloadFile(fileName: String) {
        val url = "$MODEL_BASE_URL/$fileName"
        val targetFile = File(modelDir, fileName)
        val tmpFile = File(modelDir, "$fileName.tmp")

        Log.i(TAG, "下载: $fileName → ${targetFile.absolutePath}")

        val request = Request.Builder().url(url).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw java.io.IOException("下载 $fileName 失败: HTTP ${response.code}")
            }
            response.body?.byteStream()?.use { input ->
                tmpFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw java.io.IOException("下载 $fileName 失败: 响应体为空")
        }

        // 下载完成后重命名
        if (targetFile.exists()) targetFile.delete()
        tmpFile.renameTo(targetFile)
    }

    /** 模型准备状态密封类 */
    sealed class DownloadState {
        /** 空闲 */
        data object Idle : DownloadState()

        /** 从 assets 拷贝中 */
        data class CopyingFromAssets(
            val fileName: String,
            val fileIndex: Int,
            val totalFiles: Int
        ) : DownloadState()

        /** 网络下载中 */
        data class Downloading(
            val fileName: String,
            val fileIndex: Int,
            val totalFiles: Int
        ) : DownloadState()

        /** 完成 */
        data object Done : DownloadState()

        /** 失败 */
        data class Error(val message: String) : DownloadState()
    }
}
