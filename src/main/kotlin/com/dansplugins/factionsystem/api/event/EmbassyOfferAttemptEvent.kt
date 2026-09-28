package com.dansplugins.factionsystem.api.event

import com.dansplugins.factionsystem.api.FactionId
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.UUID

/**
 * Pre-write veto for a proposed embassy, emitted on both offer and acceptance. A land consumer such
 * as Fiefs can reject a parcel overlapping one of its holdings without MF depending on that plugin.
 * Offer and acceptance call this on the server thread, where other land plugins can safely
 * inspect their current claim state.
 */
class EmbassyOfferAttemptEvent(
    val worldId: UUID,
    val chunkX: Int,
    val chunkZ: Int,
    val host: FactionId,
    val guest: FactionId,
    val accepting: Boolean,
    isAsync: Boolean
) : Event(isAsync), Cancellable {
    private var cancelled = false

    override fun isCancelled(): Boolean = cancelled
    override fun setCancelled(cancelled: Boolean) { this.cancelled = cancelled }
    override fun getHandlers(): HandlerList = handlerList

    companion object {
        @JvmStatic private val handlerList = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = handlerList
    }
}
