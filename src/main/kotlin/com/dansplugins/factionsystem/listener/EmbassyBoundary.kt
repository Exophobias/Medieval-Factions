package com.dansplugins.factionsystem.listener

import com.dansplugins.factionsystem.MedievalFactions
import org.bukkit.Location
import org.bukkit.block.Block
import java.util.UUID

/** Coordinate-only parcel boundary checks; no chunk load or database query on event hot paths. */
internal class EmbassyBoundary(private val plugin: MedievalFactions) {
    data class Point(val worldId: UUID, val chunkX: Int, val chunkZ: Int) {
        companion object {
            fun of(block: Block): Point = Point(block.world.uid, block.x shr 4, block.z shr 4)
            fun of(location: Location): Point? = location.world?.let {
                Point(it.uid, location.blockX shr 4, location.blockZ shr 4)
            }
        }
    }

    fun point(block: Block, offsetX: Int = 0, offsetZ: Int = 0): Point =
        Point(block.world.uid, (block.x + offsetX) shr 4, (block.z + offsetZ) shr 4)

    fun isEmbassy(point: Point): Boolean = plugin.services.embassyService.isParcelProtectionActive(
        point.worldId,
        point.chunkX,
        point.chunkZ
    )

    fun crosses(from: Point, to: Point): Boolean {
        if (from == to || (!isEmbassy(from) && !isEmbassy(to))) return false
        return !sameProtectedArea(from, to)
    }

    private fun sameProtectedArea(from: Point, to: Point): Boolean =
        from == to || (
            from.worldId == to.worldId &&
            plugin.services.embassyService.sameProtectedArea(
                from.worldId,
                from.chunkX,
                from.chunkZ,
                to.chunkX,
                to.chunkZ
            )
        )

    /** Unknown inventory locations may not exchange items with a known embassy location. */
    fun crosses(source: List<Point>?, destination: List<Point>?): Boolean {
        val known = (source ?: emptyList()) + (destination ?: emptyList())
        val parcel = known.firstOrNull(::isEmbassy) ?: return false
        if (source == null || destination == null) return true
        return known.any { !sameProtectedArea(parcel, it) }
    }
}
