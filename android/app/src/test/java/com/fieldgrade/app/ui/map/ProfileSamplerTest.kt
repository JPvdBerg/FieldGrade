package com.fieldgrade.app.ui.map

import com.fieldgrade.app.surface.DesignSurfaceModel
import com.fieldgrade.app.surface.PlaneDesignSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ProfileSampler] in isolation: a pose and a course in, elevations along it out. */
class ProfileSamplerTest {

    /** A plane rising 1 cm per metre east. */
    private val eastward: DesignSurfaceModel = PlaneDesignSurface(100.0, 0.01, 0.0)

    /** A plane rising 1 cm per metre north. */
    private val northward: DesignSurfaceModel = PlaneDesignSurface(100.0, 0.0, 0.01)

    private fun sample(
        design: DesignSurfaceModel? = northward,
        existing: DesignSurfaceModel? = null,
        headingDeg: Double = 0.0,
        aheadM: Double = 10.0,
        behindM: Double = 0.0,
        stepM: Double = 1.0
    ) = ProfileSampler.alongHeading(
        design, existing, eastM = 0.0, northM = 0.0,
        headingDeg = headingDeg, aheadM = aheadM, behindM = behindM, stepM = stepM
    )

    @Test fun samples_run_from_behind_to_ahead() {
        val p = sample(aheadM = 10.0, behindM = 5.0, stepM = 1.0)
        assertEquals(16, p.samples.size)
        assertEquals(-5.0, p.samples.first().distanceM, 1e-9)
        assertEquals(10.0, p.samples.last().distanceM, 1e-9)
    }

    @Test fun a_tail_behind_the_machine_is_included() {
        // The last few metres worked are how an operator confirms the pass came out.
        val p = sample(behindM = 4.0)
        assertTrue(p.samples.any { it.distanceM < 0 })
    }

    @Test fun heading_north_walks_north() {
        val p = sample(design = northward, headingDeg = 0.0, aheadM = 10.0)
        // 1 cm per metre north, so 10 m ahead is 10 cm higher.
        assertEquals(100.0, p.samples.first { it.distanceM == 0.0 }.designM!!, 1e-9)
        assertEquals(100.10, p.samples.last().designM!!, 1e-9)
    }

    @Test fun heading_east_walks_east() {
        val p = sample(design = eastward, headingDeg = 90.0, aheadM = 10.0)
        assertEquals(100.10, p.samples.last().designM!!, 1e-9)
    }

    @Test fun heading_east_along_a_north_slope_stays_level() {
        // Getting the sin/cos the wrong way round would show a slope that is
        // ninety degrees from the one the machine is actually driving into.
        val p = sample(design = northward, headingDeg = 90.0, aheadM = 10.0)
        for (s in p.samples) assertEquals(100.0, s.designM!!, 1e-9)
    }

    @Test fun heading_south_descends_a_north_slope() {
        val p = sample(design = northward, headingDeg = 180.0, aheadM = 10.0)
        assertEquals(99.90, p.samples.last().designM!!, 1e-9)
    }

    @Test fun every_bearing_walks_the_right_way() {
        for (h in 0 until 360 step 15) {
            val rad = Math.toRadians(h.toDouble())
            val p = sample(design = eastward, headingDeg = h.toDouble(), aheadM = 10.0)
            // east-rising plane: 10 m along heading h moves 10*sin(h) east.
            assertEquals(
                "heading $h", 100.0 + 0.01 * 10.0 * kotlin.math.sin(rad),
                p.samples.last().designM!!, 1e-9
            )
        }
    }

    // ---- both surfaces ----

    @Test fun cut_fill_is_design_minus_existing() {
        val design = PlaneDesignSurface(100.5, 0.0, 0.0)
        val existing = PlaneDesignSurface(100.0, 0.0, 0.0)
        val p = ProfileSampler.alongHeading(design, existing, 0.0, 0.0, 0.0, 5.0, 0.0, 1.0)
        for (s in p.samples) assertEquals(500, s.cutFillMm)
    }

    @Test fun cut_fill_is_null_when_either_surface_is_missing() {
        val p = sample(design = northward, existing = null)
        assertNull(p.samples.first().cutFillMm)
        assertNull(p.samples.first().existingM)
    }

    @Test fun a_gap_in_a_surface_is_a_null_not_a_bridged_value() {
        // Beyond the design boundary there is no design; the renderer must be able
        // to break the line rather than draw across ground nobody surveyed.
        val bounded = PlaneDesignSurface(
            100.0, 0.0, 0.0,
            bounds = PlaneDesignSurface.Bounds(-100.0, -100.0, 100.0, 5.0)
        )
        val p = ProfileSampler.alongHeading(bounded, null, 0.0, 0.0, 0.0, 10.0, 0.0, 1.0)
        assertEquals(100.0, p.samples.first { it.distanceM == 5.0 }.designM!!, 1e-9)
        assertNull(p.samples.first { it.distanceM == 6.0 }.designM)
    }

    // ---- refusal ----

    @Test fun a_non_finite_heading_yields_no_profile() {
        // A profile down an arbitrary bearing is worse than no profile at all.
        val p = sample(headingDeg = Double.NaN)
        assertTrue(p.samples.isEmpty())
        assertTrue(p.isEmpty)
    }

    @Test fun a_non_positive_step_yields_no_profile() {
        assertTrue(sample(stepM = 0.0).samples.isEmpty())
        assertTrue(sample(stepM = -1.0).samples.isEmpty())
    }

    @Test fun no_surfaces_at_all_is_empty_not_a_crash() {
        val p = ProfileSampler.alongHeading(null, null, 0.0, 0.0, 0.0, 10.0, 0.0, 1.0)
        assertTrue(p.isEmpty)
        assertNull(p.elevationRange())
    }

    // ---- range ----

    @Test fun elevation_range_spans_both_surfaces() {
        val design = PlaneDesignSurface(101.0, 0.0, 0.0)
        val existing = PlaneDesignSurface(100.0, 0.0, 0.0)
        val r = ProfileSampler.alongHeading(design, existing, 0.0, 0.0, 0.0, 5.0, 0.0, 1.0)
            .elevationRange()!!
        assertEquals(100.0, r.start, 1e-9)
        assertEquals(101.0, r.endInclusive, 1e-9)
    }

    @Test fun the_axis_step_helper_uses_round_numbers() {
        assertEquals(0.2, niceStep(1.0), 1e-9)
        assertEquals(2.0, niceStep(10.0), 1e-9)
        assertEquals(20.0, niceStep(100.0), 1e-9)
        assertEquals(1.0, niceStep(0.0), 1e-9)          // degenerate span
        assertEquals(1.0, niceStep(Double.NaN), 1e-9)
    }
}
