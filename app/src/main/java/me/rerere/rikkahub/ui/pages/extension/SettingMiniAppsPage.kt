package me.rerere.rikkahub.ui.pages.extension

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Input
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.extensions.ExtensionImportExport
import me.rerere.rikkahub.data.extensions.ExtensionRegistry
import me.rerere.rikkahub.data.extensions.ExtensionType
import me.rerere.rikkahub.data.extensions.InstalledExtension
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.theme.AppShapes
import org.koin.compose.koinInject

/**
 * Mini Apps 管理主页（详设 13.4）：展示已安装扩展（Tool Extension 与
 * Mini App 统一管理），提供创建 / 导入入口。
 */
@Composable
fun SettingMiniAppsPage(
    navController: NavController,
) {
    val registry = koinInject<ExtensionRegistry>()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val extensions by registry.installedExtensions.collectAsState()

    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            importing = true
            scope.launch {
                runCatching { ExtensionImportExport.importFromUri(context, it) }
                    .onSuccess { ext ->
                        importing = false
                        snackbarHostState.showSnackbar("已安装 ${ext.name} v${ext.version}")
                    }
                    .onFailure { e ->
                        importing = false
                        importError = e.message ?: "导入失败"
                    }
            }
        }
    }

    importError?.let { error ->
        AlertDialog(
            onDismissRequest = { importError = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = AppShapes.CardMedium,
            title = { Text("导入失败") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { importError = null }) { Text("知道了") }
            },
        )
    }

    Scaffold(
        topBar = {
            OneUITopAppBar(
                title = "小应用",
                scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { navController.navigate(Screen.ExtensionCreator(null)) }) {
                        Icon(Icons.Rounded.Add, contentDescription = "创建扩展")
                    }
                    IconButton(
                        onClick = {
                            importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                        },
                        enabled = !importing,
                    ) {
                        Icon(Icons.Rounded.Input, contentDescription = "导入扩展包")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (extensions.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(80.dp))
                Text("还没有安装任何扩展", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "点击右上角 + 创建扩展，或导入 .evolia 扩展包",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            ) {
                items(extensions, key = { it.appId }) { extension ->
                    ExtensionListItemCard(
                        extension = extension,
                        onClick = { navController.navigate(Screen.ExtensionDetail(extension.appId)) },
                        onOpen = {
                            navController.navigate(Screen.MiniApp(extension.appId))
                        },
                        onEnabledChange = { enabled ->
                            scope.launch { registry.setExtensionEnabled(extension.appId, enabled) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ExtensionListItemCard(
    extension: InstalledExtension,
    onClick: () -> Unit,
    onOpen: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    Card(
        shape = AppShapes.CardMedium,
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            extension.name,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(8.dp))
                        TypeTag(extension.extensionType)
                    }
                    if (extension.description.isNotBlank()) {
                        Text(
                            extension.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Text(
                        "${extension.appId} · v${extension.version}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Switch(checked = extension.isEnabled, onCheckedChange = onEnabledChange)
                    if (extension.extensionType == ExtensionType.MINI_APP) {
                        IconButton(onClick = onOpen, enabled = extension.isEnabled) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = "打开")
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TypeTag(type: String) {
    val label = if (type == ExtensionType.MINI_APP) "Mini App" else "Tool"
    Tag(type = TagType.INFO) {
        Text(label)
    }
}
