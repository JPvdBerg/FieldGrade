package com.fieldgrade.app.design

import com.fieldgrade.app.SampleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [SurfaceBoundary] in isolation: triangles in, outline loops out. */
class SurfaceBoundaryTest {

    private fun p(e: Double, n: Double) = DesignPoint(e, n, 0.0)

    @Test fun a_single_triangle_is_its_own_boundary() {
        val pts = listOf(p(0.0, 0.0), p(10.0, 0.0), p(0.0, 10.0))
        val loops = SurfaceBoundary.of(pts, listOf(Triangle(0, 1, 2)))
        assertEquals(1, loops.size)
        assertEquals(3, loops[0].size)
        assertEquals(setOf(0, 1, 2), loops[0].toSet())
    }

    @Test fun two_triangles_sharing_an_edge_give_one_four_sided_loop() {
        // The shared diagonal must not appear in the outline.
        val pts = listOf(p(0.0, 0.0), p(10.0, 0.0), p(10.0, 10.0), p(0.0, 10.0))
        val loops = SurfaceBoundary.of(pts, listOf(Triangle(0, 1, 2), Triangle(0, 2, 3)))
        assertEquals(1, loops.size)
        assertEquals(4, loops[0].size)
        assertEquals(setOf(0, 1, 2, 3), loops[0].toSet())
    }

    @Test fun the_outline_of_a_grid_is_its_perimeter_only() {
        val n = 6
        val pts = ArrayList<DesignPoint>()
        for (i in 0 until n) for (j in 0 until n) pts.add(p(i * 10.0, j * 10.0))
        val tris = DelaunayTriangulator.triangulate(pts)
        val loops = SurfaceBoundary.of(pts, tris)

        assertEquals(1, loops.size)
        // A 6x6 grid has 4*(6-1) = 20 perimeter vertices; interior points must not appear.
        assertEquals(4 * (n - 1), loops[0].size)
        for (idx in loops[0]) {
            val pt = pts[idx]
            val onEdge = pt.eastM == 0.0 || pt.northM == 0.0 ||
                pt.eastM == (n - 1) * 10.0 || pt.northM == (n - 1) * 10.0
            assertTrue("interior point $idx appeared in the outline", onEdge)
        }
    }

    @Test fun a_concave_field_is_not_closed_off_like_a_hull_would() {
        // An L-shape. A convex hull would cut the corner and show the operator
        // ground that is not in the design.
        val pts = listOf(
            p(0.0, 0.0), p(20.0, 0.0), p(20.0, 10.0),
            p(10.0, 10.0), p(10.0, 20.0), p(0.0, 20.0)
        )
        val tris = listOf(
            Triangle(0, 1, 2), Triangle(0, 2, 3), Triangle(0, 3, 4), Triangle(0, 4, 5)
        )
        val loops = SurfaceBoundary.of(pts, tris)
        assertEquals(1, loops.size)
        // All six corners, including the re-entrant one at (10,10).
        assertEquals(6, loops[0].size)
        assertTrue("the re-entrant corner was cut off", loops[0].contains(3))
    }

    @Test fun a_hole_is_reported_as_its_own_loop() {
        // A square annulus: outer ring of 4, inner ring of 4, triangulated between.
        val outer = listOf(p(0.0, 0.0), p(30.0, 0.0), p(30.0, 30.0), p(0.0, 30.0))
        val inner = listOf(p(10.0, 10.0), p(20.0, 10.0), p(20.0, 20.0), p(10.0, 20.0))
        val pts = outer + inner
        val tris = listOf(
            Triangle(0, 1, 5), Triangle(0, 5, 4),
            Triangle(1, 2, 6), Triangle(1, 6, 5),
            Triangle(2, 3, 7), Triangle(2, 7, 6),
            Triangle(3, 0, 4), Triangle(3, 4, 7)
        )
        val loops = SurfaceBoundary.of(pts, tris)
        assertEquals(2, loops.size)
        assertEquals(setOf(0, 1, 2, 3), loops[0].toSet())   // longest first
        assertEquals(setOf(4, 5, 6, 7), loops[1].toSet())
    }

    @Test fun no_triangles_means_no_boundary() {
        assertTrue(SurfaceBoundary.of(listOf(p(0.0, 0.0)), emptyList()).isEmpty())
        assertTrue(SurfaceBoundary.outline(emptyList(), emptyList()).isEmpty())
    }

    // ---- against the real surface ----

    @Test fun outlines_the_real_nunosurf_field() {
        val points = XyzPointReader.read(SampleData.nunosurfXyz().readText()).points
        val tris = DelaunayTriangulator.triangulate(points)
        val outline = SurfaceBoundary.outline(points, tris)

        assertTrue("expected a substantial outline, got ${outline.size}", outline.size > 50)

        // A Delaunay TIN's boundary is the convex hull, so every surface point must
        // lie inside or on the loop. Checked by signed area consistency.
        val bounds = DesignSurface("x", points).bounds()!!
        for (v in outline) {
            assertTrue(v.eastM in (bounds.minE - 1e-6)..(bounds.maxE + 1e-6))
            assertTrue(v.northM in (bounds.minN - 1e-6)..(bounds.maxN + 1e-6))
        }

        // The loop must actually enclose the field, not hug one edge of it.
        val loopMinE = outline.minOf { it.eastM }
        val loopMaxE = outline.maxOf { it.eastM }
        val loopMinN = outline.minOf { it.northM }
        val loopMaxN = outline.maxOf { it.northM }
        assertEquals(bounds.minE, loopMinE, 0.5)
        assertEquals(bounds.maxE, loopMaxE, 0.5)
        assertEquals(bounds.minN, loopMinN, 0.5)
        assertEquals(bounds.maxN, loopMaxN, 0.5)
    }

    @Test fun outlines_the_real_landxml_tin_with_its_own_faces() {
        val surface = SampleData.nunosurfXml().inputStream().use { LandXmlSurfaceReader.read(it) }
        val outline = SurfaceBoundary.outline(surface.points, surface.triangles)
        assertTrue("expected an outline from the designer's own faces", outline.size > 50)
    }
}
