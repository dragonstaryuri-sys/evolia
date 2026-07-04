package me.rerere.tts.controller

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.common.JsonInstant
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.model.TTSResponse
import me.rerere.tts.provider.TTSManager
import me.rerere.tts.provider.TTSProviderSetting
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

private const val TAG = "TtsSynthesizer"

/**
 * Bridge TTS provider flow to a single audio buffer.
 */
class TtsSynthesizer(
    private val context: Context,
    private val ttsManager: TTSManager
) {
    private val cacheDir by lazy {
        File(context.cacheDir, "tts_cache").apply {
            if (!exists()) mkdirs()
        }
    }

    suspend fun synthesize(
        setting: TTSProviderSetting,
        chunk: TtsChunk
    ): TTSResponse = withContext(Dispatchers.IO) {
        val cacheKey = generateCacheKey(setting, chunk.text)
        val cacheFile = File(cacheDir, cacheKey)

        if (cacheFile.exists()) {
            runCatching {
                val response = JsonInstant.decodeFromString<TTSResponse>(cacheFile.readText())
                Log.d(TAG, "Cache HIT for chunk ${chunk.index}: $cacheKey")
                return@withContext response
            }.onFailure {
                Log.e(TAG, "Failed to decode cache for chunk ${chunk.index}", it)
            }
        }

        Log.d(TAG, "Cache MISS for chunk ${chunk.index}, calling API...")
        val response = collectToResponse(
            ttsManager.generateSpeech(setting, TTSRequest(text = chunk.text))
        )

        // Save to cache
        runCatching {
            cacheFile.writeText(JsonInstant.encodeToString(response))
            Log.d(TAG, "Saved cache for chunk ${chunk.index}: $cacheKey")
        }.onFailure {
            Log.e(TAG, "Failed to save cache for chunk ${chunk.index}", it)
        }

        response
    }

    private fun generateCacheKey(setting: TTSProviderSetting, text: String): String {
        val settingStr = JsonInstant.encodeToString(setting)
        val input = settingStr + text
        return input.md5()
    }

    private fun String.md5(): String {
        val bytes = MessageDigest.getInstance("MD5").digest(this.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private suspend fun collectToResponse(flow: Flow<AudioChunk>): TTSResponse {
        var format: AudioFormat? = null
        var sampleRate: Int? = null
        val output = ByteArrayOutputStream()
        flow.collect { chunk ->
            if (format == null) format = chunk.format
            if (sampleRate == null) sampleRate = chunk.sampleRate
            output.write(chunk.data)
        }
        return TTSResponse(
            audioData = output.toByteArray(),
            format = format ?: AudioFormat.MP3,
            sampleRate = sampleRate
        )
    }
}
