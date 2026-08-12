package com.fieldgrade.app.design

/**
 * Finds the outline of a triangulated surface.
 *
 * One job: triangles -> closed loops of boundary points. No drawing, no
 * projection, no files.
 *
 * The rule is topological, not geometric: an edge shared by two triangles is
 * interior, an edge belonging to exactly one is on the boundary. That is why
 * this is used in preference to a convex hull — a real paddock is not convex,
 * and a hull would draw the outline straight across a re-entrant corner and
 * show the operator field that is not in the design. It also finds holes
 * (an unsurveyed dam or pivot point in the middle of a field) as their own
 * loops, which a hull cannot represent at all.
 *
 * For a Delaunay TIN built from loose XYZ the outer loop is the convex hull
 * anyway, so nothing is lost in that case.
 */
object SurfaceBoundary {

    /**
     * @return closed loops as lists of indices into [points]. The first and last
     *         index of each loop are different; the closing edge is implied.
     *         Loops are returned longest-first, so the field outline precedes
     *         any holes.
     */
    fun of(points: List<DesignPoint>, triangles: List<Triangle>): List<List<Int>> {
        if (triangles.isEmpty()) return emptyList()

        // --- count undirected edges; winding is not assumed to be consistent ---
        val counts = HashMap<Long, Int>(triangles.size * 2)
        fun bump(i: Int, j: Int) {
            val key = edgeKey(i, j)
            counts[key] = (counts[key] ?: 0) + 1
        }
        for (t in triangles) {
            bump(t.a, t.b); bump(t.b, t.c); bump(t.c, t.a)
        }

        // --- adjacency over boundary edges only ---
        val adjacency = HashMap<Int, MutableList<Int>>()
        for ((key, count) in counts) {
            if (count != 1) continue
            val u = (key ushr 32).toInt()
            val v = (key and 0xFFFFFFFFL).toInt()
            adjacency.getOrPut(u) { ArrayList(2) }.add(v)
            adjacency.getOrPut(v) { ArrayList(2) }.add(u)
        }
        if (adjacency.isEmpty()) return emptyList()

        // --- walk each loop, consuming edges as they are used ---
        val used = HashSet<Long>(adjacency.size)
        val loops = ArrayList<List<Int>>()

        for (start in adjacency.keys.sorted()) {
            while (true) {
                val first = adjacency[start]?.firstOrNull { !used.contains(edgeKey(start, it)) }
                    ?: break

                val loop = ArrayList<Int>()
                var current = start
                var next: Int? = first
                while (next != null) {
                    used.add(edgeKey(current, next))
                    loop.add(current)
                    current = next
                    if (current == start) break
                    next = adjacency[current]?.firstOrNull { !used.contains(edgeKey(current, it)) }
                }
                // A boundary that does not close is malformed input, not a loop.
                if (loop.size >= 3 && current == start) loops.add(loop)
            }
        }

        return loops.sortedByDescending { it.size }
    }

    /** Convenience: the longest loop, as coordinates. Empty when there is none. */
    fun outline(points: List<DesignPoint>, triangles: List<Triangle>): List<DesignPoint> =
        of(points, triangles).firstOrNull()?.map { points[it] } ?: emptyList()

    private fun edgeKey(i: Int, j: Int): Long {
        val lo = minOf(i, j).toLong()
        val hi = maxOf(i, j).toLong()
        return (lo shl 32) or hi
    }
}
