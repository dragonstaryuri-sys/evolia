package me.rerere.rikkahub.ui.pages.setting.components

import android.speech.tts.TextToSpeech
import android.util.Log
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.delay
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.OutlinedNumberInput
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.tts.model.TTSVoice
import me.rerere.tts.provider.TTSProviderSetting

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
            is TTSProviderSetting.Gemini -> GeminiTTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.MiniMax -> MiniMaxTTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.ElevenLabs -> ElevenLabsTTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.SystemTTS -> SystemTTSConfiguration(setting, onValueChange)
            is TTSProviderSetting.Azure -> AzureTTSConfiguration(setting, onValueChange)
        }
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

    // 引入本地状态，彻底隔离打字时的数据库写入卡顿
    var localApiKey by remember(setting.apiKey) { mutableStateOf(setting.apiKey) }
    var localRegion by remember(setting.region) { mutableStateOf(setting.region) }
    var localVoiceName by remember(setting.voiceName) { mutableStateOf(setting.voiceName) }
    var localStyle by remember(setting.style) { mutableStateOf(setting.style) }
    var localSpeed by remember(setting.speed) { mutableStateOf(setting.speed) }

    // 获取当前语音对象以提取动态风格
    val currentVoice = remember(localVoiceName, voices) {
        voices.find { it.id == localVoiceName }
    }
    val supportedStyles = remember(currentVoice) {
        val list = mutableListOf("general")
        currentVoice?.styles?.let { list.addAll(it) }
        list.distinct()
    }

    // 防抖同步：停止输入 500ms 后才同步给外部（触发数据库保存）
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

    // API Key
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

    // Region
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

    // Voice Name
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

    // Style (Emotion)
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

    // Speed
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
                // 切换语音后重置风格为默认
                localStyle = "general"
                showVoicePicker = false
                // 强制立刻同步一次，确保预览生效
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
                // 强制立刻同步一次，确保预览生效
                onValueChange(setting.copy(style = it))
            },
            onDismiss = { showStylePicker = false }
        )
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
    var filterType by remember { mutableIntStateOf(0) } // 0: All, 1: Neural, 2: Standard

    val filteredVoices by remember(voices, searchQuery, filterType) {
        derivedStateOf {
            voices.filter { voice ->
                val matchesSearch = if (searchQuery.isBlank()) true
                else {
                    voice.name.contains(searchQuery, ignoreCase = true) ||
                    voice.id.contains(searchQuery, ignoreCase = true) ||
                    (voice.locale?.contains(searchQuery, ignoreCase = true) ?: false)
                }

                val isNeural = voice.id.contains("Neural", ignoreCase = true)
                val matchesType = when (filterType) {
                    1 -> isNeural
                    2 -> !isNeural
                    else -> true
                }

                matchesSearch && matchesType
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

                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                ) {
                    SegmentedButton(
                        selected = filterType == 0,
                        onClick = {
                            haptics.perform(HapticPattern.Pop)
                            filterType = 0
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
                    ) {
                        Text(stringResource(R.string.setting_tts_page_azure_filter_all), style = MaterialTheme.typography.labelSmall)
                    }
                    SegmentedButton(
                        selected = filterType == 1,
                        onClick = {
                            haptics.perform(HapticPattern.Pop)
                            filterType = 1
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
                    ) {
                        Text(stringResource(R.string.setting_tts_page_azure_filter_neural), style = MaterialTheme.typography.labelSmall)
                    }
                    SegmentedButton(
                        selected = filterType == 2,
                        onClick = {
                            haptics.perform(HapticPattern.Pop)
                            filterType = 2
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
                    ) {
                        Text(stringResource(R.string.setting_tts_page_azure_filter_standard), style = MaterialTheme.typography.labelSmall)
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
                                    text = "${voice.locale ?: ""} | ${if (isNeural) "Neural" else "Standard"} | ${voice.id}",
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
    // API Key
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

    // Base URL
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

    // Model
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

    // Voice
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
}

@Composable
private fun MiniMaxTTSConfiguration(
    setting: TTSProviderSetting.MiniMax,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
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

    // Base URL
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

    // Model
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
            placeholder = { Text("speech-2.5-hd-preview") }
        )
    }

    // Voice ID
    var voiceIdExpanded by remember { mutableStateOf(false) }
    val voiceIds = listOf(
        "male-qn-qingse",
        "male-qn-jingying",
        "male-qn-badao",
        "male-qn-daxuesheng",
        "female-shaonv",
        "female-yujie",
        "female-chengshu",
        "female-tianmei",
        "audiobook_male_1",
        "audiobook_female_1",
        "cartoon_pig"
    )

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice_id)) },
        description = { Text(stringResource(R.string.setting_tts_page_voice_id_description)) }
    ) {
        ExposedDropdownMenuBox(
            expanded = voiceIdExpanded,
            onExpandedChange = { voiceIdExpanded = !voiceIdExpanded }
        ) {
            OutlinedTextField(
                value = setting.voiceId,
                onValueChange = { newVoiceId ->
                    onValueChange(setting.copy(voiceId = newVoiceId))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceIdExpanded)
                }
            )
            ExposedDropdownMenu(
                expanded = voiceIdExpanded,
                onDismissRequest = { voiceIdExpanded = false }
            ) {
                voiceIds.forEach { voiceId ->
                    DropdownMenuItem(
                        text = { Text(voiceId) },
                        onClick = {
                            voiceIdExpanded = false
                            onValueChange(setting.copy(voiceId = voiceId))
                        }
                    )
                }
            }
        }
    }

    // Emotion
    var emotionExpanded by remember { mutableStateOf(false) }
    val emotions = listOf("calm", "happy", "sad", "angry", "fearful", "disgusted", "surprised")

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_emotion)) },
        description = { Text(stringResource(R.string.setting_tts_page_emotion_description)) }
    ) {
        ExposedDropdownMenuBox(
            expanded = emotionExpanded,
            onExpandedChange = { emotionExpanded = !emotionExpanded }
        ) {
            OutlinedTextField(
                value = setting.emotion,
                onValueChange = { newEmotion ->
                    onValueChange(setting.copy(emotion = newEmotion))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = emotionExpanded)
                }
            )
            ExposedDropdownMenu(
                expanded = emotionExpanded,
                onDismissRequest = { emotionExpanded = false }
            ) {
                emotions.forEach { emotion ->
                    DropdownMenuItem(
                        text = { Text(emotion) },
                        onClick = {
                            emotionExpanded = false
                            onValueChange(setting.copy(emotion = emotion))
                        }
                    )
                }
            }
        }
    }

    // Speed
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
    // API Key
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

    // Base URL
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

    // Model
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

    // Voice Name
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
    // API Key
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

    // Voice ID
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

    // Model ID
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

    // Speech Rate
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

    // Pitch
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

    // Voice Name
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
