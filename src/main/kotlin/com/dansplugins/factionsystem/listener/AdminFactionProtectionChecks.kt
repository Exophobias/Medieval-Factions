package com.dansplugins.factionsystem.listener

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.faction.AdminFactionProtection
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent

/** Resolve protection from the victim's or spawn's claimed location, not faction membership. */
internal object AdminFactionProtectionChecks {
    fun denies(plugin: MedievalFactions, location: Location?, setting: AdminFactionProtection): Boolean {
        if (location == null) return false
        val claim = plugin.services.claimService.getClaim(location.chunk) ?: return false
        val faction = plugin.services.factionService.getFaction(claim.factionId) ?: return false
        return !setting.isAllowed(faction)
    }

    fun deniesDamage(plugin: MedievalFactions, event: EntityDamageEvent): Boolean {
        val isExplosion = event.cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION ||
            event.cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
        val victim = event.entity as? Player
        if (!isExplosion && victim == null) return false
        val location = event.entity.location ?: return false
        val claim = plugin.services.claimService.getClaim(location.chunk) ?: return false
        val faction = plugin.services.factionService.getFaction(claim.factionId) ?: return false
        if (!faction.adminLeaderless) return false
        if (isExplosion && !AdminFactionProtection.EXPLOSIONS.isAllowed(faction)) return true
        if (victim == null) return false
        if (!AdminFactionProtection.PLAYER_DAMAGE.isAllowed(faction)) return true
        return event is EntityDamageByEntityEvent &&
            attackerIsPlayer(event.damager) &&
            !AdminFactionProtection.PVP.isAllowed(faction)
    }

    private fun attackerIsPlayer(damager: Entity): Boolean = when (damager) {
        is Player -> true
        is Projectile -> damager.shooter is Player
        else -> false
    }
}
