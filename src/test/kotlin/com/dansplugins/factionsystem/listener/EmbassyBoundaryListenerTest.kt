package com.dansplugins.factionsystem.listener

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.ClaimAction
import com.dansplugins.factionsystem.claim.EmbassyAccessDecision
import com.dansplugins.factionsystem.claim.MfClaimService
import com.dansplugins.factionsystem.claim.MfClaimedChunk
import com.dansplugins.factionsystem.claim.MfEmbassyService
import com.dansplugins.factionsystem.player.MfPlayer
import com.dansplugins.factionsystem.player.MfPlayerId
import com.dansplugins.factionsystem.player.MfPlayerService
import com.dansplugins.factionsystem.service.Services
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockState
import org.bukkit.block.data.Directional
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockDispenseEvent
import org.bukkit.event.block.BlockFertilizeEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntitySpawnEvent
import org.bukkit.event.world.StructureGrowEvent
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.UUID

class EmbassyBoundaryListenerTest {
    private lateinit var world: World
    private lateinit var embassies: MfEmbassyService
    private lateinit var listener: EmbassyBoundaryListener
    private lateinit var claimService: MfClaimService
    private lateinit var playerService: MfPlayerService

    @BeforeEach
    fun setUp() {
        val plugin = mock(MedievalFactions::class.java)
        val services = mock(Services::class.java)
        `when`(plugin.services).thenReturn(services)
        embassies = defaultEmbassyService(services)
        claimService = mock(MfClaimService::class.java)
        playerService = mock(MfPlayerService::class.java)
        `when`(services.claimService).thenReturn(claimService)
        `when`(services.playerService).thenReturn(playerService)
        world = mock(World::class.java)
        `when`(world.uid).thenReturn(UUID.randomUUID())
        listener = EmbassyBoundaryListener(plugin)
    }

    private fun block(x: Int, z: Int = 0): Block = mock(Block::class.java).also {
        `when`(it.world).thenReturn(world)
        `when`(it.x).thenReturn(x)
        `when`(it.z).thenReturn(z)
    }

    private fun active(chunkX: Int) {
        `when`(embassies.isParcelProtectionActive(world.uid, chunkX, 0)).thenReturn(true)
    }

    @Test
    fun fluidCannotCrossIntoEmbassyButCanFlowWithinIt() {
        active(1)
        val crossing = mock(BlockFromToEvent::class.java)
        val outside = block(15)
        val inside = block(16)
        `when`(crossing.block).thenReturn(outside)
        `when`(crossing.toBlock).thenReturn(inside)
        listener.onFlow(crossing)
        verify(crossing).isCancelled = true

        val interior = mock(BlockFromToEvent::class.java)
        val nextInside = block(17)
        `when`(interior.block).thenReturn(inside)
        `when`(interior.toBlock).thenReturn(nextInside)
        listener.onFlow(interior)
        verify(interior, never()).isCancelled = true
    }

    @Test
    fun fluidCrossesInternalAreaCellButStopsAtDifferentAgreement() {
        active(1)
        active(2)
        `when`(embassies.sameProtectedArea(world.uid, 1, 0, 2, 0)).thenReturn(true)
        val source = block(31)
        val destination = block(32)
        val interior = mock(BlockFromToEvent::class.java)
        `when`(interior.block).thenReturn(source)
        `when`(interior.toBlock).thenReturn(destination)

        listener.onFlow(interior)

        verify(interior, never()).isCancelled = true
        `when`(embassies.sameProtectedArea(world.uid, 1, 0, 2, 0)).thenReturn(false)
        val separate = mock(BlockFromToEvent::class.java)
        `when`(separate.block).thenReturn(source)
        `when`(separate.toBlock).thenReturn(destination)
        listener.onFlow(separate)
        verify(separate).isCancelled = true
    }

    @Test
    fun outsideTreeOrFertilizerBatchCannotBuildIntoEmbassy() {
        active(1)
        val origin = block(15)
        val changed = mock(BlockState::class.java)
        val inside = block(16)
        `when`(changed.block).thenReturn(inside)
        val grow = mock(StructureGrowEvent::class.java)
        `when`(grow.location).thenReturn(Location(world, 15.5, 70.0, 0.5))
        `when`(grow.blocks).thenReturn(mutableListOf(changed))
        val fertilize = mock(BlockFertilizeEvent::class.java)
        `when`(fertilize.block).thenReturn(origin)
        `when`(fertilize.blocks).thenReturn(mutableListOf(changed))

        listener.onStructureGrow(grow)
        listener.onFertilize(fertilize)

        verify(grow).isCancelled = true
        verify(fertilize).isCancelled = true
    }

    @Test
    fun growthBatchMaySpanInternalConnectedAreaCells() {
        active(1)
        active(2)
        `when`(embassies.sameProtectedArea(world.uid, 1, 0, 2, 0)).thenReturn(true)
        val origin = block(31)
        val changed = mock(BlockState::class.java)
        val inside = block(32)
        `when`(changed.block).thenReturn(inside)
        val grow = mock(StructureGrowEvent::class.java)
        `when`(grow.location).thenReturn(Location(world, 31.5, 70.0, 0.5))
        `when`(grow.blocks).thenReturn(mutableListOf(changed))
        val fertilize = mock(BlockFertilizeEvent::class.java)
        `when`(fertilize.block).thenReturn(origin)
        `when`(fertilize.blocks).thenReturn(mutableListOf(changed))

        listener.onStructureGrow(grow)
        listener.onFertilize(fertilize)

        verify(grow, never()).isCancelled = true
        verify(fertilize, never()).isCancelled = true
    }

    @Test
    fun pistonPayloadCannotCrossEmbassyEdge() {
        active(1)
        val event = mock(BlockPistonExtendEvent::class.java)
        val piston = block(14)
        val payload = block(15)
        `when`(event.block).thenReturn(piston)
        `when`(event.direction).thenReturn(BlockFace.EAST)
        `when`(event.blocks).thenReturn(listOf(payload))
        listener.onExtend(event)
        verify(event).isCancelled = true
    }

    @Test
    fun dispenserCannotDispenseAcrossEmbassyEdge() {
        active(1)
        val source = block(15)
        val facing = mock(Directional::class.java)
        `when`(facing.facing).thenReturn(BlockFace.EAST)
        `when`(source.blockData).thenReturn(facing)
        val event = mock(BlockDispenseEvent::class.java)
        `when`(event.block).thenReturn(source)
        listener.onDispense(event)
        verify(event).isCancelled = true
    }

    @Test
    fun guestDispenserCannotBeRemotePoweredByHost() {
        active(1)
        val event = mock(BlockDispenseEvent::class.java)
        val source = block(20)
        `when`(event.block).thenReturn(source)

        listener.onDispense(event)

        verify(event).isCancelled = true
    }

    @Test
    fun guestPistonCannotBeRemotePoweredByHost() {
        active(1)
        val event = mock(BlockPistonExtendEvent::class.java)
        val piston = block(20)
        `when`(event.block).thenReturn(piston)
        `when`(event.direction).thenReturn(BlockFace.EAST)

        listener.onExtend(event)

        verify(event).isCancelled = true
    }

    @Test
    fun outsideFlintClickCannotIgniteBlockInsideEmbassy() {
        active(1)
        val target = block(16)
        val chunk = mock(Chunk::class.java)
        val claim = mock(MfClaimedChunk::class.java)
        val player = mock(Player::class.java)
        val playerId = MfPlayerId(UUID.randomUUID().toString())
        `when`(target.chunk).thenReturn(chunk)
        `when`(playerService.getPlayer(player)).thenReturn(MfPlayer(playerId))
        `when`(claimService.getClaim(chunk)).thenReturn(claim)
        `when`(embassies.access(playerId, claim, ClaimAction.INTERACT)).thenReturn(EmbassyAccessDecision.DENY)
        val event = mock(BlockIgniteEvent::class.java)
        `when`(event.block).thenReturn(target)
        `when`(event.player).thenReturn(player)

        listener.onIgnite(event)

        verify(event).isCancelled = true
    }

    @Test
    fun fallingBlockCarriesItsOriginAcrossTheBoundary() {
        active(1)
        val falling = mock(FallingBlock::class.java)
        val origin = Location(world, 15.5, 70.0, 0.5)
        `when`(falling.location).thenReturn(origin)
        val spawn = mock(EntitySpawnEvent::class.java)
        `when`(spawn.entity).thenReturn(falling)
        listener.onFallingBlockSpawn(spawn)

        val landing = mock(EntityChangeBlockEvent::class.java)
        `when`(landing.entity).thenReturn(falling)
        val destination = block(16)
        `when`(landing.block).thenReturn(destination)
        val material = mock(Material::class.java)
        `when`(material.isAir).thenReturn(false)
        `when`(landing.to).thenReturn(material)
        listener.onFallingBlockChange(landing)
        verify(landing).isCancelled = true
    }
}
