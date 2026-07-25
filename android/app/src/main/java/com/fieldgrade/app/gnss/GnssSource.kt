package com.fieldgrade.app.gnss

/**
 * Source of fused GNSS solutions. The levelling maths never depends on which
 * receiver is connected (PROJECT_PLAN section 6). Real sources (Bluetooth/USB
 * NMEA from the Emlid) feed [NmeaGnssDecoder]; [DemoGnssSource] drives the UI
 * with no hardware.
 */
interface GnssSource {
    /** Latest fused solution, or null if none yet. */
    fun latest(): GnssSample?
}

/**
 * Fuses a stream of NMEA sentences into [GnssSample]s. GGA carries position/fix
 * and triggers a new sample; the most recent GST (accuracy) and RMC (speed/heading)
 * are merged in. Sample age is derived from the injected clock.
 */
class NmeaGnssDecoder {
    private var lastGst: GstData? = null
    private var lastRmc: RmcData? = null
    private var lastPositionMs: Long = -1

    /** Offer one NMEA line; returns a fresh sample when a GGA position arrives. */
    fun offer(line: String, nowMs: Long): GnssSample? {
        when (val s = NmeaParser.parse(line)) {
            is GstData -> { lastGst = s; return null }
            is RmcData -> { lastRmc = s; return null }
            is GgaData -> {
                lastPositionMs = nowMs
                return GnssSample(
                    latitudeDeg = s.latitudeDeg,
                    longitudeDeg = s.longitudeDeg,
                    ellipsoidHeightM = s.ellipsoidHeightM,
                    quality = s.quality,
                    horizontalAccuracyM = lastGst?.horizontalAccuracyM
                        ?: hdopFallback(s.hdop),
                    ageMs = 0,
                    verticalAccuracyM = lastGst?.altStdDevM ?: Double.NaN,
                    satellites = s.satellites,
                    speedMps = lastRmc?.speedMps ?: Double.NaN,
                    headingDeg = lastRmc?.headingDeg ?: Double.NaN
                )
            }
            else -> return null
        }
    }

    /** Crude accuracy estimate when no GST is available (~HDOP * nominal UERE). */
    private fun hdopFallback(hdop: Double): Double =
        if (hdop.isNaN()) Double.NaN else hdop * 2.5
}

/** Deterministic demo source: a slow straight track at RTK-FIXED, for the UI. */
class DemoGnssSource(
    private val originLat: Double = -26.20000,
    private val originLon: Double = 28.04000,
    private val baseHeightM: Double = 1542.400
) : GnssSource {
    private var step = 0
    private var sample: GnssSample? = null

    /** Advance the simulated machine one tick and return the new sample. */
    fun tick(): GnssSample {
        step++
        // creep north ~0.05 m/tick and gently oscillate height by a few cm
        val north = step * 0.05
        val dLat = north / 111_132.0
        val height = baseHeightM + 0.03 * kotlin.math.sin(step / 8.0)
        val s = GnssSample(
            latitudeDeg = originLat + dLat,
            longitudeDeg = originLon,
            ellipsoidHeightM = height,
            quality = FixQuality.FIXED,
            horizontalAccuracyM = 0.014,
            ageMs = 0,
            verticalAccuracyM = 0.020,
            satellites = 27,
            speedMps = 1.6,
            headingDeg = 0.0
        )
        sample = s
        return s
    }

    override fun latest(): GnssSample? = sample
}
