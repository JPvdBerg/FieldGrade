package com.fieldgrade.app.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.fieldgrade.app.control.GuidanceDirection
import com.fieldgrade.app.control.GuidanceState
import com.fieldgrade.app.design.CutFillField
import com.fieldgrade.app.geom.LocalXY
import com.fieldgrade.app.geom.MachinePose
import kotlin.math.max
import kotlin.math.min

/**
 * The moving map: the field from above, the work left, the work done, and the
 * machine on it.
 *
 * One job: draw. Every value it renders is handed to it — the outline, the
 * cut/fill raster, the track, the pose, the heading. It computes no guidance,
 * stores no history and filters nothing, so the same view renders a replay, a
 * live machine, or a fixture in a test.
 *
 * Two cameras, chosen by [orientation]:
 *  - **north-up** — the whole field, for planning and for finding the strips that
 *    still need a pass;
 *  - **heading-up** — centred on the machine and rotated to its course, for
 *    steering, because it matches what is out of the windscreen.
 *
 * Design rules it follows, from the system design document:
 *  - the map occupies most of the display, because it is what the operator steers by;
 *  - **colour is never the only indicator** — the ramp legend is captioned, the
 *    cut/fill readout is numeric, and the orientation and heading state are
 *    spelled out in words;
 *  - the scale is honest and true north is always locatable, both guaranteed by
 *    [MapProjection].
 */
@Composable
fun FieldMapView(
    state: GuidanceState,
    outline: List<LocalXY>,
    track: List<FieldTrack.Mark>,
    pose: MachinePose?,
    modifier: Modifier = Modifier,
    cutFill: CutFillField? = null,
    orientation: MapOrientation = MapOrientation.NORTH_UP,
    headingDeg: Double = Double.NaN,
    headingHolding: Boolean = false,
    headingUpRadiusM: Double = 45.0,
    onGradeToleranceMm: Int = CutFillPalette.ON_GRADE_MM
) {
    val measurer = rememberTextMeasurer()

    // Rebuild the raster only when the field actually changes; `version` ticks
    // whenever the blade works a cell, so a static field costs nothing per frame.
    val raster: ImageBitmap? = remember(cutFill, cutFill?.version) {
        cutFill?.let { CutFillRaster.render(it) }
    }

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val projection = buildProjection(
                orientation, outline, track, pose, cutFill,
                headingDeg, headingUpRadiusM, size.width, size.height
            ) ?: run {
                drawCentredText(measurer, "waiting for position", center, MUTED)
                return@Canvas
            }

            drawBackground()

            if (cutFill != null) {
                if (projection.isRotated) {
                    // Rotated: draw only the cells actually in view, as quads.
                    // A few thousand at this zoom, and it sidesteps rotating a
                    // bitmap — correctness over cleverness where they conflict.
                    drawCutFillCells(cutFill, projection, pose, headingUpRadiusM)
                } else if (raster != null) {
                    // Whole field: one scaled blit instead of ~11,000 rectangles.
                    drawCutFillRaster(raster, cutFill, projection)
                }
            }

            drawFieldOutline(outline, projection)
            drawWorkedTrack(track, projection)
            pose?.let { drawMachine(it, state, projection, headingDeg) }

            drawScaleBar(measurer, projection)
            drawNorthArrow(measurer, projection)
            drawLegend(measurer, onGradeToleranceMm)
            drawStatusLine(measurer, state, orientation, headingHolding)
        }
    }
}

// ---------------------------------------------------------------- framing

/**
 * Pick the camera. Heading-up needs a position to centre on; without one it
 * falls back rather than showing an empty rotated void.
 */
private fun buildProjection(
    orientation: MapOrientation,
    outline: List<LocalXY>,
    track: List<FieldTrack.Mark>,
    pose: MachinePose?,
    field: CutFillField?,
    headingDeg: Double,
    radiusM: Double,
    width: Float,
    height: Float
): MapProjection? {
    if (orientation == MapOrientation.HEADING_UP && pose != null) {
        return MapProjection.centredOn(
            pose.eastM, pose.northM, radiusM, headingDeg, width, height
        )
    }

    var minE = Double.MAX_VALUE; var maxE = -Double.MAX_VALUE
    var minN = Double.MAX_VALUE; var maxN = -Double.MAX_VALUE
    var any = false

    fun include(e: Double, n: Double) {
        if (!e.isFinite() || !n.isFinite()) return
        minE = min(minE, e); maxE = max(maxE, e)
        minN = min(minN, n); maxN = max(maxN, n)
        any = true
    }

    if (outline.isNotEmpty()) {
        outline.forEach { include(it.eastM, it.northM) }
    } else if (field != null) {
        include(field.minE, field.minN)
        include(field.minE + field.widthM, field.minN + field.heightM)
    } else {
        track.forEach { include(it.eastM, it.northM) }
        pose?.let { include(it.eastM, it.northM) }
    }
    if (!any || maxE - minE < 1e-6 || maxN - minN < 1e-6) return null

    return MapProjection.fitting(minE, minN, maxE, maxN, width, height)
}

// ---------------------------------------------------------------- layers

private fun DrawScope.drawBackground() {
    drawRect(color = GROUND)
}

/** Whole-field path: one scaled blit of the pre-rendered raster. */
private fun DrawScope.drawCutFillRaster(
    raster: ImageBitmap, field: CutFillField, p: MapProjection
) {
    val (topLeft, size) = CutFillRaster.placement(field, p)
    if (size.width <= 0f || size.height <= 0f) return
    drawImage(
        image = raster,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(raster.width, raster.height),
        dstOffset = IntOffset(topLeft.x.toInt(), topLeft.y.toInt()),
        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
        // Unfiltered: hard cell edges are honest about a 2 m raster, and they
        // survive sunlight where a smoothed ramp would not.
        filterQuality = FilterQuality.None
    )
}

/** Rotated path: the visible cells only, each as a quad through the projection. */
private fun DrawScope.drawCutFillCells(
    field: CutFillField, p: MapProjection, pose: MachinePose?, radiusM: Double
) {
    if (pose == null) return
    // Cover the view circle plus a cell of slack, so nothing pops in at the edge.
    val reach = radiusM + field.cellSizeM
    val c0 = field.colOf(pose.eastM - reach)
    val c1 = field.colOf(pose.eastM + reach)
    val r0 = field.rowOf(pose.northM - reach)
    val r1 = field.rowOf(pose.northM + reach)

    for (row in max(0, r0)..min(field.rows - 1, r1)) {
        for (col in max(0, c0)..min(field.cols - 1, c1)) {
            val value = field.valueAt(col, row)
            if (value == CutFillField.NO_DATA) continue

            val e0 = field.minE + col * field.cellSizeM
            val n0 = field.minN + row * field.cellSizeM
            val e1 = e0 + field.cellSizeM
            val n1 = n0 + field.cellSizeM

            val a = p.toScreen(e0, n0)
            val b = p.toScreen(e1, n0)
            val c = p.toScreen(e1, n1)
            val d = p.toScreen(e0, n1)
            drawPath(
                Path().apply {
                    moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); lineTo(d.x, d.y); close()
                },
                color = CutFillPalette.colourFor(value)
            )
        }
    }
}

private fun DrawScope.drawFieldOutline(outline: List<LocalXY>, p: MapProjection) {
    if (outline.size < 3) return
    val path = Path()
    outline.forEachIndexed { i, v ->
        val s = p.toScreen(v.eastM, v.northM)
        if (i == 0) path.moveTo(s.x, s.y) else path.lineTo(s.x, s.y)
    }
    path.close()
    drawPath(path, color = FIELD_FILL)
    drawPath(path, color = FIELD_EDGE, style = Stroke(width = 2f))
}

/**
 * The worked path, one segment per pair of marks, coloured by how far off grade
 * the machine was there. Segments rather than dots, so a pass reads as a
 * continuous swath the operator can see the edge of.
 */
private fun DrawScope.drawWorkedTrack(track: List<FieldTrack.Mark>, p: MapProjection) {
    if (track.size < 2) return
    val widthPx = max(3f, p.pixelsFor(2.5))

    for (i in 1 until track.size) {
        val a = track[i - 1]
        val b = track[i]
        val sa = p.toScreen(a.eastM, a.northM)
        val sb = p.toScreen(b.eastM, b.northM)
        drawLine(
            color = cutFillColour(b.cutFillMm, b.onGrade),
            start = Offset(sa.x, sa.y),
            end = Offset(sb.x, sb.y),
            strokeWidth = widthPx,
            cap = StrokeCap.Round
        )
    }
}

private fun DrawScope.drawMachine(
    pose: MachinePose, state: GuidanceState, p: MapProjection, headingDeg: Double
) {
    val s = p.toScreen(pose.eastM, pose.northM)
    val centre = Offset(s.x, s.y)

    drawCircle(color = MACHINE_HALO, radius = 16f, center = centre)

    // Heading relative to whatever the map has at the top. On a heading-up map
    // that is zero by construction, so the machine points straight up and stays
    // there while the field turns around it.
    val onScreenBearing =
        if (headingDeg.isFinite()) (headingDeg - p.rotationDeg).toFloat() else 0f

    rotate(degrees = onScreenBearing, pivot = centre) {
        val n = 13f
        val w = 8f
        val arrow = Path().apply {
            moveTo(centre.x, centre.y - n)          // nose
            lineTo(centre.x - w, centre.y + n * 0.8f)
            lineTo(centre.x, centre.y + n * 0.35f)  // tail notch
            lineTo(centre.x + w, centre.y + n * 0.8f)
            close()
        }
        drawPath(arrow, color = if (state.hasSolution) MACHINE else MUTED)
        drawPath(arrow, color = Color.Black.copy(alpha = 0.55f), style = Stroke(width = 1.5f))
    }
}

private fun DrawScope.drawScaleBar(measurer: TextMeasurer, p: MapProjection) {
    val metres = p.niceScaleBarMetres()
    val lengthPx = p.pixelsFor(metres)
    if (lengthPx < 8f) return

    val y = size.height - 22f
    val x0 = 20f
    val x1 = x0 + lengthPx
    drawLine(INK, Offset(x0, y), Offset(x1, y), strokeWidth = 2.5f)
    drawLine(INK, Offset(x0, y - 5f), Offset(x0, y + 5f), strokeWidth = 2.5f)
    drawLine(INK, Offset(x1, y - 5f), Offset(x1, y + 5f), strokeWidth = 2.5f)
    drawText(
        measurer, "${metres.toInt()} m",
        topLeft = Offset(x0, y + 6f),
        style = TextStyle(color = INK, fontSize = 11.sp)
    )
}

/**
 * True north, wherever it has ended up. Asking the projection rather than
 * assuming "up" means the arrow can never disagree with the map beneath it —
 * which on a rotating map is the difference between an aid and a hazard.
 */
private fun DrawScope.drawNorthArrow(measurer: TextMeasurer, p: MapProjection) {
    val cx = size.width - 34f
    val cy = 36f
    val n = p.northOnScreen()
    val len = 16f
    val tip = Offset(cx + n.x * len, cy + n.y * len)
    val tail = Offset(cx - n.x * len * 0.5f, cy - n.y * len * 0.5f)

    drawLine(INK, tail, tip, strokeWidth = 2.5f)
    // Arrowhead: two short strokes back from the tip, square to the shaft.
    val px = -n.y
    val py = n.x
    drawLine(INK, tip, Offset(tip.x - n.x * 6f + px * 4f, tip.y - n.y * 6f + py * 4f), strokeWidth = 2.5f)
    drawLine(INK, tip, Offset(tip.x - n.x * 6f - px * 4f, tip.y - n.y * 6f - py * 4f), strokeWidth = 2.5f)
    drawText(
        measurer, "N",
        topLeft = Offset(cx - 4f, cy + 20f),
        style = TextStyle(color = INK, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    )
}

/**
 * A diverging ramp strip: deepest cut on the left, neutral in the middle, deepest
 * fill on the right. Drawn as one continuous bar so it reads as a scale rather
 * than a set of categories, and captioned in words — colour is never the only
 * channel carrying the meaning.
 */
private fun DrawScope.drawLegend(measurer: TextMeasurer, toleranceMm: Int) {
    val ramp = CutFillPalette.rampStrip()
    val swatch = 16f
    val height = 12f
    val x0 = 20f
    val y = 20f

    ramp.forEachIndexed { i, colour ->
        drawRect(
            color = colour,
            topLeft = Offset(x0 + i * swatch, y),
            size = Size(swatch, height)
        )
    }
    drawRect(
        color = Color.Black.copy(alpha = 0.45f),
        topLeft = Offset(x0, y),
        size = Size(ramp.size * swatch, height),
        style = Stroke(width = 1f)
    )
    val midX = x0 + (CutFillPalette.midpointIndex + 0.5f) * swatch
    drawLine(INK, Offset(midX, y - 3f), Offset(midX, y + height + 3f), strokeWidth = 1.5f)

    val small = TextStyle(color = MUTED, fontSize = 10.sp)
    val bold = TextStyle(color = INK, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    drawText(measurer, "CUT", topLeft = Offset(x0, y + height + 5f), style = bold)
    drawText(
        measurer, "ON GRADE +/-${toleranceMm}mm",
        topLeft = Offset(midX - 42f, y + height + 5f), style = small
    )
    val fillLabel = measurer.measure("FILL", bold)
    drawText(
        measurer, "FILL",
        topLeft = Offset(x0 + ramp.size * swatch - fillLabel.size.width, y + height + 5f),
        style = bold
    )
}

/**
 * Which way the map is pointed, and whether the heading behind it is live.
 *
 * A heading-up map whose heading has quietly frozen looks exactly like one that
 * is tracking, so it has to say. Below walking pace, course over ground is noise
 * and the rotation is deliberately held.
 */
private fun DrawScope.drawStatusLine(
    measurer: TextMeasurer,
    state: GuidanceState,
    orientation: MapOrientation,
    headingHolding: Boolean
) {
    val y = size.height - 44f
    val bold = TextStyle(color = MUTED, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    drawText(measurer, orientation.label, topLeft = Offset(20f, y), style = bold)

    if (orientation == MapOrientation.HEADING_UP && headingHolding) {
        val label = measurer.measure(orientation.label, bold)
        drawText(
            measurer, "HEADING HELD - too slow to track",
            topLeft = Offset(20f + label.size.width + 10f, y),
            style = TextStyle(color = WARN, fontSize = 11.sp)
        )
    }
    if (!state.hasSolution) {
        drawCentredText(
            measurer, state.autoInhibitReason ?: "no solution",
            Offset(size.width / 2f, 28f), WARN
        )
    }
}

private fun DrawScope.drawCentredText(
    measurer: TextMeasurer, text: String, at: Offset, colour: Color
) {
    val laid = measurer.measure(text, TextStyle(color = colour, fontSize = 13.sp))
    drawText(laid, topLeft = Offset(at.x - laid.size.width / 2f, at.y - laid.size.height / 2f))
}

// ---------------------------------------------------------------- palette

/** Delegates to the validated ramp so map, track and profile all agree. */
private fun cutFillColour(cutFillMm: Int, onGrade: Boolean): Color =
    if (onGrade) CutFillPalette.colourFor(0) else CutFillPalette.colourFor(cutFillMm)

private val GROUND = Color(0xFF14181C)
private val FIELD_FILL = Color(0x1A9E9E9E)
private val FIELD_EDGE = Color(0xFF8A8F98)
private val MACHINE = Color(0xFFFFC107)
private val MACHINE_HALO = Color(0x33FFC107)
private val INK = Color(0xFFE6E9EC)
private val MUTED = Color(0xFF9AA0A6)
private val WARN = Color(0xFFEF6C00)

/** Colour used for the direction chip elsewhere in the UI, kept consistent here. */
internal fun directionColour(d: GuidanceDirection): Color = when (d) {
    GuidanceDirection.RAISE -> CutFillPalette.colourFor(400)
    GuidanceDirection.LOWER -> CutFillPalette.colourFor(-400)
    GuidanceDirection.ON_GRADE -> CutFillPalette.colourFor(0)
}
