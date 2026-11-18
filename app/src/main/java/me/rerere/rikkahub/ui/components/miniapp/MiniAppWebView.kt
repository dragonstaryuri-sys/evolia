package me.rerere.rikkahub.ui.components.miniapp

import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import me.rerere.rikkahub.data.extensions.ManifestValidator
import me.rerere.rikkahub.data.extensions.MiniAppBridgeSession
import me.rerere.rikkahub.data.extensions.ExtensionRegistry
import java.io.File

/**
 * Mini App 专用 WebView（详设第十一章）。
 *
 * **禁止**复用通用 WebView composable 作为 Mini App 运行环境：
 * - WebViewAssetLoader 受控资源域（不使用 file:// 加载）；
 * - per-app 资源隔离（路径必须位于 /app/{app_id}/ 下，禁止穿越）；
 * - URL 拦截：外部网页经系统浏览器打开，绝不继承 Native Bridge 权限；
 * - addDocumentStartJavaScript 注入 SDK + addWebMessageListener 受控通信，
 *   Origin Rules 限定受控来源；
 * - 子 frame 一律拒绝调用（Bridge 内部检查 isMainFrame）。
 */
@Composable
fun MiniAppWebView(
    appId: String,
    entry: String,
    session: MiniAppBridgeSession,
    registry: ExtensionRegistry,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sdkScript = remember {
        runCatching {
            context.assets.open("evolia/evolia-sdk.js").use { it.readBytes().decodeToString() }
        }.getOrElse { "" }
    }
    val assetLoader = remember(appId) {
        WebViewAssetLoader.Builder()
            .setDomain(MiniAppBridgeSession.RESOURCE_ORIGIN.removePrefix("https://"))
            .addPathHandler("/app/", MiniAppExtensionPathHandler(context, registry))
            .build()
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    allowFileAccessFromFileURLs = false
                    allowUniversalAccessFromFileURLs = false
                    javaScriptCanOpenWindowsAutomatically = false
                    mediaPlaybackRequiresUserGesture = false
                    setSupportZoom(false)
                    builtInZoomControls = false
                }
                webViewClient = object : WebViewClientCompat() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean = handleExternalNavigation(ctx, appId, request.url)
                }
                // SDK 注入：页面脚本开始前执行（仅受控来源）
                if (sdkScript.isNotBlank() &&
                    WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                ) {
                    WebViewCompat.addDocumentStartJavaScript(
                        this,
                        sdkScript,
                        setOf(MiniAppBridgeSession.RESOURCE_ORIGIN),
                    )
                }
                // 受控 Bridge：Origin Rules 限定受控来源，外部网页拿不到该注入对象
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    WebViewCompat.addWebMessageListener(
                        this,
                        MiniAppBridgeSession.BRIDGE_NAME,
                        setOf(MiniAppBridgeSession.RESOURCE_ORIGIN),
                    ) { _, message, sourceOrigin, isMainFrame, replyProxy ->
                        session.handleMessage(message, sourceOrigin, isMainFrame, replyProxy)
                    }
                }
                loadUrl(MiniAppBridgeSession.resourceUrl(appId, entry))
            }
        },
        onRelease = { webView ->
            session.invalidate()
            webView.destroy()
        },
    )
}

/**
 * 外部跳转分类（详设 11.4）：
 * - 本扩展合法资源：放行；
 * - 其他受控域路径：拦截（不允许跳转到其他扩展的域路径）；
 * - http/https 外部网页：系统浏览器打开，不继续在拥有 Bridge 的 WebView 中加载；
 * - 未知/危险协议：直接拒绝。
 */
private fun handleExternalNavigation(context: android.content.Context, appId: String, url: Uri): Boolean {
    val inOwnScope = url.host == MiniAppBridgeSession.RESOURCE_ORIGIN.removePrefix("https://") &&
        url.path?.startsWith(MiniAppBridgeSession.resourcePathPrefix(appId)) == true
    if (inOwnScope) return false

    val scheme = url.scheme?.lowercase()
    if (scheme == "http" || scheme == "https") {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        return true
    }
    // intent: / javascript: / file: / about: 等一律拒绝
    return true
}

/**
 * /app/{app_id}/{relative} → filesDir/extensions/installed/{app_id}/{relative}。
 * 路径穿越 / 其他扩展资源 / 缺失文件一律返回 null（WebViewAssetLoader 会 404）。
 */
private class MiniAppExtensionPathHandler(
    private val context: android.content.Context,
    private val registry: ExtensionRegistry,
) : WebViewAssetLoader.PathHandler {

    override fun handle(path: String): WebResourceResponse? {
        val appId = path.substringBefore('/')
        val relative = path.substringAfter('/', missingDelimiterValue = "")
        if (appId.isBlank() || relative.isBlank()) return null
        if (!ManifestValidator.isSafeRelativePath(relative)) return null

        val installed = registry.getInstalled(appId) ?: return null
        val root = File(context.filesDir, installed.installPath).canonicalFile
        val file = File(root, relative)
        if (!file.isFile) return null
        if (!file.canonicalFile.path.startsWith(root.path + File.separator)) return null

        val mime = guessMimeType(file.name)
        return WebResourceResponse(
            mime,
            if (mime.startsWith("text/") || mime == "application/json") "utf-8" else null,
            file.inputStream(),
        )
    }

    private fun guessMimeType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "html", "htm" -> "text/html"
            "js", "mjs" -> "text/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "mp3" -> "audio/mpeg"
            "mp4" -> "video/mp4"
            else -> android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }
}
