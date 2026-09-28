package com.dansplugins.factionsystem.listener

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.ClaimAction
import com.dansplugins.factionsystem.claim.EmbassyAccessDecision
import com.dansplugins.factionsystem.player.MfPlayerId
import org.bukkit.block.data.Directional
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockDispenseEvent
import org.bukkit.event.block.BlockFertilizeEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockSpreadEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntitySpawnEvent
import org.bukkit.event.world.StructureGrowEvent
import java.util.Collections
import java.util.WeakHashMap

/** Prevent indirect terrain and item changes crossing a peaceful charter's outer boundary. */
class EmbassyBoundaryListener(private val plugin: MedievalFactions) : Listener {
    private val boundary = EmbassyBoundary(plugin)
    private val fallingOrigins = Collections.synchronizedMap(WeakHashMap<FallingBlock, EmbassyBoundary.Point>())

    @EventHandler
    fun onFlow(event: BlockFromToEvent) {
        if (boundary.crosses(
                EmbassyBoundary.Point.of(event.block),
                EmbassyBoundary.Point.of(event.toBlock)
            )
        ) {
                event.isCancelled = true
            }
    }

    @EventHandler
    fun onSpread(event: BlockSpreadEvent) {
        if (boundary.crosses(
                EmbassyBoundary.Point.of(event.source),
                EmbassyBoundary.Point.of(event.block)
            )
        ) {
                event.isCancelled = true
            }
    }

    @EventHandler(ignoreCancelled = true)
    fun onStructureGrow(event: StructureGrowEvent) {
        val origin = EmbassyBoundary.Point.of(event.location) ?: return
        if (event.blocks.any { boundary.crosses(origin, EmbassyBoundary.Point.of(it.block)) }) {
            event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onFertilize(event: BlockFertilizeEvent) {
        val origin = EmbassyBoundary.Point.of(event.block)
        if (event.blocks.any { boundary.crosses(origin, EmbassyBoundary.Point.of(it.block)) }) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onIgnite(event: BlockIgniteEvent) {
        val destination = boundary.point(event.block)
        val source = event.ignitingBlock?.let { boundary.point(it) }
        if (source != null && boundary.crosses(source, destination)) {
            event.isCancelled = true
            return
        }
        if (!boundary.isEmbassy(destination)) return
        val player = event.player ?: ((event.ignitingEntity as? Projectile)?.shooter as? Player)
        if (player == null) {
            event.isCancelled = true
            return
        }
        val actor = plugin.services.playerService.getPlayer(player)
        if (actor?.isBypassEnabled == true && player.hasPermission("mf.bypass")) return
        val claim = plugin.services.claimService.getClaim(event.block.chunk)
        if (claim == null || plugin.services.embassyService.access(
                actor?.id ?: MfPlayerId.fromBukkitPlayer(player),
                claim,
                ClaimAction.INTERACT
            ) != EmbassyAccessDecision.GRANT
        ) {
                event.isCancelled = true
            }
    }

    @EventHandler
    fun onDispense(event: BlockDispenseEvent) {
        // Redstone does not identify who supplied power. A host can trigger a guest dispenser
        // from across the border, so automation inside an embassy stays disabled.
        val source = boundary.point(event.block)
        if (boundary.isEmbassy(source)) {
            event.isCancelled = true
            return
        }
        val facing = (event.block.blockData as? Directional)?.facing ?: return
        if (boundary.crosses(
                source,
                boundary.point(event.block, facing.modX, facing.modZ)
            )
        ) {
                event.isCancelled = true
            }
    }

    @EventHandler
    fun onFallingBlockSpawn(event: EntitySpawnEvent) {
        val falling = event.entity as? FallingBlock ?: return
        EmbassyBoundary.Point.of(falling.location)?.let { fallingOrigins[falling] = it }
    }

    @EventHandler
    fun onFallingBlockChange(event: EntityChangeBlockEvent) {
        val falling = event.entity as? FallingBlock ?: return
        val destination = EmbassyBoundary.Point.of(event.block)
        if (event.to.isAir) {
            // Some server versions publish the source transition before EntitySpawnEvent.
            fallingOrigins.putIfAbsent(falling, destination)
            return
        }
        val origin = fallingOrigins.remove(falling)
        if (origin == null) {
            // An entity already airborne when MF enabled has no known origin. It cannot be
            // admitted into an embassy on that uncertainty.
            if (boundary.isEmbassy(destination)) event.isCancelled = true
        } else if (boundary.crosses(origin, destination)) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onExtend(event: BlockPistonExtendEvent) {
        val direction = event.direction
        val piston = boundary.point(event.block)
        val head = boundary.point(event.block, direction.modX, direction.modZ)
        if (boundary.isEmbassy(piston) || boundary.crosses(piston, head) || event.blocks.any { block ->
                val moved = boundary.point(block)
                boundary.isEmbassy(moved) ||
                    boundary.crosses(moved, boundary.point(block, direction.modX, direction.modZ))
            }
        ) {
                event.isCancelled = true
            }
    }

    @EventHandler
    fun onRetract(event: BlockPistonRetractEvent) {
        val direction = event.direction
        val piston = boundary.point(event.block)
        val head = boundary.point(event.block, direction.modX, direction.modZ)
        if (boundary.isEmbassy(piston) || boundary.crosses(head, piston) || event.blocks.any { block ->
                val moved = boundary.point(block)
                boundary.isEmbassy(moved) ||
                    boundary.crosses(moved, boundary.point(block, -direction.modX, -direction.modZ))
            }
        ) {
                event.isCancelled = true
            }
    }
}
