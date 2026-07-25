package com.fieldgrade.app.transport

import com.fieldgrade.app.logging.SessionLog

/**
 * Drives the command stream to the controller and consumes status frames.
 *
 * Design points (PHASES_PLAN Phase 2):
 *  - Commands are emitted at a fixed cadence (default 25 Hz) off an injected clock,
 *    NOT a UI-thread timer, so a busy or paused UI cannot stall the safety stream.
 *  - Sequence numbers are monotonic (the controller rejects stale/replayed seq).
 *  - Inbound bytes are reframed, CRC-verified, and decoded; bad frames are dropped
 *    and logged, never trusted.
 *  - On link loss nothing is sent, so the controller times out to neutral.
 *
 * Call [tick] from a real scheduler (e.g. a coroutine loop at >= the cadence).
 */
class CommandStreamer(
    private val link: ByteLink,
    private val supervisor: LinkSupervisor,
    private val clock: Clock,
    private val log: SessionLog? = null,
    private val periodMs: Long = 40
) {
    private val framer = LineFramer()
    // Nullable, not Long.MIN_VALUE: `now - Long.MIN_VALUE` overflows negative and the
    // cadence check would never fire, so the first command would never be sent.
    private var lastSendMs: Long? = null

    var seq: Long = 0
        private set
    var mode: String = "HOLD"
    var targetMm: Int = 0
    var manual: Int = 0
    var enable: Boolean = false

    fun tick() {
        // --- receive: reframe, verify, decode ---
        if (link.isOpen) {
            val inBytes = link.read()
            for (line in framer.offer(inBytes)) {
                val status = ControllerProtocol.decodeStatus(line)
                if (status != null) {
                    supervisor.onStatus(status)
                    log?.status(clock.nowMs(), status)
                } else {
                    log?.event(clock.nowMs(), "DROP invalid status frame")
                }
            }
        }
        supervisor.poll()

        // --- transmit at cadence ---
        val now = clock.nowMs()
        val due = lastSendMs?.let { now - it >= periodMs } ?: true
        if (link.isOpen && due) {
            seq += 1
            val cmd = ControlCommand(
                seq = seq, ts_ms = now, mode = mode,
                target_mm = targetMm, manual = manual, enable = enable
            )
            link.write(ControllerProtocol.encode(cmd))
            log?.command(now, cmd)
            lastSendMs = now
        }
    }
}
