package com.fieldgrade.app.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** [DelaunayTriangulator] and [TriangleIndex] in isolation — pure 2D geometry. */
class TriangulationTest {

    private fun p(e: Double, n: Double, z: Double = 0.0) = DesignPoint(e, n, z)

    @Test fun three_points_make_one_triangle() {
        val tris = DelaunayTriangulator.triangulate(listOf(p(0.0, 0.0), p(10.0, 0.0), p(0.0, 10.0)))
        assertEquals(1, tris.size)
    }

    @Test fun a_square_makes_two_triangles() {
        val tris = DelaunayTriangulator.triangulate(
            listOf(p(0.0, 0.0), p(10.0, 0.0), p(10.0, 10.0), p(0.0, 10.0))
        )
        assertEquals(2, tris.size)
    }

    @Test fun a_regular_grid_is_fully_triangulated() {
        // An n x n grid of points has exactly 2*(n-1)^2 triangles when the hull
        // is fully covered. Anything less means holes in the surface.
        val n = 8
        val pts = ArrayList<DesignPoint>()
        for (i in 0 until n) for (j in 0 until n) pts.add(p(i * 5.0, j * 5.0))
        val tris = DelaunayTriangulator.triangulate(pts)
        assertEquals(2 * (n - 1) * (n - 1), tris.size)
    }

    @Test fun collinear_points_have_no_triangulation() {
        val pts = (0..10).map { p(it * 1.0, it * 2.0) }
        assertTrue(DelaunayTriangulator.triangulate(pts).isEmpty())
    }

    @Test fun fewer_than_three_points_is_empty_not_a_crash() {
        assertTrue(DelaunayTriangulator.triangulate(emptyList()).isEmpty())
        assertTrue(DelaunayTriangulator.triangulate(listOf(p(0.0, 0.0))).isEmpty())
        assertTrue(DelaunayTriangulator.triangulate(listOf(p(0.0, 0.0), p(1.0, 1.0))).isEmpty())
    }

    @Test fun duplicate_points_do_not_produce_degenerate_triangles() {
        val pts = listOf(
            p(0.0, 0.0), p(0.0, 0.0), p(10.0, 0.0), p(10.0, 0.0), p(0.0, 10.0)
        )
        val tris = DelaunayTriangulator.triangulate(pts)
        assertEquals(1, tris.size)
        val t = tris[0]
        assertTrue("indices must be distinct", t.a != t.b && t.b != t.c && t.a != t.c)
    }

    @Test fun satisfies_the_delaunay_empty_circumcircle_property() {
        // The defining property: no vertex lies strictly inside any triangle's
        // circumcircle. This is what rules out the sliver triangles that would
        // interpolate elevation wildly between distant survey points.
        val pts = listOf(
            p(0.0, 0.0), p(37.0, 4.0), p(12.0, 41.0), p(55.0, 33.0),
            p(21.0, 18.0), p(48.0, 9.0), p(5.0, 27.0), p(31.0, 52.0)
        )
        val tris = DelaunayTriangulator.triangulate(pts)
        assertTrue(tris.isNotEmpty())

        for (t in tris) {
            val a = pts[t.a]; val b = pts[t.b]; val c = pts[t.c]
            val d = 2.0 * (a.eastM * (b.northM - c.northM) +
                b.eastM * (c.northM - a.northM) +
                c.eastM * (a.northM - b.northM))
            if (kotlin.math.abs(d) < 1e-12) continue
            val a2 = a.eastM * a.eastM + a.northM * a.northM
            val b2 = b.eastM * b.eastM + b.northM * b.northM
            val c2 = c.eastM * c.eastM + c.northM * c.northM
            val ux = (a2 * (b.northM - c.northM) + b2 * (c.northM - a.northM) +
                c2 * (a.northM - b.northM)) / d
            val uy = (a2 * (c.eastM - b.eastM) + b2 * (a.eastM - c.eastM) +
                c2 * (b.eastM - a.eastM)) / d
            val r = hypot(a.eastM - ux, a.northM - uy)

            pts.forEachIndexed { i, q ->
                if (i == t.a || i == t.b || i == t.c) return@forEachIndexed
                val dist = hypot(q.eastM - ux, q.northM - uy)
                assertTrue(
                    "point $i lies inside the circumcircle of $t (d=$dist r=$r)",
                    dist >= r - 1e-6
                )
            }
        }
    }

    // ---- index ----

    @Test fun index_returns_a_candidate_containing_the_query_point() {
        val pts = ArrayList<DesignPoint>()
        for (i in 0 until 12) for (j in 0 until 12) pts.add(p(i * 10.0, j * 10.0))
        val tris = DelaunayTriangulator.triangulate(pts)
        val index = TriangleIndex(pts, tris)

        // Every interior query must land in at least one candidate cell.
        for (e in listOf(5.0, 33.0, 61.7, 99.0)) {
            for (n in listOf(5.0, 44.0, 72.3, 108.0)) {
                assertTrue(
                    "no candidates at ($e,$n)",
                    index.candidatesAt(e, n).isNotEmpty()
                )
            }
        }
    }

    @Test fun index_prunes_hard_relative_to_a_linear_scan() {
        val pts = ArrayList<DesignPoint>()
        for (i in 0 until 40) for (j in 0 until 40) pts.add(p(i * 2.0, j * 2.0))
        val tris = DelaunayTriangulator.triangulate(pts)
        val index = TriangleIndex(pts, tris)
        val candidates = index.candidatesAt(41.0, 41.0)
        assertTrue("expected a few candidates, got ${candidates.size} of ${tris.size}",
            candidates.size < tris.size / 50)
    }
}
