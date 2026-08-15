package me.rerere.rikkahub.common.utils

import android.util.Log
import org.webrtc.audio.WebRtcAudioEffectsWrapper

private const val TAG = "WebRtcApm"

/**
 * WebRTC 原生音频处理封装（基于 AudioEffect session 绑定机制）。
 *
 * 原理：
 *   org.webrtc.audio.WebRtcAudioEffects 是 android.media.AudioEffect 的子类，
 *   绑定到 AudioRecord.getAudioSessionId() 后，Android MediaServer 会自动：
 *   1) 把同设备上 AudioTrack/ExoPlayer 播放的音频作为 far-end 参考（AEC 的参考信号）
 *   2) 把 AudioRecord 采集的麦克风音频作为 near-end 输入
 *   3) 在 native 层用 WebRTC AEC3 + NoiseSuppressor 处理，
 *      处理完再交给应用层的 AudioRecord.read() 返回
 *
 *  对比"手动喂 processStream/processReverseStream"方案的优势：
 *   - 不用我们处理采样率（框架层自动 SRC）
 *   - 不用我们处理 far-end 参考信号的时序与缓冲对齐
 *   - 不用手动切 10ms 帧
 *   - far-end 参考自动包含系统所有播放音频（包括通知声、铃声等），AEC3 能消除所有回声
 *
 * 使用步骤：
 *   1) audioRecord = AudioRecord(...)  // 先创建
 *   2) apm.attachToAudioRecordSession(audioRecord.audioSessionId)  // 立刻绑定，然后再 startRecording
 *   3) audioRecord.startRecording() + read() —— read 返回的就是消过回声+降噪的 PCM
 *   4) 结束时 apm.close()
 */
class WebRtcAudioProcessor : AutoCloseable {

    private var wrapper: WebRtcAudioEffectsWrapper? = null

    val isAecSupported: Boolean get() = wrapper?.isAecSupported() ?: false
    val isNsSupported: Boolean get() = wrapper?.isNsSupported() ?: false

    fun isAvailable(): Boolean = wrapper != null

    init {
        runCatching {
            val w = WebRtcAudioEffectsWrapper()
            wrapper = w
        }.onFailure {
            Log.e(TAG, "WebRtcAudioEffectsWrapper create failed (libjingle_peerconnection_so.so missing?)", it)
            wrapper = null
        }
    }

    fun attachToAudioRecordSession(audioSessionId: Int): Boolean {
        if (audioSessionId <= 0) {
            Log.w(TAG, "attachToAudioRecordSession: invalid sessionId=$audioSessionId")
            return false
        }
        val w = wrapper ?: run {
            Log.w(TAG, "attach: APM unavailable")
            return false
        }
        return w.attachToSession(audioSessionId)
    }

    override fun close() {
        wrapper?.release()
        wrapper = null
    }
}
