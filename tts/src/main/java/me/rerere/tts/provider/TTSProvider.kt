package me.rerere.tts.provider

import android.content.Context
import kotlinx.coroutines.flow.Flow
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.model.TTSVoice

interface TTSProvider<T : TTSProviderSetting> {
    fun generateSpeech(
        context: Context,
        providerSetting: T,
        request: TTSRequest
    ): Flow<AudioChunk>

    suspend fun getVoices(
        context: Context,
        providerSetting: T
    ): List<TTSVoice> = emptyList()
}
