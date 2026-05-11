package me.rerere.rikkahub.ui.hooks

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.rerere.tts.model.PlaybackState
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.rikkahub.utils.stripMarkdown
import me.rerere.tts.model.TTSResponse
import me.rerere.tts.model.TTSVoice
import me.rerere.tts.provider.TTSManager
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.controller.TtsController
import org.koin.compose.koinInject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private const val TAG = "TTS"

// Refined regex to match emojis without being too aggressive on standard full-width punctuation
private val EMOJI_REGEX = Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF]|[\\u2600-\\u27BF]")

/**
 * Composable function to remember and manage custom TTS state.
 * Uses user-configured TTS providers instead of system TTS.
 */
@Composable
fun rememberCustomTtsState(): CustomTtsState {
    val context = LocalContext.current
    val settingsStore = koinInject<SettingsStore>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()

    // Remember the CustomTtsState instance across recompositions
    val ttsState = remember {
        CustomTtsStateImpl(
            context = context.applicationContext,
            settingsStore = settingsStore
        )
    }

    // Update the provider when settings change
    DisposableEffect(settings.selectedTTSProviderId, settings.ttsProviders) {
        ttsState.updateProvider(settings.getSelectedTTSProvider())
        onDispose { }
    }

    // Cleanup resources when the state is disposed
    DisposableEffect(ttsState) {
        onDispose {
            ttsState.cleanup()
        }
    }

    return ttsState
}

/**
 * Interface defining the public API of our custom TTS state holder.
 */
interface CustomTtsState {
    /** Flow indicating if the TTS provider is available and ready. */
    val isAvailable: StateFlow<Boolean>

    /** Flow indicating if the TTS is currently speaking. */
    val isSpeaking: StateFlow<Boolean>

    /** Flow holding any error message. */
    val error: StateFlow<String?>

    /** Flow indicating current chunk being processed (index) */
    val currentChunk: StateFlow<Int>

    /** Flow indicating total chunks in queue */
    val totalChunks: StateFlow<Int>

    /** Unified playback state (status, position, duration, speed, etc.) */
    val playbackState: StateFlow<PlaybackState>

    /**
     * Speaks the given text using the selected TTS provider.
     * Long texts will be automatically chunked and queued.
     */
    fun speak(text: String, flushCalled: Boolean = true, overrideSetting: TTSProviderSetting? = null)

    /** Stops the current speech and clears the queue. */
    fun stop()

    /** Pauses the current playback. */
    fun pause()

    /** Resumes the paused playback. */
    fun resume()

    /** Skips to the next chunk in the queue. */
    fun skipNext()

    /** Fast forward current playback by [ms]. */
    fun fastForward(ms: Long = 5_000)

    /** Set playback [speed]. */
    fun setSpeed(speed: Float)

    /** Get available voices for a provider. */
    suspend fun getVoices(providerSetting: TTSProviderSetting): List<TTSVoice>

    /** Cleanup resources. */
    fun cleanup()
}

/**
 * Internal implementation of CustomTtsState.
 */
private class CustomTtsStateImpl(
    private val context: Context,
    private val settingsStore: SettingsStore
) : CustomTtsState, KoinComponent {

    private val ttsManager by inject<TTSManager>()
    private val controller by lazy { me.rerere.tts.controller.TtsController(context, ttsManager) }

    private val scope = CoroutineScope(Dispatchers.Main)
    private var currentJob: Job? = null

    override val isAvailable: StateFlow<Boolean> get() = controller.isAvailable
    override val isSpeaking: StateFlow<Boolean> get() = controller.isSpeaking
    override val error: StateFlow<String?> get() = controller.error
    override val currentChunk: StateFlow<Int> get() = controller.currentChunk
    override val totalChunks: StateFlow<Int> get() = controller.totalChunks
    override val playbackState: StateFlow<PlaybackState> get() = controller.playbackState

    fun updateProvider(provider: TTSProviderSetting?) {
        controller.setProvider(provider)
    }

    override fun speak(text: String, flushCalled: Boolean, overrideSetting: TTSProviderSetting?) {
        Log.d(TAG, "[Speak] Raw input: \"$text\"")

        // Step 1: Apply TTS filters (Robust matching for all bracket types)
        val filtered = applyTtsTextFilters(text)

        // Step 2: Strip Markdown syntax
        val processed = filtered.stripMarkdown()

        if (processed.isBlank()) {
            Log.d(TAG, "[Speak] Text fully filtered, nothing to speak")
            return
        }

        if (overrideSetting != null) {
            Log.d(TAG, "[Speak] Speaking with override: \"$processed\"")
            controller.speakWithProvider(processed, overrideSetting, flushCalled)
        } else {
            Log.d(TAG, "[Speak] Speaking: \"$processed\"")
            controller.speak(processed, flushCalled)
        }
    }

    /**
     * Apply TTS text filter rules to the text.
     */
    private fun applyTtsTextFilters(text: String): String {
        Log.d(TAG, "[Filter] Start. Input: \"$text\"")
        val settings = settingsStore.settingsFlow.value
        val rules = settings.displaySetting.ttsTextFilterRules.filter { it.enabled }
        val filterEmojis = settings.displaySetting.filterEmojis

        var result = text

        // Helper to get flexible regex pattern for single char or escaped string
        fun toFlex(s: String): String {
            return when (val trimmed = s.trim()) {
                "(", "（" -> "[\\(\\（]"
                ")", "）" -> "[\\)\\）]"
                "[", "【" -> "[\\[\\【]"
                "]", "】" -> "[\\]\\】]"
                "{", "｛" -> "[\\{\\｛]"
                "}", "｝" -> "[\\}\\｝]"
                "<", "《" -> "[\\<\\《]"
                ">", "》" -> "[\\>\\》]"
                else -> Regex.escape(trimmed)
            }
        }

        // Helper to resolve start/end patterns intelligently
        fun resolvePatterns(rule: me.rerere.rikkahub.data.datastore.TtsTextFilterRule): Pair<String, String> {
            val p = rule.pattern.trim()
            val ep = rule.endPattern?.trim()?.takeIf { it.isNotBlank() }

            var startPart = p
            var endPart = ep ?: p

            if (ep == null) {
                // If single bracket char, find its pair
                if (p.length == 1) {
                    endPart = when (p) {
                        "(" -> ")"; "（" -> "）"; "[" -> "]"; "【" -> "】"
                        "{" -> "}"; "｛" -> "｝"; "<" -> ">"; "《" -> "》"
                        else -> p
                    }
                }
                // If pair string like "()" or "（ ）" or "( )"
                else if (p.contains(" ") || p.length == 2) {
                    val parts = if (p.contains(" ")) p.split(Regex("\\s+")) else listOf(p[0].toString(), p[1].toString())
                    if (parts.size == 2) {
                        startPart = parts[0]
                        endPart = parts[1]
                    }
                }
            }

            return toFlex(startPart) to toFlex(endPart)
        }

        // 1. Apply ONLY_READ rules first (while brackets are still present)
        val onlyReadRules = rules.filter { it.mode == me.rerere.rikkahub.data.datastore.TtsFilterMode.ONLY_READ }
        if (onlyReadRules.isNotEmpty()) {
            val extracted = StringBuilder()
            for (rule in onlyReadRules) {
                val (s, e) = resolvePatterns(rule)
                val regex = Regex("$s(.*?)$e", RegexOption.DOT_MATCHES_ALL)
                val matches = regex.findAll(result).toList()
                Log.d(TAG, "[Filter] ONLY_READ Rule '${rule.pattern}': matched ${matches.size} times")
                matches.forEach { match ->
                    if (extracted.isNotEmpty()) extracted.append(" ")
                    extracted.append(match.groupValues[1])
                }
            }
            if (extracted.isNotEmpty()) {
                result = extracted.toString()
                Log.d(TAG, "[Filter] Result after ONLY_READ: \"$result\"")
            } else if (onlyReadRules.any { it.enabled }) {
                result = ""
                Log.d(TAG, "[Filter] ONLY_READ matched nothing, clearing text")
            }
        }

        // 2. Apply SKIP rules (while brackets are still present)
        val skipRules = rules.filter { it.mode == me.rerere.rikkahub.data.datastore.TtsFilterMode.SKIP }
        for (rule in skipRules) {
            val (s, e) = resolvePatterns(rule)
            val regex = Regex("$s.*?$e", RegexOption.DOT_MATCHES_ALL)
            var lastResult: String
            var count = 0
            do {
                lastResult = result
                result = result.replace(regex, "")
                if (result != lastResult) count++
            } while (result != lastResult)
            if (count > 0) {
                Log.d(TAG, "[Filter] SKIP Rule '${rule.pattern}': applied $count times. Current result: \"$result\"")
            }
        }

        // 3. Filter emojis LAST. This ensures brackets are intact for rules matching above.
        if (filterEmojis) {
            val beforeEmoji = result
            result = result.replace(EMOJI_REGEX, "")
            if (beforeEmoji != result) {
                Log.d(TAG, "[Filter] After Emoji removal: \"$result\"")
            }
        }

        Log.d(TAG, "[Filter] Final output: \"${result.trim()}\"")
        return result.trim()
    }

    override fun stop() {
        controller.stop()
    }

    override fun pause() {
        controller.pause()
    }

    override fun resume() {
        controller.resume()
    }

    override fun skipNext() {
        controller.skipNext()
    }

    override fun fastForward(ms: Long) {
        controller.fastForward(ms)
    }

    override fun setSpeed(speed: Float) {
        controller.setSpeed(speed)
    }

    override suspend fun getVoices(providerSetting: TTSProviderSetting): List<TTSVoice> {
        return ttsManager.getVoices(providerSetting)
    }

    override fun cleanup() {
        controller.dispose()
        currentJob = null
    }
}
