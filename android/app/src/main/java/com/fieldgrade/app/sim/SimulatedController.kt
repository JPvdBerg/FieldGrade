package com.fieldgrade.app.sim

import com.fieldgrade.app.transport.ByteLink
import com.fieldgrade.app.transport.ControlCommand
import com.fieldgrade.app.transport.ControllerProtocol
import com.fieldgrade.app.transport.LineFramer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.sign

/**
 * An in-process stand-in for the ESP32 hydraulic controller, presented as a
 * [ByteLink] so the tablet talks to it exactly as it would to real hardware.
 *
 * One job: behave like the controller. It enforces the same safety rules as
 * `tools/controller_simulator.py` and `firmware/src/main.cpp` — this is the
 * third implementation of that same state machine, which is the point: if the
 * three ever disagree, a test fails rather than a machine moves.
 *
 * It is a **development harness, not evidence**. Passing against this model
 * proves the tablet speaks the protocol correctly. It proves nothing about
 * real hydraulics, real timing jitter, or the real ESP32 — those stay Tier B,
 * behind the bench-HIL gate in PHASES_PLAN.
 *
 * Usage: [write] accepts command frames from the tablet, [tick] advances the
 * control loop and queues a status frame, [read] returns queued status bytes.
 */
class SimulatedController(
    private val cfg: Config = Config(),
    private val supplyMv: Int = 13_800
) : ByteLink {

    data class Config(
        val maxDuty: Int = 820,
        val maxSlewPerLoop: Int = 18,
        val commandTimeoutMs: Long = 250,
        val gainPerMm: Int = 12,
        val directionDeadtimeMs: Long = 60
    )

    // Same Json configuration as ControllerProtocol, so the canonical form used
    // for CRC verification is byte-identical to what the tablet signed.
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private val framer = LineFramer()
    private val outbound = StringBuilder()

    private var nowMs: Long = 0
    private var lastValidMs: Long = Long.MIN_VALUE / 4
    private var lastSeq: Long = -1
    private var enabled = false
    private var requestedDuty = 0
    private var deadtimeUntilMs: Long = 0

    /** Signed applied duty: positive raises, negative lowers. */
    var applied: Int = 0
        private set

    var fault: Int = FAULT_NONE
        private set

    var estop: Boolean = false

    /** Counters the harness asserts on. */
    var acceptedFrames: Int = 0; private set
    var rejectedFrames: Int = 0; private set

    val raiseDuty: Int get() = if (applied > 0) applied else 0
    val lowerDuty: Int get() = if (applied < 0) -applied else 0

    val state: String
        get() = when {
            estop -> "ESTOP"
            applied != 0 -> "ACTIVE"
            else -> "NEUTRAL"
        }

    // ---------------------------------------------------------------- ByteLink
    override var isOpen: Boolean = true
        private set

    override fun write(bytes: ByteArray) {
        if (!isOpen) return
        for (line in framer.offer(bytes)) onFrame(line)
    }

    override fun read(): ByteArray {
        if (outbound.isEmpty()) return ByteArray(0)
        val out = outbound.toString().toByteArray(Charsets.UTF_8)
        outbound.setLength(0)
        return out
    }

    override fun close() {
        isOpen = false
    }

    // ---------------------------------------------------------------- protocol
    /** Validate and apply one command frame. Any rejection drives neutral. */
    private fun onFrame(line: String) {
        val cmd = try {
            json.decodeFromString<ControlCommand>(line)
        } catch (e: Exception) {
            rejectedFrames++
            requestNeutral(FAULT_BADFRAME)
            return
        }
        if (cmd.v != 1) {
            rejectedFrames++
            requestNeutral(FAULT_BADFRAME)
            return
        }
        val canonical = json.encodeToString(cmd.copy(crc16 = 0))
        if (ControllerProtocol.crc16Ccitt(canonical.encodeToByteArray()) != cmd.crc16) {
            rejectedFrames++
            requestNeutral(FAULT_BADFRAME)
            return
        }
        if (cmd.seq <= lastSeq) {                 // stale / replayed / reordered
            rejectedFrames++
            requestNeutral(FAULT_BADFRAME)
            return
        }

        lastSeq = cmd.seq
        enabled = cmd.enable
        requestedDuty = targetDutyFor(cmd.mode, cmd.target_mm, cmd.manual)
        lastValidMs = nowMs
        fault = FAULT_NONE
        acceptedFrames++
    }

    private fun targetDutyFor(mode: String, targetMm: Int, manual: Int): Int {
        val base = when (mode) {
            "MANUAL" -> manual                    // direct request, no gain
            "AUTO" -> targetMm * cfg.gainPerMm    // ALL control gain lives here
            else -> 0                             // HOLD
        }
        return base.coerceIn(-cfg.maxDuty, cfg.maxDuty)
    }

    private fun requestNeutral(f: Int) {
        requestedDuty = 0
        enabled = false
        fault = f
    }

    private fun hardZero(f: Int) {
        requestedDuty = 0
        applied = 0
        enabled = false
        fault = f
    }

    // ---------------------------------------------------------------- loop
    /** Advance the control loop to [atMs] and queue a status frame. */
    fun tick(atMs: Long) {
        nowMs = atMs

        if (estop) {
            hardZero(FAULT_ESTOP)                 // immediate, bypasses slew
            emitStatus()
            return
        }
        if (nowMs - lastValidMs >= cfg.commandTimeoutMs) {
            // Comms loss is the same safety class as e-stop: motion STOPS, it
            // does not coast down. This is what meets "neutral within 250 ms".
            hardZero(FAULT_TIMEOUT)
            emitStatus()
            return
        }

        var target = if (enabled) requestedDuty else 0

        // Mutual exclusion + dead-time: never cross zero directly, and pause
        // before energising the opposite channel.
        if (target != 0 && applied != 0 && sign(target.toDouble()) != sign(applied.toDouble())) {
            target = 0
        }
        if (applied == 0 && target != 0 && nowMs < deadtimeUntilMs) {
            target = 0
        }

        val delta = (target - applied).coerceIn(-cfg.maxSlewPerLoop, cfg.maxSlewPerLoop)
        val prev = applied
        applied += delta
        if (prev != 0 && applied == 0) deadtimeUntilMs = nowMs + cfg.directionDeadtimeMs

        emitStatus()
    }

    /** Byte-identical to the firmware's emitStatus() canonical form. */
    private fun emitStatus() {
        val ageMs = if (lastValidMs < 0) 0L else maxOf(0L, nowMs - lastValidMs)
        val body = canonicalStatus(lastSeq, state, applied, supplyMv, estop, fault, ageMs, 0)
        val crc = ControllerProtocol.crc16Ccitt(body.encodeToByteArray())
        outbound.append(
            canonicalStatus(lastSeq, state, applied, supplyMv, estop, fault, ageMs, crc)
        ).append('\n')
    }

    private fun canonicalStatus(
        seqAck: Long, state: String, output: Int, supply: Int,
        estop: Boolean, fault: Int, ageMs: Long, crc: Int
    ): String =
        "{\"v\":1,\"seq_ack\":$seqAck,\"state\":\"$state\"," +
            "\"output\":$output,\"supply_mv\":$supply,\"estop\":$estop," +
            "\"fault\":$fault,\"age_ms\":$ageMs,\"crc16\":$crc}"

    companion object {
        const val FAULT_NONE = 0
        const val FAULT_TIMEOUT = 1
        const val FAULT_ESTOP = 2
        const val FAULT_BADFRAME = 3
    }
}
