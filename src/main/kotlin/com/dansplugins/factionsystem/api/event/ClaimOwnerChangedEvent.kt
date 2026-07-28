package com.dansplugins.factionsystem.api.event

import com.dansplugins.factionsystem.api.FactionId
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.UUID

/**
 * Fired (on the main thread) after the faction owning a chunk has changed, and after that change has
 * been written to the database. Part of the stable API.
 *
 * ## Why this exists when MedievalFactions already has a claim event
 *
 * MF's internal `FactionClaimEvent` cannot answer the question a conquest consumer actually asks.
 * It is cancellable and fires *before* the claim is persisted, so acting on it means acting on
 * something that may not happen; it may fire off the main thread, because the `/f claim` command
 * chain runs asynchronously; it carries MF's internal `MfClaimedChunk`, which the API seam exists to
 * keep out of consumers; and, decisively, it reports only the incoming owner. Whoever held the chunk
 * a moment ago is nowhere in it.
 *
 * That last point is the whole reason for this class. A consumer presenting a conqueror with a
 * decision about what they have just taken, and telling the dispossessed party what became of it,
 * needs both ends of the transfer at the moment it happens. Reconstructing the previous owner from a
 * cached copy of the claim map is possible but wrong in exactly the case that matters, because the
 * cache would have to be updated by the same event that fails to report it.
 *
 * This is the same "MF's internal event does not carry enough" problem already documented on
 * [FactionWarStartedEvent], which exists because `RelationshipDeleteEvent` carries only an id, and on
 * [FactionUnclaimedChunkEvent], which exists because the internal unclaim event carries internal
 * types.
 *
 * ## Contract
 *
 * Past tense, not cancellable, fired on the main thread on the tick after the write succeeded. It
 * reports a transfer that has already taken effect, so there is nothing left to veto. A consumer that
 * wants to *prevent* a claim should use MF's own cancellable path instead.
 *
 * Fired only when the owner genuinely differs. Re-saving a chunk to the faction that already holds
 * it, which MF does on some administrative paths, emits nothing.
 *
 * ## What it does not cover, and the sweep you still need
 *
 * `MfClaimService.deleteAllClaims` deletes rows in bulk straight through the repository. It fires no
 * per-claim internal event and it is not bridged here, so a faction being disbanded or running
 * `/f unclaimall` silently returns a great deal of land to wilderness with this event never firing.
 * A consumer that keys anything durable off chunk ownership, land tax or shop tenancy for instance,
 * therefore still needs a periodic reconciliation sweep against
 * [com.dansplugins.factionsystem.api.MedievalFactionsApi.getClaimAt] as a backstop. Treat this event
 * as the fast path that lets you present a live decision, not as a complete ledger of ownership
 * changes.
 *
 * Disband specifically is still observable, because MF fires its disband event before wiping the
 * claims: see [FactionDisbandedEvent].
 *
 * ## Coordinates, not a Chunk
 *
 * Raw chunk coordinates for the same reason as [FactionUnclaimedChunkEvent]: obtaining a Bukkit
 * `Chunk` goes through `World.getChunkAt`, which loads and if necessary generates it. Conquest
 * happens on chunks nobody may currently be standing in, and forcing a load per transfer during a
 * mass overclaim is a real cost. A consumer that needs the chunk can ask for it itself.
 *
 * @property worldId the world the chunk is in, as [org.bukkit.World.getUID]
 * @property chunkX the chunk's X coordinate, in chunks and not blocks
 * @property chunkZ the chunk's Z coordinate, in chunks and not blocks
 * @property previousOwner the faction that held the chunk immediately before, or null if it was
 *   unclaimed wilderness
 * @property newOwner the faction that holds it now, or null if it has been returned to wilderness
 */
class ClaimOwnerChangedEvent(
    val worldId: UUID,
    val chunkX: Int,
    val chunkZ: Int,
    val previousOwner: FactionId?,
    val newOwner: FactionId?
) : Event() {

    /**
     * Whether this transfer took land from one faction and gave it to another, as opposed to a first
     * claim of wilderness or a release back to it.
     *
     * Convenience only, but it is the condition nearly every consumer opens with, and spelling it out
     * here keeps consumers from writing the null checks in subtly different ways.
     */
    val isConquest: Boolean
        get() = previousOwner != null && newOwner != null

    override fun getHandlers(): HandlerList = handlerList

    companion object {
        @JvmStatic
        private val handlerList = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = handlerList
    }
}
