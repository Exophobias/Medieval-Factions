package com.dansplugins.factionsystem.claim

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.FactionId
import com.dansplugins.factionsystem.api.event.ClaimOwnerChangedEvent
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.faction.MfFactionService
import com.dansplugins.factionsystem.locks.MfLockService
import com.dansplugins.factionsystem.service.Services
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
import org.mockito.Mockito.`when`
import java.util.UUID
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
    private lateinit var world: UUID

    /** Every event handed to the plugin manager, internal and API alike, in the order fired. */
    private val firedEvents = mutableListOf<Event>()

    private val factionA = MfFactionId("faction-a")
    private val factionB = MfFactionId("faction-b")
    private val factionUnknown = MfFactionId("faction-unknown")

    private fun claim(x: Int, z: Int, factionId: MfFactionId) = MfClaimedChunk(world, x, z, factionId)

    @BeforeEach
    fun setUp() {
        world = UUID.randomUUID()
        firedEvents.clear()
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
            firedEvents.add(invocation.getArgument(0, Event::class.java))
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
        `when`(services.lockService).thenReturn(mock(MfLockService::class.java))

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
        val service = serviceWith(claim(0, 0, factionA))
        // Same chunk, different faction (an at-war overclaim).
        service.save(claim(0, 0, factionB))

        assertEquals(0, service.getClaimCount(factionA))
        assertFalse(service.hasClaims(factionA))
        assertEquals(1, service.getClaimCount(factionB))
        assertEquals(setOf(claim(0, 0, factionB)), service.getClaims(factionB).toSet())
        assertEquals(factionB, service.getClaim(world, 0, 0)?.factionId)
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
    fun deleteAllClaimsClearsOnlyThatFaction() {
        val service = serviceWith(claim(0, 0, factionA), claim(1, 0, factionA), claim(2, 0, factionB))

        service.deleteAllClaims(factionA)

        assertEquals(0, service.getClaimCount(factionA))
        assertFalse(service.hasClaims(factionA))
        assertEquals(1, service.getClaimCount(factionB))
    }

    // --- ClaimOwnerChangedEvent ---

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
        store.remove(Triple(worldId, x, z))
    }

    override fun deleteAll(factionId: MfFactionId) {
        store.entries.removeIf { it.value.factionId == factionId }
    }
}
