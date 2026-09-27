package com.dansplugins.factionsystem.event.faction

import com.dansplugins.factionsystem.event.player.PlayerEvent
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.player.MfPlayerId
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

class FactionJoinEvent(
    @get:JvmName("getFactionId")
    override val factionId: MfFactionId,
    @get:JvmName("getPlayerId")
    override val playerId: MfPlayerId,
    /** Size of the complete roster proposed by this save, including every arrival. */
    val proposedMemberCount: Int,
    isAsync: Boolean
) : Event(isAsync), FactionEvent, PlayerEvent, Cancellable {

    /** Preserve callers of the original constructor; a negative count means unavailable. */
    constructor(factionId: MfFactionId, playerId: MfPlayerId, isAsync: Boolean) :
        this(factionId, playerId, -1, isAsync)

    companion object {
        @JvmStatic private val handlers: HandlerList = HandlerList()

        @JvmStatic fun getHandlerList() = handlers
    }

    private var cancel: Boolean = false

    override fun getHandlers(): HandlerList = getHandlerList()

    override fun isCancelled(): Boolean {
        return cancel
    }

    override fun setCancelled(cancel: Boolean) {
        this.cancel = cancel
    }
}
