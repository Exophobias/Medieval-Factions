package com.dansplugins.factionsystem.api.event

import com.dansplugins.factionsystem.api.FactionId
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.UUID

/**
 * Fired (on the main thread) after a player has stopped being a member of a faction — whether they
 * left voluntarily or were kicked. Part of the stable API.
 *
 * **Why there is no separate "kicked" event.** MedievalFactions emits *both* `FactionKickEvent` and
 * `FactionLeaveEvent` for a single kick: the kick command fires the former, then saves the faction,
 * and the save diffs the member list and fires the latter for each removed member. A stable API that
 * mirrored both 1:1 would therefore double-fire on every kick, which is exactly the kind of trap this
 * API layer exists to absorb. So this event is bridged from the *leave* event only, which covers both
 * paths exactly once.
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
