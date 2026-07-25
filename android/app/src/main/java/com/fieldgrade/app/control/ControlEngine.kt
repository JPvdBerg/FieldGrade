package com.fieldgrade.app.control

import kotlin.math.abs

/**
 * Tablet-side control logic. Output is a PHYSICAL vertical correction in millimetres
 * that goes straight into the command frame's `target_mm`.
 *
 * The proportional gain that turns millimetres into hydraulic duty lives ENTIRELY on
 * the controller (see PROJECT_PLAN.md section 5.3). This guarantees a tablet-side bug
 * can never amplify hydraulic authority.
 */
data class ControlTuning(
    val deadbandMm: Int = 8,
    val maxCorrectionMm: Int = 60,   // clamp the correction the tablet is willing to request
    val nudgeStepMm: Int = 1
)

class ControlEngine(private val tuning: ControlTuning = ControlTuning()) {

    /**
     * Convert a signed cut/fill error (mm) plus any operator nudge (mm) into the
     * signed vertical correction to request, in millimetres. Inside the deadband the
     * base correction is zero, but an explicit nudge is still honoured.
     */
    fun autoTargetMm(cutFillMm: Int, nudgeMm: Int = 0): Int {
        val base = if (abs(cutFillMm) <= tuning.deadbandMm) 0 else cutFillMm
        return (base + nudgeMm).coerceIn(-tuning.maxCorrectionMm, tuning.maxCorrectionMm)
    }
}
