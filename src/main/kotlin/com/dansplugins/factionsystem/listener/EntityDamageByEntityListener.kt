package com.dansplugins.factionsystem.listener

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.ClaimAction
import com.dansplugins.factionsystem.api.MercenaryCombatProvider.Decision
import com.dansplugins.factionsystem.claim.EmbassyAccessDecision
import com.dansplugins.factionsystem.player.MfPlayer
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType
import com.dansplugins.factionsystem.utils.MfHostileMobChecker
import org.bukkit.ChatColor
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.Hanging
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityPlaceEvent
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.hanging.HangingBreakEvent
import org.bukkit.event.hanging.HangingPlaceEvent
import org.bukkit.inventory.InventoryHolder

class EntityDamageByEntityListener(private val plugin: MedievalFactions) : Listener {

    @EventHandler
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        if (AdminFactionProtectionChecks.deniesDamage(plugin, event)) {
            event.isCancelled = true
            return
        }
        val damaged = event.entity
        val damager = event.damager
        val damagerPlayer: Player? = when (damager) {
            is Player -> damager
            is Projectile -> damager.shooter as? Player
            else -> null
        }
        // Dispensers can fire across the parcel border without a player damager. Deny their
        // projectiles against parcel property while the peaceful charter is in force.
        if (damagerPlayer == null && damager is Projectile && isParcelProperty(damaged) &&
            isPeacefulEmbassy(damaged)) {
            event.isCancelled = true
            return
        }
        if (damagerPlayer != null) {
            val playerService = plugin.services.playerService
            val factionService = plugin.services.factionService
            val duelService = plugin.services.duelService
            val damagerMfPlayer = playerService.getPlayer(damagerPlayer) ?: MfPlayer(plugin, damagerPlayer)
            val damagerFaction = factionService.getFaction(damagerMfPlayer.id)
            if (damaged !is Player) {
                val claimService = plugin.services.claimService
                val claim = claimService.getClaim(damaged.location.chunk) ?: return
                // Armor stands, hanging decorations, storage vehicles and inventory mounts
                // are parcel property.
                // A player can strike them from outside the chunk, so entry denial alone is not
                // enough. Keep PvP, other mob combat and explosions on their ordinary MF paths.
                if (isParcelProperty(damaged) && isPeacefulEmbassy(damaged)) {
                    when (plugin.services.embassyService.access(damagerMfPlayer.id, claim, ClaimAction.BREAK)) {
                        EmbassyAccessDecision.GRANT -> return
                        EmbassyAccessDecision.DENY -> {
                            if (damagerMfPlayer.isBypassEnabled && damagerPlayer.hasPermission("mf.bypass")) {
                                damagerPlayer.sendMessage("${ChatColor.RED}${plugin.language["FactionTerritoryProtectionBypassed"]}")
                            } else {
                                event.isCancelled = true
                            }
                            return
                        }
                        EmbassyAccessDecision.NONE -> Unit
                    }
                }
                val damagedFaction = factionService.getFaction(claim.factionId) ?: return
                if (!damagedFaction.flags[plugin.flags.enableMobProtection]) return
                if (MfHostileMobChecker.isHostileMob(damaged)) return
                if (claimService.isInteractionAllowed(damagerMfPlayer.id, claim)) return
                if (claimService.isOverridden(
                        damagerMfPlayer.id,
                        damaged.world,
                        damaged.location.blockX,
                        damaged.location.blockY,
                        damaged.location.blockZ,
                        ClaimAction.DAMAGE
                    )
                ) {
                    return
                }
                if (damagerMfPlayer.isBypassEnabled && damagerPlayer.hasPermission("mf.bypass")) {
                    damagerPlayer.sendMessage("${ChatColor.RED}${plugin.language["FactionTerritoryProtectionBypassed"]}")
                    return
                }
                event.isCancelled = true
                return
            }
            val damagedMfPlayer = playerService.getPlayer(damaged) ?: MfPlayer(plugin, damaged)
            val damagerDuel = duelService.getDuel(damagerMfPlayer.id)
            val damagedDuel = duelService.getDuel(damagedMfPlayer.id)
            if (damagerDuel != null && damagedDuel != null && damagerDuel.id == damagedDuel.id) {
                return
            }
            if (damagerDuel != null || damagedDuel != null) {
                event.isCancelled = true
                return
            }
            val damagedFaction = factionService.getFaction(damagedMfPlayer.id)
            if (damagerFaction == null || damagedFaction == null) {
                if (!plugin.config.getBoolean("pvp.enabledForFactionlessPlayers")) {
                    event.isCancelled = true
                }
                return
            }
            if (damagerFaction.id == damagedFaction.id) {
                if (!plugin.config.getBoolean("pvp.friendlyFire") && !damagerFaction.flags[plugin.flags.allowFriendlyFire]) {
                    event.isCancelled = true
                }
                return
            }
            when (MercenaryCombatGate.decide(plugin, damagerPlayer.uniqueId, damaged.uniqueId)) {
                Decision.ALLOW -> return
                Decision.DENY -> {
                    event.isCancelled = true
                    return
                }
                Decision.ABSTAIN -> Unit
            }
            val relationshipService = plugin.services.factionRelationshipService
            val relationships = relationshipService.getRelationships(damagerFaction.id, damagedFaction.id)
            val reverseRelationships = relationshipService.getRelationships(damagedFaction.id, damagerFaction.id)
            if ((relationships + reverseRelationships).none { it.type == MfFactionRelationshipType.AT_WAR }) {
                if (plugin.config.getBoolean("pvp.warRequiredForPlayersOfDifferentFactions")) {
                    event.isCancelled = true
                }
                return
            }
        }
    }

    @EventHandler
    fun onHangingBreak(event: HangingBreakEvent) {
        // Leave explicit explosion damage with MF's existing explosion protection.
        if (event.cause == HangingBreakEvent.RemoveCause.EXPLOSION ||
            !isPeacefulEmbassy(event.entity)) return
        val remover = (event as? HangingBreakByEntityEvent)?.remover
        val player = when (remover) {
            is Player -> remover
            is Projectile -> remover.shooter as? Player
            else -> null
        }
        if (player == null) {
            event.isCancelled = true
            return
        }
        val mfPlayer = plugin.services.playerService.getPlayer(player) ?: MfPlayer(plugin, player)
        if (mfPlayer.isBypassEnabled && player.hasPermission("mf.bypass")) return
        val claim = plugin.services.claimService.getClaim(event.entity.location.chunk)
        if (claim == null || plugin.services.embassyService.access(mfPlayer.id, claim, ClaimAction.BREAK) !=
            EmbassyAccessDecision.GRANT) event.isCancelled = true
    }

    @EventHandler
    fun onHangingPlace(event: HangingPlaceEvent) {
        if (deniesParcelPlacement(event.player, event.entity)) event.isCancelled = true
    }

    @EventHandler
    fun onEntityPlace(event: EntityPlaceEvent) {
        if (deniesParcelPlacement(event.player, event.entity)) event.isCancelled = true
    }

    private fun deniesParcelPlacement(player: Player?, entity: Entity): Boolean {
        if (!isPeacefulEmbassy(entity)) return false
        if (player == null) return true
        val mfPlayer = plugin.services.playerService.getPlayer(player) ?: MfPlayer(plugin, player)
        if (mfPlayer.isBypassEnabled && player.hasPermission("mf.bypass")) return false
        val claim = plugin.services.claimService.getClaim(entity.location.chunk) ?: return true
        return plugin.services.embassyService.access(mfPlayer.id, claim, ClaimAction.BUILD) !=
            EmbassyAccessDecision.GRANT
    }

    private fun isParcelProperty(entity: Entity): Boolean =
        entity is ArmorStand || entity is Hanging || (entity !is Player && entity is InventoryHolder)

    private fun isPeacefulEmbassy(entity: Entity): Boolean {
        val location = entity.location
        val world = location.world ?: return false
        return plugin.services.embassyService.isParcelProtectionActive(
            world.uid, location.blockX shr 4, location.blockZ shr 4)
    }
}
