package com.fieldgrade.app.gnss

import kotlin.math.sqrt

/** Parsed NMEA 0183 sentences we care about. */
sealed interface NmeaSentence

data class GgaData(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val quality: FixQuality,
    val satellites: Int,
    val hdop: Double,
    val altitudeMslM: Double,
    val geoidSepM: Double
) : NmeaSentence {
    /** Ellipsoidal height = orthometric (MSL) height + geoid separation. */
    val ellipsoidHeightM: Double get() = altitudeMslM + geoidSepM
}

data class GstData(
    val latStdDevM: Double,
    val lonStdDevM: Double,
    val altStdDevM: Double
) : NmeaSentence {
    val horizontalAccuracyM: Double get() = sqrt(latStdDevM * latStdDevM + lonStdDevM * lonStdDevM)
}

data class RmcData(
    val valid: Boolean,
    val speedMps: Double,
    val headingDeg: Double
) : NmeaSentence

object NmeaParser {

    /** XOR checksum of the sentence body (the text between `$` and `*`). */
    fun checksum(body: String): Int {
        var c = 0
        for (ch in body) c = c xor ch.code
        return c and 0xFF
    }

    /** Build a full sentence with a valid `*checksum` suffix (used by tests/demo). */
    fun withChecksum(body: String): String = "\$%s*%02X".format(body, checksum(body))

    /**
     * Parse one NMEA line. Returns null on empty/unknown/failed-checksum input.
     * A `*checksum` is verified when present; sentences without one are accepted
     * (some sources omit it), but a present-and-wrong checksum is always rejected.
     */
    fun parse(line: String): NmeaSentence? {
        val trimmed = line.trim()
        if (trimmed.length < 6 || trimmed[0] != '$') return null

        val star = trimmed.indexOf('*')
        val body = if (star >= 0) trimmed.substring(1, star) else trimmed.substring(1)
        if (star >= 0) {
            val given = trimmed.substring(star + 1).trim().toIntOrNull(16) ?: return null
            if (given != checksum(body)) return null
        }

        val f = body.split(',')
        return when (f[0].takeLast(3)) {
            "GGA" -> parseGga(f)
            "GST" -> parseGst(f)
            "RMC" -> parseRmc(f)
            else -> null
        }
    }

    private fun field(f: List<String>, i: Int): String? = f.getOrNull(i)?.takeIf { it.isNotEmpty() }

    /** ddmm.mmmm / dddmm.mmmm -> signed decimal degrees. */
    private fun coord(value: String?, hemi: String?): Double? {
        if (value == null) return null
        val dot = value.indexOf('.')
        if (dot < 3) return null
        val degLen = dot - 2
        val deg = value.substring(0, degLen).toDoubleOrNull() ?: return null
        val min = value.substring(degLen).toDoubleOrNull() ?: return null
        var dd = deg + min / 60.0
        if (hemi == "S" || hemi == "W") dd = -dd
        return dd
    }

    private fun parseGga(f: List<String>): GgaData? {
        val lat = coord(field(f, 2), field(f, 3)) ?: return null
        val lon = coord(field(f, 4), field(f, 5)) ?: return null
        val quality = when (field(f, 6)?.toIntOrNull()) {
            1 -> FixQuality.AUTONOMOUS
            2 -> FixQuality.DGPS
            4 -> FixQuality.FIXED
            5 -> FixQuality.FLOAT
            else -> FixQuality.NONE
        }
        return GgaData(
            latitudeDeg = lat,
            longitudeDeg = lon,
            quality = quality,
            satellites = field(f, 7)?.toIntOrNull() ?: 0,
            hdop = field(f, 8)?.toDoubleOrNull() ?: Double.NaN,
            altitudeMslM = field(f, 9)?.toDoubleOrNull() ?: Double.NaN,
            geoidSepM = field(f, 11)?.toDoubleOrNull() ?: 0.0
        )
    }

    private fun parseGst(f: List<String>): GstData? {
        val latSd = field(f, 6)?.toDoubleOrNull() ?: return null
        val lonSd = field(f, 7)?.toDoubleOrNull() ?: return null
        val altSd = field(f, 8)?.toDoubleOrNull() ?: Double.NaN
        return GstData(latSd, lonSd, altSd)
    }

    private fun parseRmc(f: List<String>): RmcData {
        val valid = field(f, 2) == "A"
        val knots = field(f, 7)?.toDoubleOrNull() ?: Double.NaN
        val course = field(f, 8)?.toDoubleOrNull() ?: Double.NaN
        return RmcData(valid, if (knots.isNaN()) Double.NaN else knots * 0.514444, course)
    }
}
