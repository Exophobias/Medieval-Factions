package com.dansplugins.factionsystem.faction

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.FactionId
import com.dansplugins.factionsystem.api.FactionView
import com.dansplugins.factionsystem.api.PrimaryOwnerReplaceOutcome
import com.dansplugins.factionsystem.api.SuccessionPolicy
import com.dansplugins.factionsystem.api.event.FactionPrimaryOwnerChangedEvent
import com.dansplugins.factionsystem.api.impl.DefaultMedievalFactionsApi
import com.dansplugins.factionsystem.event.faction.FactionDescriptionChangeEvent
import com.dansplugins.factionsystem.exception.NoSuccessorException
import com.dansplugins.factionsystem.faction.flag.MfFlags
import com.dansplugins.factionsystem.faction.permission.MfFactionPermissions
import com.dansplugins.factionsystem.faction.role.MfFactionRoles
import com.dansplugins.factionsystem.failure.OptimisticLockingFailureException
import com.dansplugins.factionsystem.lang.Language
import com.dansplugins.factionsystem.locks.MfLockRepository
import com.dansplugins.factionsystem.locks.MfLockService
import com.dansplugins.factionsystem.locks.MfLockedBlock
import com.dansplugins.factionsystem.locks.MfLockedBlockId
import com.dansplugins.factionsystem.player.MfPlayerId
import com.dansplugins.factionsystem.relationship.MfFactionRelationship
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipId
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipRepository
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipService
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType
import com.dansplugins.factionsystem.service.Services
import dev.forkhandles.result4k.onFailure
import org.bukkit.Server
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.event.Event
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.scheduler.BukkitTask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.RETURNS_SMART_NULLS
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger
import kotlin.concurrent.thread

/**
 * The contract a plugin outside MedievalFactions may rely on when it decides who rules a faction.
 *
 * MF already runs a complete succession ladder on every save, so an external government plugin is
 * not filling a gap - it is overriding a rule that already fires. That makes the boundary the
 * interesting part rather than the mechanism, and it is what these tests are about: what an outside
 * plugin may change, what it may not change however it answers, and what happens when it is broken.
 *
 * Everything runs against a real [MfFactionService] over an in-memory repository. A mocked service
 * would assert only that this test agrees with itself, and the behaviour under test lives in the
 * interaction between the registry, the ladder and the save.
 */
class MfFactionSuccessionPolicyTest {

    private lateinit var plugin: MedievalFactions
    private lateinit var config: FileConfiguration
    private lateinit var factionService: MfFactionService
    private lateinit var relationshipService: MfFactionRelationshipService
    private lateinit var api: DefaultMedievalFactionsApi
    private val announced = mutableListOf<Event>()

    private val ruler = MfPlayerId(UUID.randomUUID().toString())
    private val marshal = MfPlayerId(UUID.randomUUID().toString())
    private val steward = MfPlayerId(UUID.randomUUID().toString())
    private val outsider = MfPlayerId(UUID.randomUUID().toString())

    private class InMemoryFactionRepository : MfFactionRepository {
        val rows = java.util.concurrent.ConcurrentHashMap<MfFactionId, MfFaction>()
        override fun getFaction(id: MfFactionId) = rows[id]
        override fun getFaction(name: String) = rows.values.firstOrNull { it.name == name }
        override fun getFaction(playerId: MfPlayerId) = rows.values.firstOrNull { it.isMember(playerId) }
        override fun getFactions() = rows.values.toList()

        @Synchronized
        override fun upsert(faction: MfFaction): MfFaction {
            val current = rows[faction.id]
            val persisted = if (current == null) {
                faction.copy(version = 1)
            } else {
                if (current.version != faction.version) {
                    throw OptimisticLockingFailureException("Invalid version: ${faction.version}")
                }
                faction.copy(version = faction.version + 1)
            }
            rows[faction.id] = persisted
            return persisted
        }
        override fun delete(factionId: MfFactionId) {
            rows.remove(factionId)
        }
    }

    private class EmptyLockRepository : MfLockRepository {
        override fun getLockedBlock(id: MfLockedBlockId): MfLockedBlock? = null
        override fun getLockedBlock(worldId: UUID, x: Int, y: Int, z: Int): MfLockedBlock? = null
        override fun getLockedBlocks(): List<MfLockedBlock> = emptyList()
        override fun upsert(lockedBlock: MfLockedBlock): MfLockedBlock = lockedBlock
        override fun delete(lockedBlock: MfLockedBlock) = Unit
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
        override fun upsert(relationship: MfFactionRelationship) = relationship.also { rows[it.id] = it }
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
        `when`(config.getBoolean("factions.allowNeutrality")).thenReturn(true)
        allowLeaderlessFactions(true)
        val flags = MfFlags(plugin)
        `when`(plugin.flags).thenReturn(flags)
        val permissions = MfFactionPermissions(plugin)
        `when`(plugin.factionPermissions).thenReturn(permissions)

        val server = mock(Server::class.java)
        `when`(plugin.server).thenReturn(server)
        val pluginManager = mock(PluginManager::class.java)
        `when`(server.pluginManager).thenReturn(pluginManager)
        `when`(server.isPrimaryThread).thenReturn(false)
        // Records what MF announced. Every assertion about the event below reads this list, so a
        // notification that is fired but never delivered - the shape MfFactionService deliberately
        // tolerates when the scheduler refuses - is distinguishable from one that never fired.
        doAnswerRecording(pluginManager)
        val scheduler = mock(BukkitScheduler::class.java)
        `when`(server.scheduler).thenReturn(scheduler)
        `when`(scheduler.runTask(any(Plugin::class.java), any(Runnable::class.java))).thenAnswer { invocation ->
            invocation.getArgument<Runnable>(1).run()
            mock(BukkitTask::class.java)
        }

        relationshipService = MfFactionRelationshipService(plugin, InMemoryRelationshipRepository())
        factionService = MfFactionService(plugin, InMemoryFactionRepository())
        val services = mock(Services::class.java)
        val lockService = MfLockService(plugin, EmptyLockRepository())
        `when`(services.factionService).thenReturn(factionService)
        `when`(services.factionRelationshipService).thenReturn(relationshipService)
        `when`(services.lockService).thenReturn(lockService)
        `when`(services.claimService).thenReturn(null)
        `when`(plugin.services).thenReturn(services)
        `when`(plugin.servicesOrNull).thenReturn(services)
        api = DefaultMedievalFactionsApi(plugin)
        announced.clear()
    }

    private fun doAnswerRecording(pluginManager: PluginManager) {
        org.mockito.Mockito.doAnswer { invocation ->
            announced.add(invocation.getArgument(0))
            null
        }.`when`(pluginManager).callEvent(any(Event::class.java))
    }

    private fun allowLeaderlessFactions(allowed: Boolean) {
        `when`(config.getBoolean("factions.allowLeaderlessFactions")).thenReturn(allowed)
    }

    /**
     * A faction whose head is its first member, with the rest joining later in the order given.
     *
     * Everyone below the head holds the DEFAULT role, so MF's ladder falls through to its last tier
     * and picks on standing. That is deliberate: it makes "the ladder ran" and "the policy ran"
     * produce visibly different answers, which several of these tests depend on.
     */
    private fun foundFaction(name: String, head: MfPlayerId, others: List<MfPlayerId>): MfFaction {
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

    private fun current(faction: MfFaction): MfFaction = factionService.getFaction(faction.id)!!

    /** Removes a member the way /f leave and /f kick both do: by rewriting the member list. */
    private fun depart(faction: MfFaction, playerId: MfPlayerId): MfFaction =
        factionService.save(current(faction).copy(members = current(faction).members.filter { it.playerId != playerId }))
            .onFailure { throw it.reason.cause }

    private fun uuid(id: MfPlayerId): UUID = UUID.fromString(id.value)

    private fun ownerChanges(): List<FactionPrimaryOwnerChangedEvent> =
        announced.filterIsInstance<FactionPrimaryOwnerChangedEvent>()

    // --- the ladder, unchanged when nobody is registered ---

    @Test
    @DisplayName("with no policy registered, MedievalFactions' own order still decides")
    fun theLadderStillAppliesWithNoPolicy() {
        val faction = foundFaction("Kingdom", ruler, listOf(marshal, steward))

        depart(faction, ruler)

        assertEquals(marshal, current(faction).primaryOwnerId, "the longest-standing remaining member")
        assertTrue(factionService.successionPolicies.isEmpty())
    }

    // --- what a policy may decide ---

    @Test
    @DisplayName("a policy naming a member seats them instead of the one next in line")
    fun aPolicyOutranksTheLadder() {
        val faction = foundFaction("Kingdom", ruler, listOf(marshal, steward))
        factionService.successionPolicies.register { _, _ -> uuid(steward) }

        depart(faction, ruler)

        assertEquals(steward, current(faction).primaryOwnerId, "the policy's choice, not MF's")
    }

    @Test
    @DisplayName("a policy that defers leaves the ladder to decide")
    fun deferringIsSafe() {
        val faction = foundFaction("Kingdom", ruler, listOf(marshal, steward))
        factionService.successionPolicies.register { _, _ -> null }

        depart(faction, ruler)

        assertEquals(marshal, current(faction).primaryOwnerId)
    }

    @Test
    @DisplayName("a policy sees the member list with the departing head already gone")
    fun thePolicySeesTheVacancy() {
        val faction = foundFaction("Kingdom", ruler, listOf(marshal, steward))
        var seenMembers: List<UUID> = emptyList()
        var seenDeparting: UUID? = null
        var seenHead: UUID? = null
        factionService.successionPolicies.register { view, departing ->
            seenMembers = view.memberIds
            seenDeparting = departing
            seenHead = view.primaryOwnerId
            null
        }

        depart(faction, ruler)

        assertEquals(uuid(ruler), seenDeparting)
        assertEquals(uuid(ruler), seenHead, "primaryOwnerId still names the head who left")
        assertFalse(seenMembers.contains(uuid(ruler)), "but the member list no longer does")
        assertEquals(listOf(uuid(marshal), uuid(steward)), seenMembers)
    }

    @Test
    @DisplayName("a policy is not consulted while the head is still a member")
    fun noVacancyMeansNoConsultation() {
        val faction = foundFaction("Kingdom", ruler, listOf(marshal, steward))
        var consulted = false
        factionService.successionPolicies.register { _, _ ->
            consulted = true
            null
        }

        factionService.save(current(faction).copy(description = "A realm")).onFailure { throw it.reason.cause }

        assertFalse(consulted, "an ordinary save is not a succession")
        assertEquals(ruler, current(faction).primaryOwnerId)
    }

    // --- what a policy may not decide, however it answers ---

    @Test
    @DisplayName("a policy naming somebody who is not a member is ignored")
    fun anOutsiderCannotBeSeated() {
        val faction = foundFaction("Kingdom", ruler, listOf(marshal, steward))
        factionService.successionPolicies.register { _, _ -> uuid(outsider) }

        depart(faction, ruler)

        assertEquals(
            marshal,
            current(faction).primaryOwnerId,
            "otherwise a third-party plugin could hand a stranger a faction's land and treasury"
        )
    }

    @Test
    @DisplayName("a policy naming the departing head is ignored")
    fun theDepartedCannotBeReinstated() {
        val faction = foundFaction("Kingdom", ruler, listOf(marshal, steward))
        factionService.successionPolicies.register { _, departing -> departing }

        depart(faction, ruler)

        assertEquals(
            marshal,
            current(faction).primaryOwnerId,
            "otherwise leaving would be impossible: every save would restore the head the member " +
                "list no longer contains"
        )
        assertFalse(current(faction).isMember(ruler))
    }

    @Test
    @DisplayName("a policy cannot empty a seat the server forbids emptying")
    fun aPolicyCannotOverrideAllowLeaderlessFactions() {
        allowLeaderlessFactions(false)
        val faction = foundFaction("Kingdom", ruler, listOf())
        // The interface offers no way to answer "nobody", so the strongest a policy can do is defer
        // and hope the ladder finds no one. It must not turn that into a headless faction.
        factionService.successionPolicies.register { _, _ -> null }

        assertThrows(NoSuccessorException::class.java) {
            factionService.save(current(faction).copy(members = emptyList()))
                .onFailure { throw it.reason.cause }
        }
    }

    // --- a broken policy ---

    @Test
    @DisplayName("a policy that throws is disabled for the session and never asked again")
    fun aBrokenPolicyIsContainedRatherThanFatal() {
        val faction = foundFaction("Kingdom", ruler, listOf(marshal, steward))
        var calls = 0
        factionService.successionPolicies.register { _, _ ->
            calls++
            throw NoClassDefFoundError("compiled against a class this jar no longer has")
        }

        depart(faction, ruler)
        assertEquals(marshal, current(faction).primaryOwnerId, "MF's order applies")

        val second = foundFaction("Duchy", steward, listOf(outsider))
        depart(second, steward)

        assertEquals(outsider, current(second).primaryOwnerId)
        assertEquals(1, calls, "poisoned after the first throw, so a broken plugin is logged once")
    }

    @Test
    @DisplayName("a broken policy does not stop a player leaving")
    fun aBrokenPolicyDoesNotFailTheSave() {
        val faction = foundFaction("Kingdom", ruler, listOf(marshal))
        factionService.successionPolicies.register { _, _ -> throw IllegalStateException("bug") }

        depart(faction, ruler)

        assertFalse(current(faction).isMember(ruler), "the departure was persisted")
    }

    @Test
    @DisplayName("unregistering re-arms a policy that had been poisoned")
    fun unregisteringClearsThePoison() {
        val faction = foundFaction("Kingdom", ruler, listOf(marshal, steward))
        val policy = SuccessionPolicy { _, _ -> uuid(steward) }
        factionService.successionPolicies.register(policy)
        factionService.successionPolicies.unregister(policy)
        factionService.successionPolicies.register(policy)

        depart(faction, ruler)

        assertEquals(steward, current(faction).primaryOwnerId)
    }

    // --- the announcement ---

    @Test
    @DisplayName("a succession is announced with who left and who took over")
    fun successionIsAnnounced() {
        val faction = foundFaction("Kingdom", ruler, listOf(marshal, steward))
        announced.clear()

        depart(faction, ruler)

        val change = ownerChanges().single()
        assertEquals(faction.id.value, change.faction.value)
        assertEquals(uuid(ruler), change.previousOwnerId)
        assertEquals(uuid(marshal), change.newOwnerId)
    }

    @Test
    @DisplayName("founding a faction is not announced as a change of head")
    fun creationIsNotAChange() {
        foundFaction("Kingdom", ruler, listOf(marshal))

        assertTrue(
            ownerChanges().isEmpty(),
            "the head going from nobody to the founder is what FactionCreateEvent already reports"
        )
    }

    @Test
    @DisplayName("a save that leaves the head alone is not announced")
    fun anOrdinarySaveIsNotAnnounced() {
        val faction = foundFaction("Kingdom", ruler, listOf(marshal))
        announced.clear()

        factionService.save(current(faction).copy(description = "A realm")).onFailure { throw it.reason.cause }

        assertTrue(ownerChanges().isEmpty())
    }

    // --- the write ---

    @Test
    @DisplayName("setPrimaryOwner seats a member and announces it")
    fun theApiCanSeatAMember() {
        val faction = foundFaction("Kingdom", ruler, listOf(marshal, steward))
        announced.clear()

        val result = api.setPrimaryOwner(FactionId(faction.id.value), uuid(steward))

        assertTrue(result.isSuccess, result.errorMessage ?: "")
        assertEquals(steward, current(faction).primaryOwnerId)
        assertEquals(uuid(steward), ownerChanges().single().newOwnerId)
    }

    @Test
    @DisplayName("setPrimaryOwner refuses somebody who is not a member")
    fun theApiRefusesAnOutsider() {
        val faction = foundFaction("Kingdom", ruler, listOf(marshal))

        val result = api.setPrimaryOwner(FactionId(faction.id.value), uuid(outsider))

        assertFalse(result.isSuccess)
        assertEquals(ruler, current(faction).primaryOwnerId, "and changes nothing")
    }

    @Test
    @DisplayName("setPrimaryOwner refuses an unknown faction")
    fun theApiRefusesAnUnknownFaction() {
        assertFalse(api.setPrimaryOwner(FactionId("no-such-faction"), uuid(ruler)).isSuccess)
    }

    @Test
    @DisplayName("setPrimaryOwner is a no-op when the player is already the head")
    fun seatingTheSittingHeadChangesNothing() {
        val faction = foundFaction("Kingdom", ruler, listOf(marshal))
        announced.clear()

        assertTrue(api.setPrimaryOwner(FactionId(faction.id.value), uuid(ruler)).isSuccess)

        assertTrue(ownerChanges().isEmpty(), "so a caller reconciling its own state need not compare first")
    }

    @Test
    @DisplayName("seating the standing heir consumes the nomination")
    fun seatingTheHeirClearsTheNomination() {
        val faction = foundFaction("Kingdom", ruler, listOf(marshal, steward))
        factionService.save(current(faction).copy(heirId = steward)).onFailure { throw it.reason.cause }

        api.setPrimaryOwner(FactionId(faction.id.value), uuid(steward))

        assertEquals(steward, current(faction).primaryOwnerId)
        assertNull(current(faction).heirId, "a faction that is its own heir is not a state worth having")
    }

    @Test
    @DisplayName("seating somebody else leaves an unrelated nomination standing")
    fun anUnrelatedNominationSurvives() {
        val faction = foundFaction("Kingdom", ruler, listOf(marshal, steward))
        factionService.save(current(faction).copy(heirId = steward)).onFailure { throw it.reason.cause }

        api.setPrimaryOwner(FactionId(faction.id.value), uuid(marshal))

        assertEquals(marshal, current(faction).primaryOwnerId)
        assertEquals(steward, current(faction).heirId, "the outgoing head's choice is not invalidated by this")
    }

    @Test
    @DisplayName("setPrimaryOwner does not hand over the right to disband")
    fun seatingIsIdentityNotCapability() {
        val faction = foundFaction("Kingdom", ruler, listOf(marshal))

        api.setPrimaryOwner(FactionId(faction.id.value), uuid(marshal))

        val view: FactionView = api.getFaction(FactionId(faction.id.value))!!
        assertEquals(uuid(marshal), view.primaryOwnerId)
        assertFalse(
            view.isLeader(uuid(marshal)),
            "a regent seated for a fortnight should not silently acquire the power to dissolve the realm"
        )
    }

    // --- the read ---

    @Test
    @DisplayName("the API exposes the standing nomination")
    fun theHeirIsVisibleThroughTheApi() {
        val faction = foundFaction("Kingdom", ruler, listOf(marshal, steward))
        factionService.save(current(faction).copy(heirId = steward)).onFailure { throw it.reason.cause }

        val view = api.getFaction(FactionId(faction.id.value))!!

        assertEquals(uuid(steward), view.heirId)
    }

    @Test
    @DisplayName("a faction with no nomination reports none")
    fun noNominationReadsAsNull() {
        val faction = foundFaction("Kingdom", ruler, listOf(marshal))

        assertNull(api.getFaction(FactionId(faction.id.value))!!.heirId)
    }

    @Test
    @DisplayName("the tenure stamp moves when the head does, and only then")
    fun theTenureStampFollowsTheSeat() {
        // The gap this closes: MF recorded WHO the head was and not WHEN they became it, so a tenure
        // rule could be enforced for a sub-group whose own plugin records a grant time and silently
        // did nothing for the faction itself.
        val faction = foundFaction("Kingdom", ruler, listOf(marshal, steward))
        val founded = current(faction).primaryOwnerSince
        val foundingTerm = current(faction).primaryOwnerTerm
        assertTrue(founded > 0L, "founding a faction seats its founder, so the stamp is set")

        // An ordinary save that leaves the head alone must NOT push the date forward, or every
        // /f desc would reset the tenure of whoever is in the seat.
        factionService.save(current(faction).copy(description = "A realm")).onFailure { throw it.reason.cause }
        assertEquals(founded, current(faction).primaryOwnerSince)
        assertEquals(foundingTerm, current(faction).primaryOwnerTerm)

        // Moving the head restamps it, so the new ruler starts their tenure now rather than
        // inheriting the old one's standing along with the seat.
        factionService.save(current(faction).copy(primaryOwnerId = steward)).onFailure { throw it.reason.cause }
        assertTrue(current(faction).primaryOwnerSince >= founded)
        assertEquals(steward, current(faction).primaryOwnerId)
        assertTrue(current(faction).primaryOwnerTerm != foundingTerm)
    }

    @Test
    @DisplayName("the API publishes the tenure, and zero for a faction that predates it")
    fun theTenureIsVisibleThroughTheApi() {
        val faction = foundFaction("Kingdom", ruler, listOf(marshal))

        val view = api.getFaction(FactionId(faction.id.value))!!
        assertTrue(view.primaryOwnerSince > 0L)

        // Zero is what every faction saved before this column existed carries, and a consumer must
        // read it as long-held rather than as new -- otherwise the day this ships freezes every
        // faction on the server out of a tenure rule for its first week.
        factionService.save(current(faction).copy(primaryOwnerSince = 0L)).onFailure { throw it.reason.cause }
        assertEquals(0L, api.getFaction(FactionId(faction.id.value))!!.primaryOwnerSince)
    }

    @Test
    @DisplayName("the API publishes the exact primary-owner term")
    fun theOwnerTermIsVisibleThroughTheApi() {
        val faction = foundFaction("Kingdom", ruler, listOf(marshal))

        assertEquals(current(faction).primaryOwnerTerm, api.getFaction(FactionId(faction.id.value))!!.primaryOwnerTerm)
    }

    @Test
    @DisplayName("an exact tenure may be cleared without removing members")
    fun exactOwnerTenureMayBeCleared() {
        val faction = foundFaction("Kingdom", ruler, listOf(marshal, steward))
        val before = current(faction)
        allowLeaderlessFactions(false)

        val outcome = api.replacePrimaryOwnerIf(
            FactionId(faction.id.value),
            uuid(ruler),
            before.primaryOwnerTerm,
            null
        )

        assertTrue(outcome.isSuccess)
        assertEquals(PrimaryOwnerReplaceOutcome.REPLACED, outcome.get())
        assertNull(current(faction).primaryOwnerId)
        assertEquals(before.members, current(faction).members)
        assertEquals(before.heirId, current(faction).heirId)
        assertTrue(before.primaryOwnerTerm != current(faction).primaryOwnerTerm)
    }

    @Test
    @DisplayName("an away-and-back owner transition defeats a stale exact-tenure clear")
    fun reacquiredOwnerTenureIsNotCleared() {
        val faction = foundFaction("Kingdom", ruler, listOf(marshal))
        val staleTerm = current(faction).primaryOwnerTerm
        api.setPrimaryOwner(FactionId(faction.id.value), uuid(marshal))
        api.setPrimaryOwner(FactionId(faction.id.value), uuid(ruler))
        assertTrue(staleTerm != current(faction).primaryOwnerTerm)

        val outcome = api.replacePrimaryOwnerIf(
            FactionId(faction.id.value),
            uuid(ruler),
            staleTerm,
            null
        )

        assertTrue(outcome.isSuccess)
        assertEquals(PrimaryOwnerReplaceOutcome.MISMATCH, outcome.get())
        assertEquals(ruler, current(faction).primaryOwnerId)
    }

    @Test
    @DisplayName("repository versioning arbitrates exact owner CAS against an in-flight save")
    fun exactOwnerCasConflictsWithInFlightOrdinarySaveWithoutStaleOverwrite() {
        val faction = foundFaction("Kingdom", ruler, listOf(marshal))
        val staleTerm = current(faction).primaryOwnerTerm
        val saveReachedPreCommit = CountDownLatch(1)
        val releaseSave = CountDownLatch(1)
        val saveFinished = CountDownLatch(1)
        val casStarted = CountDownLatch(1)
        val casFinished = CountDownLatch(1)
        val casOutcome = AtomicReference<com.dansplugins.factionsystem.api.ApiOutcome<PrimaryOwnerReplaceOutcome>>()
        val saveFailure = AtomicReference<Throwable>()

        val pluginManager = plugin.server.pluginManager
        org.mockito.Mockito.doAnswer { invocation ->
            val event = invocation.getArgument<Event>(0)
            announced.add(event)
            if (event is FactionDescriptionChangeEvent && event.description == "barrier") {
                saveReachedPreCommit.countDown()
                assertTrue(releaseSave.await(5, TimeUnit.SECONDS))
            }
            null
        }.`when`(pluginManager).callEvent(any(Event::class.java))

        val saver = thread(name = "ordinary-faction-save") {
            try {
                val result = factionService.save(
                    current(faction).copy(primaryOwnerId = marshal, description = "barrier")
                )
                if (result is dev.forkhandles.result4k.Failure) {
                    saveFailure.set(result.reason.cause)
                }
            } finally {
                saveFinished.countDown()
            }
        }
        assertTrue(saveReachedPreCommit.await(5, TimeUnit.SECONDS))

        val cas = thread(name = "exact-owner-cas") {
            casStarted.countDown()
            casOutcome.set(
                api.replacePrimaryOwnerIf(
                    FactionId(faction.id.value),
                    uuid(ruler),
                    staleTerm,
                    null
                )
            )
            casFinished.countDown()
        }
        assertTrue(casStarted.await(5, TimeUnit.SECONDS))
        assertTrue(
            casFinished.await(5, TimeUnit.SECONDS),
            "the exact CAS was blocked behind a pre-commit callback"
        )
        assertEquals(PrimaryOwnerReplaceOutcome.REPLACED, casOutcome.get().get())

        releaseSave.countDown()
        assertTrue(saveFinished.await(5, TimeUnit.SECONDS))
        saver.join()
        cas.join()

        assertTrue(saveFailure.get() is OptimisticLockingFailureException)
        assertNull(current(faction).primaryOwnerId)
        assertFalse(current(faction).description == "barrier")
        assertTrue(staleTerm != current(faction).primaryOwnerTerm)
    }

    @Test
    @DisplayName("a stale unrelated faction copy cannot reinstate the owner replaced by exact CAS")
    fun staleWholeFactionCopyCannotUndoExactOwnerCas() {
        val faction = foundFaction("Kingdom", ruler, listOf(marshal))
        val before = current(faction)
        val staleDescriptionEdit = before.copy(description = "prepared before the CAS")

        val outcome = api.replacePrimaryOwnerIf(
            FactionId(faction.id.value),
            uuid(ruler),
            before.primaryOwnerTerm,
            uuid(marshal)
        )
        val staleSave = factionService.save(staleDescriptionEdit)

        assertEquals(PrimaryOwnerReplaceOutcome.REPLACED, outcome.get())
        assertTrue(staleSave is dev.forkhandles.result4k.Failure)
        assertEquals(marshal, current(faction).primaryOwnerId)
        assertTrue(before.primaryOwnerTerm != current(faction).primaryOwnerTerm)
        assertFalse(current(faction).description == staleDescriptionEdit.description)
    }
}
