package com.fieldgrade.app.design

import com.fieldgrade.app.surface.DesignSurfaceModel
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * How much work is left, everywhere in the field, as a grid of millimetres.
 *
 * One job: hold and update a raster of `design elevation - existing elevation`.
 * It does not draw, does not know about GNSS, and does not decide colours.
 *
 * This is the picture the whole job is about. Guidance answers "how far off am I
 * *here*"; this answers "how much is left, and where" — which is what an operator
 * plans a pass from. It needs **two** surfaces, which is why the app loads an
 * existing-ground survey alongside the design; with only a design, the target is
 * knowable but the remaining work is not.
 *
 * A grid rather than triangles because it has to be redrawn continuously as the
 * machine works: sampling the TIN once into a raster turns every later redraw
 * into an array read, and [version] lets the renderer cache its bitmap and rebuild
 * only when something actually changed.
 */
class CutFillField(
    val minE: Double,
    val minN: Double,
    val cellSizeM: Double,
    val cols: Int,
    val rows: Int,
    private val values: IntArray
) {
    init {
        require(values.size == cols * rows) {
            "expected ${cols * rows} cells, got ${values.size}"
        }
    }

    /**
     * Bumped whenever a cell changes. The renderer keeps its own copy and
     * rebuilds only on a mismatch, so an unchanged field costs nothing to draw.
     */
    var version: Int = 0
        private set

    val widthM: Double get() = cols * cellSizeM
    val heightM: Double get() = rows * cellSizeM

    /** Cut/fill in millimetres, or [NO_DATA] outside the design. */
    fun valueAt(col: Int, row: Int): Int =
        if (col < 0 || row < 0 || col >= cols || row >= rows) NO_DATA
        else values[row * cols + col]

    fun valueAtWorld(eastM: Double, northM: Double): Int =
        valueAt(colOf(eastM), rowOf(northM))

    fun colOf(eastM: Double): Int = ((eastM - minE) / cellSizeM).toInt()
    fun rowOf(northM: Double): Int = ((northM - minN) / cellSizeM).toInt()

    /** Centre of a cell in world coordinates. */
    fun eastOf(col: Int): Double = minE + (col + 0.5) * cellSizeM
    fun northOf(row: Int): Double = minN + (row + 0.5) * cellSizeM

    /**
     * Record that the blade has just worked this spot, leaving [cutFillMm] still
     * to do there.
     *
     * Only cells that already had data are touched: passing outside the design
     * boundary must not invent surface that was never surveyed. That is the same
     * rule [TinDesignSurface] follows by returning null outside the hull.
     *
     * @param radiusM half the blade width — the swath actually cut.
     * @return true if any cell changed.
     */
    fun markWorked(eastM: Double, northM: Double, radiusM: Double, cutFillMm: Int): Boolean {
        val cellRadius = max(0, ceil(radiusM / cellSizeM).toInt())
        val c0 = colOf(eastM)
        val r0 = rowOf(northM)
        var changed = false

        for (r in (r0 - cellRadius)..(r0 + cellRadius)) {
            if (r < 0 || r >= rows) continue
            for (c in (c0 - cellRadius)..(c0 + cellRadius)) {
                if (c < 0 || c >= cols) continue
                val idx = r * cols + c
                if (values[idx] == NO_DATA) continue      // never invent surface
                if (values[idx] == cutFillMm) continue
                values[idx] = cutFillMm
                changed = true
            }
        }
        if (changed) version++
        return changed
    }

    /** Counts for the status readout: cells needing cut, fill, and already on grade. */
    fun summary(toleranceMm: Int = 25): Summary {
        var cut = 0; var fill = 0; var onGrade = 0; var none = 0
        for (v in values) {
            when {
                v == NO_DATA -> none++
                v > toleranceMm -> fill++
                v < -toleranceMm -> cut++
                else -> onGrade++
            }
        }
        return Summary(cut, fill, onGrade, none)
    }

    data class Summary(val cut: Int, val fill: Int, val onGrade: Int, val outside: Int) {
        val inField: Int get() = cut + fill + onGrade
        val onGradeFraction: Double get() = if (inField == 0) 0.0 else onGrade.toDouble() / inField
    }

    companion object {
        /** No design/existing pair covers this cell. */
        const val NO_DATA = Int.MIN_VALUE

        /**
         * Sample `design - existing` onto a grid.
         *
         * A cell is [NO_DATA] unless **both** surfaces cover it — a cut/fill value
         * derived from only one of them would be a guess presented as a measurement.
         *
         * @param cellSizeM grid resolution. 2 m is about a blade width and keeps a
         *        200 m field near 100x100 cells, which is cheap to sample and to
         *        rasterise; finer buys detail the operator cannot act on.
         */
        fun sample(
            design: DesignSurfaceModel,
            existing: DesignSurfaceModel,
            bounds: DesignSurface.Bounds,
            cellSizeM: Double = 2.0
        ): CutFillField {
            val cols = max(1, ceil(bounds.widthM / cellSizeM).toInt())
            val rows = max(1, ceil(bounds.heightM / cellSizeM).toInt())
            val values = IntArray(cols * rows)

            for (r in 0 until rows) {
                val north = bounds.minN + (r + 0.5) * cellSizeM
                for (c in 0 until cols) {
                    val east = bounds.minE + (c + 0.5) * cellSizeM
                    val d = design.elevationAt(east, north)
                    val e = existing.elevationAt(east, north)
                    values[r * cols + c] =
                        if (d == null || e == null) NO_DATA
                        else ((d - e) * 1000.0).roundToInt()
                }
            }
            return CutFillField(bounds.minE, bounds.minN, cellSizeM, cols, rows, values)
        }
    }
}
