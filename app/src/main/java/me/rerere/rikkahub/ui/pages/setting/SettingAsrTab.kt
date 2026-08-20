package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.asr.provider.ASRProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberAvatarShape
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AsrTab(
    vm: SettingVM,
    contentPadding: PaddingValues
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val providers = settings.asrProviders
    var editingProvider by remember { mutableStateOf<ASRProviderSetting?>(null) }
    val haptics = rememberPremiumHaptics(enabled = settings.displaySetting.enableUIHaptics)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        itemsIndexed(providers, key = { _, provider -> provider.id }) { _, provider ->
            val isSelected = settings.selectedASRProviderId == provider.id
            ASRProviderItem(
                provider = provider,
                isSelected = isSelected,
                haptics = haptics,
                onSelect = {
                    if (!isSelected) {
                        haptics.perform(HapticPattern.Pop)
                        vm.updateSettings(
                            settings.copy(selectedASRProviderId = provider.id)
                        )
                    }
                },
                onEdit = {
                    haptics.perform(HapticPattern.Pop)
                    editingProvider = provider
                }
            )
        }
    }

    editingProvider?.let { provider ->
        var localProvider by remember(provider) { mutableStateOf(provider) }
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()

        ModalBottomSheet(
            onDismissRequest = { editingProvider = null },
            sheetState = sheetState,
            sheetGesturesEnabled = false,
            dragHandle = {
                IconButton(
                    onClick = {
                        scope.launch {
                            sheetState.hide()
                            editingProvider = null
                        }
                    }
                ) {
                    Icon(Icons.Rounded.KeyboardArrowDown, null)
                }
            }
        ) {
            @Suppress("RemoveExplicitTypeArguments")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .fillMaxHeight(0.8f),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 标题栏
                val providerTypeName = when (provider) {
                    is ASRProviderSetting.SystemASR -> "System ASR"
                    is ASRProviderSetting.EvoliaASR -> "Evolia ASR"
                    is ASRProviderSetting.OnlineASR -> "Online ASR"
                    is ASRProviderSetting.LocalSenseVoiceASR -> "Local SenseVoice ASR"
                }
                Text(
                    text = providerTypeName,
                    style = MaterialTheme.typography.headlineSmall
                )

                // 配置内容区（占据剩余空间，支持滚动）
                me.rerere.rikkahub.ui.pages.setting.components.ASRProviderConfigure(
                    setting = localProvider,
                    providers = settings.providers,
                    onValueChange = { localProvider = it },
                    modifier = Modifier.weight(1f)
                )

                // 取消 / 保存行（对齐 TTS 风格）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = {
                            editingProvider = null
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.cancel))
                    }

                    TextButton(
                        onClick = {
                            vm.updateSettings(
                                settings.copy(
                                    asrProviders = settings.asrProviders.map {
                                        if (it.id == localProvider.id) localProvider else it
                                    }
                                )
                            )
                            editingProvider = null
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.chat_page_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun ASRProviderItem(
    provider: ASRProviderSetting,
    isSelected: Boolean,
    haptics: me.rerere.rikkahub.ui.hooks.PremiumHaptics,
    onSelect: () -> Unit,
    onEdit: () -> Unit
) {
    // 选中 / 未选中背景色动画（对齐 TTS）
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow
            else MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "asrSelectionBackground"
    )
    // 选中 / 未选中文字颜色动画（对齐 TTS）
    val textColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "asrTextColor"
    )
    // 选中时更大的圆角（对齐 TTS）
    val shapeRadius = if (isSelected) 100.dp else 24.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(shapeRadius))
            .background(backgroundColor)
            .clickable {
                haptics.perform(HapticPattern.Pop)
                onSelect()
            }
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Provider Icon（40dp，对齐 TTS）
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    shape = rememberAvatarShape(false)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = when (provider) {
                    is ASRProviderSetting.SystemASR -> Icons.Rounded.PhoneAndroid
                    is ASRProviderSetting.EvoliaASR -> Icons.Rounded.AutoAwesome
                    is ASRProviderSetting.OnlineASR -> Icons.Rounded.Cloud
                    is ASRProviderSetting.LocalSenseVoiceASR -> Icons.Rounded.GraphicEq
                },
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
        }

        // 名称 + 副标题
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = provider.name.ifBlank { providerDisplayName(provider) },
                style = MaterialTheme.typography.titleMedium,
                color = textColor,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = providerDisplayName(provider),
                style = MaterialTheme.typography.bodyMedium,
                color = textColor.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 选中指示 Check 图标（对齐 TTS 选中视觉）
        if (isSelected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(20.dp)
            )
        }

        // 设置按钮（用 Settings 图标，对齐 TTS）。EvoliaASR 不可编辑。
        if (provider !is ASRProviderSetting.EvoliaASR) {
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = stringResource(R.string.settings),
                    tint = textColor
                )
            }
        }
    }
}

private fun providerDisplayName(provider: ASRProviderSetting): String = when (provider) {
    is ASRProviderSetting.SystemASR -> "System ASR"
    is ASRProviderSetting.OnlineASR -> "Online ASR (Whisper API)"
    is ASRProviderSetting.EvoliaASR -> "Evolia 提供的 ASR 模型"
    is ASRProviderSetting.LocalSenseVoiceASR -> "本地 ASR (SenseVoice)"
}
