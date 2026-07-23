package com.dansplugins.factionsystem.api.event

import com.dansplugins.factionsystem.api.FactionId
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * Fired (on the main thread) when a war begins between two factions. Part of the stable API: unlike
 * MedievalFactions' internal relationship events, this carries both faction identities and a
 * definite "war" meaning.
 */
class FactionWarStartedEvent(
    val faction: FactionId,
    val otherFaction: FactionId
) : Event() {

    override fun getHandlers(): HandlerList = handlerList

    companion object {
        @JvmStatic
        private val handlerList = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = handlerList
    }
}
