package com.fieldgrade.app.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The long section: a side-on slice of the ground along the line the machine is
 * driving, design against existing.
 *
 * One job: draw a profile it is handed. It samples nothing and decides no
 * geometry — [ProfileSampler] does that.
 *
 * This is the anticipation view. The map says where the work is; this says what is
 * about to arrive under the blade, far enough ahead that the operator can act
 * before they are in it.
 *
 * **Vertical exaggeration is applied and stated.** A 4 ha levelling field has
 * around 0.6 m of relief over 200 m: drawn true to scale it is a dead flat line
 * and tells you nothing. Every grade-control profile view exaggerates the vertical
 * for this reason. Doing it silently would imply slopes that do not exist, so the
 * factor is computed and printed on the chart — the exaggeration is a reading aid,
 * never a claim about the ground.
 */
@Composable
fun ProfileView(
    profile: ProfileSampler.Profile,
    modifier: Modifier = Modifier,
    bladeElevationM: Double = Double.NaN,
    onGradeToleranceMm: Int = CutFillPalette.ON_GRADE_MM
) {
    val measurer = rememberTextMeasurer()

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(color = PANEL)

            val range = profile.elevationRange()
            if (profile.isEmpty || range == null) {
                drawCentred(
                    measurer,
                    if (profile.headingDeg.isFinite()) "no design ahead"
                    else "no heading — profile needs a course to point down",
                    Offset(size.width / 2f, size.height / 2f)
                )
                return@Canvas
            }

            val left = 46f
            val right = size.width - 54f      // room for the direct labels
            val top = 26f
            val bottom = size.height - 22f
            if (right <= left || bottom <= top) return@Canvas

            // --- vertical scale, padded and floored ---
            // A floor stops a perfectly flat section from being magnified into
            // meaningless noise by an auto-range with nothing to range over.
            val midZ = (range.start + range.endInclusive) / 2.0
            val spanZ = max(range.endInclusive - range.start, MIN_SPAN_M) * 1.25
            val loZ = midZ - spanZ / 2
            val hiZ = midZ + spanZ / 2

            val totalM = profile.aheadM + profile.behindM
            val pxPerM = (right - left) / totalM.toFloat()
            val pxPerZ = (bottom - top) / spanZ.toFloat()
            val exaggeration = (pxPerZ / pxPerM).roundToInt()

            fun x(distanceM: Double) = left + ((distanceM + profile.behindM) * pxPerM).toFloat()
            fun y(elevM: Double) = bottom - ((elevM - loZ) * pxPerZ).toFloat()

            drawGrid(measurer, left, right, top, bottom, loZ, hiZ, ::y)
            drawCutFillBand(profile, ::x, ::y, bottom)
            drawSurfaceLine(profile, ::x, ::y, GROUND_LINE) { it.existingM }
            drawSurfaceLine(profile, ::x, ::y, DESIGN_LINE) { it.designM }
            drawDirectLabels(measurer, profile, right, ::y)
            drawMachineMarker(measurer, profile, bladeElevationM, ::x, ::y, top, bottom)
            drawDistanceAxis(measurer, profile, bottom, ::x)
            drawCaption(measurer, exaggeration, onGradeToleranceMm, left, top)
        }
    }
}

// ---------------------------------------------------------------- layers

/** Recessive horizontal rules at round elevations, so the eye reads the data. */
private fun DrawScope.drawGrid(
    measurer: TextMeasurer,
    left: Float, right: Float, top: Float, bottom: Float,
    loZ: Double, hiZ: Double,
    y: (Double) -> Float
) {
    val step = niceStep(hiZ - loZ)
    var z = Math.ceil(loZ / step) * step
    while (z <= hiZ) {
        val py = y(z)
        if (py in top..bottom) {
            drawLine(GRID, Offset(left, py), Offset(right, py), strokeWidth = 1f)
            drawText(
                measurer, "%.2f".format(z),
                topLeft = Offset(4f, py - 7f),
                style = TextStyle(color = MUTED, fontSize = 9.sp)
            )
        }
        z += step
    }
}

/**
 * The work itself: the band between design and ground, in the same colours the
 * map uses. Its thickness *is* the cut or fill at that point.
 */
private fun DrawScope.drawCutFillBand(
    profile: ProfileSampler.Profile,
    x: (Double) -> Float,
    y: (Double) -> Float,
    bottom: Float
) {
    val s = profile.samples
    for (i in 1 until s.size) {
        val a = s[i - 1]
        val b = s[i]
        val ad = a.designM; val ae = a.existingM
        val bd = b.designM; val be = b.existingM
        if (ad == null || ae == null || bd == null || be == null) continue

        val mid = b.cutFillMm ?: continue
        drawPath(
            Path().apply {
                moveTo(x(a.distanceM), y(ae))
                lineTo(x(b.distanceM), y(be))
                lineTo(x(b.distanceM), y(bd))
                lineTo(x(a.distanceM), y(ad))
                close()
            },
            color = CutFillPalette.colourFor(mid).copy(alpha = 0.55f)
        )
    }
}

/** One surface as a polyline, broken wherever its data is missing. */
private inline fun DrawScope.drawSurfaceLine(
    profile: ProfileSampler.Profile,
    x: (Double) -> Float,
    y: (Double) -> Float,
    colour: Color,
    pick: (ProfileSampler.Sample) -> Double?
) {
    var started = false
    val path = Path()
    for (s in profile.samples) {
        val z = pick(s)
        if (z == null) {
            started = false          // a gap in the surface is a gap in the line
            continue
        }
        val px = x(s.distanceM)
        val py = y(z)
        if (!started) {
            path.moveTo(px, py)
            started = true
        } else {
            path.lineTo(px, py)
        }
    }
    drawPath(path, color = colour, style = Stroke(width = 2f))
}

/** Named at the line's own end — identity never rides on a legend key alone. */
private fun DrawScope.drawDirectLabels(
    measurer: TextMeasurer,
    profile: ProfileSampler.Profile,
    right: Float,
    y: (Double) -> Float
) {
    val last = profile.samples.lastOrNull { it.designM != null || it.existingM != null } ?: return
    val style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Bold)
    last.designM?.let {
        drawText(
            measurer, "DESIGN", topLeft = Offset(right + 4f, y(it) - 6f),
            style = style.copy(color = DESIGN_LINE)
        )
    }
    last.existingM?.let {
        drawText(
            measurer, "GROUND", topLeft = Offset(right + 4f, y(it) - 6f),
            style = style.copy(color = GROUND_LINE)
        )
    }
}

/** Where the machine is now: distance zero, with the blade's actual elevation. */
private fun DrawScope.drawMachineMarker(
    measurer: TextMeasurer,
    profile: ProfileSampler.Profile,
    bladeElevationM: Double,
    x: (Double) -> Float,
    y: (Double) -> Float,
    top: Float,
    bottom: Float
) {
    val px = x(0.0)
    drawLine(
        MACHINE, Offset(px, top), Offset(px, bottom), strokeWidth = 1.5f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
    )
    drawText(
        measurer, "NOW",
        topLeft = Offset(px - 12f, top - 14f),
        style = TextStyle(color = MACHINE, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    )
    if (bladeElevationM.isFinite()) {
        drawCircle(color = MACHINE, radius = 4f, center = Offset(px, y(bladeElevationM)))
        drawCircle(
            color = Color.Black.copy(alpha = 0.6f), radius = 4f,
            center = Offset(px, y(bladeElevationM)), style = Stroke(width = 1f)
        )
    }
}

private fun DrawScope.drawDistanceAxis(
    measurer: TextMeasurer,
    profile: ProfileSampler.Profile,
    bottom: Float,
    x: (Double) -> Float
) {
    val step = niceStep(profile.aheadM + profile.behindM) * 2
    var d = -Math.floor(profile.behindM / step) * step
    while (d <= profile.aheadM) {
        val px = x(d)
        drawLine(GRID, Offset(px, bottom), Offset(px, bottom + 4f), strokeWidth = 1f)
        val text = if (d == 0.0) "0" else "%+d".format(d.roundToInt())
        drawText(
            measurer, text,
            topLeft = Offset(px - 8f, bottom + 5f),
            style = TextStyle(color = MUTED, fontSize = 9.sp)
        )
        d += step
    }
    drawText(
        measurer, "m ahead",
        topLeft = Offset(x(profile.aheadM) - 34f, bottom + 5f),
        style = TextStyle(color = MUTED, fontSize = 9.sp)
    )
}

/** States the exaggeration, so no one reads a slope off this that is not there. */
private fun DrawScope.drawCaption(
    measurer: TextMeasurer, exaggeration: Int, toleranceMm: Int, left: Float, top: Float
) {
    drawText(
        measurer, "PROFILE AHEAD",
        topLeft = Offset(left, top - 20f),
        style = TextStyle(color = INK, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    )
    drawText(
        measurer, "vertical x$exaggeration  •  on grade +/-${toleranceMm}mm",
        topLeft = Offset(left + 96f, top - 19f),
        style = TextStyle(color = MUTED, fontSize = 9.sp)
    )
}

private fun DrawScope.drawCentred(measurer: TextMeasurer, text: String, at: Offset) {
    val laid = measurer.measure(text, TextStyle(color = MUTED, fontSize = 11.sp))
    drawText(laid, topLeft = Offset(at.x - laid.size.width / 2f, at.y - laid.size.height / 2f))
}

// ---------------------------------------------------------------- helpers

/** A round 1/2/5 x 10^n step near a tenth of the span. */
internal fun niceStep(span: Double): Double {
    if (!span.isFinite() || span <= 0.0) return 1.0
    val raw = span / 5.0
    val magnitude = Math.pow(10.0, Math.floor(Math.log10(raw)))
    val normalised = raw / magnitude
    val step = when {
        normalised <= 1.5 -> 1.0
        normalised <= 3.5 -> 2.0
        normalised <= 7.5 -> 5.0
        else -> 10.0
    }
    return step * magnitude
}

/** Smallest vertical window; below this an auto-range magnifies noise. */
private const val MIN_SPAN_M = 0.30

private val PANEL = Color(0xFF10141A)
private val GRID = Color(0xFF2A3038)
private val DESIGN_LINE = Color(0xFFE6E9EC)
private val GROUND_LINE = Color(0xFFB99A6B)
private val MACHINE = Color(0xFFFFC107)
private val INK = Color(0xFFE6E9EC)
private val MUTED = Color(0xFF9AA0A6)
