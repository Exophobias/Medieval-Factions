package com.dansplugins.factionsystem.relationship

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.event.relationship.RelationshipCreateEvent
import com.dansplugins.factionsystem.event.relationship.RelationshipDeleteEvent
import com.dansplugins.factionsystem.exception.EventCancelledException
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.failure.OptimisticLockingFailureException
import com.dansplugins.factionsystem.failure.ServiceFailure
import com.dansplugins.factionsystem.failure.ServiceFailureType
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType.ALLY
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType.AT_WAR
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType.LIEGE
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType.VASSAL
import dev.forkhandles.result4k.Result4k
import dev.forkhandles.result4k.mapFailure
import dev.forkhandles.result4k.resultFrom
import java.util.concurrent.ConcurrentHashMap

class MfFactionRelationshipService(private val plugin: MedievalFactions, private val repository: MfFactionRelationshipRepository) {

    private val relationshipsById: MutableMap<MfFactionRelationshipId, MfFactionRelationship> = ConcurrentHashMap()

    /**
     * The same relationships bucketed by the faction that holds them.
     *
     * Every lookup here used to be a filter over every relationship on the server, and the list being
     * filtered was itself a fresh copy of the whole map. That is fine when a lookup happens once per
     * command, and ruinous for anything asked per chat line: resolving a faction's position in the
     * hierarchy costs a handful of these lookups, so an unindexed read would scan the entire table a
     * dozen times per message. Bucketing makes each one proportional to the relationships that one
     * faction holds, which is a single-digit number in practice.
     *
     * Kept in step with [relationshipsById] in exactly the places that write to it: the initial load,
     * [save] and [delete]. Values are immutable lists replaced wholesale, so a reader never observes a
     * half-updated bucket.
     */
    private val relationshipsByFactionId: MutableMap<MfFactionId, List<MfFactionRelationship>> = ConcurrentHashMap()

    private val relationships: List<MfFactionRelationship>
        get() = relationshipsById.values.toList()

    init {
        plugin.logger.info("Loading faction relationships...")
        val startTime = System.currentTimeMillis()
        relationshipsById.putAll(repository.getFactionRelationships().associateBy(MfFactionRelationship::id))
        relationshipsByFactionId.putAll(relationshipsById.values.groupBy(MfFactionRelationship::factionId))
        plugin.logger.info("${relationshipsById.size} faction relationships loaded (${System.currentTimeMillis() - startTime}ms)")
    }

    private fun index(relationship: MfFactionRelationship) {
        relationshipsByFactionId.compute(relationship.factionId) { _, existing ->
            existing.orEmpty().filter { it.id != relationship.id } + relationship
        }
    }

    private fun unindex(relationship: MfFactionRelationship) {
        relationshipsByFactionId.computeIfPresent(relationship.factionId) { _, existing ->
            val remaining = existing.filter { it.id != relationship.id }
            if (remaining.isEmpty()) null else remaining
        }
    }

    @JvmName("getRelationshipByRelationshipId")
    fun getRelationship(relationshipId: MfFactionRelationshipId): MfFactionRelationship? {
        return relationshipsById[relationshipId]
    }

    @JvmName("getRelationshipsByFactionIdAndTargetId")
    fun getRelationships(factionId: MfFactionId, targetId: MfFactionId): List<MfFactionRelationship> {
        return getRelationships(factionId).filter { it.targetId == targetId }
    }

    @JvmName("getRelationshipsByFactionId")
    fun getRelationships(factionId: MfFactionId): List<MfFactionRelationship> {
        return relationshipsByFactionId[factionId].orEmpty()
    }

    @JvmName("getRelationshipsByFactionIdAndType")
    fun getRelationships(factionId: MfFactionId, type: MfFactionRelationshipType): List<MfFactionRelationship> {
        return getRelationships(factionId).filter { it.type == type }
    }

    fun save(relationship: MfFactionRelationship): Result4k<MfFactionRelationship, ServiceFailure> = resultFrom {
        val previousState = getRelationship(relationship.id)
        if (previousState == null) {
            val event = RelationshipCreateEvent(relationship.id, relationship, !plugin.server.isPrimaryThread)
            plugin.server.pluginManager.callEvent(event)
            if (event.isCancelled) {
                throw EventCancelledException("Event cancelled")
            }
        }
        val result = repository.upsert(relationship)
        // Unindex the previous state first: an upsert may move a relationship to a different holder,
        // and leaving the old bucket populated would report a vassalage that no longer exists.
        previousState?.let(::unindex)
        relationshipsById[result.id] = result
        index(result)
        return@resultFrom result
    }.mapFailure { exception ->
        ServiceFailure(exception.toServiceFailureType(), "Service error: ${exception.message}", exception)
    }

    @JvmName("deleteRelationshipByRelationshipId")
    fun delete(id: MfFactionRelationshipId): Result4k<Unit, ServiceFailure> = resultFrom {
        val event = RelationshipDeleteEvent(id, !plugin.server.isPrimaryThread)
        plugin.server.pluginManager.callEvent(event)
        if (event.isCancelled) {
            throw EventCancelledException("Event cancelled")
        }
        val result = repository.delete(id)
        relationshipsById.remove(id)?.let(::unindex)
        return@resultFrom result
    }.mapFailure { exception ->
        ServiceFailure(exception.toServiceFailureType(), "Service error: ${exception.message}", exception)
    }

    @JvmName("getVassalTreeByFactionId")
    fun getVassalTree(factionId: MfFactionId): MfVassalNode {
        return MfVassalNode(
            factionId,
            getVassals(factionId).map(::getVassalTree)
        )
    }

    @JvmName("getLiegeChainByFactionId")
    fun getLiegeChain(factionId: MfFactionId): MfLiegeNode {
        val liege = getLiege(factionId)
        return if (liege != null) {
            MfLiegeNode(factionId, getLiegeChain(liege))
        } else {
            MfLiegeNode(factionId, null)
        }
    }

    @JvmName("getLiegeByFactionId")
    fun getLiege(factionId: MfFactionId): MfFactionId? {
        val liege = getRelationships(factionId, LIEGE).firstOrNull()?.targetId
        if (liege != null) {
            val reverseRelationships = getRelationships(liege, factionId)
            if (reverseRelationships.any { it.type == VASSAL }) {
                return liege
            }
        }
        return null
    }

    @JvmName("getVassalsByFactionId")
    fun getVassals(factionId: MfFactionId): List<MfFactionId> {
        return getRelationships(factionId, VASSAL)
            .filter { relationship ->
                getRelationships(relationship.targetId, factionId).any {
                    it.type == LIEGE
                }
            }.map(MfFactionRelationship::targetId)
    }

    /**
     * Whether this faction holds at least one vassal, without building the list to find out.
     *
     * [getVassals] allocates and validates every vassal; callers that only need to know whether the
     * faction is somebody's liege stop at the first confirmed one.
     */
    @JvmName("hasVassalsByFactionId")
    fun hasVassals(factionId: MfFactionId): Boolean {
        return getRelationships(factionId, VASSAL).any { relationship ->
            getRelationships(relationship.targetId, factionId).any { it.type == LIEGE }
        }
    }

    /**
     * How many liege links separate this faction from the top of its chain: 0 for a faction that
     * swears to nobody, 1 for the direct vassal of a sovereign, and so on.
     *
     * Walked iteratively rather than through [getLiegeChain], which recurses and allocates a node per
     * level. It also refuses to loop: relationships are stored as two rows and nothing stops a corrupt
     * or hand-edited pair from forming a ring, and a consumer asking this to render a chat line must
     * get a finite answer rather than a stack overflow. On meeting a faction already walked, the walk
     * stops and reports the distance covered so far. Such a faction has a liege either way, which is
     * the only thing a caller can safely conclude from a broken chain.
     *
     * Cost: two bucket lookups per level, and a chain is a handful of levels deep at most.
     */
    @JvmName("getDepthBelowSovereignByFactionId")
    fun getDepthBelowSovereign(factionId: MfFactionId): Int {
        val visited = mutableSetOf(factionId)
        var depth = 0
        var current = factionId
        while (true) {
            val liege = getLiege(current) ?: return depth
            if (!visited.add(liege)) return depth
            depth++
            current = liege
        }
    }

    /**
     * This faction's direct vassals that are themselves somebody's liege.
     *
     * Exactly two levels deep and no further, deliberately: it answers "does this faction rule rulers"
     * without walking the subtree that [getVassalTree] materialises, which grows with the whole realm
     * and is far too expensive to ask per chat line.
     *
     * Cost: one bucket lookup per direct vassal, plus one per candidate to confirm the vassalage is
     * mutual.
     */
    @JvmName("getVassalsHoldingVassalsByFactionId")
    fun getVassalsHoldingVassals(factionId: MfFactionId): List<MfFactionId> {
        return getVassals(factionId).filter(::hasVassals)
    }

    @JvmName("getAlliesByFactionId")
    fun getAllies(factionId: MfFactionId): List<MfFactionId> {
        return getRelationships(factionId, ALLY)
            .filter { relationship ->
                getRelationships(relationship.targetId, relationship.factionId).any {
                    it.type == ALLY
                }
            }.map(MfFactionRelationship::targetId)
    }

    @JvmName("getFactionsAtWarWithByFactionId")
    fun getFactionsAtWarWith(factionId: MfFactionId): List<MfFactionId> {
        return relationships
            .filter { it.type == AT_WAR && (it.factionId == factionId || it.targetId == factionId) }
            .map {
                if (it.factionId == factionId) it.targetId else it.factionId
            }.toSet().toList()
    }

    private fun Exception.toServiceFailureType(): ServiceFailureType {
        return when (this) {
            is OptimisticLockingFailureException -> ServiceFailureType.CONFLICT
            else -> ServiceFailureType.GENERAL
        }
    }
}
