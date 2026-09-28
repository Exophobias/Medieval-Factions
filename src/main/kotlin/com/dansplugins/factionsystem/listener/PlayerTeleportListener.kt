package com.dansplugins.factionsystem.listener

import com.dansplugins.factionsystem.MedievalFactions
import org.bukkit.ChatColor.RED
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent

class PlayerTeleportListener(private val plugin: MedievalFactions) : Listener {
    private val embassyEntry = EmbassyEntryGuard(plugin)

    // PlayerPortalEvent is a PlayerTeleportEvent and reaches this protection path as well.
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onEmbassyTeleport(event: PlayerTeleportEvent) {
        if (embassyEntry.isRelocating(event.player)) return
        val to = event.to ?: return
        if (!embassyEntry.isEmbassy(to) || !embassyEntry.isDenied(event.player, to)) return
        event.isCancelled = true
        event.player.sendMessage("${RED}Only the guest faction may enter this embassy.")
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onEmbassyRespawn(event: PlayerRespawnEvent) {
        val destination = event.respawnLocation
        if (!embassyEntry.isEmbassy(destination) || !embassyEntry.isDenied(event.player, destination)) return
        event.respawnLocation = embassyEntry.exit(event.player, destination) ?: destination
        event.player.sendMessage("${RED}Only the guest faction may enter this embassy.")
    }

    @EventHandler
    fun onPlayerTeleport(event: PlayerTeleportEvent) {
        val teleportService = plugin.services.teleportService
        teleportService.cancelTeleportation(event.player)
    }
}
