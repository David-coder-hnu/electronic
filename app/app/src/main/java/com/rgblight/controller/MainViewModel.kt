package com.rgblight.controller

import android.app.Application
import android.graphics.Color as AndroidColor
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rgblight.controller.data.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class HsvColor(
    val hue: Float = 0f,
    val saturation: Float = 1f,
    val value: Float = 1f
)

data class RgbColor(
    val r: Float = 0f,
    val g: Float = 0f,
    val b: Float = 0f
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val btManager = BleManager(application)
    private val sceneRepo = SceneRepository(application)

    // ── Color state ──
    private val _hsv = MutableStateFlow(HsvColor(hue = 0f, saturation = 1f, value = 1f))
    val hsv: StateFlow<HsvColor> = _hsv.asStateFlow()

    private val _rgb = MutableStateFlow(RgbColor(1f, 0f, 0f))
    val rgb: StateFlow<RgbColor> = _rgb.asStateFlow()

    // ── Mode: 0=static, 1=breathing, 2=chasing ──
    private val _mode = MutableStateFlow(0)
    val mode: StateFlow<Int> = _mode.asStateFlow()

    // ── Brightness 0..100 → 6-bit 0..63 on FPGA ──
    private val _brightness = MutableStateFlow(100)
    val brightness: StateFlow<Int> = _brightness.asStateFlow()

    // ── Effect parameters (sent via 0xAC config frame) ──
    private val _breathPeriod = MutableStateFlow(3.0f)      // seconds, 1.0..5.0
    val breathPeriod: StateFlow<Float> = _breathPeriod.asStateFlow()

    private val _chaseSpeed = MutableStateFlow(2.0f)        // seconds, 0.5..5.0
    val chaseSpeed: StateFlow<Float> = _chaseSpeed.asStateFlow()

    private val _maxBrightness = MutableStateFlow(100)      // 10..100
    val maxBrightness: StateFlow<Int> = _maxBrightness.asStateFlow()

    private val _autoReconnect = MutableStateFlow(true)
    val autoReconnect: StateFlow<Boolean> = _autoReconnect.asStateFlow()

    private val _sendConfirm = MutableStateFlow(true)
    val sendConfirm: StateFlow<Boolean> = _sendConfirm.asStateFlow()

    // ── Bluetooth state (delegated) ──
    val devices: StateFlow<List<BtDevice>> = btManager.devices
    val isScanning: StateFlow<Boolean> = btManager.isScanning
    val isConnected: StateFlow<Boolean> = btManager.isConnected
    val connectionError: StateFlow<String?> = btManager.connectionError

    // ── FPGA feedback (parsed from 0xBB frame via BleManager.rxData) ──
    private val _fpgaStatus = MutableStateFlow<FpgaStatus?>(null)
    val fpgaStatus: StateFlow<FpgaStatus?> = _fpgaStatus.asStateFlow()

    // ── Scenes ──
    private val _scenes = MutableStateFlow<List<Scene>>(emptyList())
    val scenes: StateFlow<List<Scene>> = _scenes.asStateFlow()

    // ── UI feedback ──
    private val _lastSentHex = MutableStateFlow("#FF0000")
    val lastSentHex: StateFlow<String> = _lastSentHex.asStateFlow()

    private val _sendFeedback = MutableStateFlow(false)
    val sendFeedback: StateFlow<Boolean> = _sendFeedback.asStateFlow()

    private var feedbackJob: Job? = null
    private var statusJob: Job? = null

    init {
        loadScenes()

        // Parse incoming 0xBB status frames from FPGA
        statusJob = viewModelScope.launch {
            btManager.rxData.collect { data ->
                if (data != null && data.size >= 6 && data[0] == 0xBB.toByte()) {
                    try {
                        val xor = (data[0].toInt() xor data[1].toInt() xor data[2].toInt() xor data[3].toInt() xor data[4].toInt()) and 0xFF
                        if (xor == (data[5].toInt() and 0xFF)) {
                            _fpgaStatus.value = FpgaStatus(
                                curMode = (data[1].toInt() shr 6) and 0x03,
                                btConnected = ((data[1].toInt() shr 5) and 0x01) == 1,
                                r = data[2].toInt() and 0xFF,
                                g = data[3].toInt() and 0xFF,
                                b = data[4].toInt() and 0xFF
                            )
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        statusJob?.cancel()
        feedbackJob?.cancel()
        btManager.close()
    }

    // ── Color actions ──

    fun setHsv(h: Float, s: Float, v: Float) {
        val clamped = HsvColor(
            hue = h.coerceIn(0f, 360f),
            saturation = s.coerceIn(0f, 1f),
            value = v.coerceIn(0f, 1f)
        )
        _hsv.value = clamped
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

    fun setMode(newMode: Int) { _mode.value = newMode.coerceIn(0, 2) }

    fun setBrightness(value: Int) { _brightness.value = value.coerceIn(0, 100) }

    // ── Settings actions ──

    fun setBreathPeriod(v: Float) {
        _breathPeriod.value = v.coerceIn(1f, 5f)
        sendConfig(0, (v * 10).toInt())
    }

    fun setChaseSpeed(v: Float) {
        _chaseSpeed.value = v.coerceIn(0.5f, 5f)
        sendConfig(1, (v * 10).toInt())
    }

    fun setMaxBrightness(v: Int) {
        _maxBrightness.value = v.coerceIn(10, 100)
        sendConfig(2, v)
    }

    fun setAutoReconnect(v: Boolean) {
        _autoReconnect.value = v
        btManager.autoReconnect = v
    }

    fun setSendConfirm(v: Boolean) { _sendConfirm.value = v }

    private fun sendConfig(paramId: Int, value: Int) {
        if (isConnected.value) {
            val ok = btManager.send(ConfigCommand(paramId, value).toFrame())
            if (!ok) {
                btManager.reportError("配置发送失败，请检查蓝牙连接")
            }
        }
    }

    // ── Send to FPGA ──

    fun sendColor() {
        val scale = _brightness.value / 100f
        val r6 = ((rgb.value.r * scale) * 63).toInt().coerceIn(0, 63)
        val g6 = ((rgb.value.g * scale) * 63).toInt().coerceIn(0, 63)
        val b6 = ((rgb.value.b * scale) * 63).toInt().coerceIn(0, 63)
        val cmd = LightCommand(mode = _mode.value, r = r6, g = g6, b = b6)
        val ok = btManager.send(cmd.toFrame())
        if (!ok) {
            btManager.reportError("发送失败，请检查蓝牙连接")
            return
        }

        _lastSentHex.value = String.format(
            "#%02X%02X%02X",
            (rgb.value.r * 255).toInt(),
            (rgb.value.g * 255).toInt(),
            (rgb.value.b * 255).toInt()
        )
        triggerFeedback()
    }

    fun sendScene(scene: Scene) {
        val scale = scene.brightness / 100f
        val r6 = ((scene.r / 63f * scale) * 63).toInt().coerceIn(0, 63)
        val g6 = ((scene.g / 63f * scale) * 63).toInt().coerceIn(0, 63)
        val b6 = ((scene.b / 63f * scale) * 63).toInt().coerceIn(0, 63)
        val cmd = LightCommand(mode = scene.mode, r = r6, g = g6, b = b6)
        val ok = btManager.send(cmd.toFrame())
        if (!ok) {
            btManager.reportError("发送失败，请检查蓝牙连接")
            return
        }

        _mode.value = scene.mode
        _brightness.value = scene.brightness
        setRgb(scene.r / 63f, scene.g / 63f, scene.b / 63f)
        triggerFeedback()
    }

    private fun triggerFeedback() {
        feedbackJob?.cancel()
        _sendFeedback.value = true
        feedbackJob = viewModelScope.launch {
            delay(800)
            _sendFeedback.value = false
        }
    }

    // ── Bluetooth actions ──

    /** Load paired devices from system (no scan — safe on all phones). */
    fun loadPairedDevices() = btManager.loadPairedDevices()
    /** Connect directly by MAC address. */
    fun connectByAddress(address: String) = btManager.connectByAddress(address)
    /** One-tap reconnect to last-known device. */
    fun reconnectLast() = btManager.reconnectLast()
    /** Last successfully connected MAC (null if never connected). */
    val lastMac: String? get() = btManager.lastMac

    fun startScan() = btManager.startScan()
    fun stopScan() = btManager.stopScan()
    fun connect(device: BtDevice) = btManager.connect(device)
    fun disconnect() = btManager.disconnect()

    // ── Scene actions ──

    fun saveScene(name: String) {
        val scale = _brightness.value / 100f
        val scene = Scene(
            name = name,
            mode = _mode.value,
            r = ((rgb.value.r * scale) * 63).toInt().coerceIn(0, 63),
            g = ((rgb.value.g * scale) * 63).toInt().coerceIn(0, 63),
            b = ((rgb.value.b * scale) * 63).toInt().coerceIn(0, 63),
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

/** Parsed FPGA status from 0xBB frame */
data class FpgaStatus(
    val curMode: Int,
    val btConnected: Boolean,
    val r: Int,
    val g: Int,
    val b: Int
)
