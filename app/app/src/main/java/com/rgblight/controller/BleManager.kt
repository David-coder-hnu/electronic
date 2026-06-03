package com.rgblight.controller

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.rgblight.controller.data.BtDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * BLE Manager for CH9143 Bluetooth module.
 *
 * CH9143 uses BLE GATT with a custom service for UART transparent transmission.
 * Service UUID (WCH BLE UART):     0000ffe0-0000-1000-8000-00805f9b34fb
 * Characteristic TX (notify):       0000ffe1-0000-1000-8000-00805f9b34fb
 * Characteristic RX (write):        0000ffe1-0000-1000-8000-00805f9b34fb
 *
 * If using WCH official BleUart SDK, replace this class with BleUartManager from the SDK.
 */
class BleManager(private val context: Context) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val CHAR_TX_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        val CHAR_RX_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        const val TAG = "BleManager"
    }

    private val btAdapter: BluetoothAdapter? by lazy {
        // Fully-qualified cast to avoid shadowing this custom class
        (context.getSystemService(Context.BLUETOOTH_SERVICE)
            as? android.bluetooth.BluetoothManager)?.adapter
    }

    private var btGatt: BluetoothGatt? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var rxChar: BluetoothGattCharacteristic? = null

    private val _devices = MutableStateFlow<List<BtDevice>>(emptyList())
    val devices: StateFlow<List<BtDevice>> = _devices

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    fun hasPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED
        } else true
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasPermission()) return
        _isScanning.value = true
        _devices.value = emptyList()

        val scanner = btAdapter?.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        btAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        _isScanning.value = false
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        stopScan()
        btGatt?.close()
        btGatt = device.connectGatt(context, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        btGatt?.disconnect()
        btGatt?.close()
        btGatt = null
        _isConnected.value = false
    }

    @SuppressLint("MissingPermission")
    fun send(data: ByteArray) {
        rxChar?.let { char ->
            char.value = data
            btGatt?.writeCharacteristic(char)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            @Suppress("MissingPermission")
            val name = dev.name ?: "CH9143 (${dev.address.takeLast(6)})"
            val existing = _devices.value.find { it.address == dev.address }
            if (existing == null) {
                _devices.value = _devices.value + BtDevice(
                    name = name,
                    address = dev.address,
                    rssi = result.rssi
                )
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _isScanning.value = false
            Log.e(TAG, "Scan failed: $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "GATT connected, discovering services...")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "GATT disconnected")
                    _isConnected.value = false
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                if (service != null) {
                    txChar = service.getCharacteristic(CHAR_TX_UUID)
                    rxChar = service.getCharacteristic(CHAR_RX_UUID)
                    // Enable notifications on the TX characteristic
                    gatt.setCharacteristicNotification(txChar, true)
                    _isConnected.value = true
                    Log.d(TAG, "CH9143 BLE UART service discovered and ready")
                } else {
                    Log.e(TAG, "CH9143 UART service not found — is this a CH9143 device?")
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            // Data from FPGA (status feedback via 0xBB frames)
            val data = characteristic.value
            Log.d(TAG, "RX from FPGA: ${data?.joinToString { "%02X".format(it) }}")
        }
    }
}
