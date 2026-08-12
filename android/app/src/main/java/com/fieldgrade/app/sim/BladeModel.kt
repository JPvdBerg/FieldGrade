package com.fieldgrade.app.sim

import kotlin.math.abs
import kotlin.math.sign

/**
 * A crude model of what the hydraulics do when the controller drives an output.
 *
 * One job: integrate a signed PWM duty into a blade height offset over time.
 * It knows nothing about GNSS, protocol frames or the design surface.
 *
 * Modelled, because each of these changes loop behaviour visibly:
 *  - **Valve deadband.** A proportional spool does nothing at all until it
 *    cracks open. Below [valveDeadbandDuty] the blade does not move, which is
 *    why a control deadband that is *narrower* than the valve's own is pointless.
 *  - **Finite slew rate.** The cylinder has a maximum speed, so the blade cannot
 *    instantly follow a step change in design elevation.
 *  - **Cylinder travel limits.** The blade physically cannot go past its stroke.
 *
 * NOT modelled — and these are the reasons this model must never be mistaken for
 * commissioning evidence: soil resistance changing cylinder speed under load,
 * oil temperature and viscosity, hose compliance, machine pitch dynamics as the
 * scraper fills, valve hysteresis, and the fact that a real blade *removes* the
 * material it cuts. Real values for rate and deadband come from Phase 5 on a rig.
 */
class BladeModel(
    private val fullDutyRateMmPerS: Double = 90.0,
    private val maxDuty: Int = 820,
    private val valveDeadbandDuty: Int = 45,
    private val travelLimitMm: Double = 900.0,
    initialOffsetMm: Double = 0.0
) {
    /**
     * Blade offset from its neutral (mast-derived) position, in millimetres.
     * Positive is raised. The harness adds this to the geometric tool elevation.
     */
    var offsetMm: Double = initialOffsetMm
        private set

    /** True while the cylinder is against a stop — the loop cannot correct further. */
    var atTravelLimit: Boolean = false
        private set

    /** Current commanded blade speed in mm/s, for tracing. */
    var rateMmPerS: Double = 0.0
        private set

    /**
     * Advance the blade by [dtMs] under a signed controller [duty].
     * Positive duty raises the blade.
     */
    fun update(duty: Int, dtMs: Long) {
        if (dtMs <= 0) return

        val magnitude = abs(duty)
        rateMmPerS = if (magnitude <= valveDeadbandDuty) {
            0.0
        } else {
            // Linear from the crack point to full duty, so the deadband does not
            // simply shift the curve — it compresses the usable range.
            val usable = (magnitude - valveDeadbandDuty).toDouble() /
                (maxDuty - valveDeadbandDuty).toDouble()
            sign(duty.toDouble()) * usable.coerceIn(0.0, 1.0) * fullDutyRateMmPerS
        }

        val next = offsetMm + rateMmPerS * (dtMs / 1000.0)
        offsetMm = next.coerceIn(-travelLimitMm, travelLimitMm)
        atTravelLimit = abs(offsetMm) >= travelLimitMm - 1e-9
    }

    /** Vertical offset in metres, the unit the geometry chain works in. */
    val offsetM: Double get() = offsetMm / 1000.0
}
