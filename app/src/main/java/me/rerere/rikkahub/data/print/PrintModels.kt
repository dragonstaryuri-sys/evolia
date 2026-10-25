package me.rerere.rikkahub.data.print

import java.io.File
import kotlinx.serialization.Serializable

/** 打印协议类型（第一期仅 PD01，为后续 ESC/POS 等协议预留边界） */
@Serializable
enum class PrinterProtocol(val displayName: String) {
    PD01("PD01 热敏打印机"),
}

/** 已保存的打印机配置（持久化在 DataStore，单台默认打印机） */
@Serializable
data class PrinterDeviceConfig(
    val address: String = "",   // BLE MAC；地址可能变化，连接失败时需重新扫描恢复
    val name: String = "",
    val protocol: PrinterProtocol = PrinterProtocol.PD01,
    val autoPrint: Boolean = false, // 自动打印开关，默认关闭
    // 打印浓度百分比（30..100）：50=浅、65=中、75=深，其余视为自定义；默认 55
    val energyPercent: Int = 55,
) {
    val isConfigured: Boolean get() = address.isNotBlank()
}

/** 打印任务状态（由 PrintTaskManager 集中流转，UI / 工具返回 / 持久化共用） */
enum class PrintTaskStatus {
    PREPARING,
    AWAITING_CONFIRMATION,
    CONNECTING,
    SENDING,
    COMPLETED,
    FAILED,
    CANCELLED,
    OUTCOME_UNKNOWN,    // 数据可能已部分送达，无法确认最终打印结果
    ;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED || this == OUTCOME_UNKNOWN
}

/** 打印失败分类，用于生成可操作的提示（蓝牙关闭 / 未配置 / 断连……） */
enum class PrintErrorCode {
    NOT_CONFIGURED,
    BLUETOOTH_OFF,
    PERMISSION_DENIED,
    DEVICE_NOT_FOUND,
    CONNECT_TIMEOUT,
    GATT_ERROR,
    SERVICE_NOT_FOUND,
    INIT_FAILED,
    WRITE_FAILED,
    DISCONNECTED,
    EMPTY_CONTENT,
    RENDER_FAILED,
    CANCELLED_DURING_SEND,
}

/** 打印任务（内存态：持久化记录 + 预览文件 + 取消请求标记） */
data class PrintTask(
    val record: PrintTaskRecord,
    val previewFile: File? = null,
    val cancelRequested: Boolean = false,   // 发送中请求取消（UI 显示"取消中"）
)

/** 扫描到的附近打印机 */
data class ScannedPrinter(
    val address: String,
    val name: String,
    val rssi: Int,
    val isLikelyPrinter: Boolean,   // 名称/广播特征命中 PD01 类设备
)

/** 创建打印任务的结果（工具返回与测试打印共用） */
sealed interface CreatePrintTaskResult {
    data class Created(val taskId: String, val autoPrint: Boolean) : CreatePrintTaskResult
    data class Rejected(val errorCode: PrintErrorCode, val message: String) : CreatePrintTaskResult
}

/** 根据错误分类生成面向用户的可操作提示 */
fun humanizePrintError(error: PrintException): String {
    val detail = error.message?.takeIf { it.isNotBlank() }?.let { "：$it" } ?: ""
    return when (error.errorCode) {
        PrintErrorCode.NOT_CONFIGURED -> "尚未配置打印机，请先在 设置 → 扩展服务 → 打印机服务 中添加设备$detail"
        PrintErrorCode.BLUETOOTH_OFF -> "蓝牙未开启，请打开蓝牙后重试$detail"
        PrintErrorCode.PERMISSION_DENIED -> "缺少蓝牙权限，请到系统设置授予 Evolia「附近的设备」权限$detail"
        PrintErrorCode.DEVICE_NOT_FOUND -> "未找到打印机，请确认打印机已开机且靠近手机，或重新扫描添加$detail"
        PrintErrorCode.CONNECT_TIMEOUT -> "连接打印机超时，请确认打印机已唤醒并靠近手机后重试$detail"
        PrintErrorCode.GATT_ERROR -> "蓝牙连接失败，请尝试重新连接$detail"
        PrintErrorCode.SERVICE_NOT_FOUND -> "打印机协议服务不可用，请确认设备为 PD01 兼容打印机$detail"
        PrintErrorCode.INIT_FAILED -> "打印机初始化失败$detail"
        PrintErrorCode.WRITE_FAILED -> "打印数据写入失败$detail"
        PrintErrorCode.DISCONNECTED -> "与打印机的连接已断开$detail"
        PrintErrorCode.EMPTY_CONTENT -> "打印内容为空$detail"
        PrintErrorCode.RENDER_FAILED -> "内容排版失败$detail"
        PrintErrorCode.CANCELLED_DURING_SEND -> "打印已中止，可能产生部分出纸$detail"
    }
}

/** 打印链路异常：携带面向用户的错误分类，由连接 / 适配 / 任务各层抛出 */
class PrintException(
    val errorCode: PrintErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** 打印任务持久化记录（JSON 文件存储；content 仅未完成任务保留） */
@Serializable
data class PrintTaskRecord(
    val taskId: String,
    val title: String,
    val status: PrintTaskStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val deviceName: String? = null,
    val rowCount: Int = 0,      // 点阵总行数
    val sentRows: Int = 0,      // 已发送行数（发送进度）
    val errorCode: PrintErrorCode? = null,
    val errorMessage: String? = null,
    val content: String? = null,
)
