package com.fieldgrade.app.transport

/**
 * Tracks the health of the link to the controller and reports it honestly.
 *
 * The tablet is supervisory: it can never *make* the machine safe, but it must
 * never lie about the link. A controller-reported fault or e-stop is surfaced and
 * never suppressed (safety constraint 8). If status frames stop arriving, the link
 * goes STALE — the operator is told, and the controller independently times out to
 * neutral after 250 ms because the command stream has stopped.
 */
class LinkSupervisor(
    private val clock: Clock,
    private val staleAfterMs: Long = 250
) {
    var state: LinkState = LinkState.DISCONNECTED
        private set
    var lastStatus: StatusFrame? = null
        private set

    private var lastStatusMs: Long = Long.MIN_VALUE

    fun onOpen() {
        state = LinkState.CONNECTED
        lastStatusMs = clock.nowMs()
    }

    fun onClosed() {
        state = LinkState.DISCONNECTED
        lastStatus = null
    }

    /** A CRC-verified status frame arrived. */
    fun onStatus(frame: StatusFrame) {
        lastStatus = frame
        lastStatusMs = clock.nowMs()
        state = if (frame.fault != 0 || frame.estop) LinkState.FAULTED else LinkState.CONNECTED
    }

    /** Recompute staleness against the clock; call every loop. */
    fun poll(): LinkState {
        if (state == LinkState.DISCONNECTED) return state
        if (clock.nowMs() - lastStatusMs > staleAfterMs) state = LinkState.STALE
        return state
    }

    /** Current controller fault code, never suppressed (0 = none). */
    val currentFault: Int get() = lastStatus?.fault ?: 0
}
