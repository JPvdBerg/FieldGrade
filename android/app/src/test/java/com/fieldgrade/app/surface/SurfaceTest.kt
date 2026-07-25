package com.fieldgrade.app.surface

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceTest {

    @Test fun plane_from_three_points_interpolates() {
        val s = PlaneDesignSurface.fromThreePoints(
            Triple(0.0, 0.0, 100.0),
            Triple(10.0, 0.0, 100.1),
            Triple(0.0, 10.0, 100.2)
        )
        assertEquals(100.15, s.elevationAt(5.0, 5.0)!!, 1e-9)
        assertEquals(100.0, s.elevationAt(0.0, 0.0)!!, 1e-9)
    }

    @Test fun explicit_plane_coefficients() {
        val s = PlaneDesignSurface(a = 1542.400, b = 0.0, c = -0.005)
        assertEquals(1541.900, s.elevationAt(0.0, 100.0)!!, 1e-9)
    }

    @Test fun bounds_reject_points_outside() {
        val s = PlaneDesignSurface(
            a = 100.0, b = 0.0, c = 0.0,
            bounds = PlaneDesignSurface.Bounds(-50.0, -50.0, 50.0, 50.0)
        )
        assertTrue(s.contains(0.0, 0.0))
        assertNotNull(s.elevationAt(0.0, 0.0))
        assertFalse(s.contains(100.0, 0.0))
        assertNull(s.elevationAt(100.0, 0.0))
    }

    private fun assertNotNull(v: Any?) = assertTrue(v != null)
}
