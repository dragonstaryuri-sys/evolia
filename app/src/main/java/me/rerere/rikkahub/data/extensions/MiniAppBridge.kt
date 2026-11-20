package me.rerere.rikkahub.data.extensions

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.common.JsonInstant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Bridge 每次调用的运行时策略（2026-11-20 拆出以支持工作区预览）：
 * 扩展可运行状态 + 权限判定。
 * - [RegistryMiniAppRuntimePolicy]：安装运行态，走 Registry 动态快照
 *   （停用/卸载即时生效；授权弹窗写库后无需重载页面即可通过后续调用）；
 * - [PreviewMiniAppRuntimePolicy]：工作区预览态，按 Manifest 声明静态判定，
 *   不弹授权、不落授权，未声明权限直接返回 PERMISSION_DENIED 信封。
 */
enum class MiniAppExtensionState { ACTIVE, DISABLED, NOT_FOUND }

interface MiniAppRuntimePolicy {
    fun extensionState(appId: String): MiniAppExtensionState
    fun isPermissionAllowed(appId: String, permission: String): Boolean
}

class RegistryMiniAppRuntimePolicy(
    private val registry: ExtensionRegistry,
) : MiniAppRuntimePolicy {
    override fun extensionState(appId: String): MiniAppExtensionState {
        val installed = registry.getInstalled(appId) ?: return MiniAppExtensionState.NOT_FOUND
        return if (installed.isEnabled) MiniAppExtensionState.ACTIVE else MiniAppExtensionState.DISABLED
    }

    override fun isPermissionAllowed(appId: String, permission: String): Boolean =
        registry.isPermissionGranted(appId, permission)
}

class PreviewMiniAppRuntimePolicy(
    private val declaredPermissions: Set<String>,
) : MiniAppRuntimePolicy {
    override fun extensionState(appId: String): MiniAppExtensionState = MiniAppExtensionState.ACTIVE

    override fun isPermissionAllowed(appId: String, permission: String): Boolean =
        permission in declaredPermissions
}

/**
 * Mini App Native Bridge 会话（详设第十二章）。
 *
 * 一次 WebView 运行实例对应一个会话。每次 Bridge 调用都必须完整校验
 * （12.3）：扩展存在 / 启用 / 运行实例有效 / 页面来源属于本扩展资源域 /
 * 权限已授权 / 参数合法——不能仅在 WebView 初始化时检查一次。
 * 页面销毁、扩展停用后旧实例的调用立即失效。
 */
class MiniAppBridgeSession(
    val appId: String,
    private val context: Context,
    private val policy: MiniAppRuntimePolicy,
    private val storage: MiniAppStorage,
    private val scope: CoroutineScope,
    private val listener: Listener,
) {
    companion object {
        const val TAG = "MiniAppBridge"
        const val BRIDGE_NAME = "evoliaNativeBridge"

        /** 受控本地资源域（与 MiniAppWebView 的 WebViewAssetLoader 域一致） */
        const val RESOURCE_ORIGIN = "https://appassets.androidplatform.net"

        /** 该扩展的资源路径前缀 /app/{app_id}/ */
        fun resourcePathPrefix(appId: String) = "/app/$appId/"

        /** 当前扩展页面 URL */
        fun resourceUrl(appId: String, relative: String): String =
            "$RESOURCE_ORIGIN${resourcePathPrefix(appId)}$relative"
    }

    interface Listener {
        /** 请求用户授权权限（未授权调用触发，UI 弹确认后调 registry.grantPermission） */
        fun onPermissionRequired(permission: String)

        /** 原生 Dialog 请求 */
        fun onDialogRequest(callId: Long, title: String, message: String, confirmText: String, cancelText: String)

        /** navigation.close：请求关闭 Mini App 页面 */
        fun onCloseRequested()
    }

    private val active = AtomicBoolean(true)
    private val seq = AtomicLong(0)
    private val pendingDialogCalls = ConcurrentHashMap<Long, JavaScriptReplyProxy>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun isActive(): Boolean = active.get()

    /** 页面销毁 / 扩展停用 / 卸载时调用，此后所有调用立即失效 */
    fun invalidate() {
        active.set(false)
        pendingDialogCalls.clear()
    }

    /**
     * 处理一条 Bridge 消息（onPostMessage 回调，主线程）。
     * 回复经 replyProxy 异步投递。
     */
    fun handleMessage(
        message: WebMessageCompat?,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy,
    ) {
        if (!active.get()) return
        val raw = message?.data ?: return
        val request = runCatching { JsonInstant.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        if (request == null) {
            replyError(replyProxy, 0, ExtensionToolError.INVALID_ARGUMENTS, "消息不是合法 JSON")
            return
        }
        val type = request.readString("type")
        if (type != "evolia_request") return
        val callId = (request["id"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
        val method = request.readString("method").orEmpty()

        // 每次调用校验 1-4：扩展存在 / 启用 / 实例有效 / 来源合法
        val extensionState = policy.extensionState(appId)
        if (extensionState == MiniAppExtensionState.NOT_FOUND) {
            replyError(replyProxy, callId, ExtensionToolError.EXTENSION_NOT_FOUND, "扩展不存在或已卸载")
            return
        }
        if (extensionState == MiniAppExtensionState.DISABLED) {
            replyError(replyProxy, callId, ExtensionToolError.EXTENSION_DISABLED, "扩展已被停用")
            return
        }
        if (!isMainFrame) {
            // 子 frame 一律拒绝（防外部 iframe 继承 Bridge 能力）
            replyError(replyProxy, callId, ExtensionToolError.PERMISSION_DENIED, "仅主 frame 可调用原生接口")
            return
        }
        if (sourceOrigin.toString() != RESOURCE_ORIGIN) {
            replyError(replyProxy, callId, ExtensionToolError.PERMISSION_DENIED, "页面来源不属于受控资源域")
            return
        }

        val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())

        scope.launch {
            try {
                dispatch(method, params, replyProxy, callId)
            } catch (e: MiniAppStorage.StorageException) {
                replyError(replyProxy, callId, e.code, e.message ?: "存储操作失败")
            } catch (e: Exception) {
                Log.e(TAG, "Bridge 调用失败: $method", e)
                replyError(replyProxy, callId, ExtensionToolError.SDK_CALL_FAILED, e.message ?: "SDK 调用失败")
            }
        }
    }

    private suspend fun dispatch(
        method: String,
        params: JsonObject,
        replyProxy: JavaScriptReplyProxy,
        callId: Long,
    ) {
        val parts = method.split('.')
        val namespace = parts.getOrNull(0).orEmpty()
        val action = parts.getOrNull(1).orEmpty()
        when (namespace) {
            "storage" -> dispatchStorage(action, params, replyProxy, callId)
            "ui" -> dispatchUi(action, params, replyProxy, callId)
            "navigation" -> dispatchNavigation(action, replyProxy, callId)
            else -> replyError(replyProxy, callId, ExtensionToolError.INVALID_ARGUMENTS, "未知的 SDK 方法：$method")
        }
    }

    private suspend fun dispatchStorage(
        action: String,
        params: JsonObject,
        replyProxy: JavaScriptReplyProxy,
        callId: Long,
    ) {
        val collection = params.readString("collection").orEmpty()
        val recordId = params.readString("id").orEmpty()
        val isWrite = action in setOf("insert", "update", "delete")
        val requiredPermission = if (isWrite) ExtensionPermissions.STORAGE_WRITE else ExtensionPermissions.STORAGE_READ

        // 每次调用校验 5：权限
        if (!policy.isPermissionAllowed(appId, requiredPermission)) {
            listener.onPermissionRequired(requiredPermission)
            replyError(replyProxy, callId, ExtensionToolError.PERMISSION_DENIED, "扩展未获得 $requiredPermission 权限")
            return
        }

        val result: kotlinx.serialization.json.JsonElement = when (action) {
            "get" -> storage.get(appId, collection, recordId) ?: kotlinx.serialization.json.JsonNull
            "list" -> {
                val page = params.readInt("page") ?: 1
                val pageSize = params.readInt("pageSize") ?: MiniAppStorageService.DEFAULT_PAGE_SIZE
                val listResult = storage.list(appId, collection, page, pageSize)
                buildJsonObject {
                    put("items", JsonInstant.parseToJsonElement(JsonInstant.encodeToString(kotlinx.serialization.json.JsonArray(listResult.items))))
                    put("total", listResult.total)
                    put("page", listResult.page)
                    put("pageSize", listResult.pageSize)
                }
            }

            "insert" -> {
                val data = params["data"] as? JsonObject
                    ?: return replyError(replyProxy, callId, ExtensionToolError.INVALID_ARGUMENTS, "insert 需要 data 对象")
                JsonPrimitive(storage.insert(appId, collection, data))
            }

            "update" -> {
                val data = params["data"] as? JsonObject
                    ?: return replyError(replyProxy, callId, ExtensionToolError.INVALID_ARGUMENTS, "update 需要 data 对象")
                if (recordId.isBlank()) {
                    return replyError(replyProxy, callId, ExtensionToolError.INVALID_ARGUMENTS, "update 需要 id")
                }
                JsonPrimitive(storage.update(appId, collection, recordId, data))
            }

            "delete" -> {
                if (recordId.isBlank()) {
                    return replyError(replyProxy, callId, ExtensionToolError.INVALID_ARGUMENTS, "delete 需要 id")
                }
                JsonPrimitive(storage.delete(appId, collection, recordId))
            }

            else -> return replyError(replyProxy, callId, ExtensionToolError.INVALID_ARGUMENTS, "未知的 storage 方法：$action")
        }
        replyOk(replyProxy, callId, result)
    }

    private fun dispatchUi(
        action: String,
        params: JsonObject,
        replyProxy: JavaScriptReplyProxy,
        callId: Long,
    ) {
        when (action) {
            "toast" -> {
                if (!policy.isPermissionAllowed(appId, ExtensionPermissions.UI_TOAST)) {
                    listener.onPermissionRequired(ExtensionPermissions.UI_TOAST)
                    replyError(replyProxy, callId, ExtensionToolError.PERMISSION_DENIED, "扩展未获得 ui.toast 权限")
                    return
                }
                val message = params.readString("message").orEmpty().take(120)
                if (message.isNotBlank()) {
                    mainHandler.post {
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                }
                replyOk(replyProxy, callId, kotlinx.serialization.json.JsonNull)
            }

            "dialog" -> {
                if (!policy.isPermissionAllowed(appId, ExtensionPermissions.UI_DIALOG)) {
                    listener.onPermissionRequired(ExtensionPermissions.UI_DIALOG)
                    replyError(replyProxy, callId, ExtensionToolError.PERMISSION_DENIED, "扩展未获得 ui.dialog 权限")
                    return
                }
                // 以请求 callId 登记，等待用户点击后经 onDialogResult 回复
                pendingDialogCalls[callId] = replyProxy
                listener.onDialogRequest(
                    callId,
                    params.readString("title") ?: "提示",
                    params.readString("message") ?: "",
                    params.readString("confirmText") ?: "确定",
                    params.readString("cancelText") ?: "取消",
                )
            }

            else -> replyError(replyProxy, callId, ExtensionToolError.INVALID_ARGUMENTS, "未知的 ui 方法：$action")
        }
    }

    /** UI 层回调：用户点击了 Dialog 按钮（confirmed = 是否点确认） */
    fun onDialogResult(requestCallId: Long, confirmed: Boolean) {
        val replyProxy = pendingDialogCalls.remove(requestCallId) ?: return
        replyOk(replyProxy, requestCallId, JsonPrimitive(confirmed))
    }

    private fun dispatchNavigation(
        action: String,
        replyProxy: JavaScriptReplyProxy,
        callId: Long,
    ) {
        when (action) {
            "close" -> {
                replyOk(replyProxy, callId, kotlinx.serialization.json.JsonNull)
                listener.onCloseRequested()
            }

            else -> replyError(replyProxy, callId, ExtensionToolError.INVALID_ARGUMENTS, "未知的 navigation 方法：$action")
        }
    }

    // ---------- 回复 ----------

    private fun replyOk(
        replyProxy: JavaScriptReplyProxy,
        requestId: Long,
        value: kotlinx.serialization.json.JsonElement?,
    ) {
        if (!active.get()) return
        val json = buildJsonObject {
            put("type", "evolia_response")
            put("id", requestId)
            put("ok", true)
            put("value", value ?: kotlinx.serialization.json.JsonNull)
        }
        postReply(replyProxy, json)
    }

    private fun replyError(
        replyProxy: JavaScriptReplyProxy,
        requestId: Long,
        code: String,
        message: String,
    ) {
        if (!active.get()) return
        val json = buildJsonObject {
            put("type", "evolia_response")
            put("id", requestId)
            put("ok", false)
            put("code", code)
            put("message", message)
        }
        postReply(replyProxy, json)
    }

    private fun postReply(replyProxy: JavaScriptReplyProxy, json: JsonObject) {
        val payload = JsonInstant.encodeToString(json)
        // WebMessageReplyProxy.postMessage 必须在主线程调用
        mainHandler.post {
            if (!active.get()) return@post
            try {
                replyProxy.postMessage(payload)
            } catch (e: Exception) {
                Log.w(TAG, "回复投递失败（页面可能已销毁）：${e.message}")
            }
        }
    }

    private fun JsonObject.readString(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.readInt(key: String): Int? =
        (this[key] as? JsonPrimitive)?.content?.toIntOrNull()
}
