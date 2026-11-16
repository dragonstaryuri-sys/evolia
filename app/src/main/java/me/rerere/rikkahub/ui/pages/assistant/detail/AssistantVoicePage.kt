package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.core.data.model.Assistant
import me.rerere.rikkahub.data.ai.TtsVoiceResolver
import me.rerere.rikkahub.data.ai.mergeVoiceOverride
import me.rerere.rikkahub.data.ai.mimoEffectiveVoiceId
import me.rerere.rikkahub.data.ai.stripConnectionFields
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.components.ui.ToastType
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import me.rerere.rikkahub.ui.theme.AppShapes
import me.rerere.tts.provider.TTSProviderSetting
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

/** 从 Provider 配置中提取音色摘要（列表展示用）。 */
fun voiceSummary(setting: TTSProviderSetting): String = when (setting) {
    is TTSProviderSetting.OpenAI -> setting.voice
    is TTSProviderSetting.Custom -> setting.voice.ifBlank { "未设置" }
    // Mimo 特殊：voicedesign 没有音色 ID（音色由设计描述定义），voice 字段只是标准模型残留值
    is TTSProviderSetting.Mimo -> when {
        setting.model.contains("voicedesign", ignoreCase = true) -> {
            val desc = mimoEffectiveVoiceId(setting).replace(Regex("\\s+"), " ")
            "音色设计 " + if (desc.length > 16) desc.take(16) + "…" else desc
        }

        setting.model.contains("voiceclone", ignoreCase = true) ->
            "音色复刻 ${mimoEffectiveVoiceId(setting)}"

        else -> setting.voice
    }
    is TTSProviderSetting.Gemini -> setting.voiceName
    is TTSProviderSetting.SystemTTS -> setting.voiceName ?: "系统默认"
    is TTSProviderSetting.MiniMax -> when (setting.voiceType) {
        TTSProviderSetting.MiniMaxVoiceType.DESIGN -> "音色设计 ${setting.designedVoiceId.ifBlank { "(未生成)" }}"
        TTSProviderSetting.MiniMaxVoiceType.CLONE -> "音色复刻 ${setting.cloneVoiceId.ifBlank { "(未设置)" }}"
        else -> setting.voiceId
    }

    is TTSProviderSetting.ElevenLabs -> setting.voiceId
    is TTSProviderSetting.FishAudio -> setting.voiceId.ifBlank { "未设置" }
    is TTSProviderSetting.Azure -> setting.voiceName
}

/**
 * 智能体语音配置 VM：读写 Assistant.voiceProviderId + voiceSettingJson。
 */
class AssistantVoiceVM(
    private val assistantId: String,
    private val settingsStore: SettingsStore
) : ViewModel() {

    val assistant: StateFlow<Assistant?> = settingsStore.settingsFlow
        .map { settings ->
            val id = runCatching { Uuid.parse(assistantId) }.getOrNull()
            id?.let { settings.getAssistantById(it) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val globalProviders: StateFlow<List<TTSProviderSetting>> = settingsStore.settingsFlow
        .map { it.ttsProviders }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val globalDefault: StateFlow<TTSProviderSetting?> = settingsStore.settingsFlow
        .map { it.getSelectedTTSProvider() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * 保存智能体语音配置。[providerId] 为 null 时清除专属配置（回到跟随全局默认），
     * 但**保留**各 Provider 已配置的声音副本（切换回来时复用）；
     * [providerId] 非 null 时写入该 Provider 的副本并选用它。
     */
    fun saveVoice(providerId: Uuid?, settingJson: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val id = runCatching { Uuid.parse(assistantId) }.getOrNull() ?: return@launch
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map { assistant ->
                        if (assistant.id == id) {
                            val newMap = if (providerId != null && settingJson != null) {
                                assistant.voiceSettings + (providerId.toString() to settingJson)
                            } else {
                                assistant.voiceSettings
                            }
                            assistant.copy(
                                voiceProviderId = providerId,
                                voiceSettings = newMap
                            )
                        } else {
                            assistant
                        }
                    }
                )
            }
        }
    }
}

/**
 * 智能体语音配置页：
 * - 列出全局语音服务已配置的 Provider（apiKey 等连接信息自动带过来，不在此展示）；
 * - 点击任一 Provider 进入「设置声音」表单（复用编辑提供商卡片，隐藏 API 密钥/基础 URL，
 *   右上角试听），可调整音色/语速/模型等；
 * - 不配置时使用设置页选中的音色作为该智能体的默认音色。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantVoicePage(assistantId: String) {
    val vm: AssistantVoiceVM = koinViewModel(parameters = { parametersOf(assistantId) })
    val toaster = LocalToaster.current

    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val providers by vm.globalProviders.collectAsStateWithLifecycle()
    val globalDefault by vm.globalDefault.collectAsStateWithLifecycle()

    var editingProvider by remember { mutableStateOf<TTSProviderSetting?>(null) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            OneUITopAppBar(
                title = stringResource(R.string.assistant_voice_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = { BackButton() }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 提示卡
            Card(
                shape = AppShapes.CardMedium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.assistant_voice_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 跟随全局默认
            SettingsGroup(title = stringResource(R.string.assistant_voice_source)) {
                Surface(
                    onClick = { vm.saveVoice(null, null) },
                    shape = AppShapes.ListItemSmall,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = assistant?.voiceProviderId == null,
                            onClick = { vm.saveVoice(null, null) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.assistant_voice_follow_global),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (assistant?.voiceProviderId == null) {
                                    FontWeight.SemiBold
                                } else {
                                    FontWeight.Normal
                                }
                            )
                            Text(
                                text = globalDefault?.let {
                                    "${it.name} · ${voiceSummary(it)}"
                                } ?: stringResource(R.string.assistant_voice_no_global),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Provider 列表
            SettingsGroup(title = stringResource(R.string.assistant_voice_custom_section)) {
                providers.forEach { provider ->
                    val isSelected = assistant?.voiceProviderId == provider.id
                    val customSummary = assistant?.voiceProviderId?.takeIf { it == provider.id }
                        ?.let { id ->
                            TtsVoiceResolver.decodeVoiceSetting(assistant?.voiceSettings?.get(id.toString()))
                                ?.let { override ->
                                    mergeVoiceOverride(provider, override)?.let { voiceSummary(it) }
                                }
                        }
                    Surface(
                        onClick = {
                            // 编辑初始值：已配置 → 该 Provider 的副本；未配置 → 全局配置原样作为起点
                            editingProvider = if (isSelected) {
                                TtsVoiceResolver.decodeVoiceSetting(
                                    assistant?.voiceSettings?.get(provider.id.toString())
                                ) ?: provider
                            } else {
                                assistant?.voiceSettings?.get(provider.id.toString())
                                    ?.let { TtsVoiceResolver.decodeVoiceSetting(it) } ?: provider
                            }
                        },
                        shape = AppShapes.ListItemSmall,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.RecordVoiceOver,
                                contentDescription = null,
                                tint = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = provider.name.ifBlank { stringResource(R.string.assistant_voice_provider_fallback) },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                )
                                Text(
                                    text = when {
                                        isSelected && customSummary != null -> stringResource(R.string.assistant_voice_customized, customSummary)
                                        isSelected -> stringResource(R.string.assistant_voice_selected_global)
                                        else -> stringResource(R.string.assistant_voice_tap_set)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = stringResource(R.string.assistant_voice_current_cd),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 「设置声音」表单（复用编辑提供商卡片，隐藏连接字段）
    editingProvider?.let { initial ->
        val globalRef = providers.find { it.id == initial.id } ?: initial
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var editing by remember(initial.id) { mutableStateOf(initial) }
        val tts = LocalTTSState.current

        ModalBottomSheet(
            onDismissRequest = { editingProvider = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = AppShapes.BottomSheet
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.assistant_voice_sheet_title, globalRef.name.ifBlank { stringResource(R.string.assistant_voice_provider_fallback) }),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    // 右上角试听：用全局 key + 当前编辑的声音参数
                    TextButton(
                        onClick = {
                            val merged = mergeVoiceOverride(globalRef, editing) ?: editing
                            tts.speak(
                                text = "你好，我是${assistant?.name ?: "Evolia"}，这是我的声音，很高兴为你朗读这段试听。",
                                flushCalled = true,
                                overrideSetting = merged
                            )
                        }
                    ) {
                        Text(stringResource(R.string.assistant_voice_preview))
                    }
                }

                }
                item {
                me.rerere.rikkahub.ui.pages.setting.components.TTSProviderConfigure(
                    setting = editing,
                    onValueChange = { editing = it },
                    voiceOnly = true,
                    connectionSetting = globalRef
                )
                }
                item {
                val clearedText = stringResource(R.string.assistant_voice_cleared_toast)
                val savedText = stringResource(R.string.assistant_voice_saved_toast)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (assistant?.voiceProviderId == initial.id) {
                        TextButton(onClick = {
                            editingProvider = null
                            vm.saveVoice(null, null)
                            toaster.show(message = clearedText, type = ToastType.Normal)
                        }) {
                            Text(stringResource(R.string.assistant_voice_clear), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    TextButton(onClick = { editingProvider = null }) {
                        Text(stringResource(R.string.radio_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(
                        onClick = {
                            editingProvider = null
                            vm.saveVoice(
                                providerId = globalRef.id,
                                settingJson = me.rerere.rikkahub.common.JsonInstant.encodeToString(
                                    me.rerere.rikkahub.data.ai.stripConnectionFields(editing)
                                )
                            )
                            toaster.show(message = savedText, type = ToastType.Success)
                        }
                    ) {
                        Text(stringResource(R.string.radio_save))
                    }
                }
                }
            }
        }
    }
}
