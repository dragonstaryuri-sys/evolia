package org.webrtc.audio

import android.util.Log

private const val TAG = "WebRtcAudioEffectsWrapper"

/**
 * WebRtcAudioEffects 是 package-private（只对 org.webrtc.audio 包可见），
 * 外部模块无法直接 import。这个 wrapper 放在同一个包里，用 public class
 * 暴露需要的方法，供 me.rerere.rikkahub.common.utils.WebRtcAudioProcessor 调用。
 */
class WebRtcAudioEffectsWrapper {

    private val effects: WebRtcAudioEffects = WebRtcAudioEffects()

    init {
        val aecOk = effects.setAEC(true)
        val nsOk = effects.setNS(true)
        Log.i(
            TAG,
            "WebRtcAudioEffects created: setAEC(true)=$aecOk, setNS(true)=$nsOk, " +
                "aecSupported=${WebRtcAudioEffects.isAcousticEchoCancelerSupported()}, " +
                "nsSupported=${WebRtcAudioEffects.isNoiseSuppressorSupported()}"
        )
    }

    fun isAecSupported(): Boolean = WebRtcAudioEffects.isAcousticEchoCancelerSupported()
    fun isNsSupported(): Boolean = WebRtcAudioEffects.isNoiseSuppressorSupported()

    fun attachToSession(audioSessionId: Int): Boolean {
        return runCatching {
            effects.enable(audioSessionId)
            Log.i(TAG, "Attached to AudioRecord session=$audioSessionId (AEC3+NS active)")
            true
        }.onFailure {
            Log.e(TAG, "attachToSession(id=$audioSessionId) FAILED: ${it.message}", it)
        }.getOrDefault(false)
    }

    fun release() {
        runCatching { effects.release() }.onFailure {
            Log.w(TAG, "release() exception: ${it.message}")
        }
        Log.i(TAG, "WebRtcAudioEffects released")
    }
}
