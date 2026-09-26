package com.dansplugins.factionsystem.api.event

import com.dansplugins.factionsystem.api.FactionId
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.UUID

/**
 * A faction is about to release one chunk, and a consumer may refuse.
 *
 * Fired inline before MedievalFactions' internal unclaim event and before the database write.
 * The ordinary command runs on a worker, so this event may be asynchronous. A handler must make
 * its decision from thread-safe data without touching the Bukkit world or loading a chunk.
 *
 * This covers single-chunk unclaims, including [com.dansplugins.factionsystem.api.MedievalFactionsApi.unclaimIfOwned].
 * Bulk `/f unclaimall` uses [FactionUnclaimAllAttemptEvent] instead. Faction deletion cascades
 * its claims with the faction row and is not a voluntary unclaim.
 */
class FactionUnclaimAttemptEvent(
    val faction: FactionId,
    val worldId: UUID,
    val chunkX: Int,
    val chunkZ: Int,
    isAsync: Boolean
) : Event(isAsync), Cancellable {

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
