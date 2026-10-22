package com.dansplugins.factionsystem.safety

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.PluginEnableEvent

/** Native lifecycle listener: disable events arrive before Paper clears isEnabled or calls onDisable. */
class PatriamGovernanceGuardListener(private val guard: PatriamGovernanceGuard) : Listener {
    @EventHandler(priority = EventPriority.LOWEST)
    fun onPluginDisable(event: PluginDisableEvent) {
        if (event.plugin.name == "PatriamMFAddon") guard.providerStopping()
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onPluginEnable(event: PluginEnableEvent) {
        if (event.plugin.name == "PatriamMFAddon") guard.providerEnabled()
    }
}
