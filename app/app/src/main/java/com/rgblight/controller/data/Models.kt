package com.rgblight.controller.data

/** Light command sent to FPGA: 0xAA + CMD + R + G + B + XOR */
data class LightCommand(
    val mode: Int,   // 0=static, 1=breathing, 2=chasing
    val r: Int,      // 0..63 (6-bit)
    val g: Int,
    val b: Int
) {
    /** Encode to 6-byte frame for FPGA */
    fun toFrame(): ByteArray {
        val cmd = (mode shl 6) and 0xC0  // bits [7:6]
        val xor = 0xAA.toByte() xor
                  cmd.toByte() xor
                  r.toByte() xor
                  g.toByte() xor
                  b.toByte()
        return byteArrayOf(
            0xAA.toByte(),          // Frame header
            cmd.toByte(),            // CMD byte
            (r and 0x3F).toByte(),  // R (6-bit)
            (g and 0x3F).toByte(),  // G
            (b and 0x3F).toByte(),  // B
            xor.toByte()             // XOR checksum
        )
    }
}

/**
 * Effect-config frame sent to FPGA: 0xAC + PARAM_ID + VALUE_H + VALUE_L + XOR (5 bytes)
 *
 * PARAM_ID: 0=breath period, 1=chase speed, 2=max brightness
 * Breath period:     VALUE = (period_seconds * 10) → 10..50  (1.0s..5.0s)
 * Chase speed:       VALUE = (period_seconds * 10) → 5..50   (0.5s..5.0s)
 * Max brightness:    VALUE = 10..100  (percentage)
 */
data class ConfigCommand(
    val paramId: Int,
    val value: Int
) {
    fun toFrame(): ByteArray {
        val xor = (0xAC.toByte()
            xor paramId.toByte()
            xor (value shr 8).toByte()
            xor (value and 0xFF).toByte()).toByte()
        return byteArrayOf(
            0xAC.toByte(),
            paramId.toByte(),
            (value shr 8).toByte(),
            (value and 0xFF).toByte(),
            xor
        )
    }
}

/** Saved scene preset */
data class Scene(
    val id: Long = System.currentTimeMillis(),
    val name: String,
    val mode: Int,
    val r: Int,
    val g: Int,
    val b: Int,
    val brightness: Int = 100
) {
    fun toCommand() = LightCommand(mode, r, g, b)
}

/** Bluetooth device info */
data class BtDevice(
    val name: String,
    val address: String,
    val rssi: Int = 0,
    val isConnected: Boolean = false,
    val isPaired: Boolean = false
)
