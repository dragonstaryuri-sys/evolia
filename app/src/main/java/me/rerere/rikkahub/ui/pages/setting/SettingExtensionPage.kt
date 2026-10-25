package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupItem
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import me.rerere.search.SearchServiceOptions
import org.koin.androidx.compose.koinViewModel

/**
 * 扩展服务页：聚合搜索服务 / 邮件服务 / 打印机服务入口，
 * 降低"模型与服务"设置主页的信息密度。
 * 各条目展示可读的配置状态，避免只有名称无法判断可用性。
 */
@Composable
fun SettingExtensionPage(vm: SettingVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()

    val searchSelected = settings.searchServices.getOrNull(settings.searchServiceSelected)
    val searchTypeName = searchSelected?.let { SearchServiceOptions.TYPES[it::class] }
    val searchStatus = if (searchTypeName != null) {
        stringResource(R.string.setting_extensions_search_status_fmt, searchTypeName)
    } else {
        stringResource(R.string.setting_extensions_not_configured)
    }
    val emailAccount = settings.emailConfig.account
    val emailStatus = if (emailAccount.isNotBlank()) {
        stringResource(R.string.setting_extensions_email_configured_fmt, emailAccount)
    } else {
        stringResource(R.string.setting_extensions_not_configured)
    }
    val printerConfig = settings.printerConfig
    val printerStatus = if (printerConfig.isConfigured) {
        stringResource(R.string.setting_extensions_printer_configured_fmt, printerConfig.name)
    } else {
        stringResource(R.string.setting_extensions_not_configured)
    }

    Scaffold(
        topBar = {
            OneUITopAppBar(
                title = stringResource(R.string.setting_extensions_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = { BackButton() }
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding
        ) {
            item {
                SettingsGroup(
                    title = stringResource(R.string.setting_extensions_group)
                ) {
                    SettingGroupItem(
                        title = stringResource(R.string.setting_page_search_service),
                        subtitle = searchStatus,
                        icon = { Icon(Icons.Rounded.Public, null, modifier = Modifier.padding(2.dp)) },
                        onClick = { navController.navigate(Screen.SettingSearch) }
                    )
                    SettingGroupItem(
                        title = stringResource(R.string.setting_page_email_service),
                        subtitle = emailStatus,
                        icon = { Icon(Icons.Rounded.Email, null, modifier = Modifier.padding(2.dp)) },
                        onClick = { navController.navigate(Screen.SettingEmail) }
                    )
                    SettingGroupItem(
                        title = stringResource(R.string.setting_page_printer_service),
                        subtitle = printerStatus,
                        icon = { Icon(Icons.Rounded.Print, null, modifier = Modifier.padding(2.dp)) },
                        onClick = { navController.navigate(Screen.SettingPrinter) }
                    )
                }
            }
        }
    }
}
