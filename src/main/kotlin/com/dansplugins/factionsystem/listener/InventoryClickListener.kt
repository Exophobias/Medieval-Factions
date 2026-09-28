package com.dansplugins.factionsystem.listener

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.ClaimAction
import com.dansplugins.factionsystem.claim.EmbassyAccessDecision
import com.dansplugins.factionsystem.claim.MfEmbassyStatus
import com.dansplugins.factionsystem.player.MfPlayer
import dev.forkhandles.result4k.onFailure
import org.bukkit.ChatColor.RED
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.DoubleChest
import org.bukkit.Chunk
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCreativeEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryInteractEvent
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.BlockInventoryHolder
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.bukkit.entity.Player
import org.bukkit.Material
import java.util.logging.Level.SEVERE

class InventoryClickListener(private val plugin: MedievalFactions) : Listener {
    private val embassyBoundary = EmbassyBoundary(plugin)
    private data class Target(val chunk: Chunk, val world: World, val x: Int, val y: Int, val z: Int)

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        protect(event, player, event.inventory, depositsIntoTop(event, player))
    }

    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        // A drag into only the player's own slots does not touch the open parcel inventory.
        if (event.rawSlots.none { it < event.view.topInventory.size }) return
        protect(event, player, event.inventory, deposits = true)
    }

    @EventHandler
    fun onCreativeInventory(event: InventoryCreativeEvent) {
        val player = event.whoClicked as? Player ?: return
        if (event.rawSlot >= event.view.topInventory.size) return
        protect(event, player, event.inventory, deposits = true)
    }

    private fun depositsIntoTop(event: InventoryClickEvent, player: Player): Boolean = when (event.action) {
        InventoryAction.PLACE_ALL, InventoryAction.PLACE_ONE, InventoryAction.PLACE_SOME,
        InventoryAction.SWAP_WITH_CURSOR,
        InventoryAction.HOTBAR_MOVE_AND_READD ->
            event.clickedInventory == event.view.topInventory
        InventoryAction.HOTBAR_SWAP -> if (event.clickedInventory != event.view.topInventory) false else {
            when {
                event.click == ClickType.SWAP_OFFHAND -> hasStock(player.inventory.itemInOffHand)
                event.hotbarButton in 0..8 -> hasStock(player.inventory.getItem(event.hotbarButton))
                else -> true
            }
        }
        InventoryAction.MOVE_TO_OTHER_INVENTORY -> event.clickedInventory == event.view.bottomInventory
        InventoryAction.UNKNOWN -> true
        else -> false
    }

    private fun hasStock(item: ItemStack?): Boolean =
        item != null && item.type != Material.AIR && item.amount > 0

    private fun protect(event: InventoryInteractEvent, player: Player, inventory: Inventory, deposits: Boolean) {
        val holder = inventory.holder
        if (holder is Player || inventory is PlayerInventory) return

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

        val targets = blocks.map { Target(it.chunk, it.world, it.x, it.y, it.z) } +
            if (holder is Entity) {
                val location = holder.location
                val world = location.world ?: return
                listOf(Target(location.chunk, world, location.blockX, location.blockY, location.blockZ))
            } else if (blocks.isEmpty()) {
                // Physical workstation menus such as anvils can have no InventoryHolder.
                // Their location remains available even after the opener leaves the parcel.
                val location = inventory.location ?: return
                val world = location.world ?: return
                listOf(Target(location.chunk, world, location.blockX, location.blockY, location.blockZ))
            } else emptyList()

        if (targets.isEmpty()) return
        val embassyAction = if (blocks.isEmpty() && holder !is Entity) ClaimAction.INTERACT
            else ClaimAction.CONTAINER

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

        if (blocks.size > 1 && blocks.any { first ->
                blocks.any { second -> embassyBoundary.crosses(
                    embassyBoundary.point(first), embassyBoundary.point(second)
                ) }
            } && !(mfPlayer.isBypassEnabled && player.hasPermission("mf.bypass"))) {
            event.isCancelled = true
            player.sendMessage("${RED}A chest cannot be used across an embassy boundary.")
            return
        }

        // Deny if ANY half is protected: for a chest straddling a claim boundary, permission to
        // reach one half is not permission to empty the other.
        for (target in targets) {
            val claim = claimService.getClaim(target.chunk)
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

            // Retrieval remains available during clearing/passage, but a guest cannot use that
            // window to restock a parcel that the host is recovering.
            if (deposits &&
                plugin.services.embassyService.isParcelProtectionActive(claim.worldId, claim.x, claim.z) &&
                !(mfPlayer.isBypassEnabled && player.hasPermission("mf.bypass"))) {
                val status = plugin.services.embassyService.getAt(claim.worldId, claim.x, claim.z)?.status
                if (status == MfEmbassyStatus.CLEARING || status == MfEmbassyStatus.CONQUEST_PASSAGE) {
                    event.isCancelled = true
                    player.sendMessage("${RED}Only withdrawal is allowed during embassy clearing.")
                    return
                }
            }

            // Evaluate each half independently. A double chest crossing an embassy boundary may
            // be opened only when every protected half permits this player.
            when (plugin.services.embassyService.access(mfPlayer.id, claim, embassyAction)) {
                EmbassyAccessDecision.GRANT -> continue
                EmbassyAccessDecision.DENY -> {
                    if (mfPlayer.isBypassEnabled && player.hasPermission("mf.bypass")) {
                        player.sendMessage("$RED${plugin.language["FactionTerritoryProtectionBypassed"]}")
                        continue
                    }
                    event.isCancelled = true
                    player.sendMessage("$RED${plugin.language["CannotInteractWithInventoryInFactionTerritory", claimFaction.name]}")
                    return
                }
                EmbassyAccessDecision.NONE -> Unit
            }
            // The override is asked about the BLOCK's position, not the player's. Passing the
            // player's coordinates let reach carry a grant across a claim boundary into a faction
            // that never consented to it.
            if (!claimService.isInteractionAllowed(mfPlayer.id, claim) &&
                !claimService.isOverridden(
                        mfPlayer.id,
                        target.world,
                        target.x,
                        target.y,
                        target.z,
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
