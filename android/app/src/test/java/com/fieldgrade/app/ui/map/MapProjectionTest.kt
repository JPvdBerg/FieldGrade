package com.fieldgrade.app.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** [MapProjection] in isolation: metres in, pixels out, in both cameras. */
class MapProjectionTest {

    private fun square(width: Float = 400f, height: Float = 400f, padding: Float = 0f) =
        MapProjection.boxed(0.0, 0.0, 100.0, 100.0, width, height, padding)

    // ---- north-up camera ----

    @Test fun north_is_up() {
        // The single most important property. A map that flips north would put
        // every cut on the wrong side of the field.
        val p = square()
        assertTrue("north must render above south", p.toScreen(50.0, 100.0).y < p.toScreen(50.0, 0.0).y)
    }

    @Test fun east_is_right() {
        val p = square()
        assertTrue(p.toScreen(100.0, 50.0).x > p.toScreen(0.0, 50.0).x)
    }

    @Test fun corners_map_to_the_canvas_corners() {
        val p = square(padding = 0f)
        val bottomLeft = p.toScreen(0.0, 0.0)
        val topRight = p.toScreen(100.0, 100.0)
        assertEquals(0f, bottomLeft.x, 1e-3f)
        assertEquals(400f, bottomLeft.y, 1e-3f)
        assertEquals(400f, topRight.x, 1e-3f)
        assertEquals(0f, topRight.y, 1e-3f)
    }

    @Test fun scale_is_equal_on_both_axes_in_a_non_square_window() {
        // Stretching to fill would distort every distance read off the screen.
        val p = MapProjection.boxed(0.0, 0.0, 100.0, 100.0, 800f, 400f, 0f)
        val tenEast = p.toScreen(10.0, 0.0).x - p.toScreen(0.0, 0.0).x
        val tenNorth = p.toScreen(0.0, 0.0).y - p.toScreen(0.0, 10.0).y
        assertEquals(tenEast, tenNorth, 1e-3f)
    }

    @Test fun a_wide_window_centres_the_field_horizontally() {
        val p = MapProjection.boxed(0.0, 0.0, 100.0, 100.0, 800f, 400f, 0f)
        val left = p.toScreen(0.0, 50.0).x
        val right = p.toScreen(100.0, 50.0).x
        assertEquals("field not centred", 800f - right, left, 1e-3f)
    }

    @Test fun padding_insets_the_drawing() {
        val p = square(padding = 20f)
        assertEquals(20f, p.toScreen(0.0, 0.0).x, 1e-3f)
        assertEquals(380f, p.toScreen(0.0, 0.0).y, 1e-3f)
    }

    @Test fun pixels_and_metres_round_trip() {
        assertEquals(25.0, square().let { it.metresFor(it.pixelsFor(25.0)) }, 1e-6)
    }

    @Test fun the_scale_bar_uses_round_numbers() {
        val p = square()             // 400 px over 100 m -> 4 px/m
        assertEquals(25.0, p.niceScaleBarMetres(120f), 1e-9)   // 30 m of ground -> 25
        assertEquals(10.0, p.niceScaleBarMetres(50f), 1e-9)
    }

    @Test fun fitting_adds_a_margin_so_the_edge_is_not_flush() {
        val p = MapProjection.fitting(0.0, 0.0, 100.0, 100.0, 400f, 400f, paddingPx = 0f)
        assertTrue("expected the field edge to be inset", p.toScreen(0.0, 0.0).x > 1f)
        assertTrue(p.toScreen(0.0, 0.0).y < 399f)
    }

    @Test fun a_degenerate_extent_does_not_divide_by_zero() {
        val p = MapProjection.boxed(5.0, 5.0, 5.0, 5.0, 400f, 400f, 0f)
        val s = p.toScreen(5.0, 5.0)
        assertTrue(s.x.isFinite() && s.y.isFinite())
    }

    @Test fun north_up_reports_north_as_straight_up() {
        val n = square().northOnScreen()
        assertEquals(0f, n.x, 1e-6f)
        assertEquals(-1f, n.y, 1e-6f)
        assertFalse(square().isRotated)
    }

    // ---- heading-up camera ----

    private fun headingUp(headingDeg: Double) =
        MapProjection.centredOn(
            eastM = 50.0, northM = 50.0, radiusM = 50.0, headingDeg = headingDeg,
            widthPx = 400f, heightPx = 400f, anchorFraction = 0.5f
        )

    @Test fun the_machine_sits_at_its_anchor_whatever_the_heading() {
        for (h in listOf(0.0, 45.0, 137.0, 270.0, 359.9)) {
            val s = headingUp(h).toScreen(50.0, 50.0)
            assertEquals("heading $h", 200f, s.x, 1e-3f)
            assertEquals("heading $h", 200f, s.y, 1e-3f)
        }
    }

    @Test fun the_look_ahead_anchor_puts_the_machine_below_centre() {
        // Most of the screen must show ground ahead, not ground already worked.
        val p = MapProjection.centredOn(0.0, 0.0, 50.0, 0.0, 400f, 400f)
        assertTrue("machine should sit below centre", p.toScreen(0.0, 0.0).y > 200f)
    }

    @Test fun heading_zero_behaves_exactly_like_north_up() {
        val p = headingUp(0.0)
        val ahead = p.toScreen(50.0, 60.0)      // 10 m north
        assertEquals(200f, ahead.x, 1e-3f)
        assertTrue("north must be up when heading is north", ahead.y < 200f)
        assertFalse(p.isRotated)
    }

    @Test fun driving_east_puts_east_at_the_top_of_the_screen() {
        val p = headingUp(90.0)
        val ahead = p.toScreen(60.0, 50.0)      // 10 m east = 10 m ahead
        assertEquals("ahead must be straight up", 200f, ahead.x, 1e-3f)
        assertTrue("ahead must be above the machine", ahead.y < 200f)
    }

    @Test fun driving_south_puts_south_at_the_top() {
        val p = headingUp(180.0)
        val ahead = p.toScreen(50.0, 40.0)      // 10 m south = ahead
        assertEquals(200f, ahead.x, 1e-3f)
        assertTrue(ahead.y < 200f)
    }

    @Test fun the_ground_ahead_is_always_up_for_every_heading() {
        // The defining property of a heading-up map, checked all the way round
        // rather than at the four cardinals where sign errors hide.
        for (h in 0 until 360 step 7) {
            val rad = Math.toRadians(h.toDouble())
            val aheadE = 50.0 + 20.0 * kotlin.math.sin(rad)
            val aheadN = 50.0 + 20.0 * kotlin.math.cos(rad)
            val s = headingUp(h.toDouble()).toScreen(aheadE, aheadN)
            assertEquals("heading $h: ahead drifted sideways", 200f, s.x, 0.05f)
            assertTrue("heading $h: ahead was not up (y=${s.y})", s.y < 199f)
        }
    }

    @Test fun rotation_preserves_distance() {
        // Rotating the view must not change how far apart two points look.
        val north = square()
        val rotated = headingUp(137.0)
        fun span(p: MapProjection): Float {
            val a = p.toScreen(20.0, 20.0)
            val b = p.toScreen(60.0, 45.0)
            return hypot(a.x - b.x, a.y - b.y)
        }
        // Same ground distance, different scales; compare in metres.
        assertEquals(north.metresFor(span(north)), rotated.metresFor(span(rotated)), 1e-3)
    }

    @Test fun north_is_reported_correctly_when_rotated() {
        // The north arrow must never disagree with the map beneath it.
        val p = headingUp(90.0)                 // driving east, so north is to the left
        val n = p.northOnScreen()
        assertEquals(-1f, n.x, 1e-6f)
        assertEquals(0f, n.y, 1e-6f)
        assertTrue(p.isRotated)
    }

    @Test fun a_non_finite_heading_falls_back_to_north_up() {
        // Before the first usable course, rotating by NaN would erase the map.
        val p = headingUp(Double.NaN)
        assertFalse(p.isRotated)
        val s = p.toScreen(50.0, 60.0)
        assertTrue(s.x.isFinite() && s.y.isFinite())
        assertTrue(s.y < 200f)
    }

    @Test fun the_requested_radius_fills_the_shorter_axis() {
        val p = MapProjection.centredOn(0.0, 0.0, 50.0, 0.0, 400f, 600f, 0.5f)
        assertEquals(4f, p.scale, 1e-6f)        // 400 px / 100 m
        assertEquals(50.0, p.metresFor(200f), 1e-6)
    }
}
