package com.fieldgrade.app.ui.map

import androidx.compose.ui.graphics.Color
import com.fieldgrade.app.design.CutFillField
import kotlin.math.abs

/**
 * Colour for a cut/fill value.
 *
 * One job: millimetres -> a colour and a name. It draws nothing and decides no
 * layout, so both the map and the profile view speak the same visual language.
 *
 * This is a **diverging** scale — the data has a polarity (which side of grade)
 * and a magnitude (how far). That fixes the construction:
 *
 *  - two hues, one per pole: **red for cut, blue for fill**, warm against cool so
 *    they read as opposites even when the field is glanced at rather than studied;
 *  - a **neutral grey midpoint**, never a third hue. Ground that is already on
 *    grade is *absence of work*, and it should sink into the background so the
 *    operator's eye goes to what is left. A saturated green midpoint — the
 *    obvious choice, and what the first version of this screen used — makes
 *    finished ground the loudest thing on the map, which is exactly backwards;
 *  - **equal step count per arm**, so a 150 mm cut and a 150 mm fill are equally
 *    prominent and neither direction looks more urgent than it is.
 *
 * The steps were derived and machine-checked rather than picked by eye: lightness
 * increases monotonically outward from the midpoint on both arms (0.34 -> 0.72 in
 * OKLCH L), both poles clear the chroma floor (C >= 0.10) so neither reads as
 * grey, and the two arms are >= 21 apart in OKLab dE at equal magnitude, so a cut
 * can never be mistaken for a fill. The strong poles reach 6.7:1 and 7.1:1 against
 * the map surface, which is what keeps them legible on a cab tablet in sunlight;
 * the midpoint deliberately sits at 1.52:1 and recedes.
 *
 * Bands are discrete, not a smooth ramp. A continuous gradient washes out to grey
 * mush in direct sun, and it would imply a precision between grid cells that a 2 m
 * raster does not have. [labelFor] exists because colour is never the only channel.
 */
object CutFillPalette {

    /** Within this, the ground counts as finished. */
    const val ON_GRADE_MM = 25

    /** Band upper bounds in millimetres; the last band is everything beyond. */
    private val BAND_LIMITS = intArrayOf(50, 100, 200)

    private val NEUTRAL = Color(0xFF383835)

    /** Design above ground: material must be added. */
    private val FILL = arrayOf(
        Color(0xFF1C5CAB), Color(0xFF2A78D6), Color(0xFF3987E5), Color(0xFF6DA7EC)
    )

    /** Design below ground: material must be removed. */
    private val CUT = arrayOf(
        Color(0xFF9E3337), Color(0xFFC7474B), Color(0xFFD75758), Color(0xFFE48482)
    )

    /** Nothing is known here — no design, or no survey. */
    val NO_DATA: Color = Color(0x00000000)

    fun colourFor(cutFillMm: Int): Color {
        if (cutFillMm == CutFillField.NO_DATA) return NO_DATA
        val magnitude = abs(cutFillMm)
        if (magnitude <= ON_GRADE_MM) return NEUTRAL
        val arm = if (cutFillMm > 0) FILL else CUT
        return arm[bandOf(magnitude)]
    }

    /** 0-based band index for a magnitude already known to be off grade. */
    private fun bandOf(magnitudeMm: Int): Int {
        for (i in BAND_LIMITS.indices) if (magnitudeMm <= BAND_LIMITS[i]) return i
        return BAND_LIMITS.size
    }

    /** The word for a value, so meaning never rides on hue alone. */
    fun labelFor(cutFillMm: Int): String = when {
        cutFillMm == CutFillField.NO_DATA -> "no data"
        abs(cutFillMm) <= ON_GRADE_MM -> "on grade"
        cutFillMm > 0 -> "fill"
        else -> "cut"
    }

    /**
     * The full ramp left-to-right for a legend strip: deepest cut, through the
     * neutral midpoint, to deepest fill. Drawn as one continuous bar because that
     * is what shows a reader it is a *scale* rather than a set of categories.
     */
    fun rampStrip(): List<Color> =
        CUT.reversed() + listOf(NEUTRAL) + FILL.toList()

    /** Index of the neutral midpoint within [rampStrip]. */
    val midpointIndex: Int get() = CUT.size

    /** Magnitude at which the outermost band starts, for labelling the ramp ends. */
    val strongestBandMm: Int get() = BAND_LIMITS.last()

    data class Entry(val colour: Color, val title: String, val range: String)

    /** Row-per-band legend, for anywhere a strip does not fit. */
    fun legend(): List<Entry> {
        val rows = ArrayList<Entry>(9)
        for (i in CUT.indices.reversed()) rows.add(Entry(CUT[i], "CUT", rangeLabel(i)))
        rows.add(Entry(NEUTRAL, "ON GRADE", "within $ON_GRADE_MM mm"))
        for (i in FILL.indices) rows.add(Entry(FILL[i], "FILL", rangeLabel(i)))
        return rows
    }

    private fun rangeLabel(band: Int): String = when (band) {
        0 -> "$ON_GRADE_MM-${BAND_LIMITS[0]} mm"
        BAND_LIMITS.size -> "over ${BAND_LIMITS.last()} mm"
        else -> "${BAND_LIMITS[band - 1]}-${BAND_LIMITS[band]} mm"
    }
}
