package com.rgblight.controller

import android.app.Application
import android.graphics.Color as AndroidColor
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rgblight.controller.data.BtDevice
import com.rgblight.controller.data.LightCommand
import com.rgblight.controller.data.Scene
import com.rgblight.controller.data.SceneRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HsvColor(
    val hue: Float = 0f,        // 0..360
    val saturation: Float = 1f,  // 0..1
    val value: Float = 1f        // 0..1
)

data class RgbColor(
    val r: Float = 0f,  // 0..1
    val g: Float = 0f,
    val b: Float = 0f
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val btManager = BluetoothManager(application)
    private val sceneRepo = SceneRepository(application)

    // ── Color state ──

    private val _hsv = MutableStateFlow(HsvColor(hue = 0f, saturation = 1f, value = 1f))
    val hsv: StateFlow<HsvColor> = _hsv.asStateFlow()

    private val _rgb = MutableStateFlow(RgbColor(1f, 0f, 0f))
    val rgb: StateFlow<RgbColor> = _rgb.asStateFlow()

    // ── Mode ──

    private val _mode = MutableStateFlow(0)  // 0=static, 1=breathing, 2=chasing
    val mode: StateFlow<Int> = _mode.asStateFlow()

    // ── Brightness (0..100, maps to 6-bit 0..63 for FPGA) ──

    private val _brightness = MutableStateFlow(100)
    val brightness: StateFlow<Int> = _brightness.asStateFlow()

    // ── Bluetooth (delegated to BluetoothManager flows) ──

    val devices: StateFlow<List<BtDevice>> = btManager.devices
    val isScanning: StateFlow<Boolean> = btManager.isScanning
    val isConnected: StateFlow<Boolean> = btManager.isConnected

    // ── Scenes ──

    private val _scenes = MutableStateFlow<List<Scene>>(emptyList())
    val scenes: StateFlow<List<Scene>> = _scenes.asStateFlow()

    // ── UI state ──

    private val _lastSentHex = MutableStateFlow("#FF0000")
    val lastSentHex: StateFlow<String> = _lastSentHex.asStateFlow()

    private val _sendFeedback = MutableStateFlow(false)  // true → "已发送 ✓" for 0.5s
    val sendFeedback: StateFlow<Boolean> = _sendFeedback.asStateFlow()

    init {
        loadScenes()
    }

    // ── Color actions ──

    fun setHsv(h: Float, s: Float, v: Float) {
        val clamped = HsvColor(
            hue = h.coerceIn(0f, 360f),
            saturation = s.coerceIn(0f, 1f),
            value = v.coerceIn(0f, 1f)
        )
        _hsv.value = clamped
        // Convert HSV → RGB float
        val colorInt = AndroidColor.HSVToColor(floatArrayOf(clamped.hue, clamped.saturation, clamped.value))
        _rgb.value = RgbColor(
            r = AndroidColor.red(colorInt) / 255f,
            g = AndroidColor.green(colorInt) / 255f,
            b = AndroidColor.blue(colorInt) / 255f
        )
    }

    fun setRgb(r: Float, g: Float, b: Float) {
        val clamped = RgbColor(
            r = r.coerceIn(0f, 1f),
            g = g.coerceIn(0f, 1f),
            b = b.coerceIn(0f, 1f)
        )
        _rgb.value = clamped
        val hsvArr = FloatArray(3)
        AndroidColor.RGBToHSV(
            (clamped.r * 255).toInt(),
            (clamped.g * 255).toInt(),
            (clamped.b * 255).toInt(),
            hsvArr
        )
        _hsv.value = HsvColor(hue = hsvArr[0], saturation = hsvArr[1], value = hsvArr[2])
    }

    fun setMode(newMode: Int) {
        _mode.value = newMode.coerceIn(0, 2)
    }

    fun setBrightness(value: Int) {
        _brightness.value = value.coerceIn(0, 100)
    }

    // ── Send to FPGA ──

    fun sendColor() {
        val r6 = ((rgb.value.r * _brightness.value / 100f) * 63).toInt().coerceIn(0, 63)
        val g6 = ((rgb.value.g * _brightness.value / 100f) * 63).toInt().coerceIn(0, 63)
        val b6 = ((rgb.value.b * _brightness.value / 100f) * 63).toInt().coerceIn(0, 63)
        val cmd = LightCommand(mode = _mode.value, r = r6, g = g6, b = b6)
        btManager.send(cmd.toFrame())

        // Update HEX display
        _lastSentHex.value = String.format("#%02X%02X%02X",
            (rgb.value.r * 255).toInt(),
            (rgb.value.g * 255).toInt(),
            (rgb.value.b * 255).toInt()
        )

        // "已发送 ✓" feedback for 0.5s
        _sendFeedback.value = true
        viewModelScope.launch {
            kotlinx.coroutines.delay(500)
            _sendFeedback.value = false
        }
    }

    fun sendScene(scene: Scene) {
        val r6 = ((scene.r / 63f * scene.brightness / 100f) * 63).toInt().coerceIn(0, 63)
        val g6 = ((scene.g / 63f * scene.brightness / 100f) * 63).toInt().coerceIn(0, 63)
        val b6 = ((scene.b / 63f * scene.brightness / 100f) * 63).toInt().coerceIn(0, 63)
        val cmd = LightCommand(mode = scene.mode, r = r6, g = g6, b = b6)
        btManager.send(cmd.toFrame())

        // Sync UI to scene state
        _mode.value = scene.mode
        _brightness.value = scene.brightness
        setRgb(scene.r / 63f, scene.g / 63f, scene.b / 63f)

        _sendFeedback.value = true
        viewModelScope.launch {
            kotlinx.coroutines.delay(500)
            _sendFeedback.value = false
        }
    }

    // ── Bluetooth actions ──

    fun startScan() = btManager.startScan()
    fun stopScan() = btManager.stopScan()
    fun connect(device: BtDevice) {
        // BtDevice address → BluetoothDevice → connect
        val btAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        val remoteDevice = btAdapter?.getRemoteDevice(device.address)
        remoteDevice?.let { btManager.connect(it) }
    }
    fun disconnect() = btManager.disconnect()

    // ── Scene actions ──

    fun saveScene(name: String) {
        val scene = Scene(
            name = name,
            mode = _mode.value,
            r = ((rgb.value.r * _brightness.value / 100f) * 63).toInt().coerceIn(0, 63),
            g = ((rgb.value.g * _brightness.value / 100f) * 63).toInt().coerceIn(0, 63),
            b = ((rgb.value.b * _brightness.value / 100f) * 63).toInt().coerceIn(0, 63),
            brightness = _brightness.value
        )
        sceneRepo.save(scene)
        loadScenes()
    }

    fun deleteScene(scene: Scene) {
        sceneRepo.delete(scene.id)
        loadScenes()
    }

    fun renameScene(scene: Scene, newName: String) {
        sceneRepo.save(scene.copy(name = newName))
        loadScenes()
    }

    private fun loadScenes() {
        _scenes.value = sceneRepo.loadAll()
    }
}
