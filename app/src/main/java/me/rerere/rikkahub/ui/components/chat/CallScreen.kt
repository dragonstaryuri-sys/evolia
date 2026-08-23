package me.rerere.rikkahub.ui.components.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import coil3.compose.AsyncImage
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.R
import me.rerere.rikkahub.core.data.model.Assistant
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.theme.LocalDarkMode

/**
 * 通话状态枚举
 */
enum class CallStatus {
    CONNECTING,
    LISTENING,
    THINKING,
    SPEAKING,
    DUCKING
}

@Composable
fun CallScreen(
    assistant: Assistant,
    status: CallStatus,
    isMuted: Boolean,
    isSpeakerOn: Boolean,
    latestMessageRole: MessageRole? = null,
    latestMessageText: String? = null,
    listeningText: String? = null,
    onMuteToggle: () -> Unit,
    onSpeakerToggle: () -> Unit,
    onHangup: () -> Unit,
    onInterrupt: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val haptics = rememberPremiumHaptics()

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
            CallStatus.DUCKING -> Color(0xFFFFC107).copy(alpha = 0.55f)
            else -> Color.White.copy(alpha = 0.2f)
        },
        animationSpec = tween(500),
        label = "aura_color"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 1. 背景处理：复用对话背景逻辑
        if (assistant.background != null) {
            val scrimAlpha = assistant.backgroundDim.coerceIn(0f, 0.85f)
            val scrimColor = if (LocalDarkMode.current) {
                Color.Black.copy(alpha = scrimAlpha)
            } else {
                Color.White.copy(alpha = scrimAlpha)
            }
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
            // 顶部信息
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
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
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

            // 中间：动态头像 + 最新一条消息气泡
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // 动态头像 / 多层呼吸光圈
                Box(contentAlignment = Alignment.Center) {
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

                // 最新一条对话气泡（含"正在听"时的识别文字）：
                // - LISTENING 状态且有 listeningText（ASR 实时 partial）：优先显示，角色=USER，末尾加闪烁省略号
                // - 否则：显示最近一条已落盘的消息
                val isListeningNow = status == CallStatus.LISTENING && listeningText != null
                val (displayRole, displayText, showTypingDot) = when {
                    isListeningNow -> Triple(MessageRole.USER, listeningText.orEmpty(), true)
                    else -> Triple(latestMessageRole, latestMessageText.orEmpty(), false)
                }

                // 过滤掉带有“系统”标识的 User 消息（通常是定时任务或监控触发的指令）
                val isSystemTriggered = displayRole == MessageRole.USER &&
                    (displayText.startsWith("【系统自动化指令 - 任务触发】"))

                androidx.compose.animation.AnimatedVisibility(
                    visible = displayText.isNotBlank() && !isSystemTriggered,
                    enter = fadeIn(tween(300)),
                    exit = fadeOut(tween(200)),
                    modifier = Modifier.padding(top = 36.dp, start = 24.dp, end = 24.dp)
                ) {
                    val isUser = displayRole == MessageRole.USER
                    // user 和 agent 都用同样的半透明白底，整体风格统一
                    val bubbleColor = Color.White.copy(alpha = 0.12f)
                    val textColor = Color.White
                    val iconTint = if (isUser) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
                    // 固定最大高度（≈5 行文字），气泡高度不超过这个值；文字超出时内部滚动
                    val bubbleMaxHeight = 110.dp

                    // 自动滚动逻辑：记录滚动状态并监听文字或最大滚动值变化
                    val scrollState = rememberScrollState()
                    LaunchedEffect(displayText, scrollState.maxValue) {
                        scrollState.animateScrollTo(scrollState.maxValue)
                    }

                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .heightIn(max = bubbleMaxHeight)
                            .background(bubbleColor, RoundedCornerShape(20.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            imageVector = if (isUser) Icons.Rounded.Person else Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier
                                .size(18.dp)
                                .padding(top = 2.dp)
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(scrollState)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = displayText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontSize = 14.sp,
                                    color = textColor,
                                    textAlign = TextAlign.Start,
                                    lineHeight = 20.sp
                                )
                                if (showTypingDot) {
                                    // 识别中闪烁省略号：给用户明确的"还在继续识别"信号
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
                                        color = Color(0xFF4CAF50).copy(alpha = dotAlpha),
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(start = 2.dp)
                                    )
                                }
                            }
                        }
                    }
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
                        .background(Color(0xFFE53935), CircleShape)
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

                // 扬声器按钮
                CallControlButton(
                    icon = if (isSpeakerOn) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeDown,
                    label = stringResource(R.string.call_action_speaker),
                    active = isSpeakerOn,
                    onClick = {
                        haptics.perform(HapticPattern.Pop)
                        onSpeakerToggle()
                    }
                )
            }
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
