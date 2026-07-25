package com.fieldgrade.app.geom

import kotlin.math.cos

/** A point in the local site grid: metres East, North (Up handled separately). */
data class LocalXY(val eastM: Double, val northM: Double)

/**
 * Local tangent-plane (equirectangular) transform about a fixed origin. Accurate
 * to well under a centimetre over a single field, which is all a design surface
 * spans. The origin is the site benchmark set at job start / rebench.
 */
class CoordinateTransform(private val originLatDeg: Double, private val originLonDeg: Double) {

    private val metresPerDegLat: Double
    private val metresPerDegLon: Double

    init {
        val phi = Math.toRadians(originLatDeg)
        // WGS84 length-of-a-degree series (metres per degree).
        metresPerDegLat = 111_132.92 - 559.82 * cos(2 * phi) + 1.175 * cos(4 * phi) - 0.0023 * cos(6 * phi)
        metresPerDegLon = 111_412.84 * cos(phi) - 93.5 * cos(3 * phi) + 0.118 * cos(5 * phi)
    }

    fun toLocal(latDeg: Double, lonDeg: Double): LocalXY =
        LocalXY(
            eastM = (lonDeg - originLonDeg) * metresPerDegLon,
            northM = (latDeg - originLatDeg) * metresPerDegLat
        )

    fun toGeodetic(local: LocalXY): Pair<Double, Double> =
        Pair(
            originLatDeg + local.northM / metresPerDegLat,
            originLonDeg + local.eastM / metresPerDegLon
        )
}
