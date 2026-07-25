package com.fieldgrade.app.gnss

enum class FixQuality { NONE, AUTONOMOUS, DGPS, FLOAT, FIXED }

/** A fused GNSS solution at one instant. Height is ellipsoidal metres. */
data class GnssSample(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val ellipsoidHeightM: Double,
    val quality: FixQuality,
    val horizontalAccuracyM: Double,
    val ageMs: Long,
    val verticalAccuracyM: Double = Double.NaN,
    val satellites: Int = 0,
    val speedMps: Double = Double.NaN,
    val headingDeg: Double = Double.NaN
)
