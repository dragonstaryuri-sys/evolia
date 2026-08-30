package me.rerere.rikkahub.ui.pages.backup

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.sync.WebDavBackupItem
import me.rerere.rikkahub.data.sync.WebdavSync
import me.rerere.rikkahub.common.JsonInstant
import me.rerere.rikkahub.service.BackupWorker
import me.rerere.rikkahub.R
import me.rerere.rikkahub.utils.UiState
import java.io.File

private const val TAG = "BackupVM"

class BackupVM(
    private val settingsStore: SettingsStore,
    private val webdavSync: WebdavSync,
    private val context: Context,
) : ViewModel() {
    val settings = settingsStore.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = Settings.dummy()
    )

    val webDavBackupItems = MutableStateFlow<UiState<List<WebDavBackupItem>>>(UiState.Idle)

    /**
     * 页面点击「立即备份」后的前台同步状态，用于给 BackupPage 顶部 Toast 反馈。
     * WorkManager 的 BackupWorker 继续负责后台/启动时自动备份，并走系统通知作为兜底。
     */
    val manualBackupStatus = MutableStateFlow<ManualBackupStatus>(ManualBackupStatus.Idle)

    sealed interface ManualBackupStatus {
        data object Idle : ManualBackupStatus
        data object Running : ManualBackupStatus
        data object Success : ManualBackupStatus
        data class Failed(val reason: String) : ManualBackupStatus
    }

    init {
        loadBackupFileItems()
    }

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    fun loadBackupFileItems() {
        viewModelScope.launch {
            runCatching {
                webDavBackupItems.emit(UiState.Loading)
                webDavBackupItems.emit(
                    value = UiState.Success(
                        data = webdavSync.listBackupFiles(
                            webDavConfig = settings.value.webDavConfig
                        ).sortedByDescending { it.lastModified }
                    )
                )
            }.onFailure {
                webDavBackupItems.emit(UiState.Error(it))
            }
        }
    }

    suspend fun testWebDav() {
        webdavSync.testWebdav(settings.value.webDavConfig)
    }

    fun backup() {
        val workRequest = OneTimeWorkRequestBuilder<BackupWorker>()
            .addTag("manual_backup")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "manual_backup",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    /**
     * 前台用户点击「立即备份」时调用：
     * - 在当前页面内同步执行，保证成功/失败能立刻通过 UI Toast 给出具体原因（尤其 403）。
     * - 仍会触发一次 WorkManager（便于统一重试/通知逻辑），但最终 UI 以本函数返回为准。
     */
    fun backupNow(scope: kotlinx.coroutines.CoroutineScope) {
        if (manualBackupStatus.value == ManualBackupStatus.Running) return
        scope.launch(context = viewModelScope.coroutineContext + kotlinx.coroutines.Dispatchers.IO) {
            manualBackupStatus.emit(ManualBackupStatus.Running)
            // 保持与 BackupWorker 一致的调度：先把 WorkManager 也 enqueue 一份，
            // 以防用户退出页面 / 前台协程被系统回收后仍能靠 Worker 通知兜底。
            backup()
            val result = runCatching {
                val config = settings.value.webDavConfig
                require(config.url.isNotBlank()) {
                    context.getString(R.string.backup_page_webdav_config_empty)
                }
                webdavSync.backupToWebDav(config)
            }
            result.onSuccess {
                manualBackupStatus.emit(ManualBackupStatus.Success)
                runCatching { loadBackupFileItems() }
            }.onFailure { err ->
                Log.e(TAG, "manual backup failed", err)
                val reason = err.message
                    ?: context.getString(R.string.backup_page_unknown_error)
                manualBackupStatus.emit(ManualBackupStatus.Failed(reason = reason))
            }
        }
    }

    suspend fun restore(item: WebDavBackupItem): WebdavSync.RestoreResult {
        return webdavSync.restoreFromWebDav(webDavConfig = settings.value.webDavConfig, item = item)
    }

    suspend fun deleteWebDavBackupFile(item: WebDavBackupItem) {
        webdavSync.deleteWebDavBackupFile(settings.value.webDavConfig, item)
    }

    suspend fun exportToFile(): File {
        return webdavSync.prepareBackupFile(settings.value.webDavConfig.copy())
    }

    suspend fun restoreFromLocalFile(file: File): WebdavSync.RestoreResult {
        return webdavSync.restoreFromLocalFile(file, settings.value.webDavConfig)
    }

    fun restartApp(context: android.content.Context) {
        val packageManager = context.packageManager
        val intent = packageManager.getLaunchIntentForPackage(context.packageName)
        val componentName = intent?.component
        val mainIntent = android.content.Intent.makeRestartActivityTask(componentName)
        context.startActivity(mainIntent)
        kotlin.system.exitProcess(0)
    }

    fun restoreFromChatBox(file: File) {
        val importProviders = arrayListOf<ProviderSetting>()

        val jsonElements = JsonInstant.parseToJsonElement(file.readText()).jsonObject
        val settingsObj = jsonElements["settings"]?.jsonObject
        if (settingsObj != null) {
            settingsObj["providers"]?.jsonObject?.let { providers ->
                providers["openai"]?.jsonObject?.let { openai ->
                    val apiHost = openai["apiHost"]?.jsonPrimitive?.contentOrNull ?: "https://api.openai.com"
                    val apiKey = openai["apiKey"]?.jsonPrimitive?.contentOrNull ?: ""
                    val models = openai["models"]?.jsonArray?.map { element ->
                        val modelId = element.jsonObject["modelId"]?.jsonPrimitive?.contentOrNull ?: ""
                        val capabilities =
                            element.jsonObject["capabilities"]?.jsonArray?.map { it.jsonPrimitive.contentOrNull }
                                ?: emptyList()
                        Model(
                            modelId = modelId,
                            displayName = modelId,
                            inputModalities = buildList {
                                if (capabilities.contains("vision")) {
                                    add(Modality.IMAGE)
                                }
                            },
                            abilities = buildList {
                                if (capabilities.contains("tool_use")) {
                                    add(ModelAbility.TOOL)
                                }
                                if (capabilities.contains("reasoning")) {
                                    add(ModelAbility.REASONING)
                                }
                            }
                        )
                    } ?: emptyList()
                    if (apiKey.isNotBlank()) importProviders.add(
                        ProviderSetting.OpenAI(
                            name = "OpenAI",
                            baseUrl = "$apiHost/v1",
                            apiKey = apiKey,
                            models = models,
                        )
                    )
                }
                providers["claude"]?.jsonObject?.let { claude ->
                    val apiHost =
                        claude["apiHost"]?.jsonPrimitive?.contentOrNull ?: "https://api.anthropic.com"
                    val apiKey = claude["apiKey"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (apiKey.isNotBlank()) importProviders.add(
                        ProviderSetting.Claude(
                            name = "Claude",
                            baseUrl = "${apiHost}/v1",
                            apiKey = apiKey,
                        )
                    )
                }
                providers["gemini"]?.jsonObject?.let { gemini ->
                    val apiHost = gemini["apiHost"]?.jsonPrimitive?.contentOrNull
                        ?: "https://generativelanguage.googleapis.com"
                    val apiKey = gemini["apiKey"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (apiKey.isNotBlank()) importProviders.add(
                        ProviderSetting.Google(
                            name = "Gemini",
                            baseUrl = "$apiHost/v1beta",
                            apiKey = apiKey,
                        )
                    )
                }
            }
        }

        Log.i(TAG, "restoreFromChatBox: import ${importProviders.size} providers: $importProviders")

        updateSettings(
            settings.value.copy(
                providers = importProviders + settings.value.providers,
            )
        )
    }
}
