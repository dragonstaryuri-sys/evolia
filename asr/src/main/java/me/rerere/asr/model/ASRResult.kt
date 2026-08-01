package me.rerere.asr.model

/**
 * ASR 识别结果。
 * 为流式识别预留 partial/final 区分：
 * - [isFinal] = false：中间结果（partial），会随识别进行不断更新
 * - [isFinal] = true：最终结果，识别已结束
 */
data class ASRResult(
    val text: String,
    val isFinal: Boolean = false,
    val confidence: Float = 1.0f,
    val metadata: Map<String, String> = emptyMap()
)
