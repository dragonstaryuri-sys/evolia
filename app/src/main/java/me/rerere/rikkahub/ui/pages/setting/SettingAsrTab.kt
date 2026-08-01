package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.asr.provider.ASRProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AsrTab(
    vm: SettingVM,
    contentPadding: PaddingValues
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val providers = settings.asrProviders
    var editingProvider by remember { mutableStateOf<ASRProviderSetting?>(null) }
    val haptics = rememberPremiumHaptics(enabled = settings.displaySetting.enableUIHaptics)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        itemsIndexed(providers, key = { _, provider -> provider.id }) { _, provider ->
            val isSelected = settings.selectedASRProviderId == provider.id
            ASRProviderItem(
                provider = provider,
                isSelected = isSelected,
                onSelect = {
                    if (!isSelected) {
                        haptics.perform(HapticPattern.Pop)
                        vm.updateSettings(
                            settings.copy(selectedASRProviderId = provider.id)
                        )
                    }
                },
                onEdit = { editingProvider = provider }
            )
        }
    }

    editingProvider?.let { provider ->
        var localProvider by remember(provider) { mutableStateOf(provider) }
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()

        ModalBottomSheet(
            onDismissRequest = { editingProvider = null },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = localProvider.name.ifBlank { stringResource(R.string.setting_asr_page_title) },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                me.rerere.rikkahub.ui.pages.setting.components.ASRProviderConfigure(
                    setting = localProvider,
                    onValueChange = { localProvider = it }
                )
                androidx.compose.material3.Button(
                    onClick = {
                        vm.updateSettings(
                            settings.copy(
                                asrProviders = settings.asrProviders.map {
                                    if (it.id == localProvider.id) localProvider else it
                                }
                            )
                        )
                        scope.launch { sheetState.hide() }
                        editingProvider = null
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    }
}

@Composable
private fun ASRProviderItem(
    provider: ASRProviderSetting,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit
) {
    Card(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth()
    ) {
        ListItem(
            headlineContent = {
                Text(
                    text = provider.name.ifBlank { providerDisplayName(provider) },
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                )
            },
            supportingContent = { Text(providerDisplayName(provider)) },
            trailingContent = {
                androidx.compose.foundation.layout.Row {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Rounded.Edit, contentDescription = null)
                    }
                    if (isSelected) {
                        Icon(Icons.Rounded.Check, contentDescription = null)
                    }
                }
            },
            colors = ListItemDefaults.colors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent
            )
        )
    }
}

private fun providerDisplayName(provider: ASRProviderSetting): String = when (provider) {
    is ASRProviderSetting.SystemASR -> "System ASR"
}
