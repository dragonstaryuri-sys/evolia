package me.rerere.rikkahub.ui.pages.setting

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DEFAULT_WAKE_WORDS
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.service.voice.WakeWordModelManager
import me.rerere.rikkahub.service.voice.WakeWordService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupItem
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.components.ui.ToastType
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingWakeWordPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val modelManager = koinInject<WakeWordModelManager>()
    val chatService: me.rerere.rikkahub.service.ChatService = koinInject()
    val voiceCallManager = koinInject<me.rerere.rikkahub.service.voice.VoiceCallManager>()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val downloadState by modelManager.downloadState.collectAsState()
    var modelReady by remember { mutableStateOf(modelManager.isModelReady()) }
    var customKeywords by remember(settings.customWakeWords) {
        mutableStateOf(settings.customWakeWords)
    }
    var sensitivity by remember(settings.wakeWordSensitivity) {
        mutableFloatStateOf(settings.wakeWordSensitivity)
    }
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
    }

    val bundled by remember { mutableStateOf(modelManager.hasBundledAssets()) }
    val isPreparing = when (downloadState) {
        is WakeWordModelManager.DownloadState.CopyingFromAssets,
        is WakeWordModelManager.DownloadState.DownloadingArchive,
        is WakeWordModelManager.DownloadState.ExtractingArchive -> true
        else -> false
    }

    // 下载/拷贝状态变化时刷新模型就绪状态
    LaunchedEffect(downloadState) {
        modelReady = modelManager.isModelReady()
    }

    /** 开启唤醒前自动确保模型就绪（assets 拷贝优先，下载兜底），成功后再启动服务 */
    fun enableWakeWithModel() {
        if (!hasRecordPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        vm.updateSettings(settings.copy(enableWakeWord = true))
        scope.launch {
            val result = modelManager.ensureReady()
            modelReady = modelManager.isModelReady()
            if (result.isSuccess && modelReady) {
                WakeWordService.start(context)
            }
        }
    }

    Scaffold(
        topBar = {
            OneUITopAppBar(
                title = stringResource(R.string.setting_wake_word_page_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = { BackButton() }
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding,
        ) {
            // ===== 唤醒开关 =====
            item {
                SettingsGroup(title = stringResource(R.string.setting_wake_word_group_basic)) {
                    SettingGroupItem(
                        title = stringResource(R.string.setting_wake_word_enable),
                        subtitle = if (settings.enableWakeWord) {
                            stringResource(R.string.setting_wake_word_enable_on)
                        } else {
                            stringResource(R.string.setting_wake_word_enable_off)
                        },
                        icon = { Icon(Icons.Rounded.Mic, null, modifier = Modifier.size(20.dp)) },
                        trailing = {
                            HapticSwitch(
                                checked = settings.enableWakeWord,
                                onCheckedChange = { enabled ->
                                    if (enabled) {
                                        enableWakeWithModel()
                                    } else {
                                        vm.updateSettings(settings.copy(enableWakeWord = false))
                                        WakeWordService.stop(context)
                                    }
                                }
                            )
                        }
                    )
                    // ====== 唤醒接听智能体 ======
                    var assistantDropdownExpanded by remember { mutableStateOf(false) }
                    val currentAssistant = settings.wakeWordAssistantId
                        ?.let { id -> settings.assistants.find { it.id == id } }
                    val fallbackAssistant = settings.getCurrentAssistant()
                    SettingGroupItem(
                        title = stringResource(R.string.setting_wake_word_assistant),
                        subtitle = currentAssistant?.name
                            ?: stringResource(
                                R.string.setting_wake_word_assistant_fallback,
                                fallbackAssistant.name
                            ),
                        icon = {
                            Icon(Icons.Rounded.Person, null, modifier = Modifier.size(20.dp))
                        }
                    )
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        AssistChip(
                            onClick = { assistantDropdownExpanded = true },
                            label = {
                                Text(
                                    text = currentAssistant?.name
                                        ?: stringResource(
                                            R.string.setting_wake_word_assistant_follow,
                                            fallbackAssistant.name
                                        ),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            leadingIcon = {
                                val assistant = currentAssistant ?: fallbackAssistant
                                UIAvatar(
                                    name = assistant.name,
                                    value = assistant.avatar,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            trailingIcon = {
                                Icon(
                                    Icons.Rounded.ArrowDropDown,
                                    null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                        DropdownMenu(
                            expanded = assistantDropdownExpanded,
                            onDismissRequest = { assistantDropdownExpanded = false }
                        ) {
                            // "跟随当前主智能体"选项
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            R.string.setting_wake_word_assistant_follow,
                                            fallbackAssistant.name
                                        )
                                    )
                                },
                                leadingIcon = {
                                    UIAvatar(
                                        name = fallbackAssistant.name,
                                        value = fallbackAssistant.avatar,
                                        modifier = Modifier.size(24.dp)
                                    )
                                },
                                onClick = {
                                    scope.launch {
                                        vm.updateSettings(
                                            settings.copy(wakeWordAssistantId = null)
                                        )
                                    }
                                    assistantDropdownExpanded = false
                                    toaster.show(
                                        message = context.getString(
                                            R.string.setting_wake_word_assistant_updated,
                                            fallbackAssistant.name
                                        ),
                                        type = ToastType.Success
                                    )
                                }
                            )
                            settings.assistants.forEach { assistant ->
                                DropdownMenuItem(
                                    text = { Text(assistant.name) },
                                    leadingIcon = {
                                        UIAvatar(
                                            name = assistant.name,
                                            value = assistant.avatar,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    },
                                    onClick = {
                                        scope.launch {
                                            vm.updateSettings(
                                                settings.copy(
                                                    wakeWordAssistantId = assistant.id
                                                )
                                            )
                                        }
                                        assistantDropdownExpanded = false
                                        toaster.show(
                                            message = context.getString(
                                                R.string.setting_wake_word_assistant_updated,
                                                assistant.name
                                            ),
                                            type = ToastType.Success
                                        )
                                    }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.setting_wake_word_assistant_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ===== 模型管理 =====
            item {
                SettingsGroup(title = stringResource(R.string.setting_wake_word_group_model)) {
                    // 模型状态
                    SettingGroupItem(
                        title = stringResource(R.string.setting_wake_word_model_status),
                        subtitle = when {
                            modelReady -> if (bundled) {
                                stringResource(R.string.setting_wake_word_model_builtin)
                            } else {
                                stringResource(R.string.setting_wake_word_model_ready)
                            }
                            downloadState is WakeWordModelManager.DownloadState.CopyingFromAssets -> {
                                val st = downloadState as WakeWordModelManager.DownloadState.CopyingFromAssets
                                stringResource(
                                    R.string.setting_wake_word_model_copying,
                                    st.fileIndex,
                                    st.totalFiles,
                                    st.fileName
                                )
                            }
                            downloadState is WakeWordModelManager.DownloadState.DownloadingArchive -> {
                                val dl = downloadState as WakeWordModelManager.DownloadState.DownloadingArchive
                                val pct = (dl.progress * 100).toInt().coerceIn(0, 100)
                                stringResource(R.string.setting_wake_word_model_downloading_archive, pct)
                            }
                            downloadState is WakeWordModelManager.DownloadState.ExtractingArchive -> {
                                stringResource(R.string.setting_wake_word_model_extracting)
                            }
                            downloadState is WakeWordModelManager.DownloadState.Error -> {
                                stringResource(R.string.setting_wake_word_model_error)
                            }
                            bundled -> stringResource(R.string.setting_wake_word_model_bundled_not_ready)
                            else -> stringResource(R.string.setting_wake_word_model_not_downloaded)
                        },
                        icon = { Icon(Icons.Rounded.GraphicEq, null, modifier = Modifier.size(20.dp)) },
                        trailing = {
                            if (isPreparing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    if (modelReady) Icons.Rounded.AutoAwesome else Icons.Rounded.CloudDownload,
                                    contentDescription = null,
                                    tint = if (modelReady) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    )

                    // 下载按钮：模型未就绪且不在准备中，且未内置 assets（需要走网络）时显示
                    if (!modelReady && !isPreparing && !bundled) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        modelManager.downloadModel()
                                        modelReady = modelManager.isModelReady()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Rounded.CloudDownload, null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.setting_wake_word_download))
                            }
                        }
                    }

                    // 手动准备按钮：模型未就绪且不在准备中，但已内置 assets 时显示
                    if (!modelReady && !isPreparing && bundled) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        modelManager.copyFromAssets()
                                        modelReady = modelManager.isModelReady()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Rounded.AutoAwesome, null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.setting_wake_word_prepare_builtin))
                            }
                        }
                    }

                    // 删除按钮（模型已就绪时显示）
                    if (modelReady) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    modelManager.deleteModel()
                                    modelReady = false
                                    // 模型删除后关闭唤醒
                                    if (settings.enableWakeWord) {
                                        WakeWordService.stop(context)
                                        vm.updateSettings(settings.copy(enableWakeWord = false))
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Icon(Icons.Rounded.DeleteOutline, null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.setting_wake_word_delete_model))
                            }
                        }
                    }
                }
            }

            // ===== 唤醒词设置（仅在模型就绪时显示）=====
            item {
                AnimatedVisibility(
                    visible = modelReady,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    SettingsGroup(title = stringResource(R.string.setting_wake_word_group_wake)) {
                        // 灵敏度
                        SettingGroupItem(
                            title = stringResource(R.string.setting_wake_word_sensitivity),
                            subtitle = stringResource(R.string.setting_wake_word_sensitivity_desc),
                            icon = { Icon(Icons.Rounded.GraphicEq, null, modifier = Modifier.size(20.dp)) }
                        )

                        // 灵敏度滑块
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Slider(
                                value = sensitivity,
                                onValueChange = { sensitivity = it },
                                onValueChangeFinished = {
                                    vm.updateSettings(settings.copy(wakeWordSensitivity = sensitivity))
                                },
                                valueRange = 0f..1f
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = stringResource(R.string.setting_wake_word_sensitivity_low),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = stringResource(R.string.setting_wake_word_sensitivity_high),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // 自定义唤醒词输入
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.setting_wake_word_custom),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.setting_wake_word_custom_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = customKeywords,
                                onValueChange = { customKeywords = it },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                                maxLines = 5,
                                placeholder = { Text(DEFAULT_WAKE_WORDS) }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        customKeywords = DEFAULT_WAKE_WORDS
                                        vm.updateSettings(settings.copy(customWakeWords = DEFAULT_WAKE_WORDS))
                                    }
                                ) {
                                    Text(stringResource(R.string.setting_wake_word_reset))
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        vm.updateSettings(settings.copy(customWakeWords = customKeywords))
                                        // 如果唤醒已开启，重启服务以加载新唤醒词
                                        if (settings.enableWakeWord) {
                                            WakeWordService.stop(context)
                                            WakeWordService.start(context)
                                        }
                                    }
                                ) {
                                    Text(stringResource(R.string.setting_wake_word_apply))
                                }
                            }
                        }
                    }
                }
            }

            // ===== 测试 & 调试 =====
            item {
                SettingsGroup(title = stringResource(R.string.setting_wake_word_group_test)) {
                    SettingGroupItem(
                        title = stringResource(R.string.setting_wake_word_test_title),
                        subtitle = stringResource(R.string.setting_wake_word_test_desc),
                        icon = { Icon(Icons.Rounded.Bolt, null, modifier = Modifier.size(20.dp)) }
                    )
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        // 模拟唤醒按钮：直接走 WakeWordService 里 handleWakeWordTrigger 的同等链路（但在 UI 线程启动）
                        var triggerLoading by remember { mutableStateOf(false) }
                        FilledTonalButton(
                            onClick = {
                                if (triggerLoading) return@FilledTonalButton
                                val selectedAssistantId = settings.wakeWordAssistantId
                                val assistant = selectedAssistantId
                                    ?.let { id -> settings.assistants.find { it.id == id } }
                                    ?: settings.getCurrentAssistant()
                                triggerLoading = true
                                scope.launch {
                                    runCatching {
                                        val conversationId = Uuid.random()
                                        chatService.initializeConversation(
                                            conversationId = conversationId,
                                            targetAssistantId = assistant.id,
                                            skipAutoArchive = true
                                        )
                                        voiceCallManager.startCall(conversationId)
                                        val intent = android.content.Intent(
                                            context,
                                            me.rerere.rikkahub.RouteActivity::class.java
                                        ).apply {
                                            flags =
                                                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                                            putExtra("conversationId", conversationId.toString())
                                        }
                                        context.startActivity(intent)
                                    }.onFailure { t ->
                                        toaster.show(
                                            message = context.getString(
                                                R.string.setting_wake_word_test_failed,
                                                t.message ?: ""
                                            ),
                                            type = ToastType.Error
                                        )
                                    }.onSuccess {
                                        toaster.show(
                                            message = context.getString(
                                                R.string.setting_wake_word_test_success,
                                                assistant.name
                                            ),
                                            type = ToastType.Success
                                        )
                                    }
                                    triggerLoading = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !triggerLoading
                        ) {
                            if (triggerLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.setting_wake_word_test_launching))
                            } else {
                                Icon(Icons.Rounded.PlayArrow, null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.setting_wake_word_test_trigger))
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.setting_wake_word_test_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ===== 使用说明 =====
            item {
                Column(modifier = Modifier.padding(16.dp)) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.HelpOutline,
                                null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.setting_wake_word_help_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.setting_wake_word_help_content),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
