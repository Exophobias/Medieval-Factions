package com.dansplugins.factionsystem.event.relationship

import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.relationship.MfFactionRelationship
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/** A relationship row that has successfully committed and entered the live service index. */
class RelationshipCreatedEvent(
    val relationship: MfFactionRelationship,
    val initiatingFaction: MfFactionId,
    isAsync: Boolean
) : Event(isAsync) {

    companion object {
        @JvmStatic private val handlers: HandlerList = HandlerList()

        @JvmStatic fun getHandlerList() = handlers
    }

    override fun getHandlers(): HandlerList = getHandlerList()
}
