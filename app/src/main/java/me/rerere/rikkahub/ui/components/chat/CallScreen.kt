package me.rerere.rikkahub.ui.components.chat

import me.rerere.rikkahub.ui.theme.AppShapes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.R
import me.rerere.rikkahub.core.data.model.Assistant
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics

/** 左滑缩小为迷你窗口的最小拖动距离（dp）。 */
private const val MIN_SWIPE_TO_MINIMIZE_DP = 80

// 通话页为沉浸式深色固定画布（黑白明暗不随主题切换），
// 以下为该画布上的固定语义色（挂断红 / 接听绿 / DUCKING 黄 / 声纹绿），不参与主题取色。
private val CallRejectColor = Color(0xFFE53935)
private val CallAcceptColor = Color(0xFF43A047)
private val CallDuckingColor = Color(0xFFFFC107)
private val CallVoiceColor = Color(0xFF4CAF50)

/**
 * 通话状态枚举
 */
enum class CallStatus {
    CONNECTING,
    LISTENING,
    THINKING,
    SPEAKING,
    DUCKING,
    DISCONNECTED
}

/**
 * 通话字幕面板的转写条目（角色 + 清洗后的纯文本），按时间正序，
 * 由 VoiceCallManager.callTranscript 提供。
 */
data class CallTranscriptEntry(
    val role: MessageRole,
    val text: String
)

/**
 * Agent 来电响铃页：等待用户「接听 / 拒绝」。
 *
 * 设计约束：
 * - 未接通前绝不显示为「已接通」：只展示来电提示，不展示通话状态/时长/静音等控制。
 * - 交互对齐手机原生来电页：两个圆钮点击即触发，右绿 = 接听，左红 = 挂断。
 */
@Composable
fun IncomingCallScreen(
    assistant: Assistant,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = rememberPremiumHaptics()
    val infiniteTransition = rememberInfiniteTransition(label = "incoming_ring")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "incoming_pulse"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            UIAvatar(
                value = assistant.avatar,
                name = assistant.name,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(50.dp)
                    .graphicsLayer { alpha = 0.45f },
                onUpdate = null
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)),
                            startY = 400f
                        )
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 80.dp)
            ) {
                Text(
                    text = assistant.name,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
                Surface(color = Color.White.copy(alpha = 0.1f), shape = CircleShape) {
                    Text(
                        text = "来电",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
            }

            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .graphicsLayer {
                            scaleX = pulse
                            scaleY = pulse
                            alpha = 0.25f
                        }
                        .background(Color.White.copy(alpha = 0.25f), CircleShape)
                )
                UIAvatar(
                    value = assistant.avatar,
                    name = assistant.name,
                    modifier = Modifier
                        .size(160.dp)
                        .clip(CircleShape),
                    onUpdate = null
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 64.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 挂断：向上滑触发（左红钮）
                SwipeUpCallButton(
                    icon = Icons.Rounded.CallEnd,
                    label = "挂断",
                    background = CallRejectColor,
                    onTrigger = {
                        haptics.perform(HapticPattern.Thud)
                        onReject()
                    }
                )
                // 接听：向上滑触发（右绿钮）
                SwipeUpCallButton(
                    icon = Icons.Rounded.Call,
                    label = "接听",
                    background = CallAcceptColor,
                    onTrigger = {
                        haptics.perform(HapticPattern.Pop)
                        onAccept()
                    }
                )
            }
        }
    }
}

/**
 * 用户主动拨号等待页：已发出呼叫，等待 AI 决定是否接听。
 * 期间不视为「已接通」，只提供取消按钮。
 */
@Composable
fun OutgoingCallWaitingScreen(
    assistant: Assistant,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = rememberPremiumHaptics()
    val infiniteTransition = rememberInfiniteTransition(label = "outgoing_wait")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "outgoing_pulse"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            UIAvatar(
                value = assistant.avatar,
                name = assistant.name,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(50.dp)
                    .graphicsLayer { alpha = 0.45f },
                onUpdate = null
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)),
                            startY = 400f
                        )
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 80.dp)
            ) {
                Text(
                    text = assistant.name,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
                Surface(color = Color.White.copy(alpha = 0.1f), shape = CircleShape) {
                    Text(
                        text = "正在呼叫…",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
            }

            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .graphicsLayer {
                            scaleX = pulse
                            scaleY = pulse
                            alpha = 0.2f
                        }
                        .background(Color.White.copy(alpha = 0.2f), CircleShape)
                )
                UIAvatar(
                    value = assistant.avatar,
                    name = assistant.name,
                    modifier = Modifier
                        .size(160.dp)
                        .clip(CircleShape),
                    onUpdate = null
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 80.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CallAnswerButton(
                    icon = Icons.Rounded.CallEnd,
                    label = "取消",
                    background = CallRejectColor,
                    onClick = {
                        haptics.perform(HapticPattern.Thud)
                        onCancel()
                    }
                )
            }
        }
    }
}

@Composable
private fun CallAnswerButton(
    icon: ImageVector,
    label: String,
    background: Color,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "answer_scale"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .background(background, CircleShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(bounded = false, radius = 40.dp),
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(34.dp)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = 0.9f)
        )
    }
}

/**
 * 来电页接听/挂断圆钮：点击即触发，左红挂断、右绿接听。
 */
@Composable
private fun SwipeUpCallButton(
    icon: ImageVector,
    label: String,
    background: Color,
    onTrigger: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(background, CircleShape)
                .clickable(onClick = onTrigger),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(34.dp)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = 0.9f)
        )
    }
}

@Composable
fun CallScreen(
    assistant: Assistant,
    status: CallStatus,
    isMuted: Boolean,
    isSpeakerOn: Boolean,
    isHeadsetConnected: Boolean = false,
    durationMs: Long = 0L,
    subtitleEnabled: Boolean = false,
    transcript: List<CallTranscriptEntry> = emptyList(),
    listeningText: String? = null,
    isNearEar: Boolean = false,
    onMuteToggle: () -> Unit,
    onSpeakerToggle: () -> Unit,
    onHangup: () -> Unit,
    onInterrupt: () -> Unit = {},
    onMinimize: () -> Unit = {},
    onSubtitleToggle: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val haptics = rememberPremiumHaptics()
    // 左滑缩小：累计水平拖动距离，向左超过阈值时触发缩小
    var dragTotalX by remember { mutableStateOf(0f) }

    // 1. 呼吸动画控制 (多层联动)
    val infiniteTransition = rememberInfiniteTransition(label = "breathing_aura")

    // 内圈缩放
    val innerScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = when (status) {
            CallStatus.SPEAKING -> 1.25f
            CallStatus.DUCKING -> 1.18f
            else -> 1.1f
        },
        animationSpec = infiniteRepeatable(
            animation = tween(
                when (status) {
                    CallStatus.SPEAKING -> 1200
                    CallStatus.DUCKING -> 400
                    else -> 2500
                }, easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "inner_scale"
    )

    // 外圈缩放 (稍微滞后且幅度更大)
    val outerScale by infiniteTransition.animateFloat(
        initialValue = 1.05f,
        targetValue = when (status) {
            CallStatus.SPEAKING -> 1.45f
            CallStatus.DUCKING -> 1.3f
            else -> 1.15f
        },
        animationSpec = infiniteRepeatable(
            animation = tween(
                when (status) {
                    CallStatus.SPEAKING -> 1500
                    CallStatus.DUCKING -> 500
                    else -> 3000
                }, easing = LinearOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "outer_scale"
    )

    // 动态光圈颜色
    val auraColor by animateColorAsState(
        targetValue = when (status) {
            CallStatus.SPEAKING -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            CallStatus.THINKING -> Color.White.copy(alpha = 0.3f)
            CallStatus.DUCKING -> CallDuckingColor.copy(alpha = 0.55f)
            else -> Color.White.copy(alpha = 0.2f)
        },
        animationSpec = tween(500),
        label = "aura_color"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                // 只观察不消费：全屏 Box 参与命中测试即可阻止触摸穿透到下层的消息气泡，
                // 不需要 consume（在 Final pass consume 会污染子组件的手势协商状态，
                // 导致字幕列表的 LazyColumn 收不到拖动）。
                // 同时检测左滑手势：向左拖动超过阈值时触发缩小为迷你窗口。
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        event.changes.forEach { change ->
                            if (change.isConsumed) {
                                // 字幕面板滚动等子组件已接管手势：
                                // 放弃本次左滑累计，避免滚动被误判为缩小手势。
                                dragTotalX = 0f
                                return@forEach
                            }
                            if (change.pressed) {
                                dragTotalX += change.positionChange().x
                            } else {
                                // 抬手：判断是否为左滑缩小
                                if (dragTotalX < -MIN_SWIPE_TO_MINIMIZE_DP.dp.toPx()) {
                                    onMinimize()
                                }
                                dragTotalX = 0f
                            }
                        }
                    }
                }
            }
    ) {
        // 1. 背景处理：复用对话背景逻辑
        if (assistant.background != null) {
            // 通话界面始终使用深色背景（与系统深色/浅色模式无关）
            val scrimAlpha = assistant.backgroundDim.coerceIn(0f, 0.85f)
            val scrimColor = Color.Black.copy(alpha = scrimAlpha)
            Box(modifier = Modifier.fillMaxSize()) {
                AsyncImage(
                    model = assistant.background,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(scrimColor)
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                UIAvatar(
                    value = assistant.avatar,
                    name = assistant.name,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(50.dp)
                        .graphicsLayer { alpha = 0.45f },
                    onUpdate = null
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)),
                                startY = 400f
                            )
                        )
                )
            }
        }

        // 2. 核心展示区域
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 顶部信息（居中）+ 右上角操作按钮（字幕开关 / 缩小通话）
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 80.dp)
                ) {
                    Text(
                        text = assistant.name,
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = Color.White.copy(alpha = 0.1f),
                        shape = CircleShape
                    ) {
                        Text(
                            text = when (status) {
                                CallStatus.CONNECTING -> stringResource(R.string.call_status_connecting)
                                CallStatus.LISTENING -> stringResource(R.string.call_status_listening)
                                CallStatus.THINKING -> stringResource(R.string.call_status_thinking)
                                CallStatus.SPEAKING -> stringResource(R.string.call_status_speaking)
                                CallStatus.DUCKING -> stringResource(R.string.call_status_ducking)
                                CallStatus.DISCONNECTED -> stringResource(R.string.call_status_disconnected)
                            },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                    // 通话时长：与迷你窗同格式（%02d:%02d），接通后每秒刷新
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = formatCallDuration(durationMs),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.65f)
                    )
                    // 手动打断按钮：只在 AI 正在思考/说话/检测到用户声音时显示
                    if (status == CallStatus.THINKING ||
                        status == CallStatus.SPEAKING ||
                        status == CallStatus.DUCKING
                    ) {
                        Spacer(modifier = Modifier.height(12.dp))
                        val interruptInteractionSource = remember { MutableInteractionSource() }
                        val isInterruptPressed by interruptInteractionSource.collectIsPressedAsState()
                        val interruptScale by animateFloatAsState(
                            targetValue = if (isInterruptPressed) 0.9f else 1f,
                            animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
                            label = "interrupt_scale"
                        )
                        Surface(
                            color = Color.White.copy(alpha = 0.15f),
                            shape = CircleShape,
                            modifier = Modifier
                                .graphicsLayer {
                                    scaleX = interruptScale
                                    scaleY = interruptScale
                                }
                                .clickable(
                                    interactionSource = interruptInteractionSource,
                                    indication = ripple(bounded = false, radius = 28.dp),
                                    onClick = {
                                        haptics.perform(HapticPattern.Pop)
                                        onInterrupt()
                                    }
                                )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.FrontHand,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "打断",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }

                // 右上角操作按钮：字幕开关（左）+ 缩小通话（右）
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 16.dp, end = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CallIconButton(
                        icon = Icons.Rounded.ClosedCaption,
                        contentDescription = stringResource(R.string.call_action_subtitle),
                        active = subtitleEnabled,
                        onClick = onSubtitleToggle
                    )
                    CallIconButton(
                        icon = Icons.Rounded.Minimize,
                        contentDescription = stringResource(R.string.call_action_minimize),
                        active = false,
                        onClick = onMinimize
                    )
                }
            }

            // 中间：动态头像 +（字幕开启时的）实时转写面板
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // 字幕开启时头像整体缩小，为转写面板让出空间
                val avatarScale by animateFloatAsState(
                    targetValue = if (subtitleEnabled) 0.7f else 1f,
                    animationSpec = tween(300),
                    label = "call_avatar_scale"
                )

                // 动态头像 / 多层呼吸光圈：
                // 字幕开启时占据剩余空间并居中，面板固定在下方
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = if (subtitleEnabled) {
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .graphicsLayer {
                                scaleX = avatarScale
                                scaleY = avatarScale
                            }
                    } else {
                        Modifier.graphicsLayer {
                            scaleX = avatarScale
                            scaleY = avatarScale
                        }
                    }
                ) {
                    // 外层灵动光圈
                    Box(
                        modifier = Modifier
                            .size(240.dp)
                            .graphicsLayer {
                                scaleX = outerScale
                                scaleY = outerScale
                                alpha = if (status == CallStatus.SPEAKING) 0.4f else 0.15f
                            }
                            .background(auraColor, CircleShape)
                    )

                    // 内层灵动光圈
                    Box(
                        modifier = Modifier
                            .size(200.dp)
                            .graphicsLayer {
                                scaleX = innerScale
                                scaleY = innerScale
                                alpha = if (status == CallStatus.SPEAKING) 0.6f else 0.3f
                            }
                            .background(auraColor, CircleShape)
                    )

                    // 实体头像
                    UIAvatar(
                        value = assistant.avatar,
                        name = assistant.name,
                        modifier = Modifier
                            .size(160.dp)
                            .clip(CircleShape)
                            .graphicsLayer {
                                // 极轻微的头像自身缩放
                                val s = 1f + (innerScale - 1f) * 0.1f
                                scaleX = s
                                scaleY = s
                            },
                        onUpdate = null
                    )
                }

                // 实时转写面板：由右上角字幕开关控制显隐，关闭时为纯语音通话界面
                if (subtitleEnabled) {
                    CallTranscriptPanel(
                        status = status,
                        transcript = transcript,
                        listeningText = listeningText,
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .heightIn(min = 180.dp, max = 300.dp)
                            .padding(bottom = 12.dp)
                    )
                }
            }

            // 底部控制按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 80.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 静音按钮
                CallControlButton(
                    icon = if (isMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                    label = stringResource(R.string.call_action_mute),
                    active = isMuted,
                    onClick = {
                        haptics.perform(HapticPattern.Pop)
                        onMuteToggle()
                    }
                )

                // 挂断按钮 (大红钮 + 物理缩放)
                val hangupInteractionSource = remember { MutableInteractionSource() }
                val isHangupPressed by hangupInteractionSource.collectIsPressedAsState()
                val hangupScale by animateFloatAsState(
                    targetValue = if (isHangupPressed) 0.85f else 1f,
                    animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
                    label = "hangup_scale"
                )

                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .graphicsLayer {
                            scaleX = hangupScale
                            scaleY = hangupScale
                        }
                        .background(CallRejectColor, CircleShape)
                        .clickable(
                            interactionSource = hangupInteractionSource,
                            indication = ripple(bounded = false, radius = 44.dp),
                            onClick = {
                                haptics.perform(HapticPattern.Thud)
                                onHangup()
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CallEnd,
                        contentDescription = stringResource(R.string.call_action_end),
                        tint = Color.White,
                        modifier = Modifier.size(38.dp)
                    )
                }

                // 扬声器/耳机按钮
                // 耳机连接时显示耳机图标，按钮 active 但点击无操作（防止破坏耳机路由）
                val speakerIcon = when {
                    isHeadsetConnected -> Icons.Rounded.Headset
                    isSpeakerOn -> Icons.AutoMirrored.Rounded.VolumeUp
                    else -> Icons.AutoMirrored.Rounded.VolumeDown
                }
                val speakerLabel = if (isHeadsetConnected) {
                    stringResource(R.string.call_action_headset)
                } else {
                    stringResource(R.string.call_action_speaker)
                }
                CallControlButton(
                    icon = speakerIcon,
                    label = speakerLabel,
                    active = isHeadsetConnected || isSpeakerOn,
                    onClick = {
                        haptics.perform(HapticPattern.Pop)
                        onSpeakerToggle()
                    }
                )
            }
        }

        // 3. 贴近耳朵 → 全黑遮罩（兜底息屏 + 绝对防止误触）
        //    - 系统 proximityWakeLock 生效时这层会被"黑屏盖过"几乎看不见；
        //    - 某些 ROM 不支持三方 proximityWakeLock 时，这层完全替代黑屏；
        //    - 无论哪种情况，所有触摸事件都被强制 consume，避免脸颊挂断/静音。
        androidx.compose.animation.AnimatedVisibility(
            visible = isNearEar,
            enter = androidx.compose.animation.fadeIn(tween(120)),
            exit = androidx.compose.animation.fadeOut(tween(180)),
            modifier = Modifier
                .fillMaxSize()
                .zIndex(Float.MAX_VALUE)
                .pointerInput(Unit) {
                    // 拦截所有指针事件：按下、移动、抬起全部 consume，不下传到按钮
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            )
        }
    }
}

@Composable
private fun CallControlButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "scale"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .background(
                    if (active) Color.White else Color.White.copy(alpha = 0.15f),
                    CircleShape
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (active) Color.Black else Color.White,
                modifier = Modifier.size(26.dp)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.7f)
        )
    }
}

/**
 * 通话页右上角圆形小按钮（字幕开关 / 缩小通话）。
 * 样式与底部控制按钮一致：active 时白底黑图标，按下缩放 + 触觉反馈。
 */
@Composable
private fun CallIconButton(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit
) {
    val haptics = rememberPremiumHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "call_icon_button_scale"
    )

    Surface(
        color = if (active) Color.White else Color.White.copy(alpha = 0.15f),
        shape = CircleShape,
        modifier = Modifier
            .size(44.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = false, radius = 22.dp),
                onClick = {
                    haptics.perform(HapticPattern.Pop)
                    onClick()
                }
            )
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (active) Color.Black else Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/**
 * 通话实时转写面板：完整展示本次通话的双向对话（按时间正序）。
 *
 * - 自动跟随最新消息；用户上滑离开底部即暂停跟随，出现「回到最新」胶囊；
 * - LISTENING 时末尾追加实时识别行（用户正在说的话，带闪烁省略号）；
 * - 过滤系统自动化指令触发的消息，与聊天时间线口径一致。
 */
@Composable
private fun CallTranscriptPanel(
    status: CallStatus,
    transcript: List<CallTranscriptEntry>,
    listeningText: String?,
    modifier: Modifier = Modifier
) {
    val haptics = rememberPremiumHaptics()
    val scope = rememberCoroutineScope()

    // 过滤系统自动化指令触发的 User 消息（定时任务/监控指令，对通话用户无意义）
    val visibleEntries = transcript.filterNot { entry ->
        entry.role == MessageRole.USER && entry.text.startsWith("【系统自动化指令 - 任务触发】")
    }
    // LISTENING 且有实时识别文本时，末尾追加一条"正在说"的用户行
    val isListeningNow = status == CallStatus.LISTENING && !listeningText.isNullOrBlank()
    val lastEntryText = visibleEntries.lastOrNull()?.text.orEmpty()

    val listState = rememberLazyListState()
    // 跟随状态：默认跟随最新转写；用户拖动列表即暂停（停在用户滚到的位置），
    // 拖动/惯性滚动结束回到最底部时自动恢复跟随。
    var autoFollow by remember { mutableStateOf(true) }
    // 用户手指是否停留在列表上。转写流式增长期间会持续触发自动滚动，
    // 若在手指按下阶段仍执行自动滚动，会以同优先级抢占并取消用户刚开始的拖动，
    // 表现为"第一次滑动没反应、要再滑一次才生效"。按下即暂停自动滚动可消除该竞争。
    var userTouching by remember { mutableStateOf(false) }

    // 用户开始拖动列表 → 暂停自动跟随
    LaunchedEffect(Unit) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                autoFollow = false
            }
        }
    }
    // 拖动结束（含惯性滚动）后：若列表停在最底部则恢复跟随，否则保持暂停
    LaunchedEffect(Unit) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Stop || interaction is DragInteraction.Cancel) {
                scope.launch {
                    // 等惯性滚动完全停下来再判断是否贴底
                    snapshotFlow { listState.isScrollInProgress }.first { !it }
                    if (!listState.canScrollForward) {
                        autoFollow = true
                    }
                }
            }
        }
    }

    // 面板组合且列表首次有内容时，直接跳到最新一条
    // （字幕常在通话中途开启，此时已有历史消息，不能从最早一条开始显示）
    LaunchedEffect(Unit) {
        snapshotFlow { listState.layoutInfo.totalItemsCount }
            .first { it > 0 }
        listState.scrollToLatestBottom(animate = false)
    }

    // 新消息到达或最后一条消息流式增长（AI 回复中）：跟随状态下平滑滚动到最新
    LaunchedEffect(visibleEntries.size, lastEntryText, autoFollow, userTouching) {
        if (autoFollow && !userTouching) {
            listState.scrollToLatestBottom(animate = true)
        }
    }
    // 实时识别文本增长（高频 partial）：跟随状态下瞬时跟上，避免动画反复重启的抖动
    LaunchedEffect(listeningText, autoFollow, userTouching) {
        if (autoFollow && !userTouching) {
            listState.scrollToLatestBottom(animate = false)
        }
    }

    Box(
        modifier = modifier
            .clip(AppShapes.CardMedium)
            .background(Color.White.copy(alpha = 0.12f))
    ) {
        if (visibleEntries.isEmpty() && !isListeningNow) {
            // 空态：字幕刚打开、通话内容尚未产生
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.call_subtitle_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        // 手指一接触列表就暂停自动滚动，不等 drag 超过 touch slop 才暂停，
                        // 避免流式转写触发的 animateScrollToItem 抢占并取消用户刚开始的拖动。
                        // 只观察不消费，不影响 LazyColumn 自身的滚动处理。
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            userTouching = true
                            try {
                                do {
                                    val event = awaitPointerEvent()
                                } while (event.changes.any { it.pressed })
                            } finally {
                                userTouching = false
                            }
                        }
                    },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(visibleEntries) { entry ->
                    TranscriptPanelRow(
                        role = entry.role,
                        text = entry.text,
                        showTypingDot = false
                    )
                }
                if (isListeningNow) {
                    item(key = "listening") {
                        TranscriptPanelRow(
                            role = MessageRole.USER,
                            text = listeningText.orEmpty(),
                            showTypingDot = true
                        )
                    }
                }
            }

            // 回到最新：跟随被暂停时出现
            androidx.compose.animation.AnimatedVisibility(
                visible = !autoFollow,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(200)),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Surface(
                    color = Color.White.copy(alpha = 0.25f),
                    shape = CircleShape,
                    modifier = Modifier
                        .padding(bottom = 10.dp)
                        .clickable(
                            onClick = {
                                haptics.perform(HapticPattern.Pop)
                                autoFollow = true
                                scope.launch {
                                    listState.scrollToLatestBottom(animate = true)
                                }
                            }
                        )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.call_subtitle_back_to_latest),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

/**
 * 滚动到列表最底部。scrollToItem/animateScrollToItem 停在最后一条消息的顶部，
 * 当该消息比可视区还高时补一次滚动，确保真正停在底部（canScrollForward == false）。
 */
private suspend fun LazyListState.scrollToLatestBottom(animate: Boolean) {
    val lastIndex = layoutInfo.totalItemsCount - 1
    if (lastIndex < 0) return
    if (animate) {
        animateScrollToItem(lastIndex)
    } else {
        scrollToItem(lastIndex)
    }
    if (canScrollForward) {
        scrollBy(Float.MAX_VALUE)
    }
}

/** 转写面板单行：角色图标 + 文本；识别行末尾带闪烁省略号。 */
@Composable
private fun TranscriptPanelRow(
    role: MessageRole,
    text: String,
    showTypingDot: Boolean
) {
    val isUser = role == MessageRole.USER
    val iconTint = if (isUser) CallVoiceColor else MaterialTheme.colorScheme.primary

    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = if (isUser) Icons.Rounded.Person else Icons.Rounded.AutoAwesome,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(16.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 14.sp,
                color = Color.White,
                lineHeight = 20.sp
            )
            if (showTypingDot) {
                ListeningTypingDot()
            }
        }
    }
}

/** 识别中闪烁省略号：给用户明确的"还在继续识别"信号。 */
@Composable
private fun ListeningTypingDot() {
    val infiniteTransition = rememberInfiniteTransition(label = "listening_dot")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "listening_dot_alpha"
    )
    Text(
        text = "…",
        fontSize = 18.sp,
        color = CallVoiceColor.copy(alpha = dotAlpha),
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 2.dp)
    )
}

/**
 * 应用内迷你通话窗口：通话缩小后显示，不中断语音会话。
 *
 * 显示 AI 头像、名称、通话时长；点击整体恢复完整通话页；右侧挂断按钮结束通话。
 * 由 VoiceCallManager.isCallMinimized 驱动，不独立维护通话状态。
 */
@Composable
fun MiniCallWindow(
    assistant: Assistant,
    durationMs: Long,
    onExpand: () -> Unit,
    onHangup: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = rememberPremiumHaptics()
    val minutes = (durationMs / 60000).toInt()
    val seconds = ((durationMs % 60000) / 1000).toInt()
    val durationText = "%02d:%02d".format(minutes, seconds)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = AppShapes.ButtonRounded,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    haptics.perform(HapticPattern.Pop)
                    onExpand()
                }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // AI 头像
            UIAvatar(
                value = assistant.avatar,
                name = assistant.name,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape),
                onUpdate = null
            )
            // 名称 + 时长
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = assistant.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Call,
                        contentDescription = null,
                        tint = CallVoiceColor,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = durationText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // 挂断按钮
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(CallRejectColor, CircleShape)
                    .clickable {
                        haptics.perform(HapticPattern.Thud)
                        onHangup()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.CallEnd,
                    contentDescription = stringResource(R.string.call_action_end),
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}
