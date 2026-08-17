package com.dansplugins.factionsystem.event.faction

import com.dansplugins.factionsystem.faction.MfFactionId
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/** A faction that has successfully been removed from storage and all live service indexes. */
class FactionDeletedEvent(
    val factionId: MfFactionId,
    isAsync: Boolean
) : Event(isAsync) {

    companion object {
        @JvmStatic private val handlers: HandlerList = HandlerList()

        @JvmStatic fun getHandlerList() = handlers
    }

    override fun getHandlers(): HandlerList = getHandlerList()
}
