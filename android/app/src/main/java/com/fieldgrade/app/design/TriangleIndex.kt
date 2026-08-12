package com.fieldgrade.app.design

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A uniform grid over a triangulation, so "which triangle is under this point?"
 * does not mean testing every triangle.
 *
 * One job: given a plan position, return a short list of candidate triangle
 * indices. It performs no point-in-triangle test and no interpolation — the
 * candidates it returns still have to be checked properly by [TinDesignSurface].
 *
 * This is not premature optimisation. The machine queries the surface at the
 * GNSS rate for the whole working day; the sample surface carries 12,798
 * triangles. Linear scanning is ~13k tests per fix, which turns a 20-minute
 * replay into minutes of CPU and would be hopeless on a tablet at 5–10 Hz.
 */
class TriangleIndex(
    private val points: List<DesignPoint>,
    private val triangles: List<Triangle>,
    targetTrianglesPerCell: Int = 2
) {
    private val minE: Double
    private val minN: Double
    private val cellSize: Double
    private val cols: Int
    private val rows: Int

    /** CSR-style buckets: cellStart[i] until cellStart[i+1] indexes into cellItems. */
    private val cellStart: IntArray
    private val cellItems: IntArray

    val isEmpty: Boolean get() = triangles.isEmpty()

    init {
        if (triangles.isEmpty() || points.isEmpty()) {
            minE = 0.0; minN = 0.0; cellSize = 1.0; cols = 0; rows = 0
            cellStart = IntArray(1); cellItems = IntArray(0)
        } else {
            var loE = Double.MAX_VALUE; var hiE = -Double.MAX_VALUE
            var loN = Double.MAX_VALUE; var hiN = -Double.MAX_VALUE
            for (p in points) {
                if (p.eastM < loE) loE = p.eastM
                if (p.eastM > hiE) hiE = p.eastM
                if (p.northM < loN) loN = p.northM
                if (p.northM > hiN) hiN = p.northM
            }
            minE = loE; minN = loN
            val w = (hiE - loE).coerceAtLeast(1e-6)
            val h = (hiN - loN).coerceAtLeast(1e-6)

            // Aim for roughly `targetTrianglesPerCell` triangles per cell.
            val wantCells = max(1.0, triangles.size.toDouble() / targetTrianglesPerCell)
            val size = sqrt(w * h / wantCells).coerceAtLeast(1e-6)
            cellSize = size
            cols = max(1, ceil(w / size).toInt())
            rows = max(1, ceil(h / size).toInt())

            // Two-pass CSR build: count per cell, prefix-sum, then fill.
            val counts = IntArray(cols * rows + 1)
            forEachTriangleCell { _, cell -> counts[cell + 1]++ }
            for (i in 1 until counts.size) counts[i] += counts[i - 1]
            cellStart = counts
            val cursor = counts.copyOf()
            val items = IntArray(counts[counts.size - 1])
            forEachTriangleCell { t, cell -> items[cursor[cell]++] = t }
            cellItems = items
        }
    }

    /** Visit every (triangle, cell) pair its bounding box overlaps. */
    private inline fun forEachTriangleCell(action: (triangle: Int, cell: Int) -> Unit) {
        for (t in triangles.indices) {
            val tri = triangles[t]
            val a = points[tri.a]; val b = points[tri.b]; val c = points[tri.c]
            val loE = min(a.eastM, min(b.eastM, c.eastM))
            val hiE = max(a.eastM, max(b.eastM, c.eastM))
            val loN = min(a.northM, min(b.northM, c.northM))
            val hiN = max(a.northM, max(b.northM, c.northM))
            val c0 = colOf(loE); val c1 = colOf(hiE)
            val r0 = rowOf(loN); val r1 = rowOf(hiN)
            for (r in r0..r1) for (cc in c0..c1) action(t, r * cols + cc)
        }
    }

    private fun colOf(e: Double): Int =
        floor((e - minE) / cellSize).toInt().coerceIn(0, cols - 1)

    private fun rowOf(n: Double): Int =
        floor((n - minN) / cellSize).toInt().coerceIn(0, rows - 1)

    /**
     * Candidate triangle indices whose bounding box cell contains the point.
     * Returns an empty array when the point is outside the indexed extent.
     */
    fun candidatesAt(eastM: Double, northM: Double): IntArray {
        if (cols == 0 || rows == 0) return EMPTY
        val e0 = minE - cellSize
        val n0 = minN - cellSize
        if (eastM < e0 || northM < n0) return EMPTY
        if (eastM > minE + cols * cellSize + cellSize) return EMPTY
        if (northM > minN + rows * cellSize + cellSize) return EMPTY

        val cell = rowOf(northM) * cols + colOf(eastM)
        val from = cellStart[cell]
        val to = cellStart[cell + 1]
        if (from == to) return EMPTY
        return cellItems.copyOfRange(from, to)
    }

    /** Diagnostics for the harness — grid shape and occupancy. */
    fun describe(): String =
        "TriangleIndex(${cols}x${rows} cells @ ${"%.1f".format(cellSize)} m, " +
            "${triangles.size} triangles, ${cellItems.size} entries)"

    private companion object {
        val EMPTY = IntArray(0)
    }
}
