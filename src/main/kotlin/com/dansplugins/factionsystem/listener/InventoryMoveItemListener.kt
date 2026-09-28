package com.dansplugins.factionsystem.listener

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.area.MfBlockPosition
import org.bukkit.block.BlockState
import org.bukkit.block.Chest
import org.bukkit.block.DoubleChest
import org.bukkit.entity.Entity
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryPickupItemEvent
import org.bukkit.inventory.BlockInventoryHolder
import org.bukkit.inventory.InventoryHolder

class InventoryMoveItemListener(private val plugin: MedievalFactions) : Listener {
    private val embassyBoundary = EmbassyBoundary(plugin)

    @EventHandler
    fun onInventoryMoveItem(event: InventoryMoveItemEvent) {
        // Stop hoppers from taking items from or putting items into locked blocks.
        val lockService = plugin.services.lockService

        // Check source inventory (where items are being taken from)
        val sourceInventoryHolder = event.source.holder
        val sourceBlocksToCheck = when (sourceInventoryHolder) {
            is BlockInventoryHolder -> listOf(sourceInventoryHolder.block)
            is DoubleChest -> {
                val left = sourceInventoryHolder.leftSide as? Chest
                val right = sourceInventoryHolder.rightSide as? Chest
                listOfNotNull(left?.block, right?.block)
            }
            else -> emptyList()
        }

        // Check if any of the source blocks are locked
        for (block in sourceBlocksToCheck) {
            val lockedBlock = lockService.getLockedBlock(MfBlockPosition.fromBukkitBlock(block))
            if (lockedBlock != null) {
                event.isCancelled = true
                return
            }
        }

        // Check destination inventory (where items are being put into)
        val destinationInventoryHolder = event.destination.holder
        val destinationBlocksToCheck = when (destinationInventoryHolder) {
            is BlockInventoryHolder -> listOf(destinationInventoryHolder.block)
            is DoubleChest -> {
                val left = destinationInventoryHolder.leftSide as? Chest
                val right = destinationInventoryHolder.rightSide as? Chest
                listOfNotNull(left?.block, right?.block)
            }
            else -> emptyList()
        }

        // Check if any of the destination blocks are locked
        for (block in destinationBlocksToCheck) {
            val lockedBlock = lockService.getLockedBlock(MfBlockPosition.fromBukkitBlock(block))
            if (lockedBlock != null) {
                event.isCancelled = true
                return
            }
        }

        // A hopper or chest vehicle may move stock without any player protection event. Compare
        // every physical half of both inventories; a double chest straddling the parcel edge is a
        // boundary crossing even when the hopper itself sits on only one side.
        val sourcePoints = points(sourceInventoryHolder)
            ?: event.source.location?.let { EmbassyBoundary.Point.of(it)?.let(::listOf) }
        val destinationPoints = points(destinationInventoryHolder)
            ?: event.destination.location?.let { EmbassyBoundary.Point.of(it)?.let(::listOf) }
        // Bukkit provides no player identity for a hopper pulse. Disable item automation touching
        // a live parcel so the host cannot remotely empty or restock it during clearing.
        if (sourcePoints?.any(embassyBoundary::isEmbassy) == true ||
            destinationPoints?.any(embassyBoundary::isEmbassy) == true ||
            embassyBoundary.crosses(sourcePoints, destinationPoints)
        ) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onInventoryPickupItem(event: InventoryPickupItemEvent) {
        val source = EmbassyBoundary.Point.of(event.item.location)?.let(::listOf)
        val destination = points(event.inventory.holder)
            ?: event.inventory.location?.let { EmbassyBoundary.Point.of(it)?.let(::listOf) }
        if (destination?.any(embassyBoundary::isEmbassy) == true ||
            embassyBoundary.crosses(source, destination)
        ) {
                event.isCancelled = true
            }
    }

    private fun points(holder: InventoryHolder?): List<EmbassyBoundary.Point>? = when (holder) {
        is DoubleChest -> {
            val left = points(holder.leftSide)
            val right = points(holder.rightSide)
            if (left == null || right == null) null else left + right
        }
        is BlockInventoryHolder -> listOf(EmbassyBoundary.Point.of(holder.block))
        is BlockState -> listOf(EmbassyBoundary.Point.of(holder.block))
        is Entity -> EmbassyBoundary.Point.of(holder.location)?.let(::listOf)
        else -> null
    }
}
