package com.dansplugins.factionsystem.api.event

import com.dansplugins.factionsystem.api.FactionId
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * Fired inline before a new faction relationship is written. Consumers can veto hierarchy and
 * alliance changes regardless of the command or API path. One reciprocal relationship may produce
 * two events, one for each row. New war rows also fire [FactionWarStartEvent] first.
 *
 * The event can be asynchronous. Handlers must use thread-safe data and return promptly.
 */
class FactionRelationshipCreateAttemptEvent(
    val faction: FactionId,
    val targetFaction: FactionId,
    val initiatingFaction: FactionId,
    val relationshipType: Type,
    isAsync: Boolean
) : Event(isAsync), Cancellable {

    enum class Type { ALLY, AT_WAR, VASSAL, LIEGE }

    private var cancelled = false

    override fun isCancelled(): Boolean = cancelled

    override fun setCancelled(cancel: Boolean) {
        cancelled = cancel
    }

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
