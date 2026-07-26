package com.dansplugins.factionsystem.listener

import com.dansplugins.factionsystem.api.ClaimAction
import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.player.MfPlayer
import dev.forkhandles.result4k.onFailure
import org.bukkit.ChatColor.RED
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.DoubleChest
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.BlockInventoryHolder
import java.util.logging.Level.SEVERE

class InventoryClickListener(private val plugin: MedievalFactions) : Listener {

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked
        if (player !is org.bukkit.entity.Player) return

        val inventory = event.inventory
        val holder = inventory.holder

        // Check if the inventory belongs to one or more blocks in claimed territory.
        //
        // A DoubleChest is neither a BlockInventoryHolder nor a BlockState, so it previously fell
        // through to null and returned -- leaving double chests entirely unprotected here. Both
        // halves are resolved because a double chest can straddle a chunk boundary, which means the
        // two halves can sit in different claims owned by different factions.
        val blocks: List<Block> = when (holder) {
            is BlockInventoryHolder -> listOf(holder.block)
            is BlockState -> holder.block.let(::listOf)
            is DoubleChest -> listOfNotNull(blockOf(holder.leftSide), blockOf(holder.rightSide))
            else -> emptyList()
        }

        if (blocks.isEmpty()) return

        val playerService = plugin.services.playerService
        val mfPlayer = playerService.getPlayer(player)
        if (mfPlayer == null) {
            event.isCancelled = true
            plugin.server.scheduler.runTaskAsynchronously(
                plugin,
                Runnable {
                    playerService.save(MfPlayer(plugin, player)).onFailure {
                        player.sendMessage("$RED${plugin.language["InventoryClickFailedToSavePlayer"]}")
                        plugin.logger.log(SEVERE, "Failed to save player: ${it.reason.message}", it.reason.cause)
                        return@Runnable
                    }
                }
            )
            return
        }

        val claimService = plugin.services.claimService
        val factionService = plugin.services.factionService

        // Deny if ANY half is protected: for a chest straddling a claim boundary, permission to
        // reach one half is not permission to empty the other.
        for (block in blocks) {
            val claim = claimService.getClaim(block.chunk)
            if (claim == null) {
                if (plugin.config.getBoolean("wilderness.interaction.prevent", false)) {
                    event.isCancelled = true
                    if (plugin.config.getBoolean("wilderness.interaction.alert", true)) {
                        player.sendMessage("$RED${plugin.language["CannotInteractWithInventoryInWilderness"]}")
                    }
                    return
                }
                continue
            }

            val claimFaction = factionService.getFaction(claim.factionId) ?: continue

            // The override is asked about the BLOCK's position, not the player's. Passing the
            // player's coordinates let reach carry a grant across a claim boundary into a faction
            // that never consented to it.
            if (!claimService.isInteractionAllowed(mfPlayer.id, claim) &&
                !claimService.isOverridden(
                    mfPlayer.id, block.world,
                    block.x, block.y, block.z,
                    ClaimAction.CONTAINER
                )
            ) {
                if (mfPlayer.isBypassEnabled && player.hasPermission("mf.bypass")) {
                    player.sendMessage("$RED${plugin.language["FactionTerritoryProtectionBypassed"]}")
                    return
                }
                event.isCancelled = true
                player.sendMessage("$RED${plugin.language["CannotInteractWithInventoryInFactionTerritory", claimFaction.name]}")
                return
            }
        }
    }

    /** The block behind one half of a double chest, however that half is exposed by the API. */
    private fun blockOf(side: org.bukkit.inventory.InventoryHolder?): Block? = when (side) {
        is BlockInventoryHolder -> side.block
        is BlockState -> side.block
        else -> null
    }
}
