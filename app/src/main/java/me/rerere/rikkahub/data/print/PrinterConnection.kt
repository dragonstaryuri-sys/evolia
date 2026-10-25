package me.rerere.rikkahub.data.print

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PD01 的 BLE 传输层：扫描、GATT 连接、服务发现、MTU 协商、通知订阅与
 * 分块写入。不包含任何打印协议知识（帧构造见 [Pd01Protocol]，编排见适配器）。
 *
 * 写入策略：协商 MTU 后按 min(123, mtu-3) 分块，块间 10ms 节流，
 * WRITE_TYPE_NO_RESPONSE（与参考实现一致）；Android 13+ 可同步获得写状态，
 * 旧版本依赖节奏控制 + 连接状态检查 + 回调错误兜底。
 */
@SuppressLint("MissingPermission") // 权限由 PrinterManager 在调用前统一校验
class PrinterConnection(private val appContext: Context) {

    /** 对外连接状态（供管理器/UI 观察） */
    enum class State { IDLE, CONNECTING, CONNECTED, DISCONNECTED }

    private val gattMutex = Mutex() // 串行化 GATT 操作（连接/发现/MTU/描述符）
    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var mtuSize = DEFAULT_MTU

    private val disposed = AtomicBoolean(false)
    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    private val _notifications = MutableSharedFlow<Pd01Notification>(extraBufferCapacity = 16)
    val notifications: SharedFlow<Pd01Notification> = _notifications

    /** 连接意外断开时触发（区别于主动 disconnect） */
    var onUnexpectedDisconnect: (() -> Unit)? = null

    // 各异步 GATT 操作的完成门（回调线程 complete，协程线程 await）
    private var connectGate: CompletableDeferred<Boolean>? = null
    private var servicesGate: CompletableDeferred<Boolean>? = null
    private var mtuGate: CompletableDeferred<Int>? = null
    private var descriptorGate: CompletableDeferred<Boolean>? = null

    /** 最近一次写回调的错误状态（旧版本 Android 的失败兜底），null 表示无错误 */
    @Volatile
    private var lastWriteError: Int? = null

    val isConnected: Boolean
        get() = _state.value == State.CONNECTED

    val negotiatedMtu: Int get() = mtuSize

    // ---------------- 扫描 ----------------

    /**
     * 扫描附近 BLE 设备（不做系统级过滤：部分 PD01 兼容机广播内容不一致，
     * 由调用方按名称/厂商数据在 UI 层筛选；停止时机由收集方控制）。
     */
    fun scan(): Flow<ScanResult> = callbackFlow {
        val adapter = bluetoothAdapter()
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || !adapter.isEnabled || scanner == null) {
            close(PrintException(PrintErrorCode.BLUETOOTH_OFF, "蓝牙不可用或未开启"))
            return@callbackFlow
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(result)
            }

            override fun onScanFailed(errorCode: Int) {
                close(PrintException(PrintErrorCode.GATT_ERROR, "BLE 扫描失败（errorCode=$errorCode）"))
            }
        }
        try {
            scanner.startScan(callback)
        } catch (e: SecurityException) {
            close(PrintException(PrintErrorCode.PERMISSION_DENIED, "缺少蓝牙扫描权限", e))
            return@callbackFlow
        }
        awaitClose {
            try {
                scanner.stopScan(callback)
            } catch (_: SecurityException) {
                // 权限已被回收，扫描随进程结束自然停止
            }
        }
    }

    // ---------------- 连接 ----------------

    /** 连接设备并完成服务发现 / MTU 协商 / 通知订阅，失败抛 [PrintException] */
    suspend fun connect(device: BluetoothDevice, timeoutMs: Long = CONNECT_TIMEOUT_MS) {
        if (disposed.get()) throw PrintException(PrintErrorCode.GATT_ERROR, "连接已释放")
        val adapter = bluetoothAdapter()
        if (adapter == null || !adapter.isEnabled) {
            throw PrintException(PrintErrorCode.BLUETOOTH_OFF, "蓝牙未开启")
        }
        gattMutex.withLock {
            _state.value = State.CONNECTING
            try {
                connectInternal(device, timeoutMs)
                _state.value = State.CONNECTED
            } catch (e: Exception) {
                closeGattQuietly()
                _state.value = State.DISCONNECTED
                throw e
            }
        }
    }

    private suspend fun connectInternal(device: BluetoothDevice, timeoutMs: Long) {
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> connectGate?.complete(status == BluetoothGatt.GATT_SUCCESS)
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        // 中止所有等待中的 GATT 操作
                        connectGate?.complete(false)
                        servicesGate?.complete(false)
                        mtuGate?.complete(-1)
                        descriptorGate?.complete(false)
                        // 主动 disconnect() 会先置位 disposed，此处仅上报意外断开
                        if (disposed.get()) return
                        if (_state.value == State.CONNECTED || _state.value == State.CONNECTING) {
                            _state.value = State.DISCONNECTED
                            onUnexpectedDisconnect?.invoke()
                        }
                    }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                servicesGate?.complete(status == BluetoothGatt.GATT_SUCCESS)
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS && mtu > 0) {
                    mtuSize = mtu
                }
                mtuGate?.complete(mtuSize)
            }

            override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                descriptorGate?.complete(status == BluetoothGatt.GATT_SUCCESS)
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    lastWriteError = status
                }
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                Pd01Notification.parse(value)?.let { _notifications.tryEmit(it) }
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    val value = characteristic.value ?: return
                    Pd01Notification.parse(value)?.let { _notifications.tryEmit(it) }
                }
            }
        }

        // 建立连接（status 133 等失败统一映射为 GATT_ERROR）
        val connectGateLocal = CompletableDeferred<Boolean>().also { connectGate = it }
        val handle = try {
            device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            throw PrintException(PrintErrorCode.PERMISSION_DENIED, "缺少蓝牙连接权限", e)
        } catch (e: IllegalArgumentException) {
            throw PrintException(PrintErrorCode.DEVICE_NOT_FOUND, "设备地址无效", e)
        }
        if (handle == null) throw PrintException(PrintErrorCode.GATT_ERROR, "系统无法创建 GATT 连接")
        gatt = handle

        val connectOk = withTimeoutOrNull(timeoutMs) { connectGateLocal.await() }
            ?: throw PrintException(PrintErrorCode.CONNECT_TIMEOUT, "连接打印机超时")
        if (!connectOk) throw PrintException(PrintErrorCode.GATT_ERROR, "GATT 连接失败")

        // 服务发现
        val servicesGateLocal = CompletableDeferred<Boolean>().also { servicesGate = it }
        val discovered = withTimeoutOrNull(SERVICE_DISCOVERY_TIMEOUT_MS) {
            gatt?.discoverServices()
            servicesGateLocal.await()
        } ?: throw PrintException(PrintErrorCode.CONNECT_TIMEOUT, "服务发现超时")
        if (!discovered) throw PrintException(PrintErrorCode.SERVICE_NOT_FOUND, "服务发现失败")

        // 校验服务与特征（不假定所有兼容设备固件一致）
        val service = gatt?.getService(UUID.fromString(Pd01Protocol.SERVICE_UUID))
            ?: throw PrintException(PrintErrorCode.SERVICE_NOT_FOUND, "未找到打印服务（AE30），请确认设备为 PD01 兼容打印机")
        writeCharacteristic = service.getCharacteristic(UUID.fromString(Pd01Protocol.WRITE_CHARACTERISTIC_UUID))
            ?.takeIf {
                it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            }
            ?: throw PrintException(PrintErrorCode.SERVICE_NOT_FOUND, "打印写入特征（AE01）不可用")
        notifyCharacteristic = service.getCharacteristic(UUID.fromString(Pd01Protocol.NOTIFY_CHARACTERISTIC_UUID))
            ?.takeIf { it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 }

        // MTU 协商（失败可容忍：退回默认 23，分块自动缩小）
        val mtuGateLocal = CompletableDeferred<Int>().also { mtuGate = it }
        withTimeoutOrNull(MTU_TIMEOUT_MS) {
            gatt?.requestMtu(REQUEST_MTU)
            mtuGateLocal.await()
        } // 结果无论成败均继续

        // 订阅状态通知（辅助信号，失败可容忍）
        enableNotifications(notifyCharacteristic)
    }

    private suspend fun enableNotifications(characteristic: BluetoothGattCharacteristic?) {
        val gatt = gatt ?: return
        characteristic ?: return
        val descriptor = characteristic.getDescriptor(UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG)) ?: return
        val gate = CompletableDeferred<Boolean>().also { descriptorGate = it }
        withTimeoutOrNull(DESCRIPTOR_TIMEOUT_MS) {
            try {
                gatt.setCharacteristicNotification(characteristic, true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                }
                gate.await()
            } catch (_: SecurityException) {
            }
        } // 失败可容忍：通知仅用于辅助状态
    }

    // ---------------- 写入（阻塞式数据泵，调用方须运行于 Dispatchers.IO） ----------------

    /**
     * 分块写入数据流（阻塞）。块大小 = min([chunkSizeLimit], MTU-3)，块间 [intervalMs] 节流。
     * [shouldAbort] 在块间调用（协作式取消：发送中取消只能停止后续数据，
     * 打印机可能已缓存部分数据，无法撤销）。
     * @return 已写入的字节数（中止时为中止前已写入量）
     */
    fun writeChunkedBlocking(
        data: ByteArray,
        chunkSizeLimit: Int = MAX_CHUNK_SIZE,
        intervalMs: Long = CHUNK_INTERVAL_MS,
        shouldAbort: () -> Boolean = { false },
        onProgress: (sentBytes: Int) -> Unit = {},
    ): Int {
        val chunkSize = minOf(chunkSizeLimit, mtuSize - 3).coerceAtLeast(MIN_CHUNK_SIZE)
        var offset = 0
        while (offset < data.size) {
            if (shouldAbort()) return offset
            val writeError = lastWriteError
            if (writeError != null) {
                lastWriteError = null
                throw PrintException(PrintErrorCode.WRITE_FAILED, "蓝牙写入失败（status=$writeError）")
            }
            if (!isConnected) {
                throw PrintException(PrintErrorCode.DISCONNECTED, "与打印机的连接已断开")
            }
            val end = minOf(offset + chunkSize, data.size)
            writeOnce(data.copyOfRange(offset, end), shouldAbort)
            offset = end
            onProgress(offset)
            if (offset < data.size) {
                try {
                    Thread.sleep(intervalMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw PrintException(PrintErrorCode.CANCELLED_DURING_SEND, "发送已中止，可能产生部分打印", e)
                }
            }
        }
        return offset
    }

    private fun writeOnce(chunk: ByteArray, shouldAbort: () -> Boolean) {
        val gatt = gatt ?: throw PrintException(PrintErrorCode.DISCONNECTED, "连接已断开")
        val characteristic = writeCharacteristic ?: throw PrintException(
            PrintErrorCode.SERVICE_NOT_FOUND, "写入特征不可用"
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                writeOnceModern(gatt, characteristic, chunk, shouldAbort)
            } else {
                writeOnceLegacy(gatt, characteristic, chunk, shouldAbort)
            }
        } catch (e: SecurityException) {
            throw PrintException(PrintErrorCode.PERMISSION_DENIED, "缺少蓝牙连接权限", e)
        }
    }

    /**
     * API 33+：writeCharacteristic 同步返回状态。
     * ERROR_GATT_WRITE_REQUEST_BUSY（201）表示栈内写入队列已满——打印机消费
     * 速度跟不上发送节奏（write-without-response 无应用层流控），属瞬态拥堵：
     * 指数退避后重试同一块数据，持续忙超过 [BUSY_TOTAL_TIMEOUT_MS] 才视为失败。
     */
    private fun writeOnceModern(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        chunk: ByteArray,
        shouldAbort: () -> Boolean,
    ) {
        var backoffMs = BUSY_BACKOFF_INITIAL_MS
        var waitedMs = 0L
        while (true) {
            val status = gatt.writeCharacteristic(
                characteristic, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            )
            when {
                status == BluetoothStatusCodes.SUCCESS -> return

                status == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY -> {
                    ensureBusyRetryAllowed(waitedMs, shouldAbort)
                    sleepBackoff(backoffMs)
                    waitedMs += backoffMs
                    backoffMs = minOf(backoffMs * 2, BUSY_BACKOFF_MAX_MS)
                }

                else -> throw PrintException(PrintErrorCode.WRITE_FAILED, "蓝牙写入失败（status=$status）")
            }
        }
    }

    /**
     * 旧版本：栈队列满时 writeCharacteristic 直接返回 false（无状态码），
     * 同样按瞬态拥堵退避重试。
     */
    @Suppress("DEPRECATION")
    private fun writeOnceLegacy(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        chunk: ByteArray,
        shouldAbort: () -> Boolean,
    ) {
        var backoffMs = BUSY_BACKOFF_INITIAL_MS
        var waitedMs = 0L
        while (true) {
            characteristic.value = chunk
            val accepted = gatt.writeCharacteristic(characteristic)
            if (accepted) return
            ensureBusyRetryAllowed(waitedMs, shouldAbort)
            sleepBackoff(backoffMs)
            waitedMs += backoffMs
            backoffMs = minOf(backoffMs * 2, BUSY_BACKOFF_MAX_MS)
        }
    }

    private fun ensureBusyRetryAllowed(waitedMs: Long, shouldAbort: () -> Boolean) {
        if (shouldAbort()) {
            throw PrintException(PrintErrorCode.CANCELLED_DURING_SEND, "发送已中止，可能产生部分打印")
        }
        if (!isConnected) {
            throw PrintException(PrintErrorCode.DISCONNECTED, "与打印机的连接已断开")
        }
        if (waitedMs >= BUSY_TOTAL_TIMEOUT_MS) {
            throw PrintException(
                PrintErrorCode.WRITE_FAILED,
                "打印机长时间未接收数据（可能缺纸、卡纸或过热保护），请检查打印机后重试"
            )
        }
    }

    private fun sleepBackoff(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw PrintException(PrintErrorCode.CANCELLED_DURING_SEND, "发送已中止，可能产生部分打印", e)
        }
    }

    // ---------------- 释放 ----------------

    /** 主动断开并释放 GATT 资源（幂等） */
    fun disconnect() {
        disposed.set(true)
        closeGattQuietly()
        _state.value = State.DISCONNECTED
    }

    private fun closeGattQuietly() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: SecurityException) {
        } catch (_: IllegalStateException) {
        }
        gatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
    }

    private fun bluetoothAdapter(): android.bluetooth.BluetoothAdapter? {
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter
    }

    companion object {
        private const val DEFAULT_MTU = 23
        private const val REQUEST_MTU = 247

        /** 参考实现验证过的安全分块与节流 */
        const val MAX_CHUNK_SIZE = 123
        const val MIN_CHUNK_SIZE = 20
        const val CHUNK_INTERVAL_MS = 10L

        /** 写入队列忙（ERROR_GATT_WRITE_REQUEST_BUSY）的退避参数 */
        private const val BUSY_BACKOFF_INITIAL_MS = 15L
        private const val BUSY_BACKOFF_MAX_MS = 120L

        /** 持续忙超过该时长视为打印机停止消费（缺纸/卡纸/过热），判定失败 */
        private const val BUSY_TOTAL_TIMEOUT_MS = 15_000L

        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val SERVICE_DISCOVERY_TIMEOUT_MS = 10_000L
        private const val MTU_TIMEOUT_MS = 5_000L
        private const val DESCRIPTOR_TIMEOUT_MS = 5_000L

        private const val CLIENT_CHARACTERISTIC_CONFIG = "00002902-0000-1000-8000-00805f9b34fb"
    }
}
