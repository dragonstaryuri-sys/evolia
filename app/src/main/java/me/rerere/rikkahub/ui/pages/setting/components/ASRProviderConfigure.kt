package me.rerere.rikkahub.ui.pages.setting.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.asr.provider.ASRManager
import me.rerere.asr.provider.ASRProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.voice.SenseVoiceModelManager
import me.rerere.rikkahub.service.voice.VoiceRecorderController
import me.rerere.rikkahub.service.voice.VoiceRecorderState
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import me.rerere.rikkahub.ui.components.ui.ToastType
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.UiState
import org.koin.compose.koinInject

@Composable
fun ASRProviderConfigure(
    setting: ASRProviderSetting,
    providers: List<ProviderSetting>,
    modifier: Modifier = Modifier,
    onValueChange: (ASRProviderSetting) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.verticalScroll(rememberScrollState())
    ) {
        when (setting) {
            is ASRProviderSetting.SystemASR -> SystemASRConfiguration(setting, onValueChange)
            is ASRProviderSetting.OnlineASR -> OnlineASRConfiguration(setting, providers, onValueChange)
            is ASRProviderSetting.LocalSenseVoiceASR -> LocalSenseVoiceASRConfiguration(setting, onValueChange)
            // EvoliaASR 是内置不可编辑项，UI 不显示编辑入口，此处仅需满足 exhaustive when
            is ASRProviderSetting.EvoliaASR -> {}
        }
    }
}

@Composable
private fun SystemASRConfiguration(
    setting: ASRProviderSetting.SystemASR,
    onValueChange: (ASRProviderSetting) -> Unit
) {
    // 名称
    FormItem(
        label = { Text(stringResource(R.string.setting_asr_page_name)) },
        description = { Text(stringResource(R.string.setting_asr_page_name_description)) }
    ) {
        OutlinedTextField(
            value = setting.name,
            onValueChange = { onValueChange(setting.copy(name = it)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.setting_asr_page_name_placeholder)) }
        )
    }

    // 识别语言
    val languages = remember {
        listOf(
            "zh-CN" to "中文(简体)",
            "zh-TW" to "中文(繁體)",
            "en-US" to "English (US)",
            "en-GB" to "English (UK)",
            "ja-JP" to "日本語",
            "ko-KR" to "한국어",
            "fr-FR" to "Français",
            "de-DE" to "Deutsch",
            "es-ES" to "Español",
            "ru-RU" to "Русский"
        )
    }
    var langExpanded by remember { mutableStateOf(false) }
    val selectedLangLabel = languages.firstOrNull { it.first == setting.language }?.second ?: setting.language
    FormItem(
        label = { Text(stringResource(R.string.setting_asr_page_language)) },
        description = { Text(stringResource(R.string.setting_asr_page_language_description)) }
    ) {
        ExposedDropdownMenuBox(
            expanded = langExpanded,
            onExpandedChange = { langExpanded = !langExpanded }
        ) {
            OutlinedTextField(
                value = selectedLangLabel,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = langExpanded)
                }
            )
            ExposedDropdownMenu(
                expanded = langExpanded,
                onDismissRequest = { langExpanded = false }
            ) {
                languages.forEach { (code, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            onValueChange(setting.copy(language = code))
                            langExpanded = false
                        }
                    )
                }
            }
        }
    }

    // 离线识别偏好
    FormItem(
        label = { Text(stringResource(R.string.setting_asr_page_offline)) },
        description = { Text(stringResource(R.string.setting_asr_page_offline_description)) }
    ) {
        HapticSwitch(
            checked = setting.enableOffline,
            onCheckedChange = { onValueChange(setting.copy(enableOffline = it)) }
        )
    }
}

@Composable
private fun OnlineASRConfiguration(
    setting: ASRProviderSetting.OnlineASR,
    providers: List<ProviderSetting>,
    onValueChange: (ASRProviderSetting) -> Unit
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val providerManager = koinInject<ProviderManager>()

    // ===== 从大模型 Provider 复制配置 =====
    var providerExpanded by remember { mutableStateOf(false) }
    // API Key 显示/隐藏
    var apiKeyVisible by remember { mutableStateOf(false) }
    // 用于展示的选中 Provider 名称（不持久化，仅辅助 UI 选择）
    val selectedProviderLabel = remember(providers, setting.apiKey, setting.apiUrl) {
        providers.firstOrNull { p ->
            when (p) {
                is ProviderSetting.OpenAI -> p.apiKey.isNotBlank() && p.apiKey == setting.apiKey
                is ProviderSetting.Google -> p.apiKey.isNotBlank() && p.apiKey == setting.apiKey
                is ProviderSetting.Claude -> p.apiKey.isNotBlank() && p.apiKey == setting.apiKey
            }
        }?.name ?: "未选择"
    }

    // ASR 候选模型列表（通过 listModels 拉取并过滤 modelId 含 'asr' 的项）
    var asrCandidateModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var loadingModels by remember { mutableStateOf(false) }

    // 已保存的模型 + 拉取到的 ASR 候选模型，用于下拉框去重展示
    val allDropdownModels = remember(asrCandidateModels, setting.model) {
        buildSet {
            if (setting.model.isNotBlank()) add(setting.model)
            addAll(asrCandidateModels)
        }.toList()
    }
    var modelExpanded by remember { mutableStateOf(false) }

    // 名称
    FormItem(
        label = { Text(stringResource(R.string.setting_asr_page_name)) },
        description = { Text(stringResource(R.string.setting_asr_page_name_description)) }
    ) {
        OutlinedTextField(
            value = setting.name,
            onValueChange = { onValueChange(setting.copy(name = it)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.setting_asr_page_name_placeholder)) }
        )
    }

    // ===== 从大模型 Provider 复制配置 =====
    FormItem(
        label = { Text("从大模型 Provider 复制配置") },
        description = { Text("自动同步 API Key / URL，并获取该 Provider 的 ASR 模型列表") }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ExposedDropdownMenuBox(
                expanded = providerExpanded,
                onExpandedChange = { providerExpanded = !providerExpanded }
            ) {
                OutlinedTextField(
                    value = selectedProviderLabel,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    placeholder = { Text("选择一个已配置的大模型 Provider") },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded)
                    }
                )
                ExposedDropdownMenu(
                    expanded = providerExpanded,
                    onDismissRequest = { providerExpanded = false }
                ) {
                    if (providers.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("暂无可用 Provider，请先在大模型页面配置") },
                            onClick = { providerExpanded = false }
                        )
                    } else {
                        providers.forEach { provider ->
                            DropdownMenuItem(
                                text = { Text(provider.name) },
                                onClick = {
                                    providerExpanded = false
                                    // 提取 apiKey / baseUrl
                                    val (providerApiKey, providerBaseUrl, providerType) = when (provider) {
                                        is ProviderSetting.OpenAI -> Triple(provider.apiKey, provider.baseUrl, "openai")
                                        is ProviderSetting.Google -> Triple(provider.apiKey, provider.baseUrl, "google")
                                        is ProviderSetting.Claude -> Triple(provider.apiKey, provider.baseUrl, "claude")
                                    }

                                    // 构造语音转录端点：优先使用 Whisper 兼容模式路径
                                    val transcriptionUrl = buildAsrTranscriptionUrl(providerType, providerBaseUrl)

                                    // 自动填到 OnlineASR，模型字段清空，避免把旧 Provider 的模型带过来
                                    asrCandidateModels = emptyList()
                                    onValueChange(
                                        setting.copy(
                                            apiKey = providerApiKey,
                                            apiUrl = transcriptionUrl,
                                            model = ""
                                        )
                                    )

                                    // 异步拉取模型列表并筛选 ASR 模型
                                    loadingModels = true
                                    scope.launch {
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                @Suppress("UNCHECKED_CAST")
                                                val providerImpl = providerManager.getProviderByType(provider) as me.rerere.ai.provider.Provider<ProviderSetting>
                                                providerImpl.listModels(provider)
                                            }
                                        }.onSuccess { models ->
                                            // 使用统一过滤函数：DashScope 白名单只保留 qwen+asr 多模态模型，
                                            // 其他 Provider 黑名单过滤 fun-asr/filetrans/paraformer
                                            val (asrModels, filteredCount) = filterAsrModels(
                                                models.map { it.modelId },
                                                provider
                                            )
                                            asrCandidateModels = asrModels
                                            val size = asrModels.size
                                            val filteredHint = if (filteredCount > 0) "（已自动过滤 $filteredCount 个不兼容本地文件上传的模型）" else ""
                                            toaster.show("发现 $size 个可用 ASR 模型，请在下方模型列表中选择 $filteredHint", type = ToastType.Success)
                                            if (asrModels.isEmpty() && models.isNotEmpty()) {
                                                toaster.show(
                                                    "未找到可本地调用的 ASR 模型（DashScope 仅 qwen3-asr-flash 非realtime 系列支持 OpenAI 兼容接口本地文件上传），请手动输入兼容模型",
                                                    type = ToastType.Warning
                                                )
                                            } else if (models.isEmpty()) {
                                                toaster.show(
                                                    "该 Provider 模型列表为空，请在大模型页面点击「获取模型列表」先同步",
                                                    type = ToastType.Warning
                                                )
                                            }
                                        }.onFailure { e ->
                                            toaster.show(
                                                "获取模型列表失败: ${e.message ?: "未知错误"}",
                                                type = ToastType.Error
                                            )
                                        }
                                        loadingModels = false
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // API URL
    FormItem(
        label = { Text("API URL") },
        description = { Text("兼容 OpenAI Whisper 接口格式的语音转录端点") }
    ) {
        OutlinedTextField(
            value = setting.apiUrl,
            onValueChange = { newUrl ->
                // 手动修改 API URL：清空模型字段，避免带入上一个 Provider 的模型或候选列表
                asrCandidateModels = emptyList()
                onValueChange(setting.copy(apiUrl = newUrl, model = ""))
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("https://api.siliconflow.cn/v1/audio/transcriptions") }
        )
    }

    // API Key
    FormItem(
        label = { Text("API Key") },
        description = { Text("用于鉴权的 API Key") }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { onValueChange(setting.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("sk-...") },
            visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                    Icon(
                        imageVector = if (apiKeyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = "切换 API Key 显示"
                    )
                }
            }
        )
    }

    // 模型：下拉 + 手动输入
    FormItem(
        label = { Text("模型") },
        description = { Text("从 Provider 同步模型后下拉选择；或直接手动输入") }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ExposedDropdownMenuBox(
                    expanded = modelExpanded,
                    onExpandedChange = { modelExpanded = !modelExpanded },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = setting.model,
                        onValueChange = { onValueChange(setting.copy(model = it)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                        singleLine = true,
                        placeholder = { Text("TeleAI/TeleSpeechASR") },
                        trailingIcon = {
                            if (loadingModels) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded)
                            }
                        }
                    )
                    ExposedDropdownMenu(
                        expanded = modelExpanded,
                        onDismissRequest = { modelExpanded = false }
                    ) {
                        if (allDropdownModels.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("暂无模型，请先选择上方 Provider 或手动输入") },
                                onClick = { modelExpanded = false }
                            )
                        } else {
                            allDropdownModels.forEach { modelId ->
                                DropdownMenuItem(
                                    text = { Text(modelId) },
                                    onClick = {
                                        onValueChange(setting.copy(model = modelId))
                                        modelExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                // 手动刷新 ASR 模型列表（使用当前已填的 API Key / URL 尝试从 Provider 推断拉取）
                IconButton(
                    onClick = {
                        val matchedProvider = providers.firstOrNull { p ->
                            when (p) {
                                is ProviderSetting.OpenAI -> p.apiKey.isNotBlank() && p.apiKey == setting.apiKey
                                is ProviderSetting.Google -> p.apiKey.isNotBlank() && p.apiKey == setting.apiKey
                                is ProviderSetting.Claude -> p.apiKey.isNotBlank() && p.apiKey == setting.apiKey
                            }
                        }
                        if (matchedProvider == null) {
                            toaster.show(
                                "API Key 未匹配到任何 Provider，请先从上方选择 Provider",
                                type = ToastType.Warning
                            )
                            return@IconButton
                        }
                        loadingModels = true
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    @Suppress("UNCHECKED_CAST")
                                    val providerImpl = providerManager.getProviderByType(matchedProvider) as me.rerere.ai.provider.Provider<ProviderSetting>
                                    providerImpl.listModels(matchedProvider)
                                }
                            }.onSuccess { models ->
                                // 同上方逻辑：使用统一过滤函数
                                val (asrModels, filteredCount) = filterAsrModels(
                                    models.map { it.modelId },
                                    matchedProvider
                                )
                                asrCandidateModels = asrModels
                                val size = asrModels.size
                                val filteredHint = if (filteredCount > 0) "（已过滤 $filteredCount 个不兼容模型）" else ""
                                toaster.show("发现 $size 个可用 ASR 模型 $filteredHint", type = ToastType.Success)
                                if (asrModels.isEmpty() && filteredCount > 0) {
                                    toaster.show(
                                        "注意：过滤后无可用模型，DashScope 仅 qwen3-asr-flash 非realtime 系列支持 OpenAI 兼容接口本地文件上传",
                                        type = ToastType.Warning
                                    )
                                }
                            }.onFailure { e ->
                                toaster.show("刷新失败: ${e.message ?: "未知错误"}", type = ToastType.Error)
                            }
                            loadingModels = false
                        }
                    }
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "刷新 ASR 模型列表")
                }
            }
            if (asrCandidateModels.isNotEmpty()) {
                Text(
                    text = "已从 Provider 发现 ${asrCandidateModels.size} 个 ASR 模型",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }

    // 语言
    val languages = remember {
        listOf(
            "zh" to "中文",
            "en" to "English",
            "ja" to "日本語",
            "ko" to "한국어",
            "fr" to "Français",
            "de" to "Deutsch",
            "es" to "Español",
            "ru" to "Русский"
        )
    }
    var langExpanded by remember { mutableStateOf(false) }
    val selectedLangLabel = languages.firstOrNull { it.first == setting.language }?.second ?: setting.language
    FormItem(
        label = { Text(stringResource(R.string.setting_asr_page_language)) },
        description = { Text(stringResource(R.string.setting_asr_page_language_description)) }
    ) {
        ExposedDropdownMenuBox(
            expanded = langExpanded,
            onExpandedChange = { langExpanded = !langExpanded }
        ) {
            OutlinedTextField(
                value = selectedLangLabel,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = langExpanded)
                }
            )
            ExposedDropdownMenu(
                expanded = langExpanded,
                onDismissRequest = { langExpanded = false }
            ) {
                languages.forEach { (code, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            onValueChange(setting.copy(language = code))
                            langExpanded = false
                        }
                    )
                }
            }
        }
    }

    // ===== 连接测试 =====
    AsrConnectionTestButton(setting = setting)
}

/**
 * 根据 Provider 类型 + baseUrl 构造语音转录端点。
 *
 * 规则：
 *  - DashScope 地域（host 含 dashscope.aliyuncs.com / qwencloud.com / compatible-mode）：
 *    DashScope 原生 **没有** `/audio/transcriptions` Whisper multipart 端点，只能走
 *    `compatible-mode/v1/chat/completions`，通过 Chat Completions 的 `input_audio` content
 *    类型上传 DataURL 编码的音频。
 *  - 其他通用 OpenAI 兼容 Provider（SiliconFlow / Groq / OpenAI 官方等）：
 *    baseUrl 拼接 `/audio/transcriptions`，使用标准 Whisper multipart 上传。
 */
private fun buildAsrTranscriptionUrl(providerType: String, baseUrl: String): String {
    val trimmedBase = baseUrl.trimEnd('/')
    val lower = trimmedBase.lowercase()

    val isDashScope = trimmedBase.contains("dashscope.aliyuncs.com", ignoreCase = true)
        || trimmedBase.contains("qwencloud.com", ignoreCase = true)
        || trimmedBase.contains("qianwenai.com", ignoreCase = true)
        || trimmedBase.contains("platform.qianwenai.com", ignoreCase = true)
        || trimmedBase.contains("compatible-mode", ignoreCase = true)
        || trimmedBase.contains("token-plan.cn-beijing.maas.aliyuncs.com", ignoreCase = true)
        || trimmedBase.contains("coding.dashscope.aliyuncs.com", ignoreCase = true)

    if (isDashScope) {
        // DashScope: 构造 compatible-mode 的 chat/completions 端点
        // 如果用户已经配置了 /compatible-mode/v1，直接追加 /chat/completions
        // 如果用户配置的是 /api/v1（原生），先插入 compatible-mode
        return when {
            lower.contains("/compatible-mode/") ->
                "$trimmedBase/chat/completions"
            // 原生 api/v1 → 转为 compatible-mode/v1/chat/completions
            lower.endsWith("/api/v1") ->
                trimmedBase.removeSuffix("/api/v1") + "/compatible-mode/v1/chat/completions"
            lower.endsWith("/v1") ->
                "$trimmedBase/chat/completions"
            else ->
                "$trimmedBase/compatible-mode/v1/chat/completions"
        }
    }

    // 通用：Google / OpenAI / Claude / 其他 OpenAI 兼容，默认 Whisper multipart
    // (Google 原生实际上也没有 /audio/transcriptions，但用户可以用兼容层)
    return "$trimmedBase/audio/transcriptions"
}

/**
 * 判断 Provider 是否**原生指向 DashScope（阿里云百炼/千问）官方 API 端点**。
 *
 * 注意：第三方转发 Provider（4sapi / SiliconFlow / OpenRouter / Groq 等）也可能
 * 包含 "qwen3-asr-flash" 等模型，但它们使用标准 Whisper multipart 或标准
 * OpenAI chat/completions audio 协议，不适用 DashScope 原生调用方式的限制。
 * 因此本函数只以 baseUrl host 是否属于 DashScope 官方域为判定标准。
 */
private fun isDashScopeProvider(provider: ProviderSetting): Boolean {
    val baseUrl = when (provider) {
        is ProviderSetting.OpenAI -> provider.baseUrl
        is ProviderSetting.Google -> provider.baseUrl
        is ProviderSetting.Claude -> provider.baseUrl
    }.lowercase()
    return baseUrl.contains("dashscope.aliyuncs.com")
        || baseUrl.contains("compatible-mode")
        || baseUrl.contains("qwencloud.com")
        || baseUrl.contains("qianwenai.com")
        || baseUrl.contains("token-plan.cn-beijing.maas.aliyuncs.com")
        || baseUrl.contains("coding.dashscope.aliyuncs.com")
}

/**
 * 根据模型 ID 判断是否是支持「OpenAI 兼容 Chat Completions + 本地文件上传」的 ASR 模型。
 *
 * DashScope 的 ASR 模型按调用方式分为四类（参见官方文档）：
 *
 * 1. OpenAI 兼容 chat/completions（本地 DataURL 上传）✅ —— 仅 qwen3-asr-flash（非 realtime）系列
 *    端点: /compatible-mode/v1/chat/completions
 *
 * 2. DashScope 原生 multimodal-generation 同步 ❌ —— qwen-audio-3.0-asr-flash / fun-asr-flash-*
 *    端点: /api/v1/services/aigc/multimodal-generation/generation
 *    响应结构非标准（无 choices，需 output.text / output.output.sentence.text），未实现
 *
 * 3. WebSocket 实时流式 ❌ —— *-realtime 系列（如 qwen3-asr-flash-realtime*）
 *    端点: wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=...
 *    必须用 WebSocket 事件流，不能 HTTP POST，未实现
 *
 * 4. 异步任务轮询 ❌ —— *-filetrans / fun-asr / paraformer-* 系列
 *    端点: /api/v1/services/audio/asr/transcription + /api/v1/tasks/{task_id}
 *    需公网 URL + 任务轮询，不支持本地文件，未实现
 *
 * 因此 DashScope Provider 白名单严格限定为「qwen3-asr 且非 realtime」。
 * 其他 Provider（SiliconFlow / OpenAI / Groq 等）使用宽松黑名单过滤已知不兼容的。
 *
 * @return Pair(filtered models, count of filtered out)
 */
private fun filterAsrModels(
    allModels: List<String>,
    provider: ProviderSetting
): Pair<List<String>, Int> {
    val isDashScope = isDashScopeProvider(provider)
    val asrRaw = allModels.filter { it.contains("asr", ignoreCase = true) }

    val filtered = if (isDashScope) {
        // DashScope 白名单：只保留 qwen3-asr-flash（非 realtime）系列
        // 原因：只有这一类支持 OpenAI 兼容 chat/completions + 本地 DataURL 上传
        asrRaw.filter { modelId ->
            val lower = modelId.lowercase()
            lower.contains("qwen3-asr")
                && !lower.contains("realtime")
                && !lower.contains("filetrans")
                && !lower.contains("paraformer")
                && !lower.contains("fun-asr")
        }
    } else {
        // 其他 Provider：黑名单过滤已知不兼容的模型类型
        asrRaw.filter { modelId ->
            val lower = modelId.lowercase()
            !lower.contains("fun-asr")
                && !lower.contains("filetrans")
                && !lower.contains("paraformer")
        }
    }
    return filtered to (asrRaw.size - filtered.size)
}

@Composable
private fun LocalSenseVoiceASRConfiguration(
    setting: ASRProviderSetting.LocalSenseVoiceASR,
    onValueChange: (ASRProviderSetting) -> Unit
) {
    val modelManager = koinInject<SenseVoiceModelManager>()
    var modelReady by remember { mutableStateOf(modelManager.isModelReady()) }

    // 名称
    FormItem(
        label = { Text(stringResource(R.string.setting_asr_page_name)) },
        description = { Text(stringResource(R.string.setting_asr_page_name_description)) }
    ) {
        OutlinedTextField(
            value = setting.name,
            onValueChange = { onValueChange(setting.copy(name = it)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.setting_asr_page_name_placeholder)) }
        )
    }

    // ===== 内置模型状态 =====
    FormItem(
        label = { Text("内置模型") },
        description = {
            val statusText = if (modelReady) {
                val sizeMb = modelManager.getModelSize() / (1024 * 1024)
                "已就绪 (${sizeMb}MB)，完全离线推理，无需联网"
            } else {
                "模型未就绪，请确认知安装包内置模型已正确打包"
            }
            Text(statusText)
        }
    ) {
        Icon(
            imageVector = if (modelReady) Icons.Rounded.GraphicEq else Icons.Rounded.CloudOff,
            contentDescription = null,
            tint = if (modelReady) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
    }

    // ===== 识别语言 =====
    val languages = remember {
        listOf(
            "auto" to "自动检测",
            "zh" to "中文",
            "en" to "English",
            "ja" to "日本語",
            "ko" to "한국어",
            "yue" to "粤语"
        )
    }
    var langExpanded by remember { mutableStateOf(false) }
    val selectedLangLabel = languages.firstOrNull { it.first == setting.language }?.second ?: setting.language
    FormItem(
        label = { Text(stringResource(R.string.setting_asr_page_language)) },
        description = { Text(stringResource(R.string.setting_asr_page_language_description)) }
    ) {
        ExposedDropdownMenuBox(
            expanded = langExpanded,
            onExpandedChange = { langExpanded = !langExpanded }
        ) {
            OutlinedTextField(
                value = selectedLangLabel,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = langExpanded)
                }
            )
            ExposedDropdownMenu(
                expanded = langExpanded,
                onDismissRequest = { langExpanded = false }
            ) {
                languages.forEach { (code, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            onValueChange(setting.copy(language = code))
                            langExpanded = false
                        }
                    )
                }
            }
        }
    }

    // ===== 逆文本正则化 =====
    FormItem(
        label = { Text("逆文本正则化") },
        description = { Text("将数字、日期等转换为规范文本格式（如 123 → 一百二十三）") }
    ) {
        HapticSwitch(
            checked = setting.useItn,
            onCheckedChange = { onValueChange(setting.copy(useItn = it)) }
        )
    }

    // ===== 推理线程数 =====
    val threadOptions = remember {
        listOf(1 to "1 线程（省电）", 2 to "2 线程（推荐）", 4 to "4 线程（最快）")
    }
    var threadsExpanded by remember { mutableStateOf(false) }
    val selectedThreadsLabel = threadOptions.firstOrNull { it.first == setting.numThreads }?.second
        ?: "${setting.numThreads} 线程"
    FormItem(
        label = { Text("推理线程数") },
        description = { Text("更多线程推理更快但更耗电，通话场景建议 2 线程") }
    ) {
        ExposedDropdownMenuBox(
            expanded = threadsExpanded,
            onExpandedChange = { threadsExpanded = !threadsExpanded }
        ) {
            OutlinedTextField(
                value = selectedThreadsLabel,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = threadsExpanded)
                }
            )
            ExposedDropdownMenu(
                expanded = threadsExpanded,
                onDismissRequest = { threadsExpanded = false }
            ) {
                threadOptions.forEach { (count, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            onValueChange(setting.copy(numThreads = count))
                            threadsExpanded = false
                        }
                    )
                }
            }
        }
    }

    // ===== 连接测试 =====
    AsrConnectionTestButton(setting = setting)
}

/**
 * ASR 连接测试按钮（支持 OnlineASR / LocalSenseVoiceASR）.
 *
 * 按住按钮录制一段测试音频，松开后自动发送到 ASR 服务进行识别。
 * 识别成功时在按钮下方展示识别结果；失败时通过顶部 Toast 提示错误信息。
 */
@Composable
private fun AsrConnectionTestButton(
    setting: ASRProviderSetting
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val asrManager = koinInject<ASRManager>()

    var hasRecordPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasRecordPermission = granted
        if (!granted) {
            toaster.show("需要麦克风权限才能录制测试音频", type = ToastType.Error)
        }
    }

    val recorder = remember { VoiceRecorderController(context) }
    DisposableEffect(recorder) {
        onDispose { recorder.release() }
    }

    val recorderState by recorder.state.collectAsState()
    val durationMs by recorder.durationMs.collectAsState()
    val isRecording = recorderState == VoiceRecorderState.Recording

    var testState: UiState<String> by remember { mutableStateOf(UiState.Idle) }

    // 录音中的脉冲动画
    val pulseTransition = rememberInfiniteTransition(label = "asr_test_pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse_alpha"
    )

    FormItem(
        label = { Text("连接测试") },
        description = { Text("按住按钮录制一段测试音频，松开后自动发送到 ASR 服务进行识别") }
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = when {
                isRecording -> MaterialTheme.colorScheme.errorContainer
                testState is UiState.Success -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHigh
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .pointerInput(setting) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)

                        // 权限检查
                        if (!hasRecordPermission) {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            return@awaitEachGesture
                        }
                        // 识别中时禁止再次录音
                        if (testState is UiState.Loading) return@awaitEachGesture

                        testState = UiState.Idle
                        try {
                            recorder.start()
                        } catch (e: Exception) {
                            toaster.show(e.message ?: "录音启动失败", type = ToastType.Error)
                            return@awaitEachGesture
                        }

                        // 等待手指抬起
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: continue
                            if (change.changedToUp()) {
                                val result = recorder.stop()
                                if (result == null) {
                                    toaster.show("录音太短，请长按按钮录制", type = ToastType.Warning)
                                } else {
                                    testState = UiState.Loading
                                    scope.launch {
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                asrManager.transcribeFile(
                                                    providerSetting = setting,
                                                    context = context,
                                                    uri = result.uri
                                                )
                                            }
                                        }.onSuccess { text ->
                                            val recognizedText = text.trim()
                                            if (recognizedText.isNotBlank()) {
                                                testState = UiState.Success(recognizedText)
                                                toaster.show("识别成功", type = ToastType.Success)
                                            } else {
                                                testState = UiState.Error(
                                                    RuntimeException("ASR 返回空结果，请检查模型配置或重试")
                                                )
                                                toaster.show("ASR 返回空结果", type = ToastType.Warning)
                                            }
                                        }.onFailure { e ->
                                            testState = UiState.Error(e)
                                            toaster.show(
                                                "ASR 测试失败: ${e.message ?: "未知错误"}",
                                                type = ToastType.Error
                                            )
                                        }
                                        // 清理临时录音文件
                                        result.file.delete()
                                    }
                                }
                                break
                            }
                        }
                    }
                }
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 16.dp)
            ) {
                when {
                    testState is UiState.Loading -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = "识别中...",
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
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = pulseAlpha)
                            )
                            val seconds = durationMs / 1000.0
                            Text(
                                text = "录音中 ${"%.1f".format(seconds)}s",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
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
                                text = "按住录制测试音频",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    // 识别结果展示
    when (val st = testState) {
        is UiState.Success -> {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "识别结果",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = st.data,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
        is UiState.Error -> {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "识别失败",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = st.error.message ?: "未知错误",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
        else -> {}
    }
}
