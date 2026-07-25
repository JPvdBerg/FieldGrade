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

@Serializable
data class StatusFrame(
    val v: Int = 1,
    val seq_ack: Long = -1,
    val state: String = "NEUTRAL",
    val output: Int = 0,
    val supply_mv: Int = 0,
    val estop: Boolean = false,
    val fault: Int = 0,
    val age_ms: Long = 0,
    val crc16: Int = 0
)

object ControllerProtocol {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /** Encode a command as a newline-terminated wire frame with a valid CRC. */
    fun encode(command: ControlCommand): ByteArray {
        val withoutCrc = command.copy(crc16 = 0)
        val canonical = json.encodeToString(withoutCrc)
        val crc = crc16Ccitt(canonical.encodeToByteArray())
        return (json.encodeToString(command.copy(crc16 = crc)) + "\n").encodeToByteArray()
    }

    /**
     * Decode an inbound status frame, verifying its CRC. Returns null on malformed
     * JSON, wrong version, or CRC mismatch — link corruption is never read as a
     * valid controller state.
     */
    fun decodeStatus(line: String): StatusFrame? {
        val frame = try {
            json.decodeFromString<StatusFrame>(line)
        } catch (e: Exception) {
            return null
        }
        if (frame.v != 1) return null
        val canonical = canonicalStatus(frame.copy(crc16 = 0))
        if (crc16Ccitt(canonical.encodeToByteArray()) != frame.crc16) return null
        return frame
    }

    /** Canonical status string, byte-identical to the controller's emitStatus(). */
    private fun canonicalStatus(f: StatusFrame): String =
        "{\"v\":${f.v},\"seq_ack\":${f.seq_ack},\"state\":\"${f.state}\"," +
        "\"output\":${f.output},\"supply_mv\":${f.supply_mv},\"estop\":${f.estop}," +
        "\"fault\":${f.fault},\"age_ms\":${f.age_ms},\"crc16\":${f.crc16}}"

    /** CRC-16/CCITT-FALSE: poly 0x1021, init 0xFFFF. Matches firmware + tools. */
    fun crc16Ccitt(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) { crc = if ((crc and 0x8000) != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF }
        }
        return crc
    }
}
