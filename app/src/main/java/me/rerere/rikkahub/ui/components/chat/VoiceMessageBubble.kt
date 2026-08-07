package me.rerere.rikkahub.ui.components.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.core.net.toUri
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.voice.VoiceMessagePlayer
import me.rerere.rikkahub.service.voice.VoiceRecorderController
import me.rerere.rikkahub.service.voice.VoiceRecorderResult
import me.rerere.rikkahub.service.voice.VoiceRecorderState

/**
 * 通过 CompositionLocal 向消息列表中的语音条暴露 [VoiceMessagePlayer]。
 * 在 ChatPage 顶层用 [CompositionLocalProvider] 注入 vm.voiceMessagePlayer。
 */
val LocalVoiceMessagePlayer = compositionLocalOf<VoiceMessagePlayer?> { null }

/**
 * 语音消息气泡（类似微信语音条）。
 *
 * @param audioUrl 音频文件 url（file:// 形式），同时作为播放器去重 key
 * @param durationMs 录音时长（毫秒），作为初始展示；播放后用实际时长替换
 * @param isUser 是否是用户发的（影响图标顺序与颜色）
 * @param containerColor 气泡背景色（由调用方传入以匹配微信模式等自定义配色）
 * @param contentColor 气泡内容色
 * @param selecting 是否处于多选模式；为 true 时点击走 [onClick]（由上层切换选中），不再触发播放
 * @param onClick 点击回调（默认走播放器 toggle，可覆盖用于长按等）
 */
@Composable
fun VoiceMessageBubble(
    audioUrl: String,
    durationMs: Long,
    isUser: Boolean,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    selecting: Boolean = false,
    onClick: () -> Unit = {}
) {
    val player = LocalVoiceMessagePlayer.current
    val playback by (player?.playback?.collectAsState() ?: remember { androidx.compose.runtime.mutableStateOf(null) })
    val currentKey by (player?.currentKey?.collectAsState() ?: remember { androidx.compose.runtime.mutableStateOf(null) })

    val key = audioUrl
    val isCurrent = currentKey == key
    val isPlaying = isCurrent && playback?.isPlaying == true
    val actualDuration = if (isCurrent && (playback?.durationMs ?: 0L) > 0L) {
        playback?.durationMs ?: durationMs
    } else durationMs
    val positionMs = if (isCurrent) playback?.positionMs ?: 0L else 0L
    val progress = if (actualDuration > 0) (positionMs.toFloat() / actualDuration).coerceIn(0f, 1f) else 0f

    val icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow
    val iconContentDescription = if (isPlaying) stringResource(R.string.chat_voice_pause) else stringResource(R.string.chat_voice_play)

    // 宽度固定，不随时长变化（保持简洁一致的视觉）
    val bubbleWidth = 120.dp

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        onClick = {
            if (selecting) {
                onClick()
            } else if (player != null) {
                player.playOrToggle(key, audioUrl.toUri())
            } else {
                onClick()
            }
        },
        modifier = modifier.width(bubbleWidth)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            // 用户消息：图标在右；AI 消息：图标在左
            if (!isUser) {
                Icon(
                    imageVector = icon,
                    contentDescription = iconContentDescription,
                    tint = contentColor,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
            }

            VoiceWaveform(
                isPlaying = isPlaying,
                progress = progress,
                color = contentColor,
                modifier = Modifier
                    .weight(1f)
                    .height(20.dp)
            )

            Spacer(Modifier.width(8.dp))
            Text(
                text = formatVoiceDuration(actualDuration),
                color = contentColor,
                style = MaterialTheme.typography.bodyMedium
            )

            if (isUser) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = icon,
                    contentDescription = iconContentDescription,
                    tint = contentColor,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

/**
 * 简单的语音波形：3 条竖线，播放时中间一条做呼吸动画，进度用透明度区分已播放部分。
 * 保持极简，不做复杂波形以契合 Minimal UI。
 */
@Composable
private fun VoiceWaveform(
    isPlaying: Boolean,
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "voice_wave")
    val breath by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breath"
    )

    Canvas(modifier = modifier) {
        val barCount = 4
        val totalWidth = size.width
        val gap = totalWidth / (barCount * 2 - 1)
        val barWidth = gap
        val centerY = size.height / 2

        for (i in 0 until barCount) {
            // 中间条做呼吸动画，两侧固定高度
            val baseHeight = when (i) {
                0, 3 -> size.height * 0.4f
                1, 2 -> size.height * 0.85f
                else -> size.height * 0.6f
            }
            val animatedHeight = if (isPlaying && (i == 1 || i == 2)) {
                baseHeight * (0.6f + 0.4f * breath)
            } else baseHeight

            val x = gap / 2 + i * (barWidth + gap)
            val playedRatio = (i + 1) / barCount.toFloat()
            val isPlayed = playedRatio <= progress
            val alpha = if (isPlayed) 1f else 0.4f

            drawLine(
                color = color.copy(alpha = alpha),
                start = Offset(x, centerY - animatedHeight / 2),
                end = Offset(x, centerY + animatedHeight / 2),
                strokeWidth = barWidth,
                cap = StrokeCap.Round
            )
        }
    }
}

private fun formatVoiceDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).toInt().coerceAtLeast(0)
    if (totalSeconds < 60) return "${totalSeconds}\""
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes'$seconds\""
}

/**
 * 按住说话按钮（类似微信语音输入）。
 *
 * 按下时开始录音（[VoiceRecorderController.start]），松开时停止录音
 * （[VoiceRecorderController.stop]）并把结果回调给 [onRecordComplete]。
 * 过短录音返回 null，由上层提示"录音太短"。
 *
 * @param controller 录音控制器
 * @param transcribing 是否正在 ASR 转写（松开后、发送前），为 true 时禁用按钮并显示加载
 * @param onRecordComplete 录音完成回调；null 表示过短/取消
 * @param onError 录音启动失败回调（如权限缺失、MediaRecorder 异常）
 */
@Composable
fun VoiceRecordButton(
    controller: VoiceRecorderController,
    transcribing: Boolean,
    onRecordComplete: (VoiceRecorderResult?) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val recorderState by controller.state.collectAsState()
    val durationMs by controller.durationMs.collectAsState()
    val isRecording = recorderState == VoiceRecorderState.Recording

    val holdToSpeakText = stringResource(R.string.chat_hold_to_speak)
    val recordingText = stringResource(R.string.chat_voice_recording)
    val transcribingText = stringResource(R.string.chat_voice_transcribing)

    val pulseTransition = rememberInfiniteTransition(label = "rec_pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse_alpha"
    )

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .heightIn(min = 48.dp)
            .then(
                if (transcribing) Modifier
                else Modifier.pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            try {
                                controller.start()
                            } catch (e: Exception) {
                                onError(e.message ?: "录音启动失败")
                                tryAwaitRelease()
                                return@detectTapGestures
                            }
                            val released = tryAwaitRelease()
                            if (released) {
                                val result = controller.stop()
                                onRecordComplete(result)
                            }
                        }
                    )
                }
            )
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 16.dp)
        ) {
            when {
                transcribing -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = transcribingText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                isRecording -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Canvas(modifier = Modifier.size(10.dp)) {
                            drawCircle(color = Color.Red.copy(alpha = pulseAlpha))
                        }
                        Text(
                            text = "$recordingText  ${formatVoiceDuration(durationMs)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                else -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = holdToSpeakText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
