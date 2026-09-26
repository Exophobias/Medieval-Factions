package com.dansplugins.factionsystem.listener

import com.dansplugins.factionsystem.MedievalFactions
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent

class PlayerInteractEntityListener @JvmOverloads constructor(
    plugin: MedievalFactions,
    private val protection: EntityInteractionProtection = EntityInteractionProtection(plugin)
) : Listener {
    @EventHandler(ignoreCancelled = true)
    fun onPlayerInteractEntity(event: PlayerInteractEntityEvent) {
        // The subclass has its own handler list and is handled by the precise-hit listener.
        if (event is PlayerInteractAtEntityEvent) return
        protection.handle(event, "PlayerInteractEntityFailedToSavePlayer")
    }
}
