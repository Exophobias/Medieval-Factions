package com.dansplugins.factionsystem.listener

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.ClaimAction
import com.dansplugins.factionsystem.area.MfBlockPosition
import com.dansplugins.factionsystem.claim.EmbassyAccessDecision
import com.dansplugins.factionsystem.player.MfPlayer
import dev.forkhandles.result4k.onFailure
import org.bukkit.ChatColor.RED
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockPlaceEvent
import java.util.logging.Level

class BlockPlaceListener(private val plugin: MedievalFactions) : Listener {
    private val embassyBoundary = EmbassyBoundary(plugin)

    @EventHandler
    fun onBlockPlace(event: BlockPlaceEvent) {
        // A chest merged across the parcel edge becomes one inventory. Reject the placement on
        // either side of an embassy before the player can expose the other side's contents.
        if (event.block.type == Material.CHEST || event.block.type == Material.TRAPPED_CHEST) {
            val block = event.block
            if (listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST).any { face ->
                    val neighbor = block.getRelative(face)
                    neighbor.type == block.type && embassyBoundary.crosses(
                        embassyBoundary.point(block), embassyBoundary.point(neighbor)
                    )
                } && !(plugin.services.playerService.getPlayer(event.player)?.isBypassEnabled == true &&
                    event.player.hasPermission("mf.bypass"))) {
                event.isCancelled = true
                event.player.sendMessage("${RED}A chest cannot merge across an embassy boundary.")
                return
            }
        }
        val gateService = plugin.services.gateService
        val blockPosition = MfBlockPosition.fromBukkitBlock(event.block)
        val gates = gateService.getGatesAt(blockPosition)
        if (gates.isNotEmpty()) {
            event.isCancelled = true
            event.player.sendMessage("$RED${plugin.language["CannotPlaceBlockInGate"]}")
            return
        }

        val claimService = plugin.services.claimService
        val claim = claimService.getClaim(event.block.chunk)
        if (claim == null) {
            if (plugin.config.getBoolean("wilderness.place.prevent", false)) {
                event.isCancelled = true
                if (plugin.config.getBoolean("wilderness.place.alert", true)) {
                    event.player.sendMessage("$RED${plugin.language["CannotPlaceBlockInWilderness"]}")
                }
            }
            return
        }
        val factionService = plugin.services.factionService
        val claimFaction = factionService.getFaction(claim.factionId) ?: return
        val relationshipService = plugin.services.factionRelationshipService
        val playerService = plugin.services.playerService
        val mfPlayer = playerService.getPlayer(event.player)
        if (mfPlayer == null) {
            event.isCancelled = true
            plugin.server.scheduler.runTaskAsynchronously(
                plugin,
                Runnable {
                    playerService.save(MfPlayer(plugin, event.player)).onFailure {
                        event.player.sendMessage("$RED${plugin.language["BlockPlaceFailedToSavePlayer"]}")
                        plugin.logger.log(Level.SEVERE, "Failed to save player: ${it.reason.message}", it.reason.cause)
                        return@Runnable
                    }
                }
            )
            return
        }
        val playerFaction = factionService.getFaction(mfPlayer.id)
        // Embassy access is authoritative inside its chunk. In particular, the host's ordinary
        // claim permission, wartime placement and additive third-party providers cannot defeat a
        // charter's exclusive guest possession. The live staff bypass remains available.
        when (plugin.services.embassyService.access(mfPlayer.id, claim, ClaimAction.BUILD)) {
            EmbassyAccessDecision.GRANT -> return
            EmbassyAccessDecision.DENY -> {
                if (mfPlayer.isBypassEnabled && event.player.hasPermission("mf.bypass")) {
                    event.player.sendMessage("$RED${plugin.language["FactionTerritoryProtectionBypassed"]}")
                } else {
                    event.isCancelled = true
                    event.player.sendMessage("$RED${plugin.language["CannotPlaceBlockInFactionTerritory", claimFaction.name]}")
                }
                return
            }
            EmbassyAccessDecision.NONE -> Unit
        }
        if (!claimService.isInteractionAllowed(mfPlayer.id, claim) &&
            !claimService.isOverridden(
                    mfPlayer.id,
                    event.block.world,
                    event.block.x,
                    event.block.y,
                    event.block.z,
                    ClaimAction.BUILD
                )
        ) {
            if (mfPlayer.isBypassEnabled && event.player.hasPermission("mf.bypass")) {
                event.player.sendMessage("$RED${plugin.language["FactionTerritoryProtectionBypassed"]}")
            } else if (playerFaction != null && relationshipService.getFactionsAtWarWith(playerFaction.id).contains(claimFaction.id)) {
                val isLadderAllowed = event.block.type == Material.LADDER && plugin.config.getBoolean("factions.laddersPlaceableInEnemyFactionTerritory")
                if (!isLadderAllowed && !claimService.isWartimePlaceableBlock(mfPlayer.id, claim, event.block.type)) {
                    event.isCancelled = true
                    event.player.sendMessage("$RED${plugin.language["CannotPlaceBlockInFactionTerritory", claimFaction.name]}")
                }
            } else {
                event.isCancelled = true
                event.player.sendMessage("$RED${plugin.language["CannotPlaceBlockInFactionTerritory", claimFaction.name]}")
            }
        }
    }
}
