package com.dansplugins.factionsystem.listener

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.ClaimAction
import com.dansplugins.factionsystem.area.MfBlockPosition
import com.dansplugins.factionsystem.claim.EmbassyAccessDecision
import com.dansplugins.factionsystem.claim.MfEmbassyStatus
import com.dansplugins.factionsystem.player.MfPlayer
import com.dansplugins.factionsystem.player.MfPlayerId
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace.DOWN
import org.bukkit.block.BlockFace.UP
import org.bukkit.block.Chest
import org.bukkit.block.DoubleChest
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.Openable
import org.bukkit.block.data.type.Door
import org.bukkit.block.data.type.TrapDoor
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.inventory.InventoryHolder
import org.bukkit.block.data.type.Gate as FenceGateData

/** Main-thread read policy shared by native interaction enforcement and public admission reads. */
class BlockInteractionPolicy(private val plugin: MedievalFactions) {
    enum class Denial { UNKNOWN_PLAYER, EMBASSY_BOUNDARY, EMBASSY, LOCKED, WILDERNESS, TERRITORY }
    data class Decision(
        val denial: Denial? = null,
        val ownerName: String = "",
        val lockOwner: MfPlayerId? = null,
        val lockBypass: Boolean = false,
        val territoryBypass: MfPlayer? = null,
        val restrictHeldItem: Boolean = false
    ) {
        val allowed: Boolean get() = denial == null
    }

    /** Never registers a player, changes a mode, sends chat, schedules work, or mutates a block. */
    fun evaluate(player: Player, block: Block, action: Action, heldMaterial: Material?): Decision {
        val actor = plugin.services.playerService.getPlayer(player)
            ?: return Decision(Denial.UNKNOWN_PLAYER)
        val bypass = actor.isBypassEnabled && player.hasPermission("mf.bypass")
        if (crossesEmbassyChestBoundary(block) && !bypass) return Decision(Denial.EMBASSY_BOUNDARY)

        val claims = plugin.services.claimService
        val claim = claims.getClaim(block.chunk)
        val embassies = plugin.services.embassyService
        val embassyAccess = if (claim != null && embassies.hasActiveOrClearingEmbassy(claim.worldId, claim.x, claim.z)) {
            embassies.access(actor.id, claim, overrideActionFor(block))
        } else EmbassyAccessDecision.NONE
        if (embassyAccess == EmbassyAccessDecision.DENY && !bypass) {
            return Decision(Denial.EMBASSY, claim?.let { plugin.services.factionService.getFaction(it.factionId)?.name ?: it.factionId.value } ?: "")
        }
        val restrictHeldItem = embassyAccess == EmbassyAccessDecision.GRANT && action == Action.RIGHT_CLICK_BLOCK && claim != null &&
            embassies.getAt(claim.worldId, claim.x, claim.z)?.status in setOf(MfEmbassyStatus.CLEARING, MfEmbassyStatus.CONQUEST_PASSAGE)

        val blockData = block.blockData
        val holder = (block.state as? Chest)?.inventory?.holder
        val parts = if (blockData is Bisected) {
            if (blockData.half == Bisected.Half.BOTTOM) listOf(block, block.getRelative(UP))
            else listOf(block, block.getRelative(DOWN))
        } else if (holder is DoubleChest) {
            listOfNotNull((holder.leftSide as? Chest)?.block, (holder.rightSide as? Chest)?.block)
        } else listOf(block)
        val lock = parts.mapNotNull { plugin.services.lockService.getLockedBlock(MfBlockPosition.fromBukkitBlock(it)) }.firstOrNull()
        if (lock != null) {
            if (player.uniqueId.toString() in (lock.accessors + lock.playerId).map(MfPlayerId::value)) {
                return Decision(restrictHeldItem = restrictHeldItem)
            }
            return if (bypass || hasLockBypassPermission(actor)) Decision(lockOwner = lock.playerId, lockBypass = true, restrictHeldItem = restrictHeldItem)
            else Decision(Denial.LOCKED, lockOwner = lock.playerId, restrictHeldItem = restrictHeldItem)
        }
        if (plugin.config.getBoolean("factions.nonMembersCanInteractWithDoors") &&
            (blockData is Door || blockData is TrapDoor || blockData is FenceGateData)) {
            return Decision(restrictHeldItem = restrictHeldItem)
        }
        if (claim == null) {
            return if (plugin.config.getBoolean("wilderness.interaction.prevent", false)) Decision(Denial.WILDERNESS)
            else Decision()
        }
        val claimFaction = plugin.services.factionService.getFaction(claim.factionId) ?: return Decision(restrictHeldItem = restrictHeldItem)
        if (embassyAccess != EmbassyAccessDecision.NONE || claims.isInteractionAllowed(actor.id, claim) ||
            claims.isOverridden(actor.id, block.world, block.x, block.y, block.z, overrideActionFor(block))) {
            return Decision(restrictHeldItem = restrictHeldItem)
        }
        if (bypass) return Decision(territoryBypass = actor, restrictHeldItem = restrictHeldItem)
        if (action == Action.RIGHT_CLICK_BLOCK && heldMaterial == Material.LADDER && block.type.isSolid && !block.type.isInteractable &&
            claims.isWartimeLadderPlacementAllowed(actor.id, claim, true)) return Decision(restrictHeldItem = restrictHeldItem)
        val wartime = when (action) {
            Action.LEFT_CLICK_BLOCK -> claims.isWartimeBreakableBlock(actor.id, claim, block.type)
            Action.RIGHT_CLICK_BLOCK -> if (block.type.isInteractable) claims.isWartimeInteractableBlock(actor.id, claim, block.type)
                else heldMaterial != null && claims.isWartimePlaceableBlock(actor.id, claim, heldMaterial)
            else -> false
        }
        return if (wartime) Decision(restrictHeldItem = restrictHeldItem)
        else Decision(Denial.TERRITORY, claimFaction.name ?: claim.factionId.value, restrictHeldItem = restrictHeldItem)
    }

    fun hasLockBypassPermission(actor: MfPlayer): Boolean {
        val faction = plugin.services.factionService.getFaction(actor.id) ?: return false
        val role = faction.getRole(actor.id) ?: return false
        return role.hasPermission(faction, plugin.factionPermissions.bypassLocks)
    }

    fun crossesEmbassyChestBoundary(block: Block): Boolean {
        val holder = (block.state as? Chest)?.inventory?.holder as? DoubleChest ?: return false
        val left = (holder.leftSide as? Chest)?.block ?: return true
        val right = (holder.rightSide as? Chest)?.block ?: return true
        val boundary = EmbassyBoundary(plugin)
        return boundary.crosses(boundary.point(left), boundary.point(right))
    }

    private fun overrideActionFor(block: Block): ClaimAction = when {
        block.state is InventoryHolder -> ClaimAction.CONTAINER
        block.blockData is Openable -> ClaimAction.DOOR
        else -> ClaimAction.INTERACT
    }
}
