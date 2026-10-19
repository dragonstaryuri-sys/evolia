package me.rerere.tts.provider.providers

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import me.rerere.common.android.appTempFolder
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderSetting
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "SystemTTSProvider"

class SystemTTSProvider : TTSProvider<TTSProviderSetting.SystemTTS> {
    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.SystemTTS,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val audioData = suspendCancellableCoroutine<ByteArray> { continuation ->
            var tts: TextToSpeech? = null
            val listener = TextToSpeech.OnInitListener { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val ttsInstance = tts ?: error("TextToSpeech instance is null")

                    // Set language
                    val locale = Locale.getDefault()
                    val langResult = ttsInstance.setLanguage(locale)

                    if (langResult == TextToSpeech.LANG_MISSING_DATA ||
                        langResult == TextToSpeech.LANG_NOT_SUPPORTED
                    ) {
                        Log.w(TAG, "generateSpeech: Language $locale not supported")
                    }

                    // Set Voice if specified
                    providerSetting.voiceName?.let { voiceName ->
                        val voice = ttsInstance.voices?.find { it.name == voiceName }
                        if (voice != null) {
                            ttsInstance.setVoice(voice)
                        } else {
                            Log.w(TAG, "generateSpeech: Voice $voiceName not found, using default")
                        }
                    }

                    // Set speech parameters
                    ttsInstance.setSpeechRate(providerSetting.speechRate)
                    ttsInstance.setPitch(providerSetting.pitch)

                    // Create temporary file for audio output
                    val tempDir = context.appTempFolder
                    val audioFile = File(tempDir, "tts_${System.currentTimeMillis()}.wav")

                    val utteranceId = UUID.randomUUID().toString()

                    ttsInstance.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            Log.i(TAG, "onStart: TTS engine started!")
                        }

                        override fun onDone(utteranceId: String?) {
                            try {
                                if (audioFile.exists()) {
                                    val audioData = audioFile.readBytes()
                                    audioFile.delete()

                                    if (continuation.isActive) continuation.resume(audioData)
                                } else {
                                    if (continuation.isActive) continuation.resumeWithException(
                                        Exception("Failed to generate audio file")
                                    )
                                }
                            } catch (e: Exception) {
                                if (continuation.isActive) continuation.resumeWithException(e)
                            } finally {
                                ttsInstance.shutdown()
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            Log.e(TAG, "onError: TTS synthesis failed!")
                            audioFile.delete()
                            if (continuation.isActive) continuation.resumeWithException(
                                Exception("TTS synthesis failed")
                            )
                            ttsInstance.shutdown()
                        }
                    })

                    val result = ttsInstance.synthesizeToFile(
                        request.text,
                        null,
                        audioFile,
                        utteranceId
                    )

                    if (result != TextToSpeech.SUCCESS) {
                        if (continuation.isActive) continuation.resumeWithException(
                            Exception("Failed to start TTS synthesis")
                        )
                        ttsInstance.shutdown()
                    }

                } else {
                    if (continuation.isActive) continuation.resumeWithException(
                        Exception("Failed to initialize TextToSpeech engine")
                    )
                }
            }
            tts = TextToSpeech(context, listener)

            continuation.invokeOnCancellation {
                tts?.shutdown()
            }
        }

        // Android TTS 引擎输出 WAV 文件。统一契约要求 PCM：解析 WAV 头，
        // 提取采样率并剥离头部，把裸 PCM（16-bit LE）交给下游，供通话录音使用。
        val (pcmData, sampleRate) = parseWav(audioData)

        emit(
            AudioChunk(
                data = pcmData,
                format = AudioFormat.PCM,
                sampleRate = sampleRate,
                isLast = true,
                metadata = mapOf(
                    "provider" to "system",
                    "speechRate" to providerSetting.speechRate.toString(),
                    "pitch" to providerSetting.pitch.toString(),
                    "voice" to (providerSetting.voiceName ?: "default")
                )
            )
        )
    }

    /**
     * 解析标准 PCM WAV：返回 (裸 PCM 字节, 采样率)。
     * 若解析失败，退化为剥离前 44 字节（标准 WAV 头部长度）。
     */
    private fun parseWav(wav: ByteArray): Pair<ByteArray, Int> {
        if (wav.size < 44) return wav to 24000

        // RIFF 头校验
        val isRiff = wav[0] == 'R'.code.toByte() &&
            wav[1] == 'I'.code.toByte() &&
            wav[2] == 'F'.code.toByte() &&
            wav[3] == 'F'.code.toByte()
        if (!isRiff) {
            // 非 WAV，原样返回并假设 24k
            return wav to 24000
        }

        // 从 fmt 块读取采样率（字节 24-27，小端）
        val sampleRate = (wav[24].toInt() and 0xFF) or
            ((wav[25].toInt() and 0xFF) shl 8) or
            ((wav[26].toInt() and 0xFF) shl 16) or
            ((wav[27].toInt() and 0xFF) shl 24)

        // 寻找 data 块起始位置（标准 WAV 通常在偏移 44，但引擎可能插入额外块）
        var dataOffset = 44
        var dataSize = wav.size - 44
        var pos = 12
        while (pos + 8 <= wav.size) {
            val chunkId = String(wav, pos, 4, Charsets.US_ASCII)
            val chunkSize = (wav[pos + 4].toInt() and 0xFF) or
                ((wav[pos + 5].toInt() and 0xFF) shl 8) or
                ((wav[pos + 6].toInt() and 0xFF) shl 16) or
                ((wav[pos + 7].toInt() and 0xFF) shl 24)
            if (chunkId == "data") {
                dataOffset = pos + 8
                dataSize = chunkSize.coerceAtMost(wav.size - dataOffset)
                break
            }
            // 块长度按 2 字节对齐
            pos += 8 + chunkSize + (chunkSize % 2)
        }

        val pcm = wav.copyOfRange(dataOffset, (dataOffset + dataSize).coerceAtMost(wav.size))
        return pcm to sampleRate.coerceIn(8000, 48000)
    }
}
