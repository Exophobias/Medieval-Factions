package com.dansplugins.factionsystem.listener

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.faction.AdminFactionProtection
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.event.block.BlockSpreadEvent

/** Keeps natural fire from starting or spreading into protected admin territory. */
class AdminFactionFireListener(private val plugin: MedievalFactions) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockIgnite(event: BlockIgniteEvent) {
        if (event.cause !in NATURAL_IGNITE_CAUSES) return
        if (AdminFactionProtectionChecks.denies(plugin, event.block.location, AdminFactionProtection.FIRE_SPREAD)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockSpread(event: BlockSpreadEvent) {
        if (event.source.type !in FIRE_TYPES && event.newState.type !in FIRE_TYPES) return
        if (AdminFactionProtectionChecks.denies(plugin, event.block.location, AdminFactionProtection.FIRE_SPREAD)) {
            event.isCancelled = true
        }
    }

    private companion object {
        val FIRE_TYPES = setOf(Material.FIRE, Material.SOUL_FIRE)
        val NATURAL_IGNITE_CAUSES = setOf(
            BlockIgniteEvent.IgniteCause.SPREAD,
            BlockIgniteEvent.IgniteCause.LAVA,
            BlockIgniteEvent.IgniteCause.LIGHTNING
        )
    }
}
