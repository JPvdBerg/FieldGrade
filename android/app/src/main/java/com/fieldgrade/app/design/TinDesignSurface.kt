package com.fieldgrade.app.design

import com.fieldgrade.app.surface.DesignSurfaceModel

/**
 * A design surface backed by a triangulated irregular network.
 *
 * One job: answer "what is the design elevation at this East/North?" by finding
 * the triangle under the point and interpolating across it. It does not parse,
 * does not triangulate (it delegates), and does not know about GNSS.
 *
 * This is the module that finally satisfies [DesignSurfaceModel] with a real
 * surveyed surface rather than the hardcoded plane the UI has been demoing on,
 * so everything downstream — cut/fill, the map, AUTO — works unchanged.
 *
 * Outside the triangulation it returns null rather than extrapolating. That is a
 * safety property, not tidiness: beyond the surveyed boundary there is no design,
 * and inventing one would let AUTO confidently cut ground nobody surveyed.
 * [com.fieldgrade.app.control.GuidanceEngine] turns that null into
 * "outside design boundary" and refuses AUTO.
 */
class TinDesignSurface(
    private val points: List<DesignPoint>,
    private val triangles: List<Triangle>,
    val name: String = "TIN"
) : DesignSurfaceModel {

    private val index = TriangleIndex(points, triangles)

    val triangleCount: Int get() = triangles.size
    val pointCount: Int get() = points.size

    override fun contains(eastM: Double, northM: Double): Boolean =
        elevationAt(eastM, northM) != null

    override fun elevationAt(eastM: Double, northM: Double): Double? {
        for (t in index.candidatesAt(eastM, northM)) {
            val z = interpolateInTriangle(triangles[t], eastM, northM)
            if (z != null) return z
        }
        return null
    }

    /**
     * Barycentric interpolation. Returns null when the point is outside this
     * triangle, so the caller can try the next candidate.
     */
    private fun interpolateInTriangle(tri: Triangle, e: Double, n: Double): Double? {
        val a = points[tri.a]
        val b = points[tri.b]
        val c = points[tri.c]

        val v0e = b.eastM - a.eastM
        val v0n = b.northM - a.northM
        val v1e = c.eastM - a.eastM
        val v1n = c.northM - a.northM

        val denom = v0e * v1n - v1e * v0n
        if (denom == 0.0) return null            // degenerate sliver

        val pe = e - a.eastM
        val pn = n - a.northM

        val w1 = (pe * v1n - v1e * pn) / denom   // weight of b
        val w2 = (v0e * pn - pe * v0n) / denom   // weight of c
        val w0 = 1.0 - w1 - w2                   // weight of a

        // Tolerance so a point exactly on a shared edge belongs to one of the
        // two triangles rather than falling through the gap between them.
        if (w0 < -EDGE_TOLERANCE || w1 < -EDGE_TOLERANCE || w2 < -EDGE_TOLERANCE) return null

        return w0 * a.elevationM + w1 * b.elevationM + w2 * c.elevationM
    }

    fun describe(): String =
        "$name: $pointCount points, $triangleCount triangles; ${index.describe()}"

    companion object {
        private const val EDGE_TOLERANCE = 1e-9

        /**
         * Build from a [DesignSurface], triangulating only when the source did
         * not already supply faces. A designer's own TIN is preserved as-is —
         * re-triangulating it can cut across breaklines and silently change the
         * surface the customer approved.
         */
        fun from(surface: DesignSurface): TinDesignSurface {
            val tris = if (surface.hasTriangles) {
                surface.triangles
            } else {
                DelaunayTriangulator.triangulate(surface.points)
            }
            if (tris.isEmpty()) {
                throw DesignFormatException(
                    "surface '${surface.name}' has ${surface.points.size} points but no " +
                        "usable triangulation (collinear or degenerate?)"
                )
            }
            return TinDesignSurface(surface.points, tris, surface.name)
        }
    }
}
