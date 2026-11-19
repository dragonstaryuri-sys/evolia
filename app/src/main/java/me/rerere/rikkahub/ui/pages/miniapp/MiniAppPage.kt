package me.rerere.rikkahub.ui.pages.miniapp

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.extensions.ExtensionRegistry
import me.rerere.rikkahub.data.extensions.MiniAppBridgeSession
import me.rerere.rikkahub.data.extensions.MiniAppStorageService
import me.rerere.rikkahub.ui.components.extension.permissionDisplayName
import me.rerere.rikkahub.ui.components.miniapp.MiniAppWebView
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.theme.AppShapes
import org.koin.compose.koinInject

/**
 * Mini App 运行页（详设第十一章）：独立 WebView Runtime。
 *
 * 一次进入页面 = 一个 Bridge 运行实例；退出页面（onDispose）invalidate
 * 会话，此后旧实例的 Bridge 调用全部失效。扩展停用 / 卸载后 Bridge
 * 每次调用按最新快照拒绝。
 */
@Composable
fun MiniAppPage(
    appId: String,
    navController: NavController,
) {
    val registry = koinInject<ExtensionRegistry>()
    val storage = koinInject<MiniAppStorageService>()
    val application = koinInject<Application>()
    val scope = rememberCoroutineScope()

    val installed by registry.installedExtensions.collectAsState()
    val extension = installed.firstOrNull { it.appId == appId }

    // 一次性会话状态（权限申请 / 原生弹窗 / 关闭请求）
    var pendingPermission by remember { mutableStateOf<String?>(null) }
    var dialogRequest by remember { mutableStateOf<DialogRequest?>(null) }
    var closeRequested by remember { mutableStateOf(false) }

    val session = remember(appId) {
        MiniAppBridgeSession(
            appId = appId,
            context = application,
            registry = registry,
            storage = storage,
            scope = CoroutineScope(Dispatchers.Main.immediate),
            listener = object : MiniAppBridgeSession.Listener {
                override fun onPermissionRequired(permission: String) {
                    pendingPermission = permission
                }

                override fun onDialogRequest(
                    callId: Long,
                    title: String,
                    message: String,
                    confirmText: String,
                    cancelText: String,
                ) {
                    dialogRequest = DialogRequest(callId, title, message, confirmText, cancelText)
                }

                override fun onCloseRequested() {
                    closeRequested = true
                }
            },
        )
    }

    LaunchedEffect(closeRequested) {
        if (closeRequested) navController.popBackStack()
    }

    // 页面销毁时失效 Bridge 会话（扩展停用/卸载后旧实例调用立即失效）
    DisposableEffect(session) {
        onDispose { session.invalidate() }
    }

    Scaffold(
        topBar = {
            OneUITopAppBar(
                title = extension?.name ?: "小应用",
                largeTitle = false,
                scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
                navigationIcon = { BackButton() },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (extension == null) {
                // 扩展已卸载 / manifest 缺失
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("小应用不存在或已被卸载")
                    }
                }
            } else {
                MiniAppWebView(
                    appId = appId,
                    entry = extension.manifest.frontend?.entry ?: "web/index.html",
                    session = session,
                    registry = registry,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    // 权限授权确认弹窗（Manifest 申请状态与授权状态分离）
    pendingPermission?.let { permission ->
        val permissionName = permissionDisplayName(permission)
        AlertDialog(
            onDismissRequest = { pendingPermission = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = AppShapes.CardMedium,
            title = { Text("权限请求") },
            text = {
                Text("「${extension?.name ?: "该小应用"}」请求使用「$permissionName」权限，是否授权？")
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        registry.grantPermission(appId, permission, true)
                    }
                    pendingPermission = null
                }) { Text("授权") }
            },
            dismissButton = {
                TextButton(onClick = { pendingPermission = null }) {
                    Text("拒绝")
                }
            },
        )
    }

    // 原生 Dialog 请求
    dialogRequest?.let { request ->
        AlertDialog(
            onDismissRequest = {
                session.onDialogResult(request.callId, false)
                dialogRequest = null
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = AppShapes.CardMedium,
            title = { Text(request.title) },
            text = { Text(request.message) },
            confirmButton = {
                TextButton(onClick = {
                    session.onDialogResult(request.callId, true)
                    dialogRequest = null
                }) { Text(request.confirmText) }
            },
            dismissButton = {
                TextButton(onClick = {
                    session.onDialogResult(request.callId, false)
                    dialogRequest = null
                }) { Text(request.cancelText) }
            },
        )
    }
}

data class DialogRequest(
    val callId: Long,
    val title: String,
    val message: String,
    val confirmText: String,
    val cancelText: String,
)
