package com.dansplugins.factionsystem.api.impl

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.FactionId
import com.dansplugins.factionsystem.api.event.FactionCreateEvent
import com.dansplugins.factionsystem.api.event.FactionDisbandedEvent
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import java.util.UUID
import com.dansplugins.factionsystem.event.faction.FactionCreateEvent as MfFactionCreateEvent
import com.dansplugins.factionsystem.event.faction.FactionDeletedEvent as MfFactionDeletedEvent

/**
 * Bridges MedievalFactions' internal faction lifecycle events to the stable API equivalents, so
 * consumers never register listeners against MF's internal event classes.
 *
 * Two shapes live here:
 * - **The create gate** — [FactionCreateEvent] is cancellable and fired *inline*, so a veto reaches MF
 *   before it persists the faction.
 * - **The notification** — [FactionDisbandedEvent] is past-tense, non-cancellable, fired at MONITOR with
 *   `ignoreCancelled = true`, and re-fired on the next tick so consumers get a main-thread guarantee.
 *
 * The create bridge also does the awkward part on the consumer's behalf, described below.
 *
 * It also does the awkward part on the consumer's behalf: MF represents the founder as the faction's
 * first member, whose player id is a Kotlin value class erased to a String on the Java side. This
 * resolves that to a Bukkit UUID, and to `null` when the faction has no members (MF admin tooling can
 * create a leaderless faction).
 *
 * The API event is fired inline rather than scheduled, because a cancellation must be visible to MF
 * before it continues persisting the faction.
 */
class ApiFactionLifecycleListener(private val plugin: MedievalFactions) : Listener {

    // HIGHEST + ignoreCancelled, deliberately: the API event is fired LATE in the internal event's
    // chain so every other cancel-capable handler has already run. A gate listening on the API event
    // therefore never acts on a creation that something else was going to block anyway — which
    // matters when acting has a side effect, such as spending a one-time founding grant.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onFactionCreate(event: MfFactionCreateEvent) {
        val faction = event.faction
        val creatorId = faction.members.firstOrNull()?.playerId?.value?.let { value ->
            runCatching { UUID.fromString(value) }.getOrNull()
        }
        val apiEvent = FactionCreateEvent(
            FactionId(faction.id.value),
            faction.name,
            creatorId,
            event.isAsynchronous
        )
        plugin.server.pluginManager.callEvent(apiEvent)
        if (apiEvent.isCancelled) {
            event.setCancelled(true)
        }
    }

    // The handlers below are notifications, not gates, so they are the mirror image of the
    // create bridge above: MONITOR + ignoreCancelled so they only report changes that actually took
    // effect, and re-fired on the next tick so consumers get a main-thread guarantee (MF fires these
    // asynchronously from the /f command handlers).

    @EventHandler(priority = EventPriority.MONITOR)
    fun onFactionDeleted(event: MfFactionDeletedEvent) {
        fireNextTick(FactionDisbandedEvent(FactionId(event.factionId.value)))
    }

    private fun fireNextTick(event: Event) {
        plugin.server.scheduler.runTask(
            plugin,
            Runnable { plugin.server.pluginManager.callEvent(event) }
        )
    }
}
