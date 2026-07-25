package com.fieldgrade.app.gpsfile

import java.io.InputStream

data class DesignPoint(val eastM: Double, val northM: Double, val elevationM: Double)
data class DesignSurface(val name: String, val points: List<DesignPoint>, val metadata: Map<String, String>)

interface GpsDesignParser {
    /** Parse the real vendor design file. Never silently guess a format. */
    fun parse(input: InputStream): DesignSurface
}

class UnsupportedGpsDesignParser : GpsDesignParser {
    override fun parse(input: InputStream): DesignSurface {
        val header = input.readNBytes(32)
        val signature = header.joinToString("") { "%02x".format(it) }
        throw IllegalArgumentException("Unsupported .gps design format; first 32 bytes: $signature")
    }
}
