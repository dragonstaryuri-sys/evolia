package me.rerere.rikkahub.core.data.ai.rag

import kotlin.math.sqrt

object VectorEngine {
    /**
     * 高性能余弦相似度计算，使用 FloatArray 避免 List 的装箱开销
     */
    fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        if (v1.size != v2.size || v1.isEmpty()) return 0f
        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in v1.indices) {
            val a = v1[i].toDouble()
            val b = v2[i].toDouble()
            dotProduct += a * b
            normA += a * a
            normB += b * b
        }
        return if (normA <= 0.0 || normB <= 0.0) 0f else (dotProduct / (sqrt(normA) * sqrt(normB))).toFloat()
    }

    /**
     * 兼容旧版调用，内部转为 FloatArray 处理
     */
    fun cosineSimilarity(v1: List<Float>, v2: List<Float>): Float {
        return cosineSimilarity(v1.toFloatArray(), v2.toFloatArray())
    }
}
