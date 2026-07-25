package com.fieldgrade.app.gnss

enum class FixQuality { NONE, AUTONOMOUS, DGPS, FLOAT, FIXED }

data class GnssSample(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val ellipsoidHeightM: Double,
    val quality: FixQuality,
    val horizontalAccuracyM: Double,
    val ageMs: Long
)
