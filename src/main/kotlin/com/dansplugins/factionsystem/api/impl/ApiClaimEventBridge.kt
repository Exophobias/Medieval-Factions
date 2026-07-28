package com.dansplugins.factionsystem.api.impl

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.FactionId
import com.dansplugins.factionsystem.api.event.ClaimOwnerChangedEvent
import com.dansplugins.factionsystem.faction.MfFactionId
import java.util.UUID

/**
 * Bridges MedievalFactions' claim writes to the stable [ClaimOwnerChangedEvent].
 *
 * ## Why this is called from the claim path rather than written as a Bukkit listener
 *
 * The other bridges in this package ([ApiFactionLifecycleListener], [ApiRelationshipListener]) are
 * Bukkit listeners over MF's internal events, because the internal event carries everything the API
 * event needs. Chunk ownership does not work that way. MF's internal `FactionClaimEvent` reports the
 * incoming owner and nothing about the outgoing one, and it fires before the write, so a listener
 * over it would have to guess at both the previous owner and whether the change actually landed.
 *
 * `MfClaimService` has both facts in local variables at the only two points ownership can change: the
 * value displaced from the claim map on save, and the value removed from it on delete, each after the
 * repository write has already returned. Calling from there is the only place the event can be both
 * accurate about the previous owner and honest about "this has happened".
 *
 * The construction and scheduling still live here, in the adapter layer, so that the API's event
 * shape, its main-thread guarantee and its "only when it really changed" rule stay in one place and
 * out of MF's own service. `MfClaimService` calls one line and knows nothing about the API.
 *
 * ## Not registered anywhere
 *
 * Stateless, so there is no instance to construct in `MedievalFactions.onEnable` and nothing to
 * unregister. This is deliberate: a bridge that has to be wired up is a bridge that can be forgotten.
 */
object ApiClaimEventBridge {

    /**
     * Announces that a chunk's owning faction has changed, if it really has.
     *
     * Silent when [previousOwner] and [newOwner] are equal. MF re-saves an unchanged claim on several
     * administrative paths, and a consumer that treats every save as a conquest would present the
     * same decision repeatedly to a player who conquered nothing. The comparison is on
     * [MfFactionId], which is a value class over the id string, so this is a string comparison and
     * not identity.
     *
     * Scheduled onto the next tick rather than fired inline, for the same reason as the other
     * bridges: MF's claim path runs asynchronously from the `/f claim` command chain, and an API
     * consumer must be able to touch the Bukkit world in its handler without checking what thread it
     * is on. The write has already completed by the time this is called, so the delay costs
     * correctness nothing.
     *
     * @param plugin the running MedievalFactions instance, used for the scheduler and plugin manager
     * @param worldId the world the chunk is in
     * @param chunkX the chunk's X coordinate, in chunks
     * @param chunkZ the chunk's Z coordinate, in chunks
     * @param previousOwner the faction that held it before, or null if it was wilderness
     * @param newOwner the faction that holds it now, or null if it has returned to wilderness
     */
    fun ownerChanged(
        plugin: MedievalFactions,
        worldId: UUID,
        chunkX: Int,
        chunkZ: Int,
        previousOwner: MfFactionId?,
        newOwner: MfFactionId?
    ) {
        if (previousOwner == newOwner) return
        val event = ClaimOwnerChangedEvent(
            worldId,
            chunkX,
            chunkZ,
            previousOwner?.let { FactionId(it.value) },
            newOwner?.let { FactionId(it.value) }
        )
        plugin.server.scheduler.runTask(
            plugin,
            Runnable { plugin.server.pluginManager.callEvent(event) }
        )
    }
}
