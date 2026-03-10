package me.rerere.rikkahub.core.data.ai.rag

import kotlin.math.sqrt

object VectorEngine {
    fun cosineSimilarity(v1: List<Float>, v2: List<Float>): Float {
        if (v1.size != v2.size) return 0f
        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in v1.indices) {
            dotProduct += v1[i].toDouble() * v2[i].toDouble()
            normA += v1[i].toDouble() * v1[i].toDouble()
            normB += v2[i].toDouble() * v2[i].toDouble()
        }
        return if (normA == 0.0 || normB == 0.0) 0f else (dotProduct / (sqrt(normA) * sqrt(normB))).toFloat()
    }
}
