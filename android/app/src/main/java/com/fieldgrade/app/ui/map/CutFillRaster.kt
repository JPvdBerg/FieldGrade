package com.fieldgrade.app.ui.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import com.fieldgrade.app.design.CutFillField

/**
 * Turns a [CutFillField] into an image, once.
 *
 * One job: raster in, [ImageBitmap] out. It performs no layout and holds no
 * policy about when to rebuild — the caller decides that from the field's
 * `version`.
 *
 * Rebuilding matters because the field is redrawn continuously while the machine
 * works. Painting ~10,000 grid cells as individual rectangles every frame would
 * not hold a usable frame rate on a tablet; painting them once into a small
 * bitmap and letting the GPU scale it costs almost nothing per frame.
 *
 * The bitmap is one pixel per grid cell, so it is tiny — a 200 m field at 2 m
 * resolution is about 100x100. It is drawn **unfiltered** at display size: the
 * blocky cells are honest about the resolution the data actually has, and hard
 * band edges survive direct sunlight where a smoothed gradient turns to mush.
 */
object CutFillRaster {

    /** One pixel per cell, bottom-up flipped so row 0 (south) lands at the image bottom. */
    fun render(field: CutFillField): ImageBitmap {
        val image = ImageBitmap(field.cols, field.rows)
        val canvas = Canvas(image)
        val paint = Paint()

        for (row in 0 until field.rows) {
            // North is up on screen; row 0 is the southern edge.
            val y = (field.rows - 1 - row).toFloat()
            var col = 0
            while (col < field.cols) {
                val value = field.valueAt(col, row)
                // Coalesce equal neighbours into one rectangle — long runs are the
                // common case across a field, and it cuts the draw calls sharply.
                var end = col + 1
                while (end < field.cols && field.valueAt(end, row) == value) end++

                if (value != CutFillField.NO_DATA) {
                    paint.color = CutFillPalette.colourFor(value)
                    canvas.drawRect(
                        left = col.toFloat(), top = y,
                        right = end.toFloat(), bottom = y + 1f,
                        paint = paint
                    )
                }
                col = end
            }
        }
        return image
    }

    /**
     * Where the raster belongs on screen: the field's own extent, projected.
     * Returned as top-left plus size, which is what `drawImage` wants.
     */
    fun placement(field: CutFillField, projection: MapProjection): Pair<Offset, Size> {
        val topLeft = projection.toScreen(field.minE, field.minN + field.heightM)
        val bottomRight = projection.toScreen(field.minE + field.widthM, field.minN)
        return Offset(topLeft.x, topLeft.y) to
            Size(bottomRight.x - topLeft.x, bottomRight.y - topLeft.y)
    }
}
