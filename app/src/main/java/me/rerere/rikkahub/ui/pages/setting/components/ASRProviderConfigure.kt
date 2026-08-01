package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.asr.provider.ASRProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.HapticSwitch

@Composable
fun ASRProviderConfigure(
    setting: ASRProviderSetting,
    modifier: Modifier = Modifier,
    onValueChange: (ASRProviderSetting) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.verticalScroll(rememberScrollState())
    ) {
        when (setting) {
            is ASRProviderSetting.SystemASR -> SystemASRConfiguration(setting, onValueChange)
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
