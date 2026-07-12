package me.rerere.rikkahub.core.data.utils

import java.nio.ByteBuffer
import java.nio.ByteOrder

object VectorUtils {
    /**
     * Convert FloatArray to ByteArray for BLOB storage
     */
    fun toByteArray(floats: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(floats.size * 4)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        for (f in floats) {
            buffer.putFloat(f)
        }
        return buffer.array()
    }

    /**
     * Convert ByteArray from BLOB storage back to FloatArray
     */
    fun fromByteArray(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(bytes.size / 4)
        for (i in floats.indices) {
            floats[i] = buffer.getFloat()
        }
        return floats
    }

    /**
     * Convert List<Float> to ByteArray
     */
    fun fromList(list: List<Float>): ByteArray {
        return toByteArray(list.toFloatArray())
    }

    /**
     * Convert ByteArray back to List<Float>
     */
    fun toList(bytes: ByteArray): List<Float> {
        return fromByteArray(bytes).toList()
    }
}
