package me.rerere.rikkahub.data.extensions

import android.util.Log
import com.whl.quickjs.wrapper.QuickJSContext
import com.whl.quickjs.wrapper.QuickJSException
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest
import java.util.Base64

/**
 * 草稿三层校验器（2026-11-20 补充需求第四章）。
 *
 * - 第一层 结构：复用 [ManifestValidator]（与安装校验同一套，错误全部阻断）；
 * - 第二层 代码与运行环境：JS 语法检查（QuickJS compile-only 注入，不执行代码）、
 *   Mini App 本地引用完整性（阻断）、Evolia SDK 调用扫描（未声明权限 / 未知 API = 警告）；
 * - 第三层 预览与用户主动测试：见 MiniAppPreviewPage（工作区预览），不参与本判定。
 *
 * 内容哈希（排序 relpath + 内容，排除 workspace.json 元数据自身）随校验结果持久化，
 * 安装前重算比对，保证「校验通过的文件 = 最终安装的文件」；校验后有任何修改即失效。
 *
 * 校验只暴露明显问题，不承诺发现全部业务 Bug；依赖真实设备 / 账号 / 外部服务的
 * 能力不在自动校验中执行任何有副作用的调用。
 */
object ExtensionDraftValidator {

    const val TAG = "ExtensionDraftValidator"

    enum class Level { PASS, WARN, FAIL }

    data class Issue(val file: String?, val message: String)

    data class Result(
        val level: Level,
        val errors: List<Issue>,
        val warnings: List<Issue>,
        val contentHash: String,
    )

    /**
     * JS 语法检查器注入点：返回 null = 通过，非 null = 错误信息。
     * 传 null 跳过语法检查（仅用于单测环境——QuickJS native 不可用）。
     */
    fun interface JsSyntaxChecker {
        fun check(source: String, fileName: String): String?
    }

    /** Android 实现：QuickJS compile-only（JS_EVAL_FLAG_COMPILE_ONLY），不执行任何顶层语句 */
    object QuickJsSyntaxChecker : JsSyntaxChecker {
        private val loaderInitialized = java.util.concurrent.atomic.AtomicBoolean(false)

        private fun ensureNativeLoaded() {
            if (loaderInitialized.compareAndSet(false, true)) {
                com.whl.quickjs.android.QuickJSLoader.init()
                Log.i(TAG, "QuickJS native 已为语法检查初始化")
            }
        }

        override fun check(source: String, fileName: String): String? {
            return try {
                ensureNativeLoaded()
                var jsContext: QuickJSContext? = null
                try {
                    jsContext = QuickJSContext.create()
                    val ctx = jsContext
                    ctx.setMemoryLimit(16 * 1024 * 1024)
                    ctx.setMaxStackSize(256 * 1024)
                    // compile-only：仅解析编译并返回字节码，语法错误在此时抛出
                    ctx.compile(source, fileName)
                    null
                } finally {
                    try {
                        jsContext?.destroy()
                    } catch (e: Throwable) {
                        Log.e(TAG, "QuickJS Context 销毁失败", e)
                    }
                }
            } catch (e: QuickJSException) {
                "JS 语法错误：${e.message?.take(300)}"
            } catch (e: Throwable) {
                "JS 编译异常：${e.javaClass.simpleName} ${e.message?.take(200)}"
            }
        }
    }

    /**
     * @param manifest 已解析的工作区 Manifest
     * @param listFiles 包内文件相对路径全集（由调用方遍历工作区注入）
     * @param readFile 读取包内文件字节（null = 缺失）
     * @param fileExists 包内相对路径存在性（L1 结构校验用）
     * @param parseSchemaFile Schema 文件解析（L1 结构校验用）
     * @param jsSyntaxCheck JS 语法检查器（null = 跳过，仅单测）
     */
    fun validate(
        manifest: ExtensionManifest,
        listFiles: () -> List<String>,
        readFile: (String) -> ByteArray?,
        fileExists: (String) -> Boolean,
        parseSchemaFile: (String) -> JsonObject?,
        jsSyntaxCheck: JsSyntaxChecker?,
    ): Result {
        val errors = mutableListOf<Issue>()
        val warnings = mutableListOf<Issue>()

        // ---------- 第一层：结构校验（错误全部阻断） ----------
        ManifestValidator.validate(manifest, fileExists, parseSchemaFile).forEach { message ->
            errors.add(Issue(file = null, message = message))
        }

        val files = listFiles()
        val declaredPermissions = (manifest.permissions + manifest.tools.flatMap { it.permissions }).toSet()

        // ---------- 第二层：代码与运行环境校验 ----------
        if (manifest.isMiniApp) {
            validateMiniAppCode(manifest, files, readFile, jsSyntaxCheck, declaredPermissions, errors, warnings)
        } else if (manifest.isToolExtension) {
            validateToolCode(manifest, readFile, jsSyntaxCheck, declaredPermissions, errors, warnings)
        }

        val hash = computeContentHash(files, readFile)
        val level = when {
            errors.isNotEmpty() -> Level.FAIL
            warnings.isNotEmpty() -> Level.WARN
            else -> Level.PASS
        }
        return Result(level = level, errors = errors, warnings = warnings, contentHash = hash)
    }

    // ---------- Mini App 代码校验 ----------

    private fun validateMiniAppCode(
        manifest: ExtensionManifest,
        files: List<String>,
        readFile: (String) -> ByteArray?,
        jsSyntaxCheck: JsSyntaxChecker?,
        declaredPermissions: Set<String>,
        errors: MutableList<Issue>,
        warnings: MutableList<Issue>,
    ) {
        // 入口 HTML 引用的本地资源完整性（远程引用不检查）
        val entry = manifest.frontend?.entry.orEmpty()
        val entrySource = readFile(entry)?.decodeToString()
        if (entrySource != null) {
            scanLocalAssetReferences(entrySource).forEach { ref ->
                if (!files.any { it == ref }) {
                    errors.add(Issue(file = entry, message = "入口页面引用的本地文件缺失：$ref"))
                }
            }
        }

        // 全部 .js 文件：语法检查 + SDK 权限声明扫描
        files.filter { it.endsWith(".js") || it.endsWith(".mjs") }.forEach { path ->
            val source = readFile(path)?.decodeToString() ?: return@forEach
            checkJsSyntax(path, source, jsSyntaxCheck, errors)
            reportSdkUsage(path, source, declaredPermissions, warnings)
        }
    }

    // ---------- 工具拓展代码校验 ----------

    private fun validateToolCode(
        manifest: ExtensionManifest,
        readFile: (String) -> ByteArray?,
        jsSyntaxCheck: JsSyntaxChecker?,
        declaredPermissions: Set<String>,
        errors: MutableList<Issue>,
        warnings: MutableList<Issue>,
    ) {
        manifest.tools.forEach { tool ->
            val source = readFile(tool.entry)?.decodeToString() ?: return@forEach
            checkJsSyntax(tool.entry, source, jsSyntaxCheck, errors)
            reportSdkUsage(tool.entry, source, declaredPermissions, warnings)
        }
    }

    private fun checkJsSyntax(
        path: String,
        source: String,
        jsSyntaxCheck: JsSyntaxChecker?,
        errors: MutableList<Issue>,
    ) {
        if (jsSyntaxCheck == null) return
        jsSyntaxCheck.check(source, path)?.let { message ->
            errors.add(Issue(file = path, message = message))
        }
    }

    // ---------- SDK 调用扫描（纯函数可单测） ----------

    /** SDK 方法 → 所需权限（空串 = 无需权限；不在表内 = 未定义 API） */
    internal val SDK_PERMISSION_BY_METHOD: Map<String, String> = mapOf(
        "storage.get" to ExtensionPermissions.STORAGE_READ,
        "storage.list" to ExtensionPermissions.STORAGE_READ,
        "storage.insert" to ExtensionPermissions.STORAGE_WRITE,
        "storage.update" to ExtensionPermissions.STORAGE_WRITE,
        "storage.delete" to ExtensionPermissions.STORAGE_WRITE,
        "network.request" to ExtensionPermissions.NETWORK_REQUEST,
        "ui.toast" to ExtensionPermissions.UI_TOAST,
        "ui.dialog" to ExtensionPermissions.UI_DIALOG,
        "navigation.close" to "",
    )

    data class SdkUsageScan(val usedPermissions: Set<String>, val unknownApis: List<String>)

    /**
     * 扫描源码中的 `evolia.<ns>.<action>` 调用。字符串/注释中的调用也会命中，
     * 对「警告」级判定可接受（误报无害，漏报不阻断）。
     */
    internal fun scanSdkUsage(source: String): SdkUsageScan {
        val usedPermissions = mutableSetOf<String>()
        val unknownApis = mutableListOf<String>()
        Regex("""evolia\s*\.\s*([a-zA-Z_$][\w$]*)\s*\.\s*([a-zA-Z_$][\w$]*)""").findAll(source).forEach { match ->
            val method = "${match.groupValues[1]}.${match.groupValues[2]}"
            val permission = SDK_PERMISSION_BY_METHOD[method]
            if (permission != null) {
                if (permission.isNotEmpty()) usedPermissions.add(permission)
            } else {
                unknownApis.add(method)
            }
        }
        return SdkUsageScan(usedPermissions = usedPermissions, unknownApis = unknownApis.distinct())
    }

    private fun reportSdkUsage(
        path: String,
        source: String,
        declaredPermissions: Set<String>,
        warnings: MutableList<Issue>,
    ) {
        val scan = scanSdkUsage(source)
        scan.usedPermissions.filter { it !in declaredPermissions }.forEach { permission ->
            warnings.add(
                Issue(
                    file = path,
                    message = "使用了 ${permissionDisplayName(permission)} 但未在 Manifest 中声明「$permission」，安装后该调用将被拒绝",
                )
            )
        }
        scan.unknownApis.forEach { api ->
            warnings.add(Issue(file = path, message = "未知的 SDK 调用「evolia.$api」，请核对 Evolia SDK 接口规范"))
        }
    }

    private fun permissionDisplayName(permission: String): String = when (permission) {
        ExtensionPermissions.STORAGE_READ -> "读取应用数据"
        ExtensionPermissions.STORAGE_WRITE -> "写入应用数据"
        ExtensionPermissions.NETWORK_REQUEST -> "网络请求"
        ExtensionPermissions.UI_TOAST -> "显示提示"
        ExtensionPermissions.UI_DIALOG -> "显示对话框"
        else -> permission
    }

    // ---------- 本地引用提取（纯函数可单测） ----------

    /**
     * 提取 HTML 中 src/href 引用的本地包内路径：排除远程（http/https/协议相对）、
     * data:、内联锚点。仅顶层引用（CSS url() / JS 动态加载为 V1 已知盲区）。
     */
    internal fun scanLocalAssetReferences(html: String): List<String> {
        val references = mutableListOf<String>()
        Regex("""(?:src|href)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
            val raw = match.groupValues[1].trim()
            val skipped = raw.isEmpty() ||
                raw.startsWith("//") ||
                raw.contains(':') || // http: https: data: javascript: 等带协议前缀
                raw.startsWith("#")
            if (!skipped) references.add(raw)
        }
        return references.distinct()
    }

    // ---------- 内容哈希（纯函数可单测） ----------

    /**
     * 工作区内容哈希：排序后逐文件 SHA-256(relpath + 0x00 + bytes)。
     * 排除 [WORKSPACE_META_FILE]（元数据自身随校验结果写入，计入会自指失效）。
     * 同一实现内确定性成立，仅用于「校验后是否被修改」比对，非安全摘要。
     */
    fun computeContentHash(files: List<String>, readFile: (String) -> ByteArray?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.sorted().forEach { path ->
            if (path == ExtensionCreator.WORKSPACE_META_FILE) return@forEach
            digest.update(path.encodeToByteArray())
            digest.update(byteArrayOf(0))
            digest.update(readFile(path) ?: ByteArray(0))
        }
        return Base64.getEncoder().encodeToString(digest.digest())
    }
}
