package com.dansplugins.factionsystem.api.event

import com.dansplugins.factionsystem.api.FactionId
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * A faction is about to voluntarily release all its claims, and a consumer may refuse.
 *
 * Fired inline from the bulk unclaim service before any database or claim-index mutation. The
 * ordinary `/f unclaimall` command runs on a worker, so this event may be asynchronous. It is one
 * event for the whole operation: no per-chunk event is emitted by a successful bulk unclaim.
 * Faction deletion has its own lifecycle and cascades its claims without firing this veto.
 */
class FactionUnclaimAllAttemptEvent(
    val faction: FactionId,
    isAsync: Boolean
) : Event(isAsync), Cancellable {

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
