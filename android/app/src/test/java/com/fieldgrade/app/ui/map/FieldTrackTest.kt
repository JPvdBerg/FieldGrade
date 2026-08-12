package com.fieldgrade.app.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [FieldTrack] in isolation: positions in, decimated history out. */
class FieldTrackTest {

    @Test fun records_the_first_position() {
        val t = FieldTrack()
        assertTrue(t.offer(10.0, 20.0, 5))
        assertEquals(1, t.size)
        assertEquals(10.0, t.latest()!!.eastM, 1e-9)
    }

    @Test fun drops_marks_closer_than_the_spacing() {
        // A machine parked with the engine running must not bury the worked
        // history under thousands of marks in one spot.
        val t = FieldTrack(minSpacingM = 2.0)
        assertTrue(t.offer(0.0, 0.0, 0))
        assertFalse(t.offer(1.0, 0.0, 0))
        assertFalse(t.offer(0.0, 1.9, 0))
        assertTrue(t.offer(0.0, 2.5, 0))
        assertEquals(2, t.size)
    }

    @Test fun spacing_is_measured_from_the_last_kept_mark() {
        val t = FieldTrack(minSpacingM = 2.0)
        t.offer(0.0, 0.0, 0)
        t.offer(1.5, 0.0, 0)      // dropped
        assertTrue(t.offer(2.5, 0.0, 0))
        assertEquals(2, t.size)
        assertEquals(2.5, t.latest()!!.eastM, 1e-9)
    }

    @Test fun classifies_marks_against_the_tolerance() {
        val t = FieldTrack(minSpacingM = 0.0, onGradeToleranceMm = 25)
        t.offer(0.0, 0.0, 10)
        t.offer(10.0, 0.0, 25)
        t.offer(20.0, 0.0, 26)
        t.offer(30.0, 0.0, -80)
        val marks = t.snapshot()
        assertTrue(marks[0].onGrade)
        assertTrue("boundary value must count as on grade", marks[1].onGrade)
        assertFalse(marks[2].onGrade)
        assertFalse("tolerance must be symmetric about zero", marks[3].onGrade)
    }

    @Test fun keeps_the_cut_fill_from_the_moment_of_passing() {
        // The worked track is a record of work done, not a live readout — the
        // value must not change when the machine later moves elsewhere.
        val t = FieldTrack(minSpacingM = 1.0)
        t.offer(0.0, 0.0, 120)
        t.offer(5.0, 0.0, -30)
        assertEquals(120, t.snapshot()[0].cutFillMm)
    }

    @Test fun caps_the_buffer_dropping_oldest_first() {
        val t = FieldTrack(minSpacingM = 0.0, maxMarks = 50)
        for (i in 0 until 200) t.offer(i * 1.0, 0.0, i)
        assertEquals(50, t.size)
        // The oldest survivor is mark 150.
        assertEquals(150.0, t.snapshot().first().eastM, 1e-9)
        assertEquals(199.0, t.latest()!!.eastM, 1e-9)
    }

    @Test fun ignores_non_finite_positions() {
        val t = FieldTrack()
        assertFalse(t.offer(Double.NaN, 0.0, 0))
        assertFalse(t.offer(0.0, Double.POSITIVE_INFINITY, 0))
        assertEquals(0, t.size)
        assertNull(t.latest())
    }

    @Test fun clearing_resets_the_spacing_gate_too() {
        val t = FieldTrack(minSpacingM = 5.0)
        t.offer(0.0, 0.0, 0)
        t.clear()
        assertEquals(0, t.size)
        // Without resetting lastE/lastN this second offer would be swallowed.
        assertTrue(t.offer(0.0, 0.0, 0))
    }

    @Test fun reports_the_on_grade_fraction() {
        val t = FieldTrack(minSpacingM = 0.0, onGradeToleranceMm = 25)
        assertEquals(0.0, t.onGradeFraction(), 1e-9)
        repeat(3) { t.offer(it * 1.0, 0.0, 5) }
        t.offer(99.0, 0.0, 500)
        assertEquals(0.75, t.onGradeFraction(), 1e-9)
    }
}
