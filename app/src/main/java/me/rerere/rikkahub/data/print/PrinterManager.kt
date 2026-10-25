package me.rerere.rikkahub.data.print

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.data.datastore.SettingsStore

/**
 * 打印机设备管理：配置读写、权限/蓝牙环境检查、扫描、按需连接（自动重连）与释放。
 *
 * 不保持常驻蓝牙连接：打印或检查时才连接（自动重连语义），
 * 任务结束后释放，避免连接泄漏。
 */
class PrinterManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
) {

    private val connectionMutex = Mutex()   // 串行化连接的建立与释放
    private var currentConnection: PrinterConnection? = null

    private val _connectionState = MutableStateFlow(PrinterConnection.State.IDLE)
    val connectionState: StateFlow<PrinterConnection.State> = _connectionState.asStateFlow()

    // ---------------- 配置 ----------------

    /** 当前打印机配置（快照读取） */
    fun printerConfig(): PrinterDeviceConfig {
        val settings = settingsStore.settingsFlow.value
        return settings.printerConfig
    }

    /** 更新配置（保存设备 / 自动打印开关等） */
    suspend fun updateConfig(transform: (PrinterDeviceConfig) -> PrinterDeviceConfig) {
        val current = settingsStore.settingsFlow.value
        settingsStore.update(current.copy(printerConfig = transform(current.printerConfig)))
    }

    // ---------------- 环境检查 ----------------

    /** 缺失的蓝牙相关运行时权限（S+ 为连接/扫描权限，旧版本为定位权限） */
    fun missingPermissions(): List<String> {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return required.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    fun isBluetoothEnabled(): Boolean {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter?.isEnabled == true
    }

    /** 打印/扫描前检查：返回 null 表示环境就绪，否则给出错误分类 */
    fun preflightError(): PrintErrorCode? = when {
        missingPermissions().isNotEmpty() -> PrintErrorCode.PERMISSION_DENIED
        !isBluetoothEnabled() -> PrintErrorCode.BLUETOOTH_OFF
        else -> null
    }

    // ---------------- 扫描 ----------------

    /**
     * 扫描附近 BLE 设备（[durationMs] 毫秒），按"疑似打印机优先 + 信号强度"排序。
     */
    suspend fun scanPrinters(durationMs: Long = SCAN_DURATION_MS): List<ScannedPrinter> {
        preflightError()?.let { throw PrintException(it, "无法开始扫描") }
        return withContext(Dispatchers.IO) {
            val scanner = PrinterConnection(context)
            val found = LinkedHashMap<String, ScannedPrinter>()
            withTimeoutOrNull(durationMs) {
                scanner.scan().collect { result ->
                    val name = result.scanRecord?.deviceName ?: result.device?.name ?: ""
                    val address = result.device?.address ?: return@collect
                    val serviceUuids = result.scanRecord?.serviceUuids
                    val isLikely = name.contains("PD01", ignoreCase = true) ||
                        name.contains("print", ignoreCase = true) ||
                        serviceUuids?.any { it.toString() == Pd01Protocol.SERVICE_UUID } == true ||
                        result.scanRecord?.manufacturerSpecificData?.let { data ->
                            (0 until data.size()).any { data.keyAt(it) == PD01_MANUFACTURER_ID }
                        } == true
                    val printer = ScannedPrinter(
                        address = address,
                        name = name.ifBlank { "未知设备" },
                        rssi = result.rssi,
                        isLikelyPrinter = isLikely,
                    )
                    // 保留信号最强的一条记录
                    val existing = found[address]
                    if (existing == null || printer.rssi > existing.rssi) {
                        found[address] = printer
                    }
                }
            }
            found.values.sortedWith(
                compareByDescending<ScannedPrinter> { it.isLikelyPrinter }.thenByDescending { it.rssi }
            )
        }
    }

    // ---------------- 连接 ----------------

    /**
     * 确保与已保存打印机建立连接（自动重连：需要时才连接，不做后台常驻）。
     * 失败抛 [PrintException]，由调用方决定重试或提示。
     */
    suspend fun ensureConnected(): PrinterConnection = connectionMutex.withLock {
        currentConnection?.takeIf { it.isConnected }?.let { return it }

        val config = printerConfig()
        if (!config.isConfigured) {
            throw PrintException(PrintErrorCode.NOT_CONFIGURED, "尚未配置打印机")
        }
        preflightError()?.let { code ->
            throw PrintException(code, "打印机连接前置检查未通过")
        }

        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
            ?: throw PrintException(PrintErrorCode.BLUETOOTH_OFF, "设备不支持蓝牙")
        val remote = try {
            adapter.getRemoteDevice(config.address)
        } catch (e: IllegalArgumentException) {
            throw PrintException(PrintErrorCode.DEVICE_NOT_FOUND, "已保存的打印机地址无效，请重新扫描添加", e)
        }

        _connectionState.value = PrinterConnection.State.CONNECTING
        try {
            val connection = PrinterConnection(context).apply {
                onUnexpectedDisconnect = {
                    _connectionState.value = PrinterConnection.State.DISCONNECTED
                }
            }
            connection.connect(remote)
            currentConnection = connection
            _connectionState.value = PrinterConnection.State.CONNECTED
            connection
        } catch (e: Exception) {
            _connectionState.value = PrinterConnection.State.DISCONNECTED
            throw e
        }
    }

    /** 释放当前连接（任务结束 / 设备移除后调用，幂等） */
    suspend fun releaseConnection() = connectionMutex.withLock {
        currentConnection?.let { connection ->
            runCatching { connection.disconnect() }
        }
        currentConnection = null
        _connectionState.value = PrinterConnection.State.DISCONNECTED
    }

    /**
     * 连接自检（设置页"重新连接/检查"按钮）：连接 → 校验协议能力 → 释放。
     * @return null 表示自检通过，否则为失败异常
     */
    suspend fun probePrinter(): PrintException? = try {
        val connection = ensureConnected()
        try {
            val config = printerConfig()
            PrinterAdapterFactory.create(config.protocol, config.energyPercent).verify(connection)
            null
        } finally {
            releaseConnection()
        }
    } catch (e: PrintException) {
        e
    }

    /**
     * 验证指定设备（添加打印机时调用）：连接 → 服务/特征校验 → 协议自检 → 释放。
     * 验证通过前调用方不得把设备标记为配置成功。
     */
    suspend fun probeDevice(address: String, protocol: PrinterProtocol): PrintException? {
        preflightError()?.let { code -> return PrintException(code, "无法连接设备") }
        return try {
            connectionMutex.withLock {
                // 释放现有连接后直连目标设备
                currentConnection?.let { connection -> runCatching { connection.disconnect() } }
                currentConnection = null

                val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val adapter = manager?.adapter
                    ?: return@withLock PrintException(PrintErrorCode.BLUETOOTH_OFF, "设备不支持蓝牙")
                val remote = try {
                    adapter.getRemoteDevice(address)
                } catch (e: IllegalArgumentException) {
                    return@withLock PrintException(PrintErrorCode.DEVICE_NOT_FOUND, "设备地址无效", e)
                }

                _connectionState.value = PrinterConnection.State.CONNECTING
                try {
                    val connection = PrinterConnection(context).apply {
                        onUnexpectedDisconnect = {
                            _connectionState.value = PrinterConnection.State.DISCONNECTED
                        }
                    }
                    connection.connect(remote)
                    currentConnection = connection
                    _connectionState.value = PrinterConnection.State.CONNECTED
                    PrinterAdapterFactory.create(protocol).verify(connection)
                    null
                } catch (e: PrintException) {
                    e
                } catch (e: Exception) {
                    PrintException(PrintErrorCode.GATT_ERROR, e.message ?: "连接失败", e)
                } finally {
                    currentConnection?.let { connection -> runCatching { connection.disconnect() } }
                    currentConnection = null
                    _connectionState.value = PrinterConnection.State.DISCONNECTED
                }
            }
        } catch (e: PrintException) {
            e
        }
    }

    companion object {
        private const val SCAN_DURATION_MS = 10_000L

        /** PD01 广播厂商 ID（0x08D1，协议文档） */
        private const val PD01_MANUFACTURER_ID = 0x08D1
    }
}

/** 打印机适配器工厂：新增协议时在此登记实现即可；能量按当前配置的浓度换算 */
object PrinterAdapterFactory {
    fun create(protocol: PrinterProtocol, energyPercent: Int = 55): PrinterAdapter = when (protocol) {
        PrinterProtocol.PD01 -> Pd01PrinterAdapter(energy = Pd01Protocol.energyFromPercent(energyPercent))
    }
}
