package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.core.data.db.entity.AgentDiaryEntity
import me.rerere.rikkahub.core.data.db.entity.DiaryImage
import me.rerere.rikkahub.core.data.db.entity.OcrStatus
import me.rerere.rikkahub.core.data.repository.DiaryRepository
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider

private const val TAG = "DiaryOcrService"

/**
 * OCR 失败事件（全局一次性事件）。
 *
 * 携带 [diaryId] 以便 UI 跳转到对应日记详情页查看失败原因，
 * [failedCount] 表示本次失败的图片数量。
 */
data class OcrFailureEvent(
    val diaryId: String,
    val failedCount: Int
)

/**
 * 手写日记 OCR 服务。
 *
 * 负责将日记图片逐张发送给用户配置的 OCR 模型进行文字识别，
 * 识别结果写入 [DiaryImage.ocrResult]，并合并到 [AgentDiaryEntity.content]。
 *
 * 关键设计：OCR 任务运行在 [AppScope] 中（SupervisorJob），不受页面退出 / VM 清理影响。
 * 即使用户保存日记后立刻跳转到详情页，OCR 协程也不会被取消。
 *
 * 异常处理：
 * - 未配置 OCR 模型：返回 [OcrResult.NoModelConfigured]，UI 提示并跳转设置页
 * - 模型调用失败：返回 [OcrResult.Failure]，UI 显示错误信息并提供重试
 */
class DiaryOcrService(
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val diaryRepo: DiaryRepository,
    private val appScope: AppScope
) {

    sealed class OcrResult {
        /** OCR 成功，[text] 为识别出的文字内容 */
        data class Success(val text: String) : OcrResult()
        /** OCR 失败，[error] 为错误信息（用于 UI 展示） */
        data class Failure(val error: String) : OcrResult()
        /** 用户未配置 OCR 模型 */
        data object NoModelConfigured : OcrResult()
    }

    /**
     * OCR 失败一次性事件（全局）：每次有图片 OCR 失败时发送一次。
     *
     * 放在 Service 中而不是 VM 中，因为 OCR 在 AppScope 中执行，
     * 用户可能在 OCR 完成前就退出了触发 OCR 的页面。
     * 任何页面（列表页/详情页）都可以收集这个流来显示提醒。
     *
     * 携带 diaryId 以便 UI 跳转到对应日记详情页查看失败原因。
     */
    private val _ocrFailureEvents = MutableSharedFlow<OcrFailureEvent>(extraBufferCapacity = 16)
    val ocrFailureEvents = _ocrFailureEvents.asSharedFlow()

    /**
     * 对单张日记图片执行 OCR。
     *
     * @param imagePath 图片文件绝对路径
     * @return OCR 结果
     */
    suspend fun ocrImage(imagePath: String): OcrResult = withContext(Dispatchers.IO) {
        val settings = settingsStore.settingsFlow.value
        val model = settings.findModelById(settings.ocrModelId)
        if (model == null) {
            return@withContext OcrResult.NoModelConfigured
        }

        val providerSetting = model.findProvider(settings.providers)
        if (providerSetting == null) {
            return@withContext OcrResult.Failure("无法找到 OCR 模型对应的 Provider")
        }

        val provider = providerManager.getProviderByType(providerSetting)
        val fileUri = "file://$imagePath"

        runCatching {
            val result = provider.generateText(
                providerSetting = providerSetting,
                messages = listOf(
                    UIMessage.system(settings.ocrPrompt),
                    UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Image(fileUri))
                    )
                ),
                params = TextGenerationParams(model = model)
            )
            Log.d(TAG, "OCR response for $imagePath: choices.size=${result.choices.size}")
            // 与 OcrTransformer.performOcr 对齐：直接下标访问 choices[0].message?.toText()
            // 并加日志确认 content 是否为空
            val content = if (result.choices.isNotEmpty()) {
                result.choices[0].message?.toText()
            } else {
                null
            }
            Log.d(TAG, "OCR extracted content for $imagePath: isNull=${content == null}, length=${content?.length ?: 0}")
            if (content.isNullOrBlank()) {
                OcrResult.Failure("OCR 模型未返回有效内容")
            } else {
                OcrResult.Success(content)
            }
        }.getOrElse { e ->
            Log.e(TAG, "OCR failed for $imagePath", e)
            OcrResult.Failure(e.message ?: "未知错误")
        }
    }

    /**
     * 对一篇日记的所有未解析图片执行 OCR，逐张更新状态。
     *
     * - 每张图片解析完成后立即更新数据库（[DiaryImage.ocrStatus] 和 [DiaryImage.ocrResult]）
     * - 全部解析完成后，合并所有成功图片的 OCR 文本写入 [AgentDiaryEntity.content]
     * - 某张失败不影响其他图片继续解析
     *
     * 关键：在 [appScope] 中启动，不受调用方 VM 生命周期影响。
     * 即使用户保存日记后立刻退出页面，OCR 协程也不会被取消。
     *
     * @param diary 日记实体
     * @param onImageStatusChange 每张图片状态变化时的回调（用于 UI 实时更新进度）
     */
    fun ocrDiaryImages(
        diary: AgentDiaryEntity,
        onImageStatusChange: (imageId: String, status: OcrStatus, result: String?, error: String?) -> Unit
    ) {
        appScope.launch(Dispatchers.IO) {
            val pendingImages = diary.images.filter { it.ocrStatus != OcrStatus.SUCCESS }
            if (pendingImages.isEmpty()) return@launch

            var failedCount = 0

            for (image in pendingImages) {
                // 标记为处理中
                updateImageStatus(diary.id, image.id, OcrStatus.PROCESSING, null, null)
                onImageStatusChange(image.id, OcrStatus.PROCESSING, null, null)

                when (val result = ocrImage(image.imagePath)) {
                    is OcrResult.Success -> {
                        updateImageStatus(diary.id, image.id, OcrStatus.SUCCESS, result.text, null)
                        onImageStatusChange(image.id, OcrStatus.SUCCESS, result.text, null)
                    }
                    is OcrResult.Failure -> {
                        failedCount++
                        updateImageStatus(diary.id, image.id, OcrStatus.FAILED, null, result.error)
                        onImageStatusChange(image.id, OcrStatus.FAILED, null, result.error)
                    }
                    is OcrResult.NoModelConfigured -> {
                        // 无 OCR 模型，全部标记为失败
                        failedCount += pendingImages.size
                        pendingImages.forEach { img ->
                            updateImageStatus(diary.id, img.id, OcrStatus.FAILED, null, "未配置 OCR 模型")
                            onImageStatusChange(img.id, OcrStatus.FAILED, null, "未配置 OCR 模型")
                        }
                        // emit 失败事件（携带 diaryId 和失败图片数）
                        _ocrFailureEvents.emit(OcrFailureEvent(diary.id, failedCount))
                        return@launch
                    }
                }
            }

            // 合并所有成功图片的 OCR 文本到 diary.content
            updateDiaryContentFromImages(diary.id)

            // 如果有失败图片，emit 失败事件
            if (failedCount > 0) {
                _ocrFailureEvents.emit(OcrFailureEvent(diary.id, failedCount))
            }
        }
    }

    /**
     * 重试单张图片的 OCR。
     *
     * 在 [appScope] 中启动，不受调用方 VM 生命周期影响。
     */
    fun retryOcrImage(
        diaryId: String,
        imageId: String,
        onImageStatusChange: (imageId: String, status: OcrStatus, result: String?, error: String?) -> Unit
    ) {
        appScope.launch(Dispatchers.IO) {
            val diary = diaryRepo.getDiaryById(diaryId) ?: return@launch
            val image = diary.images.find { it.id == imageId } ?: return@launch

            updateImageStatus(diaryId, imageId, OcrStatus.PROCESSING, null, null)
            onImageStatusChange(imageId, OcrStatus.PROCESSING, null, null)

            when (val result = ocrImage(image.imagePath)) {
                is OcrResult.Success -> {
                    updateImageStatus(diaryId, imageId, OcrStatus.SUCCESS, result.text, null)
                    onImageStatusChange(imageId, OcrStatus.SUCCESS, result.text, null)
                }
                is OcrResult.Failure -> {
                    updateImageStatus(diaryId, imageId, OcrStatus.FAILED, null, result.error)
                    onImageStatusChange(imageId, OcrStatus.FAILED, null, result.error)
                }
                is OcrResult.NoModelConfigured -> {
                    updateImageStatus(diaryId, imageId, OcrStatus.FAILED, null, "未配置 OCR 模型")
                    onImageStatusChange(imageId, OcrStatus.FAILED, null, "未配置 OCR 模型")
                }
            }

            updateDiaryContentFromImages(diaryId)
        }
    }

    /**
     * 更新单张图片的 OCR 状态。
     * 读取当前 diary → 修改对应 image → @Update 整个 diary。
     */
    private suspend fun updateImageStatus(
        diaryId: String,
        imageId: String,
        status: OcrStatus,
        result: String?,
        error: String?
    ) {
        val diary = diaryRepo.getDiaryById(diaryId) ?: return
        val updatedImages = diary.images.map { img ->
            if (img.id == imageId) {
                img.copy(ocrStatus = status, ocrResult = result, ocrError = error)
            } else {
                img
            }
        }
        val updatedDiary = diary.copy(images = updatedImages)
        diaryRepo.updateDiary(updatedDiary)
    }

    /**
     * 将所有成功解析的图片 OCR 文本合并到 diary.content。
     * 如果没有成功解析的图片，content 保持为空。
     */
    private suspend fun updateDiaryContentFromImages(diaryId: String) {
        val diary = diaryRepo.getDiaryById(diaryId) ?: return
        val successTexts = diary.images
            .filter { it.ocrStatus == OcrStatus.SUCCESS }
            .mapNotNull { it.ocrResult }
        val mergedContent = successTexts.joinToString(separator = "\n\n---\n\n")
        val updatedDiary = diary.copy(content = mergedContent)
        diaryRepo.updateDiary(updatedDiary)
    }

    /**
     * 检查用户是否配置了 OCR 模型。
     */
    fun isOcrModelConfigured(): Boolean {
        val settings = settingsStore.settingsFlow.value
        val model = settings.findModelById(settings.ocrModelId) ?: return false
        return model.findProvider(settings.providers) != null
    }
}
