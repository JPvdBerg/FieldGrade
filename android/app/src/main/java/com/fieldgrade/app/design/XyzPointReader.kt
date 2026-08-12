package com.fieldgrade.app.design

/**
 * Reads the XYZ text interchange that land-forming designs actually travel in.
 *
 * One job: delimited text -> [DesignPoint]s. It does not triangulate, interpolate,
 * validate the shape of the surface, or touch a file handle.
 *
 * Two layouts are accepted, because these are the two that occur in practice:
 *
 *   `Point,Easting,Northing,Elevation,Code`  (OptiSurface -> Topcon AgForm3D)
 *   `Easting,Northing,Elevation`             (generic *.xyz)
 *
 * Delimiters may be comma, semicolon, tab or runs of spaces. A header row is used
 * when present and recognisable; otherwise the layout is inferred from column
 * count using one documented rule (see [inferLayout]).
 *
 * Where it refuses rather than guesses:
 *  - A four-column row with no header is genuinely ambiguous between
 *    `Pt,E,N,Z` and `E,N,Z,code`. The industry format wins, and that choice is
 *    stated here rather than buried — pass an explicit [Layout] to override.
 *  - Any row that cannot be read as numbers is reported with its line number.
 *    A file that is more than [maxBadFraction] rubbish is rejected outright,
 *    because silently importing 3 points out of 7000 is worse than failing.
 */
object XyzPointReader {

    /** Zero-based column positions of the three values we need. */
    data class Layout(val eastCol: Int, val northCol: Int, val elevCol: Int) {
        val minColumns: Int get() = maxOf(eastCol, northCol, elevCol) + 1

        companion object {
            /** `Easting,Northing,Elevation` */
            val PLAIN_ENZ = Layout(0, 1, 2)
            /** `Point,Easting,Northing,Elevation[,Code]` */
            val POINT_ENZ = Layout(1, 2, 3)
        }
    }

    data class Result(
        val points: List<DesignPoint>,
        val layout: Layout,
        val skippedLines: Int,
        val headerUsed: Boolean
    )

    private val DELIMITERS = Regex("[,;\t]|\\s{1,}")

    private const val DEFAULT_MAX_BAD_FRACTION = 0.10

    /**
     * Parse [text]. Pass [layout] to force a column mapping instead of inferring.
     *
     * @throws DesignFormatException if no points parse, or too many rows fail.
     */
    fun read(
        text: String,
        layout: Layout? = null,
        maxBadFraction: Double = DEFAULT_MAX_BAD_FRACTION
    ): Result {
        val rows = ArrayList<Pair<Int, List<String>>>()   // line number -> fields
        var headerFields: List<String>? = null

        text.lineSequence().forEachIndexed { idx, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return@forEachIndexed
            val fields = line.split(DELIMITERS).filter { it.isNotEmpty() }
            if (fields.isEmpty()) return@forEachIndexed
            // Exactly one leading non-numeric row is a header. Anything non-numeric
            // *after* data has started is corruption, and is kept so it counts
            // against the bad-row budget below rather than vanishing silently.
            if (fields[0].toDoubleOrNull() == null && rows.isEmpty() && headerFields == null) {
                headerFields = fields
                return@forEachIndexed
            }
            rows.add((idx + 1) to fields)
        }

        if (rows.isEmpty()) throw DesignFormatException("no numeric data rows found")

        val headerLayout = headerFields?.let { layoutFromHeader(it) }
        val resolved = layout ?: headerLayout ?: inferLayout(rows.first().second.size)

        val points = ArrayList<DesignPoint>(rows.size)
        var skipped = 0
        var firstError: String? = null

        for ((lineNo, fields) in rows) {
            if (fields.size < resolved.minColumns) {
                skipped++
                if (firstError == null) {
                    firstError = "line $lineNo: expected ${resolved.minColumns} columns, got ${fields.size}"
                }
                continue
            }
            val e = fields[resolved.eastCol].toDoubleOrNull()
            val n = fields[resolved.northCol].toDoubleOrNull()
            val z = fields[resolved.elevCol].toDoubleOrNull()
            if (e == null || n == null || z == null || !e.isFinite() || !n.isFinite() || !z.isFinite()) {
                skipped++
                if (firstError == null) firstError = "line $lineNo: non-numeric or non-finite coordinate"
                continue
            }
            points.add(DesignPoint(e, n, z))
        }

        if (points.isEmpty()) {
            throw DesignFormatException("no points parsed (${skipped} unusable rows; $firstError)")
        }
        val badFraction = skipped.toDouble() / rows.size
        if (badFraction > maxBadFraction) {
            throw DesignFormatException(
                "$skipped of ${rows.size} rows unusable (${(badFraction * 100).toInt()}%) — " +
                    "refusing to import a partial surface; first problem: $firstError"
            )
        }

        return Result(points, resolved, skipped, headerLayout != null)
    }

    /** Map columns by header name when the names are unambiguous. */
    private fun layoutFromHeader(header: List<String>): Layout? {
        fun find(vararg names: String): Int? =
            header.indexOfFirst { cell ->
                val c = cell.trim().lowercase().trim('"')
                names.any { c == it || c.startsWith(it) }
            }.takeIf { it >= 0 }

        val e = find("easting", "east", "x") ?: return null
        val n = find("northing", "north", "y") ?: return null
        val z = find("elevation", "elev", "height", "z") ?: return null
        if (e == n || n == z || e == z) return null
        return Layout(e, n, z)
    }

    /**
     * Column-count rule, applied only when there is no usable header:
     *  - 3 columns  -> `E,N,Z`
     *  - 4 or more  -> `Pt,E,N,Z[,Code]`, the documented industry layout
     */
    private fun inferLayout(columns: Int): Layout = when {
        columns == 3 -> Layout.PLAIN_ENZ
        columns >= 4 -> Layout.POINT_ENZ
        else -> throw DesignFormatException(
            "need at least 3 columns (E,N,Z); first data row has $columns"
        )
    }
}
