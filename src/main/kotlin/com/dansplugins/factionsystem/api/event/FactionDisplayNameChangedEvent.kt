package com.dansplugins.factionsystem.api.event

import com.dansplugins.factionsystem.api.FactionId
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/** Committed player-facing label change, delivered on the main thread on the next tick. */
class FactionDisplayNameChangedEvent(
    val faction: FactionId,
    val previousDisplayName: String,
    val displayName: String
) : Event() {
    override fun getHandlers(): HandlerList = handlerList

    companion object {
        @JvmStatic
        private val handlerList = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = handlerList
    }
}
