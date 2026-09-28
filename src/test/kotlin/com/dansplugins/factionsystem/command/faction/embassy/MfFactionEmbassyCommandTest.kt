package com.dansplugins.factionsystem.command.faction.embassy

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.claim.MfEmbassy
import com.dansplugins.factionsystem.claim.MfEmbassyService
import com.dansplugins.factionsystem.claim.MfEmbassyService.ChunkPos
import com.dansplugins.factionsystem.claim.MfEmbassyStatus
import com.dansplugins.factionsystem.claim.MfClaimService
import com.dansplugins.factionsystem.claim.MfClaimedChunk
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.faction.MfFactionService
import com.dansplugins.factionsystem.faction.permission.MfFactionPermission
import com.dansplugins.factionsystem.faction.permission.MfFactionPermissions
import com.dansplugins.factionsystem.faction.role.MfFactionRole
import com.dansplugins.factionsystem.player.MfPlayerId
import com.dansplugins.factionsystem.service.Services
import dev.forkhandles.result4k.Success
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Chest
import org.bukkit.command.Command
import org.bukkit.entity.Player
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.ArgumentMatchers.anyInt
import java.util.UUID

class MfFactionEmbassyCommandTest {
    private val playerUuid = UUID.randomUUID()
    private val playerId = MfPlayerId(playerUuid.toString())
    private val hostId = MfFactionId.generate()
    private val guestId = MfFactionId.generate()
    private val worldId = UUID.randomUUID()
    private val unclaim = MfFactionPermission("UNCLAIM", "Unclaim", false)
    private val claim = MfFactionPermission("CLAIM", "Claim", false)
    private lateinit var plugin: MedievalFactions
    private lateinit var player: Player
    private lateinit var command: Command
    private lateinit var world: World
    private lateinit var chunk: Chunk
    private lateinit var factions: MfFactionService
    private lateinit var embassies: MfEmbassyService
    private lateinit var claims: MfClaimService
    private lateinit var host: MfFaction
    private lateinit var guest: MfFaction
    private lateinit var role: MfFactionRole
    private lateinit var uut: MfFactionEmbassyCommand

    @BeforeEach
    fun setUp() {
        plugin = mock(MedievalFactions::class.java)
        val services = mock(Services::class.java)
        factions = mock(MfFactionService::class.java)
        embassies = mock(MfEmbassyService::class.java)
        claims = mock(MfClaimService::class.java)
        `when`(plugin.services).thenReturn(services)
        `when`(services.factionService).thenReturn(factions)
        `when`(services.embassyService).thenReturn(embassies)
        `when`(services.claimService).thenReturn(claims)
        `when`(claims.getClaim(worldId, 3, -2)).thenReturn(MfClaimedChunk(worldId, 3, -2, hostId))
        `when`(embassies.chunkLimit(hostId)).thenReturn(4)
        `when`(embassies.chunkLimit(guestId)).thenReturn(4)
        val permissions = mock(MfFactionPermissions::class.java)
        `when`(plugin.factionPermissions).thenReturn(permissions)
        `when`(permissions.unclaim).thenReturn(unclaim)
        `when`(permissions.claim).thenReturn(claim)
        role = mock(MfFactionRole::class.java)
        host = faction(hostId, "Peer Host")
        guest = faction(guestId, "Peer Guest")
        `when`(factions.getFaction(hostId)).thenReturn(host)
        `when`(factions.getFaction(guestId)).thenReturn(guest)
        `when`(factions.getFaction("Peer Guest")).thenReturn(guest)
        player = mock(Player::class.java)
        command = mock(Command::class.java)
        world = mock(World::class.java)
        chunk = mock(Chunk::class.java)
        `when`(world.uid).thenReturn(worldId)
        `when`(world.players).thenReturn(listOf(player))
        `when`(chunk.world).thenReturn(world)
        `when`(chunk.x).thenReturn(3)
        `when`(chunk.z).thenReturn(-2)
        `when`(chunk.tileEntities).thenReturn(emptyArray())
        `when`(chunk.entities).thenReturn(emptyArray())
        val location = mock(Location::class.java)
        `when`(location.chunk).thenReturn(chunk)
        `when`(player.location).thenReturn(location)
        `when`(player.uniqueId).thenReturn(playerUuid)
        `when`(player.hasPermission("mf.embassy")).thenReturn(true)
        uut = MfFactionEmbassyCommand(plugin)
    }

    private fun faction(id: MfFactionId, name: String): MfFaction = mock(MfFaction::class.java).also {
        `when`(it.id).thenReturn(id)
        `when`(it.displayName).thenReturn(name)
        `when`(it.getRole(playerId)).thenReturn(role)
    }

    private fun row(status: MfEmbassyStatus) = MfEmbassy(worldId, 3, -2, hostId, guestId,
        status, 1_000L, 1_000L, if (status == MfEmbassyStatus.ACTIVE) null else 10_000L)

    @Test
    fun peerOfficerCanOfferUsingRoleCapability() {
        `when`(factions.getFaction(playerId)).thenReturn(host)
        `when`(role.hasPermission(host, unclaim)).thenReturn(true)
        `when`(embassies.offerArea(hostId, guestId, worldId, listOf(ChunkPos(3, -2))))
            .thenReturn(Success(listOf(row(MfEmbassyStatus.OFFERED))))

        uut.onCommand(player, command, "f", arrayOf("offer", "Peer", "Guest"))

        verify(embassies).offerArea(hostId, guestId, worldId, listOf(ChunkPos(3, -2)))
    }

    @Test
    fun hostCanRevokeFromOutsideThePrivatePlot() {
        `when`(factions.getFaction(playerId)).thenReturn(host)
        `when`(role.hasPermission(host, unclaim)).thenReturn(true)
        `when`(chunk.x).thenReturn(40)
        `when`(embassies.getAt(worldId, 3, -2)).thenReturn(row(MfEmbassyStatus.ACTIVE))
        `when`(embassies.revoke(hostId, worldId, 3, -2)).thenReturn(Success(row(MfEmbassyStatus.CLEARING)))

        uut.onCommand(player, command, "f", arrayOf("revoke", "$worldId:3,-2"))

        verify(embassies).revoke(hostId, worldId, 3, -2)
    }

    @Test
    fun physicalStoragePreventsOfferingHostPropertyToGuest() {
        `when`(factions.getFaction(playerId)).thenReturn(host)
        `when`(role.hasPermission(host, unclaim)).thenReturn(true)
        val chest = mock(Chest::class.java)
        `when`(chunk.tileEntities).thenReturn(arrayOf(chest))

        uut.onCommand(player, command, "f", arrayOf("offer", "Peer", "Guest"))

        verify(embassies, never()).offerArea(hostId, guestId, worldId, listOf(ChunkPos(3, -2)))
    }

    @Test
    fun acceptanceRequiresNonGuestOccupantsToLeave() {
        `when`(factions.getFaction(playerId)).thenReturn(guest)
        `when`(role.hasPermission(guest, claim)).thenReturn(true)
        `when`(embassies.getAt(worldId, 3, -2)).thenReturn(row(MfEmbassyStatus.OFFERED))
        `when`(embassies.pendingOfferAt(worldId, 3, -2)).thenReturn(listOf(row(MfEmbassyStatus.OFFERED)))
        val occupant = mock(Player::class.java)
        val occupantUuid = UUID.randomUUID()
        val occupantLocation = player.location
        `when`(occupant.uniqueId).thenReturn(occupantUuid)
        `when`(occupant.location).thenReturn(occupantLocation)
        `when`(factions.getFaction(MfPlayerId(occupantUuid.toString()))).thenReturn(host)
        `when`(world.players).thenReturn(listOf(player, occupant))

        uut.onCommand(player, command, "f", arrayOf("accept"))

        verify(embassies, never()).acceptArea(guestId, worldId, 3, -2)
    }

    private fun loadNeighbor(x: Int, z: Int): Chunk = mock(Chunk::class.java).also {
        `when`(it.x).thenReturn(x)
        `when`(it.z).thenReturn(z)
        `when`(it.world).thenReturn(world)
        `when`(it.tileEntities).thenReturn(emptyArray())
        `when`(it.entities).thenReturn(emptyArray())
        `when`(world.isChunkLoaded(x, z)).thenReturn(true)
        `when`(world.getChunkAt(x, z)).thenReturn(it)
        `when`(claims.getClaim(worldId, x, z)).thenReturn(MfClaimedChunk(worldId, x, z, hostId))
    }

    @Test
    fun fourChunksAreOfferedAsOneConnectedArea() {
        `when`(factions.getFaction(playerId)).thenReturn(host)
        `when`(role.hasPermission(host, unclaim)).thenReturn(true)
        val positions = listOf(ChunkPos(3, -2), ChunkPos(4, -2), ChunkPos(3, -1), ChunkPos(4, -1))
        positions.drop(1).forEach { loadNeighbor(it.x, it.z) }
        val rows = positions.map { row(MfEmbassyStatus.OFFERED).copy(chunkX = it.x, chunkZ = it.z) }
        `when`(embassies.offerArea(hostId, guestId, worldId, positions)).thenReturn(Success(rows))

        uut.onCommand(player, command, "f", arrayOf("offer", "Peer", "Guest", "4"))

        verify(embassies).offerArea(hostId, guestId, worldId, positions)
    }

    @Test
    fun inventoryInAnotherOfferedChunkRefusesTheWholeArea() {
        `when`(factions.getFaction(playerId)).thenReturn(host)
        `when`(role.hasPermission(host, unclaim)).thenReturn(true)
        val neighbor = loadNeighbor(3, -1)
        loadNeighbor(4, -2)
        loadNeighbor(4, -1)
        val chest = mock(Chest::class.java)
        `when`(neighbor.tileEntities).thenReturn(arrayOf(chest))

        uut.onCommand(player, command, "f", arrayOf("offer", "Peer", "Guest", "4"))

        verify(embassies, never()).offerArea(hostId, guestId, worldId,
            listOf(ChunkPos(3, -2), ChunkPos(4, -2), ChunkPos(3, -1), ChunkPos(4, -1)))
    }

    @Test
    fun overCapacityCountDoesNotLoadChunks() {
        `when`(factions.getFaction(playerId)).thenReturn(host)
        `when`(role.hasPermission(host, unclaim)).thenReturn(true)

        uut.onCommand(player, command, "f", arrayOf("offer", "Peer", "Guest", "1000000"))

        verify(world, never()).getChunkAt(anyInt(), anyInt())
        assertNoOffer()
    }

    @Test
    fun insufficientLoadedClaimsRefuseWithoutForcingTerrainLoad() {
        `when`(factions.getFaction(playerId)).thenReturn(host)
        `when`(role.hasPermission(host, unclaim)).thenReturn(true)

        `when`(claims.getClaim(worldId, 4, -2)).thenReturn(MfClaimedChunk(worldId, 4, -2, hostId))
        uut.onCommand(player, command, "f", arrayOf("offer", "Peer", "Guest", "4"))

        verify(world, never()).getChunkAt(anyInt(), anyInt())
        assertNoOffer()
    }

    @Test
    fun exactCountCanFollowAnIrregularClaimIntoNegativeCoordinates() {
        `when`(factions.getFaction(playerId)).thenReturn(host)
        `when`(role.hasPermission(host, unclaim)).thenReturn(true)
        val positions = listOf(ChunkPos(3, -2), ChunkPos(2, -2), ChunkPos(2, -3))
        positions.drop(1).forEach { loadNeighbor(it.x, it.z) }
        loadNeighbor(2, -4)
        val rows = positions.map { row(MfEmbassyStatus.OFFERED).copy(chunkX = it.x, chunkZ = it.z) }
        `when`(embassies.offerArea(hostId, guestId, worldId, positions)).thenReturn(Success(rows))

        uut.onCommand(player, command, "f", arrayOf("offer", "Peer", "Guest", "3"))

        verify(embassies).offerArea(hostId, guestId, worldId, positions)
        verify(world, never()).getChunkAt(2, -4)
    }

    @Test
    fun foreignClaimCannotConnectToAnIsolatedHostClaim() {
        `when`(factions.getFaction(playerId)).thenReturn(host)
        `when`(role.hasPermission(host, unclaim)).thenReturn(true)
        loadNeighbor(4, -2)
        loadNeighbor(5, -2)
        `when`(claims.getClaim(worldId, 4, -2)).thenReturn(MfClaimedChunk(worldId, 4, -2, guestId))

        uut.onCommand(player, command, "f", arrayOf("offer", "Peer", "Guest", "2"))

        assertNoOffer()
        verify(world, never()).getChunkAt(anyInt(), anyInt())
    }

    @Test
    fun diagonalContactDoesNotConnectClaims() {
        `when`(factions.getFaction(playerId)).thenReturn(host)
        `when`(role.hasPermission(host, unclaim)).thenReturn(true)
        loadNeighbor(4, -1)

        uut.onCommand(player, command, "f", arrayOf("offer", "Peer", "Guest", "2"))

        assertNoOffer()
    }

    @Test
    fun reservedEmbassyCannotServeAsABridgeToAnotherClaim() {
        `when`(factions.getFaction(playerId)).thenReturn(host)
        `when`(role.hasPermission(host, unclaim)).thenReturn(true)
        loadNeighbor(4, -2)
        loadNeighbor(5, -2)
        `when`(embassies.getAt(worldId, 4, -2)).thenReturn(row(MfEmbassyStatus.OFFERED).copy(chunkX = 4))

        uut.onCommand(player, command, "f", arrayOf("offer", "Peer", "Guest", "2"))

        assertNoOffer()
    }

    @ParameterizedTest
    @ValueSource(strings = ["2x2", "0", "-1", "1.5", "2147483648"])
    fun invalidCountsNeverLoadOrOfferChunks(count: String) {
        `when`(factions.getFaction(playerId)).thenReturn(host)
        `when`(role.hasPermission(host, unclaim)).thenReturn(true)

        uut.onCommand(player, command, "f", arrayOf("offer", "Peer", "Guest", count))

        assertNoOffer()
        verify(world, never()).getChunkAt(anyInt(), anyInt())
    }

    @Test
    fun numericEndingRealmNameStillDefaultsToOneChunk() {
        `when`(factions.getFaction(playerId)).thenReturn(host)
        `when`(role.hasPermission(host, unclaim)).thenReturn(true)
        `when`(factions.getFaction("Peer Guest 4")).thenReturn(guest)
        val positions = listOf(ChunkPos(3, -2))
        `when`(embassies.offerArea(hostId, guestId, worldId, positions))
            .thenReturn(Success(listOf(row(MfEmbassyStatus.OFFERED))))

        uut.onCommand(player, command, "f", arrayOf("offer", "Peer", "Guest", "4"))

        verify(embassies).offerArea(hostId, guestId, worldId, positions)
    }

    private fun assertNoOffer() {
        assertTrue(mockingDetails(embassies).invocations.none { it.method.name.startsWith("offerArea") })
    }

    @Test
    fun acceptanceChecksOccupantsAcrossAllOfferedChunks() {
        `when`(factions.getFaction(playerId)).thenReturn(guest)
        `when`(role.hasPermission(guest, claim)).thenReturn(true)
        val rows = listOf(row(MfEmbassyStatus.OFFERED), row(MfEmbassyStatus.OFFERED).copy(chunkX = 4))
        `when`(embassies.getAt(worldId, 3, -2)).thenReturn(rows.first())
        `when`(embassies.pendingOfferAt(worldId, 3, -2)).thenReturn(rows)
        val neighbor = loadNeighbor(4, -2)
        val occupant = mock(Player::class.java)
        val occupantLocation = mock(Location::class.java)
        val occupantUuid = UUID.randomUUID()
        `when`(occupantLocation.chunk).thenReturn(neighbor)
        `when`(occupant.uniqueId).thenReturn(occupantUuid)
        `when`(occupant.location).thenReturn(occupantLocation)
        `when`(factions.getFaction(MfPlayerId(occupantUuid.toString()))).thenReturn(host)
        `when`(world.players).thenReturn(listOf(player, occupant))

        uut.onCommand(player, command, "f", arrayOf("accept"))

        verify(embassies, never()).acceptArea(guestId, worldId, 3, -2)
    }

    @Test
    fun acceptanceActivatesAllOfferedChunksTogether() {
        `when`(factions.getFaction(playerId)).thenReturn(guest)
        `when`(role.hasPermission(guest, claim)).thenReturn(true)
        val rows = listOf(row(MfEmbassyStatus.OFFERED), row(MfEmbassyStatus.OFFERED).copy(chunkX = 4))
        `when`(embassies.getAt(worldId, 3, -2)).thenReturn(rows.first())
        `when`(embassies.pendingOfferAt(worldId, 3, -2)).thenReturn(rows)
        loadNeighbor(4, -2)
        val active = rows.map { it.copy(status = MfEmbassyStatus.ACTIVE, deadlineAt = null) }
        `when`(embassies.acceptArea(guestId, worldId, 3, -2)).thenReturn(Success(active))

        uut.onCommand(player, command, "f", arrayOf("accept"))

        verify(embassies).acceptArea(guestId, worldId, 3, -2)
    }

    @Test
    fun decliningAnExpansionUsesOfferCancellationRatherThanWholeAgreementRelease() {
        `when`(factions.getFaction(playerId)).thenReturn(guest)
        `when`(role.hasPermission(guest, claim)).thenReturn(true)
        `when`(embassies.getAt(worldId, 3, -2)).thenReturn(row(MfEmbassyStatus.OFFERED))
        `when`(embassies.decline(guestId, worldId, 3, -2)).thenReturn(Success(Unit))

        uut.onCommand(player, command, "f", arrayOf("decline"))

        verify(embassies).decline(guestId, worldId, 3, -2)
        verify(embassies, never()).release(guestId, worldId, 3, -2)
    }
}
