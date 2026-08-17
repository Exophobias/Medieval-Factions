package com.dansplugins.factionsystem.api.event

import com.dansplugins.factionsystem.api.FactionId
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * Fired immediately before MedievalFactions commits one direction of a new war relationship.
 *
 * This is the stable enforcement boundary for plugins that must veto a war regardless of command
 * alias, delayed approval, administrative command, or API caller. A two-direction war can produce
 * this event twice. [initiatingFaction] is the faction whose act caused the relationship; invocation
 * can therefore name a faction outside [faction] and [otherFaction].
 */
class FactionWarStartEvent(
    val faction: FactionId,
    val otherFaction: FactionId,
    val initiatingFaction: FactionId,
    isAsync: Boolean
) : Event(isAsync), Cancellable {

    private var cancelled = false

    override fun isCancelled(): Boolean = cancelled

    override fun setCancelled(cancel: Boolean) {
        cancelled = cancel
    }

    override fun getHandlers(): HandlerList = handlerList

    companion object {
        @JvmStatic
        private val handlerList = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = handlerList
    }
}
