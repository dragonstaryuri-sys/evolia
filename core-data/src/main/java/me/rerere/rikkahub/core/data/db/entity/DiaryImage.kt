package me.rerere.rikkahub.core.data.db.entity

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * 日记图片的解析状态。
 * - PENDING: 待解析（刚导入/拍摄，尚未开始 OCR）
 * - PROCESSING: 正在解析中
 * - SUCCESS: 解析成功
 * - FAILED: 解析失败
 */
@Serializable
enum class OcrStatus {
    PENDING,
    PROCESSING,
    SUCCESS,
    FAILED
}

/**
 * 日记图片实体（手写日记）。
 *
 * 存储在 [AgentDiaryEntity.images] 字段中（JSON 序列化）。
 * 每张图片独立维护 OCR 解析状态与结果，用于：
 * 1. UI 上逐张展示解析进度与状态
 * 2. 评论流程中判断是否可发送 OCR 文本（全部 SUCCESS 才发）
 *
 * @param id 图片唯一标识
 * @param imagePath webp 文件绝对路径（filesDir/diary_images/<diaryId>/<imageId>.webp）
 * @param ocrStatus OCR 解析状态
 * @param ocrResult 单张图片的 OCR 解析结果（成功后填充）
 * @param ocrError 解析失败原因（失败后填充，用于 UI 展示与重试）
 */
@Serializable
data class DiaryImage(
    val id: String = Uuid.random().toString(),
    val imagePath: String,
    val ocrStatus: OcrStatus = OcrStatus.PENDING,
    val ocrResult: String? = null,
    val ocrError: String? = null
)
