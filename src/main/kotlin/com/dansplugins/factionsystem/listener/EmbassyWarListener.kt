package com.dansplugins.factionsystem.listener

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.event.FactionWarEndedEvent
import com.dansplugins.factionsystem.api.event.FactionWarStartedEvent
import com.dansplugins.factionsystem.faction.MfFactionId
import dev.forkhandles.result4k.Failure
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener

/** Persists the retrieval clock as soon as a host and guest enter or leave war. */
class EmbassyWarListener(private val plugin: MedievalFactions) : Listener {
    @EventHandler
    fun onWarStart(event: FactionWarStartedEvent) = update(
        MfFactionId(event.faction.value),
        MfFactionId(event.otherFaction.value),
        true
    )

    @EventHandler
    fun onWarEnd(event: FactionWarEndedEvent) = update(
        MfFactionId(event.faction.value),
        MfFactionId(event.otherFaction.value),
        false
    )

    private fun update(first: MfFactionId, second: MfFactionId, atWar: Boolean) {
        val result = plugin.services.embassyService.onWarStateChanged(first, second, atWar)
        if (result is Failure) {
            plugin.logger.warning("Could not update embassy war clock: ${result.reason.message}")
        }
    }
}
