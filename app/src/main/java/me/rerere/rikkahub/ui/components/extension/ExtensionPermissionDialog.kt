package me.rerere.rikkahub.ui.components.extension

import androidx.compose.runtime.Composable
import me.rerere.rikkahub.common.ui.components.ConfirmDialog
import me.rerere.rikkahub.data.extensions.ExtensionManifest
import me.rerere.rikkahub.data.extensions.ExtensionPermissions
import java.io.File

/**
 * 扩展权限显示名（安装确认弹窗 / Mini App 运行时授权弹窗共用）。
 */
fun permissionDisplayName(permission: String): String = when (permission) {
    ExtensionPermissions.STORAGE_READ -> "读取应用数据"
    ExtensionPermissions.STORAGE_WRITE -> "写入应用数据"
    ExtensionPermissions.NETWORK_REQUEST -> "网络请求"
    ExtensionPermissions.UI_TOAST -> "显示提示（Toast）"
    ExtensionPermissions.UI_DIALOG -> "显示对话框"
    else -> permission
}

/**
 * 待用户确认权限的安装/更新请求（2026-11-19 需求：安装时请求权限，
 * 被拒则不安装）。二选一提供来源：[tempZipFile]（.evolia 导入）或
 * [sourceDir]（本地创建工作区）。
 */
data class PendingExtensionPermissionRequest(
    val manifest: ExtensionManifest,
    /** 本次需要新授权的权限清单（更新时仅含新增权限） */
    val permissions: List<String>,
    val isUpdate: Boolean,
    val tempZipFile: File? = null,
    val sourceDir: File? = null,
) {
    val extensionName: String
        get() = manifest.name.ifBlank { manifest.appId }
}

/**
 * 安装/更新扩展前的权限确认弹窗：列出本次需要新授权的权限，
 * 确认后随安装直接授权；拒绝则调用方放弃本次安装/更新。
 */
@Composable
fun ExtensionPermissionDialog(
    request: PendingExtensionPermissionRequest,
    onConfirm: (PendingExtensionPermissionRequest) -> Unit,
    onDismiss: (PendingExtensionPermissionRequest) -> Unit,
) {
    val action = if (request.isUpdate) "更新" else "安装"
    val listText = request.permissions.joinToString(separator = "\n") {
        "· ${permissionDisplayName(it)}（$it）"
    }
    ConfirmDialog(
        onDismissRequest = { onDismiss(request) },
        onConfirm = { onConfirm(request) },
        title = "权限申请",
        confirmText = "授权并$action",
        dismissText = "取消",
        text = "「${request.extensionName}」$action 需要以下权限：\n$listText\n\n拒绝授权将取消本次$action。",
    )
}
