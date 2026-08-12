package com.fieldgrade.app.ui.map

import com.fieldgrade.app.surface.DesignSurfaceModel
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Samples the ground along the line the machine is driving.
 *
 * One job: a pose and a heading in, a list of elevations along that ray out. It
 * draws nothing, scales nothing, and does not know what a screen is.
 *
 * This is what turns the map from a plan view into something you can *anticipate*
 * with. The map answers "where is the work"; the profile answers "what is about to
 * happen under the blade", which is the question an operator is actually asking
 * while driving. It is the view every grade-control product calls a long section.
 *
 * It samples **both** surfaces, because the useful picture is the pair: the design
 * line you are steering to, and the existing ground you are about to hit. With
 * only the design you can see the target but not the work.
 *
 * Samples behind the machine are included too — a short tail showing the ground
 * just left behind is how an operator confirms the last few metres came out right.
 */
object ProfileSampler {

    /**
     * One point along the ray. Either elevation may be null where its surface does
     * not cover the point; the renderer must break its line there rather than
     * bridging a gap it has no data for.
     */
    data class Sample(
        val distanceM: Double,
        val designM: Double?,
        val existingM: Double?
    ) {
        /** Design minus existing, in millimetres, or null if either is missing. */
        val cutFillMm: Int?
            get() = if (designM == null || existingM == null) null
            else ((designM - existingM) * 1000.0).roundToInt()
    }

    data class Profile(
        val samples: List<Sample>,
        val headingDeg: Double,
        val aheadM: Double,
        val behindM: Double
    ) {
        val isEmpty: Boolean get() = samples.none { it.designM != null || it.existingM != null }

        /** Elevation range across everything present, or null when there is nothing. */
        fun elevationRange(): ClosedFloatingPointRange<Double>? {
            var lo = Double.MAX_VALUE
            var hi = -Double.MAX_VALUE
            for (s in samples) {
                s.designM?.let { if (it < lo) lo = it; if (it > hi) hi = it }
                s.existingM?.let { if (it < lo) lo = it; if (it > hi) hi = it }
            }
            return if (lo > hi) null else lo..hi
        }
    }

    /**
     * Sample along the machine's course.
     *
     * @param headingDeg course clockwise from north. Non-finite yields an empty
     *        profile rather than a line pointing in an arbitrary direction —
     *        a profile down the wrong bearing is worse than no profile.
     * @param aheadM how far in front to look.
     * @param behindM how far behind to include.
     * @param stepM sample spacing.
     */
    fun alongHeading(
        design: DesignSurfaceModel?,
        existing: DesignSurfaceModel?,
        eastM: Double,
        northM: Double,
        headingDeg: Double,
        aheadM: Double = 40.0,
        behindM: Double = 10.0,
        stepM: Double = 1.0
    ): Profile {
        if (!headingDeg.isFinite() || !eastM.isFinite() || !northM.isFinite() || stepM <= 0.0) {
            return Profile(emptyList(), headingDeg, aheadM, behindM)
        }

        val rad = Math.toRadians(headingDeg)
        val de = sin(rad)      // unit step east per metre travelled
        val dn = cos(rad)      // unit step north

        val samples = ArrayList<Sample>(((aheadM + behindM) / stepM).toInt() + 2)
        var d = -behindM
        while (d <= aheadM + 1e-9) {
            val e = eastM + de * d
            val n = northM + dn * d
            samples.add(Sample(d, design?.elevationAt(e, n), existing?.elevationAt(e, n)))
            d += stepM
        }
        return Profile(samples, headingDeg, aheadM, behindM)
    }
}
