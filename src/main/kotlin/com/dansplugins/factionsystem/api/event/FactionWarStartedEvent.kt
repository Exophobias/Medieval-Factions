package com.dansplugins.factionsystem.api.event

import com.dansplugins.factionsystem.api.FactionId
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * Fired (on the main thread) when a war begins between two factions. Part of the stable API: unlike
 * MedievalFactions' internal relationship events, this carries both faction identities and a
 * definite "war" meaning.
 *
 * [faction] is the faction whose committed `AT_WAR` relationship started the pair. The explicit
 * [initiatingFaction] is who caused it: normally the same faction, but invocation records the
 * existing belligerent that called its ally, which need not be one of the two factions entering the
 * new pair. Consumers deciding whether an act was outgoing must use that property rather than infer
 * causality from relationship row direction.
 */
class FactionWarStartedEvent(
    val faction: FactionId,
    val otherFaction: FactionId,
    val initiatingFaction: FactionId
) : Event() {

    /** Preserves the original two-argument JVM constructor for existing stable-API consumers. */
    constructor(faction: FactionId, otherFaction: FactionId) :
        this(faction, otherFaction, faction)

    override fun getHandlers(): HandlerList = handlerList

    companion object {
        @JvmStatic
        private val handlerList = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = handlerList
    }
}
