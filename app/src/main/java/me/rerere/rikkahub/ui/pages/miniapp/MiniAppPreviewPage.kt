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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import me.rerere.rikkahub.data.extensions.ExtensionCreator
import me.rerere.rikkahub.data.extensions.InMemoryMiniAppStorage
import me.rerere.rikkahub.data.extensions.ManifestValidator
import me.rerere.rikkahub.data.extensions.MiniAppBridgeSession
import me.rerere.rikkahub.data.extensions.PreviewMiniAppRuntimePolicy
import me.rerere.rikkahub.data.extensions.ZipPackageReader
import me.rerere.rikkahub.ui.components.miniapp.MiniAppWebView
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.theme.AppShapes
import org.koin.compose.koinInject
import java.io.File

/**
 * Mini App 工作区预览页（2026-11-20 补充需求四.3 第三层：用户主动测试入口）。
 *
 * 与安装运行态（MiniAppPage）的隔离差异：
 * - 内容来自工作区目录（MiniAppWebView contentRoot），未安装的草稿可预览；
 * - 存储 = [InMemoryMiniAppStorage] 易失内存（Schema 校验照常生效，数据不落库、
 *   不污染已安装拓展数据）；
 * - 权限 = [PreviewMiniAppRuntimePolicy] 按 Manifest 声明静态判定：已声明直接放行、
 *   未声明直接 PERMISSION_DENIED 信封（不弹授权、不落授权）——与安装后的授权
 *   门槛有差异，页面顶部横幅明示；
 * - 副作用类能力（真实网络 / Toast / Dialog）由用户主动进入预览触发，属「用户
 *   主动测试」，自动校验不代替。
 */
@Composable
fun MiniAppPreviewPage(
    draftId: String,
    navController: NavController,
) {
    val creator = koinInject<ExtensionCreator>()
    val application = koinInject<Application>()

    val workspaceDir = remember(draftId) {
        File(ExtensionCreator.workspacesRoot(application), draftId)
    }
    val draft = remember(draftId) { creator.getDraft(draftId) }
    val manifest = remember(draftId) {
        runCatching { ZipPackageReader().parseManifestFromDirectory(workspaceDir) }.getOrNull()
    }
    val entry = manifest?.frontend?.entry?.takeIf { it.isNotBlank() } ?: "web/index.html"

    // 一次性会话状态（原生弹窗 / 关闭请求；预览态无授权弹窗）
    var dialogRequest by remember { mutableStateOf<DialogRequest?>(null) }
    var closeRequested by remember { mutableStateOf(false) }

    val session = remember(draftId, manifest) {
        manifest?.let { parsed ->
            MiniAppBridgeSession(
                appId = parsed.appId,
                context = application,
                policy = PreviewMiniAppRuntimePolicy(declaredPermissions = parsed.permissions.toSet()),
                storage = InMemoryMiniAppStorage(
                    collections = ManifestValidator.resolveCollections(parsed) { relative ->
                        val f = File(workspaceDir, relative)
                        if (f.isFile) runCatching {
                            me.rerere.rikkahub.common.JsonInstant.parseToJsonElement(f.readText()) as? kotlinx.serialization.json.JsonObject
                        }.getOrNull() else null
                    },
                ),
                scope = CoroutineScope(Dispatchers.Main.immediate),
                listener = object : MiniAppBridgeSession.Listener {
                    override fun onPermissionRequired(permission: String) {
                        // 预览态静态判定，不产生授权请求
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
    }

    LaunchedEffect(closeRequested) {
        if (closeRequested) navController.popBackStack()
    }

    // 页面销毁时失效 Bridge 会话并丢弃预览存储引用
    DisposableEffect(session) {
        onDispose { session?.invalidate() }
    }

    Scaffold(
        topBar = {
            OneUITopAppBar(
                title = "预览 · ${draft?.name ?: "草稿"}",
                largeTitle = false,
                scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
                navigationIcon = { BackButton() },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (manifest == null || session == null) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("草稿不存在或 Manifest 解析失败，无法预览")
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    // 预览隔离说明横幅
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "预览环境：数据仅临时保存，权限按 Manifest 声明判定；安装后以实际授权为准",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    MiniAppWebView(
                        appId = manifest.appId,
                        entry = entry,
                        session = session,
                        contentRoot = workspaceDir,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    // 原生 Dialog 请求（与 MiniAppPage 同一交互模式）
    dialogRequest?.let { request ->
        AlertDialog(
            onDismissRequest = {
                session?.onDialogResult(request.callId, false)
                dialogRequest = null
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = AppShapes.CardMedium,
            title = { Text(request.title) },
            text = { Text(request.message) },
            confirmButton = {
                TextButton(onClick = {
                    session?.onDialogResult(request.callId, true)
                    dialogRequest = null
                }) { Text(request.confirmText) }
            },
            dismissButton = {
                TextButton(onClick = {
                    session?.onDialogResult(request.callId, false)
                    dialogRequest = null
                }) { Text(request.cancelText) }
            },
        )
    }
}
