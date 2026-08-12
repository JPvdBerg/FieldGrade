package com.fieldgrade.app.ui.map

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Turns a noisy course-over-ground into a heading a map can be rotated by.
 *
 * One job: raw heading + speed in, stable display heading out. It draws nothing
 * and owns no clock beyond the timestamps it is handed.
 *
 * This module exists because of what NMEA actually gives us. RMC reports **course
 * over ground** — the direction the antenna is *moving*, derived by differencing
 * positions. It is not a compass, and it has two properties that make it unusable
 * raw:
 *
 *  1. **At low speed it is noise.** Course is position difference divided by time;
 *     as speed approaches zero the signal vanishes and the reported course swings
 *     through the full circle on centimetre-scale RTK jitter. Stationary, it is
 *     undefined and the receiver reports nothing at all.
 *  2. **A heading-up map amplifies it.** A north-up map with a jittery arrow is
 *     merely untidy. A heading-up map rotates the *whole field* by that value, so
 *     the same jitter spins the entire display. An operator slowing for a headland
 *     would watch the paddock whirl around them.
 *
 * So: below [minSpeedMps] the heading is **frozen** at its last trustworthy value
 * and [isHolding] goes true, which the UI must surface — a map that has silently
 * stopped tracking is worse than one that admits it. Above it, the heading is
 * smoothed.
 *
 * Smoothing is done on the unit vector, not the angle. Averaging degrees directly
 * takes 350 and 10 to 180 — pointing the map exactly backwards as the machine
 * crosses north. Vector smoothing wraps correctly by construction.
 */
class HeadingFilter(
    /** Below this ground speed, course over ground is not trustworthy. */
    private val minSpeedMps: Double = 0.4,
    /** EMA weight for each new sample; smaller is smoother and laggier. */
    private val smoothing: Double = 0.18,
    /** A turn larger than this is taken as real and applied at once. */
    private val snapThresholdDeg: Double = 60.0
) {
    private var x = Double.NaN      // smoothed unit vector, east component
    private var y = Double.NaN      // smoothed unit vector, north component

    /** Filtered heading in degrees clockwise from north, or NaN before the first fix. */
    var headingDeg: Double = Double.NaN
        private set

    /** True when the heading is frozen because the machine is too slow to trust. */
    var isHolding: Boolean = true
        private set

    /** True once a usable heading has ever been established. */
    val hasHeading: Boolean get() = headingDeg.isFinite()

    /**
     * Offer one fix.
     * @param rawHeadingDeg course over ground; NaN when the receiver reports none.
     * @param speedMps ground speed.
     */
    fun update(rawHeadingDeg: Double, speedMps: Double) {
        if (!rawHeadingDeg.isFinite() || !speedMps.isFinite() || speedMps < minSpeedMps) {
            // Hold the last good value rather than drifting toward noise.
            isHolding = true
            return
        }
        isHolding = false

        val rad = Math.toRadians(rawHeadingDeg)
        val nx = sin(rad)   // east
        val ny = cos(rad)   // north

        if (!x.isFinite() || !y.isFinite()) {
            x = nx; y = ny
        } else {
            // A genuine turn should not crawl in behind a smoothing lag; a headland
            // turn is real information the operator is already acting on.
            val alpha = if (angularDistance(headingDeg, rawHeadingDeg) > snapThresholdDeg) {
                1.0
            } else {
                smoothing
            }
            x += (nx - x) * alpha
            y += (ny - y) * alpha
        }

        val magnitude = Math.hypot(x, y)
        if (magnitude < 1e-9) return          // vector collapsed; keep the old heading
        x /= magnitude
        y /= magnitude
        headingDeg = (Math.toDegrees(atan2(x, y)) + 360.0) % 360.0
    }

    /** Forget everything — used when the data source changes. */
    fun reset() {
        x = Double.NaN
        y = Double.NaN
        headingDeg = Double.NaN
        isHolding = true
    }

    companion object {
        /** Smallest angle between two bearings, in degrees, always 0..180. */
        fun angularDistance(a: Double, b: Double): Double {
            if (!a.isFinite() || !b.isFinite()) return Double.NaN
            val d = Math.abs((a - b) % 360.0)
            return if (d > 180.0) 360.0 - d else d
        }
    }
}
