package com.fieldgrade.app.ui.map

import com.fieldgrade.app.design.CutFillField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cbrt

/**
 * [CutFillPalette] in isolation.
 *
 * These are the checks a **diverging** scale owes, and they are computed here
 * rather than eyeballed: lightness monotonic outward from the midpoint, chroma
 * at the poles, and separation between the two arms. If someone later "improves"
 * a colour by hand, this fails.
 */
class CutFillPaletteTest {

    // ---- OKLab, so the perceptual claims are measured not asserted ----

    private fun srgbToLinear(c: Double) =
        if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)

    private fun oklab(argb: Long): Triple<Double, Double, Double> {
        val r = srgbToLinear(((argb shr 16) and 0xFF) / 255.0)
        val g = srgbToLinear(((argb shr 8) and 0xFF) / 255.0)
        val b = srgbToLinear((argb and 0xFF) / 255.0)
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return Triple(
            0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
        )
    }

    private fun argbOf(cutFillMm: Int): Long {
        val c = CutFillPalette.colourFor(cutFillMm)
        // Compose Color -> packed ARGB via its 0..1 components.
        val r = (c.red * 255f + 0.5f).toInt().toLong()
        val g = (c.green * 255f + 0.5f).toInt().toLong()
        val b = (c.blue * 255f + 0.5f).toInt().toLong()
        return (r shl 16) or (g shl 8) or b
    }

    private fun lightness(cutFillMm: Int) = oklab(argbOf(cutFillMm)).first
    private fun chroma(cutFillMm: Int): Double {
        val (_, a, b) = oklab(argbOf(cutFillMm))
        return Math.hypot(a, b)
    }

    private fun deltaE(m1: Int, m2: Int): Double {
        val (l1, a1, b1) = oklab(argbOf(m1))
        val (l2, a2, b2) = oklab(argbOf(m2))
        return Math.sqrt((l1 - l2) * (l1 - l2) + (a1 - a2) * (a1 - a2) + (b1 - b2) * (b1 - b2)) * 100
    }

    /** One representative magnitude per band, off grade, increasing. */
    private val bandMagnitudes = intArrayOf(40, 75, 150, 400)

    // ---- the diverging construction ----

    @Test fun on_grade_is_the_neutral_midpoint_on_both_sides_of_zero() {
        val mid = CutFillPalette.colourFor(0)
        assertEquals(mid, CutFillPalette.colourFor(25))
        assertEquals(mid, CutFillPalette.colourFor(-25))
        assertEquals(mid, CutFillPalette.colourFor(10))
    }

    @Test fun the_midpoint_is_not_a_hue() {
        // Finished ground is the absence of work and must recede. A saturated
        // midpoint makes completed field the loudest thing on the map.
        assertTrue(
            "midpoint chroma ${chroma(0)} — the midpoint must read as neutral",
            chroma(0) < 0.03
        )
    }

    @Test fun lightness_increases_monotonically_outward_on_both_arms() {
        for (sign in intArrayOf(1, -1)) {
            var previous = lightness(0)
            for (m in bandMagnitudes) {
                val current = lightness(sign * m)
                assertTrue(
                    "lightness must increase outward (sign=$sign at ${m}mm: $previous -> $current)",
                    current > previous
                )
                previous = current
            }
        }
    }

    @Test fun both_poles_clear_the_chroma_floor() {
        // Below ~0.10 a hue reads as grey and stops doing any identity work.
        assertTrue("fill pole chroma ${chroma(400)}", chroma(400) >= 0.10)
        assertTrue("cut pole chroma ${chroma(-400)}", chroma(-400) >= 0.10)
    }

    @Test fun cut_and_fill_are_never_confusable_at_equal_magnitude() {
        for (m in bandMagnitudes) {
            val d = deltaE(m, -m)
            assertTrue("cut vs fill at ${m}mm: dE $d (need >= 15)", d >= 15.0)
        }
    }

    @Test fun the_arms_have_equal_step_counts() {
        // Unequal arms would make one direction look more urgent than it is.
        val fill = bandMagnitudes.map { CutFillPalette.colourFor(it) }.distinct()
        val cut = bandMagnitudes.map { CutFillPalette.colourFor(-it) }.distinct()
        assertEquals(fill.size, cut.size)
        assertEquals(4, fill.size)
    }

    @Test fun bands_are_discrete_not_a_continuous_ramp() {
        // Within a band the colour must not drift: hard edges survive sunlight.
        assertEquals(CutFillPalette.colourFor(30), CutFillPalette.colourFor(49))
        assertNotEquals(CutFillPalette.colourFor(49), CutFillPalette.colourFor(51))
    }

    @Test fun magnitude_is_symmetric_about_zero() {
        for (m in bandMagnitudes) {
            assertEquals(
                "band boundaries must match on both arms",
                CutFillPalette.colourFor(m) == CutFillPalette.colourFor(m + 1),
                CutFillPalette.colourFor(-m) == CutFillPalette.colourFor(-m - 1)
            )
        }
    }

    // ---- the non-colour channel ----

    @Test fun every_value_also_has_a_word() {
        assertEquals("on grade", CutFillPalette.labelFor(0))
        assertEquals("on grade", CutFillPalette.labelFor(-25))
        assertEquals("fill", CutFillPalette.labelFor(100))
        assertEquals("cut", CutFillPalette.labelFor(-100))
        assertEquals("no data", CutFillPalette.labelFor(CutFillField.NO_DATA))
    }

    @Test fun no_data_is_transparent_so_it_shows_the_ground_beneath() {
        assertEquals(0f, CutFillPalette.colourFor(CutFillField.NO_DATA).alpha, 1e-6f)
    }

    @Test fun the_legend_strip_is_symmetric_with_the_midpoint_in_the_middle() {
        val ramp = CutFillPalette.rampStrip()
        assertEquals(9, ramp.size)
        assertEquals(4, CutFillPalette.midpointIndex)
        assertEquals(CutFillPalette.colourFor(0), ramp[CutFillPalette.midpointIndex])
        // Deepest cut at the left end, deepest fill at the right.
        assertEquals(CutFillPalette.colourFor(-400), ramp.first())
        assertEquals(CutFillPalette.colourFor(400), ramp.last())
    }

    @Test fun legend_rows_cover_every_band_in_both_directions() {
        val rows = CutFillPalette.legend()
        assertEquals(9, rows.size)
        assertEquals(4, rows.count { it.title == "CUT" })
        assertEquals(4, rows.count { it.title == "FILL" })
        assertEquals(1, rows.count { it.title == "ON GRADE" })
    }

    private fun assertNotEquals(a: Any?, b: Any?) = assertTrue("expected different", a != b)
}
