package com.dansplugins.factionsystem.api.impl

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.FactionId
import com.dansplugins.factionsystem.claim.MfClaimService
import com.dansplugins.factionsystem.claim.MfClaimedChunk
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.faction.MfFactionService
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
}
