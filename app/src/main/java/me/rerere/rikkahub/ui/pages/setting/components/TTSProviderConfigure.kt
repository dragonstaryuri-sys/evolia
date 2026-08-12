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
import androidx.compose.material.icons.rounded.Close
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
                fetchedModels = models.filter { it.contains("tts", ignoreCase = true) }
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
    val audioPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isConvertingAudio = true
            try {
                val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameIndex != -1) cursor.getString(nameIndex) else null
                } ?: uri.lastPathSegment ?: "audio"

                // 推断格式
                val format = fileName.substringAfterLast('.', "").lowercase().takeIf { it.isNotBlank() }
                    ?: context.contentResolver.getType(uri)?.substringAfterLast('/') ?: "wav"

                val base64 = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readBytes()
                        Base64.encodeToString(bytes, Base64.NO_WRAP)
                    }
                }
                if (base64 != null) {
                    localReferenceAudioBase64 = base64
                    localReferenceAudioFileName = fileName
                    localReferenceAudioFormat = format
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read reference audio", e)
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
                            "选择一段 5-60 秒的清晰人声音频，作为音色复刻的参考。建议文件 < 1MB。"
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
                        label = { Text("音频格式 (可选，自动识别)") },
                        placeholder = { Text("wav / mp3 / m4a / flac ...") },
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

@Composable
private fun MiniMaxTTSConfiguration(
    setting: TTSProviderSetting.MiniMax,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    val tts = LocalTTSState.current
    var voices by remember { mutableStateOf<List<TTSVoice>>(emptyList()) }
    var isLoadingVoices by remember { mutableStateOf(false) }

    // 自动修正预设地址
    LaunchedEffect(setting.id) {
        if (setting.baseUrl != "https://api.minimaxi.com/v1") {
            onValueChange(setting.copy(baseUrl = "https://api.minimaxi.com/v1"))
        }
    }

    var localApiKey by remember(setting.apiKey) { mutableStateOf(setting.apiKey) }
    var localBaseUrl by remember(setting.baseUrl) { mutableStateOf(setting.baseUrl) }
    var localModel by remember(setting.model) { mutableStateOf(setting.model) }
    var localVoiceId by remember(setting.voiceId) { mutableStateOf(setting.voiceId) }
    var localEmotion by remember(setting.emotion) { mutableStateOf(setting.emotion) }
    var localSpeed by remember(setting.speed) { mutableStateOf(setting.speed) }

    val currentVoice = remember(localVoiceId, voices) { voices.find { it.id == localVoiceId } }
    val supportedStyles = remember(currentVoice) { currentVoice?.styles ?: listOf("calm") }

    LaunchedEffect(localApiKey, localBaseUrl, localModel, localVoiceId, localEmotion, localSpeed) {
        if (localApiKey != setting.apiKey || localBaseUrl != setting.baseUrl ||
            localModel != setting.model || localVoiceId != setting.voiceId ||
            localEmotion != setting.emotion || localSpeed != setting.speed) {
            delay(500)
            onValueChange(setting.copy(
                apiKey = localApiKey,
                baseUrl = localBaseUrl,
                model = localModel,
                voiceId = localVoiceId,
                emotion = localEmotion,
                speed = localSpeed
            ))
        }
    }

    LaunchedEffect(localApiKey) {
        if (localApiKey.isNotBlank()) {
            isLoadingVoices = true
            try {
                voices = tts.getVoices(setting.copy(apiKey = localApiKey))
            } catch (e: Exception) {
                Log.e(TAG, "MiniMax: Fetch voices failed", e)
            } finally {
                isLoadingVoices = false
            }
        }
    }

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

    FormItem(label = { Text(stringResource(R.string.setting_tts_page_model)) }) {
        OutlinedTextField(value = localModel, onValueChange = { localModel = it }, modifier = Modifier.fillMaxWidth())
    }

    var showVoicePicker by remember { mutableStateOf(false) }
    FormItem(label = { Text(stringResource(R.string.setting_tts_page_voice_id)) }) {
        OutlinedTextField(
            value = localVoiceId,
            onValueChange = { localVoiceId = it },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
                    if (isLoadingVoices) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    IconButton(onClick = { showVoicePicker = true }) { Icon(Icons.AutoMirrored.Rounded.List, contentDescription = "Select Voice") }
                }
            }
        )
    }

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
                    IconButton(onClick = { showStylePicker = true }) { Icon(Icons.AutoMirrored.Rounded.List, contentDescription = "Select Style") }
                }
            }
        )
    }

    FormItem(label = { Text(stringResource(R.string.setting_tts_page_speed)) }) {
        OutlinedNumberInput(
            value = localSpeed,
            onValueChange = { if (it in 0.25f..4.0f) localSpeed = it },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.setting_tts_page_speed)
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
                onValueChange(setting.copy(voiceId = it.id, emotion = localEmotion))
            },
            onDismiss = { showVoicePicker = false }
        )
    }

    if (showStylePicker) {
        MiniMaxStylePicker(
            currentStyle = localEmotion,
            supportedStyles = supportedStyles,
            onSelect = {
                localEmotion = it
                showStylePicker = false
                onValueChange(setting.copy(emotion = it))
            },
            onDismiss = { showStylePicker = false }
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
    var filterGender by remember { mutableIntStateOf(0) } // 0: All, 1: Male, 2: Female

    val filteredVoices by remember(voices, searchQuery, filterGender) {
        derivedStateOf {
            voices.filter { voice ->
                val matchesSearch = if (searchQuery.isBlank()) true
                else voice.name.contains(searchQuery, ignoreCase = true) || voice.id.contains(searchQuery, ignoreCase = true)

                val voiceGender = voice.gender?.lowercase() ?: ""
                val matchesGender = when (filterGender) {
                    1 -> voiceGender == "male"
                    2 -> voiceGender == "female"
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
                        SegmentedButton(selected = filterGender == 0, onClick = { haptics.perform(HapticPattern.Pop); filterGender = 0 }, shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)) { Text(stringResource(R.string.setting_tts_page_azure_filter_gender_all), style = MaterialTheme.typography.labelSmall) }
                        SegmentedButton(selected = filterGender == 1, onClick = { haptics.perform(HapticPattern.Pop); filterGender = 1 }, shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)) { Text(stringResource(R.string.setting_tts_page_azure_filter_male), style = MaterialTheme.typography.labelSmall) }
                        SegmentedButton(selected = filterGender == 2, onClick = { haptics.perform(HapticPattern.Pop); filterGender = 2 }, shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)) { Text(stringResource(R.string.setting_tts_page_azure_filter_female), style = MaterialTheme.typography.labelSmall) }
                    }
                }

                Spacer(Modifier.height(16.dp))
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(filteredVoices) { voice ->
                        val isSelected = voice.id == currentVoiceId
                        ListItem(
                            modifier = Modifier.clickable { haptics.perform(HapticPattern.Pop); onSelect(voice) },
                            headlineContent = { Text(voice.name, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                            supportingContent = { Text("${voice.gender ?: ""} | ${voice.id} | ${voice.description ?: ""}", style = MaterialTheme.typography.labelSmall) },
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
