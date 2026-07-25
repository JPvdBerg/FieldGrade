package com.fieldgrade.app.control

import com.fieldgrade.app.geom.Attitude
import com.fieldgrade.app.geom.CoordinateTransform
import com.fieldgrade.app.geom.LeverArm
import com.fieldgrade.app.gnss.FixQuality
import com.fieldgrade.app.gnss.GnssSample
import com.fieldgrade.app.surface.PlaneDesignSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidanceEngineTest {

    private val originLat = -26.20000
    private val originLon = 28.04000
    private val tf = CoordinateTransform(originLat, originLon)
    private val arm = LeverArm(forwardM = 0.0, rightM = 0.0, downM = 3.0)  // tool 3 m below antenna
    private val flatDesign = PlaneDesignSurface(a = 1542.420, b = 0.0, c = 0.0)
    private val engine = GuidanceEngine()

    /** Sample at the origin with a chosen antenna height (tool = antenna - 3.0). */
    private fun sampleAtAntenna(
        antennaHeightM: Double,
        quality: FixQuality = FixQuality.FIXED,
        accuracyM: Double = 0.015,
        ageMs: Long = 0
    ) = GnssSample(originLat, originLon, antennaHeightM, quality, accuracyM, ageMs)

    // ---- sign convention (Gate: cut/fill sign confirmed) ----
    @Test fun tool_below_design_reads_positive_and_says_raise() {
        val s = engine.compute(sampleAtAntenna(1545.386), tf, arm, Attitude(), flatDesign) // tool 1542.386
        assertEquals(1542.386, s.toolElevationM, 1e-6)
        assertEquals(1542.420, s.designElevationM, 1e-6)
        assertEquals(34, s.cutFillMm)                       // +34 mm
        assertEquals(GuidanceDirection.RAISE, s.direction)  // below target -> raise (fill)
    }

    @Test fun tool_above_design_reads_negative_and_says_lower() {
        val s = engine.compute(sampleAtAntenna(1545.500), tf, arm, Attitude(), flatDesign) // tool 1542.500
        assertEquals(-80, s.cutFillMm)
        assertEquals(GuidanceDirection.LOWER, s.direction)
    }

    @Test fun within_deadband_is_on_grade() {
        val s = engine.compute(sampleAtAntenna(1545.425), tf, arm, Attitude(), flatDesign) // -5 mm
        assertEquals(-5, s.cutFillMm)
        assertEquals(GuidanceDirection.ON_GRADE, s.direction)
    }

    // ---- nudge + rebench ----
    @Test fun nudge_shifts_target() {
        val s = engine.compute(sampleAtAntenna(1545.386), tf, arm, Attitude(), flatDesign, nudgeMm = 20)
        assertEquals(54, s.cutFillMm)                       // 34 + 20
    }

    @Test fun rebench_offset_zeroes_a_known_reference() {
        // tool measures 1542.386 but the benchmark says it is on grade: offset +0.034 -> cut/fill 0
        val s = engine.compute(sampleAtAntenna(1545.386), tf, arm, Attitude(), flatDesign, benchmarkOffsetM = 0.034)
        assertEquals(0, s.cutFillMm)
        assertEquals(GuidanceDirection.ON_GRADE, s.direction)
    }

    // ---- AUTO interlocks (Gate: AUTO refused on invalid/stale GNSS) ----
    @Test fun auto_allowed_with_good_fixed_solution() {
        val s = engine.compute(sampleAtAntenna(1545.386), tf, arm, Attitude(), flatDesign)
        assertTrue(s.canAuto)
        assertEquals(null, s.autoInhibitReason)
    }

    @Test fun auto_refused_without_rtk_fixed() {
        val s = engine.compute(sampleAtAntenna(1545.386, quality = FixQuality.FLOAT), tf, arm, Attitude(), flatDesign)
        assertFalse(s.canAuto)
        assertTrue(s.autoInhibitReason!!.contains("FLOAT"))
    }

    @Test fun auto_refused_when_stale() {
        val s = engine.compute(sampleAtAntenna(1545.386, ageMs = 2000), tf, arm, Attitude(), flatDesign)
        assertFalse(s.canAuto)
        assertTrue(s.autoInhibitReason!!.contains("stale"))
    }

    @Test fun auto_refused_when_inaccurate() {
        val s = engine.compute(sampleAtAntenna(1545.386, accuracyM = 0.10), tf, arm, Attitude(), flatDesign)
        assertFalse(s.canAuto)
        assertTrue(s.autoInhibitReason!!.contains("accuracy"))
    }

    @Test fun outside_boundary_has_no_solution_and_no_auto() {
        val bounded = PlaneDesignSurface(
            a = 1542.420, b = 0.0, c = 0.0,
            bounds = PlaneDesignSurface.Bounds(100.0, 100.0, 200.0, 200.0)  // origin excluded
        )
        val s = engine.compute(sampleAtAntenna(1545.386), tf, arm, Attitude(), bounded)
        assertFalse(s.hasSolution)
        assertFalse(s.canAuto)
        assertTrue(s.autoInhibitReason!!.contains("boundary"))
    }
}
