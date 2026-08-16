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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import me.rerere.rikkahub.service.voice.WakeWordModelManager
import me.rerere.rikkahub.service.voice.WakeWordService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupItem
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun SettingWakeWordPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val modelManager = koinInject<WakeWordModelManager>()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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
    val isPreparing = downloadState is WakeWordModelManager.DownloadState.Downloading
            || downloadState is WakeWordModelManager.DownloadState.CopyingFromAssets

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
                            downloadState is WakeWordModelManager.DownloadState.Downloading -> {
                                val dl = downloadState as WakeWordModelManager.DownloadState.Downloading
                                stringResource(
                                    R.string.setting_wake_word_model_downloading,
                                    dl.fileIndex,
                                    dl.totalFiles,
                                    dl.fileName
                                )
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
