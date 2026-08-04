package com.dansplugins.factionsystem.api.geometry

/**
 * Turns a set of chunks into the closed boundaries that enclose it.
 *
 * The input is a plain `Set<ChunkPos>` rather than a faction, a fief or anything else that owns
 * land. That is deliberate: fief territory is not stored as Medieval Factions claims, so an API
 * shaped around a faction id would have been unusable by half its intended callers, and the
 * geometry does not care whose chunks these are.
 *
 * ### What you get
 *
 * [buildPolygons] is what a map wants: one [ChunkPolygon] per connected piece, each pairing an outer
 * boundary with the gaps directly inside it. [buildRings] returns the same boundaries flat, already
 * classified, for callers that want to do their own grouping.
 *
 * Output vertices are chunk-grid **corners** (see [ChunkPos]), collinear runs are already collapsed,
 * and the closing edge is implied rather than repeated.
 *
 * ### Cost
 *
 * Proportional to the number of boundary edges, which is the region's **perimeter**, not its area.
 * A solid 100x100 block of chunks is 10,000 chunks but only 400 boundary edges. The containment pass
 * that classifies rings is quadratic in the ring *count*, which is small for any real territory: a
 * realm with one outer edge and three enclaves has four rings, not four thousand.
 *
 * This is cheap enough to run on the server thread. Measured against real Medieval Factions claim
 * data the worst single faction came out at a fraction of a millisecond against a 50 ms tick. The
 * shape that costs is fragmentation, not size: a thousand scattered single-chunk claims have a
 * perimeter of four thousand where a thousand contiguous ones have roughly a hundred and thirty.
 * Callers that allow non-contiguous claiming should bound the input rather than assume this is free.
 *
 * ### Thread safety
 *
 * Pure. No shared state, no Bukkit calls, no world access. Safe to call from any thread, and safe to
 * call concurrently.
 */
object ChunkRingBuilder {

    /**
     * The boundaries of [chunks], grouped into connected pieces with their holes attached.
     *
     * @return one entry per connected piece, in a stable order. Empty if [chunks] is empty.
     */
    @JvmStatic
    fun buildPolygons(chunks: Set<ChunkPos>): List<ChunkPolygon> {
        val rings = buildRings(chunks)
        if (rings.isEmpty()) return emptyList()

        // Re-derive nesting so holes can be attached to the piece that actually encloses them.
        // Doing it here rather than threading depths out of buildRings keeps Ring free of a parent
        // pointer, which would make it awkward to construct in a test.
        val reps = rings.map(::interiorPoint)
        val depths = IntArray(rings.size) { i ->
            rings.indices.count { j -> j != i && contains(rings[j], reps[i]) }
        }
        // The enclosing ring is the deepest one that contains us, which for well-formed nesting is
        // exactly one level up. Computed off the precomputed depths so this stays quadratic in the
        // ring count rather than cubic.
        val parentOf = IntArray(rings.size) { i ->
            rings.indices
                .filter { j -> j != i && contains(rings[j], reps[i]) }
                .maxByOrNull { depths[it] }
                ?: -1
        }

        return rings.indices
            .filter { !rings[it].isHole }
            .map { outer ->
                ChunkPolygon(
                    outer = rings[outer],
                    holes = rings.indices.filter { rings[it].isHole && parentOf[it] == outer }.map { rings[it] }
                )
            }
    }

    /**
     * The boundaries of [chunks], flat and already classified as outer edges or holes.
     *
     * @return every closed boundary, in a stable order. Empty if [chunks] is empty.
     */
    @JvmStatic
    fun buildRings(chunks: Set<ChunkPos>): List<Ring> {
        if (chunks.isEmpty()) return emptyList()

        val loops = traceLoops(chunks).map(::dropCollinear)
        if (loops.isEmpty()) return emptyList()

        // Classify by containment, never by winding direction. Orientation happens to be reliable
        // the way the loops are traced below, but only for simple closed curves; containment stays
        // correct for the nested cases too, and an island sitting inside an unclaimed pocket is an
        // outer boundary again rather than a hole inside a hole.
        val reps = loops.map(::interiorPointOf)
        return loops.mapIndexed { i, loop ->
            var depth = 0
            for (j in loops.indices) {
                if (i != j && contains(loops[j], reps[i])) depth++
            }
            Ring(vertices = loop, isHole = depth % 2 == 1)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Tracing
    // ---------------------------------------------------------------------------------------------

    /**
     * Walks the boundary edges of [chunks] into closed loops of unit-length steps.
     *
     * Every edge is emitted directed so the owned side is on its **right**. That single convention is
     * what makes the rest work: loops close without bookkeeping, and outer edges and holes come out
     * wound oppositely for free.
     */
    private fun traceLoops(chunks: Set<ChunkPos>): List<List<ChunkPos>> {
        val outgoing = HashMap<ChunkPos, MutableList<ChunkPos>>()

        fun edge(from: ChunkPos, to: ChunkPos) {
            outgoing.getOrPut(from) { ArrayList(2) }.add(to)
        }

        for (c in chunks) {
            val x = c.x
            val z = c.z
            if (ChunkPos(x, z - 1) !in chunks) edge(ChunkPos(x, z), ChunkPos(x + 1, z))
            if (ChunkPos(x + 1, z) !in chunks) edge(ChunkPos(x + 1, z), ChunkPos(x + 1, z + 1))
            if (ChunkPos(x, z + 1) !in chunks) edge(ChunkPos(x + 1, z + 1), ChunkPos(x, z + 1))
            if (ChunkPos(x - 1, z) !in chunks) edge(ChunkPos(x, z + 1), ChunkPos(x, z))
        }

        val loops = ArrayList<List<ChunkPos>>()
        // Sorted so the output order is stable across runs; HashMap iteration order is not.
        val starts = outgoing.keys.sortedWith(compareBy({ it.z }, { it.x }))

        for (start in starts) {
            while (!outgoing[start].isNullOrEmpty()) {
                val loop = ArrayList<ChunkPos>()
                var current = start
                var heading: ChunkPos? = null

                while (true) {
                    val candidates = outgoing[current]
                    if (candidates.isNullOrEmpty()) break
                    val pick = chooseNext(heading, current, candidates)
                    val next = candidates.removeAt(pick)
                    loop.add(current)
                    heading = ChunkPos(next.x - current.x, next.z - current.z)
                    current = next
                    if (current == start) break
                }

                if (loop.size >= 4) loops.add(loop)
            }
        }
        return loops
    }

    /**
     * Picks the next edge at a vertex, preferring the sharpest available right turn.
     *
     * This is the whole fix for diagonally-touching regions. Where two chunks meet at only a corner,
     * four boundary edges converge on that one point and there are two ways to continue. Taking
     * whichever happens to be first merges the two regions into a single self-intersecting loop that
     * claims the two unowned chunks between them, and because it depends on iteration order it does
     * it inconsistently. Always turning as hard right as possible keeps each region's boundary
     * hugging its own side of the pinch, so they come out as two clean loops.
     *
     * With no incoming direction, at the very start of a loop, the choice is made lexicographically
     * so that repeated runs over the same input produce identical output.
     */
    private fun chooseNext(heading: ChunkPos?, from: ChunkPos, candidates: List<ChunkPos>): Int {
        if (candidates.size == 1) return 0
        if (heading == null) {
            var best = 0
            for (i in candidates.indices) {
                val c = candidates[i]
                val b = candidates[best]
                if (c.z < b.z || (c.z == b.z && c.x < b.x)) best = i
            }
            return best
        }

        var best = 0
        var bestRank = Int.MAX_VALUE
        for (i in candidates.indices) {
            val dir = ChunkPos(candidates[i].x - from.x, candidates[i].z - from.z)
            val rank = turnRank(heading, dir)
            if (rank < bestRank) {
                bestRank = rank
                best = i
            }
        }
        return best
    }

    /** 0 for a right turn, 1 for straight on, 2 for a left turn, 3 for doubling back. */
    private fun turnRank(heading: ChunkPos, dir: ChunkPos): Int = when (dir) {
        ChunkPos(-heading.z, heading.x) -> 0
        heading -> 1
        ChunkPos(heading.z, -heading.x) -> 2
        else -> 3
    }

    // ---------------------------------------------------------------------------------------------
    // Simplification
    // ---------------------------------------------------------------------------------------------

    /**
     * Removes vertices that sit in the middle of a straight run.
     *
     * Tested with a cross product, which is the actual question being asked. The tempting shortcut
     * is to drop any vertex whose two neighbours differ in both axes, but that is a proxy rather
     * than the real test, and it also deletes the pinch vertex where a loop touches itself at a
     * corner. Doing so turns a correct L-shaped boundary into a bowtie enclosing land the owner does
     * not hold, with nothing anywhere reporting an error.
     */
    private fun dropCollinear(loop: List<ChunkPos>): List<ChunkPos> {
        if (loop.size < 3) return loop
        val kept = ArrayList<ChunkPos>(loop.size)
        for (i in loop.indices) {
            val prev = loop[(i - 1 + loop.size) % loop.size]
            val here = loop[i]
            val next = loop[(i + 1) % loop.size]
            val cross = (here.x - prev.x) * (next.z - here.z) - (here.z - prev.z) * (next.x - here.x)
            if (cross != 0) kept.add(here)
        }
        // A loop with no corners at all cannot happen for real chunk boundaries, but returning
        // something degenerate would be worse than returning the unsimplified loop.
        return if (kept.size >= 3) kept else loop
    }

    // ---------------------------------------------------------------------------------------------
    // Containment
    // ---------------------------------------------------------------------------------------------

    private fun interiorPoint(ring: Ring): DoublePoint = interiorPointOf(ring.vertices)

    /**
     * A point that is exactly the centre of a chunk lying on the owned side of the loop's first edge.
     *
     * Chunk centres are the useful choice because they are always at half-integers while every edge
     * sits on integers, so a ray cast from one can never graze a vertex or run along an edge. That
     * removes the entire family of point-in-polygon tie-breaking bugs rather than handling them.
     */
    private fun interiorPointOf(loop: List<ChunkPos>): DoublePoint {
        val a = loop[0]
        val b = loop[1]
        val dx = b.x - a.x
        val dz = b.z - a.z
        // Unit-normalised so this stays correct after collinear runs have been collapsed and the
        // first edge may be many chunks long.
        val len = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dz))
        val ux = dx / len
        val uz = dz / len
        // One step along the edge, then half a step to its right, which is the owned side.
        val mx = a.x + ux * 0.5
        val mz = a.z + uz * 0.5
        return DoublePoint(mx + (-uz) * 0.5, mz + ux * 0.5)
    }

    private fun contains(ring: Ring, point: DoublePoint): Boolean = contains(ring.vertices, point)

    /** Even-odd ray cast along +x. Safe without tie-breaking because [point] is never on an edge. */
    private fun contains(loop: List<ChunkPos>, point: DoublePoint): Boolean {
        var inside = false
        for (i in loop.indices) {
            val a = loop[i]
            val b = loop[(i + 1) % loop.size]
            if ((a.z > point.z) != (b.z > point.z)) {
                val t = (point.z - a.z).toDouble() / (b.z - a.z).toDouble()
                if (point.x < a.x + t * (b.x - a.x)) inside = !inside
            }
        }
        return inside
    }

    private data class DoublePoint(val x: Double, val z: Double)
}
