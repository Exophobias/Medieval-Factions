package com.dansplugins.factionsystem.api.event

import com.dansplugins.factionsystem.api.FactionId
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.UUID

/**
 * A faction is about to claim a chunk, and a consumer may refuse.
 *
 * ## Why this exists when [ClaimOverrideProvider] already does
 *
 * Because that provider is **additive only**: it can turn one of MF's denials into a permission and
 * never the reverse, which is the right shape for granting narrow exceptions and no shape at all for
 * a rule that has to *forbid* something. A consumer with a reason to stop a claim -- a truce, a
 * contested border, a staged event -- had nothing on the stable API to bind to, and its only option
 * was MF's internal `FactionClaimEvent`, which is exactly the dependency this package exists to make
 * unnecessary.
 *
 * ## Bind to this rather than to a command
 *
 * The obvious alternative is to refuse the command, and it does not work. Claiming has a route that
 * involves no command at all: `factions.autoclaim` is a stored flag, toggled once, and applied on
 * every chunk boundary the player crosses. A consumer matching command text also misses every alias
 * an operator has configured and every `<plugin>:<label>` form, both of which Bukkit dispatches
 * identically. This event sits underneath all of it.
 *
 * ## Threading, and it is the same exception [FactionCreateEvent] is
 *
 * Fired **inline, on whatever thread MF is on, and it may be asynchronous** -- because it is
 * cancellable and a veto has to arrive before MF writes anything. MF's own command layer claims on
 * an async task, so in ordinary play this *is* async. A handler must not touch the world, and must
 * be fast: it runs inside the claim.
 *
 * Cancelling makes [MedievalFactionsApi.claim] return a failure, so the caller learns of the refusal
 * in the ordinary way.
 *
 * @param faction who is claiming
 * @param worldId the world the chunk is in
 * @param chunkX chunk coordinates, not block coordinates
 * @param chunkZ chunk coordinates, not block coordinates
 * @param previousOwner the faction that holds the chunk now, or null for wilderness. Carried because
 *                      a consumer deciding whether to refuse usually needs to know whether this is
 *                      an expansion or a conquest, and it cannot find out afterwards
 */
class FactionClaimAttemptEvent(
    val faction: FactionId,
    val worldId: UUID,
    val chunkX: Int,
    val chunkZ: Int,
    val previousOwner: FactionId?,
    private val async: Boolean
) : Event(async), Cancellable {

    private var cancelled = false

    override fun isCancelled(): Boolean = cancelled

    override fun setCancelled(cancel: Boolean) {
        cancelled = cancel
    }

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
