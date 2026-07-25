package com.fieldgrade.app.surface

/**
 * A queryable design surface in the local site grid. This is the boundary the
 * guidance maths depends on; the (still-pending) real `.gps` parser will simply
 * produce one of these, so Phase 4 does not wait on Phase 3.
 */
interface DesignSurfaceModel {
    /** Design elevation (m) under a local East/North point, or null if outside. */
    fun elevationAt(eastM: Double, northM: Double): Double?
    fun contains(eastM: Double, northM: Double): Boolean
}

/**
 * A planar (single-grade) design surface: elevation = a + b*east + c*north,
 * optionally bounded by an axis-aligned rectangle. A grade plane is the common
 * land-levelling target, so this is a genuine design surface, not just a stub.
 */
class PlaneDesignSurface(
    private val a: Double,
    private val b: Double,
    private val c: Double,
    private val bounds: Bounds? = null
) : DesignSurfaceModel {

    data class Bounds(val minE: Double, val minN: Double, val maxE: Double, val maxN: Double)

    override fun contains(eastM: Double, northM: Double): Boolean {
        val bnd = bounds ?: return true
        return eastM in bnd.minE..bnd.maxE && northM in bnd.minN..bnd.maxN
    }

    override fun elevationAt(eastM: Double, northM: Double): Double? {
        if (!contains(eastM, northM)) return null
        return a + b * eastM + c * northM
    }

    companion object {
        /** Fit a plane through three non-collinear points (east, north, elevation). */
        fun fromThreePoints(
            p1: Triple<Double, Double, Double>,
            p2: Triple<Double, Double, Double>,
            p3: Triple<Double, Double, Double>,
            bounds: Bounds? = null
        ): PlaneDesignSurface {
            // Solve z = a + b*e + c*n for the three points via the plane normal.
            val (e1, n1, z1) = p1; val (e2, n2, z2) = p2; val (e3, n3, z3) = p3
            val ux = e2 - e1; val uy = n2 - n1; val uz = z2 - z1
            val vx = e3 - e1; val vy = n3 - n1; val vz = z3 - z1
            val nx = uy * vz - uz * vy
            val ny = uz * vx - ux * vz
            val nz = ux * vy - uy * vx
            require(kotlin.math.abs(nz) > 1e-9) { "design points are collinear/vertical" }
            val b = -nx / nz
            val c = -ny / nz
            val a = z1 - b * e1 - c * n1
            return PlaneDesignSurface(a, b, c, bounds)
        }
    }
}
