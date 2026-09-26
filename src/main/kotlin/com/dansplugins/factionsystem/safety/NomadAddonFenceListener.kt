package com.dansplugins.factionsystem.safety

import com.dansplugins.factionsystem.MedievalFactions
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.ServerLoadEvent

/** Checks again after dependency-ordered plugin enable, and on later addon disable. */
class NomadAddonFenceListener(private val plugin: MedievalFactions) : Listener {
    @EventHandler
    fun onServerLoad(@Suppress("UNUSED_PARAMETER") event: ServerLoadEvent) {
        plugin.verifyNomadAddonAfterEnable()
    }

    @EventHandler
    fun onPluginDisable(event: PluginDisableEvent) {
        if (event.plugin.name == "PatriamNomads") plugin.onNomadAddonDisabled()
    }
}
