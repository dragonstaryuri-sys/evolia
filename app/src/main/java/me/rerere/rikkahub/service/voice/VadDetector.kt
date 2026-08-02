package me.rerere.rikkahub.service.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeechSegment
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * Silero VAD 封装（基于 sherpa-onnx）。
 * 端上语音活动检测，音频不上传网络。
 * 用于通话场景的"打断检测"：AI 说话时持续监听麦克风，用户开口即触发打断。
 */
class VadDetector(context: Context) {

    private val vad: Vad = Vad(
        assetManager = context.assets,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "silero_vad.onnx",
                threshold = VAD_THRESHOLD,
                minSilenceDuration = MIN_SILENCE_DURATION_SEC,
                minSpeechDuration = MIN_SPEECH_DURATION_SEC,
                windowSize = WINDOW_SIZE,
                maxSpeechDuration = MAX_SPEECH_DURATION_SEC
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
            provider = "cpu",
            debug = false
        )
    )

    /** 送入一帧音频（建议每次 WINDOW_SIZE 个样本） */
    fun acceptWaveform(samples: FloatArray) = vad.acceptWaveform(samples)

    /** 当前帧是否检测到语音（用于即时打断判断，无需等待段切分） */
    fun isSpeechDetected(): Boolean = vad.isSpeechDetected()

    /** 取出已切分完成的语音段（用户说完一句话） */
    fun pollSegments(): List<SpeechSegment> {
        val result = mutableListOf<SpeechSegment>()
        while (!vad.empty()) {
            result.add(vad.front())
            vad.pop()
        }
        return result
    }

    /** 重置内部状态（开始新一轮监听前调用） */
    fun reset() = vad.reset()

    /** 强制输出尾部未结束的语音段（结束监听时调用） */
    fun flush() = vad.flush()

    /** 释放 native 资源 */
    fun release() = vad.release()

    companion object {
        const val SAMPLE_RATE = 16000
        const val WINDOW_SIZE = 512 // Silero VAD 固定 512 样本（16kHz 下 32ms）
        // 打断检测参数（较 ASR 切分更严格，避免扬声器回声/瞬时噪声误触发）
        private const val VAD_THRESHOLD = 0.7F // 偏高，过滤扬声器回声/环境噪声
        private const val MIN_SILENCE_DURATION_SEC = 0.25F
        private const val MIN_SPEECH_DURATION_SEC = 0.6F // 持续 600ms 才算用户开口打断
        private const val MAX_SPEECH_DURATION_SEC = 30F
    }
}
