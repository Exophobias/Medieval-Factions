package com.dansplugins.factionsystem.api.impl

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.anyArg
import com.dansplugins.factionsystem.api.FactionId
import com.dansplugins.factionsystem.api.PeaceOutcome
import com.dansplugins.factionsystem.api.geometry.ChunkPos
import com.dansplugins.factionsystem.claim.MfClaimService
import com.dansplugins.factionsystem.claim.MfClaimedChunk
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.faction.MfFactionService
import com.dansplugins.factionsystem.faction.flag.MfFlagValues
import com.dansplugins.factionsystem.faction.flag.MfFlags
import com.dansplugins.factionsystem.faction.permission.MfFactionPermissions
import com.dansplugins.factionsystem.faction.role.MfFactionRoles
import com.dansplugins.factionsystem.lang.Language
import com.dansplugins.factionsystem.player.MfPlayer
import com.dansplugins.factionsystem.player.MfPlayerId
import com.dansplugins.factionsystem.player.MfPlayerService
import com.dansplugins.factionsystem.relationship.MfFactionRelationship
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipService
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType.ALLY
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType.AT_WAR
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType.LIEGE
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType.VASSAL
import com.dansplugins.factionsystem.service.Services
import dev.forkhandles.result4k.Success
import org.bukkit.Chunk
import org.bukkit.World
import org.bukkit.configuration.file.FileConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.Mockito.RETURNS_SMART_NULLS
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import java.util.UUID

/** Verifies the API adapter maps internal types to stable views and reports failures cleanly. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DefaultMedievalFactionsApiTest {

    private lateinit var plugin: MedievalFactions
    private lateinit var config: FileConfiguration
    private lateinit var factionService: MfFactionService
    private lateinit var claimService: MfClaimService
    private lateinit var relationshipService: MfFactionRelationshipService
    private lateinit var playerService: MfPlayerService
    private lateinit var api: DefaultMedievalFactionsApi

    /** The faction the last successful save was handed, so a flag write can be read back. */
    private var savedFaction: MfFaction? = null

    @BeforeEach
    fun setUp() {
        plugin = mock(MedievalFactions::class.java)
        config = mock(FileConfiguration::class.java)
        `when`(plugin.config).thenReturn(config)
        `when`(plugin.language).thenReturn(mock(Language::class.java, RETURNS_SMART_NULLS))
        // The real flag list and the real permission list, so a flag test is asserting against what
        // the plugin actually registers. Both are built into locals first, because constructing
        // either reads back off the mocked plugin.
        val flags = MfFlags(plugin)
        `when`(plugin.flags).thenReturn(flags)
        val permissions = MfFactionPermissions(plugin)
        `when`(plugin.factionPermissions).thenReturn(permissions)
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
        savedFaction = null
        `when`(factionService.save(anyArg())).thenAnswer { invocation ->
            val faction = invocation.getArgument<MfFaction>(0)
            savedFaction = faction
            Success(faction)
        }
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

    /**
     * The reason [DefaultMedievalFactionsApi.layDownArms] exists: the caller's rows go, the other
     * side's stay, and the war is still on. A consumer told this is peace would announce one that has
     * not happened.
     */
    @Test
    fun layDownArmsReportsAPeaceRequestWhileTheOtherSideStillHoldsItsHalf() {
        val ours = atWar("a", "b")
        atWar("b", "a")

        val outcome = api.layDownArms(FactionId("a"), FactionId("b"))

        assertTrue(outcome.isSuccess)
        assertEquals(PeaceOutcome.PEACE_REQUESTED, outcome.get())
        verify(relationshipService).delete(ours.id)
    }

    /** The second half of the handshake: the last row goes, so the war is over. */
    @Test
    fun layDownArmsReportsPeaceWhenTheLastRowGoes() {
        val ours = atWar("a", "b")

        val outcome = api.layDownArms(FactionId("a"), FactionId("b"))

        assertTrue(outcome.isSuccess)
        assertEquals(PeaceOutcome.PEACE_MADE, outcome.get())
        verify(relationshipService).delete(ours.id)
    }

    @Test
    fun layDownArmsFailsWhenNeitherSideIsAtWar() {
        existingFaction("a")
        existingFaction("b")

        val outcome = api.layDownArms(FactionId("a"), FactionId("b"))

        assertTrue(outcome.isFailure)
        assertEquals("Factions are not at war", outcome.errorMessage)
        // Reading both sides is the whole of the work here: nothing was deleted.
        verify(relationshipService).getRelationships(MfFactionId("a"), MfFactionId("b"))
        verify(relationshipService).getRelationships(MfFactionId("b"), MfFactionId("a"))
        verifyNoMoreInteractions(relationshipService)
    }

    /**
     * Distinct from "not at war", exactly as /f makepeace keeps them distinct. Telling a faction whose
     * half is already down that there is no war would be the opposite of the truth: the other side is
     * still at war with it.
     */
    @Test
    fun layDownArmsSaysPeaceIsAlreadyRequestedWhenOnlyTheOtherSideHoldsRows() {
        val theirs = atWar("b", "a")

        val outcome = api.layDownArms(FactionId("a"), FactionId("b"))

        assertTrue(outcome.isFailure)
        assertTrue(outcome.errorMessage!!.contains("already been requested"))
        // Their half is emphatically not the caller's to lay down.
        verify(relationshipService, never()).delete(theirs.id)
    }

    /**
     * The war goes and nothing else does. Two factions hold more than one row against each other all
     * the time, and only the AT_WAR ones are a peace's business: an alliance is not the war's to end,
     * and a vassalage row is a House's place in the title ladder.
     *
     * Guards the AT_WAR filter on the caller's own rows. Drop it and the alliance and the vassalage go
     * down with the war, silently, on a green build.
     */
    @Test
    fun layDownArmsDeletesTheWarRowAndLeavesTheAllianceAndVassalageStanding() {
        val ours = relationships("a", "b", AT_WAR, ALLY, VASSAL)

        val outcome = api.layDownArms(FactionId("a"), FactionId("b"))

        assertTrue(outcome.isSuccess)
        assertEquals(PeaceOutcome.PEACE_MADE, outcome.get())
        verify(relationshipService).delete(ours.getValue(AT_WAR).id)
        assertNotDeleted(ours - AT_WAR)
    }

    /**
     * The same on the PEACE_REQUESTED side, where the other faction is still at war and holds rows of
     * its own. Neither side's non-war rows are touched, and the other side's rows are not the caller's
     * to touch at all.
     */
    @Test
    fun layDownArmsWithMixedRowsOnBothSidesRequestsPeaceAndDeletesOnlyOurWar() {
        val ours = relationships("a", "b", AT_WAR, ALLY)
        val theirs = relationships("b", "a", AT_WAR, LIEGE)

        val outcome = api.layDownArms(FactionId("a"), FactionId("b"))

        assertTrue(outcome.isSuccess)
        assertEquals(PeaceOutcome.PEACE_REQUESTED, outcome.get())
        verify(relationshipService).delete(ours.getValue(AT_WAR).id)
        assertNotDeleted(ours - AT_WAR)
        assertNotDeleted(theirs)
    }

    /**
     * Guards the AT_WAR filter on the OTHER side's rows, which decides which of the two outcomes is
     * reported. A liege that has laid its war down is at peace with its vassal, and being owed homage
     * is not a war still running: reporting PEACE_REQUESTED here would leave a consumer waiting on a
     * second half that nobody owes.
     */
    @Test
    fun layDownArmsReportsPeaceWhenTheOtherSideOnlyHoldsVassalageAndAllianceRows() {
        val ours = relationships("a", "b", AT_WAR)
        val theirs = relationships("b", "a", VASSAL, ALLY)

        val outcome = api.layDownArms(FactionId("a"), FactionId("b"))

        assertTrue(outcome.isSuccess)
        assertEquals(PeaceOutcome.PEACE_MADE, outcome.get())
        verify(relationshipService).delete(ours.getValue(AT_WAR).id)
        assertNotDeleted(theirs)
    }

    /**
     * Allies are not at war, so there is no war to lay down and nothing to delete. Both filters answer
     * for this one: without the caller's, an ally asking for peace would have its alliance deleted and
     * be told peace was made.
     */
    @Test
    fun layDownArmsFailsAsNotAtWarWhenTheOnlyRowsAreAlliancesAndVassalage() {
        val ours = relationships("a", "b", ALLY, LIEGE)
        val theirs = relationships("b", "a", ALLY, VASSAL)

        val outcome = api.layDownArms(FactionId("a"), FactionId("b"))

        assertTrue(outcome.isFailure)
        assertEquals("Factions are not at war", outcome.errorMessage)
        assertNotDeleted(ours)
        assertNotDeleted(theirs)
    }

    /**
     * The already-requested branch has to stay reachable when the other side's remaining war row sits
     * alongside rows of other types, since that branch is chosen by the same filter.
     */
    @Test
    fun layDownArmsSaysPeaceIsAlreadyRequestedWhenOurOnlyRemainingRowIsAnAlliance() {
        val ours = relationships("a", "b", ALLY)
        val theirs = relationships("b", "a", AT_WAR, VASSAL)

        val outcome = api.layDownArms(FactionId("a"), FactionId("b"))

        assertTrue(outcome.isFailure)
        assertTrue(outcome.errorMessage!!.contains("already been requested"))
        assertNotDeleted(ours)
        assertNotDeleted(theirs)
    }

    /**
     * forcePeace ends both halves at once, and the same rule holds on both: the wars go, every other
     * relationship between the two factions stands.
     */
    @Test
    fun forcePeaceDeletesTheWarRowsOnBothSidesAndNothingElse() {
        val ours = relationships("a", "b", AT_WAR, ALLY)
        val theirs = relationships("b", "a", AT_WAR, VASSAL)

        val result = api.forcePeace(FactionId("a"), FactionId("b"))

        assertTrue(result.isSuccess)
        verify(relationshipService).delete(ours.getValue(AT_WAR).id)
        verify(relationshipService).delete(theirs.getValue(AT_WAR).id)
        assertNotDeleted(ours - AT_WAR)
        assertNotDeleted(theirs - AT_WAR)
    }

    /** An alliance is not a war, so forcing peace on one is a failure with nothing deleted. */
    @Test
    fun forcePeaceFailsWhenTheOnlyRowsAreAlliancesAndVassalage() {
        val ours = relationships("a", "b", ALLY, LIEGE)
        val theirs = relationships("b", "a", ALLY, VASSAL)

        val result = api.forcePeace(FactionId("a"), FactionId("b"))

        assertTrue(result.isFailure)
        assertEquals("Factions are not at war", result.errorMessage)
        assertNotDeleted(ours)
        assertNotDeleted(theirs)
    }

    /** Registers a faction with the faction service so an existence check finds it. */
    private fun existingFaction(id: String): MfFaction {
        val faction = mock(MfFaction::class.java)
        `when`(factionService.getFaction(MfFactionId(id))).thenReturn(faction)
        return faction
    }

    /**
     * One AT_WAR row, held by [holder] against [target], with both factions registered and its
     * deletion stubbed to succeed. Returns the row so a test can assert it was the one deleted.
     */
    private fun atWar(holder: String, target: String): MfFactionRelationship =
        relationships(holder, target, AT_WAR).getValue(AT_WAR)

    /**
     * One row per [types], all held by [holder] against [target], with both factions registered and
     * every deletion stubbed to succeed. Returns them keyed by type so a test can name the row it
     * expects to go and the rows it expects to survive.
     *
     * A pair of factions really does hold rows of several types at once: a liege at war with its own
     * vassal is an ordinary week here, and the vassalage row is the title ladder and the fief system.
     * A peace must take the war and nothing else.
     */
    private fun relationships(
        holder: String,
        target: String,
        vararg types: MfFactionRelationshipType
    ): Map<MfFactionRelationshipType, MfFactionRelationship> {
        existingFaction(holder)
        existingFaction(target)
        val rows = types.toList().associateWith { type ->
            MfFactionRelationship(
                factionId = MfFactionId(holder),
                targetId = MfFactionId(target),
                type = type
            )
        }
        `when`(relationshipService.getRelationships(MfFactionId(holder), MfFactionId(target)))
            .thenReturn(rows.values.toList())
        rows.values.forEach { row -> `when`(relationshipService.delete(row.id)).thenReturn(Success(Unit)) }
        return rows
    }

    /** Asserts none of [rows] was deleted, naming the type in the failure so a break reads plainly. */
    private fun assertNotDeleted(rows: Map<MfFactionRelationshipType, MfFactionRelationship>) {
        rows.forEach { (type, row) ->
            verify(relationshipService, never().description("$type row was deleted")).delete(row.id)
        }
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

    // --- faction flags ---

    @Test
    fun getFlagReturnsTheStoredValue() {
        val faction = realFaction("coatofarms" to "2CJW-634K-M")

        assertEquals("2CJW-634K-M", api.getFlag(FactionId(faction.id.value), "coatofarms"))
    }

    /**
     * A faction that has never set a flag reports the flag's default, not null, because that is the
     * value MF itself reads everywhere. Null therefore only ever means there is nothing to read.
     */
    @Test
    fun getFlagFallsBackToTheFlagsDefaultRatherThanNull() {
        val faction = realFaction()

        assertEquals("", api.getFlag(FactionId(faction.id.value), "coatofarms"))
        assertEquals("false", api.getFlag(FactionId(faction.id.value), "neutral"))
    }

    /** MF's own flag lookup ignores case, and a consumer typing the name should not have to care. */
    @Test
    fun getFlagIgnoresTheCaseOfTheFlagName() {
        val faction = realFaction("coatofarms" to "2CJW-634K-M")

        assertEquals("2CJW-634K-M", api.getFlag(FactionId(faction.id.value), "CoatOfArms"))
    }

    @Test
    fun getFlagReturnsNullForAnUnregisteredFlag() {
        val faction = realFaction()

        assertNull(api.getFlag(FactionId(faction.id.value), "thereisnosuchflag"))
    }

    @Test
    fun getFlagReturnsNullForAnUnknownFaction() {
        assertNull(api.getFlag(FactionId("does-not-exist"), "coatofarms"))
    }

    /**
     * The write, and the reason this pair exists at all: a consumer mirroring a House's arms onto its
     * faction had no route to a flag short of MF's internal services and a seventeen-parameter data
     * class copy.
     *
     * Note that the faction here grants nothing to anybody. [DefaultMedievalFactionsApi.setFlag]
     * deliberately checks no faction permission, because it is a plugin acting rather than a player;
     * deciding who may ask is the caller's job.
     */
    @Test
    fun setFlagWritesTheValueOntoTheFaction() {
        val faction = realFaction()

        val result = api.setFlag(FactionId(faction.id.value), "coatofarms", "2CJW-634K-M")

        assertTrue(result.isSuccess)
        assertEquals("2CJW-634K-M", savedFaction?.flags?.get(plugin.flags.coatOfArms))
    }

    /** The string is coerced by the flag's own rules, so a boolean flag stores a boolean. */
    @Test
    fun setFlagCoercesTheValueToTheFlagsType() {
        val faction = realFaction()

        val result = api.setFlag(FactionId(faction.id.value), "alliesCanInteractWithLand", "true")

        assertTrue(result.isSuccess)
        assertEquals(true, savedFaction?.flags?.valuesByName?.get("alliesCanInteractWithLand"))
    }

    @Test
    fun setFlagFailsForAnUnregisteredFlag() {
        val faction = realFaction()

        assertTrue(api.setFlag(FactionId(faction.id.value), "thereisnosuchflag", "x").isFailure)
        verify(factionService, never()).save(anyArg())
    }

    @Test
    fun setFlagFailsForAnUnknownFaction() {
        assertTrue(api.setFlag(FactionId("does-not-exist"), "coatofarms", "2CJW-634K-M").isFailure)
        verify(factionService, never()).save(anyArg())
    }

    @Test
    fun setFlagRefusesAValueItCannotCoerce() {
        val faction = realFaction()

        assertTrue(api.setFlag(FactionId(faction.id.value), "neutral", "banana").isFailure)
        verify(factionService, never()).save(anyArg())
    }

    /** The flag's own validator has the last word, so a consumer cannot write past a flag's rules. */
    @Test
    fun setFlagRefusesAValueTheFlagsValidatorRejects() {
        val faction = realFaction()

        assertTrue(api.setFlag(FactionId(faction.id.value), "color", "not a colour").isFailure)
        assertTrue(api.setFlag(FactionId(faction.id.value), "coatofarms", "A".repeat(65)).isFailure)
        verify(factionService, never()).save(anyArg())
    }

    /**
     * factions.allowNeutrality is the server owner's setting rather than the faction's, so this API
     * offers no way around it. The same reasoning as allowLeaderlessFactions in setPrimaryOwner.
     */
    @Test
    fun setFlagWillNotTurnNeutralityOnWhereTheServerForbidsIt() {
        val faction = realFaction()
        `when`(config.getBoolean("factions.allowNeutrality")).thenReturn(false)

        assertTrue(api.setFlag(FactionId(faction.id.value), "neutral", "true").isFailure)
        verify(factionService, never()).save(anyArg())
    }

    /** Turning it off must stay possible on a server that has just forbidden it. */
    @Test
    fun setFlagWillStillTurnNeutralityOffWhereTheServerForbidsIt() {
        val faction = realFaction("neutral" to true)
        `when`(config.getBoolean("factions.allowNeutrality")).thenReturn(false)

        assertTrue(api.setFlag(FactionId(faction.id.value), "neutral", "false").isSuccess)
    }

    /**
     * A whole faction save costs `6 + 2 x (members + invites + applications)` statements, so a
     * reconciler writing the value it already holds would pay that on every pass. Reported as success
     * rather than failure: nothing is wrong, and the caller wanted the flag to hold that value.
     */
    @Test
    fun setFlagSkipsTheSaveWhenTheFlagAlreadyHoldsThatValue() {
        val faction = realFaction("coatofarms" to "2CJW-634K-M")

        assertTrue(api.setFlag(FactionId(faction.id.value), "coatofarms", "2CJW-634K-M").isSuccess)
        verify(factionService, never()).save(anyArg())
    }

    /** The same skip, against a flag the faction has never set whose default already matches. */
    @Test
    fun setFlagSkipsTheSaveWhenTheDefaultAlreadyMatches() {
        val faction = realFaction()

        assertTrue(api.setFlag(FactionId(faction.id.value), "coatofarms", "").isSuccess)
        verify(factionService, never()).save(anyArg())
    }

    /**
     * A real [MfFaction] rather than a mock, because these tests read the flag map the adapter writes
     * and a mocked data class has no working copy().
     *
     * Registered with the faction service on the way out, so a test only has to name the flags it
     * cares about.
     */
    private fun realFaction(vararg flagValues: Pair<String, Any>): MfFaction {
        val factionId = MfFactionId.generate()
        // Built before the stubbing starts: constructing an MfFaction calls back into the mocked
        // plugin, and Mockito treats a mock call made mid-stubbing as an unfinished stub.
        val faction = MfFaction(
            plugin,
            id = factionId,
            name = "Test Faction",
            roles = MfFactionRoles.defaults(plugin, factionId),
            flags = MfFlagValues(plugin, mapOf(*flagValues))
        )
        `when`(factionService.getFaction(factionId)).thenReturn(faction)
        return faction
    }
}
