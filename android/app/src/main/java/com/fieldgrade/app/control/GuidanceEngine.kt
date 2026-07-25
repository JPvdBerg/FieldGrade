package com.fieldgrade.app.control

import com.fieldgrade.app.geom.Attitude
import com.fieldgrade.app.geom.CoordinateTransform
import com.fieldgrade.app.geom.LeverArm
import com.fieldgrade.app.geom.MachineGeometry
import com.fieldgrade.app.gnss.FixQuality
import com.fieldgrade.app.gnss.GnssSample
import com.fieldgrade.app.surface.DesignSurfaceModel
import kotlin.math.roundToInt

enum class GuidanceDirection { RAISE, LOWER, ON_GRADE }

/**
 * The operator-facing guidance solution.
 *
 * Sign convention (fixed here, design doc section 5): cut/fill = design elevation
 * minus tool elevation. Positive => tool is BELOW the target => RAISE (fill).
 * Negative => tool is ABOVE the target => LOWER (cut). `cutFillMm` is the exact
 * value shown to the operator and, when AUTO is allowed, the physical millimetre
 * correction sent to the controller.
 */
data class GuidanceState(
    val hasSolution: Boolean,
    val quality: FixQuality,
    val toolElevationM: Double,
    val designElevationM: Double,
    val cutFillMm: Int,
    val direction: GuidanceDirection,
    val canAuto: Boolean,
    val autoInhibitReason: String?
) {
    companion object {
        val NONE = GuidanceState(
            hasSolution = false, quality = FixQuality.NONE,
            toolElevationM = Double.NaN, designElevationM = Double.NaN,
            cutFillMm = 0, direction = GuidanceDirection.ON_GRADE,
            canAuto = false, autoInhibitReason = "no GNSS solution"
        )
    }
}

data class GuidanceConfig(
    val minQuality: FixQuality = FixQuality.FIXED,
    val maxAgeMs: Long = 1000,
    val maxHorizontalAccuracyM: Double = 0.05,
    val deadbandMm: Int = 8
)

class GuidanceEngine(private val cfg: GuidanceConfig = GuidanceConfig()) {

    fun compute(
        sample: GnssSample,
        transform: CoordinateTransform,
        arm: LeverArm,
        attitude: Attitude,
        surface: DesignSurfaceModel,
        benchmarkOffsetM: Double = 0.0,
        nudgeMm: Int = 0
    ): GuidanceState {
        val antenna = transform.toLocal(sample.latitudeDeg, sample.longitudeDeg)
        val tool = MachineGeometry.toolPoint(antenna, sample.ellipsoidHeightM, arm, attitude)
        val effectiveTool = tool.elevationM + benchmarkOffsetM

        val design = surface.elevationAt(tool.eastM, tool.northM)
            ?: return GuidanceState(
                hasSolution = false, quality = sample.quality,
                toolElevationM = effectiveTool, designElevationM = Double.NaN,
                cutFillMm = 0, direction = GuidanceDirection.ON_GRADE,
                canAuto = false, autoInhibitReason = "outside design boundary"
            )

        val targetElev = design + nudgeMm / 1000.0
        val cutFillMm = ((targetElev - effectiveTool) * 1000.0).roundToInt()
        val direction = when {
            cutFillMm > cfg.deadbandMm -> GuidanceDirection.RAISE
            cutFillMm < -cfg.deadbandMm -> GuidanceDirection.LOWER
            else -> GuidanceDirection.ON_GRADE
        }

        val reason = autoInhibitReason(sample)
        return GuidanceState(
            hasSolution = true,
            quality = sample.quality,
            toolElevationM = effectiveTool,
            designElevationM = design,
            cutFillMm = cutFillMm,
            direction = direction,
            canAuto = reason == null,
            autoInhibitReason = reason
        )
    }

    /** Null when AUTO is permitted; otherwise the reason it is inhibited. */
    private fun autoInhibitReason(s: GnssSample): String? = when {
        s.quality.ordinal < cfg.minQuality.ordinal ->
            "GNSS ${s.quality} < required ${cfg.minQuality}"
        s.ageMs > cfg.maxAgeMs ->
            "correction stale (${s.ageMs} ms)"
        s.horizontalAccuracyM.isNaN() || s.horizontalAccuracyM > cfg.maxHorizontalAccuracyM ->
            "accuracy ${fmt(s.horizontalAccuracyM)} m > ${cfg.maxHorizontalAccuracyM} m"
        else -> null
    }

    private fun fmt(v: Double) = if (v.isNaN()) "?" else ((v * 1000).roundToInt() / 1000.0).toString()
}
