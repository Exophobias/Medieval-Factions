package com.dansplugins.factionsystem.claim

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.FactionId
import com.dansplugins.factionsystem.api.event.ClaimOwnerChangedEvent
import com.dansplugins.factionsystem.api.event.FactionClaimAttemptEvent
import com.dansplugins.factionsystem.api.event.FactionUnclaimedChunkEvent
import com.dansplugins.factionsystem.event.faction.FactionClaimEvent
import com.dansplugins.factionsystem.exception.EventCancelledException
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.faction.MfFactionService
import com.dansplugins.factionsystem.failure.ServiceFailure
import com.dansplugins.factionsystem.locks.MfLockRepository
import com.dansplugins.factionsystem.locks.MfLockService
import com.dansplugins.factionsystem.service.Services
import dev.forkhandles.result4k.Failure
import org.bukkit.Server
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
import org.junit.jupiter.api.TestInstance
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger

/**
 * Exercises the per-faction claim index that backs getClaims(factionId)/getClaimCount/hasClaims, and
 * the stable API's [ClaimOwnerChangedEvent] which the same two write paths emit.
 *
 * Uses a fake in-memory repository so save/delete drive the real service logic (and therefore the
 * index maintenance and the event bridging) end to end, with the plugin's Bukkit side-effects
 * stubbed out.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MfClaimServiceTest {

    private lateinit var plugin: MedievalFactions
    private lateinit var factionService: MfFactionService
    private lateinit var lockService: MfLockService
    private lateinit var world: UUID

    /** Every event handed to the plugin manager, internal and API alike, in the order fired. */
    private val firedEvents = mutableListOf<Event>()

    /** When set, every FactionClaimAttemptEvent is vetoed, standing in for a consumer that refuses. */
    private var vetoAttempts = false

    private val factionA = MfFactionId("faction-a")
    private val factionB = MfFactionId("faction-b")
    private val factionUnknown = MfFactionId("faction-unknown")

    private fun claim(x: Int, z: Int, factionId: MfFactionId) = MfClaimedChunk(world, x, z, factionId)

    @BeforeEach
    fun setUp() {
        world = UUID.randomUUID()
        firedEvents.clear()
        vetoAttempts = false
        plugin = mock(MedievalFactions::class.java)
        `when`(plugin.logger).thenReturn(mock(Logger::class.java))

        val server = mock(Server::class.java)
        `when`(plugin.server).thenReturn(server)
        // No real world -> the "claiming blocked in world" branch and the territory-indicator
        // world lookup are both skipped, so save/delete stay side-effect free.
        `when`(server.getWorld(any(UUID::class.java))).thenReturn(null)
        `when`(server.isPrimaryThread).thenReturn(false)

        val pluginManager = mock(PluginManager::class.java)
        `when`(server.pluginManager).thenReturn(pluginManager)
        doAnswer { invocation ->
            val event = invocation.getArgument(0, Event::class.java)
            firedEvents.add(event)
            if (vetoAttempts && event is FactionClaimAttemptEvent) {
                event.isCancelled = true
            }
            null
        }.`when`(pluginManager).callEvent(any(Event::class.java))

        // Scheduled work is run inline. The API events are deliberately deferred to the next tick so
        // consumers get a main-thread guarantee, and nothing ticks in a unit test, so without this
        // the assertions below would see nothing and pass for the wrong reason.
        val scheduler = mock(BukkitScheduler::class.java)
        `when`(server.scheduler).thenReturn(scheduler)
        val task = mock(BukkitTask::class.java)
        `when`(scheduler.runTask(any(Plugin::class.java), any(Runnable::class.java))).thenAnswer { invocation ->
            invocation.getArgument(1, Runnable::class.java).run()
            task
        }

        val services = mock(Services::class.java)
        `when`(plugin.services).thenReturn(services)
        factionService = mock(MfFactionService::class.java)
        `when`(services.factionService).thenReturn(factionService)
        `when`(services.mapService).thenReturn(null)
        lockService = spy(MfLockService(plugin, mock(MfLockRepository::class.java)))
        `when`(services.lockService).thenReturn(lockService)

        // save()/delete() require the claim's faction to resolve to a non-null faction.
        val faction = mock(MfFaction::class.java)
        `when`(factionService.getFaction(factionA)).thenReturn(faction)
        `when`(factionService.getFaction(factionB)).thenReturn(faction)
    }

    private fun serviceWith(vararg claims: MfClaimedChunk): MfClaimService =
        MfClaimService(plugin, FakeClaimRepository(claims.toList()))

    @Test
    fun initBuildsPerFactionIndexFromLoadedClaims() {
        val a00 = claim(0, 0, factionA)
        val a10 = claim(1, 0, factionA)
        val b20 = claim(2, 0, factionB)
        val service = serviceWith(a00, a10, b20)

        assertEquals(2, service.getClaimCount(factionA))
        assertEquals(1, service.getClaimCount(factionB))
        assertEquals(0, service.getClaimCount(factionUnknown))
        assertTrue(service.hasClaims(factionA))
        assertFalse(service.hasClaims(factionUnknown))
        assertEquals(setOf(a00, a10), service.getClaims(factionA).toSet())
        assertEquals(emptyList<MfClaimedChunk>(), service.getClaims(factionUnknown))
        // Point lookup by chunk must still work.
        assertEquals(a00, service.getClaim(world, 0, 0))
        assertNull(service.getClaim(world, 9, 9))
    }

    @Test
    fun saveAddsClaimsToThePerFactionIndex() {
        val service = serviceWith()
        service.save(claim(0, 0, factionA))
        assertEquals(1, service.getClaimCount(factionA))
        assertTrue(service.hasClaims(factionA))
        service.save(claim(1, 0, factionA))
        assertEquals(2, service.getClaimCount(factionA))
        service.save(claim(0, 1, factionB))
        assertEquals(1, service.getClaimCount(factionB))
        assertEquals(2, service.getClaimCount(factionA))
    }

    @Test
    fun overclaimReassignsChunkBetweenFactions() {
        val previous = claim(0, 0, factionA)
        val service = serviceWith(previous)
        // Same chunk, different faction (an at-war overclaim).
        service.save(claim(0, 0, factionB))

        assertEquals(0, service.getClaimCount(factionA))
        assertFalse(service.hasClaims(factionA))
        assertEquals(1, service.getClaimCount(factionB))
        assertEquals(setOf(claim(0, 0, factionB)), service.getClaims(factionB).toSet())
        assertEquals(factionB, service.getClaim(world, 0, 0)?.factionId)
        verify(lockService).unloadLockedBlocks(previous)
    }

    @Test
    fun compareTransferRefusesLandThatChangedHandsAfterTheSnapshot() {
        val position = claim(0, 0, factionA)
        val service = serviceWith(position)
        service.save(position.copy(factionId = factionB))

        val result = service.transferOwnership(factionA, position)

        assertTrue(result is Failure)
        assertEquals(factionB, service.getClaim(world, 0, 0)?.factionId)
    }

    @Test
    fun repositoryCommitAndCachePublicationCannotOvertakeEachOther() {
        val repository = BlockingClaimRepository()
        val service = MfClaimService(plugin, repository)
        val first = claim(0, 0, factionA)
        val second = claim(0, 0, factionB)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val firstWrite = executor.submit { service.save(first) }
            assertTrue(repository.firstCommitted.await(5, TimeUnit.SECONDS))
            val secondWrite = executor.submit { service.save(second) }

            // The second repository write cannot begin while the first committed row has not yet
            // been published to the service cache.
            assertFalse(repository.secondEntered.await(200, TimeUnit.MILLISECONDS))
            repository.releaseFirst.countDown()
            assertTrue(firstWrite.get(5, TimeUnit.SECONDS) !is Failure<*>)
            assertTrue(secondWrite.get(5, TimeUnit.SECONDS) !is Failure<*>)

            assertEquals(factionB, repository.current()?.factionId)
            assertEquals(factionB, service.getClaim(world, 0, 0)?.factionId)
            assertEquals(0, service.getClaimCount(factionA))
            assertEquals(1, service.getClaimCount(factionB))
        } finally {
            repository.releaseFirst.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun factionDeleteFenceWaitsForLateClaimPublicationThenEvictsIt() {
        val repository = BlockingClaimRepository()
        val service = MfClaimService(plugin, repository)
        val doomed = claim(0, 0, factionA)
        val executor = Executors.newFixedThreadPool(2)
        var blocked = false
        try {
            val write = executor.submit { service.save(doomed) }
            assertTrue(repository.firstCommitted.await(5, TimeUnit.SECONDS))
            val fenceEntered = CountDownLatch(1)
            val fence = executor.submit {
                fenceEntered.countDown()
                service.blockFactionDeletion(factionA)
            }
            assertTrue(fenceEntered.await(5, TimeUnit.SECONDS))
            assertFalse(fence.isDone)

            repository.releaseFirst.countDown()
            assertTrue(write.get(5, TimeUnit.SECONDS) !is Failure<*>)
            fence.get(5, TimeUnit.SECONDS)
            blocked = true
            service.evictAllClaims(factionA)

            assertNull(service.getClaim(world, 0, 0))
            assertEquals(0, service.getClaimCount(factionA))
        } finally {
            repository.releaseFirst.countDown()
            if (blocked) service.unblockFactionDeletion(factionA)
            executor.shutdownNow()
        }
    }

    @Test
    fun deleteRemovesClaimFromThePerFactionIndex() {
        val a00 = claim(0, 0, factionA)
        val a10 = claim(1, 0, factionA)
        val service = serviceWith(a00, a10)

        service.delete(a00)
        assertEquals(1, service.getClaimCount(factionA))
        assertEquals(setOf(a10), service.getClaims(factionA).toSet())

        service.delete(a10)
        assertEquals(0, service.getClaimCount(factionA))
        assertFalse(service.hasClaims(factionA))
    }

    @Test
    fun staleOwnerCannotUnclaimLandThatChangedHands() {
        val oldSnapshot = claim(0, 0, factionA)
        val service = serviceWith(oldSnapshot)
        service.transferOwnership(factionA, oldSnapshot.copy(factionId = factionB))

        val result = service.delete(oldSnapshot)

        assertTrue(result is Failure)
        assertEquals(factionB, service.getClaim(world, 0, 0)?.factionId)
        assertEquals(1, service.getClaimCount(factionB))
    }

    @Test
    fun deleteAllClaimsClearsOnlyThatFaction() {
        val service = serviceWith(claim(0, 0, factionA), claim(1, 0, factionA), claim(2, 0, factionB))

        service.deleteAllClaims(factionA)

        assertEquals(0, service.getClaimCount(factionA))
        assertFalse(service.hasClaims(factionA))
        assertEquals(1, service.getClaimCount(factionB))
    }

    @Test
    fun deleteAllClaimsEvictsCascadedLocksFromMemory() {
        val first = claim(0, 0, factionA)
        val second = claim(1, 0, factionA)
        val untouched = claim(2, 0, factionB)
        val service = serviceWith(first, second, untouched)

        service.deleteAllClaims(factionA)

        verify(lockService).unloadLockedBlocks(listOf(first, second))
        org.mockito.Mockito.verify(lockService, org.mockito.Mockito.never()).unloadLockedBlocks(untouched)
    }

    // --- ClaimOwnerChangedEvent ---

    // ---- the cancellable claim event -------------------------------------------------
    //
    // None of this was covered. An edit that moved the veto check below repository.upsert would
    // have persisted the claim, indexed it, still reported a failure to the caller, and left all
    // 515 tests green -- a chunk the API calls unclaimed and the map calls claimed.

    private fun attempts() = firedEvents.filterIsInstance<FactionClaimAttemptEvent>()

    @Test
    fun claimingWildernessFiresAnAttemptWithNoPreviousOwner() {
        val service = serviceWith()
        firedEvents.clear()

        service.save(claim(4, 7, factionA))

        assertEquals(1, attempts().size)
        val attempt = attempts().single()
        assertEquals(factionA.value, attempt.faction.value)
        assertEquals(world, attempt.worldId)
        assertEquals(4, attempt.chunkX)
        assertEquals(7, attempt.chunkZ)
        assertNull(attempt.previousOwner, "wilderness has no previous owner")
    }

    @Test
    fun overclaimingReportsTheFactionBeingDisplaced() {
        val service = serviceWith(claim(0, 0, factionA))
        firedEvents.clear()

        service.save(claim(0, 0, factionB))

        val attempt = attempts().single()
        assertEquals(factionB.value, attempt.faction.value, "the claimant")
        assertEquals(factionA.value, attempt.previousOwner?.value, "the incumbent, read before the write")
    }

    /**
     * The API event is an EARLIER gate than MedievalFactions' own, deliberately.
     *
     * Whichever event is checked last is the real decision, and everything after it observes an
     * outcome it cannot influence. MF's own event is the one every existing third-party plugin binds
     * to and the one whose MONITOR handlers are documented to see the result, so it has to be last.
     */
    @Test
    fun theApiAttemptIsOfferedBeforeMedievalFactionsOwnEvent() {
        val service = serviceWith()
        firedEvents.clear()

        service.save(claim(1, 1, factionA))

        val attemptAt = firedEvents.indexOfFirst { it is FactionClaimAttemptEvent }
        val internalAt = firedEvents.indexOfFirst { it is FactionClaimEvent }
        assertTrue(attemptAt >= 0, "the API attempt event should have fired")
        assertTrue(internalAt >= 0, "MF's own claim event should have fired")
        assertTrue(attemptAt < internalAt, "the API event must not be the last word; MF's own is")
    }

    @Test
    fun aVetoedClaimIsReportedAsAFailure() {
        vetoAttempts = true
        val service = serviceWith(claim(0, 0, factionA))
        firedEvents.clear()

        val result = service.save(claim(0, 0, factionB))

        assertTrue(result is Failure<*>, "a refused claim must reach the caller as a failure")
        val reason = (result as Failure<*>).reason
        assertTrue(reason is ServiceFailure, "the failure should be MF's own service failure type")
        assertTrue(
            (reason as ServiceFailure).cause is EventCancelledException,
            "the cause should say it was cancelled, not something generic"
        )
    }

    @Test
    fun aVetoedClaimWritesNothingAnywhere() {
        vetoAttempts = true
        val service = serviceWith(claim(0, 0, factionA))
        firedEvents.clear()

        service.save(claim(0, 0, factionB))

        assertEquals(factionA, service.getClaim(world, 0, 0)?.factionId, "the chunk must not change hands")
        assertEquals(0, service.getClaimCount(factionB), "the claimant must gain nothing")
        assertEquals(1, service.getClaimCount(factionA), "and the incumbent must lose nothing")
        assertEquals(emptyList<ClaimOwnerChangedEvent>(), ownerChanges(), "and nothing may be announced")
    }

    @Test
    fun aVetoStopsBeforeMedievalFactionsOwnEventIsEvenAsked() {
        vetoAttempts = true
        val service = serviceWith()
        firedEvents.clear()

        service.save(claim(2, 2, factionA))

        assertEquals(1, attempts().size)
        assertTrue(
            firedEvents.none { it is FactionClaimEvent },
            "a refusal on the API gate should short-circuit, not run MF's gate for a claim that cannot happen"
        )
    }

    private fun ownerChanges() = firedEvents.filterIsInstance<ClaimOwnerChangedEvent>()

    /** Claiming wilderness is an ownership change with no previous owner, not a non-event. */
    @Test
    fun claimingWildernessReportsANullPreviousOwner() {
        val service = serviceWith()

        service.save(claim(4, 7, factionA))

        val changes = ownerChanges()
        assertEquals(1, changes.size)
        val change = changes.single()
        assertEquals(world, change.worldId)
        assertEquals(4, change.chunkX)
        assertEquals(7, change.chunkZ)
        assertNull(change.previousOwner)
        assertEquals(FactionId(factionA.value), change.newOwner)
        assertFalse(change.isConquest)
    }

    /**
     * The case the event exists for. MF's own claim event names only the incoming faction, so a
     * consumer presenting the conqueror with a decision cannot tell whom the land was taken from.
     */
    @Test
    fun overclaimReportsBothTheOldAndTheNewOwner() {
        val service = serviceWith(claim(0, 0, factionA))
        firedEvents.clear()

        service.save(claim(0, 0, factionB))

        val change = ownerChanges().single()
        assertEquals(FactionId(factionA.value), change.previousOwner)
        assertEquals(FactionId(factionB.value), change.newOwner)
        assertTrue(change.isConquest)
    }

    /**
     * MF re-saves an unchanged claim on several administrative paths. A consumer that acted on each
     * one would present the same conquest decision over and over to somebody who conquered nothing.
     */
    @Test
    fun resavingAClaimToItsCurrentOwnerReportsNothing() {
        val service = serviceWith(claim(0, 0, factionA))
        firedEvents.clear()

        service.save(claim(0, 0, factionA))

        assertEquals(emptyList<ClaimOwnerChangedEvent>(), ownerChanges())
    }

    /** Land going back to wilderness is an ownership change too, with a null new owner. */
    @Test
    fun unclaimingReportsANullNewOwner() {
        val a00 = claim(0, 0, factionA)
        val service = serviceWith(a00)
        firedEvents.clear()

        service.delete(a00)

        val change = ownerChanges().single()
        assertEquals(FactionId(factionA.value), change.previousOwner)
        assertNull(change.newOwner)
        assertFalse(change.isConquest)
        val unclaimed = firedEvents.filterIsInstance<FactionUnclaimedChunkEvent>().single()
        assertEquals(FactionId(factionA.value), unclaimed.faction)
        assertEquals(world, unclaimed.worldId)
        assertEquals(0, unclaimed.chunkX)
        assertEquals(0, unclaimed.chunkZ)
    }

    @Test
    fun failedUnclaimPublishesNoPastTenseStableEvent() {
        val a00 = claim(0, 0, factionA)
        val repository = FakeClaimRepository(listOf(a00)).also { it.failDelete = true }
        val service = MfClaimService(plugin, repository)
        firedEvents.clear()

        val result = service.delete(a00)

        assertTrue(result is Failure)
        assertEquals(a00, service.getClaim(world, 0, 0))
        assertTrue(ownerChanges().isEmpty())
        assertTrue(firedEvents.none { it is FactionUnclaimedChunkEvent })
    }

    /**
     * The documented gap. deleteAllClaims goes straight to the repository so a realm-sized disband
     * does not schedule thousands of Bukkit events, which means a consumer tracking tenancy needs a
     * reconciliation sweep as a backstop. This test exists to make that silence deliberate: if
     * somebody later wires an event into the bulk path, they have to come here and say so.
     */
    @Test
    fun bulkUnclaimReportsNothingAtAll() {
        val service = serviceWith(claim(0, 0, factionA), claim(1, 0, factionA))
        firedEvents.clear()

        service.deleteAllClaims(factionA)

        assertEquals(emptyList<ClaimOwnerChangedEvent>(), ownerChanges())
        assertTrue(firedEvents.none { it is FactionUnclaimedChunkEvent })
    }

    /** One event per chunk, and each carries its own coordinates rather than the last one's. */
    @Test
    fun eachChunkGetsItsOwnEvent() {
        val service = serviceWith()

        service.save(claim(0, 0, factionA))
        service.save(claim(1, 0, factionA))
        service.save(claim(1, 0, factionB))

        val changes = ownerChanges()
        assertEquals(3, changes.size)
        assertEquals(listOf(0 to 0, 1 to 0, 1 to 0), changes.map { it.chunkX to it.chunkZ })
        assertEquals(
            listOf(null, null, FactionId(factionA.value)),
            changes.map { it.previousOwner }
        )
    }
}

/** Minimal in-memory MfClaimedChunkRepository so the service's save/delete drive real index logic. */
private class FakeClaimRepository(initial: List<MfClaimedChunk>) : MfClaimedChunkRepository {

    private val store = LinkedHashMap<Triple<UUID, Int, Int>, MfClaimedChunk>()
    var failDelete = false

    init {
        initial.forEach { store[Triple(it.worldId, it.x, it.z)] = it }
    }

    override fun getClaim(worldId: UUID, x: Int, z: Int): MfClaimedChunk? = store[Triple(worldId, x, z)]
    override fun getClaims(factionId: MfFactionId): List<MfClaimedChunk> = store.values.filter { it.factionId == factionId }
    override fun getClaims(): List<MfClaimedChunk> = store.values.toList()

    override fun upsert(claim: MfClaimedChunk): MfClaimedChunk {
        store[Triple(claim.worldId, claim.x, claim.z)] = claim
        return claim
    }

    override fun delete(worldId: UUID, x: Int, z: Int) {
        if (failDelete) error("injected claim delete failure")
        store.remove(Triple(worldId, x, z))
    }

    override fun deleteAll(factionId: MfFactionId) {
        store.entries.removeIf { it.value.factionId == factionId }
    }
}

/** Pauses the first upsert after its durable write but before returning to the service. */
private class BlockingClaimRepository : MfClaimedChunkRepository {
    private val rows = ConcurrentHashMap<Triple<UUID, Int, Int>, MfClaimedChunk>()
    val firstCommitted = CountDownLatch(1)
    val releaseFirst = CountDownLatch(1)
    val secondEntered = CountDownLatch(1)
    private val writes = AtomicInteger()

    override fun getClaim(worldId: UUID, x: Int, z: Int) = rows[Triple(worldId, x, z)]
    override fun getClaims(factionId: MfFactionId) = rows.values.filter { it.factionId == factionId }
    override fun getClaims() = rows.values.toList()

    override fun upsert(claim: MfClaimedChunk): MfClaimedChunk {
        val number = writes.incrementAndGet()
        if (number == 2) secondEntered.countDown()
        rows[Triple(claim.worldId, claim.x, claim.z)] = claim
        if (number == 1) {
            firstCommitted.countDown()
            check(releaseFirst.await(5, TimeUnit.SECONDS))
        }
        return claim
    }

    override fun delete(worldId: UUID, x: Int, z: Int) {
        rows.remove(Triple(worldId, x, z))
    }

    override fun deleteAll(factionId: MfFactionId) {
        rows.entries.removeIf { it.value.factionId == factionId }
    }

    fun current(): MfClaimedChunk? = rows.values.singleOrNull()
}
