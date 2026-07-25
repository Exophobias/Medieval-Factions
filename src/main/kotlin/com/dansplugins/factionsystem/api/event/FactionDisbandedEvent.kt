package com.dansplugins.factionsystem.api.event

import com.dansplugins.factionsystem.api.FactionId
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * Fired (on the main thread) after a faction has been disbanded. Part of the stable API.
 *
 * Deliberately **not** cancellable and deliberately past-tense: it is bridged from MedievalFactions'
 * internal disband event at MONITOR priority with `ignoreCancelled = true`, so by the time consumers
 * see it the disband is a settled fact. Use it to clean up data keyed on the faction; use MF's own
 * internal event if you need to veto a disband.
 *
 * **Timing caveat.** MF's internal event may fire asynchronously, so this one is re-fired on the next
 * server tick to give consumers a main-thread guarantee. That means a consumer observes the disband
 * one tick late, and MF's own cleanup (which includes deleting the faction's claims) has already run
 * by then. This matches the behaviour of [FactionWarStartedEvent]/[FactionWarEndedEvent].
 */
class FactionDisbandedEvent(
    val faction: FactionId
) : Event() {

    override fun getHandlers(): HandlerList = handlerList

    companion object {
        @JvmStatic
        private val handlerList = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = handlerList
    }
}
