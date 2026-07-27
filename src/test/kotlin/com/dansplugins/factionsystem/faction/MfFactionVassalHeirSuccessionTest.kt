package com.dansplugins.factionsystem.faction

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.faction.flag.MfFlags
import com.dansplugins.factionsystem.faction.permission.MfFactionPermissions
import com.dansplugins.factionsystem.faction.role.MfFactionRoles
import com.dansplugins.factionsystem.lang.Language
import com.dansplugins.factionsystem.locks.MfLockService
import com.dansplugins.factionsystem.player.MfPlayerId
import com.dansplugins.factionsystem.relationship.MfFactionRelationship
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipId
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipRepository
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipService
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType.LIEGE
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType.VASSAL
import com.dansplugins.factionsystem.service.Services
import dev.forkhandles.result4k.onFailure
import org.bukkit.Server
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.plugin.PluginManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD
import org.mockito.Mockito.RETURNS_SMART_NULLS
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.UUID
import java.util.concurrent.TimeUnit.SECONDS
import java.util.logging.Logger

/**
 * Covers a ruler naming the head of one of their vassals as heir, and what happens when that
 * nomination lands.
 *
 * A player belongs to exactly one faction, so there is no personal union to be had: an heir of this
 * kind takes the greater realm by leaving their own, which fires that realm's succession in turn.
 * That cascade is the point of the feature and it is what most of these tests are about.
 *
 * Everything here runs against real [MfFactionService] and [MfFactionRelationshipService] instances
 * over in-memory repositories, because the behaviour under test is precisely how those two interact
 * across three factions at once. A mocked service would assert only that the test's own idea of the
 * rule is self-consistent.
 */
class MfFactionVassalHeirSuccessionTest {

    private lateinit var plugin: MedievalFactions
    private lateinit var config: FileConfiguration
    private lateinit var factionService: MfFactionService
    private lateinit var relationshipService: MfFactionRelationshipService

    private val emma = MfPlayerId(UUID.randomUUID().toString())
    private val kate = MfPlayerId(UUID.randomUUID().toString())
    private val diana = MfPlayerId(UUID.randomUUID().toString())
    private val edmund = MfPlayerId(UUID.randomUUID().toString())
    private val stewardOfTheEmpire = MfPlayerId(UUID.randomUUID().toString())

    private class InMemoryFactionRepository : MfFactionRepository {
        val rows = mutableMapOf<MfFactionId, MfFaction>()
        override fun getFaction(id: MfFactionId) = rows[id]
        override fun getFaction(name: String) = rows.values.firstOrNull { it.name == name }
        override fun getFaction(playerId: MfPlayerId) = rows.values.firstOrNull { it.isMember(playerId) }
        override fun getFactions() = rows.values.toList()
        override fun upsert(faction: MfFaction): MfFaction {
            rows[faction.id] = faction
            return faction
        }
        override fun delete(factionId: MfFactionId) {
            rows.remove(factionId)
        }
    }

    private class InMemoryRelationshipRepository : MfFactionRelationshipRepository {
        val rows = mutableMapOf<MfFactionRelationshipId, MfFactionRelationship>()
        override fun getFactionRelationship(relationshipId: MfFactionRelationshipId) = rows[relationshipId]
        override fun getFactionRelationships(factionId: MfFactionId, targetId: MfFactionId) =
            rows.values.filter { it.factionId == factionId && it.targetId == targetId }
        override fun getFactionRelationships(factionId: MfFactionId, type: MfFactionRelationshipType) =
            rows.values.filter { it.factionId == factionId && it.type == type }
        override fun getFactionRelationships(factionId: MfFactionId) = rows.values.filter { it.factionId == factionId }
        override fun getFactionRelationships() = rows.values.toList()
        override fun upsert(relationship: MfFactionRelationship): MfFactionRelationship {
            rows[relationship.id] = relationship
            return relationship
        }
        override fun delete(relationshipId: MfFactionRelationshipId) {
            rows.remove(relationshipId)
        }
    }

    @BeforeEach
    fun setUp() {
        plugin = mock(MedievalFactions::class.java)
        `when`(plugin.logger).thenReturn(mock(Logger::class.java))
        `when`(plugin.language).thenReturn(mock(Language::class.java, RETURNS_SMART_NULLS))
        config = mock(FileConfiguration::class.java)
        `when`(plugin.config).thenReturn(config)
        // Skips the neutrality reconciliation pass in MfFactionService's init, which runs before the
        // services exist and has nothing to do with succession.
        `when`(config.getBoolean("factions.allowNeutrality")).thenReturn(true)
        allowLeaderlessFactions(true)
        // Built before the stubbing starts: both call back into the mocked plugin, and Mockito treats
        // a mock call made mid-stubbing as an unfinished stub.
        val flags = MfFlags(plugin)
        `when`(plugin.flags).thenReturn(flags)
        val permissions = MfFactionPermissions(plugin)
        `when`(plugin.factionPermissions).thenReturn(permissions)

        val server = mock(Server::class.java)
        `when`(plugin.server).thenReturn(server)
        `when`(server.pluginManager).thenReturn(mock(PluginManager::class.java))
        `when`(server.isPrimaryThread).thenReturn(false)

        relationshipService = MfFactionRelationshipService(plugin, InMemoryRelationshipRepository())
        factionService = MfFactionService(plugin, InMemoryFactionRepository())
        val services = mock(Services::class.java)
        `when`(services.factionService).thenReturn(factionService)
        `when`(services.factionRelationshipService).thenReturn(relationshipService)
        `when`(services.lockService).thenReturn(mock(MfLockService::class.java))
        `when`(plugin.services).thenReturn(services)
        `when`(plugin.servicesOrNull).thenReturn(services)
    }

    private fun allowLeaderlessFactions(allowed: Boolean) {
        `when`(config.getBoolean("factions.allowLeaderlessFactions")).thenReturn(allowed)
    }

    /** Creates a faction whose first member is its head, plus any others in the order given. */
    private fun foundFaction(name: String, head: MfPlayerId, others: List<MfPlayerId> = emptyList()): MfFaction {
        val id = MfFactionId.generate()
        val roles = MfFactionRoles.defaults(plugin, id)
        val members = listOf(MfFactionMember(head, roles.leaderRole!!, joinedAt = 1_000)) +
            others.mapIndexed { index, playerId ->
                MfFactionMember(playerId, roles.default, joinedAt = 2_000L + index)
            }
        return factionService.save(
            MfFaction(plugin, id = id, name = name, roles = roles, members = members, primaryOwnerId = head)
        ).onFailure { throw it.reason.cause }
    }

    /** What /f vassalize plus the vassal's acceptance writes: a mirrored pair of rows. */
    private fun swearFealty(vassal: MfFaction, liege: MfFaction) {
        relationshipService.save(MfFactionRelationship(factionId = liege.id, targetId = vassal.id, type = VASSAL))
            .onFailure { throw it.reason.cause }
        relationshipService.save(MfFactionRelationship(factionId = vassal.id, targetId = liege.id, type = LIEGE))
            .onFailure { throw it.reason.cause }
    }

    private fun declareIndependence(vassal: MfFaction, liege: MfFaction) {
        (relationshipService.getRelationships(liege.id, vassal.id) + relationshipService.getRelationships(vassal.id, liege.id))
            .forEach { relationshipService.delete(it.id).onFailure { failure -> throw failure.reason.cause } }
    }

    private fun nominate(faction: MfFaction, heir: MfPlayerId): MfFaction =
        save(current(faction).copy(heirId = heir))

    private fun save(faction: MfFaction): MfFaction =
        factionService.save(faction).onFailure { throw it.reason.cause }

    /** The stored state of a faction, which is what every assertion here is about. */
    private fun current(faction: MfFaction): MfFaction = factionService.getFaction(faction.id)!!

    /** Removes a member the way /f leave and /f kick both do: by rewriting the member list. */
    private fun depart(faction: MfFaction, playerId: MfPlayerId): MfFaction =
        save(current(faction).copy(members = current(faction).members.filter { it.playerId != playerId }))

    // --- nomination ---

    @Test
    fun aVassalsHeadIsAValidNominationAndIsNotForgottenForNotBeingAMember() {
        val kingdom = foundFaction("Kingdom", kate, listOf(stewardOfTheEmpire))
        val duchy = foundFaction("Duchy", diana, listOf(edmund))
        swearFealty(duchy, kingdom)

        nominate(kingdom, diana)

        assertEquals(diana, current(kingdom).heirId)
        assertEquals(duchy.id, current(kingdom).heirsVassalFaction)
    }

    @Test
    fun theHeadOfAFactionThatIsNotAVassalIsNotAnHeirOfThisKind() {
        val kingdom = foundFaction("Kingdom", kate, listOf(stewardOfTheEmpire))
        foundFaction("Free City", diana, listOf(edmund))

        assertNull(save(current(kingdom).copy(heirId = diana)).heirsVassalFaction)
    }

    /** Nomination names a person, and the person has to still be the one in charge. */
    @Test
    fun anOrdinaryMemberOfAVassalIsNotItsHead() {
        val kingdom = foundFaction("Kingdom", kate, listOf(stewardOfTheEmpire))
        val duchy = foundFaction("Duchy", diana, listOf(edmund))
        swearFealty(duchy, kingdom)

        assertNull(save(current(kingdom).copy(heirId = edmund)).heirsVassalFaction)
    }

    @Test
    fun aNominationOfAVassalsHeadIsDroppedWhenTheVassalDeclaresIndependence() {
        val kingdom = foundFaction("Kingdom", kate, listOf(stewardOfTheEmpire))
        val duchy = foundFaction("Duchy", diana, listOf(edmund))
        swearFealty(duchy, kingdom)
        nominate(kingdom, diana)

        declareIndependence(duchy, kingdom)

        // Not merely unusable: the next save forgets it, exactly as it forgets a member who left.
        assertNull(save(current(kingdom)).heirId)
    }

    // --- succession ---

    @Test
    fun aVassalsHeadLeavesTheirOwnFactionToTakeTheGreaterOne() {
        val kingdom = foundFaction("Kingdom", kate, listOf(stewardOfTheEmpire))
        val duchy = foundFaction("Duchy", diana, listOf(edmund))
        swearFealty(duchy, kingdom)
        nominate(kingdom, diana)

        depart(kingdom, kate)

        assertEquals(diana, current(kingdom).primaryOwnerId)
        assertTrue(current(kingdom).isMember(diana))
        assertFalse(current(duchy).isMember(diana))
        // Spent on use, like any other nomination.
        assertNull(current(kingdom).heirId)
    }

    /** A head who cannot act is not a head, which is why /f transfer grants the top role too. */
    @Test
    fun theAscendingHeirArrivesHoldingTheTopRole() {
        val kingdom = foundFaction("Kingdom", kate, listOf(stewardOfTheEmpire))
        val duchy = foundFaction("Duchy", diana, listOf(edmund))
        swearFealty(duchy, kingdom)
        nominate(kingdom, diana)

        depart(kingdom, kate)

        assertEquals(current(kingdom).roles.leaderRole?.id, current(kingdom).getRole(diana)?.id)
    }

    /** The departure the ascension causes is a real one, so the vacated faction succeeds in turn. */
    @Test
    fun theFactionTheHeirLeavesPassesToItsOwnSuccessor() {
        val kingdom = foundFaction("Kingdom", kate, listOf(stewardOfTheEmpire))
        val duchy = foundFaction("Duchy", diana, listOf(edmund))
        swearFealty(duchy, kingdom)
        nominate(kingdom, diana)

        depart(kingdom, kate)

        assertEquals(edmund, current(duchy).primaryOwnerId)
    }

    /**
     * The whole point of the mechanic. One player leaving the top of a three-level hierarchy moves
     * every head down the chain, because each departure is an ordinary departure and each faction
     * applies the same rule.
     */
    @Test
    fun oneDepartureCascadesTheWholeChain() {
        val empire = foundFaction("Empire", emma, listOf(stewardOfTheEmpire))
        val kingdom = foundFaction("Kingdom", kate)
        val duchy = foundFaction("Duchy", diana, listOf(edmund))
        swearFealty(kingdom, empire)
        swearFealty(duchy, kingdom)
        nominate(empire, kate)
        nominate(kingdom, diana)

        depart(empire, emma)

        assertEquals(kate, current(empire).primaryOwnerId)
        assertEquals(diana, current(kingdom).primaryOwnerId)
        assertEquals(edmund, current(duchy).primaryOwnerId)
        assertFalse(current(kingdom).isMember(kate))
        assertFalse(current(duchy).isMember(diana))
    }

    /** Nothing about a nomination takes effect until the seat is actually vacant. */
    @Test
    fun nothingMovesWhileTheHeadIsStillThere() {
        val kingdom = foundFaction("Kingdom", kate, listOf(stewardOfTheEmpire))
        val duchy = foundFaction("Duchy", diana, listOf(edmund))
        swearFealty(duchy, kingdom)
        nominate(kingdom, diana)

        depart(kingdom, stewardOfTheEmpire)

        assertEquals(kate, current(kingdom).primaryOwnerId)
        assertEquals(diana, current(duchy).primaryOwnerId)
        assertFalse(current(kingdom).isMember(diana))
        assertEquals(diana, current(kingdom).heirId)
    }

    // --- the nomination going stale between being made and being needed ---

    @Test
    fun aVassalThatWalkedAwayDoesNotInheritAndTheOrdinaryOrderApplies() {
        val kingdom = foundFaction("Kingdom", kate, listOf(stewardOfTheEmpire))
        val duchy = foundFaction("Duchy", diana, listOf(edmund))
        swearFealty(duchy, kingdom)
        nominate(kingdom, diana)
        declareIndependence(duchy, kingdom)

        depart(kingdom, kate)

        assertEquals(stewardOfTheEmpire, current(kingdom).primaryOwnerId)
        assertEquals(diana, current(duchy).primaryOwnerId)
        assertTrue(current(duchy).isMember(diana))
        assertFalse(current(kingdom).isMember(diana))
    }

    @Test
    fun aVassalThatReplacedItsHeadDoesNotSendTheFormerOne() {
        val kingdom = foundFaction("Kingdom", kate, listOf(stewardOfTheEmpire))
        val duchy = foundFaction("Duchy", diana, listOf(edmund))
        swearFealty(duchy, kingdom)
        nominate(kingdom, diana)
        // The duchy hands itself to Edmund, as /f transfer would.
        save(current(duchy).copy(primaryOwnerId = edmund))

        depart(kingdom, kate)

        assertEquals(stewardOfTheEmpire, current(kingdom).primaryOwnerId)
        assertTrue(current(duchy).isMember(diana))
        assertFalse(current(kingdom).isMember(diana))
    }

    /**
     * A vassal of one member cannot spare its head. Passing the nomination over is the right answer:
     * refusing the save would mean a ruler could not leave their own faction because of a nomination
     * they made months ago.
     */
    @Test
    fun aVassalThatCannotSpareItsHeadIsPassedOver() {
        allowLeaderlessFactions(false)
        val kingdom = foundFaction("Kingdom", kate, listOf(stewardOfTheEmpire))
        val duchy = foundFaction("Duchy", diana)
        swearFealty(duchy, kingdom)
        nominate(kingdom, diana)

        depart(kingdom, kate)

        assertEquals(stewardOfTheEmpire, current(kingdom).primaryOwnerId)
        assertEquals(diana, current(duchy).primaryOwnerId)
        assertTrue(current(duchy).isMember(diana))
    }

    /**
     * Vassalage is two rows and nothing in the schema forbids a ring of them. A succession that met
     * one would recurse forever, having already written to other factions on the way down.
     *
     * Timed out as well as asserted, because one of the two failure modes this guards against is a
     * call that never returns rather than one that returns the wrong answer.
     */
    @Test
    @Timeout(value = 10, unit = SECONDS, threadMode = SEPARATE_THREAD)
    fun aRingOfVassalageDoesNotRecurseForever() {
        val kingdom = foundFaction("Kingdom", kate, listOf(stewardOfTheEmpire))
        val duchy = foundFaction("Duchy", diana, listOf(edmund))
        swearFealty(duchy, kingdom)
        swearFealty(kingdom, duchy)
        nominate(kingdom, diana)
        nominate(duchy, kate)

        depart(kingdom, kate)

        // Whoever ends up where, the call returns and both factions still have a head.
        assertTrue(current(kingdom).primaryOwnerId != null)
        assertTrue(current(duchy).primaryOwnerId != null)
    }
}
