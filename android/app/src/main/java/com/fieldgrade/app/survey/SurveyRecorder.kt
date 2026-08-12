package com.fieldgrade.app.survey

import com.fieldgrade.app.design.DesignPoint
import com.fieldgrade.app.geom.ToolPoint
import com.fieldgrade.app.gnss.FixQuality
import com.fieldgrade.app.gnss.GnssSample
import kotlin.math.hypot

/**
 * Records a survey pass: the machine drives the field and logs where the ground is.
 *
 * One job: decide whether an incoming fix is worth keeping, and hold the points
 * that were. It writes no files — [toXyzText] hands back the text and the caller
 * decides where it goes.
 *
 * This is step 1 of the real workflow (survey -> design -> grade), and it closes
 * the loop: the output is the same XYZ text
 * [com.fieldgrade.app.design.XyzPointReader] reads, and the format a design house
 * wants for OptiSurface / AgForm3D / Ezigrade. So the machine can survey its own
 * field, and the design comes back as a surface this same app then grades to.
 *
 * The point recorded is the **tool** point, not the antenna: the blade riding on
 * the ground is what the ground elevation actually is. Feeding antenna positions
 * to a designer produces a surface one mast-height too high.
 *
 * Rejection is the interesting behaviour, not acceptance:
 *  - anything below [minQuality] is dropped — a metre-accurate fix in a survey
 *    destined for a 25 mm-tolerance design is poison, not data;
 *  - fixes with accuracy worse than [maxHorizontalAccuracyM] are dropped;
 *  - points closer together than [minSpacingM] are dropped, so a machine parked
 *    with the engine running does not bury the real surface under thousands of
 *    duplicate points at one spot.
 */
class SurveyRecorder(
    private val minQuality: FixQuality = FixQuality.FIXED,
    private val maxHorizontalAccuracyM: Double = 0.05,
    private val minSpacingM: Double = 1.0,
    private val code: String = "SURVEY"
) {
    private val kept = ArrayList<DesignPoint>()
    private var lastE = Double.NaN
    private var lastN = Double.NaN

    var rejectedQuality: Int = 0; private set
    var rejectedAccuracy: Int = 0; private set
    var rejectedSpacing: Int = 0; private set

    val points: List<DesignPoint> get() = kept
    val count: Int get() = kept.size

    /** Reason the last offer was rejected, or null if it was kept. */
    enum class Rejection { QUALITY, ACCURACY, SPACING }

    /**
     * Offer one fix plus the tool point derived from it.
     * @return null if recorded, otherwise why it was not.
     */
    fun offer(sample: GnssSample, tool: ToolPoint): Rejection? {
        if (sample.quality.ordinal < minQuality.ordinal) {
            rejectedQuality++
            return Rejection.QUALITY
        }
        if (sample.horizontalAccuracyM.isNaN() ||
            sample.horizontalAccuracyM > maxHorizontalAccuracyM
        ) {
            rejectedAccuracy++
            return Rejection.ACCURACY
        }
        if (!lastE.isNaN() && hypot(tool.eastM - lastE, tool.northM - lastN) < minSpacingM) {
            rejectedSpacing++
            return Rejection.SPACING
        }
        kept.add(DesignPoint(tool.eastM, tool.northM, tool.elevationM))
        lastE = tool.eastM
        lastN = tool.northM
        return null
    }

    /** Render as `Point,Easting,Northing,Elevation,Code` — the XYZ interchange. */
    fun toXyzText(): String = buildString {
        append("Point,Easting,Northing,Elevation,Code\n")
        kept.forEachIndexed { i, p ->
            append(i + 1).append(',')
                .append(fixed(p.eastM)).append(',')
                .append(fixed(p.northM)).append(',')
                .append(fixed(p.elevationM)).append(',')
                .append(code).append('\n')
        }
    }

    fun describe(): String =
        "$count points kept; rejected: quality=$rejectedQuality " +
            "accuracy=$rejectedAccuracy spacing=$rejectedSpacing"

    /** 4 dp, locale-independent — a comma decimal separator would corrupt the CSV. */
    private fun fixed(v: Double): String = String.format(java.util.Locale.ROOT, "%.4f", v)
}
