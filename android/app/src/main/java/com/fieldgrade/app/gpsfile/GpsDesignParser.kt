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
        // Read up to 32 header bytes without InputStream.readNBytes (API 33+); minSdk is 26.
        val header = ByteArray(32)
        var read = 0
        while (read < header.size) {
            val n = input.read(header, read, header.size - read)
            if (n < 0) break
            read += n
        }
        val signature = header.copyOf(read).joinToString("") { "%02x".format(it) }
        throw IllegalArgumentException("Unsupported .gps design format; first $read bytes: $signature")
    }
}
