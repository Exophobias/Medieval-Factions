package com.dansplugins.factionsystem.api.geometry

/**
 * A point on the chunk grid.
 *
 * Used for two different things by [ChunkRingBuilder], and the difference matters:
 *
 * - As an **input**, it identifies a chunk, the same way `Chunk.getX()`/`getZ()` do.
 * - As an **output vertex**, it identifies a chunk *corner*. Chunk `(x, z)` occupies the square
 *   whose corners are `(x, z)`, `(x+1, z)`, `(x+1, z+1)` and `(x, z+1)`.
 *
 * So an output vertex of `(3, 5)` is not "chunk 3,5"; it is the north-west corner of it. Multiply by
 * 16 to reach block coordinates, which is what a web map wants. Doing that multiplication here would
 * force a floating-point type on a value that is exactly representable as an int, and would bake a
 * chunk size into an API that has no other reason to know one.
 *
 * A plain data class rather than a value class, and plain `Int`s rather than a packed `Long`, so
 * that Java consumers get `getX()`/`getZ()`, `equals`, `hashCode` and a readable `toString` for free.
 * These end up in hash sets by the thousand, which is exactly what the generated `hashCode` is for.
 */
data class ChunkPos(val x: Int, val z: Int)
