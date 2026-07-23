package com.dansplugins.factionsystem.claim

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.faction.MfFactionService
import com.dansplugins.factionsystem.locks.MfLockService
import com.dansplugins.factionsystem.service.Services
import org.bukkit.Server
import org.bukkit.plugin.PluginManager
import org.bukkit.scheduler.BukkitScheduler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.UUID
import java.util.logging.Logger

/**
 * Exercises the per-faction claim index that backs getClaims(factionId)/getClaimCount/hasClaims.
 * Uses a fake in-memory repository so save/delete drive the real service logic (and therefore the
 * index maintenance) end to end, with the plugin's Bukkit side-effects stubbed out.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MfClaimServiceTest {

    private lateinit var plugin: MedievalFactions
    private lateinit var factionService: MfFactionService
    private lateinit var world: UUID

    private val factionA = MfFactionId("faction-a")
    private val factionB = MfFactionId("faction-b")
    private val factionUnknown = MfFactionId("faction-unknown")

    private fun claim(x: Int, z: Int, factionId: MfFactionId) = MfClaimedChunk(world, x, z, factionId)

    @BeforeEach
    fun setUp() {
        world = UUID.randomUUID()
        plugin = mock(MedievalFactions::class.java)
        `when`(plugin.logger).thenReturn(mock(Logger::class.java))

        val server = mock(Server::class.java)
        `when`(plugin.server).thenReturn(server)
        // No real world -> the "claiming blocked in world" branch and the territory-indicator
        // world lookup are both skipped, so save/delete stay side-effect free.
        `when`(server.getWorld(any(UUID::class.java))).thenReturn(null)
        `when`(server.isPrimaryThread).thenReturn(false)
        `when`(server.pluginManager).thenReturn(mock(PluginManager::class.java))
        `when`(server.scheduler).thenReturn(mock(BukkitScheduler::class.java))

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
