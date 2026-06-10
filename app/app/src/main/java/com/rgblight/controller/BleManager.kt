package com.rgblight.controller

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.rgblight.controller.data.BtDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE Manager for CH9143 Bluetooth module — production grade.
 *
 * CH9143 uses BLE GATT with a proprietary WCH service for UART transport:
 *   Service UUID:         0000ffe0-0000-1000-8000-00805f9b34fb
 *   TX characteristic:    0000ffe1  (notify — data FROM CH9143 → phone)
 *   RX characteristic:    0000ffe1  (write  — data TO CH9143 → FPGA)
 *   Config characteristic: 0000ffe2 (write — AT commands to CH9143 itself)
 *
 * Architecture:
 *   - Connection timeout: 15s. If GATT doesn't connect, cancel and report error.
 *   - CCCD write: mandatory for BLE notifications. Without it, RX is silent.
 *   - Auto-reconnect: optional, toggled via autoReconnect flag.
 *   - State is exposed as StateFlows for Compose integration.
 */
class BleManager(private val context: Context) {

    companion object {
        val SERVICE_UUID: UUID  = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val CHAR_UART_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        // CCCD — standard BLE descriptor UUID for enabling notifications
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val TAG = "BleManager"
        private const val CONNECT_TIMEOUT_MS = 15_000L
    }

    // ── Platform handles ──

    private val btAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE)
            as? android.bluetooth.BluetoothManager)?.adapter
    }

    private var btGatt: BluetoothGatt? = null
    private var txChar: BluetoothGattCharacteristic? = null   // notify (CH9143→phone)
    private var rxChar: BluetoothGattCharacteristic? = null   // write (phone→CH9143)

    private val handler = Handler(Looper.getMainLooper())
    private var connectTimeout: Runnable? = null
    private var reconnectAttempts = 0
    private var lastConnectedAddress: String? = null

    // ── Observable state (Compose-friendly) ──

    private val _devices = MutableStateFlow<List<BtDevice>>(emptyList())
    val devices: StateFlow<List<BtDevice>> = _devices.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

    private val _rxData = MutableStateFlow<ByteArray?>(null)
    val rxData: StateFlow<ByteArray?> = _rxData.asStateFlow()

    /** Toggle for Settings → auto-reconnect. Public so ViewModel can flip it. */
    var autoReconnect: Boolean = true

    // ── Permission check ──

    fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val result = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            return result == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    fun hasFullPermission(): Boolean {
        if (!hasPermission()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val result = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
            return result == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    // ── Scanning ──

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasFullPermission()) return
        _isScanning.value = true
        _devices.value = emptyList()

        val scanner = btAdapter?.bluetoothLeScanner ?: run {
            _isScanning.value = false
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        // Scan for devices advertising the WCH UART service
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
        )

        try {
            scanner.startScan(filters, settings, scanCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "Scan start failed: security", e)
            _isScanning.value = false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        try {
            btAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {}
        _isScanning.value = false
    }

    // ── Connection ──

    @SuppressLint("MissingPermission")
    fun connect(btDevice: BtDevice) {
        if (!hasPermission()) {
            _connectionError.value = "缺少蓝牙权限"
            return
        }
        stopScan()
        disconnectGatt()

        val device = btAdapter?.getRemoteDevice(btDevice.address)
        if (device == null) {
            _connectionError.value = "设备地址无效"
            return
        }

        lastConnectedAddress = btDevice.address
        _connectionError.value = null
        reconnectAttempts = 0

        Log.d(TAG, "Connecting to ${btDevice.address} (${btDevice.name})...")
        btGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)

        // Start connection timeout
        cancelTimeout()
        connectTimeout = Runnable {
            Log.e(TAG, "Connection timeout — disconnecting")
            _connectionError.value = "连接超时，请重试"
            disconnectGatt()
        }
        handler.postDelayed(connectTimeout!!, CONNECT_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        Log.d(TAG, "User-requested disconnect")
        cancelTimeout()
        lastConnectedAddress = null
        reconnectAttempts = 0
        autoReconnect = false   // user explicitly disconnected
        disconnectGatt()
    }

    @SuppressLint("MissingPermission")
    fun send(data: ByteArray) {
        if (!_isConnected.value) {
            Log.w(TAG, "send() called while disconnected")
            return
        }
        rxChar?.let { char ->
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            char.value = data
            val ok = btGatt?.writeCharacteristic(char) ?: false
            if (!ok) {
                Log.e(TAG, "writeCharacteristic returned false")
            }
        } ?: Log.e(TAG, "rxChar is null — cannot send")
    }

    // ── Teardown (call from ViewModel onCleared) ──

    fun close() {
        cancelTimeout()
        disconnectGatt()
    }

    // ── Internals ──

    private fun cancelTimeout() {
        connectTimeout?.let { handler.removeCallbacks(it) }
        connectTimeout = null
    }

    @SuppressLint("MissingPermission")
    private fun disconnectGatt() {
        btGatt?.disconnect()
        btGatt?.close()
        btGatt = null
        txChar = null
        rxChar = null
        _isConnected.value = false
    }

    @SuppressLint("MissingPermission")
    private fun tryReconnect() {
        if (!autoReconnect) return
        if (reconnectAttempts >= 3) {
            Log.w(TAG, "Max reconnect attempts reached")
            _connectionError.value = "自动重连失败，请手动连接"
            reconnectAttempts = 0
            return
        }
        val addr = lastConnectedAddress ?: return
        val device = btAdapter?.getRemoteDevice(addr) ?: return
        reconnectAttempts++
        Log.d(TAG, "Auto-reconnect attempt $reconnectAttempts/3 → $addr")
        // Slight delay between attempts
        handler.postDelayed({
            btGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }, reconnectAttempts * 2000L)
    }

    // ── Scan callback ──

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            val name = dev.name ?: "CH9143-${dev.address.takeLast(6)}"
            val existing = _devices.value.find { it.address == dev.address }
            if (existing == null) {
                _devices.value = _devices.value + BtDevice(
                    name = name,
                    address = dev.address,
                    rssi = result.rssi
                )
            } else {
                // Update RSSI in place
                _devices.value = _devices.value.map {
                    if (it.address == dev.address) it.copy(rssi = result.rssi) else it
                }
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            _isScanning.value = false
            val msg = when (errorCode) {
                ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "扫描已在运行"
                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "BLE 注册失败"
                ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "设备不支持 BLE 扫描"
                else -> "扫描失败 (code=$errorCode)"
            }
            Log.e(TAG, msg)
            _connectionError.value = msg
        }
    }

    // ── GATT callback ──

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status=$status newState=$newState")

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Connection state change with error status=$status")
                handler.post {
                    cancelTimeout()
                    _isConnected.value = false
                    _connectionError.value = "连接失败 (status=$status)"
                    tryReconnect()
                }
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected — requesting MTU + discovering services")
                    // Request MTU for potential larger frames
                    gatt.requestMtu(64)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected")
                    handler.post {
                        _isConnected.value = false
                        txChar = null
                        rxChar = null
                        tryReconnect()
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU changed: $mtu (status=$status)")
            // Proceed with service discovery regardless
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: status=$status")
                handler.post {
                    cancelTimeout()
                    _connectionError.value = "服务发现失败"
                    disconnectGatt()
                }
                return
            }

            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "CH9143 UART service 0xFFE0 not found — wrong device?")
                handler.post {
                    cancelTimeout()
                    _connectionError.value = "未找到 CH9143 串口服务\n请确认连接的是正确的设备"
                    disconnectGatt()
                }
                return
            }

            txChar = service.getCharacteristic(CHAR_UART_UUID)   // notify
            rxChar = service.getCharacteristic(CHAR_UART_UUID)   // write

            if (txChar == null || rxChar == null) {
                Log.e(TAG, "Characteristic 0xFFE1 not found")
                handler.post {
                    cancelTimeout()
                    _connectionError.value = "CH9143 特征值缺失"
                    disconnectGatt()
                }
                return
            }

            // Enable notifications via CCCD descriptor write
            val notifyOk = gatt.setCharacteristicNotification(txChar, true)
            Log.d(TAG, "setCharacteristicNotification → $notifyOk")

            val descriptor = txChar?.getDescriptor(CCCD_UUID)
            if (descriptor != null) {
                val wrote = descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                val success = gatt.writeDescriptor(descriptor)
                Log.d(TAG, "CCCD write → value=$wrote, success=$success")
            } else {
                Log.w(TAG, "CCCD descriptor not found — notifications may not work")
            }

            // Done — connection is ready
            handler.post {
                cancelTimeout()
                reconnectAttempts = 0
                _isConnected.value = true
                _connectionError.value = null
                Log.d(TAG, "CH9143 BLE UART ready ✓")
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            Log.d(TAG, "onDescriptorWrite status=$status uuid=${descriptor.uuid}")
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val data = characteristic.value ?: return
            Log.d(TAG, "RX: ${data.joinToString(" ") { "%02X".format(it) }}")
            // Push to observable for UI layer
            handler.post { _rxData.value = data.copyOf() }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Write failed: status=$status")
            }
        }
    }
}
