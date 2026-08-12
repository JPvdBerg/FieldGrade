package com.fieldgrade.app.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [HeadingFilter] in isolation: noisy course over ground in, stable heading out. */
class HeadingFilterTest {

    private fun filter() = HeadingFilter(minSpeedMps = 0.4, smoothing = 0.18)

    /** Feed the same value until the EMA has settled. */
    private fun settle(f: HeadingFilter, headingDeg: Double, speedMps: Double = 2.0, n: Int = 120) {
        repeat(n) { f.update(headingDeg, speedMps) }
    }

    @Test fun starts_with_no_heading_at_all() {
        val f = filter()
        assertFalse(f.hasHeading)
        assertTrue(f.isHolding)
        assertTrue(f.headingDeg.isNaN())
    }

    @Test fun the_first_good_fix_is_taken_as_is() {
        // No lag on the very first sample: there is nothing to smooth against.
        val f = filter()
        f.update(137.0, 2.0)
        assertEquals(137.0, f.headingDeg, 1e-6)
        assertFalse(f.isHolding)
    }

    @Test fun converges_on_a_steady_course() {
        val f = filter()
        f.update(10.0, 2.0)
        settle(f, 80.0)
        assertEquals(80.0, f.headingDeg, 0.5)
    }

    // ---- the wraparound trap ----

    @Test fun smoothing_across_north_never_points_backwards() {
        // Averaging degrees takes 350 and 10 to 180 — the map would face exactly
        // the wrong way as the machine crosses north. Vector smoothing must not.
        val f = filter()
        f.update(350.0, 2.0)
        repeat(30) { f.update(10.0, 2.0) }
        val d = HeadingFilter.angularDistance(f.headingDeg, 0.0)
        assertTrue("heading swung to ${f.headingDeg} crossing north", d < 25.0)
    }

    @Test fun a_course_hovering_around_north_stays_around_north() {
        val f = filter()
        f.update(0.0, 2.0)
        repeat(60) { i -> f.update(if (i % 2 == 0) 358.0 else 2.0, 2.0) }
        assertTrue(
            "expected ~0 deg, got ${f.headingDeg}",
            HeadingFilter.angularDistance(f.headingDeg, 0.0) < 5.0
        )
    }

    @Test fun the_result_is_always_a_normalised_bearing() {
        val f = filter()
        for (h in listOf(0.0, 90.0, 179.9, 270.0, 359.9)) {
            settle(f, h, n = 60)
            assertTrue("got ${f.headingDeg}", f.headingDeg >= 0.0 && f.headingDeg < 360.0)
        }
    }

    // ---- the low-speed trap ----

    @Test fun below_the_speed_threshold_the_heading_freezes() {
        // Course over ground is position difference over time; near zero speed it
        // is pure noise, and a heading-up map would spin the whole field.
        val f = filter()
        settle(f, 90.0)
        val held = f.headingDeg

        for (noise in listOf(12.0, 300.0, 178.0, 45.0)) {
            f.update(noise, 0.05)
            assertEquals("heading moved while stationary", held, f.headingDeg, 1e-9)
            assertTrue(f.isHolding)
        }
    }

    @Test fun a_missing_course_holds_rather_than_resetting() {
        val f = filter()
        settle(f, 210.0)
        f.update(Double.NaN, 3.0)
        assertEquals(210.0, f.headingDeg, 0.5)
        assertTrue(f.isHolding)
    }

    @Test fun a_non_finite_speed_holds_too() {
        val f = filter()
        settle(f, 45.0)
        f.update(120.0, Double.NaN)
        assertEquals(45.0, f.headingDeg, 0.5)
        assertTrue(f.isHolding)
    }

    @Test fun moving_again_resumes_tracking() {
        val f = filter()
        settle(f, 90.0)
        f.update(90.0, 0.0)
        assertTrue(f.isHolding)
        f.update(95.0, 2.0)
        assertFalse(f.isHolding)
    }

    @Test fun holding_is_reported_so_the_ui_can_say_so() {
        // A map that has silently stopped tracking is worse than one that admits it.
        val f = filter()
        assertTrue(f.isHolding)
        f.update(45.0, 2.0)
        assertFalse(f.isHolding)
        f.update(45.0, 0.1)
        assertTrue(f.isHolding)
    }

    // ---- responsiveness ----

    @Test fun a_headland_turn_snaps_instead_of_crawling() {
        // A 180 turn is real information the operator is already acting on; making
        // the map ease into it over several seconds would be actively misleading.
        val f = filter()
        settle(f, 0.0)
        f.update(180.0, 2.0)
        assertEquals(180.0, f.headingDeg, 1.0)
    }

    @Test fun small_jitter_is_smoothed_not_followed() {
        val f = filter()
        settle(f, 90.0)
        // +/- 8 degrees of noise about 90 must not shake the map about.
        repeat(40) { i -> f.update(if (i % 2 == 0) 82.0 else 98.0, 2.0) }
        assertEquals(90.0, f.headingDeg, 3.0)
    }

    @Test fun reset_clears_everything() {
        val f = filter()
        settle(f, 123.0)
        f.reset()
        assertFalse(f.hasHeading)
        assertTrue(f.isHolding)
    }

    // ---- the helper ----

    @Test fun angular_distance_takes_the_short_way_round() {
        assertEquals(20.0, HeadingFilter.angularDistance(350.0, 10.0), 1e-9)
        assertEquals(20.0, HeadingFilter.angularDistance(10.0, 350.0), 1e-9)
        assertEquals(180.0, HeadingFilter.angularDistance(0.0, 180.0), 1e-9)
        assertEquals(0.0, HeadingFilter.angularDistance(45.0, 45.0), 1e-9)
        assertTrue(HeadingFilter.angularDistance(Double.NaN, 10.0).isNaN())
    }
}
