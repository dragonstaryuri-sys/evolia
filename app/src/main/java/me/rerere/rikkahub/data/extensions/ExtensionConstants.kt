package me.rerere.rikkahub.data.extensions

/**
 * Evolia Extensions 规范冻结常量（Phase 0，详设第二/五/九/十三章）。
 *
 * 所有扩展相关的格式约束、安全限制、权限与错误码必须集中在此定义，
 * 禁止散落在各处硬编码。
 */
object ExtensionConstants {

    /** 当前支持的 Manifest 规范版本范围（闭区间） */
    const val MIN_MANIFEST_VERSION = 1
    const val MAX_MANIFEST_VERSION = 1

    /**
     * app_id 冻结格式：小写字母开头，后接小写字母/数字/下划线（2~63 位），
     * 允许以 `.` 分隔多段（如 com.example.forum）。禁止通过前缀推断扩展身份。
     */
    const val APP_ID_REGEX = "^[a-z][a-z0-9_]{2,63}(\\.[a-z0-9_]+)*$"

    /**
     * SemVer 兼容版本号格式：major.minor.patch，允许可选 -prerelease / +build。
     */
    const val VERSION_REGEX =
        "^\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?(\\+[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?$"

    /** collection / tool_id 命名格式（tool_id 由 manifest 中单段命名，唯一标识 = app_id:tool_id） */
    const val TOOL_ID_REGEX = "^[a-z][a-z0-9_]{1,63}$"
    const val COLLECTION_NAME_REGEX = "^[a-z][a-z0-9_]{0,63}$"

    /** Tool 唯一标识分隔符 */
    const val TOOL_ID_SEPARATOR = ":"
}

/**
 * ZIP 扩展包安全限制（详设 5.5，集中定义）。
 */
object PackageLimits {
    const val MAX_PACKAGE_SIZE_BYTES: Long = 20L * 1024 * 1024      // 压缩体积 20 MB
    const val MAX_UNCOMPRESSED_SIZE_BYTES: Long = 50L * 1024 * 1024 // 解压总体积 50 MB
    const val MAX_FILE_COUNT = 2000                                  // 文件数量
    const val MAX_SINGLE_FILE_SIZE_BYTES: Long = 10L * 1024 * 1024   // 单文件 10 MB
    const val MAX_COMPRESSION_RATIO = 100                            // 解压比例上限
}

/**
 * 扩展权限集合（详设 13.5）。Manifest 中声明的权限只是申请，不代表已授权。
 */
object ExtensionPermissions {
    const val STORAGE_READ = "storage.read"
    const val STORAGE_WRITE = "storage.write"
    const val NETWORK_REQUEST = "network.request"
    const val UI_TOAST = "ui.toast"
    const val UI_DIALOG = "ui.dialog"

    /** 当前支持的全部权限；超出集合的声明在安装校验时报错，不得静默忽略 */
    val KNOWN_PERMISSIONS: Set<String> = setOf(
        STORAGE_READ, STORAGE_WRITE, NETWORK_REQUEST, UI_TOAST, UI_DIALOG
    )
}

/**
 * 扩展 Tool 执行错误码（详设 9.6）。
 */
object ExtensionToolError {
    const val EXTENSION_NOT_FOUND = "EXTENSION_NOT_FOUND"
    const val TOOL_NOT_FOUND = "TOOL_NOT_FOUND"
    const val EXTENSION_DISABLED = "EXTENSION_DISABLED"
    const val TOOL_DISABLED = "TOOL_DISABLED"
    const val ASSISTANT_NOT_AUTHORIZED = "ASSISTANT_NOT_AUTHORIZED"
    const val PERMISSION_DENIED = "PERMISSION_DENIED"
    const val INVALID_ARGUMENTS = "INVALID_ARGUMENTS"
    const val EXECUTION_TIMEOUT = "EXECUTION_TIMEOUT"
    const val EXECUTION_FAILED = "EXECUTION_FAILED"
    const val SDK_CALL_FAILED = "SDK_CALL_FAILED"
}

/**
 * 安装操作状态（详设 6.7 / 8.3），用于进程异常退出后的恢复。
 */
object ExtensionInstallOperationState {
    const val PREPARING = "PREPARING"
    const val FILES_STAGED = "FILES_STAGED"
    const val FILES_SWITCHED = "FILES_SWITCHED"
    const val DATABASE_COMMITTED = "DATABASE_COMMITTED"
    const val COMPLETED = "COMPLETED"
    const val ROLLBACK_REQUIRED = "ROLLBACK_REQUIRED"
}

/**
 * 扩展来源类型（详设 3.2）。source_type 与 created_origin 必须分开。
 */
object ExtensionSourceType {
    const val BUILTIN = "BUILTIN"
    const val LOCAL_CREATED = "LOCAL_CREATED"
    const val LOCAL_IMPORT = "LOCAL_IMPORT"
    const val GITHUB = "GITHUB"
}

/**
 * 扩展类型。
 */
object ExtensionType {
    const val TOOL = "tool"
    const val MINI_APP = "mini_app"
}
