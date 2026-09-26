package com.dansplugins.factionsystem.listener

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.faction.AdminFactionProtection
import com.dansplugins.factionsystem.utils.MfHostileMobChecker
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent

class CreatureSpawnListener(private val plugin: MedievalFactions) : Listener {

    @EventHandler
    fun onCreatureSpawn(event: CreatureSpawnEvent) {
        if (AdminFactionProtectionChecks.denies(plugin, event.location, AdminFactionProtection.MOB_SPAWNING)) {
            event.isCancelled = true
            return
        }
        if (plugin.config.getBoolean("factions.mobsSpawnInFactionTerritory")) {
            return
        }

        if (!MfHostileMobChecker.isHostileMob(event.entity)) {
            return
        }

        val spawnReasonsToAllow = plugin.config.getStringList("factions.allowedMobSpawnReasons")
            .mapNotNull {
                try {
                    return@mapNotNull CreatureSpawnEvent.SpawnReason.valueOf(it)
                } catch (exception: IllegalArgumentException) {
                    return@mapNotNull null
                }
            }
        if (spawnReasonsToAllow.contains(event.spawnReason)) {
            return
        }

        val claimService = plugin.services.claimService
        if (claimService.getClaim(event.location.chunk) == null) {
            return
        }

        event.isCancelled = true
    }
}
