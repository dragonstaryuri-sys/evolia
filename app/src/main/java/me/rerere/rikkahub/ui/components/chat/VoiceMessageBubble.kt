package me.rerere.rikkahub.ui.components.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
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
 * @param onClick 点击回调（selecting=true 时必走；否则默认走 toggle 播放）
 * @param onLongClick 长按回调（用于弹出操作表：多选/删除/转文字）
 * @param showTranscription 是否在气泡下方显示转写内容
 * @param transcription 转写内容（纯文本，不包含"用户发送了一条语音…"前缀）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VoiceMessageBubble(
    audioUrl: String,
    durationMs: Long,
    isUser: Boolean,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    selecting: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    showTranscription: Boolean = false,
    transcription: String? = null,
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

    val bubbleModifier = Modifier
        .width(bubbleWidth)
        .combinedClickable(
            onClick = {
                if (selecting) {
                    onClick()
                } else if (player != null) {
                    player.playOrToggle(key, audioUrl.toUri())
                } else {
                    onClick()
                }
            },
            onLongClick = onLongClick
        )

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = containerColor,
            modifier = bubbleModifier
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

        // 下方转写内容：纯文本，不拼接前缀
        val transcriptionText = transcription?.takeIf { it.isNotBlank() }
        AnimatedVisibility(
            visible = showTranscription && transcriptionText != null,
            enter = expandVertically(animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f)) + fadeIn(),
            exit = shrinkVertically(animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f)) + fadeOut()
        ) {
            // 退出动画期间 transcription 可能被置空，这里必须做空判断，避免 NPE
            if (transcriptionText != null) {
                val transcriptionBg = if (isUser) {
                    // 用户侧：浅一点的 primaryContainer 同色系半透明
                    containerColor.copy(alpha = 0.35f)
                } else {
                    // 助手侧：surface 层级半透明
                    MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f)
                }
                Text(
                    text = transcriptionText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .width(bubbleWidth + 80.dp) // 文本比语音条稍宽，提升可读性
                        .clip(RoundedCornerShape(12.dp))
                        .background(transcriptionBg)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
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
 * 录音时手指上滑后的目标动作区。
 * - [None]：未上滑或在中间区域，松开即发送语音。
 * - [Cancel]：左上滑，松开取消录音。
 * - [Transcribe]：右上滑，松开发送语音并自动展开转文字。
 */
private enum class RecordZone { None, Cancel, Transcribe }

/**
 * 按住说话按钮（类似微信语音输入）。
 *
 * 按下时开始录音（[VoiceRecorderController.start]），松开时停止录音
 * （[VoiceRecorderController.stop]）并把结果回调给 [onRecordComplete]。
 * 过短录音返回 null，由上层提示"录音太短"。
 *
 * 手势分区（类似微信）：
 * - 直接松开 → 发送语音消息。
 * - 左上滑（上滑且向左）→ 取消录音。
 * - 右上滑（上滑且向右）→ 发送语音并自动展开转文字。
 *
 * @param controller 录音控制器
 * @param transcribing 是否正在 ASR 转写（松开后、发送前），为 true 时禁用按钮并显示加载
 * @param onRecordComplete 录音完成回调；第一个参数为 null 表示过短/取消，
 *   第二个参数表示是否需要自动展开转文字（右上滑时为 true）
 * @param onError 录音启动失败回调（如权限缺失、MediaRecorder 异常）
 */
@Composable
fun VoiceRecordButton(
    controller: VoiceRecorderController,
    transcribing: Boolean,
    onRecordComplete: (VoiceRecorderResult?, showTranscription: Boolean) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val recorderState by controller.state.collectAsState()
    val durationMs by controller.durationMs.collectAsState()
    val isRecording = recorderState == VoiceRecorderState.Recording

    val holdToSpeakText = stringResource(R.string.chat_hold_to_speak)
    val recordingText = stringResource(R.string.chat_voice_recording)
    val transcribingText = stringResource(R.string.chat_voice_transcribing)
    val slideUpCancelText = stringResource(R.string.chat_voice_slide_up_cancel)
    val slideRightTranscribeText = stringResource(R.string.chat_voice_slide_right_transcribe)
    val releaseToCancelText = stringResource(R.string.chat_voice_release_to_cancel)
    val releaseToTranscribeText = stringResource(R.string.chat_voice_release_to_transcribe)

    // 当前上滑目标区
    var zone by remember { mutableStateOf(RecordZone.None) }
    val density = LocalDensity.current
    // 垂直方向触发阈值（上滑超过此距离才进入分区判定）
    val verticalThresholdPx = with(density) { 80.dp.toPx() }

    val pulseTransition = rememberInfiniteTransition(label = "rec_pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse_alpha"
    )

    val isCancelZone = zone == RecordZone.Cancel
    val isTranscribeZone = zone == RecordZone.Transcribe

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = when (zone) {
            RecordZone.Cancel -> MaterialTheme.colorScheme.errorContainer
            RecordZone.Transcribe -> MaterialTheme.colorScheme.primaryContainer
            RecordZone.None -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = modifier
            .heightIn(min = 48.dp)
            .then(
                if (transcribing) Modifier
                else Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // 按下：开始录音
                        zone = RecordZone.None
                        val startY = down.position.y
                        try {
                            controller.start()
                        } catch (e: Exception) {
                            onError(e.message ?: "录音启动失败")
                            return@awaitEachGesture
                        }
                        // 持续追踪手指移动，检测上滑方向
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: continue
                            val currentY = change.position.y
                            val deltaY = startY - currentY // 正数=上滑

                            if (deltaY > verticalThresholdPx) {
                                // 已上滑超过阈值，按手指在屏幕上的绝对水平位置分区
                                // 按钮居中在屏幕底部，左半屏 = 取消，右半屏 = 转文字
                                // 用手指相对按钮中心的水平偏移来判定
                                val centerPx = size.width / 2f
                                val offsetFromCenter = change.position.x - centerPx
                                zone = if (offsetFromCenter < 0) {
                                    RecordZone.Cancel
                                } else {
                                    RecordZone.Transcribe
                                }
                            } else {
                                zone = RecordZone.None
                            }

                            if (change.changedToUp()) {
                                // 手指抬起
                                when (zone) {
                                    RecordZone.Cancel -> {
                                        // 左上滑取消
                                        controller.cancel()
                                        onRecordComplete(null, false)
                                    }
                                    RecordZone.Transcribe -> {
                                        // 右上滑转文字：发送语音并自动展开转写
                                        val result = controller.stop()
                                        onRecordComplete(result, true)
                                    }
                                    RecordZone.None -> {
                                        // 正常发送
                                        val result = controller.stop()
                                        onRecordComplete(result, false)
                                    }
                                }
                                break
                            }
                        }
                    }
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
                    // 录音中按钮自身只显示一个简单的 Mic 图标（详细状态走中央遮罩 Popup）
                    Icon(
                        imageVector = Icons.Rounded.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
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

    // 录音中浮层：全屏半透明遮罩 + 底部波形/提示，类似微信
    if (isRecording) {
        Popup(
            properties = PopupProperties(
                focusable = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            )
        ) {
            RecordingOverlay(
                durationMs = durationMs,
                amplitude = controller.amplitude.collectAsState().value,
                isCancelZone = isCancelZone,
                isTranscribeZone = isTranscribeZone,
                recordingText = recordingText,
                slideUpCancelText = slideUpCancelText,
                slideRightTranscribeText = slideRightTranscribeText,
                releaseToCancelText = releaseToCancelText,
                releaseToTranscribeText = releaseToTranscribeText
            )
        }
    }
}

/**
 * 录音中遮罩浮层（全屏半透明 + 底部操作区，类似微信）。
 *
 * 布局自上而下：波形 → 计时 → 取消/转文字按钮（最靠近手指）。
 * 按钮图案直观，不再显示文字提示；进入操作区时仅显示对应松开提示。
 */
@Composable
private fun RecordingOverlay(
    durationMs: Long,
    amplitude: Int,
    isCancelZone: Boolean,
    isTranscribeZone: Boolean,
    recordingText: String,
    slideUpCancelText: String,
    slideRightTranscribeText: String,
    releaseToCancelText: String,
    releaseToTranscribeText: String
) {
    val bgColor = when {
        isCancelZone -> Color(0x99FF4D4F)
        isTranscribeZone -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        else -> Color(0x66000000)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 100.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 波形条：仅在未进入操作区时显示
            if (!isCancelZone && !isTranscribeZone) {
                WaveformBars(
                    amplitude = amplitude,
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth(0.7f)
                )
            }

            // 时长
            Text(
                text = formatVoiceDuration(durationMs),
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White
            )

            // 松开提示（仅进入操作区时显示）
            val hintText = when {
                isCancelZone -> releaseToCancelText
                isTranscribeZone -> releaseToTranscribeText
                else -> ""
            }
            if (hintText.isNotEmpty()) {
                Text(
                    text = hintText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.9f)
                )
            }

            // 取消（左）/ 转文字（右）按钮 — 放在最下方，靠近手指
            Row(
                modifier = Modifier.fillMaxWidth(0.75f),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 取消图标（药丸形，增大滑动命中区域）
                Box(
                    modifier = Modifier
                        .width(90.dp)
                        .height(56.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(
                            if (isCancelZone) Color.White else Color.White.copy(alpha = 0.25f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = if (isCancelZone) Color(0xFFFF4D4F) else Color.White
                    )
                }

                // 转文字图标（药丸形，增大滑动命中区域）
                Box(
                    modifier = Modifier
                        .width(90.dp)
                        .height(56.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(
                            if (isTranscribeZone) Color.White else Color.White.copy(alpha = 0.25f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.TextFields,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = if (isTranscribeZone) MaterialTheme.colorScheme.primary else Color.White
                    )
                }
            }
        }
    }
}

/**
 * 波形条：20 根竖条，根据 amplitude (0-32767) 动态伸缩。
 */
@Composable
private fun WaveformBars(
    amplitude: Int,
    color: Color,
    modifier: Modifier = Modifier
) {
    val normalized = (amplitude.coerceIn(0, 8000) / 8000f) // 0f~1f
    val barCount = 20
    val baseHeight = 6.dp
    val maxHeight = 36.dp

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(barCount) { index ->
            val timeFactor = kotlin.math.sin(
                (System.currentTimeMillis() / 120.0 + index * 0.8).toFloat()
            )
            val heightFactor = (0.25f + normalized * 0.75f * (0.4f + 0.6f * timeFactor)).coerceIn(0.15f, 1f)
            val h = baseHeight + (maxHeight - baseHeight) * heightFactor
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(h)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color.copy(alpha = 0.6f + 0.4f * normalized))
            )
        }
    }
}
