package com.fieldgrade.app.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [BladeModel] in isolation: duty and time in, blade offset out. */
class BladeModelTest {

    @Test fun starts_at_its_initial_offset_and_does_not_drift() {
        val b = BladeModel()
        assertEquals(0.0, b.offsetMm, 1e-9)
        repeat(100) { b.update(0, 10) }
        assertEquals(0.0, b.offsetMm, 1e-9)
    }

    @Test fun does_not_move_inside_the_valve_deadband() {
        // A proportional spool does nothing until it cracks open. A control
        // deadband narrower than this one buys nothing but wear.
        val b = BladeModel(valveDeadbandDuty = 45)
        repeat(100) { b.update(44, 10) }
        assertEquals(0.0, b.offsetMm, 1e-9)
        assertEquals(0.0, b.rateMmPerS, 1e-9)
    }

    @Test fun moves_once_the_valve_cracks_open() {
        val b = BladeModel(valveDeadbandDuty = 45)
        repeat(100) { b.update(46, 10) }
        assertTrue("blade should have moved", b.offsetMm > 0.0)
    }

    @Test fun full_duty_gives_the_rated_speed() {
        val b = BladeModel(fullDutyRateMmPerS = 90.0, maxDuty = 820, valveDeadbandDuty = 45)
        b.update(820, 1000)                      // one second at full duty
        assertEquals(90.0, b.offsetMm, 1e-6)
        assertEquals(90.0, b.rateMmPerS, 1e-6)
    }

    @Test fun rate_is_linear_between_the_crack_point_and_full_duty() {
        val b = BladeModel(fullDutyRateMmPerS = 90.0, maxDuty = 820, valveDeadbandDuty = 45)
        // Halfway through the usable range: 45 + (820-45)/2 = 432.5 -> 45 mm/s.
        b.update(433, 1000)
        assertEquals(45.06, b.offsetMm, 0.1)
    }

    @Test fun negative_duty_lowers_the_blade() {
        val b = BladeModel(fullDutyRateMmPerS = 90.0)
        b.update(-820, 1000)
        assertEquals(-90.0, b.offsetMm, 1e-6)
    }

    @Test fun travel_is_limited_by_the_cylinder_stroke() {
        val b = BladeModel(fullDutyRateMmPerS = 90.0, travelLimitMm = 200.0)
        repeat(100) { b.update(820, 100) }       // 10 s of raising = 900 mm demanded
        assertEquals(200.0, b.offsetMm, 1e-9)
        assertTrue("should report being against the stop", b.atTravelLimit)
    }

    @Test fun leaving_the_stop_clears_the_limit_flag() {
        val b = BladeModel(travelLimitMm = 200.0)
        repeat(100) { b.update(820, 100) }
        assertTrue(b.atTravelLimit)
        repeat(20) { b.update(-820, 100) }
        assertTrue(!b.atTravelLimit)
    }

    @Test fun zero_or_negative_time_steps_do_nothing() {
        val b = BladeModel()
        b.update(820, 0)
        b.update(820, -50)
        assertEquals(0.0, b.offsetMm, 1e-9)
    }

    @Test fun offset_in_metres_matches_millimetres() {
        val b = BladeModel(fullDutyRateMmPerS = 90.0)
        b.update(820, 1000)
        assertEquals(0.090, b.offsetM, 1e-9)
    }
}
