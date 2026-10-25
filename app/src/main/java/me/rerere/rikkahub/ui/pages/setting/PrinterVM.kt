package me.rerere.rikkahub.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.print.CreatePrintTaskResult
import me.rerere.rikkahub.data.print.PrintTaskManager
import me.rerere.rikkahub.data.print.PrinterDeviceConfig
import me.rerere.rikkahub.data.print.PrinterManager
import me.rerere.rikkahub.data.print.PrinterProtocol
import me.rerere.rikkahub.data.print.ScannedPrinter

/**
 * 打印机服务页 ViewModel：设备扫描/保存/移除、连接自检、自动打印开关与测试打印。
 */
class PrinterVM(
    private val settingsStore: SettingsStore,
    private val printerManager: PrinterManager,
    private val printTaskManager: PrintTaskManager,
) : ViewModel() {

    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings(init = true, providers = emptyList()))

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _scanResults = MutableStateFlow<List<ScannedPrinter>>(emptyList())
    val scanResults: StateFlow<List<ScannedPrinter>> = _scanResults.asStateFlow()

    /** 正在进行的操作："probe" / "test" / "save"，null 空闲 */
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    /** 最近一次操作结果提示（UI 以 Snackbar/文本展示后调用 clearResult） */
    private val _resultMessage = MutableStateFlow<String?>(null)
    val resultMessage: StateFlow<String?> = _resultMessage.asStateFlow()

    fun missingPermissions(): List<String> = printerManager.missingPermissions()

    /** 权限申请被拒绝后的提示 */
    fun notifyPermissionDenied() {
        _resultMessage.value = "未授予蓝牙权限，无法扫描打印机。请授予「附近的设备」权限后重试"
    }

    fun clearResult() {
        _resultMessage.value = null
    }

    /** 扫描附近设备（调用前 UI 须已取得蓝牙权限） */
    fun scan() {
        if (_scanning.value) return
        viewModelScope.launch {
            _scanning.value = true
            _scanResults.value = emptyList()
            try {
                _scanResults.value = printerManager.scanPrinters()
            } catch (e: Exception) {
                _resultMessage.value = e.message ?: "扫描失败"
            } finally {
                _scanning.value = false
            }
        }
    }

    /** 保存并验证设备：验证通过才写入配置（失败不标记配置成功） */
    fun saveDevice(printer: ScannedPrinter) {
        if (_busy.value != null) return
        viewModelScope.launch {
            _busy.value = "save"
            try {
                val error = printerManager.probeDevice(printer.address, PrinterProtocol.PD01)
                if (error == null) {
                    printerManager.updateConfig { config ->
                        config.copy(address = printer.address, name = printer.name, protocol = PrinterProtocol.PD01)
                    }
                    _resultMessage.value = "已添加打印机：${printer.name}"
                } else {
                    _resultMessage.value = "设备验证失败：${error.message ?: "无法连接"}，请确认打印机已开机且靠近手机"
                }
            } finally {
                _busy.value = null
            }
        }
    }

    /** 移除已保存设备并释放连接 */
    fun removeDevice() {
        viewModelScope.launch {
            printerManager.releaseConnection()
            printerManager.updateConfig { PrinterDeviceConfig() }
            _resultMessage.value = "已移除打印机配置"
        }
    }

    /** 自动打印开关（仅影响之后创建的新任务） */
    fun setAutoPrint(enabled: Boolean) {
        viewModelScope.launch {
            printerManager.updateConfig { it.copy(autoPrint = enabled) }
        }
    }

    /** 打印浓度百分比（30..100，下次打印生效，无需重连） */
    fun setEnergyPercent(percent: Int) {
        viewModelScope.launch {
            printerManager.updateConfig { it.copy(energyPercent = percent.coerceIn(30, 100)) }
        }
    }

    /** 重新连接自检：连接 → 协议校验 → 释放 */
    fun probePrinter() {
        if (_busy.value != null) return
        viewModelScope.launch {
            _busy.value = "probe"
            try {
                val error = printerManager.probePrinter()
                _resultMessage.value = if (error == null) {
                    "连接成功，打印机就绪"
                } else {
                    "连接失败：${error.message ?: "无法连接"}"
                }
            } finally {
                _busy.value = null
            }
        }
    }

    /** 测试打印：用户主动触发，走与正式打印相同的链路 */
    fun testPrint() {
        if (_busy.value != null) return
        viewModelScope.launch {
            _busy.value = "test"
            try {
                when (val result = printTaskManager.createTestPrint()) {
                    is CreatePrintTaskResult.Created ->
                        _resultMessage.value = "测试打印任务已创建，正在执行"
                    is CreatePrintTaskResult.Rejected ->
                        _resultMessage.value = result.message
                }
            } finally {
                _busy.value = null
            }
        }
    }
}
