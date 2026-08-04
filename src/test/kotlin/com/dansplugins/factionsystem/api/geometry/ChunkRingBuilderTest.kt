package com.dansplugins.factionsystem.api.geometry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChunkRingBuilderTest {

    private fun chunks(vararg xz: Pair<Int, Int>) = xz.map { ChunkPos(it.first, it.second) }.toSet()

    private fun rect(x0: Int, z0: Int, x1: Int, z1: Int): Set<ChunkPos> =
        (x0..x1).flatMap { x -> (z0..z1).map { z -> ChunkPos(x, z) } }.toSet()

    /** A ring that visits any corner twice is self-intersecting, which is the bowtie failure. */
    private fun assertSimple(ring: Ring) {
        assertEquals(
            ring.vertices.size,
            ring.vertices.toSet().size,
            "ring revisits a vertex, so it is self-intersecting: ${ring.vertices}"
        )
    }

    @Test
    fun `empty input produces no rings`() {
        assertEquals(emptyList<Ring>(), ChunkRingBuilder.buildRings(emptySet()))
        assertEquals(emptyList<ChunkPolygon>(), ChunkRingBuilder.buildPolygons(emptySet()))
    }

    @Test
    fun `a single chunk is one four-corner outer ring`() {
        val rings = ChunkRingBuilder.buildRings(chunks(0 to 0))
        assertEquals(1, rings.size)
        assertFalse(rings[0].isHole)
        assertEquals(4, rings[0].vertices.size)
        assertEquals(
            setOf(ChunkPos(0, 0), ChunkPos(1, 0), ChunkPos(1, 1), ChunkPos(0, 1)),
            rings[0].vertices.toSet()
        )
    }

    @Test
    fun `the closing edge is implied rather than repeated`() {
        val ring = ChunkRingBuilder.buildRings(chunks(0 to 0)).single()
        assertTrue(
            ring.vertices.first() != ring.vertices.last(),
            "first and last vertex are equal, so the closing edge was emitted twice"
        )
    }

    @Test
    fun `a long straight run collapses to its corners`() {
        // 1000 chunks in a line. Without collinear reduction this is 2002 vertices.
        val strip = (0 until 1000).map { ChunkPos(it, 0) }.toSet()
        val ring = ChunkRingBuilder.buildRings(strip).single()
        assertEquals(4, ring.vertices.size)
        assertEquals(
            setOf(ChunkPos(0, 0), ChunkPos(1000, 0), ChunkPos(1000, 1), ChunkPos(0, 1)),
            ring.vertices.toSet()
        )
    }

    @Test
    fun `a hollow square has one outer ring and exactly one hole`() {
        val hollow = rect(0, 0, 2, 2) - ChunkPos(1, 1)
        val rings = ChunkRingBuilder.buildRings(hollow)

        assertEquals(2, rings.size)
        assertEquals(1, rings.count { it.isHole })
        assertEquals(1, rings.count { !it.isHole })

        val outer = rings.single { !it.isHole }
        assertEquals(
            setOf(ChunkPos(0, 0), ChunkPos(3, 0), ChunkPos(3, 3), ChunkPos(0, 3)),
            outer.vertices.toSet()
        )
        val hole = rings.single { it.isHole }
        assertEquals(
            setOf(ChunkPos(1, 1), ChunkPos(2, 1), ChunkPos(2, 2), ChunkPos(1, 2)),
            hole.vertices.toSet()
        )
    }

    @Test
    fun `buildPolygons attaches the hole to the piece that encloses it`() {
        val hollow = rect(0, 0, 2, 2) - ChunkPos(1, 1)
        val polygons = ChunkRingBuilder.buildPolygons(hollow)

        assertEquals(1, polygons.size)
        assertFalse(polygons[0].outer.isHole)
        assertEquals(1, polygons[0].holes.size)
        assertTrue(polygons[0].holes[0].isHole)
    }

    @Test
    fun `diagonally touching chunks stay separate instead of merging into a bowtie`() {
        // Five chunks, each touching the others only at a corner. The previous builder returned
        // four paths here, one of them self-intersecting and covering land nobody owns.
        val checkerboard = chunks(0 to 0, 2 to 0, 1 to 1, 0 to 2, 2 to 2)
        val rings = ChunkRingBuilder.buildRings(checkerboard)

        assertEquals(5, rings.size, "diagonally-touching regions were merged")
        rings.forEach(::assertSimple)
        rings.forEach { assertFalse(it.isHole) }
        rings.forEach { assertEquals(4, it.vertices.size) }
    }

    @Test
    fun `three chunks meeting at one corner produce three clean rings`() {
        val threeWay = chunks(0 to 0, 1 to 1, 2 to 0)
        val rings = ChunkRingBuilder.buildRings(threeWay)

        assertEquals(3, rings.size)
        rings.forEach(::assertSimple)
        rings.forEach { assertEquals(4, it.vertices.size) }
    }

    @Test
    fun `an L shape keeps its pinch vertex`() {
        // The naive collinear test (drop any vertex whose neighbours differ in both axes) deletes
        // the inner corner here and turns the L into a quadrilateral covering the missing chunk.
        val l = chunks(0 to 0, 0 to 1, 1 to 1)
        val ring = ChunkRingBuilder.buildRings(l).single()

        assertEquals(6, ring.vertices.size, "the L's inner corner was dropped: ${ring.vertices}")
        assertTrue(ChunkPos(1, 1) in ring.vertices, "the inner corner is missing")
    }

    @Test
    fun `an island inside a hole is an outer ring, not a hole inside a hole`() {
        val withIsland = (rect(0, 0, 4, 4) - rect(1, 1, 3, 3)) + ChunkPos(2, 2)
        val rings = ChunkRingBuilder.buildRings(withIsland)

        assertEquals(3, rings.size)
        assertEquals(1, rings.count { it.isHole }, "the island was misclassified as a hole")

        val island = rings.single { !it.isHole && it.vertices.contains(ChunkPos(2, 2)) }
        assertFalse(island.isHole)
    }

    @Test
    fun `buildPolygons splits an island into its own piece`() {
        val withIsland = (rect(0, 0, 4, 4) - rect(1, 1, 3, 3)) + ChunkPos(2, 2)
        val polygons = ChunkRingBuilder.buildPolygons(withIsland)

        assertEquals(2, polygons.size)
        assertEquals(1, polygons.count { it.holes.size == 1 }, "the ring piece should own the pocket")
        assertEquals(1, polygons.count { it.holes.isEmpty() }, "the island should own nothing")
    }

    @Test
    fun `disjoint regions produce one polygon each with no holes`() {
        val two = rect(0, 0, 1, 1) + rect(10, 10, 11, 11)
        val polygons = ChunkRingBuilder.buildPolygons(two)

        assertEquals(2, polygons.size)
        polygons.forEach { assertTrue(it.holes.isEmpty()) }
    }

    @Test
    fun `negative coordinates work`() {
        val rings = ChunkRingBuilder.buildRings(rect(-5, -5, -4, -4))
        assertEquals(1, rings.size)
        assertEquals(
            setOf(ChunkPos(-5, -5), ChunkPos(-3, -5), ChunkPos(-3, -3), ChunkPos(-5, -3)),
            rings[0].vertices.toSet()
        )
    }

    @Test
    fun `regions spanning the origin work`() {
        val spanning = rect(-2, -2, 1, 1) - ChunkPos(0, 0)
        val rings = ChunkRingBuilder.buildRings(spanning)

        assertEquals(2, rings.size)
        assertEquals(1, rings.count { it.isHole })
        val hole = rings.single { it.isHole }
        assertEquals(
            setOf(ChunkPos(0, 0), ChunkPos(1, 0), ChunkPos(1, 1), ChunkPos(0, 1)),
            hole.vertices.toSet()
        )
    }

    @Test
    fun `output is stable across repeated runs`() {
        val region = (rect(0, 0, 6, 6) - rect(2, 2, 4, 4)) + ChunkPos(3, 3)
        val first = ChunkRingBuilder.buildRings(region)
        repeat(5) {
            assertEquals(first, ChunkRingBuilder.buildRings(region))
        }
    }

    @Test
    fun `a solid block costs its perimeter, not its area`() {
        // 10,000 chunks, 4 vertices out. Guards against anything area-proportional creeping in.
        val ring = ChunkRingBuilder.buildRings(rect(0, 0, 99, 99)).single()
        assertEquals(4, ring.vertices.size)
    }
}
