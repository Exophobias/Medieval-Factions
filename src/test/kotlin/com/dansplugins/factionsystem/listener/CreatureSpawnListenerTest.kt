package com.dansplugins.factionsystem.listener

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.claim.MfClaimService
import com.dansplugins.factionsystem.claim.MfClaimedChunk
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.faction.MfFactionService
import com.dansplugins.factionsystem.service.Services
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Monster
import org.bukkit.event.entity.CreatureSpawnEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.MockedStatic
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.UUID

class CreatureSpawnListenerTest {

    private lateinit var plugin: MedievalFactions
    private lateinit var config: FileConfiguration
    private lateinit var services: Services
    private lateinit var claimService: MfClaimService
    private lateinit var factionService: MfFactionService
    private lateinit var uut: CreatureSpawnListener
    private lateinit var mockedBukkit: MockedStatic<Bukkit>

    @BeforeEach
    fun setUp() {
        mockedBukkit = mockStatic(Bukkit::class.java)
        mockedBukkit.`when`<String> { Bukkit.getBukkitVersion() }.thenReturn("1.17-R0.1-SNAPSHOT")

        plugin = mock(MedievalFactions::class.java)
        config = mock(FileConfiguration::class.java)
        services = mock(Services::class.java)
        claimService = mock(MfClaimService::class.java)
        factionService = mock(MfFactionService::class.java)

        `when`(plugin.config).thenReturn(config)
        `when`(plugin.services).thenReturn(services)
        `when`(services.claimService).thenReturn(claimService)
        `when`(services.factionService).thenReturn(factionService)
        `when`(config.getBoolean("factions.mobsSpawnInFactionTerritory")).thenReturn(false)
        `when`(config.getStringList("factions.allowedMobSpawnReasons")).thenReturn(emptyList())

        uut = CreatureSpawnListener(plugin)
    }

    @AfterEach
    fun tearDown() {
        mockedBukkit.close()
    }

    @Test
    fun onCreatureSpawn_ClaimedNonHostileMob_ShouldNotCancelEvent() {
        val entity = mock(LivingEntity::class.java)
        val event = createEvent(entity)

        uut.onCreatureSpawn(event)

        verify(event, never()).isCancelled = true
    }

    @Test
    fun onCreatureSpawn_ClaimedHostileMobWithDisallowedReason_ShouldCancelEvent() {
        val entity = mock(Monster::class.java)
        val event = createEvent(entity)

        uut.onCreatureSpawn(event)

        verify(event).isCancelled = true
    }

    private fun createEvent(entity: LivingEntity): CreatureSpawnEvent {
        val event = mock(CreatureSpawnEvent::class.java)
        val location = mock(Location::class.java)
        val chunk = mock(Chunk::class.java)
        `when`(event.entity).thenReturn(entity)
        `when`(event.location).thenReturn(location)
        `when`(location.chunk).thenReturn(chunk)
        `when`(event.spawnReason).thenReturn(CreatureSpawnEvent.SpawnReason.NATURAL)
        val factionId = MfFactionId.generate()
        `when`(claimService.getClaim(chunk)).thenReturn(MfClaimedChunk(UUID.randomUUID(), 0, 0, factionId))
        `when`(factionService.getFaction(factionId)).thenReturn(mock(MfFaction::class.java))
        return event
    }
}
