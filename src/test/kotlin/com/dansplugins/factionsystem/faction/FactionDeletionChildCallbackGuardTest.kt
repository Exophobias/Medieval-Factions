package com.dansplugins.factionsystem.faction

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.event.FactionClaimAttemptEvent
import com.dansplugins.factionsystem.claim.MfClaimService
import com.dansplugins.factionsystem.claim.MfClaimedChunk
import com.dansplugins.factionsystem.claim.MfClaimedChunkRepository
import com.dansplugins.factionsystem.event.relationship.RelationshipCreateEvent
import com.dansplugins.factionsystem.failure.ServiceFailure
import com.dansplugins.factionsystem.gate.MfGateService
import com.dansplugins.factionsystem.locks.MfLockRepository
import com.dansplugins.factionsystem.locks.MfLockService
import com.dansplugins.factionsystem.locks.MfLockedBlock
import com.dansplugins.factionsystem.locks.MfLockedBlockId
import com.dansplugins.factionsystem.player.MfPlayerId
import com.dansplugins.factionsystem.relationship.MfFactionRelationship
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipId
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipRepository
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipService
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipType.ALLY
import com.dansplugins.factionsystem.service.Services
import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Result4k
import org.bukkit.Server
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.event.Event
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.scheduler.BukkitTask
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

class FactionDeletionChildCallbackGuardTest {

    @Test
    fun concurrentClaimAndRelationshipCallbacksRefuseSynchronousDeletesWithoutDeadlock() {
        val plugin = mock(MedievalFactions::class.java)
        val logger = Logger.getLogger(javaClass.name)
        `when`(plugin.logger).thenReturn(logger)
        val config = mock(FileConfiguration::class.java)
        `when`(plugin.config).thenReturn(config)
        `when`(config.getBoolean("factions.allowNeutrality")).thenReturn(true)
        val server = mock(Server::class.java)
        `when`(plugin.server).thenReturn(server)
        `when`(server.isPrimaryThread).thenReturn(false)
        `when`(server.getWorld(any(UUID::class.java))).thenReturn(null)

        val firstId = MfFactionId("claim-callback-delete")
        val secondId = MfFactionId("relationship-callback-delete")
        val first = mock(MfFaction::class.java)
        val second = mock(MfFaction::class.java)
        `when`(first.id).thenReturn(firstId)
        `when`(second.id).thenReturn(secondId)
        val factionService = MfFactionService(
            plugin,
            InMemoryFactionRepository(listOf(first, second))
        )
        val claimService = MfClaimService(plugin, InMemoryClaimRepository())
        val relationshipService = MfFactionRelationshipService(
            plugin,
            InMemoryRelationshipRepository()
        )
        val services = mock(Services::class.java)
        `when`(services.factionService).thenReturn(factionService)
        `when`(services.claimService).thenReturn(claimService)
        `when`(services.factionRelationshipService).thenReturn(relationshipService)
        val gateService = mock(MfGateService::class.java)
        `when`(services.gateService).thenReturn(gateService)
        val lockService = MfLockService(plugin, EmptyLockRepository())
        `when`(services.lockService).thenReturn(lockService)
        `when`(services.mapService).thenReturn(null)
        `when`(plugin.services).thenReturn(services)

        val scheduler = mock(BukkitScheduler::class.java)
        `when`(server.scheduler).thenReturn(scheduler)
        `when`(scheduler.runTask(any(Plugin::class.java), any(Runnable::class.java))).thenAnswer { invocation ->
            invocation.getArgument<Runnable>(1).run()
            mock(BukkitTask::class.java)
        }

        val callbacksEntered = CountDownLatch(2)
        val deletionResults = ConcurrentHashMap<String, Result4k<Unit, ServiceFailure>>()
        val manager = mock(PluginManager::class.java)
        `when`(server.pluginManager).thenReturn(manager)
        doAnswer { invocation ->
            when (invocation.getArgument<Event>(0)) {
                is FactionClaimAttemptEvent -> {
                    callbacksEntered.countDown()
                    check(callbacksEntered.await(5, TimeUnit.SECONDS))
                    deletionResults["claim"] = factionService.delete(firstId)
                }
                is RelationshipCreateEvent -> {
                    callbacksEntered.countDown()
                    check(callbacksEntered.await(5, TimeUnit.SECONDS))
                    deletionResults["relationship"] = factionService.delete(secondId)
                }
            }
            null
        }.`when`(manager).callEvent(any(Event::class.java))

        val workers = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "child-callback-delete-cycle").apply { isDaemon = true }
        }
        try {
            val claimWrite = workers.submit<Result4k<MfClaimedChunk, *>> {
                claimService.save(MfClaimedChunk(UUID.randomUUID(), 1, 2, firstId))
            }
            val relationshipWrite = workers.submit<Result4k<MfFactionRelationship, *>> {
                relationshipService.save(
                    MfFactionRelationship(factionId = firstId, targetId = secondId, type = ALLY)
                )
            }

            assertFalse(claimWrite.get(5, TimeUnit.SECONDS) is Failure)
            assertFalse(relationshipWrite.get(5, TimeUnit.SECONDS) is Failure)
            assertTrue(deletionResults["claim"] is Failure)
            assertTrue(deletionResults["relationship"] is Failure)
            deletionResults.values.forEach { result ->
                val failure = result as Failure<ServiceFailure>
                assertTrue(failure.reason.message.contains("schedule deletion after the event returns"))
            }
        } finally {
            workers.shutdownNow()
        }
    }

    private class InMemoryFactionRepository(factions: List<MfFaction>) : MfFactionRepository {
        private val rows = ConcurrentHashMap(factions.associateBy(MfFaction::id))
        override fun getFaction(id: MfFactionId) = rows[id]
        override fun getFaction(name: String) = rows.values.firstOrNull { it.name == name }
        override fun getFaction(playerId: MfPlayerId): MfFaction? = null
        override fun getFactions() = rows.values.toList()
        override fun upsert(faction: MfFaction) = faction.also { rows[it.id] = it }
        override fun delete(factionId: MfFactionId) {
            rows.remove(factionId)
        }
    }

    private class InMemoryClaimRepository : MfClaimedChunkRepository {
        private val rows = ConcurrentHashMap<String, MfClaimedChunk>()
        private fun key(worldId: UUID, x: Int, z: Int) = "$worldId:$x:$z"
        override fun getClaim(worldId: UUID, x: Int, z: Int) = rows[key(worldId, x, z)]
        override fun getClaims(factionId: MfFactionId) = rows.values.filter { it.factionId == factionId }
        override fun getClaims() = rows.values.toList()
        override fun upsert(claim: MfClaimedChunk) = claim.also {
            rows[key(it.worldId, it.x, it.z)] = it
        }
        override fun delete(worldId: UUID, x: Int, z: Int) {
            rows.remove(key(worldId, x, z))
        }
        override fun deleteAll(factionId: MfFactionId) {
            rows.entries.removeIf { it.value.factionId == factionId }
        }
    }

    private class InMemoryRelationshipRepository : MfFactionRelationshipRepository {
        private val rows = ConcurrentHashMap<MfFactionRelationshipId, MfFactionRelationship>()
        override fun getFactionRelationship(relationshipId: MfFactionRelationshipId) = rows[relationshipId]
        override fun getFactionRelationships(factionId: MfFactionId, targetId: MfFactionId) =
            rows.values.filter { it.factionId == factionId && it.targetId == targetId }
        override fun getFactionRelationships(
            factionId: MfFactionId,
            type: com.dansplugins.factionsystem.relationship.MfFactionRelationshipType
        ) = rows.values.filter { it.factionId == factionId && it.type == type }
        override fun getFactionRelationships(factionId: MfFactionId) =
            rows.values.filter { it.factionId == factionId }
        override fun getFactionRelationships() = rows.values.toList()
        override fun upsert(relationship: MfFactionRelationship) = relationship.also {
            rows[it.id] = it
        }
        override fun delete(relationshipId: MfFactionRelationshipId) {
            rows.remove(relationshipId)
        }
    }

    private class EmptyLockRepository : MfLockRepository {
        override fun getLockedBlock(id: MfLockedBlockId): MfLockedBlock? = null
        override fun getLockedBlock(worldId: UUID, x: Int, y: Int, z: Int): MfLockedBlock? = null
        override fun getLockedBlocks(): List<MfLockedBlock> = emptyList()
        override fun upsert(lockedBlock: MfLockedBlock): MfLockedBlock = lockedBlock
        override fun delete(lockedBlock: MfLockedBlock) = Unit
    }
}
