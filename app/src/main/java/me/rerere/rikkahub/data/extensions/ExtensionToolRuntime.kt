package me.rerere.rikkahub.data.extensions

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.QuickJSContext
import com.whl.quickjs.wrapper.QuickJSException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.common.JsonInstant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Extension Tool Runtime（详设第九章）：隔离执行扩展提供的 JavaScript 工具代码。
 *
 * **禁止**复用或包装现有 eval_javascript（详设 9.2）。本 Runtime 独立实现：
 * - 显式 QuickJSLoader.init()，不依赖 Highlighter 初始化时机；
 * - 每次执行独立 QuickJS Context + 独立 daemon 线程，执行结束销毁 Context；
 * - 内存限制 / 栈深度限制；console 输出捕获；
 * - 受控 SDK 注入（同步桥接，JSCallFunction 阻塞等待原生服务在 IO 线程完成）；
 * - 结构化错误映射。
 *
 * ### QuickJS Spike 结论（wrapper-android 3.2.3 实测，2026-11-16）
 * | 能力 | 结论 |
 * |---|---|
 * | Context 创建/销毁 | create()/destroy() 可用 |
 * | 内存限制 | setMemoryLimit(int) 可用 |
 * | 无限循环中断 | **wrapper 3.2.3 无 setInterruptHandler API** |
 * | 无限递归 | setMaxStackSize 可用，栈溢出抛 QuickJSException |
 * | Promise | evaluate 同步返回，无事件循环泵；工具代码必须是同步代码 |
 * | SDK 注入 | globalObject.setProperty(name, JSCallFunction) 同步回调可用 |
 *
 * ### P0 已知风险（不得夸大安全能力）
 * - CPU 密集型无限循环无法强制终止（无 interrupt handler）：超时后放弃执行线程
 *   （daemon，随进程退出），向模型返回 EXECUTION_TIMEOUT；线程泄漏受
 *   maxToolRounds 会话上限约束；
 * - 进程内 QuickJS **不是**进程级安全沙箱；扩展第一阶段仅限用户显式安装的
 *   本地来源，在风险未受控前不得默认允许不可信第三方扩展获得高权限。
 */
class ExtensionToolRuntime(
    private val context: Context,
    private val storage: MiniAppStorageService,
    private val network: ExtensionNetworkService,
) {
    companion object {
        const val TAG = "ExtensionToolRuntime"
        const val MEMORY_LIMIT_BYTES = 64 * 1024 * 1024
        const val MAX_STACK_SIZE_BYTES = 512 * 1024
        const val EXECUTION_TIMEOUT_SECONDS = 15L
        const val MAX_LOG_LINES = 20
        private val loaderInitialized = AtomicInteger(0)
        private val mainHandler = Handler(Looper.getMainLooper())
    }

    /**
     * 结构化执行结果（详设 9.6 契约）。toJson() 即返回给模型的 JSON。
     */
    data class ExecutionResult(
        val success: Boolean,
        val data: JsonElement?,
        val errorCode: String?,
        val errorMessage: String?,
        val logs: List<String>,
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("success", success)
            put("data", data ?: kotlinx.serialization.json.JsonNull)
            if (!success) {
                put(
                    "error",
                    buildJsonObject {
                        put("code", errorCode ?: ExtensionToolError.EXECUTION_FAILED)
                        put("message", errorMessage ?: "未知错误")
                    }
                )
            } else if (data == null) {
                put("data", "执行成功但没有返回任何内容")
            }
        }
    }

    data class ToolExecutionRequest(
        val appId: String,
        val toolId: String,
        val entrySource: String,
        val arguments: JsonObject,
        /** 执行开始时快照的已授权权限集合 */
        val grantedPermissions: Set<String>,
    )

    /** 显式初始化（幂等），不依赖 Highlighter */
    fun ensureInitialized() {
        if (loaderInitialized.compareAndSet(0, 1)) {
            QuickJSLoader.init()
            Log.i(TAG, "QuickJS native 已显式初始化")
        }
    }

    suspend fun execute(request: ToolExecutionRequest): ExecutionResult = withContext(Dispatchers.IO) {
        ensureInitialized()
        val logs = mutableListOf<String>()
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "evolia-ext-tool").apply { isDaemon = true }
        }

        val resultFuture = executor.submit(java.util.concurrent.Callable<ExecutionResult> {
            runToolScript(request, logs)
        })

        val result = try {
            resultFuture.get(EXECUTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            null
        } catch (e: Exception) {
            ExecutionResult(
                success = false,
                data = null,
                errorCode = ExtensionToolError.EXECUTION_FAILED,
                errorMessage = e.message ?: "工具执行失败",
                logs = logs.toList(),
            )
        } finally {
            // shutdownNow 无法终止已陷入 native 循环的线程；daemon 线程随进程退出
            executor.shutdownNow()
        }

        result ?: ExecutionResult(
            success = false,
            data = null,
            errorCode = ExtensionToolError.EXECUTION_TIMEOUT,
            errorMessage = "工具执行超过 ${EXECUTION_TIMEOUT_SECONDS} 秒上限，已中止（扩展代码不允许无限循环）",
            logs = logs.toList(),
        )
    }

    private fun runToolScript(request: ToolExecutionRequest, logs: MutableList<String>): ExecutionResult {
        var jsContext: QuickJSContext? = null
        try {
            jsContext = QuickJSContext.create()
            val ctx = jsContext
            ctx.setMemoryLimit(MEMORY_LIMIT_BYTES)
            ctx.setMaxStackSize(MAX_STACK_SIZE_BYTES)
            ctx.setEnableStackTrace(true)

            QuickJSLoader.initConsoleLog(ctx, object : QuickJSContext.Console {
                override fun log(msg: String?) = appendLog(logs, "log", msg)
                override fun info(msg: String?) = appendLog(logs, "info", msg)
                override fun warn(msg: String?) = appendLog(logs, "warn", msg)
                override fun error(msg: String?) = appendLog(logs, "error", msg)
            })

            injectSdk(ctx, request)

            val fullCode = buildString {
                appendLine(ExtensionSdkPrelude.SDK_PRELUDE)
                appendLine(request.entrySource)
                appendLine(";")
            }
            val result = ctx.evaluate(fullCode)
            val data = serializeResult(ctx, result)
            return ExecutionResult(
                success = true,
                data = data,
                errorCode = null,
                errorMessage = null,
                logs = logs.toList(),
            )
        } catch (e: QuickJSException) {
            return ExecutionResult(
                success = false,
                data = null,
                errorCode = ExtensionToolError.EXECUTION_FAILED,
                errorMessage = "JS 执行错误：${e.message?.take(500)}",
                logs = logs.toList(),
            )
        } catch (e: Throwable) {
            return ExecutionResult(
                success = false,
                data = null,
                errorCode = ExtensionToolError.EXECUTION_FAILED,
                errorMessage = "工具执行异常：${e.javaClass.simpleName} ${e.message?.take(300)}",
                logs = logs.toList(),
            )
        } finally {
            try {
                jsContext?.destroy()
            } catch (e: Throwable) {
                Log.e(TAG, "QuickJS Context 销毁失败", e)
            }
        }
    }

    private fun appendLog(logs: MutableList<String>, level: String, msg: String?) {
        if (logs.size < MAX_LOG_LINES && !msg.isNullOrBlank()) {
            logs.add("[$level] ${msg.take(300)}")
        }
    }

    // ---------- 结果序列化 ----------

    private fun serializeResult(ctx: QuickJSContext, result: Any?): JsonElement? = when (result) {
        null -> kotlinx.serialization.json.JsonNull
        is JSObject -> runCatching {
            JsonInstant.parseToJsonElement(ctx.stringify(result))
        }.getOrElse { JsonPrimitive(result.stringify()) }
        is String -> JsonPrimitive(result)
        is Boolean -> JsonPrimitive(result)
        is Int, is Long, is Double, is Float -> JsonPrimitive(result.toDouble())
        is ByteArray -> JsonPrimitive(String(result))
        else -> JsonPrimitive(result.toString())
    }

    // ---------- 受控 SDK 注入 ----------

    /**
     * 原生函数以 JSON 字符串进出（{ok, value} / {ok:false, code, message}），
     * 由 SDK_PRELUDE 包装为友好 API。同步阻塞桥接：QuickJS 线程等待原生
     * 服务在 IO 线程完成，无 Promise/事件循环。
     */
    private fun injectSdk(ctx: QuickJSContext, request: ToolExecutionRequest) {
        val global = ctx.globalObject

        fun native(name: String, handler: suspend (String) -> String) {
            global.setProperty(name, JSCallFunction { args ->
                val raw = args.firstOrNull() as? String ?: "{}"
                runBlocking { withContext(Dispatchers.IO) { handler(raw) } }
            })
        }

        fun errorEnvelope(code: String, message: String): String =
            JsonInstant.encodeToString(
                buildJsonObject {
                    put("ok", false)
                    put("code", code)
                    put("message", message)
                }
            )

        fun okEnvelope(value: JsonElement?): String =
            JsonInstant.encodeToString(
                buildJsonObject {
                    put("ok", true)
                    put("value", value ?: kotlinx.serialization.json.JsonNull)
                }
            )

        val permissions = request.grantedPermissions

        native("__evoliaRequest") { raw ->
            if (ExtensionPermissions.NETWORK_REQUEST !in permissions) {
                return@native errorEnvelope(
                    ExtensionToolError.PERMISSION_DENIED,
                    "当前扩展未获得 network.request 权限"
                )
            }
            val params = runCatching { JsonInstant.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                ?: return@native errorEnvelope(
                    ExtensionToolError.INVALID_ARGUMENTS,
                    "request 参数必须是对象"
                )
            JsonInstant.encodeToString(network.requestStructured(params))
        }

        native("__evoliaStorageGet") { raw ->
            if (ExtensionPermissions.STORAGE_READ !in permissions) {
                return@native errorEnvelope(
                    ExtensionToolError.PERMISSION_DENIED,
                    "当前扩展未获得 storage.read 权限"
                )
            }
            handleStorage(raw) { appId, collection, recordId, data ->
                val item = storage.get(appId, collection, recordId)
                okEnvelope(item)
            }
        }

        native("__evoliaStorageList") { raw ->
            if (ExtensionPermissions.STORAGE_READ !in permissions) {
                return@native errorEnvelope(
                    ExtensionToolError.PERMISSION_DENIED,
                    "当前扩展未获得 storage.read 权限"
                )
            }
            handleStorage(raw) { appId, collection, _, _ ->
                val params = runCatching { JsonInstant.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                    ?: JsonObject(emptyMap())
                val page = (params["page"] as? JsonPrimitive)?.let { it.content.toIntOrNull() } ?: 1
                val size = (params["pageSize"] as? JsonPrimitive)?.let { it.content.toIntOrNull() }
                    ?: MiniAppStorageService.DEFAULT_PAGE_SIZE
                val result = storage.list(appId, collection, page, size)
                okEnvelope(
                    JsonInstant.parseToJsonElement(
                        JsonInstant.encodeToString(
                            buildJsonObject {
                                put("items", kotlinx.serialization.json.JsonArray(result.items))
                                put("total", result.total)
                                put("page", result.page)
                                put("pageSize", result.pageSize)
                            }
                        )
                    )
                )
            }
        }

        native("__evoliaStorageInsert") { raw ->
            if (ExtensionPermissions.STORAGE_WRITE !in permissions) {
                return@native errorEnvelope(
                    ExtensionToolError.PERMISSION_DENIED,
                    "当前扩展未获得 storage.write 权限"
                )
            }
            handleStorage(raw) { appId, collection, _, data ->
                val recordId = storage.insert(appId, collection, data)
                okEnvelope(JsonPrimitive(recordId))
            }
        }

        native("__evoliaStorageUpdate") { raw ->
            if (ExtensionPermissions.STORAGE_WRITE !in permissions) {
                return@native errorEnvelope(
                    ExtensionToolError.PERMISSION_DENIED,
                    "当前扩展未获得 storage.write 权限"
                )
            }
            handleStorage(raw) { appId, collection, recordId, data ->
                val updated = storage.update(appId, collection, recordId, data)
                okEnvelope(JsonPrimitive(updated))
            }
        }

        native("__evoliaStorageDelete") { raw ->
            if (ExtensionPermissions.STORAGE_WRITE !in permissions) {
                return@native errorEnvelope(
                    ExtensionToolError.PERMISSION_DENIED,
                    "当前扩展未获得 storage.write 权限"
                )
            }
            handleStorage(raw) { appId, collection, recordId, _ ->
                val deleted = storage.delete(appId, collection, recordId)
                okEnvelope(JsonPrimitive(deleted))
            }
        }

        native("__evoliaToast") { raw ->
            if (ExtensionPermissions.UI_TOAST !in permissions) {
                return@native errorEnvelope(
                    ExtensionToolError.PERMISSION_DENIED,
                    "当前扩展未获得 ui.toast 权限"
                )
            }
            val params = runCatching { JsonInstant.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            val message = (params?.get("message") as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (!message.isNullOrBlank()) {
                mainHandler.post {
                    android.widget.Toast.makeText(context, message.take(120), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            okEnvelope(kotlinx.serialization.json.JsonNull)
        }
    }

    private suspend fun handleStorage(
        raw: String,
        handler: suspend (appId: String, collection: String, recordId: String, data: JsonObject) -> String,
    ): String {
        val params = runCatching { JsonInstant.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return JsonInstant.encodeToString(
                buildJsonObject {
                    put("ok", false)
                    put("code", ExtensionToolError.INVALID_ARGUMENTS)
                    put("message", "参数必须是对象")
                }
            )
        val collection = (params["collection"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val recordId = (params["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val data = (params["data"] as? JsonObject) ?: JsonObject(emptyMap())
        val appId = (params["app_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        if (appId.isBlank() || collection.isBlank()) {
            return JsonInstant.encodeToString(
                buildJsonObject {
                    put("ok", false)
                    put("code", ExtensionToolError.INVALID_ARGUMENTS)
                    put("message", "缺少 collection 或 app_id")
                }
            )
        }
        return try {
            handler(appId, collection, recordId, data)
        } catch (e: MiniAppStorageService.StorageException) {
            JsonInstant.encodeToString(
                buildJsonObject {
                    put("ok", false)
                    put("code", e.code)
                    put("message", e.message ?: "存储操作失败")
                }
            )
        } catch (e: Exception) {
            JsonInstant.encodeToString(
                buildJsonObject {
                    put("ok", false)
                    put("code", ExtensionToolError.SDK_CALL_FAILED)
                    put("message", e.message ?: "存储操作失败")
                }
            )
        }
    }
}
