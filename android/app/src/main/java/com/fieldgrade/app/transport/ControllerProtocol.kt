package com.fieldgrade.app.transport

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ControlCommand(
    val v: Int = 1,
    val seq: Long,
    val ts_ms: Long,
    val mode: String,
    val target_mm: Int,
    val manual: Int,
    val enable: Boolean,
    val crc16: Int = 0
)

object ControllerProtocol {
    private val json = Json { encodeDefaults = true }

    fun encode(command: ControlCommand): ByteArray {
        val withoutCrc = command.copy(crc16 = 0)
        val canonical = json.encodeToString(withoutCrc)
        val crc = crc16Ccitt(canonical.encodeToByteArray())
        return (json.encodeToString(command.copy(crc16 = crc)) + "\n").encodeToByteArray()
    }

    fun crc16Ccitt(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) { crc = if ((crc and 0x8000) != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF }
        }
        return crc
    }
}
