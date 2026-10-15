package com.dansplugins.factionsystem.listener

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.ClaimAction
import com.dansplugins.factionsystem.api.MercenaryCombatProvider.Decision
import com.dansplugins.factionsystem.claim.EmbassyAccessDecision
import com.dansplugins.factionsystem.player.MfPlayer
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType
import com.dansplugins.factionsystem.utils.MfHostileMobChecker
import org.bukkit.ChatColor
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.Hanging
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityPlaceEvent
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.hanging.HangingBreakEvent
import org.bukkit.event.hanging.HangingPlaceEvent
import org.bukkit.inventory.InventoryHolder
import java.util.WeakHashMap

class EntityDamageByEntityListener(
    private val plugin: MedievalFactions,
    private val nanoClock: () -> Long = System::nanoTime
) : Listener {

    // A held attack button or a volley of arrows is refused once per hit, so tell each player at most
    // once per interval. Players are weak keys, so nothing outlives a session.
    private val lastRefusalMessage = WeakHashMap<Player, Long>()

    @EventHandler
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        if (AdminFactionProtectionChecks.deniesDamage(plugin, event)) {
            event.isCancelled = true
            return
        }
        val damaged = event.entity
        val damager = event.damager
        val damagerPlayer: Player? = (event.damageSource?.causingEntity as? Player) ?: when (damager) {
            is Player -> damager
            is Projectile -> damager.shooter as? Player
            else -> null
        }
        // Dispensers can fire across the parcel border without a player damager. Deny their
        // projectiles against parcel property while the peaceful charter is in force.
        if (damagerPlayer == null && damager is Projectile && isParcelProperty(damaged) &&
            isPeacefulEmbassy(damaged)
        ) {
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
                                plugin.notifyTerritoryBypass(damagerPlayer, damagerMfPlayer)
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
                    plugin.notifyTerritoryBypass(damagerPlayer, damagerMfPlayer)
                    return
                }
                event.isCancelled = true
                return
            }
            if (!com.dansplugins.factionsystem.siege.DefaultNativeSiegeAuthority(plugin)
                    .playerPolicy(damagerPlayer, damaged)
            ) {
                event.isCancelled = true
                tellRefused(damagerPlayer, "CannotAttackPlayerProtected")
            }
        }
    }

    @EventHandler
    fun onHangingBreak(event: HangingBreakEvent) {
        // Leave explicit explosion damage with MF's existing explosion protection.
        if (event.cause == HangingBreakEvent.RemoveCause.EXPLOSION ||
            !isPeacefulEmbassy(event.entity)
        ) {
                return
            }
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
            EmbassyAccessDecision.GRANT
        ) {
                event.isCancelled = true
            }
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
        return plugin.services.embassyService.isParcelProtectionActive(world.uid, location.blockX shr 4, location.blockZ shr 4)
    }

    private fun tellRefused(player: Player, key: String, vararg params: String) {
        val now = nanoClock()
        val last = lastRefusalMessage[player]
        if (last != null && now - last < REFUSAL_MESSAGE_INTERVAL_NANOS) return
        lastRefusalMessage[player] = now
        val message = plugin.language.get(key, *params)
        player.sendMessage("${ChatColor.RED}$message")
    }

    companion object {
        const val REFUSAL_MESSAGE_INTERVAL_NANOS = 2_000_000_000L
    }
}
