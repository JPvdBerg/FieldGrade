package com.fieldgrade.app.design

import kotlin.math.abs
import kotlin.math.max

/**
 * Builds a Delaunay triangulation of scattered survey points (Bowyer–Watson).
 *
 * One job: points in, triangle indices out. It knows nothing about elevation —
 * triangulation is a purely 2D (East/North) operation and the Z value rides
 * along untouched. It does not read files and does not interpolate.
 *
 * Why Delaunay: it maximises the minimum angle, which avoids the long slivers
 * that make elevation interpolation swing wildly between distant points. That
 * matters directly here — a sliver triangle spanning two survey runs would
 * report a confident, wrong design elevation in the gap between them.
 *
 * Only used for loose XYZ point sets. A LandXML TIN already carries the
 * designer's own faces, including breaklines, and those are preserved rather
 * than recomputed — see [DesignSurface.hasTriangles].
 */
object DelaunayTriangulator {

    /** Points closer than this in plan are treated as the same point. */
    private const val DEDUPE_TOLERANCE_M = 1e-6

    /**
     * @return triangles as indices into [points]. Empty when the points are
     *         degenerate (fewer than 3 distinct, or all collinear).
     */
    fun triangulate(points: List<DesignPoint>): List<Triangle> {
        if (points.size < 3) return emptyList()

        // --- deduplicate in plan, keeping a map back to original indices ---
        val order = points.indices.sortedWith(
            compareBy({ points[it].eastM }, { points[it].northM })
        )
        val keptIdx = ArrayList<Int>(points.size)
        for (i in order) {
            val last = keptIdx.lastOrNull()
            if (last != null &&
                abs(points[i].eastM - points[last].eastM) < DEDUPE_TOLERANCE_M &&
                abs(points[i].northM - points[last].northM) < DEDUPE_TOLERANCE_M
            ) continue
            keptIdx.add(i)
        }
        if (keptIdx.size < 3) return emptyList()

        val n = keptIdx.size
        val xs = DoubleArray(n + 3)
        val ys = DoubleArray(n + 3)
        for (k in 0 until n) {
            xs[k] = points[keptIdx[k]].eastM
            ys[k] = points[keptIdx[k]].northM
        }

        // --- super-triangle, comfortably enclosing every point ---
        var minX = xs[0]; var maxX = xs[0]; var minY = ys[0]; var maxY = ys[0]
        for (k in 1 until n) {
            if (xs[k] < minX) minX = xs[k]; if (xs[k] > maxX) maxX = xs[k]
            if (ys[k] < minY) minY = ys[k]; if (ys[k] > maxY) maxY = ys[k]
        }
        val dx = maxX - minX
        val dy = maxY - minY
        val span = max(dx, dy).takeIf { it > 0.0 } ?: return emptyList()
        val cx = (minX + maxX) / 2.0
        val cy = (minY + maxY) / 2.0
        val r = span * 20.0
        xs[n] = cx - r; ys[n] = cy - r
        xs[n + 1] = cx + r; ys[n + 1] = cy - r
        xs[n + 2] = cx; ys[n + 2] = cy + r

        val tri = ArrayList<Tri>(n * 2 + 16)
        tri.add(makeTri(n, n + 1, n + 2, xs, ys))

        val bad = ArrayList<Int>(32)
        val edgeCount = HashMap<Long, Int>(64)

        for (p in 0 until n) {
            val px = xs[p]; val py = ys[p]

            bad.clear()
            for (t in tri.indices) {
                val tt = tri[t]
                if (tt.dead) continue
                val ddx = px - tt.ccx
                val ddy = py - tt.ccy
                if (ddx * ddx + ddy * ddy <= tt.ccr2) bad.add(t)
            }
            if (bad.isEmpty()) continue   // numerically outside everything; skip rather than corrupt

            // Boundary of the cavity = edges belonging to exactly one bad triangle.
            edgeCount.clear()
            for (t in bad) {
                val tt = tri[t]
                bumpEdge(edgeCount, tt.a, tt.b)
                bumpEdge(edgeCount, tt.b, tt.c)
                bumpEdge(edgeCount, tt.c, tt.a)
                tt.dead = true
            }
            for ((key, count) in edgeCount) {
                if (count != 1) continue
                val u = (key ushr 32).toInt()
                val v = (key and 0xFFFFFFFFL).toInt()
                tri.add(makeTri(u, v, p, xs, ys))
            }
        }

        // --- drop everything still touching the super-triangle ---
        val out = ArrayList<Triangle>(tri.size)
        for (tt in tri) {
            if (tt.dead) continue
            if (tt.a >= n || tt.b >= n || tt.c >= n) continue
            out.add(Triangle(keptIdx[tt.a], keptIdx[tt.b], keptIdx[tt.c]))
        }
        return out
    }

    private fun bumpEdge(map: HashMap<Long, Int>, i: Int, j: Int) {
        val lo = minOf(i, j).toLong()
        val hi = maxOf(i, j).toLong()
        val key = (lo shl 32) or hi
        map[key] = (map[key] ?: 0) + 1
    }

    /** A triangle plus its cached circumcircle. */
    private class Tri(
        val a: Int, val b: Int, val c: Int,
        val ccx: Double, val ccy: Double, val ccr2: Double
    ) {
        var dead = false
    }

    private fun makeTri(a: Int, b: Int, c: Int, xs: DoubleArray, ys: DoubleArray): Tri {
        val ax = xs[a]; val ay = ys[a]
        val bx = xs[b]; val by = ys[b]
        val cx = xs[c]; val cy = ys[c]

        val d = 2.0 * (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by))
        if (abs(d) < 1e-18) {
            // Collinear: no finite circumcircle. Park it far away with zero radius
            // so it never captures a point and gets discarded at the end.
            return Tri(a, b, c, Double.MAX_VALUE / 4, Double.MAX_VALUE / 4, 0.0)
        }
        val a2 = ax * ax + ay * ay
        val b2 = bx * bx + by * by
        val c2 = cx * cx + cy * cy
        val ux = (a2 * (by - cy) + b2 * (cy - ay) + c2 * (ay - by)) / d
        val uy = (a2 * (cx - bx) + b2 * (ax - cx) + c2 * (bx - ax)) / d
        val rdx = ax - ux
        val rdy = ay - uy
        return Tri(a, b, c, ux, uy, rdx * rdx + rdy * rdy)
    }
}
