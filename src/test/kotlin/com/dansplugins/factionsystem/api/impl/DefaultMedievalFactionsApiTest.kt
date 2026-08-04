package com.dansplugins.factionsystem.api.impl

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.FactionId
import com.dansplugins.factionsystem.api.geometry.ChunkPos
import com.dansplugins.factionsystem.claim.MfClaimService
import com.dansplugins.factionsystem.claim.MfClaimedChunk
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.faction.MfFactionService
import com.dansplugins.factionsystem.player.MfPlayer
import com.dansplugins.factionsystem.player.MfPlayerId
import com.dansplugins.factionsystem.player.MfPlayerService
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipService
import com.dansplugins.factionsystem.service.Services
import org.bukkit.Chunk
import org.bukkit.World
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import java.util.UUID

/** Verifies the API adapter maps internal types to stable views and reports failures cleanly. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DefaultMedievalFactionsApiTest {

    private lateinit var plugin: MedievalFactions
    private lateinit var factionService: MfFactionService
    private lateinit var claimService: MfClaimService
    private lateinit var relationshipService: MfFactionRelationshipService
    private lateinit var playerService: MfPlayerService
    private lateinit var api: DefaultMedievalFactionsApi

    @BeforeEach
    fun setUp() {
        plugin = mock(MedievalFactions::class.java)
        val services = mock(Services::class.java)
        `when`(plugin.services).thenReturn(services)
        factionService = mock(MfFactionService::class.java)
        `when`(services.factionService).thenReturn(factionService)
        claimService = mock(MfClaimService::class.java)
        `when`(services.claimService).thenReturn(claimService)
        relationshipService = mock(MfFactionRelationshipService::class.java)
        `when`(services.factionRelationshipService).thenReturn(relationshipService)
        playerService = mock(MfPlayerService::class.java)
        `when`(services.playerService).thenReturn(playerService)
        api = DefaultMedievalFactionsApi(plugin)
    }

    @Test
    fun getFactionMapsCoreFieldsToView() {
        val faction = mock(MfFaction::class.java)
        `when`(factionService.getFaction(MfFactionId("f1"))).thenReturn(faction)
        `when`(faction.name).thenReturn("Foo")
        `when`(faction.description).thenReturn("desc")
        `when`(faction.home).thenReturn(null)
        `when`(faction.members).thenReturn(emptyList())
        `when`(claimService.getClaimCount(faction.id)).thenReturn(3)
        `when`(relationshipService.getFactionsAtWarWith(faction.id)).thenReturn(emptyList())

        val view = api.getFaction(FactionId("f1"))

        assertNotNull(view)
        assertEquals("Foo", view!!.name)
        assertEquals("desc", view.description)
        assertNull(view.home)
        assertEquals(emptyList<UUID>(), view.memberIds)
        assertEquals(3, view.claimCount)
        assertEquals(emptyList<FactionId>(), view.factionsAtWarWith)
    }

    @Test
    fun getFactionReturnsNullForUnknownFaction() {
        assertNull(api.getFaction(FactionId("does-not-exist")))
    }

    @Test
    fun getClaimAtMapsClaimToView() {
        val chunk = mock(Chunk::class.java)
        val worldId = UUID.randomUUID()
        `when`(claimService.getClaim(chunk)).thenReturn(MfClaimedChunk(worldId, 3, 7, MfFactionId("f1")))

        val view = api.getClaimAt(chunk)

        assertNotNull(view)
        assertEquals(worldId, view!!.worldId)
        assertEquals(3, view.chunkX)
        assertEquals(7, view.chunkZ)
        assertEquals(FactionId("f1"), view.factionId)
    }

    @Test
    fun getFactionAtReturnsNullForUnclaimedChunk() {
        val chunk = mock(Chunk::class.java)
        `when`(claimService.getClaim(chunk)).thenReturn(null)
        assertNull(api.getFactionAt(chunk))
    }

    @Test
    fun isClaimedReportsClaimedAndUnclaimedChunkCoordinates() {
        val world = mock(World::class.java)
        `when`(claimService.getClaim(world, 3, 7)).thenReturn(MfClaimedChunk(UUID.randomUUID(), 3, 7, MfFactionId("f1")))
        `when`(claimService.getClaim(world, 4, 7)).thenReturn(null)

        assertTrue(api.isClaimed(world, 3, 7))
        assertFalse(api.isClaimed(world, 4, 7))
    }

    // The reason this overload exists at all. Routing it through the Chunk-taking lookup would drag
    // Location.getChunk() back in and load the chunk, which is exactly what callers testing many
    // block positions per tick cannot afford. Asserting the coordinate lookup is the ONLY call made
    // pins that down without argument matchers, which cannot express "any Chunk" here anyway: the
    // parameter is non-null Kotlin, so a null-returning matcher blows up at the call site.
    @Test
    fun isClaimedNeverResolvesAChunk() {
        val world = mock(World::class.java)
        `when`(claimService.getClaim(world, 0, 0)).thenReturn(null)

        api.isClaimed(world, 0, 0)

        verify(claimService).getClaim(world, 0, 0)
        verifyNoMoreInteractions(claimService)
    }

    @Test
    fun getPowerReturnsThePlayersPower() {
        val playerId = UUID.randomUUID()
        val player = mock(MfPlayer::class.java)
        `when`(player.power).thenReturn(12.5)
        `when`(playerService.getPlayer(MfPlayerId(playerId.toString()))).thenReturn(player)

        assertEquals(12.5, api.getPower(playerId))
    }

    // 0.0 rather than an exception or null: consumers sum power across a member list, and a player MF
    // has never seen contributes nothing rather than forcing null handling at every call site.
    @Test
    fun getPowerReturnsZeroForUnknownPlayer() {
        assertEquals(0.0, api.getPower(UUID.randomUUID()))
    }

    @Test
    fun getFactionByNameLooksUpByName() {
        val faction = mock(MfFaction::class.java)
        `when`(factionService.getFaction("Foo")).thenReturn(faction)
        `when`(faction.name).thenReturn("Foo")
        `when`(faction.description).thenReturn("desc")
        `when`(faction.home).thenReturn(null)
        `when`(faction.members).thenReturn(emptyList())
        `when`(claimService.getClaimCount(faction.id)).thenReturn(0)
        `when`(relationshipService.getFactionsAtWarWith(faction.id)).thenReturn(emptyList())

        assertEquals("Foo", api.getFactionByName("Foo")?.name)
        assertNull(api.getFactionByName("no-such-faction"))
    }

    @Test
    fun forcePeaceFailsWhenFactionsAreNotAtWar() {
        val result = api.forcePeace(FactionId("a"), FactionId("b"))
        assertTrue(result.isFailure)
        assertEquals("Factions are not at war", result.errorMessage)
    }

    @Test
    fun unclaimFailsForUnclaimedChunk() {
        val chunk = mock(Chunk::class.java)
        `when`(claimService.getClaim(chunk)).thenReturn(null)
        val result = api.unclaim(chunk)
        assertTrue(result.isFailure)
        assertEquals("Chunk is not claimed", result.errorMessage)
    }

    // The grouping is the contract, not an implementation detail: ChunkPos carries no world, so a
    // caller handed one flat set would trace a boundary across two worlds and get nonsense out.
    @Test
    fun getClaimedChunksGroupsByWorld() {
        val overworld = UUID.randomUUID()
        val nether = UUID.randomUUID()
        `when`(claimService.getClaims(MfFactionId("f1"))).thenReturn(
            listOf(
                MfClaimedChunk(overworld, 0, 0, MfFactionId("f1")),
                MfClaimedChunk(overworld, 1, 0, MfFactionId("f1")),
                MfClaimedChunk(nether, -4, 9, MfFactionId("f1"))
            )
        )

        val byWorld = api.getClaimedChunks(FactionId("f1"))

        assertEquals(setOf(overworld, nether), byWorld.keys)
        assertEquals(setOf(ChunkPos(0, 0), ChunkPos(1, 0)), byWorld[overworld])
        assertEquals(setOf(ChunkPos(-4, 9)), byWorld[nether])
    }

    // An empty map rather than a map of empty sets. A caller iterating the result to build one marker
    // set per world must not be handed worlds the faction holds nothing in.
    @Test
    fun getClaimedChunksIsEmptyForAFactionWithNoClaims() {
        `when`(claimService.getClaims(MfFactionId("f1"))).thenReturn(emptyList())

        assertTrue(api.getClaimedChunks(FactionId("f1")).isEmpty())
        assertTrue(api.getClaimedChunks(FactionId("f1"), UUID.randomUUID()).isEmpty())
    }

    @Test
    fun getClaimedChunksForOneWorldExcludesEveryOther() {
        val overworld = UUID.randomUUID()
        val nether = UUID.randomUUID()
        `when`(claimService.getClaims(MfFactionId("f1"))).thenReturn(
            listOf(
                MfClaimedChunk(overworld, 2, 3, MfFactionId("f1")),
                MfClaimedChunk(nether, 2, 3, MfFactionId("f1"))
            )
        )

        assertEquals(setOf(ChunkPos(2, 3)), api.getClaimedChunks(FactionId("f1"), overworld))
        assertEquals(emptySet<ChunkPos>(), api.getClaimedChunks(FactionId("f1"), UUID.randomUUID()))
    }

    // Delegates to the O(1) index rather than counting a materialised list, which is the only reason
    // the method exists separately at all.
    @Test
    fun getClaimCountUsesTheIndexRatherThanListingClaims() {
        `when`(claimService.getClaimCount(MfFactionId("f1"))).thenReturn(1234)

        assertEquals(1234, api.getClaimCount(FactionId("f1")))

        verify(claimService).getClaimCount(MfFactionId("f1"))
        verifyNoMoreInteractions(claimService)
    }
}
