package com.dansplugins.factionsystem.locks

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.area.MfBlockPosition
import com.dansplugins.factionsystem.claim.MfClaimedChunk
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.faction.MfFactionService
import com.dansplugins.factionsystem.claim.MfClaimService
import com.dansplugins.factionsystem.player.MfPlayer
import com.dansplugins.factionsystem.player.MfPlayerId
import com.dansplugins.factionsystem.service.Services
import dev.forkhandles.result4k.Failure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger

class MfLockServiceConcurrencyTest {

    @Test
    fun repositoryCommitCannotBeOvertakenBeforeCachePublication() {
        val worldId = UUID.randomUUID()
        val factionId = MfFactionId("owners")
        val plugin = authorisedPlugin(worldId, factionId, "owner")
        val position = MfBlockPosition(worldId, 1, 64, 2)
        val original = locked(position, "owner").copy(version = 1)
        val repository = BlockingLockRepository(original)
        val service = MfLockService(plugin, repository)
        val first = original.copy(accessors = listOf(MfPlayerId("first")))
        val second = original.copy(accessors = listOf(MfPlayerId("second")))
        val executor = Executors.newFixedThreadPool(2)

        try {
            val firstWrite = executor.submit(Callable { service.save(first) })
            assertTrue(repository.firstCommitted.await(5, TimeUnit.SECONDS))
            val secondWrite = executor.submit(Callable { service.save(second) })

            assertFalse(repository.secondEntered.await(200, TimeUnit.MILLISECONDS))
            repository.releaseFirst.countDown()
            assertTrue(firstWrite.get(5, TimeUnit.SECONDS) !is Failure<*>)
            val secondResult = secondWrite.get(5, TimeUnit.SECONDS)
            assertTrue(secondResult is Failure<*>, "Expected stale save failure, got $secondResult")

            assertEquals(first.accessors, repository.current()?.accessors)
            assertEquals(first.accessors, service.getLockedBlock(position)?.accessors)
        } finally {
            repository.releaseFirst.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun claimCascadeEvictionWaitsForLateLockPublication() {
        val worldId = UUID.randomUUID()
        val factionId = MfFactionId("doomed")
        val plugin = authorisedPlugin(worldId, factionId, "owner")
        val repository = BlockingLockRepository()
        val service = MfLockService(plugin, repository)
        val position = MfBlockPosition(worldId, 1, 64, 2)
        val locked = locked(position, "owner")
        val claim = MfClaimedChunk(worldId, 0, 0, factionId)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val write = executor.submit(Callable { service.save(locked) })
            assertTrue(repository.firstCommitted.await(5, TimeUnit.SECONDS))
            val eviction = executor.submit { service.unloadLockedBlocks(listOf(claim)) }

            assertThrows(TimeoutException::class.java) { eviction.get(200, TimeUnit.MILLISECONDS) }
            repository.releaseFirst.countDown()
            assertTrue(write.get(5, TimeUnit.SECONDS) !is Failure<*>)
            eviction.get(5, TimeUnit.SECONDS)

            assertEquals(null, service.getLockedBlock(position))
        } finally {
            repository.releaseFirst.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun staleAuthorizationCannotCreateALockAfterClaimTransfer() {
        val plugin = mock(MedievalFactions::class.java)
        `when`(plugin.logger).thenReturn(Logger.getLogger(javaClass.name))
        val services = mock(Services::class.java)
        val claims = mock(MfClaimService::class.java)
        val factions = mock(MfFactionService::class.java)
        `when`(plugin.services).thenReturn(services)
        `when`(services.claimService).thenReturn(claims)
        `when`(services.factionService).thenReturn(factions)
        val repository = BlockingLockRepository(blockFirst = false)
        val service = MfLockService(plugin, repository)
        val worldId = UUID.randomUUID()
        val oldClaim = MfClaimedChunk(worldId, 0, 0, MfFactionId("old"))
        `when`(claims.getClaim(worldId, 0, 0))
            .thenReturn(oldClaim.copy(factionId = MfFactionId("new")))
        val player = mock(MfPlayer::class.java)
        `when`(player.id).thenReturn(MfPlayerId("old-member"))

        val result = service.lock(
            MfBlockPosition(worldId, 1, 64, 2),
            oldClaim,
            player
        )

        assertTrue(result is Failure<*>)
        assertEquals(null, repository.current())
    }

    @Test
    fun delayedAccessorSaveCannotRecreateLockRemovedByClaimTransfer() {
        val worldId = UUID.randomUUID()
        val factionId = MfFactionId("old")
        val plugin = authorisedPlugin(worldId, factionId, "owner")
        val position = MfBlockPosition(worldId, 1, 64, 2)
        val original = locked(position, "owner").copy(version = 1)
        val repository = BlockingLockRepository(original, blockFirst = false)
        val service = MfLockService(plugin, repository)
        val claim = MfClaimedChunk(worldId, 0, 0, factionId)

        service.withMutationLock {
            repository.cascadeDelete(position)
            service.unloadLockedBlocks(claim)
        }

        val result = service.save(original.copy(accessors = listOf(MfPlayerId("friend"))))

        assertTrue(result is Failure<*>)
        assertEquals(null, repository.current())
        assertEquals(null, service.getLockedBlock(position))
    }

    @Test
    fun delayedAccessorSaveCannotRecreateLockRemovedWhenOwnerLeavesFaction() {
        val worldId = UUID.randomUUID()
        val factionId = MfFactionId("house")
        val plugin = authorisedPlugin(worldId, factionId, "owner")
        val position = MfBlockPosition(worldId, 1, 64, 2)
        val original = locked(position, "owner").copy(version = 1)
        val repository = BlockingLockRepository(original, blockFirst = false)
        val service = MfLockService(plugin, repository)

        service.withMutationLock {
            repository.cascadeDelete(position)
            service.unloadLockedBlocks(setOf(original.playerId))
        }

        val result = service.save(original.copy(accessors = listOf(MfPlayerId("friend"))))

        assertTrue(result is Failure<*>)
        assertEquals(null, repository.current())
        assertEquals(null, service.getLockedBlock(position))
    }

    @Test
    fun delayedUnlockCannotDeleteReplacementLock() {
        val worldId = UUID.randomUUID()
        val factionId = MfFactionId("new-owner")
        val plugin = authorisedPlugin(worldId, factionId, "old-owner", "new-owner")
        val position = MfBlockPosition(worldId, 1, 64, 2)
        val oldLock = locked(position, "old-owner").copy(version = 1)
        val repository = BlockingLockRepository(oldLock, blockFirst = false)
        val service = MfLockService(plugin, repository)

        assertTrue(service.delete(oldLock) !is Failure<*>)
        val newLock = locked(position, "new-owner")
        assertTrue(service.save(newLock) !is Failure<*>)

        assertTrue(service.delete(oldLock) is Failure<*>)
        assertEquals(newLock.playerId, repository.current()?.playerId)
        assertEquals(newLock.playerId, service.getLockedBlock(position)?.playerId)
    }

    private fun locked(position: MfBlockPosition, player: String) = MfLockedBlock(
        block = position,
        chunkX = 0,
        chunkZ = 0,
        playerId = MfPlayerId(player),
        accessors = emptyList()
    )

    private fun authorisedPlugin(
        worldId: UUID,
        factionId: MfFactionId,
        vararg playerIds: String
    ): MedievalFactions {
        val plugin = mock(MedievalFactions::class.java)
        `when`(plugin.logger).thenReturn(Logger.getLogger(javaClass.name))
        val services = mock(Services::class.java)
        val claims = mock(MfClaimService::class.java)
        val factions = mock(MfFactionService::class.java)
        val faction = mock(MfFaction::class.java)
        `when`(plugin.services).thenReturn(services)
        `when`(services.claimService).thenReturn(claims)
        `when`(services.factionService).thenReturn(factions)
        `when`(claims.getClaim(worldId, 0, 0)).thenReturn(MfClaimedChunk(worldId, 0, 0, factionId))
        `when`(faction.id).thenReturn(factionId)
        playerIds.forEach { `when`(factions.getFaction(MfPlayerId(it))).thenReturn(faction) }
        return plugin
    }

    private class BlockingLockRepository(
        initial: MfLockedBlock? = null,
        private val blockFirst: Boolean = true
    ) : MfLockRepository {
        private val rows = ConcurrentHashMap<MfBlockPosition, MfLockedBlock>()
        private val writes = AtomicInteger()
        val firstCommitted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)

        init {
            initial?.let { rows[it.block] = it }
        }

        override fun getLockedBlock(id: MfLockedBlockId) = rows.values.singleOrNull { it.id == id }
        override fun getLockedBlock(worldId: UUID, x: Int, y: Int, z: Int) =
            rows[MfBlockPosition(worldId, x, y, z)]

        override fun getLockedBlocks() = rows.values.toList()

        override fun upsert(lockedBlock: MfLockedBlock): MfLockedBlock {
            val number = writes.incrementAndGet()
            if (number == 2) secondEntered.countDown()
            val current = rows.values.singleOrNull { it.id == lockedBlock.id }
            val saved = if (lockedBlock.version == 0) {
                check(current == null)
                lockedBlock.copy(version = 1)
            } else {
                check(current?.version == lockedBlock.version)
                lockedBlock.copy(version = lockedBlock.version + 1)
            }
            current?.let { rows.remove(it.block, it) }
            rows[saved.block] = saved
            if (number == 1 && blockFirst) {
                firstCommitted.countDown()
                check(releaseFirst.await(5, TimeUnit.SECONDS))
            }
            return saved
        }

        override fun delete(lockedBlock: MfLockedBlock) {
            val current = rows[lockedBlock.block]
            check(current?.id == lockedBlock.id && current.version == lockedBlock.version)
            check(rows.remove(lockedBlock.block, current))
        }

        fun cascadeDelete(position: MfBlockPosition) {
            rows.remove(position)
        }

        fun current(): MfLockedBlock? = rows.values.singleOrNull()
    }
}
