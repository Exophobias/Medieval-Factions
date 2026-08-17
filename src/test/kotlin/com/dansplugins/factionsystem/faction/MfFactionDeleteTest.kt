package com.dansplugins.factionsystem.faction

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.claim.MfClaimService
import com.dansplugins.factionsystem.event.faction.FactionDeletedEvent
import com.dansplugins.factionsystem.gate.MfGateService
import com.dansplugins.factionsystem.map.MapService
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipService
import com.dansplugins.factionsystem.service.Services
import dev.forkhandles.result4k.Failure
import org.bukkit.Server
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.event.Event
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.scheduler.BukkitTask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.logging.Logger

class MfFactionDeleteTest {

    private lateinit var plugin: MedievalFactions
    private lateinit var repository: FailingRepository
    private lateinit var service: MfFactionService
    private lateinit var claims: MfClaimService
    private lateinit var gates: MfGateService
    private lateinit var relationships: MfFactionRelationshipService
    private val events = mutableListOf<Event>()
    private val id = MfFactionId("doomed")

    @BeforeEach
    fun setUp() {
        plugin = mock(MedievalFactions::class.java)
        `when`(plugin.logger).thenReturn(Logger.getLogger(javaClass.name))
        val config = mock(FileConfiguration::class.java)
        `when`(plugin.config).thenReturn(config)
        `when`(config.getBoolean("factions.allowNeutrality")).thenReturn(true)
        val server = mock(Server::class.java)
        `when`(plugin.server).thenReturn(server)
        val manager = mock(PluginManager::class.java)
        `when`(server.pluginManager).thenReturn(manager)
        doAnswer { invocation -> events.add(invocation.getArgument(0)); null }
            .`when`(manager).callEvent(any(Event::class.java))
        val scheduler = mock(BukkitScheduler::class.java)
        `when`(server.scheduler).thenReturn(scheduler)
        `when`(scheduler.runTask(any(Plugin::class.java), any(Runnable::class.java)))
            .thenAnswer { invocation ->
                invocation.getArgument<Runnable>(1).run()
                mock(BukkitTask::class.java)
            }

        val faction = mock(MfFaction::class.java)
        `when`(faction.id).thenReturn(id)
        repository = FailingRepository(faction)
        service = MfFactionService(plugin, repository)
        claims = mock(MfClaimService::class.java)
        gates = mock(MfGateService::class.java)
        relationships = mock(MfFactionRelationshipService::class.java)
        val services = mock(Services::class.java)
        `when`(services.claimService).thenReturn(claims)
        `when`(services.gateService).thenReturn(gates)
        `when`(services.factionRelationshipService).thenReturn(relationships)
        `when`(services.mapService).thenReturn(mock(MapService::class.java))
        `when`(plugin.services).thenReturn(services)
        events.clear()
    }

    @Test
    fun finalRepositoryFailureLeavesFactionAndEveryAssetCacheUntouched() {
        repository.failDelete = true

        val result = service.delete(id)

        assertTrue(result is Failure)
        assertNotNull(service.getFaction(id))
        verify(claims, never()).evictAllClaims(id)
        verify(gates, never()).evictAllGates(id)
        verify(relationships, never()).evictForDeletedFaction(id)
        assertTrue(events.none { it is FactionDeletedEvent })
    }

    @Test
    fun committedDeleteEvictsCascadesAndPublishesExactlyOnce() {
        service.delete(id)
        val duplicate = service.delete(id)

        assertNull(service.getFaction(id))
        verify(claims).evictAllClaims(id)
        verify(gates).evictAllGates(id)
        verify(relationships).evictForDeletedFaction(id)
        assertEquals(1, events.count { it is FactionDeletedEvent })
        assertTrue(duplicate is Failure)
    }

    private class FailingRepository(faction: MfFaction) : MfFactionRepository {
        private val rows = linkedMapOf(faction.id to faction)
        var failDelete = false

        override fun getFaction(id: MfFactionId) = rows[id]
        override fun getFaction(name: String) = rows.values.firstOrNull { it.name == name }
        override fun getFaction(playerId: com.dansplugins.factionsystem.player.MfPlayerId) = null
        override fun getFactions() = rows.values.toList()
        override fun upsert(faction: MfFaction) = faction.also { rows[it.id] = it }
        override fun delete(factionId: MfFactionId) {
            if (failDelete) error("injected final delete failure")
            check(rows.remove(factionId) != null)
        }
    }
}
