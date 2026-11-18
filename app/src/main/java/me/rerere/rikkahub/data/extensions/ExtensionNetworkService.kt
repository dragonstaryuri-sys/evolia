package me.rerere.rikkahub.data.extensions

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.common.JsonInstant
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 受控网络服务（详设 13.6 最小可控版本）。
 *
 * 网络请求必须由 Evolia 原生服务发起；扩展代码不能直接获得不受控制的
 * 任意网络能力。最小可控版本提供：权限检查点（调用方负责）、请求超时、
 * 响应体大小上限、异常处理与结构化返回。
 * 域名白名单/凭证隔离为后续增强，不因此把原生网络接口无条件暴露。
 */
class ExtensionNetworkService {
    companion object {
        const val TIMEOUT_SECONDS = 30L
        const val MAX_RESPONSE_BYTES = 5L * 1024 * 1024
        private val ALLOWED_METHODS = setOf("GET", "POST", "PUT", "DELETE", "HEAD")
    }

    class NetworkException(val code: String, message: String) : Exception(message)

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    data class NetworkResult(
        val status: Int,
        val body: String,
        val contentType: String?,
    )

    /**
     * 执行 HTTP 请求。调用方（Tool Runtime / Mini App Bridge）必须先行
     * 校验 network.request 权限。
     */
    fun request(params: JsonObject): NetworkResult {
        val url = (params["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw NetworkException(ExtensionToolError.INVALID_ARGUMENTS, "缺少 url 参数")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw NetworkException(ExtensionToolError.INVALID_ARGUMENTS, "仅支持 http/https 协议")
        }
        val method = (params["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.uppercase()
            ?: "GET"
        if (method !in ALLOWED_METHODS) {
            throw NetworkException(ExtensionToolError.INVALID_ARGUMENTS, "不支持的请求方法：$method")
        }

        val bodyStr = (params["body"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val headers = params["headers"] as? JsonObject

        val builder = Request.Builder().url(url)
        headers?.forEach { (name, value) ->
            val v = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@forEach
            if (!name.equals("Content-Length", ignoreCase = true)) {
                builder.header(name, v)
            }
        }

        val hasBody = bodyStr != null && method != "GET" && method != "HEAD"
        if (hasBody) {
            val contentType = (headers?.get("Content-Type") ?: headers?.get("content-type"))
                ?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                ?: "application/json"
            builder.method(
                method,
                bodyStr.toRequestBody(contentType.toMediaTypeOrNull())
            )
        } else {
            builder.method(method, null)
        }

        val response = try {
            client.newCall(builder.build()).execute()
        } catch (e: Exception) {
            throw NetworkException(ExtensionToolError.SDK_CALL_FAILED, "网络请求失败：${e.message}")
        }

        response.use { resp ->
            val bytes = resp.body?.bytes()
            if (bytes != null && bytes.size > MAX_RESPONSE_BYTES) {
                throw NetworkException(ExtensionToolError.SDK_CALL_FAILED, "响应体超出 5MB 上限")
            }
            return NetworkResult(
                status = resp.code,
                body = bytes?.decodeToString().orEmpty(),
                contentType = resp.header("Content-Type"),
            )
        }
    }

    /** 结构化包装（供 SDK 返回给 JS） */
    fun requestStructured(params: JsonObject): JsonObject = buildJsonObject {
        try {
            val result = request(params)
            put("ok", true)
            put("status", result.status)
            put("body", result.body)
            result.contentType?.let { put("contentType", it) }
        } catch (e: NetworkException) {
            put("ok", false)
            put("code", e.code)
            put("message", e.message ?: "网络请求失败")
        } catch (e: Exception) {
            put("ok", false)
            put("code", ExtensionToolError.SDK_CALL_FAILED)
            put("message", e.message ?: "网络请求失败")
        }
    }
}
