package com.fieldgrade.app.survey

import com.fieldgrade.app.design.XyzPointReader
import com.fieldgrade.app.geom.ToolPoint
import com.fieldgrade.app.gnss.FixQuality
import com.fieldgrade.app.gnss.GnssSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [SurveyRecorder] in isolation: fixes in, XYZ rows out. */
class SurveyRecorderTest {

    private fun fix(
        quality: FixQuality = FixQuality.FIXED,
        accuracy: Double = 0.015
    ) = GnssSample(
        latitudeDeg = -27.95, longitudeDeg = 24.83, ellipsoidHeightM = 120.0,
        quality = quality, horizontalAccuracyM = accuracy, ageMs = 0
    )

    private fun tool(e: Double, n: Double, z: Double = 119.0) = ToolPoint(e, n, z)

    @Test fun records_a_good_fix() {
        val r = SurveyRecorder()
        assertNull(r.offer(fix(), tool(10.0, 20.0, 118.5)))
        assertEquals(1, r.count)
        assertEquals(10.0, r.points[0].eastM, 1e-9)
        assertEquals(118.5, r.points[0].elevationM, 1e-9)
    }

    @Test fun records_the_tool_point_not_the_antenna() {
        // The blade riding on the ground IS the ground elevation. Recording the
        // antenna instead hands the designer a surface one mast-height too high.
        val r = SurveyRecorder()
        r.offer(fix(), tool(0.0, 0.0, z = 118.9))   // sample height is 120.0
        assertEquals(118.9, r.points[0].elevationM, 1e-9)
    }

    @Test fun rejects_anything_below_rtk_fixed() {
        val r = SurveyRecorder()
        for (q in listOf(FixQuality.NONE, FixQuality.AUTONOMOUS, FixQuality.DGPS, FixQuality.FLOAT)) {
            assertEquals(SurveyRecorder.Rejection.QUALITY, r.offer(fix(quality = q), tool(0.0, 0.0)))
        }
        assertEquals(0, r.count)
        assertEquals(4, r.rejectedQuality)
    }

    @Test fun rejects_fixes_that_are_not_accurate_enough() {
        val r = SurveyRecorder(maxHorizontalAccuracyM = 0.05)
        assertEquals(SurveyRecorder.Rejection.ACCURACY, r.offer(fix(accuracy = 0.20), tool(0.0, 0.0)))
        assertEquals(SurveyRecorder.Rejection.ACCURACY,
            r.offer(fix(accuracy = Double.NaN), tool(0.0, 0.0)))
        assertEquals(0, r.count)
    }

    @Test fun rejects_points_closer_together_than_the_spacing() {
        // A machine parked with the engine running must not bury the real
        // surface under thousands of duplicate points at one spot.
        val r = SurveyRecorder(minSpacingM = 1.0)
        assertNull(r.offer(fix(), tool(0.0, 0.0)))
        assertEquals(SurveyRecorder.Rejection.SPACING, r.offer(fix(), tool(0.5, 0.0)))
        assertEquals(SurveyRecorder.Rejection.SPACING, r.offer(fix(), tool(0.0, 0.9)))
        assertNull(r.offer(fix(), tool(1.5, 0.0)))
        assertEquals(2, r.count)
        assertEquals(2, r.rejectedSpacing)
    }

    @Test fun spacing_is_measured_from_the_last_kept_point() {
        val r = SurveyRecorder(minSpacingM = 1.0)
        r.offer(fix(), tool(0.0, 0.0))
        r.offer(fix(), tool(0.6, 0.0))      // rejected
        r.offer(fix(), tool(1.2, 0.0))      // 1.2 from the kept point: accepted
        assertEquals(2, r.count)
        assertEquals(1.2, r.points[1].eastM, 1e-9)
    }

    // ---- the round trip that makes this useful ----

    @Test fun output_reads_back_through_the_xyz_reader() {
        // The whole point of step 1: what the machine records must be what a
        // design house can import, and what this app can read back.
        val r = SurveyRecorder(minSpacingM = 0.5)
        val expected = ArrayList<Triple<Double, Double, Double>>()
        for (i in 0 until 25) {
            val e = i * 2.0
            val n = i * 1.5
            val z = 118.0 + i * 0.01
            r.offer(fix(), tool(e, n, z))
            expected.add(Triple(e, n, z))
        }
        assertEquals(25, r.count)

        val parsed = XyzPointReader.read(r.toXyzText())
        assertEquals(25, parsed.points.size)
        assertTrue(parsed.headerUsed)
        parsed.points.forEachIndexed { i, p ->
            assertEquals(expected[i].first, p.eastM, 1e-4)
            assertEquals(expected[i].second, p.northM, 1e-4)
            assertEquals(expected[i].third, p.elevationM, 1e-4)
        }
    }

    @Test fun output_uses_a_dot_decimal_separator_regardless_of_locale() {
        // A comma decimal separator would silently corrupt every row of the CSV.
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val r = SurveyRecorder()
            r.offer(fix(), tool(1.5, 2.25, 118.125))
            val row = r.toXyzText().lines()[1]
            assertEquals("1,1.5000,2.2500,118.1250,SURVEY", row)
        } finally {
            java.util.Locale.setDefault(original)
        }
    }

    @Test fun an_empty_recorder_still_emits_a_valid_header() {
        val text = SurveyRecorder().toXyzText()
        assertEquals("Point,Easting,Northing,Elevation,Code", text.trim())
    }
}
