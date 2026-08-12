package com.fieldgrade.app.ui.map

import kotlin.math.abs
import kotlin.math.hypot

/**
 * The worked path: where the machine has been, and how far off grade it was there.
 *
 * One job: accumulate and decimate pose history. It does not draw, does not
 * project, and does not know what a design surface is.
 *
 * This is the part of the display that makes a levelling job legible — it is
 * the record of work done. An operator reads it to see which strips still need
 * another pass, so the value stored per mark is the cut/fill *at the moment of
 * passing*, not the current one.
 *
 * Decimation is not cosmetic. At 5 Hz a full shift is a quarter of a million
 * fixes; drawing that every frame on a tablet would drop the UI below the
 * refresh rate the operator needs to steer by. Marks closer together than
 * [minSpacingM] are merged into the previous one, and the buffer is capped at
 * [maxMarks], dropping oldest first.
 */
class FieldTrack(
    private val minSpacingM: Double = 1.5,
    private val maxMarks: Int = 6000,
    private val onGradeToleranceMm: Int = 25
) {
    /**
     * One recorded position.
     * @param onGrade whether it was within tolerance — carried explicitly so the
     *        renderer never has to re-derive it, and so the distinction survives
     *        for anything that reads the track without knowing the tolerance.
     */
    data class Mark(
        val eastM: Double,
        val northM: Double,
        val cutFillMm: Int,
        val onGrade: Boolean
    )

    private val marks = ArrayDeque<Mark>()
    private var lastE = Double.NaN
    private var lastN = Double.NaN

    val size: Int get() = marks.size
    val isEmpty: Boolean get() = marks.isEmpty()

    /** Snapshot for rendering. */
    fun snapshot(): List<Mark> = marks.toList()

    /** Most recent mark, or null. */
    fun latest(): Mark? = marks.lastOrNull()

    /**
     * Offer a position. Ignored when it is too close to the previous mark.
     * @return true if a mark was recorded.
     */
    fun offer(eastM: Double, northM: Double, cutFillMm: Int): Boolean {
        if (!eastM.isFinite() || !northM.isFinite()) return false
        if (!lastE.isNaN() && hypot(eastM - lastE, northM - lastN) < minSpacingM) return false

        marks.addLast(
            Mark(eastM, northM, cutFillMm, abs(cutFillMm) <= onGradeToleranceMm)
        )
        while (marks.size > maxMarks) marks.removeFirst()
        lastE = eastM
        lastN = northM
        return true
    }

    fun clear() {
        marks.clear()
        lastE = Double.NaN
        lastN = Double.NaN
    }

    /** Fraction of recorded marks that were on grade, 0.0 when empty. */
    fun onGradeFraction(): Double =
        if (marks.isEmpty()) 0.0 else marks.count { it.onGrade }.toDouble() / marks.size
}
