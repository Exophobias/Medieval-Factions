package com.dansplugins.factionsystem.api.impl

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.FactionId
import com.dansplugins.factionsystem.api.event.FactionWarEndedEvent
import com.dansplugins.factionsystem.api.event.FactionWarStartedEvent
import com.dansplugins.factionsystem.event.relationship.RelationshipCreateEvent
import com.dansplugins.factionsystem.event.relationship.RelationshipDeleteEvent
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.relationship.MfFactionRelationship
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipId
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType.AT_WAR
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges MedievalFactions' internal relationship events to the stable API war events.
 *
 * MF's [RelationshipDeleteEvent] carries only the relationship id, not its type, so a plain listener
 * cannot tell that a deleted relationship was a war. This listener caches relationship id -> value so
 * it can. It also collapses the possibly-two-row AT_WAR representation into exactly one
 * [FactionWarStartedEvent] / [FactionWarEndedEvent] per faction pair, fired on the main thread.
 *
 * Handlers run at MONITOR priority with ignoreCancelled=true, so the API only reacts to relationship
 * changes that actually take effect.
 */
class ApiRelationshipListener(private val plugin: MedievalFactions) : Listener {

    private data class WarPair(val a: String, val b: String) {
        companion object {
            fun of(x: MfFactionId, y: MfFactionId): WarPair {
                val sorted = listOf(x.value, y.value).sorted()
                return WarPair(sorted[0], sorted[1])
            }
        }
    }

    private val relationshipsById: MutableMap<MfFactionRelationshipId, MfFactionRelationship> = ConcurrentHashMap()
    private val warringPairs: MutableSet<WarPair> = ConcurrentHashMap.newKeySet()

    init {
        // Seed from relationships already loaded at startup so a later delete of a pre-existing war
        // still emits FactionWarEndedEvent.
        val relationshipService = plugin.services.factionRelationshipService
        plugin.services.factionService.factions.forEach { faction ->
            relationshipService.getRelationships(faction.id).forEach { relationship ->
                relationshipsById[relationship.id] = relationship
                if (relationship.type == AT_WAR) {
                    warringPairs.add(WarPair.of(relationship.factionId, relationship.targetId))
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onRelationshipCreate(event: RelationshipCreateEvent) {
        val relationship = event.relationship
        relationshipsById[relationship.id] = relationship
        if (relationship.type != AT_WAR) return
        val pair = WarPair.of(relationship.factionId, relationship.targetId)
        if (warringPairs.add(pair)) {
            fireWarEvent(relationship.factionId, relationship.targetId, started = true)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onRelationshipDelete(event: RelationshipDeleteEvent) {
        val relationship = relationshipsById.remove(event.relationshipId) ?: return
        if (relationship.type != AT_WAR) return
        val pair = WarPair.of(relationship.factionId, relationship.targetId)
        // The row still exists in the service at event time (it is removed after the event returns),
        // so excluding this id yields the post-delete state: is the pair still linked by any AT_WAR row?
        val relationshipService = plugin.services.factionRelationshipService
        val stillAtWar = (
            relationshipService.getRelationships(relationship.factionId, relationship.targetId) +
                relationshipService.getRelationships(relationship.targetId, relationship.factionId)
            ).any { it.id != relationship.id && it.type == AT_WAR }
        if (!stillAtWar && warringPairs.remove(pair)) {
            fireWarEvent(relationship.factionId, relationship.targetId, started = false)
        }
    }

    private fun fireWarEvent(faction: MfFactionId, otherFaction: MfFactionId, started: Boolean) {
        val a = FactionId(faction.value)
        val b = FactionId(otherFaction.value)
        plugin.server.scheduler.runTask(
            plugin,
            Runnable {
                val event = if (started) FactionWarStartedEvent(a, b) else FactionWarEndedEvent(a, b)
                plugin.server.pluginManager.callEvent(event)
            }
        )
    }
}
