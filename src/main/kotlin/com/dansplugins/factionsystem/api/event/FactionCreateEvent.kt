package com.dansplugins.factionsystem.api.event

import com.dansplugins.factionsystem.api.FactionId
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.UUID

/**
 * Fired before a faction is created. **Cancelling this event prevents the faction from being created.**
 *
 * Part of the stable API, and deliberately friendlier than MedievalFactions' internal create event:
 * it already resolves the founder. [creatorId] is the Bukkit UUID of the founding player, or `null`
 * when the faction is being created with no members — MF's own admin tooling can produce a leaderless
 * faction, and a gate should normally stand aside in that case rather than cancel.
 *
 * Mirrors MedievalFactions' own event and is fired inline so that cancellation propagates, which means
 * it may be fired **asynchronously**. Check [isAsynchronous] before touching the Bukkit API.
 */
class FactionCreateEvent(
    val factionId: FactionId,
    val factionName: String,
    val creatorId: UUID?,
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
