package com.dansplugins.factionsystem.api.geometry

/**
 * One closed boundary of a chunk region, as a loop of chunk-grid corner vertices.
 *
 * ### The closing edge is implied
 *
 * The last vertex is **not** a repeat of the first. A square is four vertices, not five. Consumers
 * that need an explicit closing point must append `vertices.first()` themselves; consumers that draw
 * a closed polygon, which is all of the map ones, must not, or they will emit a zero-length edge.
 *
 * ### Vertices are corners, not chunks
 *
 * See [ChunkPos]. Multiply by 16 for block coordinates.
 *
 * ### Collinear points are already removed
 *
 * A straight run of chunks yields two vertices, not one per chunk. A 1000-chunk strip is 4 vertices,
 * not 2002. This is done here rather than left to consumers because doing it wrongly is easy and the
 * failure is silent: the obvious shortcut of dropping any vertex whose neighbours differ in both
 * axes also deletes the pinch point where two diagonally-touching regions meet, turning two correct
 * shapes into one bowtie that covers land nobody owns.
 *
 * @property vertices The loop, in order. Never fewer than four entries.
 * @property isHole Whether this ring bounds a gap inside the region rather than the region's outside
 *   edge. A hole inside a hole is not a hole: an island of owned chunks sitting inside an unclaimed
 *   pocket is an outer boundary again, and reports `false`.
 */
data class Ring(
    val vertices: List<ChunkPos>,
    val isHole: Boolean
)
