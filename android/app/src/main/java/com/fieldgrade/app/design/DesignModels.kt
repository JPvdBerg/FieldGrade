package com.fieldgrade.app.design

/**
 * Plain data for design surfaces. No parsing, no maths, no I/O — every other
 * module in this package either produces or consumes these types, which is what
 * keeps the readers, the triangulator and the surface query independent of
 * each other.
 *
 * Coordinates are metres in the site's working grid (East / North / elevation).
 * Whatever grid the source file used, the reader is responsible for landing the
 * values here in that convention.
 */
data class DesignPoint(val eastM: Double, val northM: Double, val elevationM: Double)

/** A triangle as three indices into a point list. */
data class Triangle(val a: Int, val b: Int, val c: Int)

/**
 * A set of design points that may or may not already carry a triangulation.
 *
 * A LandXML TIN arrives with its faces already defined by the designer and those
 * must be preserved — re-triangulating someone's surveyed surface can quietly
 * change it across breaklines. Loose XYZ points arrive with none, and get
 * triangulated here. [hasTriangles] is how the caller tells the difference.
 */
data class DesignSurface(
    val name: String,
    val points: List<DesignPoint>,
    val triangles: List<Triangle> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
) {
    val hasTriangles: Boolean get() = triangles.isNotEmpty()

    /** Axis-aligned bounds, or null when there are no points. */
    fun bounds(): Bounds? {
        if (points.isEmpty()) return null
        var minE = Double.MAX_VALUE; var maxE = -Double.MAX_VALUE
        var minN = Double.MAX_VALUE; var maxN = -Double.MAX_VALUE
        var minZ = Double.MAX_VALUE; var maxZ = -Double.MAX_VALUE
        for (p in points) {
            if (p.eastM < minE) minE = p.eastM
            if (p.eastM > maxE) maxE = p.eastM
            if (p.northM < minN) minN = p.northM
            if (p.northM > maxN) maxN = p.northM
            if (p.elevationM < minZ) minZ = p.elevationM
            if (p.elevationM > maxZ) maxZ = p.elevationM
        }
        return Bounds(minE, minN, maxE, maxN, minZ, maxZ)
    }

    data class Bounds(
        val minE: Double, val minN: Double,
        val maxE: Double, val maxN: Double,
        val minZ: Double, val maxZ: Double
    ) {
        val widthM: Double get() = maxE - minE
        val heightM: Double get() = maxN - minN
        val reliefM: Double get() = maxZ - minZ
    }
}

/** Raised by every reader in this package when input cannot be parsed honestly. */
class DesignFormatException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)
