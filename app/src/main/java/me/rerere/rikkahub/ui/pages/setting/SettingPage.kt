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
import org.koin.compose.koinInject
import okhttp3.OkHttpClient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingPage(vm: SettingVM = koinViewModel()) {
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val lazyListState = rememberLazyListState()

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
                    if(settings.developerMode) {
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
                        title = stringResource(R.string.setting_page_assistant),
                        subtitle = stringResource(R.string.setting_page_assistant_desc),
                        icon = { Icon(Icons.Rounded.Group, null, modifier = Modifier.size(20.dp)) },
                        onClick = { navController.navigate(Screen.Assistant) }
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
                        title = stringResource(R.string.setting_android_integration),
                        subtitle = stringResource(R.string.setting_android_integration_desc),
                        icon = { Icon(Icons.Rounded.PhoneAndroid, null, modifier = Modifier.size(20.dp)) },
                        onClick = { navController.navigate(Screen.SettingAndroidIntegration) }
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
                    val storageState by produceState(-1 to 0L) {
                        value = context.countChatFiles()
                    }
                    SettingGroupItem(
                        title = stringResource(R.string.setting_page_chat_storage),
                        subtitle = stringResource(R.string.setting_page_chat_storage_desc, storageState.first, storageState.second.toDouble() / 1024 / 1024),
                        icon = { Icon(Icons.Rounded.Storage, null, modifier = Modifier.size(20.dp)) },
                        onClick = {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = android.net.Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(intent)
                        }
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
                        icon = { Icon(Icons.Rounded.Favorite, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            context.openUrl("https://buymeacoffee.com/cocolalilal")
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
private fun ProviderConfigWarningCard(navController: androidx.navigation.NavController) {
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

    if (updateState is UiState.Success) {
        val updateInfo = (updateState as UiState.Success).data
        Surface(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth(),
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.medium,
            onClick = {
                updateInfo.downloads.firstOrNull()?.let {
                    context.openUrl(it.url)
                }
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
