package com.dansplugins.factionsystem.faction

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.anyArg
import com.dansplugins.factionsystem.api.FactionId
import com.dansplugins.factionsystem.api.impl.DefaultMedievalFactionsApi
import com.dansplugins.factionsystem.event.faction.FactionDeletedEvent
import com.dansplugins.factionsystem.event.faction.FactionDisbandEvent
import com.dansplugins.factionsystem.faction.flag.MfFlags
import com.dansplugins.factionsystem.faction.permission.MfFactionPermissions
import com.dansplugins.factionsystem.faction.role.MfFactionRoles
import com.dansplugins.factionsystem.failure.OptimisticLockingFailureException
import com.dansplugins.factionsystem.gate.MfGateService
import com.dansplugins.factionsystem.lang.Language
import com.dansplugins.factionsystem.locks.MfLockRepository
import com.dansplugins.factionsystem.locks.MfLockService
import com.dansplugins.factionsystem.locks.MfLockedBlock
import com.dansplugins.factionsystem.locks.MfLockedBlockId
import com.dansplugins.factionsystem.map.MapService
import com.dansplugins.factionsystem.player.MfPlayerId
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipService
import com.dansplugins.factionsystem.service.Services
import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Result4k
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.RETURNS_SMART_NULLS
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger
import kotlin.concurrent.thread

class MfFactionMutationLifecycleTest {

    private lateinit var plugin: MedievalFactions
    private lateinit var config: FileConfiguration
    private lateinit var repository: InMemoryFactionRepository
    private lateinit var service: MfFactionService
    private lateinit var api: DefaultMedievalFactionsApi
    private lateinit var claims: com.dansplugins.factionsystem.claim.MfClaimService
    private lateinit var gates: MfGateService
    private lateinit var relationships: MfFactionRelationshipService
    private lateinit var mapService: MapService
    private val events = CopyOnWriteArrayList<Event>()
    private val publicationOrder = CopyOnWriteArrayList<String>()

    @Volatile private var cancelDisband = false

    @Volatile private var callbackDeletes: Map<MfFactionId, MfFactionId> = emptyMap()

    @Volatile private var callbackBarrier: CountDownLatch? = null
    private val callbackDeleteResults = ConcurrentHashMap<MfFactionId, Result4k<Unit, *>>()

    @BeforeEach
    fun setUp() {
        plugin = mock(MedievalFactions::class.java)
        val logger = mock(Logger::class.java)
        `when`(plugin.logger).thenReturn(logger)
        val language = mock(Language::class.java, RETURNS_SMART_NULLS)
        `when`(plugin.language).thenReturn(language)
        config = mock(FileConfiguration::class.java)
        `when`(plugin.config).thenReturn(config)
        `when`(config.getBoolean("factions.allowNeutrality")).thenReturn(true)
        `when`(config.getBoolean("factions.allowLeaderlessFactions")).thenReturn(true)
        val flags = MfFlags(plugin)
        `when`(plugin.flags).thenReturn(flags)
        val permissions = MfFactionPermissions(plugin)
        `when`(plugin.factionPermissions).thenReturn(permissions)

        val server = mock(Server::class.java)
        `when`(plugin.server).thenReturn(server)
        `when`(server.isPrimaryThread).thenReturn(false)
        val manager = mock(PluginManager::class.java)
        `when`(server.pluginManager).thenReturn(manager)
        doAnswer { invocation ->
            val event = invocation.getArgument<Event>(0)
            events += event
            if (event is FactionDeletedEvent) publicationOrder += "deleted:${event.factionId.value}"
            if (cancelDisband && event is FactionDisbandEvent) event.isCancelled = true
            null
        }.`when`(manager).callEvent(any(Event::class.java))
        val scheduler = mock(BukkitScheduler::class.java)
        `when`(server.scheduler).thenReturn(scheduler)
        `when`(scheduler.runTask(any(Plugin::class.java), any(Runnable::class.java))).thenAnswer { invocation ->
            invocation.getArgument<Runnable>(1).run()
            mock(BukkitTask::class.java)
        }

        repository = InMemoryFactionRepository()
        service = MfFactionService(plugin, repository)
        claims = mock(com.dansplugins.factionsystem.claim.MfClaimService::class.java)
        gates = mock(MfGateService::class.java)
        relationships = mock(MfFactionRelationshipService::class.java)
        mapService = mock(MapService::class.java)
        doAnswer { invocation ->
            val faction = invocation.getArgument<MfFaction>(0)
            publicationOrder += if (service.getFaction(faction.id) == null) {
                "map-after-delete:${faction.id.value}"
            } else {
                "map-before-delete:${faction.id.value}"
            }
            callbackDeletes[faction.id]?.let { target ->
                val barrier = requireNotNull(callbackBarrier)
                barrier.countDown()
                check(barrier.await(5, TimeUnit.SECONDS))
                callbackDeleteResults[faction.id] = service.delete(target)
            }
            null
        }.`when`(mapService).scheduleUpdateClaims(anyArg())

        val services = mock(Services::class.java)
        `when`(services.factionService).thenReturn(service)
        val lockService = MfLockService(plugin, EmptyLockRepository())
        `when`(services.lockService).thenReturn(lockService)
        `when`(services.claimService).thenReturn(claims)
        `when`(services.gateService).thenReturn(gates)
        `when`(services.factionRelationshipService).thenReturn(relationships)
        `when`(services.mapService).thenReturn(mapService)
        `when`(plugin.services).thenReturn(services)
        `when`(plugin.servicesOrNull).thenReturn(services)
        api = DefaultMedievalFactionsApi(plugin)
        events.clear()
        publicationOrder.clear()
    }

    @Test
    fun deleteWaitsThroughCommittedSavePublicationBeforeRemovingFaction() {
        val member = MfPlayerId(UUID.randomUUID().toString())
        val faction = createFaction("Lifecycle", listOf(member))
        events.clear()
        publicationOrder.clear()
        repository.blockNextSave(faction.id)

        val saveResult = AtomicReference<Result4k<MfFaction, *>>()
        val saveFinished = CountDownLatch(1)
        val saver = thread(name = "faction-save") {
            try {
                saveResult.set(service.save(current(faction).copy(description = "committed")))
            } finally {
                saveFinished.countDown()
            }
        }
        assertTrue(repository.saveCommitted.await(5, TimeUnit.SECONDS))

        val deleteStarted = CountDownLatch(1)
        val deleteFinished = CountDownLatch(1)
        val deleteResult = AtomicReference<Result4k<Unit, *>>()
        val deleter = thread(name = "faction-delete") {
            deleteStarted.countDown()
            try {
                deleteResult.set(service.delete(faction.id))
            } finally {
                deleteFinished.countDown()
            }
        }
        assertTrue(deleteStarted.await(5, TimeUnit.SECONDS))
        assertFalse(
            deleteFinished.await(250, TimeUnit.MILLISECONDS),
            "delete crossed a save whose repository commit had not yet published"
        )

        repository.releaseSave.countDown()
        assertTrue(saveFinished.await(5, TimeUnit.SECONDS))
        assertTrue(deleteFinished.await(5, TimeUnit.SECONDS))
        saver.join()
        deleter.join()

        assertFalse(saveResult.get() is Failure)
        assertFalse(deleteResult.get() is Failure)
        assertNull(service.getFaction(faction.id))
        val savePublication = publicationOrder.indexOf("map-before-delete:${faction.id.value}")
        val deletion = publicationOrder.indexOf("deleted:${faction.id.value}")
        assertTrue(savePublication >= 0 && deletion > savePublication, publicationOrder.toString())
        assertEquals(
            0,
            publicationOrder.drop(deletion + 1).count { it == "map-before-delete:${faction.id.value}" },
            "a committed-save callback ran after the faction was deleted"
        )
    }

    @Test
    fun crossFactionCallbackDeletesFailFastInsteadOfDeadlocking() {
        val first = createFaction("First", listOf(MfPlayerId(UUID.randomUUID().toString())))
        val second = createFaction("Second", listOf(MfPlayerId(UUID.randomUUID().toString())))
        callbackDeletes = mapOf(first.id to second.id, second.id to first.id)
        callbackBarrier = CountDownLatch(2)
        val finished = CountDownLatch(2)

        val one = thread(name = "publish-first") {
            service.save(current(first).copy(description = "one"))
            finished.countDown()
        }
        val two = thread(name = "publish-second") {
            service.save(current(second).copy(description = "two"))
            finished.countDown()
        }

        assertTrue(finished.await(5, TimeUnit.SECONDS), "cross-publication delete deadlocked")
        one.join()
        two.join()
        assertTrue(callbackDeleteResults[first.id] is Failure)
        assertTrue(callbackDeleteResults[second.id] is Failure)
        assertTrue(service.getFaction(first.id) != null)
        assertTrue(service.getFaction(second.id) != null)
    }

    @Test
    fun exactAllMemberTransferIsAtomicAcrossCancellationFailureAndRetry() {
        val first = MfPlayerId(UUID.randomUUID().toString())
        val second = MfPlayerId(UUID.randomUUID().toString())
        val source = createFaction("Source", listOf(first, second))
        // Models a legacy partial attempt: first exists in both caches before the repairing retry.
        val destination = createFaction("Destination", listOf(first))
        clearInvocations(claims, gates, relationships)
        events.clear()

        cancelDisband = true
        val cancelled = api.transferMembers(
            FactionId(source.id.value),
            FactionId(destination.id.value),
            listOf(UUID.fromString(first.value), UUID.fromString(second.value))
        )
        assertTrue(cancelled.isFailure)
        assertEquals(setOf(first, second), current(source).members.map { it.playerId }.toSet())
        assertEquals(listOf(first), current(destination).members.map { it.playerId })
        assertEquals(0, repository.atomicMutationCalls)

        cancelDisband = false
        repository.failAtomicMutation = true
        val failed = api.transferMembers(
            FactionId(source.id.value),
            FactionId(destination.id.value),
            listOf(UUID.fromString(first.value), UUID.fromString(second.value))
        )
        assertTrue(failed.isFailure)
        assertEquals(setOf(first, second), current(source).members.map { it.playerId }.toSet())
        assertEquals(listOf(first), current(destination).members.map { it.playerId })

        repository.failAtomicMutation = false
        val cacheMidpoint = CountDownLatch(1)
        val releaseCachePublication = CountDownLatch(1)
        service.cachePublicationHook = {
            cacheMidpoint.countDown()
            check(releaseCachePublication.await(5, TimeUnit.SECONDS))
        }
        val retried = AtomicReference<com.dansplugins.factionsystem.api.ApiResult>()
        val transferFinished = CountDownLatch(1)
        val transfer = thread(name = "atomic-member-transfer") {
            try {
                retried.set(
                    api.transferMembers(
                        FactionId(source.id.value),
                        FactionId(destination.id.value),
                        listOf(UUID.fromString(first.value), UUID.fromString(second.value))
                    )
                )
            } finally {
                transferFinished.countDown()
            }
        }
        assertTrue(cacheMidpoint.await(5, TimeUnit.SECONDS))
        val readerFinished = CountDownLatch(1)
        val observedFaction = AtomicReference<MfFaction?>()
        val reader = thread(name = "membership-reader") {
            observedFaction.set(service.getFaction(first))
            readerFinished.countDown()
        }
        assertFalse(
            readerFinished.await(250, TimeUnit.MILLISECONDS),
            "a reader entered between destination publication and source removal"
        )
        releaseCachePublication.countDown()
        assertTrue(transferFinished.await(5, TimeUnit.SECONDS))
        assertTrue(readerFinished.await(5, TimeUnit.SECONDS))
        transfer.join()
        reader.join()

        assertTrue(retried.get().isSuccess)
        assertNull(service.getFaction(source.id))
        assertEquals(destination.id, observedFaction.get()?.id)
        val finalMembers = current(destination).members.map(MfFactionMember::playerId)
        assertEquals(1, finalMembers.count { it == first })
        assertEquals(1, finalMembers.count { it == second })
        assertEquals(2, repository.atomicMutationCalls, "failed attempt plus successful retry")
        assertEquals(1, events.filterIsInstance<FactionDeletedEvent>().count { it.factionId == source.id })
        verify(claims, times(1)).evictAllClaims(source.id)
        verify(gates, times(1)).evictAllGates(source.id)
        verify(relationships, times(1)).evictForDeletedFaction(source.id)
    }

    @Test
    fun allMemberTransferRefusesAStaleFrozenRoster() {
        val present = MfPlayerId(UUID.randomUUID().toString())
        val departed = MfPlayerId(UUID.randomUUID().toString())
        val source = createFaction("Source", listOf(present))
        val destination = createFaction("Destination", listOf(MfPlayerId(UUID.randomUUID().toString())))

        val result = api.transferMembers(
            FactionId(source.id.value),
            FactionId(destination.id.value),
            listOf(UUID.fromString(present.value), UUID.fromString(departed.value))
        )

        assertTrue(result.isFailure)
        assertTrue(service.getFaction(source.id) != null)
        assertEquals(listOf(present), current(source).members.map(MfFactionMember::playerId))
        assertEquals(0, repository.atomicMutationCalls)
    }

    private fun createFaction(name: String, members: List<MfPlayerId>): MfFaction {
        val id = MfFactionId.generate()
        val roles = MfFactionRoles.defaults(plugin, id)
        val roster = members.mapIndexed { index, playerId ->
            MfFactionMember(
                playerId,
                if (index == 0) requireNotNull(roles.leaderRole) else roles.default,
                joinedAt = index + 1L
            )
        }
        return service.save(
            MfFaction(
                plugin,
                id = id,
                name = name,
                roles = roles,
                members = roster,
                primaryOwnerId = members.firstOrNull()
            )
        ).onFailure { throw it.reason.cause }
    }

    private fun current(faction: MfFaction): MfFaction = requireNotNull(service.getFaction(faction.id))

    private class EmptyLockRepository : MfLockRepository {
        override fun getLockedBlock(id: MfLockedBlockId): MfLockedBlock? = null
        override fun getLockedBlock(worldId: UUID, x: Int, y: Int, z: Int): MfLockedBlock? = null
        override fun getLockedBlocks(): List<MfLockedBlock> = emptyList()
        override fun upsert(lockedBlock: MfLockedBlock): MfLockedBlock = lockedBlock
        override fun delete(lockedBlock: MfLockedBlock) = Unit
    }

    private class InMemoryFactionRepository : MfFactionRepository {
        val rows = ConcurrentHashMap<MfFactionId, MfFaction>()

        @Volatile var failAtomicMutation = false

        @Volatile var atomicMutationCalls = 0

        @Volatile private var blockedSaveId: MfFactionId? = null

        @Volatile var saveCommitted = CountDownLatch(1)

        @Volatile var releaseSave = CountDownLatch(1)

        override fun getFaction(id: MfFactionId) = rows[id]
        override fun getFaction(name: String) = rows.values.firstOrNull { it.name == name }
        override fun getFaction(playerId: MfPlayerId) = rows.values.firstOrNull { it.isMember(playerId) }
        override fun getFactions() = rows.values.toList()

        @Synchronized
        override fun upsert(faction: MfFaction): MfFaction = upsertAll(listOf(faction)).single()

        @Synchronized
        override fun upsertAll(factions: List<MfFaction>): List<MfFaction> {
            val persisted = factions.map(::nextVersion)
            persisted.forEach { rows[it.id] = it }
            blockedSaveId?.let { blockedId ->
                if (persisted.any { it.id == blockedId }) {
                    blockedSaveId = null
                    saveCommitted.countDown()
                    check(releaseSave.await(5, TimeUnit.SECONDS))
                }
            }
            return persisted
        }

        @Synchronized
        override fun upsertAllAndDelete(
            factions: List<MfFaction>,
            deletedFactions: List<MfFaction>,
            departedLockOwners: Set<MfPlayerId>
        ): List<MfFaction> {
            atomicMutationCalls++
            val persisted = factions.map(::nextVersion)
            deletedFactions.forEach { deleted ->
                val live = rows[deleted.id]
                if (live == null || live.version != deleted.version) {
                    throw OptimisticLockingFailureException("stale delete")
                }
            }
            if (failAtomicMutation) error("injected atomic mutation failure")
            persisted.forEach { rows[it.id] = it }
            deletedFactions.forEach { rows.remove(it.id) }
            return persisted
        }

        @Synchronized
        override fun delete(factionId: MfFactionId) {
            check(rows.remove(factionId) != null)
        }

        fun blockNextSave(factionId: MfFactionId) {
            blockedSaveId = factionId
            saveCommitted = CountDownLatch(1)
            releaseSave = CountDownLatch(1)
        }

        private fun nextVersion(faction: MfFaction): MfFaction {
            val live = rows[faction.id]
            if (live == null) {
                if (faction.version != 0) throw OptimisticLockingFailureException("stale create")
                return faction.copy(version = 1)
            }
            if (live.version != faction.version) {
                throw OptimisticLockingFailureException("Invalid version: ${faction.version}")
            }
            return faction.copy(version = faction.version + 1)
        }
    }
}
