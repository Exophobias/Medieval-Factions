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
 * [proposedMemberCount] counts the complete roster proposed by the same save, so a multi-member
 * transfer can be rejected before any member is admitted. A negative value means unavailable and
 * must be rejected by a policy that needs the count.
 * Initial members of a newly created faction do not fire this event. Inspect
 * [FactionCreateEvent.memberIdValues] to validate the complete initial roster instead.
 *
 * Fired inline with MedievalFactions' save gate, so it may be asynchronous. Check
 * [isAsynchronous] before accessing the Bukkit API.
 */
class FactionJoinEvent(
    val factionId: FactionId,
    val playerIdValue: String,
    val proposedMemberCount: Int,
    isAsync: Boolean
) : Event(isAsync), Cancellable {

    /** Preserve existing Java/Kotlin callers; a negative count means unavailable. */
    constructor(factionId: FactionId, playerIdValue: String, isAsync: Boolean) :
        this(factionId, playerIdValue, -1, isAsync)

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
