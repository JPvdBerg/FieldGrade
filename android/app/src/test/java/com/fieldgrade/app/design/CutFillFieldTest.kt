package com.fieldgrade.app.design

import com.fieldgrade.app.SampleData
import com.fieldgrade.app.surface.DesignSurfaceModel
import com.fieldgrade.app.surface.PlaneDesignSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [CutFillField] in isolation: two surfaces in, a raster of remaining work out. */
class CutFillFieldTest {

    private val bounds = DesignSurface.Bounds(0.0, 0.0, 100.0, 100.0, 0.0, 0.0)

    /** Two flat planes a fixed distance apart. */
    private fun offsetPair(designZ: Double, existingZ: Double): CutFillField =
        CutFillField.sample(
            design = PlaneDesignSurface(designZ, 0.0, 0.0),
            existing = PlaneDesignSurface(existingZ, 0.0, 0.0),
            bounds = bounds,
            cellSizeM = 10.0
        )

    @Test fun design_above_ground_is_positive_meaning_fill() {
        // Positive must mean "add material", matching GuidanceEngine's sign
        // convention. Getting this backwards inverts the whole map.
        val f = offsetPair(designZ = 100.5, existingZ = 100.0)
        assertEquals(500, f.valueAt(5, 5))
    }

    @Test fun design_below_ground_is_negative_meaning_cut() {
        val f = offsetPair(designZ = 100.0, existingZ = 100.3)
        assertEquals(-300, f.valueAt(5, 5))
    }

    @Test fun grid_dimensions_cover_the_bounds() {
        val f = offsetPair(1.0, 1.0)
        assertEquals(10, f.cols)
        assertEquals(10, f.rows)
        assertEquals(100.0, f.widthM, 1e-9)
        assertEquals(100.0, f.heightM, 1e-9)
    }

    @Test fun cells_map_back_to_world_positions() {
        val f = offsetPair(1.0, 1.0)
        assertEquals(0, f.colOf(4.0))
        assertEquals(3, f.colOf(35.0))
        assertEquals(5.0, f.eastOf(0), 1e-9)
        assertEquals(35.0, f.northOf(3), 1e-9)
    }

    @Test fun a_cell_needs_both_surfaces_to_have_a_value() {
        // A value derived from only one surface would be a guess presented as a
        // measurement, so it must come back as NO_DATA.
        val design = PlaneDesignSurface(100.0, 0.0, 0.0)
        val existing = PlaneDesignSurface(
            99.0, 0.0, 0.0,
            bounds = PlaneDesignSurface.Bounds(0.0, 0.0, 50.0, 100.0)   // left half only
        )
        val f = CutFillField.sample(design, existing, bounds, cellSizeM = 10.0)
        assertEquals(1000, f.valueAt(2, 5))                       // both cover
        assertEquals(CutFillField.NO_DATA, f.valueAt(8, 5))       // design only
    }

    @Test fun out_of_range_lookups_are_no_data_not_a_crash() {
        val f = offsetPair(1.0, 1.0)
        assertEquals(CutFillField.NO_DATA, f.valueAt(-1, 0))
        assertEquals(CutFillField.NO_DATA, f.valueAt(0, 99))
        assertEquals(CutFillField.NO_DATA, f.valueAtWorld(-500.0, 0.0))
    }

    // ---- working the field ----

    @Test fun marking_worked_updates_a_swath_and_bumps_the_version() {
        val f = offsetPair(designZ = 100.5, existingZ = 100.0)   // 500 mm of fill
        val before = f.version
        assertTrue(f.markWorked(50.0, 50.0, radiusM = 10.0, cutFillMm = 5))
        assertNotEquals(before, f.version)

        assertEquals(5, f.valueAtWorld(50.0, 50.0))
        // Outside the swath is untouched.
        assertEquals(500, f.valueAtWorld(95.0, 95.0))
    }

    @Test fun marking_the_same_value_twice_does_not_bump_the_version() {
        // The renderer caches on version; a no-op write must not force a redraw.
        val f = offsetPair(100.5, 100.0)
        f.markWorked(50.0, 50.0, 10.0, 5)
        val after = f.version
        assertTrue(!f.markWorked(50.0, 50.0, 10.0, 5))
        assertEquals(after, f.version)
    }

    @Test fun working_outside_the_design_never_invents_surface() {
        val design = PlaneDesignSurface(100.0, 0.0, 0.0)
        val existing = PlaneDesignSurface(
            99.0, 0.0, 0.0,
            bounds = PlaneDesignSurface.Bounds(0.0, 0.0, 50.0, 100.0)
        )
        val f = CutFillField.sample(design, existing, bounds, cellSizeM = 10.0)
        assertEquals(CutFillField.NO_DATA, f.valueAt(8, 5))
        f.markWorked(85.0, 55.0, radiusM = 10.0, cutFillMm = 0)
        assertEquals(
            "a pass outside the design must not create surface",
            CutFillField.NO_DATA, f.valueAt(8, 5)
        )
    }

    @Test fun summary_counts_cut_fill_and_on_grade() {
        val f = offsetPair(designZ = 100.5, existingZ = 100.0)
        val all = f.summary()
        assertEquals(100, all.inField)
        assertEquals(100, all.fill)
        assertEquals(0, all.cut)
        assertEquals(0.0, all.onGradeFraction, 1e-9)

        // Work the whole field to grade and the summary must follow.
        for (r in 0 until f.rows) for (c in 0 until f.cols) {
            f.markWorked(f.eastOf(c), f.northOf(r), 1.0, 0)
        }
        assertEquals(1.0, f.summary().onGradeFraction, 1e-9)
    }

    // ---- against the real surfaces ----

    @Test fun samples_the_real_design_against_the_real_existing_ground() {
        val design = TinDesignSurface.from(
            DesignSurface("design", XyzPointReader.read(SampleData.nunosurfXyz().readText()).points)
        )
        val existingPoints = XyzPointReader.read(
            SampleData.design("nunosurf_existing.xyz").readText()
        ).points
        val existing: DesignSurfaceModel =
            TinDesignSurface.from(DesignSurface("existing", existingPoints))

        val b = DesignSurface("d", XyzPointReader.read(SampleData.nunosurfXyz().readText()).points)
            .bounds()!!
        val field = CutFillField.sample(design, existing, b)

        assertEquals(114, field.cols)
        assertEquals(99, field.rows)

        val s = field.summary()
        // The field is the surveyed hull, which covers about 63% of its bounding
        // box. Cells outside it must stay NO_DATA rather than being filled in —
        // that gap between the two numbers is the map telling the truth about
        // where the design actually exists.
        val total = field.cols * field.rows
        assertTrue("expected a substantial field, got ${s.inField}/$total", s.inField > 6500)
        assertTrue("the hull must not fill its bounding box", s.outside > total / 10)
        assertTrue("expected real cut work", s.cut > 500)
        assertTrue("expected real fill work", s.fill > 500)
        // A field that needed no work would make the whole exercise pointless.
        assertTrue("expected the field to be mostly unfinished", s.onGradeFraction < 0.5)
    }
}
