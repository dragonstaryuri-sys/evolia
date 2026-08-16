package me.rerere.rikkahub.ui.pages.setting.components

import android.net.Uri
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.OutlinedNumberInput
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.tts.model.TTSVoice
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.provider.providers.MiniMaxSimpleVoice
import me.rerere.tts.provider.providers.MimoTTSProvider

private const val TAG = "TTSProviderConfigure"

@Composable
fun TTSProviderConfigure(
    setting: TTSProviderSetting,
    modifier: Modifier = Modifier,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.verticalScroll(rememberScrollState())
    ) {
        // Name
        FormItem(
            label = { Text(stringResource(R.string.setting_tts_page_name)) },
            description = { Text(stringResource(R.string.setting_tts_page_name_description)) }
        ) {
            OutlinedTextField(
                value = setting.name,
                onValueChange = { newName ->
                    onValueChange(setting.copyProvider(name = newName))
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.setting_tts_page_name_placeholder)) }
            )
        }

        // Provider-specific fields
        when (setting) {
            is TTSProviderSetting.OpenAI -> OpenAITTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.Mimo -> MimoTTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.Custom -> CustomTTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.Gemini -> GeminiTTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.MiniMax -> MiniMaxTTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.ElevenLabs -> ElevenLabsTTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.SystemTTS -> SystemTTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.Azure -> AzureTTSConfiguration(setting, onValueChange)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MimoTTSConfiguration(
    setting: TTSProviderSetting.Mimo,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    val tts = LocalTTSState.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 自动修正预设地址
    LaunchedEffect(setting.id) {
        if (setting.baseUrl != "https://api.xiaomimimo.com/v1/chat/completions") {
            onValueChange(setting.copy(baseUrl = "https://api.xiaomimimo.com/v1/chat/completions"))
        }
    }

    // ---- 本地状态 + 防抖回写 (参考 MiniMax/Azure 模式) ----
    var localApiKey by remember(setting.apiKey) { mutableStateOf(setting.apiKey) }
    var localModel by remember(setting.model) { mutableStateOf(setting.model) }
    var localVoice by remember(setting.voice) { mutableStateOf(setting.voice) }
    var localSpeed by remember(setting.speed) { mutableStateOf(setting.speed) }
    var localVoiceDesignPrompt by remember(setting.voiceDesignPrompt) { mutableStateOf(setting.voiceDesignPrompt) }
    var localOptimizeTextPreview by remember(setting.optimizeTextPreview) { mutableStateOf(setting.optimizeTextPreview) }
    var localReferenceAudioBase64 by remember(setting.referenceAudioBase64) { mutableStateOf(setting.referenceAudioBase64) }
    var localReferenceAudioFileName by remember(setting.referenceAudioFileName) { mutableStateOf(setting.referenceAudioFileName) }
    var localReferenceAudioFormat by remember(setting.referenceAudioFormat) { mutableStateOf(setting.referenceAudioFormat) }

    // 防抖 500ms 后统一回写
    LaunchedEffect(
        localApiKey, localModel, localVoice, localSpeed,
        localVoiceDesignPrompt, localOptimizeTextPreview,
        localReferenceAudioBase64, localReferenceAudioFileName, localReferenceAudioFormat
    ) {
        delay(500)
        val changed = localApiKey != setting.apiKey ||
            localModel != setting.model ||
            localVoice != setting.voice ||
            localSpeed != setting.speed ||
            localVoiceDesignPrompt != setting.voiceDesignPrompt ||
            localOptimizeTextPreview != setting.optimizeTextPreview ||
            localReferenceAudioBase64 != setting.referenceAudioBase64 ||
            localReferenceAudioFileName != setting.referenceAudioFileName ||
            localReferenceAudioFormat != setting.referenceAudioFormat
        if (changed) {
            onValueChange(
                setting.copy(
                    apiKey = localApiKey,
                    model = localModel,
                    voice = localVoice,
                    speed = localSpeed,
                    voiceDesignPrompt = localVoiceDesignPrompt,
                    optimizeTextPreview = localOptimizeTextPreview,
                    referenceAudioBase64 = localReferenceAudioBase64,
                    referenceAudioFileName = localReferenceAudioFileName,
                    referenceAudioFormat = localReferenceAudioFormat
                )
            )
        }
    }

    // ---- 模型相关 ----
    var modelExpanded by remember { mutableStateOf(false) }
    var fetchedModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var isFetchingModels by remember { mutableStateOf(false) }
    val presetModels = remember { MimoTTSProvider().presetModels }

    // 从官方拉取模型列表
    fun fetchModelsFromApi() {
        if (localApiKey.isBlank()) return
        scope.launch {
            isFetchingModels = true
            try {
                val tempSetting = setting.copy(apiKey = localApiKey)
                val models = tts.listMimoModels(tempSetting)
                // 暂时过滤掉 voiceclone 模型，存在 bug
                fetchedModels = models.filter {
                    it.contains("tts", ignoreCase = true)
                        && !it.contains("voiceclone", ignoreCase = true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Mimo fetch models failed", e)
            } finally {
                isFetchingModels = false
            }
        }
    }

    // ---- 模型类型判断 ----
    val isVoiceDesignModel = localModel.contains("voicedesign", ignoreCase = true)
    val isVoiceCloneModel = localModel.contains("voiceclone", ignoreCase = true)
    val isStandardTTSModel = !isVoiceDesignModel && !isVoiceCloneModel

    // ---- 音色复刻：音频文件选择 ----
    var isConvertingAudio by remember { mutableStateOf(false) }
    // MiMo voiceclone 官方支持: mp3 / flac / m4a / wav / ogg
    val supportedAudioFormats = setOf("mp3", "flac", "m4a", "wav", "ogg")
    var audioFormatError by remember { mutableStateOf<String?>(null) }
    val audioPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isConvertingAudio = true
            audioFormatError = null
            try {
                val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameIndex != -1) cursor.getString(nameIndex) else null
                } ?: uri.lastPathSegment ?: "audio"

                // 推断格式
                val format = fileName.substringAfterLast('.', "").lowercase().takeIf { it.isNotBlank() }
                    ?: context.contentResolver.getType(uri)?.substringAfterLast('/') ?: ""

                // MiMo 音色复刻仅支持 mp3/flac/m4a/wav/ogg
                if (format.isNotBlank() && format !in supportedAudioFormats) {
                    audioFormatError = "MiMo 官方音色复刻仅支持 MP3 / FLAC / M4A / WAV / OGG 格式，当前文件格式为 \"$format\"，请先转换后再上传。"
                    return@launch
                }

                val base64 = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readBytes()
                        Base64.encodeToString(bytes, Base64.NO_WRAP)
                    }
                }
                if (base64 != null) {
                    localReferenceAudioBase64 = base64
                    localReferenceAudioFileName = fileName
                    localReferenceAudioFormat = format.ifBlank { "wav" }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read reference audio", e)
                audioFormatError = "读取音频失败: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                isConvertingAudio = false
            }
        }
    }

    // ---- UI ----
    var apiKeyVisible by remember { mutableStateOf(false) }

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text("小米 MiMo API Key") }
    ) {
        OutlinedTextField(
            value = localApiKey,
            onValueChange = { localApiKey = it.trim() },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if (!it.isFocused) apiKeyVisible = false },
            placeholder = { Text("MIMO_API_KEY") },
            visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                    Icon(
                        imageVector = if (apiKeyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = "Toggle Visibility"
                    )
                }
            }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        description = { Text("接口地址 (预设地址，不可编辑)") }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = {},
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.xiaomimimo.com/v1/chat/completions") },
            enabled = false
        )
    }

    // ---- 模型选择器：预设 + 官方获取 + 手动输入 ----
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_model)) },
        description = {
            Text(
                when {
                    isVoiceDesignModel -> "音色设计：通过文本描述定制音色"
                    isVoiceCloneModel -> "音色复刻：基于音频样本复刻任意音色"
                    else -> "标准 TTS：使用预置精品音色"
                }
            )
        }
    ) {
        Column {
            ExposedDropdownMenuBox(
                expanded = modelExpanded,
                onExpandedChange = { modelExpanded = !modelExpanded }
            ) {
                OutlinedTextField(
                    value = localModel,
                    onValueChange = { localModel = it.trim() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                    placeholder = { Text("mimo-v2.5-tts") },
                    trailingIcon = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            if (isFetchingModels) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.size(4.dp))
                            }
                            IconButton(
                                onClick = { fetchModelsFromApi() },
                                enabled = localApiKey.isNotBlank() && !isFetchingModels
                            ) {
                                Icon(
                                    Icons.Rounded.Refresh,
                                    contentDescription = "从官方获取 TTS 模型"
                                )
                            }
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded)
                        }
                    }
                )
                ExposedDropdownMenu(
                    expanded = modelExpanded,
                    onDismissRequest = { modelExpanded = false }
                ) {
                    // 预设模型
                    if (presetModels.isNotEmpty()) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "— 预设模型 —",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            onClick = {}
                        )
                        presetModels.forEach { model ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(model)
                                        Text(
                                            text = when (model) {
                                                "mimo-v2.5-tts" -> "标准 TTS · 预置精品音色"
                                                "mimo-v2.5-tts-voicedesign" -> "音色设计 · 文本描述定制音色"
                                                "mimo-v2.5-tts-voiceclone" -> "音色复刻 · 音频样本复刻音色"
                                                else -> ""
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    modelExpanded = false
                                    localModel = model
                                }
                            )
                        }
                    }
                    // API 拉取到的模型
                    if (fetchedModels.isNotEmpty()) {
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "— 官方模型 (${fetchedModels.size}) —",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            onClick = {}
                        )
                        fetchedModels.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model) },
                                onClick = {
                                    modelExpanded = false
                                    localModel = model
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // ========== 按模型类型显示不同参数 ==========

    // ---- 1. 标准 TTS (mimo-v2.5-tts): 预置音色选择 ----
    if (isStandardTTSModel) {
        var voiceExpanded by remember { mutableStateOf(false) }
        val voices = remember {
            listOf("mimo_default", "冰糖", "茉莉", "苏打", "白桦", "Mia", "Chloe", "Milo", "Dean")
        }

        FormItem(
            label = { Text(stringResource(R.string.setting_tts_page_voice)) },
            description = { Text("预置音色，仅标准 TTS 模型可用") }
        ) {
            ExposedDropdownMenuBox(
                expanded = voiceExpanded,
                onExpandedChange = { voiceExpanded = !voiceExpanded }
            ) {
                OutlinedTextField(
                    value = localVoice,
                    onValueChange = { localVoice = it.trim() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                    placeholder = { Text("Dean / 冰糖 / mimo_default") },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceExpanded)
                    }
                )
                ExposedDropdownMenu(
                    expanded = voiceExpanded,
                    onDismissRequest = { voiceExpanded = false }
                ) {
                    voices.forEach { voice ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(voice)
                                    Text(
                                        text = when (voice) {
                                            "mimo_default" -> "默认音色 (依集群)"
                                            "冰糖", "茉莉" -> "中文 · 女声"
                                            "苏打", "白桦" -> "中文 · 男声"
                                            "Mia", "Chloe" -> "英文 · 女声"
                                            "Milo", "Dean" -> "英文 · 男声"
                                            else -> ""
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                voiceExpanded = false
                                localVoice = voice
                            }
                        )
                    }
                }
            }
        }
    }

    // ---- 2. 音色设计 (voicedesign): 描述文本 + 润色开关 ----
    if (isVoiceDesignModel) {
        FormItem(
            label = { Text("音色设计描述") },
            description = {
                Text(
                    "用自然语言描述你想要的音色，越具体效果越好。必填参数，将放在 user 消息中。",
                    color = MaterialTheme.colorScheme.error
                )
            }
        ) {
            OutlinedTextField(
                value = localVoiceDesignPrompt,
                onValueChange = { localVoiceDesignPrompt = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
                placeholder = {
                    Text(
                        "例如：\n" +
                            "Young female, ASMR close-up feel, audible breathing, very slow and relaxing.\n" +
                            "或：年迈的老先生，北方口音，语速缓慢，嗓音沙哑沧桑，像在讲故事。"
                    )
                },
                maxLines = 8
            )
        }

        FormItem(
            label = { Text("智能润色目标文本") },
            description = { Text("开启后可省略 assistant 消息，让模型自动生成匹配音色的播报文本 (optimize_text_preview)") }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Switch(
                    checked = localOptimizeTextPreview,
                    onCheckedChange = { localOptimizeTextPreview = it }
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    if (localOptimizeTextPreview) "已开启润色" else "使用原始合成文本",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }

    // ---- 3. 音色复刻 (voiceclone): 上传参考音频 ----
    if (isVoiceCloneModel) {
        FormItem(
            label = { Text("参考音频") },
            description = {
                Column {
                    Text(
                        text = if (localReferenceAudioBase64.isBlank()) {
                            "支持 MP3 / FLAC / M4A / WAV / OGG 格式。选择一段 5-60 秒的清晰人声音频，建议文件 < 1MB。"
                        } else {
                            "已加载: $localReferenceAudioFileName" +
                                (if (localReferenceAudioFormat.isNotBlank()) " · $localReferenceAudioFormat" else "") +
                                " · ${localReferenceAudioBase64.length * 3 / 4 / 1024} KB"
                        },
                        color = if (localReferenceAudioBase64.isBlank())
                            MaterialTheme.colorScheme.error else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "⚠️ 429 限流排查：音色克隆资源消耗较大，如遇 \"Too many requests\"，请等待 1-2 分钟后再试；使用更短的参考音频、避免连续请求。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    if (audioFormatError != null) {
                        Text(
                            text = "❌ $audioFormatError",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.OutlinedButton(
                        onClick = { audioPickerLauncher.launch("audio/*") },
                        enabled = !isConvertingAudio
                    ) {
                        if (isConvertingAudio) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.size(8.dp))
                            Text("处理中…")
                        } else {
                            Icon(Icons.Rounded.Upload, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(if (localReferenceAudioBase64.isBlank()) "选择音频文件" else "更换音频")
                        }
                    }

                    if (localReferenceAudioBase64.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                localReferenceAudioBase64 = ""
                                localReferenceAudioFileName = ""
                                localReferenceAudioFormat = ""
                            }
                        ) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "移除参考音频",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Rounded.Audiotrack,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (localReferenceAudioFormat.isNotBlank()) {
                    OutlinedTextField(
                        value = localReferenceAudioFormat,
                        onValueChange = { localReferenceAudioFormat = it.trim().lowercase() },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("音频格式 (mp3/flac/m4a/wav/ogg)") },
                        placeholder = { Text("mp3 / flac / m4a / wav / ogg") },
                        isError = localReferenceAudioFormat.isNotBlank() && localReferenceAudioFormat !in supportedAudioFormats,
                        supportingText = {
                            if (localReferenceAudioFormat.isNotBlank() && localReferenceAudioFormat !in supportedAudioFormats) {
                                Text("MiMo 音色复刻仅支持 mp3/flac/m4a/wav/ogg，当前填写的格式会导致官方接口报错。", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        singleLine = true
                    )
                }
            }
        }
    }

    // ---- 语速 (MiMo 走客户端 ExoPlayer 播放速度调整) ----
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_speed)) },
        description = {
            Text(
                "MiMo 官方 API 未开放服务端语速参数，语速将通过系统播放器在播放时调整（保留音高）。\n" +
                    "范围 0.25 ~ 4.0，1.0 为原始速度。"
            )
        }
    ) {
        OutlinedNumberInput(
            value = localSpeed,
            onValueChange = { newSpeed ->
                if (newSpeed in 0.25f..4.0f) {
                    localSpeed = newSpeed
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.setting_tts_page_speed)
        )
    }
}

@Composable
private fun CustomTTSConfiguration(
    setting: TTSProviderSetting.Custom,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    var apiKeyVisible by remember { mutableStateOf(false) }

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if (!it.isFocused) apiKeyVisible = false },
            placeholder = { Text(stringResource(R.string.setting_tts_page_api_key_placeholder_openai)) },
            visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                    Icon(
                        imageVector = if (apiKeyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = "Toggle Visibility"
                    )
                }
            }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        description = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_base_url_placeholder)) }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_model)) },
        description = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_model_placeholder_openai)) }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice)) },
        description = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        OutlinedTextField(
            value = setting.voice,
            onValueChange = { newVoice ->
                onValueChange(setting.copy(voice = newVoice))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("alloy") }
        )
    }
}

@Composable
private fun AzureTTSConfiguration(
    setting: TTSProviderSetting.Azure,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    val tts = LocalTTSState.current
    var voices by remember { mutableStateOf<List<TTSVoice>>(emptyList()) }
    var isLoadingVoices by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }

    var localApiKey by remember(setting.apiKey) { mutableStateOf(setting.apiKey) }
    var localRegion by remember(setting.region) { mutableStateOf(setting.region) }
    var localVoiceName by remember(setting.voiceName) { mutableStateOf(setting.voiceName) }
    var localStyle by remember(setting.style) { mutableStateOf(setting.style) }
    var localSpeed by remember(setting.speed) { mutableStateOf(setting.speed) }

    val currentVoice = remember(localVoiceName, voices) {
        voices.find { it.id == localVoiceName }
    }
    val supportedStyles = remember(currentVoice) {
        val list = mutableListOf("general")
        currentVoice?.styles?.let { list.addAll(it) }
        list.distinct()
    }

    LaunchedEffect(localApiKey, localRegion, localVoiceName, localStyle, localSpeed) {
        if (localApiKey != setting.apiKey || localRegion != setting.region ||
            localVoiceName != setting.voiceName || localStyle != setting.style ||
            localSpeed != setting.speed) {
            delay(500)
            onValueChange(setting.copy(
                apiKey = localApiKey,
                region = localRegion,
                voiceName = localVoiceName,
                style = localStyle,
                speed = localSpeed
            ))
        }
    }

    LaunchedEffect(localApiKey, localRegion) {
        if (localApiKey.isNotBlank() && localRegion.isNotBlank()) {
            isLoadingVoices = true
            fetchError = null
            try {
                voices = tts.getVoices(setting.copy(apiKey = localApiKey, region = localRegion))
            } catch (e: Exception) {
                Log.e(TAG, "Azure: Fetch voices failed", e)
                fetchError = e.localizedMessage
            } finally {
                isLoadingVoices = false
            }
        }
    }

    var apiKeyVisible by remember { mutableStateOf(false) }
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text(stringResource(R.string.setting_tts_page_api_key_description_azure)) }
    ) {
        OutlinedTextField(
            value = localApiKey,
            onValueChange = { localApiKey = it.trim() },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if (!it.isFocused) apiKeyVisible = false },
            placeholder = { Text(stringResource(R.string.setting_tts_page_api_key_placeholder_azure)) },
            visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                    Icon(
                        imageVector = if (apiKeyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = "Toggle Visibility"
                    )
                }
            }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_region)) },
        description = { Text(stringResource(R.string.setting_tts_page_region_description)) }
    ) {
        OutlinedTextField(
            value = localRegion,
            onValueChange = { localRegion = it.trim() },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("eastus") }
        )
    }

    var showVoicePicker by remember { mutableStateOf(false) }
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice_name)) },
        description = {
            if (fetchError != null) {
                Text(text = "Error: $fetchError", color = MaterialTheme.colorScheme.error)
            } else {
                Text(stringResource(R.string.setting_tts_page_voice_name_description))
            }
        }
    ) {
        OutlinedTextField(
            value = localVoiceName,
            onValueChange = { localVoiceName = it },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
                    if (isLoadingVoices) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    }
                    IconButton(onClick = { showVoicePicker = true }) {
                        Icon(Icons.AutoMirrored.Rounded.List, contentDescription = "Select Voice")
                    }
                }
            },
            placeholder = { Text("zh-CN-XiaoxiaoNeural") }
        )
    }

    var showStylePicker by remember { mutableStateOf(false) }
    val hasStyles = supportedStyles.size > 1
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_emotion)) },
        description = {
            if (!hasStyles && currentVoice != null) {
                Text(stringResource(R.string.setting_tts_page_azure_style_no_styles), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(stringResource(R.string.setting_tts_page_emotion_description))
            }
        }
    ) {
        OutlinedTextField(
            value = if (hasStyles) localStyle else "general",
            onValueChange = { if (hasStyles) localStyle = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = hasStyles,
            trailingIcon = {
                if (hasStyles) {
                    IconButton(onClick = { showStylePicker = true }) {
                        Icon(Icons.AutoMirrored.Rounded.List, contentDescription = "Select Style")
                    }
                }
            },
            placeholder = { Text("general") }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_speed)) },
        description = { Text(stringResource(R.string.setting_tts_page_speed_description)) }
    ) {
        OutlinedNumberInput(
            value = localSpeed,
            onValueChange = { newSpeed ->
                if (newSpeed in 0.5f..2.0f) {
                    localSpeed = newSpeed
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.setting_tts_page_speed)
        )
    }

    if (showVoicePicker) {
        AzureVoicePicker(
            voices = voices,
            currentVoiceId = localVoiceName,
            onSelect = {
                localVoiceName = it.id
                localStyle = "general"
                showVoicePicker = false
                onValueChange(setting.copy(voiceName = it.id, style = "general"))
            },
            onDismiss = { showVoicePicker = false }
        )
    }

    if (showStylePicker) {
        AzureStylePicker(
            currentStyle = localStyle,
            supportedStyles = supportedStyles,
            onSelect = {
                localStyle = it
                showStylePicker = false
                onValueChange(setting.copy(style = it))
            },
            onDismiss = { showStylePicker = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MiniMaxTTSConfiguration(
    setting: TTSProviderSetting.MiniMax,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    val tts = LocalTTSState.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var voices by remember { mutableStateOf<List<TTSVoice>>(emptyList()) }
    var isLoadingVoices by remember { mutableStateOf(false) }
    // 用户已有的音色设计(voice_generation) / 音色复刻(voice_cloning) 列表
    var existingDesignVoices by remember { mutableStateOf(emptyList<MiniMaxSimpleVoice>()) }
    var existingCloneVoices by remember { mutableStateOf(emptyList<MiniMaxSimpleVoice>()) }
    var isLoadingExistingVoices by remember { mutableStateOf(false) }

    // 自动修正预设地址
    LaunchedEffect(setting.id) {
        if (setting.baseUrl != "https://api.minimaxi.com/v1") {
            onValueChange(setting.copy(baseUrl = "https://api.minimaxi.com/v1"))
        }
    }

    // ====== 本地状态 + 防抖回写 ======
    var localApiKey by remember(setting.apiKey) { mutableStateOf(setting.apiKey) }
    var localBaseUrl by remember(setting.baseUrl) { mutableStateOf(setting.baseUrl) }
    var localGroupId by remember(setting.groupId) { mutableStateOf(setting.groupId) }
    var localModel by remember(setting.model) { mutableStateOf(setting.model) }
    var miniMaxModelExpanded by remember { mutableStateOf(false) }
    val miniMaxPresetModels = remember {
        // 官方当前提供的标准 TTS 模型（按版本从新到旧排序）+ 常见预览模型
        listOf(
            "speech-2.8-hd",
            "speech-2.8-turbo",
            "speech-2.6-hd",
            "speech-2.6-turbo",
            "speech-02-hd",
            "speech-02-turbo",
            "speech-01-hd",
            "speech-01-turbo",
            "speech-2.5-hd-preview"
        )
    }
    var localVoiceType by remember(setting.voiceType) { mutableStateOf(setting.voiceType) }
    // 预置音色
    var localVoiceId by remember(setting.voiceId) { mutableStateOf(setting.voiceId) }
    var localEmotion by remember(setting.emotion) { mutableStateOf(setting.emotion) }
    var localSpeed by remember(setting.speed) { mutableStateOf(setting.speed) }
    // 音色设计（designPreviewText 用内置默认值，不让用户填）
    var localDesignPrompt by remember(setting.designPrompt) { mutableStateOf(setting.designPrompt) }
    var localDesignedVoiceId by remember(setting.designedVoiceId) { mutableStateOf(setting.designedVoiceId) }
    // 音色复刻（试听参数 previewModel/previewText 直接不传，不生成 demo_audio）
    var localCloneVoiceId by remember(setting.cloneVoiceId) { mutableStateOf(setting.cloneVoiceId) }
    var localCloneFileId by remember(setting.cloneFileId) { mutableStateOf(setting.cloneFileId) }
    var localCloneAudioFileName by remember(setting.cloneAudioFileName) { mutableStateOf(setting.cloneAudioFileName) }
    var localClonePromptAudioFileId by remember(setting.clonePromptAudioFileId) { mutableStateOf(setting.clonePromptAudioFileId) }
    var localClonePromptAudioFileName by remember(setting.clonePromptAudioFileName) { mutableStateOf(setting.clonePromptAudioFileName) }
    var localClonePromptText by remember(setting.clonePromptText) { mutableStateOf(setting.clonePromptText) }
    var localCloneNoiseReduction by remember(setting.cloneNeedNoiseReduction) { mutableStateOf(setting.cloneNeedNoiseReduction) }
    var localCloneVolumeNorm by remember(setting.cloneNeedVolumeNormalization) { mutableStateOf(setting.cloneNeedVolumeNormalization) }

    val currentVoice = remember(localVoiceId, voices) { voices.find { it.id == localVoiceId } }
    // 预置音色分支：跟随音色自带的 styles；音色设计 / 音色复刻分支：使用 speech-2.x 通用的 7 种情感
    val supportedStyles = remember(currentVoice, localVoiceType) {
        if (localVoiceType == TTSProviderSetting.MiniMaxVoiceType.DEFAULT) {
            currentVoice?.styles ?: listOf("calm")
        } else {
            listOf("calm", "happy", "sad", "angry", "fearful", "disgusted", "surprised")
        }
    }

    // 防抖回写 500ms
    LaunchedEffect(
        localApiKey, localBaseUrl, localGroupId, localModel, localVoiceType,
        localVoiceId, localEmotion, localSpeed,
        localDesignPrompt, localDesignedVoiceId,
        localCloneVoiceId, localCloneFileId, localCloneAudioFileName,
        localClonePromptAudioFileId, localClonePromptAudioFileName, localClonePromptText,
        localCloneNoiseReduction, localCloneVolumeNorm
    ) {
        delay(500)
        val changed =
            localApiKey != setting.apiKey ||
                localBaseUrl != setting.baseUrl ||
                localGroupId != setting.groupId ||
                localModel != setting.model ||
                localVoiceType != setting.voiceType ||
                localVoiceId != setting.voiceId ||
                localEmotion != setting.emotion ||
                localSpeed != setting.speed ||
                localDesignPrompt != setting.designPrompt ||
                localDesignedVoiceId != setting.designedVoiceId ||
                localCloneVoiceId != setting.cloneVoiceId ||
                localCloneFileId != setting.cloneFileId ||
                localCloneAudioFileName != setting.cloneAudioFileName ||
                localClonePromptAudioFileId != setting.clonePromptAudioFileId ||
                localClonePromptAudioFileName != setting.clonePromptAudioFileName ||
                localClonePromptText != setting.clonePromptText ||
                localCloneNoiseReduction != setting.cloneNeedNoiseReduction ||
                localCloneVolumeNorm != setting.cloneNeedVolumeNormalization
        if (changed) {
            onValueChange(
                setting.copy(
                    apiKey = localApiKey,
                    baseUrl = localBaseUrl,
                    groupId = localGroupId,
                    model = localModel,
                    voiceType = localVoiceType,
                    voiceId = localVoiceId,
                    emotion = localEmotion,
                    speed = localSpeed,
                    designPrompt = localDesignPrompt,
                    designedVoiceId = localDesignedVoiceId,
                    cloneVoiceId = localCloneVoiceId,
                    cloneFileId = localCloneFileId,
                    cloneAudioFileName = localCloneAudioFileName,
                    clonePromptAudioFileId = localClonePromptAudioFileId,
                    clonePromptAudioFileName = localClonePromptAudioFileName,
                    clonePromptText = localClonePromptText,
                    cloneNeedNoiseReduction = localCloneNoiseReduction,
                    cloneNeedVolumeNormalization = localCloneVolumeNorm
                )
            )
        }
    }

    // 加载预置音色列表 + 用户已有音色（design/clone）
    LaunchedEffect(localApiKey) {
        if (localApiKey.isNotBlank()) {
            isLoadingVoices = true
            isLoadingExistingVoices = true
            try {
                val temp = setting.copy(apiKey = localApiKey, groupId = localGroupId)
                voices = tts.getVoices(temp)
                existingDesignVoices = tts.miniMaxListVoiceGeneration(temp)
                existingCloneVoices = tts.miniMaxListVoiceCloning(temp)
                Log.i(TAG, "MiniMax: 在线音色加载完成: system=${voices.size}, design=${existingDesignVoices.size}, clone=${existingCloneVoices.size}")
            } catch (e: Exception) {
                Log.e(TAG, "MiniMax: Fetch voices failed", e)
            } finally {
                isLoadingVoices = false
                isLoadingExistingVoices = false
            }
        }
    }

    // ====== 加载 & 错误状态 ======
    var isGenerating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // ====== 音频上传 (音色复刻) ======
    var uploadingPurpose by remember { mutableStateOf<String?>(null) } // voice_clone / prompt_audio
    val audioPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val purpose = uploadingPurpose ?: return@rememberLauncherForActivityResult
        uploadingPurpose = null
        scope.launch {
            isGenerating = true
            errorMessage = null
            try {
                // 1. 读取文件名 & 大小
                val displayName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        val name = if (nameIdx != -1) cursor.getString(nameIdx) else null
                        val size = if (sizeIdx != -1) cursor.getLong(sizeIdx) else 0L
                        if (purpose == "voice_clone" && size > 20 * 1024 * 1024) {
                            throw Exception("复刻音频不能超过 20MB（当前 ${size / 1024 / 1024}MB）")
                        }
                        name
                    } else null
                } ?: uri.lastPathSegment ?: "audio"

                // 2. 拷贝到缓存文件
                val cacheFile = withContext(Dispatchers.IO) {
                    val ext = displayName.substringAfterLast('.', "mp3")
                    val f = java.io.File(
                        context.cacheDir,
                        "minimax_${purpose}_${System.currentTimeMillis()}.$ext"
                    )
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        java.io.FileOutputStream(f).use { out -> input.copyTo(out) }
                    }
                    f
                }

                // 3. 上传到 MiniMax
                val tempSetting = setting.copy(
                    apiKey = localApiKey,
                    groupId = localGroupId
                )
                val fileId = tts.miniMaxUploadFile(tempSetting, cacheFile, purpose)

                // 4. 写入本地状态
                when (purpose) {
                    "voice_clone" -> {
                        localCloneFileId = fileId
                        localCloneAudioFileName = displayName
                    }
                    "prompt_audio" -> {
                        localClonePromptAudioFileId = fileId
                        localClonePromptAudioFileName = displayName
                    }
                }

                // 5. 清理缓存
                cacheFile.delete()
                errorMessage = null
            } catch (e: Exception) {
                Log.e(TAG, "MiniMax audio upload failed", e)
                errorMessage = e.localizedMessage ?: "上传失败"
            } finally {
                isGenerating = false
            }
        }
    }

    // ====== 通用 API Key UI ======
    var apiKeyVisible by remember { mutableStateOf(false) }
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = localApiKey,
            onValueChange = { localApiKey = it.trim() },
            modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) apiKeyVisible = false },
            visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                    Icon(imageVector = if (apiKeyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = null)
                }
            }
        )
    }

    FormItem(
        label = { Text("Group ID") },
        description = { Text("（可选）MiniMax 账户的 GroupId，部分账户需要。没有可留空。") }
    ) {
        OutlinedTextField(
            value = localGroupId,
            onValueChange = { localGroupId = it.trim() },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("留空或填写您的 GroupId") }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        description = { Text("接口地址 (预设地址，不可编辑)") }
    ) {
        OutlinedTextField(
            value = localBaseUrl,
            onValueChange = {},
            modifier = Modifier.fillMaxWidth(),
            enabled = false
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_model)) },
        description = { Text("用于语音合成的模型。可从下拉选择预置模型，也支持自行输入官方后续新增的模型名。") }
    ) {
        ExposedDropdownMenuBox(
            expanded = miniMaxModelExpanded,
            onExpandedChange = { miniMaxModelExpanded = !miniMaxModelExpanded }
        ) {
            OutlinedTextField(
                value = localModel,
                onValueChange = { localModel = it.trim() },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                placeholder = { Text("例如：speech-02-hd") },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = miniMaxModelExpanded)
                },
                shape = MaterialTheme.shapes.medium
            )
            ExposedDropdownMenu(
                expanded = miniMaxModelExpanded,
                onDismissRequest = { miniMaxModelExpanded = false }
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            "— 官方预置模型 —",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    onClick = {}
                )
                miniMaxPresetModels.forEach { model ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = model,
                                fontWeight = if (model == localModel) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        onClick = {
                            localModel = model
                            miniMaxModelExpanded = false
                        },
                        leadingIcon = {
                            if (model == localModel) {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    )
                }
            }
        }
    }

    // ====== 音色来源类型选择 ======
    FormItem(
        label = { Text("音色来源") },
        description = {
            Text(
                when (localVoiceType) {
                    TTSProviderSetting.MiniMaxVoiceType.DEFAULT -> "使用系统预置精品音色，立即可用。"
                    TTSProviderSetting.MiniMaxVoiceType.DESIGN -> "用自然语言描述你想要的音色，AI 为你生成个性化 voice_id。"
                    TTSProviderSetting.MiniMaxVoiceType.CLONE -> "上传 10 秒~5 分钟的清晰音频，快速复刻目标音色。"
                }
            )
        }
    ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = localVoiceType == TTSProviderSetting.MiniMaxVoiceType.DEFAULT,
                onClick = { localVoiceType = TTSProviderSetting.MiniMaxVoiceType.DEFAULT },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
            ) {
                Text("预置音色", style = MaterialTheme.typography.labelSmall)
            }
            SegmentedButton(
                selected = localVoiceType == TTSProviderSetting.MiniMaxVoiceType.DESIGN,
                onClick = { localVoiceType = TTSProviderSetting.MiniMaxVoiceType.DESIGN },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
            ) {
                Text("音色设计", style = MaterialTheme.typography.labelSmall)
            }
            SegmentedButton(
                selected = localVoiceType == TTSProviderSetting.MiniMaxVoiceType.CLONE,
                onClick = { localVoiceType = TTSProviderSetting.MiniMaxVoiceType.CLONE },
                shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
            ) {
                Text("音色复刻", style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    // ====== 错误提示 ======
    errorMessage?.let { err ->
        FormItem(
            label = { Text("操作提示", color = MaterialTheme.colorScheme.error) },
            description = { Text(err, color = MaterialTheme.colorScheme.error) }
        ) {}
    }

    // ============================================================================
    // 分支 A：预置音色
    // ============================================================================
    if (localVoiceType == TTSProviderSetting.MiniMaxVoiceType.DEFAULT) {
        var showVoicePicker by remember { mutableStateOf(false) }
        FormItem(label = { Text(stringResource(R.string.setting_tts_page_voice_id)) }) {
            OutlinedTextField(
                value = localVoiceId,
                onValueChange = { localVoiceId = it },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
                        if (isLoadingVoices) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        IconButton(onClick = { showVoicePicker = true }) {
                            Icon(Icons.AutoMirrored.Rounded.List, contentDescription = "Select Voice")
                        }
                    }
                }
            )
        }

        if (showVoicePicker) {
            MiniMaxVoicePicker(
                voices = voices,
                currentVoiceId = localVoiceId,
                onSelect = {
                    localVoiceId = it.id
                    localEmotion = it.styles.firstOrNull() ?: "calm"
                    showVoicePicker = false
                },
                onDismiss = { showVoicePicker = false }
            )
        }
    }

    // ============================================================================
    // Emotion 选择（三个音色分支通用：预置音色 / 音色设计 / 音色复刻）
    // ============================================================================
    var showStylePicker by remember { mutableStateOf(false) }
    val hasStyles = supportedStyles.size > 1
    FormItem(label = { Text(stringResource(R.string.setting_tts_page_emotion)) }) {
        OutlinedTextField(
            value = if (hasStyles) localEmotion else "calm",
            onValueChange = { if (hasStyles) localEmotion = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = hasStyles,
            trailingIcon = {
                if (hasStyles) {
                    IconButton(onClick = { showStylePicker = true }) {
                        Icon(Icons.AutoMirrored.Rounded.List, contentDescription = "Select Style")
                    }
                }
            }
        )
    }

    if (showStylePicker) {
        MiniMaxStylePicker(
            currentStyle = localEmotion,
            supportedStyles = supportedStyles,
            onSelect = {
                localEmotion = it
                showStylePicker = false
            },
            onDismiss = { showStylePicker = false }
        )
    }

    // ============================================================================
    // 分支 B：音色设计 (Voice Design)
    // ============================================================================
    if (localVoiceType == TTSProviderSetting.MiniMaxVoiceType.DESIGN) {
        // ---- 已有音色（voice_generation）选择，放在 Prompt 输入框上方 ----
        FormItem(
            label = { Text("已有音色") },
            description = {
                Text(
                    "从账号下已激活的「音色设计」音色中直接选用，无需重新生成。\n" +
                        "注：临时音色需至少成功调用一次 TTS 后才会出现在此列表中。"
                )
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = run {
                            val v = existingDesignVoices.firstOrNull { it.voiceId == localDesignedVoiceId }
                            when {
                                isLoadingExistingVoices -> "加载中…"
                                existingDesignVoices.isEmpty() -> "（暂无可选的音色设计）"
                                v != null -> "${v.voiceName}  (${v.voiceId})"
                                localDesignedVoiceId.isNotBlank() -> "当前选中（生成的临时音色）：$localDesignedVoiceId"
                                else -> "请选择已有音色…"
                            }
                        },
                        onValueChange = { },
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        trailingIcon = {
                            if (isLoadingExistingVoices) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                            }
                        },
                        shape = MaterialTheme.shapes.medium
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        if (existingDesignVoices.isEmpty() && !isLoadingExistingVoices) {
                            DropdownMenuItem(
                                text = { Text("（还没有已激活的音色设计，试试在下方生成一个并试听一次吧）") },
                                onClick = { expanded = false }
                            )
                        }
                        existingDesignVoices.forEach { v ->
                            DropdownMenuItem(
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = v.voiceName,
                                            fontWeight = if (v.voiceId == localDesignedVoiceId) FontWeight.Bold else FontWeight.Normal
                                        )
                                        Text(
                                            text = "${v.voiceId}${if (v.createdTime.isNotBlank()) " · 创建于 ${v.createdTime}" else ""}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    localDesignedVoiceId = v.voiceId
                                    expanded = false
                                },
                                leadingIcon = {
                                    if (v.voiceId == localDesignedVoiceId) {
                                        Icon(
                                            Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
                if (localDesignedVoiceId.isNotBlank() && existingDesignVoices.none { it.voiceId == localDesignedVoiceId }) {
                    Text(
                        "💡 当前使用的是本次会话内新生成的临时音色（还未在列表中激活），到右上角试听一次即可正式激活。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }

        FormItem(
            label = { Text("音色描述 Prompt") },
            description = {
                Text(
                    "必填。用自然语言描述你想要的音色：性别、年龄、情绪、说话风格、口音、场景等。",
                    color = MaterialTheme.colorScheme.error
                )
            }
        ) {
            OutlinedTextField(
                value = localDesignPrompt,
                onValueChange = { localDesignPrompt = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
                placeholder = {
                    Text(
                        "例如：\n" +
                            "讲述悬疑故事的播音员，声音低沉富有磁性，语速时快时慢，营造紧张神秘的氛围。\n" +
                            "或：年迈的老先生，北方口音，语速缓慢，嗓音沙哑沧桑，像在讲故事。\n" +
                            "或：年轻女生，声音温柔慵懒像刚睡醒，带一点点鼻音，语速很慢。"
                    )
                },
                maxLines = 8
            )
        }

        // 生成按钮（试听文本用数据模型内置的默认值，不让用户手动填）
        FormItem(label = { Text("生成音色") }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedButton(
                    onClick = {
                        scope.launch {
                            isGenerating = true
                            errorMessage = null
                            try {
                                // designPreviewText 使用 setting 默认值（内置试听文本）
                                val temp = setting.copy(
                                    apiKey = localApiKey,
                                    groupId = localGroupId,
                                    designPrompt = localDesignPrompt
                                )
                                val (voiceId, trialHex) = tts.miniMaxVoiceDesign(temp)
                                localDesignedVoiceId = voiceId
                                errorMessage = "✅ 音色设计成功！已保存 voice_id = $voiceId"
                                Log.i(TAG, "MiniMax voiceDesign OK: voice_id=$voiceId, trialHexLen=${trialHex.length}")
                            } catch (e: Exception) {
                                Log.e(TAG, "MiniMax voiceDesign failed", e)
                                errorMessage = "❌ " + (e.localizedMessage ?: "音色设计失败")
                            } finally {
                                isGenerating = false
                            }
                        }
                    },
                    enabled = !isGenerating && localApiKey.isNotBlank() && localDesignPrompt.isNotBlank()
                ) {
                    if (isGenerating) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                        Text("设计中…")
                    } else {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("生成个性化音色")
                    }
                }

                if (localDesignedVoiceId.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = "当前 voice_id：",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = localDesignedVoiceId,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = "⚠️ 该音色为临时音色，请在 7 天内至少在本应用进行一次语音合成（点右上角试听即可），否则会被 MiniMax 删除。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }

    // ============================================================================
    // 分支 C：音色复刻 (Voice Clone)
    // ============================================================================
    if (localVoiceType == TTSProviderSetting.MiniMaxVoiceType.CLONE) {
        // ---- 已有音色（voice_cloning）选择，放在自定义 voice_id 上方 ----
        FormItem(
            label = { Text("已有音色") },
            description = {
                Text(
                    "从账号下已激活的「音色复刻」音色中直接选用，无需重新上传音频复刻。\n" +
                        "注：临时复刻音色需至少成功调用一次 TTS 后才会出现在此列表中。"
                )
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = run {
                            val v = existingCloneVoices.firstOrNull { it.voiceId == localCloneVoiceId }
                            when {
                                isLoadingExistingVoices -> "加载中…"
                                existingCloneVoices.isEmpty() -> "（暂无可选的音色复刻）"
                                v != null -> "${v.voiceName}  (${v.voiceId})"
                                localCloneVoiceId.isNotBlank() -> "当前自定义 voice_id：$localCloneVoiceId"
                                else -> "请选择已有音色…"
                            }
                        },
                        onValueChange = { },
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.ContentCopy,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        trailingIcon = {
                            if (isLoadingExistingVoices) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                            }
                        },
                        shape = MaterialTheme.shapes.medium
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        if (existingCloneVoices.isEmpty() && !isLoadingExistingVoices) {
                            DropdownMenuItem(
                                text = { Text("（还没有已激活的音色复刻，试试在下方上传音频复刻一个并试听一次吧）") },
                                onClick = { expanded = false }
                            )
                        }
                        existingCloneVoices.forEach { v ->
                            DropdownMenuItem(
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = v.voiceName,
                                            fontWeight = if (v.voiceId == localCloneVoiceId) FontWeight.Bold else FontWeight.Normal
                                        )
                                        Text(
                                            text = "${v.voiceId}${if (v.createdTime.isNotBlank()) " · 创建于 ${v.createdTime}" else ""}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    // 选已有音色的话，直接把该 voice_id 写入 localCloneVoiceId，并清空上传必填项
                                    localCloneVoiceId = v.voiceId
                                    expanded = false
                                },
                                leadingIcon = {
                                    if (v.voiceId == localCloneVoiceId) {
                                        Icon(
                                            Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
                // 选了已有音色时：友好提示用户不必再上传音频
                if (localCloneVoiceId.isNotBlank() && existingCloneVoices.any { it.voiceId == localCloneVoiceId }) {
                    Text(
                        "💡 已从已有音色中选择，可直接使用，无需重新上传音频和执行复刻。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }

        // —— 自定义 voice_id ——
        val voiceIdError = remember(localCloneVoiceId) {
            if (localCloneVoiceId.isBlank()) null
            else tts.miniMaxValidateVoiceId(localCloneVoiceId).exceptionOrNull()?.localizedMessage
        }
        FormItem(
            label = { Text("自定义 voice_id") },
            description = {
                Column {
                    Text(
                        "必填。将来在 TTS 中引用该音色的标识。规则：长度 8-256，首字符必须是字母，只允许字母数字 - _，末尾不能是 -/_。",
                        color = MaterialTheme.colorScheme.error
                    )
                    if (voiceIdError != null) {
                        Text(voiceIdError, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        ) {
            OutlinedTextField(
                value = localCloneVoiceId,
                onValueChange = { localCloneVoiceId = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("例如：MiniMax001、MyVoice_01、Clone-2025abc") },
                isError = voiceIdError != null
            )
        }

        // —— 复刻主音频 ——
        FormItem(
            label = { Text("复刻音频（10s ~ 5min，≤ 20MB）") },
            description = {
                Column {
                    Text(
                        text = if (localCloneFileId == 0L) {
                            "必填。选择一段清晰的人声录音（mp3 / m4a / wav），环境安静，发音标准。"
                        } else {
                            "✅ 已上传：$localCloneAudioFileName ｜ file_id = $localCloneFileId"
                        },
                        color = if (localCloneFileId == 0L) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.OutlinedButton(
                    onClick = {
                        uploadingPurpose = "voice_clone"
                        audioPickerLauncher.launch("audio/*")
                    },
                    enabled = !isGenerating && localApiKey.isNotBlank()
                ) {
                    if (isGenerating && uploadingPurpose == "voice_clone") {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                        Text("上传中…")
                    } else {
                        Icon(Icons.Rounded.Upload, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(if (localCloneFileId == 0L) "选择并上传" else "重新上传")
                    }
                }
                if (localCloneFileId != 0L) {
                    Spacer(Modifier.size(8.dp))
                    IconButton(
                        onClick = {
                            localCloneFileId = 0L
                            localCloneAudioFileName = ""
                        }
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // —— clone_prompt：示例音频（可选） ——
        FormItem(
            label = { Text("示例音频（可选，< 8s）") },
            description = {
                Text(
                    if (localClonePromptAudioFileId == 0L)
                        "可选。上传一段非常短的示例音频（< 8s）+ 其对应文本，有助于增强音色相似度和稳定性。"
                    else
                        "✅ 已上传：$localClonePromptAudioFileName ｜ file_id = $localClonePromptAudioFileId"
                )
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            uploadingPurpose = "prompt_audio"
                            audioPickerLauncher.launch("audio/*")
                        },
                        enabled = !isGenerating && localApiKey.isNotBlank()
                    ) {
                        if (isGenerating && uploadingPurpose == "prompt_audio") {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                            Text("上传中…")
                        } else {
                            Icon(Icons.Rounded.Upload, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(if (localClonePromptAudioFileId == 0L) "选择示例音频" else "重新上传")
                        }
                    }
                    if (localClonePromptAudioFileId != 0L) {
                        Spacer(Modifier.size(8.dp))
                        IconButton(
                            onClick = {
                                localClonePromptAudioFileId = 0L
                                localClonePromptAudioFileName = ""
                            }
                        ) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = localClonePromptText,
                    onValueChange = { localClonePromptText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("示例音频对应的文本（句末请加标点）") },
                    placeholder = { Text("例如：这是一段用于音色复刻的示例文本。") },
                    enabled = localClonePromptAudioFileId != 0L || localClonePromptText.isNotBlank()
                )
            }
        }

        // —— 降噪 & 音量归一化 ——
        FormItem(label = { Text("音频预处理") }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                ) {
                    Switch(
                        checked = localCloneNoiseReduction,
                        onCheckedChange = { localCloneNoiseReduction = it }
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            if (localCloneNoiseReduction) "降噪：已开启" else "降噪：关闭",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "对复刻音频执行降噪处理，适合环境音较大的录音。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                ) {
                    Switch(
                        checked = localCloneVolumeNorm,
                        onCheckedChange = { localCloneVolumeNorm = it }
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            if (localCloneVolumeNorm) "音量归一化：已开启" else "音量归一化：关闭",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "将复刻音频音量调整到统一水平，适合音量忽大忽小的录音。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // —— 执行复刻按钮 ——
        FormItem(label = { Text("开始复刻") }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedButton(
                    onClick = {
                        scope.launch {
                            isGenerating = true
                            errorMessage = null
                            try {
                                // 不传 previewModel / previewText，仅生成音色（不额外合成 demo_audio）
                                val temp = setting.copy(
                                    apiKey = localApiKey,
                                    groupId = localGroupId,
                                    cloneVoiceId = localCloneVoiceId,
                                    cloneFileId = localCloneFileId,
                                    clonePromptAudioFileId = localClonePromptAudioFileId,
                                    clonePromptText = localClonePromptText,
                                    cloneNeedNoiseReduction = localCloneNoiseReduction,
                                    cloneNeedVolumeNormalization = localCloneVolumeNorm
                                )
                                val demoUrl = tts.miniMaxVoiceClone(temp)
                                errorMessage = "✅ 音色复刻成功！voice_id = ${temp.cloneVoiceId}"
                                Log.i(TAG, "MiniMax voiceClone OK: voice_id=${temp.cloneVoiceId}, demoUrlLen=${demoUrl.length}")
                            } catch (e: Exception) {
                                Log.e(TAG, "MiniMax voiceClone failed", e)
                                errorMessage = "❌ " + (e.localizedMessage ?: "音色复刻失败")
                            } finally {
                                isGenerating = false
                            }
                        }
                    },
                    enabled = !isGenerating &&
                        localApiKey.isNotBlank() &&
                        localCloneFileId != 0L &&
                        localCloneVoiceId.isNotBlank() &&
                        voiceIdError == null
                ) {
                    if (isGenerating) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                        Text("复刻中…")
                    } else {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("执行音色复刻")
                    }
                }

                if (localCloneVoiceId.isNotBlank() && voiceIdError == null && localCloneFileId != 0L) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.size(8.dp))
                        Text("当前复刻 voice_id：", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            localCloneVoiceId,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        "⚠️ 该音色为临时音色，请在 7 天内至少在本应用进行一次语音合成（点右上角试听即可），否则会被 MiniMax 删除；同时 MiniMax 需要账号完成个人/企业认证才可使用复刻。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }

    // ============================================================================
    // 通用：语速（所有音色类型都用 voice_setting.speed）
    // ============================================================================
    FormItem(label = { Text(stringResource(R.string.setting_tts_page_speed)) }) {
        OutlinedNumberInput(
            value = localSpeed,
            onValueChange = { if (it in 0.25f..4.0f) localSpeed = it },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.setting_tts_page_speed)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MiniMaxVoicePicker(
    voices: List<TTSVoice>,
    currentVoiceId: String,
    onSelect: (TTSVoice) -> Unit,
    onDismiss: () -> Unit
) {
    val haptics = rememberPremiumHaptics()
    var searchQuery by remember { mutableStateOf("") }
    var filterGender by remember { mutableIntStateOf(0) } // 0: All, 1: Male, 2: Female, 3: Custom

    val filteredVoices by remember(voices, searchQuery, filterGender) {
        derivedStateOf {
            voices.filter { voice ->
                val matchesSearch = if (searchQuery.isBlank()) true
                else voice.name.contains(searchQuery, ignoreCase = true) || voice.id.contains(searchQuery, ignoreCase = true)

                val voiceGender = voice.gender?.lowercase() ?: ""
                val matchesGender = when (filterGender) {
                    1 -> voiceGender == "male"
                    2 -> voiceGender == "female"
                    3 -> voiceGender != "male" && voiceGender != "female"
                    else -> true
                }
                matchesSearch && matchesGender
            }
        }
    }

    BasicAlertDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.85f), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = stringResource(R.string.setting_tts_page_minimax_voice_picker_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp, start = 8.dp))
                OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), placeholder = { Text(stringResource(R.string.setting_tts_page_azure_voice_picker_search_placeholder)) }, leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) }, singleLine = true, shape = MaterialTheme.shapes.medium)
                Spacer(Modifier.height(12.dp))

                // 维度矩阵
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(selected = filterGender == 0, onClick = { haptics.perform(HapticPattern.Pop); filterGender = 0 }, shape = SegmentedButtonDefaults.itemShape(index = 0, count = 4)) { Text(stringResource(R.string.setting_tts_page_azure_filter_gender_all), style = MaterialTheme.typography.labelSmall) }
                        SegmentedButton(selected = filterGender == 1, onClick = { haptics.perform(HapticPattern.Pop); filterGender = 1 }, shape = SegmentedButtonDefaults.itemShape(index = 1, count = 4)) { Text(stringResource(R.string.setting_tts_page_azure_filter_male), style = MaterialTheme.typography.labelSmall) }
                        SegmentedButton(selected = filterGender == 2, onClick = { haptics.perform(HapticPattern.Pop); filterGender = 2 }, shape = SegmentedButtonDefaults.itemShape(index = 2, count = 4)) { Text(stringResource(R.string.setting_tts_page_azure_filter_female), style = MaterialTheme.typography.labelSmall) }
                        SegmentedButton(selected = filterGender == 3, onClick = { haptics.perform(HapticPattern.Pop); filterGender = 3 }, shape = SegmentedButtonDefaults.itemShape(index = 3, count = 4)) { Text("自定义", style = MaterialTheme.typography.labelSmall) }
                    }
                }

                Spacer(Modifier.height(16.dp))
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(filteredVoices) { voice ->
                        val isSelected = voice.id == currentVoiceId
                        ListItem(
                            modifier = Modifier.clickable { haptics.perform(HapticPattern.Pop); onSelect(voice) },
                            headlineContent = { Text(voice.name, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                            supportingContent = { Text("${when (voice.gender?.lowercase()) { "male" -> "男声"; "female" -> "女声"; else -> "自定义" }} | ${voice.id} | ${voice.description ?: ""}", style = MaterialTheme.typography.labelSmall) },
                            trailingContent = if (isSelected) { { Icon(Icons.Rounded.Visibility, tint = MaterialTheme.colorScheme.primary, contentDescription = null) } } else null,
                            colors = ListItemDefaults.colors(containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface)
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    }
                }
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp), horizontalArrangement = Arrangement.End) {
                    androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MiniMaxStylePicker(
    currentStyle: String,
    supportedStyles: List<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val haptics = rememberPremiumHaptics()
    var searchQuery by remember { mutableStateOf("") }
    val filteredStyles by remember(searchQuery, supportedStyles) {
        derivedStateOf {
            if (searchQuery.isBlank()) supportedStyles
            else supportedStyles.filter { it.contains(searchQuery, ignoreCase = true) }
        }
    }

    BasicAlertDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.85f), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = stringResource(R.string.setting_tts_page_minimax_style_picker_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp, start = 8.dp))

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    placeholder = { Text(stringResource(R.string.setting_tts_page_azure_style_picker_search_placeholder)) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        { IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Rounded.Close, contentDescription = null) } }
                    } else null,
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )

                Spacer(modifier = Modifier.height(16.dp))
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(filteredStyles) { style ->
                        val isSelected = style == currentStyle
                        ListItem(
                            modifier = Modifier.clickable { haptics.perform(HapticPattern.Pop); onSelect(style) },
                            headlineContent = { Text(style, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                            trailingContent = if (isSelected) { { Icon(Icons.Rounded.Visibility, tint = MaterialTheme.colorScheme.primary, contentDescription = null) } } else null,
                            colors = ListItemDefaults.colors(containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface)
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f) )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp), horizontalArrangement = Arrangement.End) {
                    androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AzureVoicePicker(
    voices: List<TTSVoice>,
    currentVoiceId: String,
    onSelect: (TTSVoice) -> Unit,
    onDismiss: () -> Unit
) {
    val haptics = rememberPremiumHaptics()
    var searchQuery by remember { mutableStateOf("") }

    // 过滤器状态
    var filterType by remember { mutableIntStateOf(0) } // 0: All, 1: Neural, 2: Standard
    var filterGender by remember { mutableIntStateOf(0) } // 0: All, 1: Male, 2: Female

    val filteredVoices by remember(voices, searchQuery, filterType, filterGender) {
        derivedStateOf {
            voices.filter { voice ->
                // 1. 搜索过滤
                val matchesSearch = if (searchQuery.isBlank()) true
                else {
                    voice.name.contains(searchQuery, ignoreCase = true) ||
                    voice.id.contains(searchQuery, ignoreCase = true) ||
                    (voice.locale?.contains(searchQuery, ignoreCase = true) ?: false)
                }

                // 2. 类型过滤
                val isNeural = voice.id.contains("Neural", ignoreCase = true)
                val matchesType = when (filterType) {
                    1 -> isNeural
                    2 -> !isNeural
                    else -> true
                }

                // 3. 性别过滤
                val voiceGender = voice.gender?.lowercase() ?: ""
                val matchesGender = when (filterGender) {
                    1 -> voiceGender == "male"
                    2 -> voiceGender == "female"
                    else -> true
                }

                matchesSearch && matchesType && matchesGender
            }
        }
    }

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.85f),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.setting_tts_page_azure_voice_picker_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp, start = 8.dp)
                )

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    placeholder = { Text(stringResource(R.string.setting_tts_page_azure_voice_picker_search_placeholder)) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        { IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Rounded.Close, contentDescription = "Clear") } }
                    } else null,
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 过滤器矩阵
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 品质维度
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = filterType == 0,
                            onClick = { haptics.perform(HapticPattern.Pop); filterType = 0 },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
                        ) { Text(stringResource(R.string.setting_tts_page_azure_filter_all), style = MaterialTheme.typography.labelSmall) }
                        SegmentedButton(
                            selected = filterType == 1,
                            onClick = { haptics.perform(HapticPattern.Pop); filterType = 1 },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
                        ) { Text(stringResource(R.string.setting_tts_page_azure_filter_neural), style = MaterialTheme.typography.labelSmall) }
                        SegmentedButton(
                            selected = filterType == 2,
                            onClick = { haptics.perform(HapticPattern.Pop); filterType = 2 },
                            shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
                        ) { Text(stringResource(R.string.setting_tts_page_azure_filter_standard), style = MaterialTheme.typography.labelSmall) }
                    }

                    // 性别维度
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = filterGender == 0,
                            onClick = { haptics.perform(HapticPattern.Pop); filterGender = 0 },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
                        ) { Text(stringResource(R.string.setting_tts_page_azure_filter_gender_all), style = MaterialTheme.typography.labelSmall) }
                        SegmentedButton(
                            selected = filterGender == 1,
                            onClick = { haptics.perform(HapticPattern.Pop); filterGender = 1 },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
                        ) { Text(stringResource(R.string.setting_tts_page_azure_filter_male), style = MaterialTheme.typography.labelSmall) }
                        SegmentedButton(
                            selected = filterGender == 2,
                            onClick = { haptics.perform(HapticPattern.Pop); filterGender = 2 },
                            shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
                        ) { Text(stringResource(R.string.setting_tts_page_azure_filter_female), style = MaterialTheme.typography.labelSmall) }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    items(filteredVoices, key = { it.id }) { voice ->
                        val isSelected = voice.id == currentVoiceId
                        val isNeural = voice.id.contains("Neural", ignoreCase = true)

                        ListItem(
                            modifier = Modifier
                                .clickable {
                                    haptics.perform(HapticPattern.Pop)
                                    onSelect(voice)
                                },
                            headlineContent = {
                                Text(
                                    text = voice.name,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = "${voice.locale ?: ""} | ${if (isNeural) "Neural" else "Standard"} | ${voice.gender ?: ""} | ${voice.id}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            trailingContent = if (isSelected) {
                                { Icon(Icons.Rounded.Visibility, tint = MaterialTheme.colorScheme.primary, contentDescription = "Selected") }
                            } else null,
                            colors = ListItemDefaults.colors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface
                            )
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                        )
                    }

                    if (filteredVoices.isEmpty()) {
                        item {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(stringResource(R.string.setting_tts_page_azure_voice_picker_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    androidx.compose.material3.TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.done))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AzureStylePicker(
    currentStyle: String,
    supportedStyles: List<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val haptics = rememberPremiumHaptics()
    var searchQuery by remember { mutableStateOf("") }
    val filteredStyles by remember(searchQuery, supportedStyles) {
        derivedStateOf {
            if (searchQuery.isBlank()) supportedStyles
            else supportedStyles.filter { it.contains(searchQuery, ignoreCase = true) }
        }
    }

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.85f),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.setting_tts_page_azure_style_picker_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp, start = 8.dp)
                )

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    placeholder = { Text(stringResource(R.string.setting_tts_page_azure_style_picker_search_placeholder)) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        { IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Rounded.Close, contentDescription = "Clear") } }
                    } else null,
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )

                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    items(filteredStyles) { style ->
                        val isSelected = style == currentStyle
                        ListItem(
                            modifier = Modifier
                                .clickable {
                                    haptics.perform(HapticPattern.Pop)
                                    onSelect(style)
                                },
                            headlineContent = {
                                Text(
                                    text = style,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            trailingContent = if (isSelected) {
                                { Icon(Icons.Rounded.Visibility, tint = MaterialTheme.colorScheme.primary, contentDescription = "Selected") }
                            } else null,
                            colors = ListItemDefaults.colors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface
                            )
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    androidx.compose.material3.TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.done))
                    }
                }
            }
        }
    }
}

@Composable
private fun OpenAITTSConfiguration(
    setting: TTSProviderSetting.OpenAI,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // 自动修正预设地址
    LaunchedEffect(setting.id) {
        if (setting.baseUrl != "https://api.openai.com/v1") {
            onValueChange(setting.copy(baseUrl = "https://api.openai.com/v1"))
        }
    }

    var apiKeyVisible by remember { mutableStateOf(false) }
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if (!it.isFocused) apiKeyVisible = false },
            placeholder = { Text(stringResource(R.string.setting_tts_page_api_key_placeholder_openai)) },
            visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                    Icon(
                        imageVector = if (apiKeyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = "Toggle Visibility"
                    )
                }
            }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        description = { Text("接口地址 (预设地址，不可编辑)") }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = {},
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_base_url_placeholder)) },
            enabled = false
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_model)) },
        description = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_model_placeholder_openai)) }
        )
    }

    var voiceExpanded by remember { mutableStateOf(false) }
    val voices = listOf("alloy", "echo", "fable", "onyx", "nova", "shimmer")

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice)) },
        description = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        ExposedDropdownMenuBox(
            expanded = voiceExpanded,
            onExpandedChange = { voiceExpanded = !voiceExpanded }
        ) {
            OutlinedTextField(
                value = setting.voice,
                onValueChange = { newVoice ->
                    onValueChange(setting.copy(voice = newVoice))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceExpanded)
                }
            )
            ExposedDropdownMenu(
                expanded = voiceExpanded,
                onDismissRequest = { voiceExpanded = false }
            ) {
                voices.forEach { voice ->
                    DropdownMenuItem(
                        text = { Text(voice) },
                        onClick = {
                            voiceExpanded = false
                            onValueChange(setting.copy(voice = voice))
                        }
                    )
                }
            }
        }
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_speed)) },
        description = { Text(stringResource(R.string.setting_tts_page_speed_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.speed,
            onValueChange = { newSpeed ->
                if (newSpeed in 0.25f..4.0f) {
                    onValueChange(setting.copy(speed = newSpeed))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.setting_tts_page_speed)
        )
    }
}

@Composable
private fun GeminiTTSConfiguration(
    setting: TTSProviderSetting.Gemini,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // 自动修正预设地址
    LaunchedEffect(setting.id) {
        if (setting.baseUrl != "https://generativelanguage.googleapis.com/v1beta") {
            onValueChange(setting.copy(baseUrl = "https://generativelanguage.googleapis.com/v1beta"))
        }
    }

    var apiKeyVisible by remember { mutableStateOf(false) }
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text(stringResource(R.string.setting_tts_page_api_key_description_gemini)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if (!it.isFocused) apiKeyVisible = false },
            placeholder = { Text(stringResource(R.string.setting_tts_page_api_key_placeholder_gemini)) },
            visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                    Icon(
                        imageVector = if (apiKeyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = "Toggle Visibility"
                    )
                }
            }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        description = { Text("接口地址 (预设地址，不可编辑)") }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = {},
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_base_url_placeholder)) },
            enabled = false
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_model)) },
        description = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_model_placeholder_gemini)) }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice_name)) },
        description = { Text(stringResource(R.string.setting_tts_page_voice_name_description)) }
    ) {
        OutlinedTextField(
            value = setting.voiceName,
            onValueChange = { newVoiceName ->
                onValueChange(setting.copy(voiceName = newVoiceName))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_voice_name_placeholder_gemini)) }
        )
    }
}

@Composable
private fun ElevenLabsTTSConfiguration(
    setting: TTSProviderSetting.ElevenLabs,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    var apiKeyVisible by remember { mutableStateOf(false) }
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text(stringResource(R.string.setting_tts_page_api_key_description_elevenlabs)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if (!it.isFocused) apiKeyVisible = false },
            placeholder = { Text(stringResource(R.string.setting_tts_page_api_key_placeholder_elevenlabs)) },
            visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                    Icon(
                        imageVector = if (apiKeyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = "Toggle Visibility"
                    )
                }
            }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice_id)) },
        description = { Text(stringResource(R.string.setting_tts_page_voice_id_description)) }
    ) {
        OutlinedTextField(
            value = setting.voiceId,
            onValueChange = { newVoiceId ->
                onValueChange(setting.copy(voiceId = newVoiceId))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("21m00Tcm4TlvDq8ikWAM") }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_model_id)) },
        description = { Text(stringResource(R.string.setting_tts_page_model_id_description)) }
    ) {
        OutlinedTextField(
            value = setting.modelId,
            onValueChange = { newModelId ->
                onValueChange(setting.copy(modelId = newModelId))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("eleven_multilingual_v2") }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_speed)) },
        description = { Text(stringResource(R.string.setting_tts_page_speed_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.speed,
            onValueChange = { newSpeed ->
                if (newSpeed in 0.25f..4.0f) {
                    onValueChange(setting.copy(speed = newSpeed))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.setting_tts_page_speed)
        )
    }
}

@Composable
private fun SystemTTSConfiguration(
    setting: TTSProviderSetting.SystemTTS,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    val context = LocalContext.current
    var voices by remember { mutableStateOf(emptyList<android.speech.tts.Voice>()) }

    LaunchedEffect(Unit) {
        var ttsInstance: TextToSpeech? = null
        ttsInstance = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsInstance?.let {
                    voices = it.voices.toList()
                    it.stop()
                    it.shutdown()
                }
            }
        }
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_speech_rate)) },
        description = { Text(stringResource(R.string.setting_tts_page_speech_rate_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.speechRate,
            onValueChange = { newRate ->
                if (newRate in 0.1f..5.0f) {
                    onValueChange(setting.copy(speechRate = newRate))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.setting_tts_page_speech_rate)
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_pitch)) },
        description = { Text(stringResource(R.string.setting_tts_page_pitch_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.pitch,
            onValueChange = { newPitch ->
                if (newPitch in 0.1f..5.0f) {
                    onValueChange(setting.copy(pitch = newPitch))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.setting_tts_page_pitch)
        )
    }

    var voiceExpanded by remember { mutableStateOf(false) }
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice_name)) },
        description = { Text(stringResource(R.string.setting_tts_page_voice_name_description)) }
    ) {
        ExposedDropdownMenuBox(
            expanded = voiceExpanded,
            onExpandedChange = { voiceExpanded = !voiceExpanded }
        ) {
            OutlinedTextField(
                value = setting.voiceName ?: stringResource(R.string.setting_tts_page_voice_name_default),
                onValueChange = {},
                readOnly = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceExpanded)
                }
            )
            ExposedDropdownMenu(
                expanded = voiceExpanded,
                onDismissRequest = { voiceExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.setting_tts_page_voice_name_default)) },
                    onClick = {
                        voiceExpanded = false
                        onValueChange(setting.copy(voiceName = null))
                    }
                )
                voices.forEach { voice ->
                    DropdownMenuItem(
                        text = { Text(voice.name) },
                        onClick = {
                            voiceExpanded = false
                            onValueChange(setting.copy(voiceName = voice.name))
                        }
                    )
                }
            }
        }
    }
}
