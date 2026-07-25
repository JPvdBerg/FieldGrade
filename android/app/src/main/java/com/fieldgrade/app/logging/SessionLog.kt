package com.fieldgrade.app.logging

import com.fieldgrade.app.transport.ControlCommand
import com.fieldgrade.app.transport.StatusFrame

/**
 * Immutable, timestamped record of every command sent, status received, and event,
 * for troubleshooting and job records (design doc section 9, acceptance test 7).
 * Exportable as tab-separated text.
 */
class SessionLog {
    data class Entry(val tMs: Long, val dir: String, val text: String)

    private val entries = ArrayList<Entry>()

    @Synchronized
    fun command(tMs: Long, c: ControlCommand) = add(tMs, "TX",
        "CMD seq=${c.seq} mode=${c.mode} target_mm=${c.target_mm} manual=${c.manual} enable=${c.enable}")

    @Synchronized
    fun status(tMs: Long, s: StatusFrame) = add(tMs, "RX",
        "STA ack=${s.seq_ack} state=${s.state} out=${s.output} fault=${s.fault} estop=${s.estop} supply_mv=${s.supply_mv}")

    @Synchronized
    fun event(tMs: Long, msg: String) = add(tMs, "EV", msg)

    private fun add(tMs: Long, dir: String, text: String) {
        entries.add(Entry(tMs, dir, text))
    }

    @Synchronized
    fun snapshot(): List<Entry> = entries.toList()

    @Synchronized
    fun export(): String = buildString {
        for (e in entries) append("${e.tMs}\t${e.dir}\t${e.text}\n")
    }

    val size: Int @Synchronized get() = entries.size
}
