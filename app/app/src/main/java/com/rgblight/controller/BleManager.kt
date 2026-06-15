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
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.rgblight.controller.data.BtDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * BLE Manager for CH9143 Bluetooth module.
 *
 * CH9143 uses BLE GATT with a proprietary WCH service for UART transport:
 *   Service UUID:         0000ffe0-0000-1000-8000-00805f9b34fb
 *   TX characteristic:    0000ffe1  (notify — data FROM CH9143 → phone)
 *   RX characteristic:    0000ffe1  (write  — data TO CH9143 → FPGA)
 *
 * SCAN SAFETY — Lessons from Xiaomi/Huawei/OPPO/VIVO production crashes:
 *
 *   1. NO custom ScanSettings. Even ScanSettings.Builder().setScanMode()
 *      triggers native HAL crashes on some MIUI/HyperOS versions.
 *      Use the deprecated single-arg startScan(ScanCallback) which bypasses
 *      the problematic JNI path in many OEM BLE stacks.
 *
 *   2. 5-starts-per-30-seconds hard limit (Android 7+). Xiaomi enforces this
 *      aggressively and can crash the system Bluetooth process when exceeded.
 *      Minimum 6s cooldown between scan sessions.
 *
 *   3. stopScan() → startScan() with zero gap triggers "scan too frequently"
 *      on MIUI even on the first call. Never force-stop before starting.
 *
 *   4. ScanCallback lives in app process. MIUI PowerKeeper can suspend the
 *      app process, turning the callback into a zombie. The BLE stack then
 *      crashes trying to deliver results. Keep scans short (<15s).
 *
 *   5. NO ScanFilter / ParcelUuid anywhere — these crash most Chinese BLE
 *      chips at construction time.
 */
class BleManager(private val context: Context) {

    companion object {
        val SERVICE_UUID: UUID  = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val CHAR_UART_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val TAG = "BleManager"
        private const val CONNECT_TIMEOUT_MS = 15_000L

        // Android 7+ limit: max 5 startScan calls per 30 seconds.
        // We enforce 6s minimum between scan starts (5 × 6 = 30).
        private const val SCAN_COOLDOWN_MS = 6_000L

        // Auto-stop scan after 12s to avoid zombie-callback risk on MIUI.
        private const val SCAN_TIMEOUT_MS = 12_000L
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
    private var scanTimeout: Runnable? = null
    private var reconnectAttempts = 0
    private var lastConnectedAddress: String? = null

    // Scan throttle
    private var lastScanStartMs: Long = 0
    private var scanStartCount30s: Int = 0
    private var throttleWindowStartMs: Long = 0

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

    var autoReconnect: Boolean = true

    // ── Permission checks ──

    fun hasPermission(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
            } else {
                // Android 10-11: need ACCESS_FINE_LOCATION at runtime for BLE
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
            }
        } catch (e: Throwable) {
            Log.e(TAG, "hasPermission check crashed", e)
            false
        }
    }

    fun hasFullPermission(): Boolean {
        if (!hasPermission()) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED
            } else {
                true // covered by ACCESS_FINE_LOCATION in hasPermission()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "hasFullPermission check crashed", e)
            false
        }
    }

    // ── Scanning ──

    @SuppressLint("MissingPermission")
    fun startScan() {
        try {
            if (!hasFullPermission()) {
                _connectionError.value = "缺少蓝牙权限，请授权后重试"
                return
            }

            if (btAdapter?.isEnabled != true) {
                _connectionError.value = "请先开启蓝牙"
                return
            }

            // ── Throttle check: Android 7+ 5-starts-per-30s limit ──
            val now = SystemClock.elapsedRealtime()
            if (throttleWindowStartMs == 0L || now - throttleWindowStartMs > 30_000L) {
                throttleWindowStartMs = now
                scanStartCount30s = 0
            }
            if (scanStartCount30s >= 5) {
                val waitSec = ((throttleWindowStartMs + 30_000L - now) / 1000).toInt() + 1
                _connectionError.value = "扫描太频繁，请${waitSec}秒后重试"
                return
            }

            // Cooldown between individual scan sessions
            if (now - lastScanStartMs < SCAN_COOLDOWN_MS) {
                _connectionError.value = "请稍后再扫描"
                return
            }

            // Guard: don't start if already scanning
            if (_isScanning.value) {
                Log.d(TAG, "Scan already running — ignoring duplicate start")
                return
            }

            _isScanning.value = true
            _devices.value = emptyList()
            _connectionError.value = null
            lastScanStartMs = now
            scanStartCount30s++

            val scanner = btAdapter?.bluetoothLeScanner
            if (scanner == null) {
                Log.e(TAG, "BluetoothLeScanner is null — BT may still be initializing")
                _isScanning.value = false
                _connectionError.value = "蓝牙正在初始化，请稍后重试"
                return
            }

            // CRITICAL: Use the deprecated single-arg startScan(ScanCallback).
            // The 3-arg startScan(List, ScanSettings, ScanCallback) routes
            // through a different JNI path that crashes the native BLE HAL on
            // many Xiaomi/Huawei/OPPO/VIVO ROMs, even with default settings.
            // The deprecated API is simpler and universally stable.
            @Suppress("DEPRECATION")
            scanner.startScan(scanCallback)

            Log.d(TAG, "Scan started (cooldown=${now - lastScanStartMs}ms, count30s=$scanStartCount30s)")

            // Auto-stop after timeout to prevent zombie callback on MIUI
            cancelScanTimeout()
            scanTimeout = Runnable {
                Log.d(TAG, "Scan auto-timeout after ${SCAN_TIMEOUT_MS}ms")
                safeStopScan()
            }
            handler.postDelayed(scanTimeout!!, SCAN_TIMEOUT_MS)

        } catch (e: SecurityException) {
            Log.e(TAG, "startScan: SecurityException — missing permission", e)
            _isScanning.value = false
            _connectionError.value = "缺少蓝牙权限"
        } catch (e: Throwable) {
            Log.e(TAG, "startScan crashed", e)
            _isScanning.value = false
            _connectionError.value = "扫描启动失败"
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        safeStopScan()
        // Reset throttle on explicit user stop
        lastScanStartMs = 0
    }

    /** Internal stop that doesn't reset throttle state. */
    @SuppressLint("MissingPermission")
    private fun safeStopScan() {
        cancelScanTimeout()
        try {
            @Suppress("DEPRECATION")
            btAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Throwable) {}
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

        val device: BluetoothDevice? = try {
            btAdapter?.getRemoteDevice(btDevice.address)
        } catch (e: Throwable) {
            Log.e(TAG, "getRemoteDevice crashed", e)
            null
        }
        if (device == null) {
            _connectionError.value = "设备地址无效"
            return
        }

        lastConnectedAddress = btDevice.address
        _connectionError.value = null
        reconnectAttempts = 0

        Log.d(TAG, "Connecting to ${btDevice.address} (${btDevice.name})...")
        btGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)

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
        autoReconnect = false
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
            @Suppress("DEPRECATION")
            char.value = data
            @Suppress("DEPRECATION")
            val ok = btGatt?.writeCharacteristic(char) ?: false
            if (!ok) {
                Log.e(TAG, "writeCharacteristic returned false")
            }
        } ?: Log.e(TAG, "rxChar is null — cannot send")
    }

    fun close() {
        cancelTimeout()
        disconnectGatt()
    }

    // ── Internals ──

    private fun cancelScanTimeout() {
        scanTimeout?.let { handler.removeCallbacks(it) }
        scanTimeout = null
    }

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
        handler.postDelayed({
            btGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }, reconnectAttempts * 2000L)
    }

    // ── Scan callback ──

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            try {
                val dev: BluetoothDevice = result.device ?: return
                // getName()/getAddress() are hidden Binder IPC — guard each call
                val name: String = try {
                    dev.name ?: "CH9143-${safeTail(dev)}"
                } catch (_: Throwable) { "CH9143-${safeTail(dev)}" }
                val addr: String = try {
                    dev.address
                } catch (_: Throwable) { "00:00:00:00:00:00" }
                val rssi: Int = try {
                    result.rssi
                } catch (_: Throwable) { -100 }

                val current = _devices.value
                val existing = current.find { it.address == addr }
                if (existing == null) {
                    _devices.value = current + BtDevice(name = name, address = addr, rssi = rssi)
                } else {
                    _devices.value = current.map {
                        if (it.address == addr) it.copy(rssi = rssi) else it
                    }
                }
            } catch (_: Throwable) {
                Log.e(TAG, "onScanResult crashed — swallowed to keep scan alive")
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            _isScanning.value = false
            val msg = when (errorCode) {
                ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "扫描已在运行"
                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "BLE 注册失败，请重启蓝牙后重试"
                ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "设备不支持 BLE 扫描"
                ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "扫描太频繁，请稍后重试"
                else -> "扫描失败 (code=$errorCode)"
            }
            Log.e(TAG, msg)
            _connectionError.value = msg
        }
    }

    /** Safe MAC tail extraction — getAddress() can throw on some ROMs. */
    private fun safeTail(dev: BluetoothDevice): String {
        return try { dev.address.takeLast(6) } catch (_: Throwable) { "000000" }
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

            txChar = service.getCharacteristic(CHAR_UART_UUID)
            rxChar = service.getCharacteristic(CHAR_UART_UUID)

            if (txChar == null || rxChar == null) {
                Log.e(TAG, "Characteristic 0xFFE1 not found")
                handler.post {
                    cancelTimeout()
                    _connectionError.value = "CH9143 特征值缺失"
                    disconnectGatt()
                }
                return
            }

            @Suppress("DEPRECATION")
            val notifyOk = gatt.setCharacteristicNotification(txChar, true)
            Log.d(TAG, "setCharacteristicNotification → $notifyOk")

            val descriptor = txChar?.getDescriptor(CCCD_UUID)
            if (descriptor != null) {
                @Suppress("DEPRECATION")
                val wrote = descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                @Suppress("DEPRECATION")
                val success = gatt.writeDescriptor(descriptor)
                Log.d(TAG, "CCCD write → value=$wrote, success=$success")
            } else {
                Log.w(TAG, "CCCD descriptor not found — notifications may not work")
            }

            handler.post {
                cancelTimeout()
                reconnectAttempts = 0
                _isConnected.value = true
                _connectionError.value = null
                Log.d(TAG, "CH9143 BLE UART ready ✓")
            }
        }

        @Suppress("DEPRECATION")
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            Log.d(TAG, "onDescriptorWrite status=$status uuid=${descriptor.uuid}")
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val data = characteristic.value ?: return
            Log.d(TAG, "RX: ${data.joinToString(" ") { "%02X".format(it) }}")
            handler.post { _rxData.value = data.copyOf() }
        }

        @Suppress("DEPRECATION")
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
