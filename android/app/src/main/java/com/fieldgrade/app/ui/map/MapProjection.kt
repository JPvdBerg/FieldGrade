package com.fieldgrade.app.ui.map

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A point in canvas pixels. Kept free of Compose so the maths is unit-testable. */
data class ScreenPoint(val x: Float, val y: Float)

/** Which way the map is pointed. */
enum class MapOrientation {
    /** True north at the top of the screen, whole field in view. */
    NORTH_UP,

    /** The machine's heading at the top, centred on the machine, zoomed in. */
    HEADING_UP;

    fun toggled(): MapOrientation = if (this == NORTH_UP) HEADING_UP else NORTH_UP

    val label: String get() = if (this == NORTH_UP) "NORTH UP" else "HEADING UP"
}

/**
 * Maps the site grid onto the canvas.
 *
 * One job: East/North metres -> pixels. It draws nothing and holds no state about
 * the machine beyond the pivot it was built around.
 *
 * Two cameras, both built here so there is a single authority on where a metre
 * lands on screen:
 *
 *  - [boxed] / [fitting] — north-up, whole field. What you plan a job from.
 *  - [centredOn] — heading-up, machine-centred, zoomed. What you steer by, because
 *    it matches what the operator sees out of the windscreen.
 *
 * Properties enforced here rather than left to the drawing code:
 *
 *  - **The scale is equal in both axes.** One metre east is the same number of
 *    pixels as one metre north, so a square paddock looks square and any distance
 *    judged off the screen is honest. Stretching to fill the window would corrupt
 *    every such judgement.
 *  - **Up is well-defined and queryable.** North-up puts true north at the top;
 *    heading-up puts the machine's course there. Either way [northOnScreen] says
 *    which way north actually points, so the north arrow can never disagree with
 *    the map beneath it.
 */
class MapProjection private constructor(
    private val pivotE: Double,
    private val pivotN: Double,
    private val anchorX: Float,
    private val anchorY: Float,
    /** Pixels per metre; identical on both axes. */
    val scale: Float,
    /**
     * The bearing that is drawn pointing up, in radians clockwise from north.
     * 0 is north-up. Note this is the heading itself, *not* its negation: the
     * canvas y axis already points down, and that flip supplies the sign change
     * a rotation from world to screen would otherwise need.
     */
    private val rotationRad: Double,
    /** Ground extent this projection was built to show. */
    val widthM: Double,
    val heightM: Double
) {
    val isRotated: Boolean get() = rotationRad != 0.0

    /** The bearing drawn pointing up, in degrees. 0 for a north-up map. */
    val rotationDeg: Double get() = Math.toDegrees(rotationRad)

    private val cosR = cos(rotationRad)
    private val sinR = sin(rotationRad)

    fun toScreen(eastM: Double, northM: Double): ScreenPoint {
        val dx = (eastM - pivotE) * scale
        // Canvas y grows downward, north grows upward.
        val dy = -(northM - pivotN) * scale
        // Rotate so the machine's course points up. At rotation 0 this reduces to
        // the identity, so the north-up path costs nothing.
        val x = anchorX + (dx * cosR + dy * sinR).toFloat()
        val y = anchorY + (-dx * sinR + dy * cosR).toFloat()
        return ScreenPoint(x, y)
    }

    /**
     * Unit vector, in screen space, along which true north lies. North-up returns
     * straight up (0, -1); heading-up returns whatever direction north has ended
     * up in, which is exactly what the north arrow must be drawn along.
     */
    fun northOnScreen(): ScreenPoint =
        ScreenPoint((-sinR).toFloat(), (-cosR).toFloat())

    /** Length in pixels of a distance on the ground. */
    fun pixelsFor(metres: Double): Float = (metres * scale).toFloat()

    /** Ground distance covered by a number of pixels. */
    fun metresFor(pixels: Float): Double = pixels / scale.toDouble()

    /**
     * A round number of metres that renders near [targetPx] wide — the scale bar
     * shows 50 m, not 47 m.
     */
    fun niceScaleBarMetres(targetPx: Float = 120f): Double {
        val raw = metresFor(targetPx)
        val steps = doubleArrayOf(1.0, 2.0, 5.0, 10.0, 20.0, 25.0, 50.0, 100.0, 200.0, 500.0, 1000.0)
        return steps.lastOrNull { it <= raw } ?: steps.first()
    }

    companion object {

        /** North-up, the box fitted exactly inside the canvas less [paddingPx]. */
        fun boxed(
            minE: Double, minN: Double, maxE: Double, maxN: Double,
            widthPx: Float, heightPx: Float, paddingPx: Float = 24f
        ): MapProjection {
            val widthM = max(maxE - minE, 1e-6)
            val heightM = max(maxN - minN, 1e-6)
            val usableW = max(widthPx - 2 * paddingPx, 1f)
            val usableH = max(heightPx - 2 * paddingPx, 1f)
            val scale = min(usableW / widthM.toFloat(), usableH / heightM.toFloat())

            // Centre the field in whatever space is left on the looser axis.
            val drawnW = widthM.toFloat() * scale
            val drawnH = heightM.toFloat() * scale
            val offsetX = paddingPx + (usableW - drawnW) / 2f
            val offsetY = paddingPx + (usableH - drawnH) / 2f

            // Pivot on the world's north-west corner, anchored at the drawing origin.
            return MapProjection(
                pivotE = minE, pivotN = maxN,
                anchorX = offsetX, anchorY = offsetY,
                scale = scale, rotationRad = 0.0,
                widthM = widthM, heightM = heightM
            )
        }

        /** North-up with a margin of [marginFraction] of the larger side. */
        fun fitting(
            minE: Double, minN: Double, maxE: Double, maxN: Double,
            widthPx: Float, heightPx: Float,
            paddingPx: Float = 24f,
            marginFraction: Double = 0.04
        ): MapProjection {
            val margin = max(maxE - minE, maxN - minN) * marginFraction
            return boxed(
                minE - margin, minN - margin, maxE + margin, maxN + margin,
                widthPx, heightPx, paddingPx
            )
        }

        /**
         * Heading-up: centred on the machine, rotated so its course points up.
         *
         * @param radiusM ground radius to show. A working view, not the whole field.
         * @param headingDeg course clockwise from north.
         * @param anchorFraction how far down the canvas the machine sits. Below
         *        centre by default so most of the screen shows the ground *ahead* —
         *        an operator needs to see what is coming, not what has been done.
         */
        fun centredOn(
            eastM: Double, northM: Double,
            radiusM: Double,
            headingDeg: Double,
            widthPx: Float, heightPx: Float,
            anchorFraction: Float = 0.68f
        ): MapProjection {
            val radius = max(radiusM, 1e-3)
            val scale = min(widthPx, heightPx) / (2f * radius.toFloat())
            // Before the first usable course, fall back to north-up rather than
            // rotating by NaN and erasing the map.
            val rotation = if (headingDeg.isFinite()) Math.toRadians(headingDeg) else 0.0
            return MapProjection(
                pivotE = eastM, pivotN = northM,
                anchorX = widthPx / 2f, anchorY = heightPx * anchorFraction,
                scale = scale, rotationRad = rotation,
                widthM = 2 * radius, heightM = 2 * radius
            )
        }
    }
}
