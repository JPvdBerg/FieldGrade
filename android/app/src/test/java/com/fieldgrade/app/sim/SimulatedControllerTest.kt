package com.fieldgrade.app.sim

import com.fieldgrade.app.transport.ControlCommand
import com.fieldgrade.app.transport.ControllerProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SimulatedController] in isolation, against the same safety rules the Python
 * reference model and the firmware enforce.
 *
 * This is deliberately a third implementation of one state machine. Where the
 * three disagree, one of them is wrong about something that moves hydraulics —
 * so the duplication is the test, not an accident.
 *
 * Note the shape of every test here: the command stream must be *kept alive* at
 * the tablet's cadence, because a controller that is not being talked to is
 * supposed to go neutral. Holding one command and waiting is not a control
 * test, it is a timeout test.
 */
class SimulatedControllerTest {

    private var seq = 0L

    private fun ctrl(): SimulatedController {
        seq = 0
        return SimulatedController()
    }

    private fun send(c: SimulatedController, mode: String,
                     targetMm: Int = 0, manual: Int = 0, enable: Boolean = true, at: Long = ++seq) {
        c.write(ControllerProtocol.encode(
            ControlCommand(seq = at, ts_ms = 0, mode = mode,
                target_mm = targetMm, manual = manual, enable = enable)
        ))
    }

    /**
     * Run the control loop for [durationMs], refreshing the command at 25 Hz the
     * way [com.fieldgrade.app.transport.CommandStreamer] does.
     * @return the simulated time reached.
     */
    private fun drive(
        c: SimulatedController, mode: String, targetMm: Int = 0, manual: Int = 0,
        enable: Boolean = true, durationMs: Long, fromMs: Long = 0,
        onLoop: (Long) -> Unit = {}
    ): Long {
        var t = fromMs
        var lastSend = Long.MIN_VALUE / 4
        val end = fromMs + durationMs
        while (t < end) {
            t += LOOP_MS
            if (t - lastSend >= 40) {
                send(c, mode, targetMm, manual, enable)
                lastSend = t
            }
            c.tick(t)
            onLoop(t)
        }
        return t
    }

    // ---- normal operation ----

    @Test fun a_valid_command_produces_bounded_output() {
        val c = ctrl()
        drive(c, "MANUAL", manual = 250, durationMs = 300)
        assertEquals(250, c.applied)
        assertEquals(SimulatedController.FAULT_NONE, c.fault)
        assertEquals("ACTIVE", c.state)
        assertTrue(c.acceptedFrames > 5)
    }

    @Test fun auto_applies_controller_side_gain_and_clamps() {
        val c = ctrl()
        // 100 mm x gain 12 = 1200, clamped to MAX_DUTY 820. Slew-limited at 18
        // per 10 ms loop, so reaching the clamp takes ~460 ms.
        drive(c, "AUTO", targetMm = 100, durationMs = 800)
        assertEquals(820, c.applied)
    }

    @Test fun auto_gain_is_linear_below_the_clamp() {
        val c = ctrl()
        drive(c, "AUTO", targetMm = 20, durationMs = 400)   // 20 x 12 = 240
        assertEquals(240, c.applied)
    }

    @Test fun hold_mode_is_neutral() {
        val c = ctrl()
        drive(c, "HOLD", targetMm = 100, durationMs = 300)
        assertEquals(0, c.applied)
        assertEquals("NEUTRAL", c.state)
    }

    @Test fun enable_false_is_neutral() {
        val c = ctrl()
        drive(c, "MANUAL", manual = 400, enable = false, durationMs = 300)
        assertEquals(0, c.applied)
    }

    @Test fun output_respects_the_per_loop_slew_limit() {
        val c = ctrl()
        var prev = 0
        drive(c, "MANUAL", manual = 820, durationMs = 800) {
            assertTrue("slew ${c.applied - prev} exceeds limit",
                kotlin.math.abs(c.applied - prev) <= 18)
            prev = c.applied
        }
        assertEquals(820, c.applied)
    }

    // ---- frame validation ----

    @Test fun a_corrupted_frame_drives_neutral_with_a_bad_frame_fault() {
        val c = ctrl()
        val t = drive(c, "MANUAL", manual = 300, durationMs = 300)
        assertEquals(300, c.applied)

        // Same frame, payload edited after the CRC was computed.
        val frame = String(ControllerProtocol.encode(
            ControlCommand(seq = ++seq, ts_ms = 0, mode = "MANUAL",
                target_mm = 0, manual = 300, enable = true))).trim()
        c.write((frame.replace("\"manual\":300", "\"manual\":700") + "\n").toByteArray())
        c.tick(t + LOOP_MS)

        assertEquals(SimulatedController.FAULT_BADFRAME, c.fault)
        assertEquals(1, c.rejectedFrames)
    }

    @Test fun malformed_json_is_rejected() {
        val c = ctrl()
        val t = drive(c, "MANUAL", manual = 200, durationMs = 300)
        c.write("{not json at all\n".toByteArray())
        c.tick(t + LOOP_MS)
        assertEquals(SimulatedController.FAULT_BADFRAME, c.fault)
        assertEquals(1, c.rejectedFrames)
    }

    @Test fun a_replayed_sequence_number_is_rejected() {
        val c = ctrl()
        val t = drive(c, "MANUAL", manual = 200, durationMs = 300)
        assertEquals(200, c.applied)

        send(c, "MANUAL", manual = 800, at = seq)      // same seq: replay
        c.tick(t + LOOP_MS)
        assertEquals(SimulatedController.FAULT_BADFRAME, c.fault)
        assertEquals(1, c.rejectedFrames)
    }

    @Test fun an_out_of_order_sequence_number_is_rejected() {
        val c = ctrl()
        val t = drive(c, "MANUAL", manual = 200, durationMs = 300)
        send(c, "MANUAL", manual = 800, at = seq - 1)  // older frame arriving late
        c.tick(t + LOOP_MS)
        assertEquals(SimulatedController.FAULT_BADFRAME, c.fault)
    }

    @Test fun a_rejected_frame_never_changes_the_output_target() {
        val c = ctrl()
        val t = drive(c, "MANUAL", manual = 200, durationMs = 300)
        send(c, "MANUAL", manual = 820, at = seq)      // replay demanding more output
        c.tick(t + LOOP_MS)
        // Neutral, not the larger value the rejected frame asked for.
        assertTrue("output rose on a rejected frame", c.applied <= 200)
    }

    // ---- fail-safe ----

    @Test fun silence_longer_than_the_timeout_drives_neutral() {
        val c = ctrl()
        var t = drive(c, "MANUAL", manual = 500, durationMs = 600)
        assertEquals(500, c.applied)

        t += 250                                        // stop talking
        c.tick(t)
        assertEquals(0, c.applied)
        assertEquals(SimulatedController.FAULT_TIMEOUT, c.fault)
    }

    @Test fun the_timeout_is_a_hard_zero_not_a_ramp_down() {
        val c = ctrl()
        var t = drive(c, "MANUAL", manual = 820, durationMs = 800)
        assertEquals(820, c.applied)

        t += 260
        c.tick(t)
        // 820 -> 0 in a single loop; a slew-limited ramp would need ~46 loops.
        assertEquals(0, c.applied)
    }

    @Test fun neutral_arrives_within_250ms_of_comms_loss() {
        val c = ctrl()
        val lastCommandAt = drive(c, "MANUAL", manual = 700, durationMs = 800)
        assertTrue(c.applied > 0)

        var t = lastCommandAt
        var zeroAt: Long? = null
        while (t < lastCommandAt + 1000 && zeroAt == null) {
            t += LOOP_MS
            c.tick(t)
            if (c.applied == 0) zeroAt = t
        }
        assertNotNull("never reached neutral after comms loss", zeroAt)
        assertTrue("took ${zeroAt!! - lastCommandAt} ms to reach neutral",
            zeroAt - lastCommandAt <= 250 + LOOP_MS)
    }

    @Test fun estop_hard_zeroes_regardless_of_command() {
        val c = ctrl()
        val t = drive(c, "MANUAL", manual = 700, durationMs = 800)
        assertTrue(c.applied > 600)

        c.estop = true
        send(c, "MANUAL", manual = 700)                 // still demanding output
        c.tick(t + LOOP_MS)
        assertEquals(0, c.applied)
        assertEquals(SimulatedController.FAULT_ESTOP, c.fault)
        assertEquals("ESTOP", c.state)
    }

    @Test fun estop_keeps_output_at_zero_while_asserted() {
        val c = ctrl()
        val t = drive(c, "MANUAL", manual = 700, durationMs = 800)
        c.estop = true
        drive(c, "MANUAL", manual = 700, durationMs = 500, fromMs = t) {
            assertEquals(0, c.applied)
        }
    }

    // ---- structural safety ----

    @Test fun raise_and_lower_are_never_both_energised() {
        val c = ctrl()
        var t = drive(c, "MANUAL", manual = 600, durationMs = 600) {
            assertTrue(c.raiseDuty == 0 || c.lowerDuty == 0)
        }
        drive(c, "MANUAL", manual = -600, durationMs = 1200, fromMs = t) {
            assertTrue("raise=${c.raiseDuty} lower=${c.lowerDuty} both live",
                c.raiseDuty == 0 || c.lowerDuty == 0)
        }
    }

    @Test fun reversing_direction_pauses_at_zero_for_the_dead_time() {
        val c = ctrl()
        val t = drive(c, "MANUAL", manual = 300, durationMs = 500)
        assertEquals(300, c.applied)

        var zeroLoops = 0
        var sawOpposite = false
        drive(c, "MANUAL", manual = -300, durationMs = 1500, fromMs = t) {
            if (c.applied == 0) zeroLoops++
            if (c.applied < 0) sawOpposite = true
        }
        // 60 ms of dead time is 6 loops at 10 ms, on top of the slew down.
        assertTrue("expected a dead-time pause at zero, saw $zeroLoops loops", zeroLoops >= 6)
        assertTrue("never energised the opposite direction", sawOpposite)
        assertEquals(-300, c.applied)
    }

    // ---- status reporting ----

    @Test fun status_frames_are_readable_by_the_tablet_decoder() {
        val c = ctrl()
        drive(c, "MANUAL", manual = 200, durationMs = 300)
        val lines = String(c.read(), Charsets.UTF_8).trim().lines()
        val status = ControllerProtocol.decodeStatus(lines.last())
        assertNotNull("tablet could not decode the controller's own status frame", status)
        assertEquals(seq, status!!.seq_ack)
        assertEquals(13_800, status.supply_mv)
        assertEquals(0, status.fault)
        assertEquals(200, status.output)
    }

    @Test fun a_tampered_status_frame_is_rejected_by_the_decoder() {
        val c = ctrl()
        drive(c, "MANUAL", manual = 100, durationMs = 300)
        val line = String(c.read(), Charsets.UTF_8).trim().lines().last()
        val tampered = line.replace("\"fault\":0", "\"fault\":5")
        assertTrue("test did not actually change the frame", tampered != line)
        assertNull(ControllerProtocol.decodeStatus(tampered))
    }

    @Test fun a_fault_is_reported_and_never_suppressed() {
        val c = ctrl()
        val t = drive(c, "MANUAL", manual = 300, durationMs = 300)
        c.estop = true
        c.tick(t + LOOP_MS)
        val line = String(c.read(), Charsets.UTF_8).trim().lines().last()
        val status = ControllerProtocol.decodeStatus(line)!!
        assertEquals(SimulatedController.FAULT_ESTOP, status.fault)
        assertTrue(status.estop)
        assertEquals("ESTOP", status.state)
    }

    @Test fun a_closed_link_accepts_nothing() {
        val c = ctrl()
        c.close()
        send(c, "MANUAL", manual = 500)
        c.tick(10)
        assertEquals(0, c.acceptedFrames)
        assertEquals(0, c.applied)
    }

    private companion object {
        const val LOOP_MS = 10L
    }
}
