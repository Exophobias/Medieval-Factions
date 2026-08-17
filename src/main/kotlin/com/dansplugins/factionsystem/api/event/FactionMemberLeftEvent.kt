package com.dansplugins.factionsystem.api.event

import com.dansplugins.factionsystem.api.FactionId
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.UUID

/**
 * Fired (on the main thread) after a player has stopped being a member of a faction — whether they
 * left voluntarily or were kicked. Part of the stable API.
 *
 * **Why there is no separate "kicked" event.** MedievalFactions publishes this once from the
 * committed member-list diff. Both a voluntary leave and a kick end in that same save, so exposing
 * the command's earlier kick gate as a second notification would double-fire.
 *
 * If a consumer ever genuinely needs to distinguish the two, add a reason to this event rather than a
 * second event — one notification per departure is the contract.
 *
 * [playerId] is the departing player's Bukkit UUID. See [FactionDisbandedEvent] for the same
 * next-tick timing caveat.
 */
class FactionMemberLeftEvent(
    val faction: FactionId,
    val playerId: UUID
) : Event() {

    override fun getHandlers(): HandlerList = handlerList

    companion object {
        @JvmStatic
        private val handlerList = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = handlerList
    }
}
