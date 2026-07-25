package com.dansplugins.factionsystem.api.event

import com.dansplugins.factionsystem.api.FactionId
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.UUID

/**
 * Fired (on the main thread) after a faction has unclaimed a single chunk. Part of the stable API.
 *
 * Carries raw chunk coordinates rather than a Bukkit `Chunk` on purpose: obtaining a `Chunk` goes
 * through `World.getChunkAt`, which loads and if necessary generates the chunk. A consumer reacting to
 * an unclaim almost never wants that, and the ones that do can ask for it themselves.
 *
 * **Does NOT cover bulk unclaims.** `MfClaimService.deleteAllClaims` — the path used when a faction is
 * disbanded and by `/f unclaimall` — deletes rows without firing MF's per-claim internal event, so this
 * event is not emitted for them. Disband is still covered, because MF fires its disband event *before*
 * wiping the claims: listen to [FactionDisbandedEvent] for that case. `/f unclaimall` on a faction that
 * stays alive is genuinely unobserved; a consumer that must be exact should revalidate lazily against
 * [com.dansplugins.factionsystem.api.MedievalFactionsApi.isClaimed] rather than trusting event history.
 *
 * See [FactionDisbandedEvent] for the same next-tick timing caveat.
 */
class FactionUnclaimedChunkEvent(
    val faction: FactionId,
    val worldId: UUID,
    val chunkX: Int,
    val chunkZ: Int
) : Event() {

    override fun getHandlers(): HandlerList = handlerList

    companion object {
        @JvmStatic
        private val handlerList = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = handlerList
    }
}
