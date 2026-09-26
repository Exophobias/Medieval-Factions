package com.dansplugins.factionsystem.api.event

import com.dansplugins.factionsystem.api.FactionId
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * Fired inline before a faction is deleted, including deletion through an atomic member transfer.
 * Cancellation leaves the faction and its claims in place. The event may be asynchronous, so
 * handlers must decide from thread-safe state without loading a Bukkit world.
 */
class FactionDisbandAttemptEvent(val faction: FactionId, isAsync: Boolean) : Event(isAsync), Cancellable {

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
