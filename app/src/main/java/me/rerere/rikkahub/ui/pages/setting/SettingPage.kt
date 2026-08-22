package me.rerere.rikkahub.ui.pages.setting

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.isNotConfigured
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.hooks.rememberColorMode
import me.rerere.rikkahub.ui.theme.ColorMode
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.utils.countChatFiles
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupItem
import org.koin.androidx.compose.koinViewModel
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.UpdateInfo
import org.koin.compose.koinInject
import okhttp3.OkHttpClient
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.components.ui.UpdateDialog
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.ui.components.ui.ChatModelWarningBanner
import me.rerere.rikkahub.ui.components.ui.ProviderConfigWarningCard
import me.rerere.rikkahub.common.countImageFiles
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.components.ui.ToastType


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingPage(vm: SettingVM = koinViewModel()) {
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val lazyListState = rememberLazyListState()
    val toaster = LocalToaster.current

    var showClearCacheDialog by remember { mutableStateOf(false) }

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text(stringResource(R.string.setting_page_clear_cache_confirm_title)) },
            text = { Text(stringResource(R.string.setting_page_clear_cache_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearCacheDialog = false
                        vm.clearCache {
                            toaster.show(
                                message = "缓存清理完成", // 这里也可以用 stringResource，但异步回调里建议直接传或用 context
                                type = ToastType.Success
                            )
                        }
                    }
                ) {
                    Text(stringResource(R.string.confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings),
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    if (settings.developerMode) {
                        IconButton(
                            onClick = {
                                navController.navigate(Screen.Developer)
                            }
                        ) {
                            Icon(Icons.Rounded.Build, stringResource(R.string.setting_display_page_developer_mode))
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = lazyListState,
            contentPadding = innerPadding,
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            val isNotConfigured = settings.isNotConfigured()
            if (isNotConfigured) {
                item {
                    ProviderConfigWarningCard(navController)
                }
            } else {
                val noDefaultChatModel = settings.findModelById(settings.chatModelId) == null
                if (noDefaultChatModel) {
                    item {
                        // 注意：这里建议加上 padding，保持和 SettingPage 的样式一致
                        Box(modifier = Modifier.padding(horizontal = 0.dp)) {
                            ChatModelWarningBanner(navController)
                        }
                    }
                }
            }

            // User Profile Header (WeChat Style)
            item {
                Surface(
                    onClick = { navController.navigate(Screen.SettingUserProfile) },
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.extraLarge
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        UIAvatar(
                            name = settings.displaySetting.userNickname,
                            value = settings.displaySetting.userAvatar,
                            modifier = Modifier.size(64.dp),
                            onClick = { navController.navigate(Screen.SettingUserProfile) }
                        )

                        Spacer(Modifier.width(20.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = settings.displaySetting.userNickname.ifBlank { stringResource(R.string.user_default_name) },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            if (settings.displaySetting.userEmail.isNotBlank()) {
                                Text(
                                    text = settings.displaySetting.userEmail,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text(
                                    text = "点击修改个人资料",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Icon(
                            Icons.Rounded.ChevronRight,
                            null,
                            tint = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            if (settings.isNotConfigured()) {
                item {
                    ProviderConfigWarningCard(navController)
                }
            }

            // Update Available Banner
            item {
                UpdateAvailableBanner(
                    checkForUpdates = settings.displaySetting.checkForUpdates
                )
            }

            // General Settings Section
            item {
                SettingsGroup(
                    title = stringResource(R.string.setting_page_general_settings)
                ) {
                    var colorMode by rememberColorMode()
                    SettingGroupItem(
                        title = stringResource(R.string.setting_page_color_mode),
                        icon = { Icon(Icons.Rounded.InvertColors, null, modifier = Modifier.size(20.dp)) },
                        trailing = {
                            Select(
                                options = ColorMode.entries,
                                selectedOption = colorMode,
                                onOptionSelected = {
                                    colorMode = it
                                },
                                optionToString = {
                                    when (it) {
                                        ColorMode.SYSTEM -> stringResource(R.string.setting_page_color_mode_system)
                                        ColorMode.LIGHT -> stringResource(R.string.setting_page_color_mode_light)
                                        ColorMode.DARK -> stringResource(R.string.setting_page_color_mode_dark)
                                    }
                                },
                                modifier = Modifier.wrapContentWidth()
                            )
                        }
                    )
                    SettingGroupItem(
                        title = stringResource(R.string.setting_page_display_setting),
                        subtitle = stringResource(R.string.setting_page_display_setting_desc),
                        icon = { Icon(Icons.Rounded.DesktopWindows, null, modifier = Modifier.size(20.dp)) },
                        onClick = { navController.navigate(Screen.SettingDisplay) }
                    )

                    // Language Selector
                    val currentLocales = AppCompatDelegate.getApplicationLocales()
                    val currentTag = if (currentLocales.isEmpty) "" else currentLocales.toLanguageTags()
                    val languages = listOf(
                        LanguageOption(stringResource(R.string.language_follow_system), ""),
                        LanguageOption(stringResource(R.string.language_simplified_chinese), "zh-CN"),
                        LanguageOption(stringResource(R.string.language_traditional_chinese), "zh-TW"),
                        LanguageOption(stringResource(R.string.language_english), "en"),
                        LanguageOption(stringResource(R.string.language_japanese), "ja"),
                        LanguageOption(stringResource(R.string.language_korean), "ko"),
                        LanguageOption(stringResource(R.string.language_french), "fr"),
                        LanguageOption(stringResource(R.string.language_german), "de"),
                        LanguageOption(stringResource(R.string.language_spanish), "es"),
                        LanguageOption(stringResource(R.string.language_italian), "it"),
                    )
                    val selectedLanguage = languages.find {
                        if (it.tag.isEmpty()) currentTag.isEmpty()
                        else currentTag.split(",").any { tag -> tag.startsWith(it.tag) }
                    } ?: languages.first()

                    SettingGroupItem(
                        title = stringResource(R.string.setting_page_language),
                        icon = { Icon(Icons.Rounded.Translate, null, modifier = Modifier.size(20.dp)) },
                        trailing = {
                            Select(
                                options = languages,
                                selectedOption = selectedLanguage,
                                onOptionSelected = { option ->
                                    val appLocale: LocaleListCompat = if (option.tag.isEmpty()) {
                                        LocaleListCompat.getEmptyLocaleList()
                                    } else {
                                        LocaleListCompat.forLanguageTags(option.tag)
                                    }
                                    AppCompatDelegate.setApplicationLocales(appLocale)
                                },
                                optionToString = { it.name },
                                modifier = Modifier.wrapContentWidth()
                            )
                        }
                    )

                    SettingGroupItem(
                        title = stringResource(R.string.setting_page_prompt_injections),
                        subtitle = stringResource(R.string.setting_page_prompt_injections_desc),
                        icon = { Icon(Icons.Rounded.Code, null, modifier = Modifier.size(20.dp)) },
                        onClick = { navController.navigate(Screen.SettingModes()) }
                    )
                }
            }

            // Models & Services Section
            item {
                SettingsGroup(
                    title = stringResource(R.string.setting_page_model_and_services)
                ) {
                    SettingGroupItem(
                        title = stringResource(R.string.setting_page_default_model),
                        subtitle = stringResource(R.string.setting_page_default_model_desc),
                        icon = { Icon(Icons.Rounded.AccountTree, null, modifier = Modifier.size(20.dp)) },
                        onClick = { navController.navigate(Screen.SettingModels) }
                    )

                    SettingGroupItem(
                        title = stringResource(R.string.setting_page_providers),
                        subtitle = stringResource(R.string.setting_page_providers_desc),
                        icon = { Icon(Icons.Rounded.Cloud, null, modifier = Modifier.size(20.dp)) },
                        onClick = { navController.navigate(Screen.SettingProvider) }
                    )

                    SettingGroupItem(
                        title = stringResource(R.string.setting_page_search_service),
                        subtitle = stringResource(R.string.setting_page_search_service_desc),
                        icon = { Icon(Icons.Rounded.Public, null, modifier = Modifier.size(20.dp)) },
                        onClick = { navController.navigate(Screen.SettingSearch) }
                    )

                    SettingGroupItem(
                        title = stringResource(R.string.setting_page_tts_service),
                        subtitle = stringResource(R.string.setting_page_tts_service_desc),
                        icon = { Icon(Icons.Rounded.RecordVoiceOver, null, modifier = Modifier.size(20.dp)) },
                        onClick = { navController.navigate(Screen.SettingTTS) }
                    )

                    if (BuildConfig.DEBUG) {
                        SettingGroupItem(
                            title = stringResource(R.string.setting_page_wake_word),
                            subtitle = stringResource(R.string.setting_page_wake_word_desc),
                            icon = { Icon(Icons.Rounded.GraphicEq, null, modifier = Modifier.size(20.dp)) },
                            onClick = { navController.navigate(Screen.SettingWakeWord) }
                        )
                    }

                    SettingGroupItem(
                        title = stringResource(R.string.setting_page_email_service),
                        subtitle = stringResource(R.string.setting_page_email_service_desc),
                        icon = { Icon(Icons.Rounded.Email, null, modifier = Modifier.size(20.dp)) },
                        onClick = { navController.navigate(Screen.SettingEmail) }
                    )

                    SettingGroupItem(
                        title = stringResource(R.string.setting_page_mcp),
                        subtitle = stringResource(R.string.setting_page_mcp_desc),
                        icon = { Icon(Icons.Rounded.Code, null, modifier = Modifier.size(20.dp)) },
                        onClick = { navController.navigate(Screen.SettingMcp) }
                    )

                    SettingGroupItem(
                        title = stringResource(R.string.setting_page_permission_check),
                        subtitle = stringResource(R.string.setting_page_permission_check_desc),
                        icon = { Icon(Icons.Rounded.VerifiedUser, null, modifier = Modifier.size(20.dp)) },
                        onClick = { navController.navigate(Screen.SettingPermissionCheck) }
                    )
                }
            }

            // Data Section
            item {
                SettingsGroup(
                    title = stringResource(R.string.setting_page_data_settings)
                ) {
                    SettingGroupItem(
                        title = stringResource(R.string.setting_page_data_backup),
                        subtitle = stringResource(R.string.setting_page_data_backup_desc),
                        icon = { Icon(Icons.Rounded.CloudUpload, null, modifier = Modifier.size(20.dp)) },
                        onClick = { navController.navigate(Screen.Backup) }
                    )
                    val context = LocalContext.current
                    val chatStorageState by produceState(-1 to 0L) {
                        value = context.countChatFiles()
                    }
                    val imageStorageState by produceState(-1 to 0L) {
                        value = context.countImageFiles()
                    }

                    SettingGroupItem(
                        title = stringResource(R.string.setting_page_chat_storage),
                        subtitle = stringResource(
                            R.string.setting_page_chat_storage_desc,
                            chatStorageState.first,
                            chatStorageState.second.toDouble() / 1024 / 1024
                        ),
                        icon = { Icon(Icons.Rounded.Storage, null, modifier = Modifier.size(20.dp)) },
                        onClick = {
                            val intent =
                                android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    .apply {
                                        data = android.net.Uri.fromParts("package", context.packageName, null)
                                    }
                            context.startActivity(intent)
                        }
                    )

                    SettingGroupItem(
                        title = stringResource(R.string.setting_page_clear_cache),
                        subtitle = if (imageStorageState.first > 0) {
                            "${stringResource(R.string.setting_page_clear_cache_desc)} (${imageStorageState.first} 张图, %.2f MB)".format(imageStorageState.second.toDouble() / 1024 / 1024)
                        } else {
                            stringResource(R.string.setting_page_clear_cache_desc)
                        },
                        icon = { Icon(Icons.Rounded.DeleteSweep, null, modifier = Modifier.size(20.dp)) },
                        onClick = { showClearCacheDialog = true }
                    )
                }
            }

            item {
                val context = LocalContext.current
                SettingsGroup(
                    title = stringResource(R.string.setting_page_about)
                ) {
                    SettingGroupItem(
                        title = stringResource(R.string.setting_page_donate_coffee),
                        subtitle = stringResource(R.string.setting_page_donate_coffee_desc),
                        icon = {
                            Icon(
                                Icons.Rounded.Favorite,
                                null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            val alipayQrUrl = "https://qr.alipay.com/fkx15758hya1ubxd8vd6l36?t=1778995257602"
                            val fallbackUrl = "https://xx-evolia.mysxl.cn/"

                            try {
                                // 尝试通过 Scheme 唤起支付宝转账页面
                                val intent = android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("alipays://platformapi/startapp?saId=10000007&clientVersion=3.7.0.0718&qrcode=${android.net.Uri.encode(alipayQrUrl)}")
                                ).apply {
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)

                                // 弹出感谢提示 (由于无法监听支付结果，我们在跳转瞬间送上祝福)
                                android.widget.Toast.makeText(context, "感谢支持，祝你和你的小机都越来越好！", android.widget.Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                // 如果未安装支付宝或唤起失败，跳转到你指定的网页
                                context.openUrl(fallbackUrl)
                            }
                        }
                    )
                    SettingGroupItem(
                        title = stringResource(R.string.setting_page_feedback),
                        subtitle = stringResource(R.string.setting_page_feedback_desc),
                        icon = { Icon(Icons.Rounded.Feedback, null, modifier = Modifier.size(20.dp)) },
                        onClick = {
                            context.openUrl("https://my.feishu.cn/wiki/K8biwb7k4ibiFXka7EGcab3jnac?from=from_copylink")
                        }
                    )
                    SettingGroupItem(
                        title = stringResource(R.string.about_page_title),
                        subtitle = stringResource(R.string.setting_page_about_desc),
                        icon = { Icon(Icons.Rounded.Info, null, modifier = Modifier.size(20.dp)) },
                        onClick = { navController.navigate(Screen.SettingAbout) }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.padding(16.dp))
            }
        }
    }
}

private data class LanguageOption(val name: String, val tag: String)

@Composable
fun ProviderConfigWarningCard(navController: androidx.navigation.NavController) {
    // ... (保持原样)
    Surface(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        onClick = { navController.navigate(Screen.SettingProvider) }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.Cloud,
                null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    stringResource(R.string.setting_page_config_api_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    stringResource(R.string.setting_page_config_api_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
private fun UpdateAvailableBanner(
    checkForUpdates: Boolean
) {
    if (!checkForUpdates) return

    val context = LocalContext.current
    val okHttpClient = koinInject<OkHttpClient>()
    val updateChecker = remember { UpdateChecker(okHttpClient) }
    val updateFlow = remember(updateChecker) { updateChecker.checkUpdate() }
    val updateState by updateFlow.collectAsStateWithLifecycle(initialValue = UiState.Loading)

    // 控制弹窗的状态
    var showDialog by remember { mutableStateOf<UpdateInfo?>(null) }

    if (showDialog != null) {
        UpdateDialog(
            updateInfo = showDialog!!,
            onDismissRequest = { showDialog = null },
            onConfirm = {
                showDialog?.downloads?.firstOrNull()?.let { download ->
                    // 修复：这里改为调用封装好的下载方法，它会自动处理镜像地址
                    updateChecker.downloadUpdate(context, download)
                }
                showDialog = null
            }
        )
    }

    if (updateState is UiState.Success) {
        val updateInfo = (updateState as UiState.Success).data
        if (me.rerere.rikkahub.utils.Version(updateInfo.version) > me.rerere.rikkahub.utils.Version(BuildConfig.VERSION_NAME)){
            Surface(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.medium,
                onClick = {
                    // 点击后弹出详情弹窗
                    showDialog = updateInfo
                }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.Public,
                        null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            stringResource(R.string.update_banner_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            updateInfo.changelog,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
