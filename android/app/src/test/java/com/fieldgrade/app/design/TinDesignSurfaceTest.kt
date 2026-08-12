package com.fieldgrade.app.design

import com.fieldgrade.app.SampleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [TinDesignSurface] in isolation: geometry in, elevation out. */
class TinDesignSurfaceTest {

    /** A tilted plane sampled on a grid — interpolation must reproduce it exactly. */
    private fun planarSurface(
        a: Double = 100.0, b: Double = 0.01, c: Double = -0.004, n: Int = 10, spacing: Double = 10.0
    ): TinDesignSurface {
        val pts = ArrayList<DesignPoint>()
        for (i in 0 until n) for (j in 0 until n) {
            val e = i * spacing
            val nn = j * spacing
            pts.add(DesignPoint(e, nn, a + b * e + c * nn))
        }
        return TinDesignSurface.from(DesignSurface("plane", pts))
    }

    @Test fun interpolates_a_plane_exactly_between_its_samples() {
        val s = planarSurface()
        // Any linear surface must be reproduced exactly by barycentric
        // interpolation, at points that are deliberately not on a vertex.
        for ((e, n) in listOf(3.7 to 4.2, 45.5 to 22.25, 71.3 to 88.9, 12.0 to 67.5)) {
            val expected = 100.0 + 0.01 * e - 0.004 * n
            val got = s.elevationAt(e, n)
            assertNotNull("expected a solution at ($e,$n)", got)
            assertEquals(expected, got!!, 1e-9)
        }
    }

    @Test fun vertices_return_their_own_elevation() {
        val s = planarSurface()
        assertEquals(100.0 + 0.01 * 30.0 - 0.004 * 40.0, s.elevationAt(30.0, 40.0)!!, 1e-9)
    }

    @Test fun outside_the_hull_returns_null_rather_than_extrapolating() {
        val s = planarSurface()
        // Beyond the surveyed edge there is no design. Inventing one would let
        // AUTO cut ground nobody measured.
        assertNull(s.elevationAt(-50.0, 50.0))
        assertNull(s.elevationAt(50.0, -50.0))
        assertNull(s.elevationAt(500.0, 50.0))
        assertNull(s.elevationAt(50.0, 500.0))
        assertTrue(s.contains(45.0, 45.0))
        assertFalse(s.contains(-1000.0, -1000.0))
    }

    private fun assertFalse(b: Boolean) = assertTrue(!b)

    @Test fun a_non_planar_surface_is_interpolated_within_its_neighbours() {
        // A ridge: interpolated values must never exceed the local sample range.
        val pts = listOf(
            DesignPoint(0.0, 0.0, 10.0),
            DesignPoint(10.0, 0.0, 20.0),
            DesignPoint(0.0, 10.0, 10.0),
            DesignPoint(10.0, 10.0, 20.0)
        )
        val s = TinDesignSurface.from(DesignSurface("ridge", pts))
        val mid = s.elevationAt(5.0, 5.0)!!
        assertTrue("interpolated $mid outside sample range", mid in 10.0..20.0)
        assertEquals(15.0, mid, 1e-9)
    }

    @Test fun degenerate_input_is_refused_not_silently_empty() {
        val collinear = (0..5).map { DesignPoint(it * 1.0, it * 1.0, 5.0) }
        try {
            TinDesignSurface.from(DesignSurface("line", collinear))
            throw AssertionError("expected DesignFormatException")
        } catch (e: DesignFormatException) {
            assertTrue(e.message!!.contains("triangulation"))
        }
    }

    @Test fun supplied_triangles_are_preserved_not_recomputed() {
        // Two points sets that Delaunay would connect differently; the caller's
        // faces must survive, because a designer's breaklines live in them.
        val pts = listOf(
            DesignPoint(0.0, 0.0, 0.0),
            DesignPoint(10.0, 0.0, 0.0),
            DesignPoint(10.0, 10.0, 0.0),
            DesignPoint(0.0, 10.0, 0.0)
        )
        val explicit = listOf(Triangle(0, 1, 2))          // only one of the two
        val s = TinDesignSurface.from(DesignSurface("kept", pts, explicit))
        assertEquals(1, s.triangleCount)
        // The half that was not supplied must be a hole, not silently filled.
        assertNull(s.elevationAt(1.0, 9.0))
    }

    // ---- against the real surface ----

    @Test fun queries_the_real_nunosurf_tin() {
        val surface = SampleData.nunosurfXml().inputStream().use { LandXmlSurfaceReader.read(it) }
        val tin = TinDesignSurface.from(surface)
        val b = surface.bounds()!!

        assertEquals(7048, tin.pointCount)
        assertEquals(12798, tin.triangleCount)

        // Sample a grid across the field; every hit must land inside the real
        // elevation range of the surface (390.00 .. 392.00).
        var hits = 0
        var misses = 0
        for (i in 1..20) for (j in 1..20) {
            val e = b.minE + b.widthM * i / 21.0
            val n = b.minN + b.heightM * j / 21.0
            val z = tin.elevationAt(e, n)
            if (z == null) {
                misses++
            } else {
                hits++
                // Real range of the converted surface: 118.87 .. 119.48 m.
                assertTrue("elevation $z outside surface range", z in 118.86..119.49)
            }
        }
        assertTrue("expected most of a 400-point grid to hit the surface, got $hits", hits > 250)
    }
}
