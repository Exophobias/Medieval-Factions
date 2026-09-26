package com.dansplugins.factionsystem.listener

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.claim.MfClaimService
import com.dansplugins.factionsystem.claim.MfClaimedChunk
import com.dansplugins.factionsystem.faction.AdminFactionProtection
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.faction.MfFactionService
import com.dansplugins.factionsystem.faction.flag.MfFlagValues
import com.dansplugins.factionsystem.gate.MfGateService
import com.dansplugins.factionsystem.service.Services
import com.dansplugins.factionsystem.utils.MfServerVersion
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.event.block.BlockSpreadEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.UUID

class AdminFactionAreaProtectionTest {
    private lateinit var plugin: MedievalFactions
    private lateinit var claimService: MfClaimService
    private lateinit var factionService: MfFactionService
    private lateinit var protectedBlock: Block
    private lateinit var ordinaryBlock: Block
    private lateinit var faction: MfFaction
    private lateinit var savedVersionProvider: () -> String

    @BeforeEach
    fun setUp() {
        savedVersionProvider = MfServerVersion.versionProvider
        MfServerVersion.versionProvider = { "1.17-R0.1-SNAPSHOT" }
        MfServerVersion.resetForTesting()
        plugin = mock(MedievalFactions::class.java)
        val services = mock(Services::class.java)
        claimService = mock(MfClaimService::class.java)
        factionService = mock(MfFactionService::class.java)
        `when`(plugin.services).thenReturn(services)
        `when`(services.claimService).thenReturn(claimService)
        `when`(services.factionService).thenReturn(factionService)
        `when`(services.gateService).thenReturn(mock(MfGateService::class.java))
        val protectedChunk = mock(Chunk::class.java)
        val ordinaryChunk = mock(Chunk::class.java)
        val factionId = MfFactionId.generate()
        `when`(claimService.getClaim(protectedChunk)).thenReturn(MfClaimedChunk(UUID.randomUUID(), 0, 0, factionId))
        faction = mock(MfFaction::class.java)
        `when`(faction.adminLeaderless).thenReturn(true)
        `when`(factionService.getFaction(factionId)).thenReturn(faction)
        protectedBlock = block(protectedChunk)
        ordinaryBlock = block(ordinaryChunk)
    }

    @AfterEach
    fun tearDown() {
        MfServerVersion.versionProvider = savedVersionProvider
        MfServerVersion.resetForTesting()
    }

    private fun block(chunk: Chunk): Block {
        val block = mock(Block::class.java)
        val world = mock(World::class.java)
        val location = mock(Location::class.java)
        `when`(world.uid).thenReturn(UUID.randomUUID())
        `when`(location.chunk).thenReturn(chunk)
        `when`(block.world).thenReturn(world)
        `when`(block.location).thenReturn(location)
        return block
    }

    private fun flags(vararg values: Pair<String, Boolean>) {
        `when`(faction.flags).thenReturn(MfFlagValues(plugin, mapOf(*values)))
    }

    @Test
    fun explosionProtectionRemovesOnlyAdminClaimBlocksFromBothExplosionEvents() {
        flags(AdminFactionProtection.EXPLOSIONS.storageKey to false)
        val entityBlocks = mutableListOf(protectedBlock, ordinaryBlock)
        val entityEvent = mock(EntityExplodeEvent::class.java)
        val entity = mock(Entity::class.java)
        val type = mock(EntityType::class.java)
        `when`(type.name).thenReturn("CREEPER")
        `when`(entity.type).thenReturn(type)
        `when`(entityEvent.entity).thenReturn(entity)
        `when`(entityEvent.blockList()).thenReturn(entityBlocks)

        EntityExplodeListener(plugin).onEntityExplode(entityEvent)
        assertEquals(listOf(ordinaryBlock), entityBlocks)

        val blockBlocks = mutableListOf(protectedBlock, ordinaryBlock)
        val blockEvent = mock(BlockExplodeEvent::class.java)
        `when`(blockEvent.blockList()).thenReturn(blockBlocks)

        BlockExplodeListener(plugin).onBlockExplode(blockEvent)
        assertEquals(listOf(ordinaryBlock), blockBlocks)
    }

    @Test
    fun explosionProtectionStopsBlastDamageToEntitiesInsideAdminClaim() {
        flags(AdminFactionProtection.EXPLOSIONS.storageKey to false)
        val victim = mock(LivingEntity::class.java)
        val location = protectedBlock.location
        `when`(victim.location).thenReturn(location)
        val event = mock(EntityDamageEvent::class.java)
        `when`(event.entity).thenReturn(victim)
        `when`(event.cause).thenReturn(EntityDamageEvent.DamageCause.ENTITY_EXPLOSION)

        EntityDamageListener(plugin).onEntityDamage(event)

        verify(event).isCancelled = true
    }

    @Test
    fun fireProtectionStopsSpreadIgnitionAndBurnButAllowsOtherGrowth() {
        flags(AdminFactionProtection.FIRE_SPREAD.storageKey to false)
        val source = mock(Block::class.java)
        `when`(source.type).thenReturn(Material.FIRE)
        val newState = mock(BlockState::class.java)
        `when`(newState.type).thenReturn(Material.FIRE)
        val spread = mock(BlockSpreadEvent::class.java)
        `when`(spread.block).thenReturn(protectedBlock)
        `when`(spread.source).thenReturn(source)
        `when`(spread.newState).thenReturn(newState)
        AdminFactionFireListener(plugin).onBlockSpread(spread)
        verify(spread).isCancelled = true

        val ignite = mock(BlockIgniteEvent::class.java)
        `when`(ignite.block).thenReturn(protectedBlock)
        `when`(ignite.cause).thenReturn(BlockIgniteEvent.IgniteCause.SPREAD)
        AdminFactionFireListener(plugin).onBlockIgnite(ignite)
        verify(ignite).isCancelled = true

        val burn = mock(BlockBurnEvent::class.java)
        `when`(burn.block).thenReturn(protectedBlock)
        BlockBurnListener(plugin).onBlockBurn(burn)
        verify(burn).isCancelled = true

        val grassSource = mock(Block::class.java)
        `when`(grassSource.type).thenReturn(Material.GRASS_BLOCK)
        val grassState = mock(BlockState::class.java)
        `when`(grassState.type).thenReturn(Material.GRASS_BLOCK)
        val grassSpread = mock(BlockSpreadEvent::class.java)
        `when`(grassSpread.source).thenReturn(grassSource)
        `when`(grassSpread.newState).thenReturn(grassState)
        AdminFactionFireListener(plugin).onBlockSpread(grassSpread)
        verify(grassSpread, never()).isCancelled = true
    }
}
