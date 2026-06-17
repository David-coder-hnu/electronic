package com.rgblight.controller

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.rgblight.controller.data.BtDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * BLE Manager for CH9143 — zero-scan architecture.
 *
 * Instead of BLE scanning (which crashes on many Chinese-ROM devices at the
 * native HAL level), the app relies on:
 *
 *   1. Paired devices   — user pairs CH9143 in phone Settings → Bluetooth,
 *                          then the app reads getBondedDevices().
 *   2. Last connection   — MAC address persisted to SharedPreferences so
 *                          next launch is one-tap reconnect.
 *   3. Manual MAC input  — fallback for users who know their module's MAC.
 *
 * CH9143 BLE GATT service:
 *   0000ffe0-0000-1000-8000-00805f9b34fb  (UART service)
 *   0000ffe1  (TX notify + RX write — shared characteristic)
 */
class BleManager(private val context: Context) {

    companion object {
        val SERVICE_UUID: UUID  = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val CHAR_UART_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val TAG = "BleManager"
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val PREFS_NAME = "ble_prefs"
        private const val KEY_LAST_ADDR = "last_mac"
    }

    // ── Platform handles ──

    private val btAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE)
            as? android.bluetooth.BluetoothManager)?.adapter
    }

    private var btGatt: BluetoothGatt? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var rxChar: BluetoothGattCharacteristic? = null

    private val handler = Handler(Looper.getMainLooper())
    private var connectTimeout: Runnable? = null
    private var reconnectAttempts = 0
    private var connecting = false

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Observable state ──

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

    /** Last successfully connected MAC (persisted). Null until first connect. */
    val lastMac: String?
        get() = prefs.getString(KEY_LAST_ADDR, null)

    var autoReconnect: Boolean = true

    /** Report a connection error to the UI (public setter). */
    fun reportError(msg: String) {
        _connectionError.value = msg
    }

    // ── Permission ──

    fun hasPermission(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        } catch (e: Throwable) {
            Log.e(TAG, "hasPermission check crashed", e)
            false
        }
    }

    // ── Paired devices (no scan — reads system pairing list) ──

    /**
     * Load paired BLE devices. This does NOT trigger a scan — it reads the
     * system's bonded-device database, which is populated when the user pairs
     * through phone Settings → Bluetooth. Safe on all ROMs.
     */
    @SuppressLint("MissingPermission")
    fun loadPairedDevices() {
        try {
            if (!hasPermission()) {
                _connectionError.value = "缺少蓝牙权限"
                return
            }

            val adapter = btAdapter
            if (adapter == null || !adapter.isEnabled) {
                _connectionError.value = "请先开启蓝牙"
                _devices.value = emptyList()
                return
            }

            @Suppress("DEPRECATION")
            val bonded: Set<BluetoothDevice> = adapter.bondedDevices ?: emptySet()
            val list = bonded.mapNotNull { dev ->
                try {
                    val name = dev.name ?: "CH9143"
                    BtDevice(name = name, address = dev.address, rssi = 0, isPaired = true)
                } catch (_: Throwable) { null }
            }
            _devices.value = list
            _connectionError.value = null

            // If last-connected device is still paired, mark it
            val last = prefs.getString(KEY_LAST_ADDR, null)
            if (last != null && list.any { it.address == last }) {
                _devices.value = _devices.value.map {
                    if (it.address == last) it.copy(isConnected = false) else it
                }
            }

            Log.d(TAG, "Paired devices: ${list.size}")
        } catch (e: Throwable) {
            Log.e(TAG, "loadPairedDevices crashed", e)
            _connectionError.value = "读取配对列表失败"
        }
    }

    // ── Connection (by address — scan-free) ──

    /** Connect by MAC address string. Does not require a scan result. */
    @SuppressLint("MissingPermission")
    fun connectByAddress(address: String) {
        if (!hasPermission()) {
            _connectionError.value = "缺少蓝牙权限"
            return
        }
        if (connecting) {
            Log.w(TAG, "Connection already in progress, ignoring")
            return
        }
        disconnectGatt()
        connecting = true

        val device: BluetoothDevice? = try {
            btAdapter?.getRemoteDevice(address)
        } catch (e: Throwable) {
            Log.e(TAG, "getRemoteDevice crashed", e)
            null
        }
        if (device == null) {
            _connectionError.value = "设备地址无效"
            return
        }

        _connectionError.value = null
        reconnectAttempts = 0

        Log.d(TAG, "Connecting to $address ...")
        btGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)

        cancelTimeout()
        connectTimeout = Runnable {
            Log.e(TAG, "Connection timeout")
            connecting = false
            _connectionError.value = "连接超时，请重试"
            disconnectGatt()
        }
        handler.postDelayed(connectTimeout!!, CONNECT_TIMEOUT_MS)
    }

    /** Connect by BtDevice (convenience wrapper). */
    fun connect(btDevice: BtDevice) = connectByAddress(btDevice.address)

    /** Quick reconnect to last-known device. */
    fun reconnectLast() {
        val addr = prefs.getString(KEY_LAST_ADDR, null)
        if (addr == null) {
            _connectionError.value = "没有上次连接的设备"
            return
        }
        connectByAddress(addr)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        Log.d(TAG, "User-requested disconnect")
        cancelTimeout()
        handler.removeCallbacksAndMessages(null)  // Clear all pending callbacks
        reconnectAttempts = 0
        connecting = false
        autoReconnect = false
        disconnectGatt()
    }

    // ── Data send ──

    @SuppressLint("MissingPermission")
    fun send(data: ByteArray): Boolean {
        if (!_isConnected.value) {
            Log.w(TAG, "send() called while disconnected")
            return false
        }
        val char = rxChar
        if (char == null) {
            Log.e(TAG, "rxChar is null — cannot send")
            return false
        }
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        @Suppress("DEPRECATION")
        char.value = data
        @Suppress("DEPRECATION")
        val ok = btGatt?.writeCharacteristic(char) ?: false
        if (!ok) {
            Log.e(TAG, "writeCharacteristic returned false")
            return false
        }
        return true
    }

    fun close() {
        cancelTimeout()
        disconnectGatt()
    }

    // ── BLE scanning (deprecated — kept as fallback, known crash source) ──

    @SuppressLint("MissingPermission")
    fun startScan() {
        try {
            if (!hasPermission()) {
                _connectionError.value = "缺少蓝牙权限"
                return
            }
            if (btAdapter?.isEnabled != true) {
                _connectionError.value = "请先开启蓝牙"
                return
            }
            if (_isScanning.value) return

            _isScanning.value = true
            _devices.value = emptyList()
            _connectionError.value = null

            val scanner = btAdapter?.bluetoothLeScanner
            if (scanner == null) {
                _isScanning.value = false
                _connectionError.value = "蓝牙正在初始化"
                return
            }

            // Use deprecated single-arg API — less likely to crash than 3-arg
            @Suppress("DEPRECATION")
            scanner.startScan(scanCallback)
            Log.d(TAG, "Scan started (fallback mode)")

            // Auto-stop after 10s
            handler.postDelayed({
                if (_isScanning.value) safeStopScan()
            }, 10_000L)

        } catch (e: Throwable) {
            Log.e(TAG, "startScan crashed", e)
            _isScanning.value = false
            _connectionError.value = "扫描失败，请使用配对方式连接"
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() = safeStopScan()

    @SuppressLint("MissingPermission")
    private fun safeStopScan() {
        try {
            @Suppress("DEPRECATION")
            btAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Throwable) {}
        _isScanning.value = false
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
            _connectionError.value = "自动重连失败"
            reconnectAttempts = 0
            return
        }
        val addr = prefs.getString(KEY_LAST_ADDR, null) ?: return
        val device = btAdapter?.getRemoteDevice(addr) ?: return
        reconnectAttempts++
        Log.d(TAG, "Auto-reconnect $reconnectAttempts/3 → $addr")
        handler.postDelayed({
            btGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }, reconnectAttempts * 2000L)
    }

    // ── Scan callback (fallback only) ──

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            try {
                val dev = result.device ?: return
                val name = try { dev.name ?: "CH9143" } catch (_: Throwable) { "CH9143" }
                val addr = try { dev.address } catch (_: Throwable) { "00:00:00:00:00:00" }
                val rssi = try { result.rssi } catch (_: Throwable) { -100 }
                val current = _devices.value
                val existing = current.find { it.address == addr }
                if (existing == null) {
                    _devices.value = current + BtDevice(name = name, address = addr, rssi = rssi)
                } else {
                    _devices.value = current.map { if (it.address == addr) it.copy(rssi = rssi) else it }
                }
            } catch (_: Throwable) {}
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }
        override fun onScanFailed(errorCode: Int) {
            _isScanning.value = false
            _connectionError.value = "扫描失败，请使用配对方式连接"
        }
    }

    // ── GATT callback ──

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status=$status newState=$newState")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handler.post {
                    cancelTimeout()
                    connecting = false
                    _isConnected.value = false
                    _connectionError.value = "连接失败 (status=$status)"
                    tryReconnect()
                }
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected")
                    gatt.requestMtu(64)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected")
                    handler.post {
                        _isConnected.value = false; txChar = null; rxChar = null
                        tryReconnect()
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU: $mtu"); gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handler.post {
                    cancelTimeout(); connecting = false; _connectionError.value = "服务发现失败"; disconnectGatt()
                }
                return
            }
            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                handler.post {
                    cancelTimeout(); connecting = false; _connectionError.value = "未找到 CH9143 串口服务\n请确认连接的是正确的设备"; disconnectGatt()
                }
                return
            }
            txChar = service.getCharacteristic(CHAR_UART_UUID)
            rxChar = service.getCharacteristic(CHAR_UART_UUID)
            if (txChar == null || rxChar == null) {
                handler.post {
                    cancelTimeout(); connecting = false; _connectionError.value = "CH9143 特征值缺失"; disconnectGatt()
                }
                return
            }
            @Suppress("DEPRECATION")
            gatt.setCharacteristicNotification(txChar, true)
            val desc = txChar?.getDescriptor(CCCD_UUID)
            if (desc != null) {
                @Suppress("DEPRECATION")
                desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(desc)
            }
            // Persist last successful connection
            val addr = gatt.device.address
            prefs.edit().putString(KEY_LAST_ADDR, addr).apply()
            handler.post {
                cancelTimeout(); reconnectAttempts = 0; connecting = false
                _isConnected.value = true; _connectionError.value = null
                Log.d(TAG, "CH9143 BLE UART ready ✓  ($addr)")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "onDescriptorWrite status=$status")
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val data = characteristic.value ?: return
            handler.post { _rxData.value = data.copyOf() }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) Log.e(TAG, "Write failed: status=$status")
        }
    }
}
