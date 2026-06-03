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
