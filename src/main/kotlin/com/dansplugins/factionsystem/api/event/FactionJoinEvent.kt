package com.dansplugins.factionsystem.api.event

import com.dansplugins.factionsystem.api.FactionId
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * Fired before a player joins an existing faction. Cancelling this event prevents the join.
 *
 * [playerIdValue] is the raw player ID from MedievalFactions. Consumers must validate it before
 * using it as a Bukkit UUID; an invalid value must not silently become an eligibility bypass.
 * Initial members of a newly created faction do not fire this event. Inspect
 * [FactionCreateEvent.memberIdValues] to validate the complete initial roster instead.
 *
 * Fired inline with MedievalFactions' save gate, so it may be asynchronous. Check
 * [isAsynchronous] before accessing the Bukkit API.
 */
class FactionJoinEvent(
    val factionId: FactionId,
    val playerIdValue: String,
    isAsync: Boolean
) : Event(isAsync), Cancellable {

    private var cancel: Boolean = false

    override fun isCancelled(): Boolean = cancel

    override fun setCancelled(cancel: Boolean) {
        this.cancel = cancel
    }

    override fun getHandlers(): HandlerList = handlerList

    companion object {
        @JvmStatic
        private val handlerList = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = handlerList
    }
}
