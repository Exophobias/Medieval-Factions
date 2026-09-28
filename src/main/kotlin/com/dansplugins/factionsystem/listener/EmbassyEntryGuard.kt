package com.dansplugins.factionsystem.listener

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.claim.EmbassyAccessDecision
import com.dansplugins.factionsystem.player.MfPlayerId
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.Player
import java.util.UUID
import java.util.WeakHashMap

/** Main-thread admission and safe exit for an embassy's connected area. */
internal class EmbassyEntryGuard(private val plugin: MedievalFactions) {
    companion object {
        private val relocating = HashSet<UUID>()
        private val unsafeGround = setOf(Material.MAGMA_BLOCK, Material.CACTUS, Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE)
        private val unsafeSpace = setOf(Material.FIRE, Material.SOUL_FIRE, Material.LAVA)
    }
    private val lastFailedExit = WeakHashMap<Player, Long>()

    fun isRelocating(player: Player): Boolean = player.uniqueId in relocating

    fun isEmbassy(location: Location): Boolean {
        val world = location.world ?: return false
        return plugin.services.embassyService.hasActiveOrClearingEmbassy(
            world.uid, location.blockX shr 4, location.blockZ shr 4)
    }

    fun isDenied(player: Player, location: Location): Boolean {
        val world = location.world ?: return false
        val mfPlayer = plugin.services.playerService.getPlayer(player)
        if (mfPlayer?.isBypassEnabled == true && player.hasPermission("mf.bypass")) return false
        return plugin.services.embassyService.entryDecision(
            mfPlayer?.id ?: MfPlayerId.fromBukkitPlayer(player),
            world.uid, location.blockX shr 4, location.blockZ shr 4
        ) == EmbassyAccessDecision.DENY
    }

    fun sameChunk(first: Location, second: Location): Boolean =
        first.world?.uid == second.world?.uid &&
            first.blockX shr 4 == second.blockX shr 4 &&
            first.blockZ shr 4 == second.blockZ shr 4

    fun sameProtectedArea(first: Location, second: Location): Boolean {
        if (sameChunk(first, second)) return true
        val worldId = first.world?.uid ?: return false
        if (worldId != second.world?.uid) return false
        return plugin.services.embassyService.sameProtectedArea(worldId,
            first.blockX shr 4, first.blockZ shr 4,
            second.blockX shr 4, second.blockZ shr 4)
    }

    /** Choose the closest safe point just outside the parcel, then a world/server spawn. */
    fun exit(player: Player, from: Location): Location? {
        val world = from.world ?: return null
        val chunkX = from.blockX shr 4
        val chunkZ = from.blockZ shr 4
        val x = from.blockX
        val z = from.blockZ
        val cells = plugin.services.embassyService.protectedAreaAt(world.uid, chunkX, chunkZ)
            .map { it.chunkX to it.chunkZ }.toSet().ifEmpty { setOf(chunkX to chunkZ) }
        val edges = cells.flatMap { (cellX, cellZ) ->
            val minX = cellX shl 4
            val minZ = cellZ shl 4
            val edgeX = x.coerceIn(minX, minX + 15)
            val edgeZ = z.coerceIn(minZ, minZ + 15)
            listOfNotNull(
                if ((cellX - 1 to cellZ) !in cells) (minX - 1) to edgeZ else null,
                if ((cellX + 1 to cellZ) !in cells) (minX + 16) to edgeZ else null,
                if ((cellX to cellZ - 1) !in cells) edgeX to (minZ - 1) else null,
                if ((cellX to cellZ + 1) !in cells) edgeX to (minZ + 16) else null
            )
        }.distinct().sortedBy { (edgeX, edgeZ) ->
            val dx = edgeX.toLong() - x
            val dz = edgeZ.toLong() - z
            dx * dx + dz * dz
        }
        for ((edgeX, edgeZ) in edges) {
            if (isDenied(player, Location(world, edgeX + 0.5, from.y, edgeZ + 0.5))) continue
            val heights = (0..6).flatMap { delta ->
                if (delta == 0) listOf(from.blockY) else listOf(from.blockY + delta, from.blockY - delta)
            } + world.getHighestBlockYAt(edgeX, edgeZ) + 1
            for (y in heights) {
                val candidate = Location(world, edgeX + 0.5, y.toDouble(), edgeZ + 0.5,
                    from.yaw, from.pitch)
                if (!isDenied(player, candidate) && isSafe(world, edgeX, y, edgeZ)) return candidate
            }
        }
        val spawns = (listOf(world) + plugin.server.worlds.orEmpty()).distinctBy { it.uid }
            .mapNotNull { it.spawnLocation }
        for (spawn in spawns) {
            val spawnWorld = spawn.world ?: continue
            for (radius in listOf(0, 1, 2, 4, 8, 16, 24, 32)) {
                val offsets = if (radius == 0) listOf(0 to 0) else listOf(
                    radius to 0, -radius to 0, 0 to radius, 0 to -radius,
                    radius to radius, radius to -radius, -radius to radius, -radius to -radius
                )
                for ((dx, dz) in offsets) {
                    val sx = spawn.blockX + dx
                    val sz = spawn.blockZ + dz
                    for (sy in listOf(spawn.blockY, spawnWorld.getHighestBlockYAt(sx, sz) + 1)) {
                        val candidate = Location(spawnWorld, sx + 0.5, sy.toDouble(), sz + 0.5,
                            from.yaw, from.pitch)
                        if (!isDenied(player, candidate) && isSafe(spawnWorld, sx, sy, sz)) return candidate
                    }
                }
            }
        }
        // A configured world/server spawn can be used when terrain checks find no standable
        // point. It still must pass the parcel's admission rule to avoid a teleport loop.
        spawns.firstOrNull { !isDenied(player, it) }?.let { return it }
        val now = System.currentTimeMillis()
        if (now - (lastFailedExit[player] ?: 0L) > 10_000) {
            lastFailedExit[player] = now
            plugin.logger.warning("No permitted embassy exit found for ${player.uniqueId}; will retry")
        }
        return null
    }

    fun relocate(player: Player, from: Location): Boolean {
        val destination = exit(player, from) ?: return false
        return relocateTo(player, destination)
    }

    /** Also used for a vehicle rollback to the location it occupied immediately before entry. */
    fun relocateTo(player: Player, destination: Location): Boolean {
        val permitted = if (isDenied(player, destination)) exit(player, destination) ?: return false else destination
        if (!relocating.add(player.uniqueId)) return false
        return try {
            if (player.isInsideVehicle) player.leaveVehicle()
            player.teleport(permitted)
        } finally {
            relocating.remove(player.uniqueId)
        }
    }

    private fun isSafe(world: World, x: Int, y: Int, z: Int): Boolean {
        if (y <= world.minHeight || y + 1 >= world.maxHeight) return false
        val feet = world.getBlockAt(x, y, z)
        val head = world.getBlockAt(x, y + 1, z)
        val floor = world.getBlockAt(x, y - 1, z)
        return feet.isPassable && head.isPassable &&
            !feet.isLiquid && !head.isLiquid &&
            feet.type !in unsafeSpace && head.type !in unsafeSpace &&
            !floor.isPassable && floor.type !in unsafeGround
    }
}
