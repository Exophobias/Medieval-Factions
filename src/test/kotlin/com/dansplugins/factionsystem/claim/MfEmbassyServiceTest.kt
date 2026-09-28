package com.dansplugins.factionsystem.claim

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.anyArg
import com.dansplugins.factionsystem.api.ClaimAction
import com.dansplugins.factionsystem.api.event.EmbassyOfferAttemptEvent
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.faction.MfFactionService
import com.dansplugins.factionsystem.faction.flag.MfFlags
import com.dansplugins.factionsystem.faction.flag.MfFlagValues
import com.dansplugins.factionsystem.gate.MfGateService
import com.dansplugins.factionsystem.locks.MfLockService
import com.dansplugins.factionsystem.locks.MfLockRepository
import com.dansplugins.factionsystem.player.MfPlayerId
import com.dansplugins.factionsystem.relationship.MfFactionRelationship
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipService
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType
import com.dansplugins.factionsystem.service.Services
import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import org.bukkit.Server
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.Event
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.scheduler.BukkitTask
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class MfEmbassyServiceTest {
    private val world = UUID.randomUUID()
    private val host = MfFactionId("host")
    private val guest = MfFactionId("guest")
    private val conqueror = MfFactionId("conqueror")
    private val hostPlayer = MfPlayerId(UUID.randomUUID().toString())
    private val guestPlayer = MfPlayerId(UUID.randomUUID().toString())
    private val outsider = MfPlayerId(UUID.randomUUID().toString())
    private val claim = MfClaimedChunk(world, 2, -3, host)
    private val clock = MutableClock()
    private lateinit var plugin: MedievalFactions
    private lateinit var claimService: MfClaimService
    private lateinit var factionService: MfFactionService
    private lateinit var relationshipService: MfFactionRelationshipService
    private lateinit var gateService: MfGateService
    private lateinit var repository: MemoryRepository
    private lateinit var embassies: MfEmbassyService
    private lateinit var hostFaction: MfFaction
    private lateinit var guestFaction: MfFaction
    private val scheduledNotices = mutableListOf<Runnable>()
    private var veto = false
    private var vetoChunkX: Int? = null
    private val events = mutableListOf<EmbassyOfferAttemptEvent>()

    @BeforeEach
    fun setUp() {
        veto = false
        vetoChunkX = null
        events.clear()
        scheduledNotices.clear()
        clock.time = 1_000_000L
        plugin = mock(MedievalFactions::class.java)
        `when`(plugin.logger).thenReturn(Logger.getLogger("EmbassyTest"))
        val config = YamlConfiguration()
        `when`(plugin.config).thenReturn(config)
        val flags = MfFlags(plugin)
        `when`(plugin.flags).thenReturn(flags)
        val server = mock(Server::class.java)
        val manager = mock(PluginManager::class.java)
        `when`(plugin.server).thenReturn(server)
        `when`(server.pluginManager).thenReturn(manager)
        `when`(server.isPrimaryThread).thenReturn(true)
        val scheduler = mock(BukkitScheduler::class.java)
        `when`(server.scheduler).thenReturn(scheduler)
        doAnswer { invocation ->
            scheduledNotices.add(invocation.getArgument(1, Runnable::class.java))
            mock(BukkitTask::class.java)
        }.`when`(scheduler).runTask(any(Plugin::class.java), any(Runnable::class.java))
        doAnswer { invocation ->
            val event = invocation.getArgument(0, Event::class.java)
            if (event is EmbassyOfferAttemptEvent) {
                events.add(event)
                if (veto || event.chunkX == vetoChunkX) event.isCancelled = true
            }
            null
        }.`when`(manager).callEvent(any(Event::class.java))

        val services = mock(Services::class.java)
        `when`(plugin.services).thenReturn(services)
        claimService = mock(MfClaimService::class.java)
        factionService = mock(MfFactionService::class.java)
        relationshipService = mock(MfFactionRelationshipService::class.java)
        val lockRepository = mock(MfLockRepository::class.java)
        `when`(lockRepository.getLockedBlocks()).thenReturn(emptyList())
        val locks = MfLockService(plugin, lockRepository)
        val gates = mock(MfGateService::class.java)
        gateService = gates
        doAnswer { invocation -> invocation.getArgument<() -> Any?>(0).invoke() }
            .`when`(gates).withMutationLock<Any?>(anyArg())
        `when`(services.claimService).thenReturn(claimService)
        `when`(services.factionService).thenReturn(factionService)
        `when`(services.factionRelationshipService).thenReturn(relationshipService)
        `when`(services.lockService).thenReturn(locks)
        `when`(services.gateService).thenReturn(gates)
        `when`(claimService.getClaim(world, 2, -3)).thenReturn(claim)
        hostFaction = mock(MfFaction::class.java)
        guestFaction = mock(MfFaction::class.java)
        `when`(factionService.getFaction(host)).thenReturn(hostFaction)
        `when`(factionService.getFaction(guest)).thenReturn(guestFaction)
        `when`(factionService.getFaction(conqueror)).thenReturn(mock(MfFaction::class.java))
        `when`(hostFaction.id).thenReturn(host)
        `when`(guestFaction.id).thenReturn(guest)
        val hostFlags = MfFlagValues(plugin)
        val guestFlags = MfFlagValues(plugin)
        `when`(hostFaction.flags).thenReturn(hostFlags)
        `when`(guestFaction.flags).thenReturn(guestFlags)
        `when`(factionService.getFaction(hostPlayer)).thenReturn(hostFaction)
        `when`(factionService.getFaction(guestPlayer)).thenReturn(guestFaction)
        `when`(relationshipService.getRelationships(host, guest)).thenReturn(emptyList())
        `when`(relationshipService.getRelationships(guest, host)).thenReturn(emptyList())
        repository = MemoryRepository()
        embassies = MfEmbassyService(plugin, repository, clock)
    }

    @Test
    fun `offer is inert until guest accepts and preflight runs twice`() {
        assertIs<Success<MfEmbassy>>(embassies.offer(host, guest, world, 2, -3))
        assertTrue(embassies.hasReservedEmbassy(world, 2, -3))
        assertFalse(embassies.hasActiveOrClearingEmbassy(world, 2, -3))
        assertEquals(EmbassyAccessDecision.NONE, embassies.access(guestPlayer, claim, ClaimAction.CONTAINER))
        assertIs<Success<MfEmbassy>>(embassies.accept(guest, world, 2, -3))
        assertEquals(listOf(false, true), events.map { it.accepting })
        assertFalse(events.any { it.isAsynchronous })
        assertTrue(embassies.hasActiveOrClearingEmbassy(world, 2, -3))
    }

    @Test
    fun `offered parcel must be cancelled before claim release or faction disband`() {
        assertIs<Success<MfEmbassy>>(embassies.offer(host, guest, world, 2, -3))
        assertFailsWith<IllegalArgumentException> { embassies.withClaimRelease(claim) { Unit } }
        assertFailsWith<IllegalArgumentException> { embassies.withAllClaimsRelease(host) { Unit } }
        assertFailsWith<IllegalArgumentException> { embassies.blockFactionDeletion(host) }

        assertIs<Success<MfEmbassy?>>(embassies.revoke(host, world, 2, -3))
        embassies.withClaimRelease(claim) { Unit }
        embassies.blockFactionDeletion(host)
        embassies.unblockFactionDeletion(host)
    }

    @Test
    fun `active parcel is exclusive and clearing permits retrieval but no additions`() {
        assertIs<Success<MfEmbassy>>(embassies.offer(host, guest, world, 2, -3))
        assertIs<Success<MfEmbassy>>(embassies.accept(guest, world, 2, -3))
        assertEquals(EmbassyAccessDecision.GRANT, embassies.access(guestPlayer, claim, ClaimAction.BUILD))
        assertEquals(EmbassyAccessDecision.GRANT, embassies.access(guestPlayer, claim, ClaimAction.CONTAINER))
        assertEquals(EmbassyAccessDecision.DENY, embassies.access(hostPlayer, claim, ClaimAction.CONTAINER))
        assertEquals(EmbassyAccessDecision.DENY, embassies.access(outsider, claim, ClaimAction.BREAK))
        assertEquals(EmbassyAccessDecision.NONE, embassies.access(guestPlayer, claim, ClaimAction.DAMAGE))

        val clearing = embassies.revoke(host, world, 2, -3)
        assertIs<Success<MfEmbassy?>>(clearing)
        assertEquals(MfEmbassyStatus.CLEARING, clearing.value?.status)
        assertEquals(EmbassyAccessDecision.GRANT, embassies.access(guestPlayer, claim, ClaimAction.BREAK))
        assertEquals(EmbassyAccessDecision.GRANT, embassies.access(guestPlayer, claim, ClaimAction.CONTAINER))
        assertEquals(EmbassyAccessDecision.DENY, embassies.access(guestPlayer, claim, ClaimAction.BUILD))
        assertEquals(EmbassyAccessDecision.DENY, embassies.access(guestPlayer, claim, ClaimAction.INTERACT))
        assertEquals(EmbassyAccessDecision.DENY, embassies.access(hostPlayer, claim, ClaimAction.BREAK))
        assertIs<Success<Unit>>(embassies.finish(guest, world, 2, -3))
        assertNull(embassies.getAt(world, 2, -3))
    }

    @Test
    fun `offer and clearing expire at their deadlines even before sweep`() {
        assertIs<Success<MfEmbassy>>(embassies.offer(host, guest, world, 2, -3))
        clock.time += MfEmbassyService.OFFER_MILLIS
        assertNull(embassies.getAt(world, 2, -3))
        assertIs<Success<Int>>(embassies.sweep())
        assertEquals(0, repository.rows.size)

        assertIs<Success<MfEmbassy>>(embassies.offer(host, guest, world, 2, -3))
        assertIs<Success<MfEmbassy>>(embassies.accept(guest, world, 2, -3))
        assertIs<Success<MfEmbassy?>>(embassies.release(guest, world, 2, -3))
        clock.time += MfEmbassyService.CLEARING_MILLIS
        assertNull(embassies.getAt(world, 2, -3))
        assertEquals(EmbassyAccessDecision.NONE, embassies.access(guestPlayer, claim, ClaimAction.CONTAINER))
        assertIs<Success<Int>>(embassies.sweep())
        assertTrue(repository.rows.isEmpty())
    }

    @Test
    fun `expiry notice is scheduled for both factions after the offer is removed`() {
        assertIs<Success<MfEmbassy>>(embassies.offer(host, guest, world, 2, -3))
        clock.time += MfEmbassyService.OFFER_MILLIS
        assertIs<Success<Int>>(embassies.sweep())
        assertTrue(repository.rows.isEmpty())
        assertEquals(1, scheduledNotices.size)

        scheduledNotices.single().run()
        val body = "The embassy offer at $world:2,-3 expired."
        verify(hostFaction).sendMessage("Embassy period ended", body)
        verify(guestFaction).sendMessage("Embassy period ended", body)
    }

    @Test
    fun `war suspends parcel use and peace restores it`() {
        assertIs<Success<MfEmbassy>>(embassies.offer(host, guest, world, 2, -3))
        assertIs<Success<MfEmbassy>>(embassies.accept(guest, world, 2, -3))
        assertTrue(embassies.isParcelProtectionActive(world, 2, -3))
        val war = mock(MfFactionRelationship::class.java)
        `when`(war.type).thenReturn(MfFactionRelationshipType.AT_WAR)
        `when`(relationshipService.getRelationships(host, guest)).thenReturn(listOf(war))
        assertFalse(embassies.isParcelProtectionActive(world, 2, -3))
        assertEquals(EmbassyAccessDecision.NONE, embassies.access(guestPlayer, claim, ClaimAction.BUILD))
        assertEquals(EmbassyAccessDecision.NONE, embassies.access(hostPlayer, claim, ClaimAction.CONTAINER))
        `when`(relationshipService.getRelationships(host, guest)).thenReturn(emptyList())
        assertTrue(embassies.isParcelProtectionActive(world, 2, -3))
        assertEquals(EmbassyAccessDecision.GRANT, embassies.access(guestPlayer, claim, ClaimAction.BUILD))
    }

    @Test
    fun `claim transfer stops old rights then persists a conqueror decision and default passage`() {
        assertIs<Success<MfEmbassy>>(embassies.offer(host, guest, world, 2, -3))
        assertIs<Success<MfEmbassy>>(embassies.accept(guest, world, 2, -3))
        `when`(claimService.getClaim(world, 2, -3)).thenReturn(MfClaimedChunk(world, 2, -3, conqueror))
        assertEquals(MfEmbassyStatus.CONQUEST_DECISION, embassies.getAt(world, 2, -3)?.status)
        assertEquals(EmbassyAccessDecision.DENY, embassies.access(guestPlayer, claim, ClaimAction.CONTAINER))
        assertEquals(EmbassyAccessDecision.DENY,
            embassies.access(guestPlayer, MfClaimedChunk(world, 2, -3, conqueror), ClaimAction.CONTAINER))
        assertIs<Success<Int>>(embassies.sweep())
        assertEquals(MfEmbassyStatus.CONQUEST_DECISION, embassies.getAt(world, 2, -3)?.status)
        assertEquals(EmbassyAccessDecision.DENY,
            embassies.access(guestPlayer, MfClaimedChunk(world, 2, -3, conqueror), ClaimAction.CONTAINER))
        clock.time += MfEmbassyService.CONQUEST_DECISION_MILLIS
        assertEquals(MfEmbassyStatus.CONQUEST_PASSAGE, embassies.getAt(world, 2, -3)?.status)
        assertIs<Success<Int>>(embassies.sweep())
        assertEquals(MfEmbassyStatus.CONQUEST_PASSAGE, repository.rows.values.single().status)
        assertEquals(EmbassyAccessDecision.GRANT,
            embassies.access(guestPlayer, MfClaimedChunk(world, 2, -3, conqueror), ClaimAction.CONTAINER))
        clock.time += MfEmbassyService.CLEARING_MILLIS
        assertNull(embassies.getAt(world, 2, -3))
    }

    @Test
    fun `conqueror can seize or grant passage before the default`() {
        assertIs<Success<MfEmbassy>>(embassies.offer(host, guest, world, 2, -3))
        assertIs<Success<MfEmbassy>>(embassies.accept(guest, world, 2, -3))
        `when`(claimService.getClaim(world, 2, -3)).thenReturn(MfClaimedChunk(world, 2, -3, conqueror))
        assertIs<Success<Boolean>>(embassies.invalidateClaim(world, 2, -3))
        val passage = embassies.chooseConquest(conqueror, world, 2, -3, true)
        assertIs<Success<MfEmbassy?>>(passage)
        assertEquals(MfEmbassyStatus.CONQUEST_PASSAGE, passage.value?.status)
        assertEquals(EmbassyAccessDecision.DENY,
            embassies.access(hostPlayer, MfClaimedChunk(world, 2, -3, conqueror), ClaimAction.CONTAINER))
    }

    @Test
    fun `failed conquest write keeps guest property closed and historical host fenced`() {
        assertIs<Success<MfEmbassy>>(embassies.offer(host, guest, world, 2, -3))
        assertIs<Success<MfEmbassy>>(embassies.accept(guest, world, 2, -3))
        `when`(claimService.getClaim(world, 2, -3)).thenReturn(MfClaimedChunk(world, 2, -3, conqueror))
        repository.failUpsert = true

        assertIs<Failure<*>>(embassies.invalidateClaim(world, 2, -3))
        assertEquals(MfEmbassyStatus.CONQUEST_DECISION, embassies.getAt(world, 2, -3)?.status)
        assertEquals(EmbassyAccessDecision.DENY,
            embassies.access(guestPlayer, MfClaimedChunk(world, 2, -3, conqueror), ClaimAction.CONTAINER))
        assertTrue(embassies.hasActiveOrClearingEmbassy(world, 2, -3))

        repository.failUpsert = false
        assertIs<Success<Int>>(embassies.sweep())
        assertTrue(embassies.hasActiveOrClearingForFaction(host))
        assertFailsWith<IllegalArgumentException> { embassies.blockFactionDeletion(host) }
    }

    @Test
    fun `war spanning the conquest decision preserves the full passage window without a sweep`() {
        assertIs<Success<MfEmbassy>>(embassies.offer(host, guest, world, 2, -3))
        assertIs<Success<MfEmbassy>>(embassies.accept(guest, world, 2, -3))
        `when`(claimService.getClaim(world, 2, -3)).thenReturn(MfClaimedChunk(world, 2, -3, conqueror))
        assertIs<Success<Boolean>>(embassies.invalidateClaim(world, 2, -3))
        clock.time += 3L * 24 * 60 * 60 * 1000
        val war = mock(MfFactionRelationship::class.java)
        `when`(war.type).thenReturn(MfFactionRelationshipType.AT_WAR)
        `when`(relationshipService.getRelationships(conqueror, guest)).thenReturn(listOf(war))
        assertIs<Success<Int>>(embassies.onWarStateChanged(conqueror, guest, true))

        clock.time += 30L * 24 * 60 * 60 * 1000
        assertEquals(MfEmbassyStatus.CONQUEST_PASSAGE, embassies.getAt(world, 2, -3)?.status)
        `when`(relationshipService.getRelationships(conqueror, guest)).thenReturn(emptyList())
        assertIs<Success<Int>>(embassies.onWarStateChanged(conqueror, guest, false))
        assertEquals(MfEmbassyStatus.CONQUEST_PASSAGE, repository.rows.values.single().status)
        assertEquals(EmbassyAccessDecision.GRANT,
            embassies.access(guestPlayer, MfClaimedChunk(world, 2, -3, conqueror), ClaimAction.CONTAINER))
        clock.time += MfEmbassyService.CLEARING_MILLIS
        assertNull(embassies.getAt(world, 2, -3))
    }

    @Test
    fun `war pauses clearing without restoring build rights`() {
        assertIs<Success<MfEmbassy>>(embassies.offer(host, guest, world, 2, -3))
        assertIs<Success<MfEmbassy>>(embassies.accept(guest, world, 2, -3))
        assertIs<Success<MfEmbassy?>>(embassies.revoke(host, world, 2, -3))
        clock.time += 3L * 24 * 60 * 60 * 1000
        val war = mock(MfFactionRelationship::class.java)
        `when`(war.type).thenReturn(MfFactionRelationshipType.AT_WAR)
        `when`(relationshipService.getRelationships(host, guest)).thenReturn(listOf(war))
        assertIs<Success<Int>>(embassies.onWarStateChanged(host, guest, true))
        clock.time += 30L * 24 * 60 * 60 * 1000
        assertEquals(MfEmbassyStatus.CLEARING, embassies.getAt(world, 2, -3)?.status)
        assertEquals(EmbassyAccessDecision.NONE, embassies.access(guestPlayer, claim, ClaimAction.CONTAINER))
        `when`(relationshipService.getRelationships(host, guest)).thenReturn(emptyList())
        assertIs<Success<Int>>(embassies.onWarStateChanged(host, guest, false))
        assertEquals(EmbassyAccessDecision.GRANT, embassies.access(guestPlayer, claim, ClaimAction.CONTAINER))
        assertEquals(EmbassyAccessDecision.DENY, embassies.access(guestPlayer, claim, ClaimAction.BUILD))
        clock.time += 11L * 24 * 60 * 60 * 1000
        assertNull(embassies.getAt(world, 2, -3))
    }

    @Test
    fun `clearing started during war begins with its deadline paused`() {
        assertIs<Success<MfEmbassy>>(embassies.offer(host, guest, world, 2, -3))
        assertIs<Success<MfEmbassy>>(embassies.accept(guest, world, 2, -3))
        val war = mock(MfFactionRelationship::class.java)
        `when`(war.type).thenReturn(MfFactionRelationshipType.AT_WAR)
        `when`(relationshipService.getRelationships(host, guest)).thenReturn(listOf(war))

        assertIs<Success<MfEmbassy?>>(embassies.revoke(host, world, 2, -3))
        assertNotNull(embassies.getAt(world, 2, -3)?.pausedAt)
        clock.time += MfEmbassyService.CLEARING_MILLIS * 2
        assertEquals(MfEmbassyStatus.CLEARING, embassies.getAt(world, 2, -3)?.status)

        `when`(relationshipService.getRelationships(host, guest)).thenReturn(emptyList())
        assertIs<Success<Int>>(embassies.onWarStateChanged(host, guest, false))
        assertEquals(EmbassyAccessDecision.GRANT, embassies.access(guestPlayer, claim, ClaimAction.CONTAINER))
    }

    @Test
    fun `land consumer veto leaves no durable offer`() {
        veto = true
        assertIs<Failure<*>>(embassies.offer(host, guest, world, 2, -3))
        assertNull(embassies.getAt(world, 2, -3))
        assertTrue(repository.rows.isEmpty())
    }

    @Test
    fun `existing gate blocks offer and a gate added during the offer blocks acceptance`() {
        `when`(gateService.hasGateAreaInChunk(world, 2, -3)).thenReturn(true)
        assertIs<Failure<*>>(embassies.offer(host, guest, world, 2, -3))
        assertNull(embassies.getAt(world, 2, -3))

        `when`(gateService.hasGateAreaInChunk(world, 2, -3)).thenReturn(false)
        assertIs<Success<MfEmbassy>>(embassies.offer(host, guest, world, 2, -3))
        `when`(gateService.hasGateAreaInChunk(world, 2, -3)).thenReturn(true)
        assertIs<Failure<*>>(embassies.accept(guest, world, 2, -3))
        assertEquals(MfEmbassyStatus.OFFERED, embassies.getAt(world, 2, -3)?.status)
    }

    @Test
    fun `two by two agreement commits together and shares an internal boundary`() {
        val chunks = rectangle(2, -3, 2, 2)
        stubClaims(chunks)
        val offered = embassies.offerArea(host, guest, world, chunks)
        assertIs<Success<List<MfEmbassy>>>(offered)
        assertEquals(4, embassies.pendingOfferAt(world, 3, -2).size)
        assertEquals(4, embassies.hostedChunkCount(host))
        assertEquals(4, embassies.heldChunkCount(guest))
        assertFalse(embassies.sameProtectedArea(world, 2, -3, 3, -3))

        repository.duringBatch = {
            chunks.forEach { assertEquals(MfEmbassyStatus.OFFERED, embassies.getAt(world, it.x, it.z)?.status) }
        }
        assertIs<Success<List<MfEmbassy>>>(embassies.acceptArea(guest, world, 3, -2))
        repository.duringBatch = null
        assertTrue(chunks.all { embassies.getAt(world, it.x, it.z)?.status == MfEmbassyStatus.ACTIVE })
        assertTrue(embassies.sameProtectedArea(world, 2, -3, 3, -3))
        assertEquals(4, embassies.protectedAreaAt(world, 2, -3).size)
        val restarted = MfEmbassyService(plugin, repository, clock)
        assertEquals(4, restarted.listFor(host).size)
        assertEquals(8, events.size)

        val beforeWithdraw = repository.batches
        assertIs<Success<MfEmbassy?>>(embassies.revoke(host, world, 2, -3))
        assertEquals(beforeWithdraw + 1, repository.batches)
        assertTrue(chunks.all { embassies.getAt(world, it.x, it.z)?.status == MfEmbassyStatus.CLEARING })
        assertTrue(embassies.sameProtectedArea(world, 2, -3, 3, -3))
        assertIs<Success<Unit>>(embassies.finish(guest, world, 3, -2))
        assertTrue(repository.rows.isEmpty())
    }

    @Test
    fun `hosting and holding capacity are independent pools`() {
        val hosted = rectangle(2, -3, 2, 2)
        val reciprocal = rectangle(10, 10, 2, 2)
        stubClaims(hosted)
        stubClaims(reciprocal, guest)
        assertIs<Success<List<MfEmbassy>>>(embassies.offerArea(host, guest, world, hosted))
        assertIs<Success<List<MfEmbassy>>>(embassies.offerArea(guest, host, world, reciprocal))
        assertEquals(4, embassies.hostedChunkCount(host))
        assertEquals(4, embassies.heldChunkCount(host))
        assertEquals(4, embassies.hostedChunkCount(guest))
        assertEquals(4, embassies.heldChunkCount(guest))
    }

    @Test
    fun `disconnected areas duplicate cells and a partial veto leave no offer`() {
        val chunks = listOf(MfEmbassyService.ChunkPos(2, -3), MfEmbassyService.ChunkPos(5, -3))
        stubClaims(chunks)
        assertIs<Failure<*>>(embassies.offerArea(host, guest, world, chunks))
        assertIs<Failure<*>>(embassies.offerArea(host, guest, world, listOf(chunks.first(), chunks.first())))
        val connected = rectangle(2, -3, 2, 1)
        stubClaims(connected)
        vetoChunkX = 3
        assertIs<Failure<*>>(embassies.offerArea(host, guest, world, connected))
        assertTrue(repository.rows.isEmpty())
        assertEquals(0, embassies.hostedChunkCount(host))
        assertEquals(0, embassies.heldChunkCount(guest))
    }

    @Test
    fun `losing one offered cell cancels the entire pending area before acceptance`() {
        val chunks = rectangle(2, -3, 3, 1)
        stubClaims(chunks)
        assertIs<Success<List<MfEmbassy>>>(embassies.offerArea(host, guest, world, chunks))
        val middle = chunks[1]
        `when`(claimService.getClaim(world, middle.x, middle.z)).thenReturn(MfClaimedChunk(world, middle.x, middle.z, conqueror))
        assertTrue(embassies.pendingOfferAt(world, 2, -3).isEmpty())
        assertIs<Failure<*>>(embassies.acceptArea(guest, world, 2, -3))
        assertTrue(repository.rows.isEmpty())
        assertTrue(chunks.all { embassies.getAt(world, it.x, it.z) == null })

        stubClaims(chunks)
        assertIs<Success<List<MfEmbassy>>>(embassies.offerArea(host, guest, world, chunks))
        `when`(claimService.getClaim(world, middle.x, middle.z)).thenReturn(MfClaimedChunk(world, middle.x, middle.z, conqueror))
        assertIs<Success<Boolean>>(embassies.invalidateClaim(world, middle.x, middle.z))
        assertTrue(repository.rows.isEmpty())
    }

    @Test
    fun `restart cannot sign a connected remnant of a partially cascaded offer`() {
        val chunks = rectangle(2, -3, 2, 2)
        stubClaims(chunks)
        assertIs<Success<List<MfEmbassy>>>(embassies.offerArea(host, guest, world, chunks))
        repository.rows.remove(Triple(world, chunks.last().x, chunks.last().z))
        val restarted = MfEmbassyService(plugin, repository, clock)
        assertTrue(restarted.pendingOfferAt(world, 2, -3).isEmpty())
        assertIs<Failure<*>>(restarted.acceptArea(guest, world, 2, -3))
        assertTrue(repository.rows.isEmpty())
    }

    @Test
    fun `dynamic absent integer flag and exact stored numbers determine capacity`() {
        assertEquals(4, embassies.chunkLimit(host))
        plugin.config.set("factions.defaults.flags.maxEmbassyChunks", 12)
        assertEquals(12, embassies.chunkLimit(host))
        `when`(hostFaction.flags).thenReturn(MfFlagValues(plugin, mapOf("maxEmbassyChunks" to 8.0)))
        assertEquals(8, embassies.chunkLimit(host))
        listOf<Any?>(8.5, -1, 4097, "8", null, Double.NaN).forEach { invalid ->
            `when`(hostFaction.flags).thenReturn(MfFlagValues(plugin, mapOf("maxEmbassyChunks" to invalid)))
            assertEquals(0, embassies.chunkLimit(host))
            assertIs<Failure<*>>(embassies.offer(host, guest, world, 2, -3))
        }
        assertTrue(repository.rows.isEmpty())
    }

    @Test
    fun `both quotas reserve full offers and reductions preserve an existing offer`() {
        val chunks = rectangle(2, -3, 2, 2)
        stubClaims(chunks)
        `when`(guestFaction.flags).thenReturn(MfFlagValues(plugin, mapOf("maxEmbassyChunks" to 3)))
        assertIs<Failure<*>>(embassies.offerArea(host, guest, world, chunks))
        assertTrue(repository.rows.isEmpty())
        `when`(guestFaction.flags).thenReturn(MfFlagValues(plugin))
        assertIs<Success<List<MfEmbassy>>>(embassies.offerArea(host, guest, world, chunks))
        `when`(hostFaction.flags).thenReturn(MfFlagValues(plugin, mapOf("maxEmbassyChunks" to 0)))
        assertIs<Success<List<MfEmbassy>>>(embassies.acceptArea(guest, world, 2, -3))
        assertEquals(4, embassies.hostedChunkCount(host))
        assertEquals(MfEmbassyStatus.ACTIVE, embassies.getAt(world, 3, -2)?.status)
        val extra = MfEmbassyService.ChunkPos(4, -3)
        stubClaims(listOf(extra))
        assertIs<Failure<*>>(embassies.offerArea(host, guest, world, listOf(extra)))
    }

    @Test
    fun `adjacent expansion must be signed and withdrawal cancels it with the active area`() {
        val chunks = rectangle(2, -3, 2, 2)
        stubClaims(chunks)
        assertIs<Success<MfEmbassy>>(embassies.offer(host, guest, world, 2, -3))
        assertIs<Success<MfEmbassy>>(embassies.accept(guest, world, 2, -3))
        assertIs<Success<List<MfEmbassy>>>(embassies.offerArea(host, guest, world, chunks.drop(1)))
        assertEquals(3, embassies.pendingOfferAt(world, 3, -2).size)
        assertFalse(embassies.sameProtectedArea(world, 2, -3, 3, -3))
        assertIs<Failure<*>>(embassies.offer(host, guest, world, 4, -3))
        assertIs<Success<MfEmbassy?>>(embassies.release(guest, world, 3, -2))
        assertEquals(1, repository.rows.size)
        assertEquals(MfEmbassyStatus.CLEARING, embassies.getAt(world, 2, -3)?.status)
        assertNull(embassies.getAt(world, 3, -2))
    }

    @Test
    fun `declining or cancelling a pending expansion preserves the active area`() {
        val chunks = rectangle(2, -3, 2, 1)
        stubClaims(chunks)
        assertIs<Success<MfEmbassy>>(embassies.offer(host, guest, world, 2, -3))
        assertIs<Success<MfEmbassy>>(embassies.accept(guest, world, 2, -3))
        assertIs<Success<MfEmbassy>>(embassies.offer(host, guest, world, 3, -3))
        assertIs<Success<Unit>>(embassies.decline(guest, world, 3, -3))
        assertEquals(MfEmbassyStatus.ACTIVE, embassies.getAt(world, 2, -3)?.status)
        assertNull(embassies.getAt(world, 3, -3))
        assertIs<Success<MfEmbassy>>(embassies.offer(host, guest, world, 3, -3))
        assertIs<Success<MfEmbassy?>>(embassies.revoke(host, world, 3, -3))
        assertEquals(MfEmbassyStatus.ACTIVE, embassies.getAt(world, 2, -3)?.status)
        assertNull(embassies.getAt(world, 3, -3))
    }

    @Test
    fun `failed area storage never publishes partial offer acceptance or withdrawal`() {
        val chunks = rectangle(2, -3, 2, 2)
        stubClaims(chunks)
        repository.failUpsert = true
        assertIs<Failure<*>>(embassies.offerArea(host, guest, world, chunks))
        assertTrue(repository.rows.isEmpty())
        assertTrue(chunks.all { embassies.getAt(world, it.x, it.z) == null })
        repository.failUpsert = false
        assertIs<Success<List<MfEmbassy>>>(embassies.offerArea(host, guest, world, chunks))
        repository.failUpsert = true
        assertIs<Failure<*>>(embassies.acceptArea(guest, world, 2, -3))
        assertTrue(chunks.all { embassies.getAt(world, it.x, it.z)?.status == MfEmbassyStatus.OFFERED })
        repository.failUpsert = false
        assertIs<Success<List<MfEmbassy>>>(embassies.acceptArea(guest, world, 2, -3))
        repository.failUpsert = true
        assertIs<Failure<*>>(embassies.revoke(host, world, 2, -3))
        assertTrue(chunks.all { embassies.getAt(world, it.x, it.z)?.status == MfEmbassyStatus.ACTIVE })
        assertTrue(repository.rows.values.all { it.status == MfEmbassyStatus.ACTIVE })
    }

    @Test
    fun `partial conquest separates cells and ordinary finish preserves conquered recovery`() {
        val chunks = rectangle(2, -3, 2, 2)
        stubClaims(chunks)
        assertIs<Success<List<MfEmbassy>>>(embassies.offerArea(host, guest, world, chunks))
        assertIs<Success<List<MfEmbassy>>>(embassies.acceptArea(guest, world, 2, -3))
        listOf(chunks[0], chunks[1]).forEach { pos ->
            `when`(claimService.getClaim(world, pos.x, pos.z)).thenReturn(MfClaimedChunk(world, pos.x, pos.z, conqueror))
            assertIs<Success<Boolean>>(embassies.invalidateClaim(world, pos.x, pos.z))
            assertIs<Success<MfEmbassy?>>(embassies.chooseConquest(conqueror, world, pos.x, pos.z, true))
        }
        assertFalse(embassies.sameProtectedArea(world, chunks[0].x, chunks[0].z, chunks[1].x, chunks[1].z))
        assertEquals(1, embassies.protectedAreaAt(world, chunks[0].x, chunks[0].z).size)
        assertEquals(2, embassies.hostedChunkCount(conqueror))
        assertEquals(2, embassies.hostedChunkCount(host))
        assertIs<Failure<*>>(embassies.offer(host, guest, world, 4, -3))
        val retained = chunks[2]
        assertIs<Success<MfEmbassy?>>(embassies.revoke(host, world, retained.x, retained.z))
        assertIs<Success<Unit>>(embassies.finish(guest, world, retained.x, retained.z))
        assertEquals(2, repository.rows.size)
        assertTrue(repository.rows.values.all { it.status == MfEmbassyStatus.CONQUEST_PASSAGE })
        assertIs<Success<Unit>>(embassies.finish(guest, world, chunks[0].x, chunks[0].z))
        assertEquals(1, repository.rows.size)
    }

    @Test
    fun `failed war pause keeps the original remaining retrieval window until persistence recovers`() {
        assertIs<Success<MfEmbassy>>(embassies.offer(host, guest, world, 2, -3))
        assertIs<Success<MfEmbassy>>(embassies.accept(guest, world, 2, -3))
        assertIs<Success<MfEmbassy?>>(embassies.revoke(host, world, 2, -3))
        val remaining = 11L * 24 * 60 * 60 * 1000
        clock.time += 3L * 24 * 60 * 60 * 1000
        setWar(true)
        repository.failUpsert = true
        assertIs<Failure<*>>(embassies.onWarStateChanged(host, guest, true))
        clock.time += 30L * 24 * 60 * 60 * 1000
        assertEquals(MfEmbassyStatus.CLEARING, embassies.getAt(world, 2, -3)?.status)
        repository.failUpsert = false
        setWar(false)
        assertIs<Success<Int>>(embassies.onWarStateChanged(host, guest, false))
        assertEquals(clock.time + remaining, embassies.getAt(world, 2, -3)?.deadlineAt)
        clock.time += remaining
        assertNull(embassies.getAt(world, 2, -3))
    }

    @Test
    fun `restart preserves expired unpaused recovery during a still active war`() {
        assertIs<Success<MfEmbassy>>(embassies.offer(host, guest, world, 2, -3))
        assertIs<Success<MfEmbassy>>(embassies.accept(guest, world, 2, -3))
        assertIs<Success<MfEmbassy?>>(embassies.revoke(host, world, 2, -3))
        clock.time += MfEmbassyService.CLEARING_MILLIS + 1000
        setWar(true)
        val restarted = MfEmbassyService(plugin, repository, clock)
        assertEquals(MfEmbassyStatus.CLEARING, restarted.getAt(world, 2, -3)?.status)
        assertNotNull(restarted.getAt(world, 2, -3)?.pausedAt)
        assertIs<Success<Int>>(restarted.sweep())
        clock.time += 30L * 24 * 60 * 60 * 1000
        setWar(false)
        assertIs<Success<Int>>(restarted.onWarStateChanged(host, guest, false))
        assertEquals(clock.time + MfEmbassyService.CLEARING_MILLIS, restarted.getAt(world, 2, -3)?.deadlineAt)
    }

    @Test
    fun `restart preserves conquest passage after a missing wartime decision marker`() {
        assertIs<Success<MfEmbassy>>(embassies.offer(host, guest, world, 2, -3))
        assertIs<Success<MfEmbassy>>(embassies.accept(guest, world, 2, -3))
        `when`(claimService.getClaim(world, 2, -3)).thenReturn(MfClaimedChunk(world, 2, -3, conqueror))
        assertIs<Success<Boolean>>(embassies.invalidateClaim(world, 2, -3))
        clock.time += MfEmbassyService.CONQUEST_DECISION_MILLIS + MfEmbassyService.CLEARING_MILLIS + 1000
        val war = mock(MfFactionRelationship::class.java)
        `when`(war.type).thenReturn(MfFactionRelationshipType.AT_WAR)
        `when`(relationshipService.getRelationships(conqueror, guest)).thenReturn(listOf(war))
        val restarted = MfEmbassyService(plugin, repository, clock)
        assertEquals(MfEmbassyStatus.CONQUEST_PASSAGE, restarted.getAt(world, 2, -3)?.status)
        assertNotNull(restarted.getAt(world, 2, -3)?.pausedAt)
        assertIs<Success<Int>>(restarted.sweep())
        `when`(relationshipService.getRelationships(conqueror, guest)).thenReturn(emptyList())
        assertIs<Success<Int>>(restarted.onWarStateChanged(conqueror, guest, false))
        assertEquals(clock.time + MfEmbassyService.CLEARING_MILLIS, restarted.getAt(world, 2, -3)?.deadlineAt)
    }

    @Test
    fun `missing runtime protection closes new offers and acceptance`() {
        assertIs<Success<MfEmbassy>>(embassies.offer(host, guest, world, 2, -3))
        embassies.blockNewAgreements("Required storage-vehicle protection is unavailable")
        assertIs<Failure<*>>(embassies.accept(guest, world, 2, -3))
        assertIs<Failure<*>>(embassies.offer(host, guest, world, 3, -3))
        assertEquals(MfEmbassyStatus.OFFERED, embassies.getAt(world, 2, -3)?.status)
    }

    private fun rectangle(x: Int, z: Int, width: Int, depth: Int) =
        (x until x + width).flatMap { cellX -> (z until z + depth).map { MfEmbassyService.ChunkPos(cellX, it) } }

    private fun stubClaims(chunks: List<MfEmbassyService.ChunkPos>, owner: MfFactionId = host) {
        chunks.forEach { pos ->
            `when`(claimService.getClaim(world, pos.x, pos.z)).thenReturn(MfClaimedChunk(world, pos.x, pos.z, owner))
        }
    }

    private fun setWar(atWar: Boolean) {
        val war = mock(MfFactionRelationship::class.java)
        `when`(war.type).thenReturn(MfFactionRelationshipType.AT_WAR)
        `when`(relationshipService.getRelationships(host, guest)).thenReturn(if (atWar) listOf(war) else emptyList())
    }

    private class MemoryRepository : MfEmbassyRepository {
        val rows = LinkedHashMap<Triple<UUID, Int, Int>, MfEmbassy>()
        var failUpsert = false
        var batches = 0
        var duringBatch: (() -> Unit)? = null
        override fun getAll() = rows.values.toList()
        override fun applyChanges(upserts: List<MfEmbassy>, deletes: List<MfEmbassy>) {
            if (failUpsert) throw IllegalStateException("Simulated embassy storage outage")
            duringBatch?.invoke()
            val next = rows.toMutableMap()
            deletes.forEach { next.remove(Triple(it.worldId, it.chunkX, it.chunkZ)) }
            upserts.forEach { next[Triple(it.worldId, it.chunkX, it.chunkZ)] = it }
            rows.clear()
            rows.putAll(next)
            batches++
        }
        override fun upsert(embassy: MfEmbassy) {
            if (failUpsert) throw IllegalStateException("Simulated embassy storage outage")
            rows[Triple(embassy.worldId, embassy.chunkX, embassy.chunkZ)] = embassy
        }
        override fun delete(worldId: UUID, chunkX: Int, chunkZ: Int) {
            rows.remove(Triple(worldId, chunkX, chunkZ))
        }
    }

    private class MutableClock(var time: Long = 0) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(time)
    }
}
