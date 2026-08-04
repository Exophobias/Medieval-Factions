package com.dansplugins.factionsystem.api.geometry

/**
 * One connected piece of a chunk region: its outer boundary, plus any gaps inside it.
 *
 * This exists because every consumer of [ChunkRingBuilder] so far is a web map, and a web map's
 * polygon type wants exactly this pairing. BlueMap's `ShapeMarker` takes one `Shape` and a separate
 * `Collection<Shape> getHoles()`; handing it a flat list of rings would make every consumer redo the
 * same containment test to work out which hole belongs to which outline, and get it subtly wrong in
 * different ways.
 *
 * A region with two disjoint parts produces two of these. A hole containing an island produces two
 * as well: the outer piece, whose [holes] contains the pocket, and the island, which is its own
 * polygon with no holes. Nesting deeper than that keeps working by the same rule.
 *
 * @property outer The outside edge. Always has `isHole == false`.
 * @property holes The gaps directly inside [outer], each with `isHole == true`. Only *direct*
 *   children: a hole nested inside an island inside a hole belongs to the island's polygon, not
 *   this one. Empty for a solid piece.
 */
data class ChunkPolygon(
    val outer: Ring,
    val holes: List<Ring>
)
