package com.dansplugins.factionsystem.claim

import com.dansplugins.factionsystem.api.WildernessReservationApi
import com.dansplugins.factionsystem.api.WildernessReservationStatus
import com.dansplugins.factionsystem.api.geometry.ChunkPos
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class WildernessReservationsTest {
    private val world = UUID.randomUUID()
    private val lock = ReentrantLock()
    private val clock = AtomicLong(100_000_000)
    private val claimed = HashSet<ChunkPos>()
    private val reservations = WildernessReservations(lock, { _, x, z -> ChunkPos(x, z) in claimed }, clock::get)

    @Test
    fun footprintIsAtomicWorldScopedAndDefensivelyCopied() {
        claimed.add(ChunkPos(1, 2))
        assertEquals(
            WildernessReservationStatus.CLAIMED,
            reservations.tryReserve(world, setOf(ChunkPos(0, 0), ChunkPos(1, 2)), 5000).status
        )
        claimed.clear()
        val cells = mutableSetOf<ChunkPos?>(ChunkPos(-1, -2), ChunkPos(0, 0))
        val first = reservations.tryReserve(world, cells, 5000)
        assertEquals(WildernessReservationStatus.ACQUIRED, first.status)
        cells.clear()
        lock.withLock {
            assertTrue(reservations.isReserved(world, -1, -2))
            assertTrue(reservations.isReserved(world, 0, 0))
            assertFalse(reservations.isReserved(UUID.randomUUID(), -1, -2))
        }
        assertEquals(
            WildernessReservationStatus.RESERVED,
            reservations.tryReserve(world, setOf(ChunkPos(0, 0), ChunkPos(8, 8)), 5000).status
        )
        assertEquals(
            WildernessReservationStatus.ACQUIRED,
            reservations.tryReserve(world, setOf(ChunkPos(8, 8)), 5000).status
        )
    }

    @Test
    fun expiryUsesMonotonicTimeAndDoesNotRenewOnRead() {
        val token = reservations.tryReserve(world, setOf(ChunkPos(0, 0)), 5000).token!!
        assertEquals(5000, reservations.remainingMillis(token))
        clock.addAndGet(4_999_500_000)
        assertTrue(reservations.isValid(token))
        assertEquals(0, reservations.remainingMillis(token))
        clock.addAndGet(500_000)
        assertFalse(reservations.isValid(token))
        lock.withLock { assertFalse(reservations.isReserved(world, 0, 0)) }
        assertEquals(
            WildernessReservationStatus.ACQUIRED,
            reservations.tryReserve(world, setOf(ChunkPos(0, 0)), 5000).status
        )
    }

    @Test
    fun expiryHandlesNanoTimeWraparound() {
        clock.set(Long.MAX_VALUE - 1_000_000)
        val token = reservations.tryReserve(world, setOf(ChunkPos(0, 0)), 5).token!!
        clock.addAndGet(2_000_000)
        assertEquals(3, reservations.remainingMillis(token))
        clock.addAndGet(3_000_000)
        assertFalse(reservations.isValid(token))
    }

    @Test
    fun invalidRequestsCannotTakeAnyCapacity() {
        val valid = setOf(ChunkPos(0, 0))
        for (duration in listOf(Long.MIN_VALUE, -1, 0, 60_001, Long.MAX_VALUE)) {
            assertEquals(
                WildernessReservationStatus.INVALID_REQUEST,
                reservations.tryReserve(world, valid, duration).status
            )
        }
        assertEquals(WildernessReservationStatus.INVALID_REQUEST, reservations.tryReserve(null, valid, 5000).status)
        assertEquals(WildernessReservationStatus.INVALID_REQUEST, reservations.tryReserve(world, null, 5000).status)
        assertEquals(WildernessReservationStatus.INVALID_REQUEST, reservations.tryReserve(world, emptySet(), 5000).status)
        assertEquals(WildernessReservationStatus.INVALID_REQUEST, reservations.tryReserve(world, setOf(null), 5000).status)
        assertEquals(
            WildernessReservationStatus.INVALID_REQUEST,
            reservations.tryReserve(world, (0..4096).map { ChunkPos(it, 0) }.toSet(), 5000).status
        )
        assertEquals(WildernessReservationStatus.ACQUIRED, reservations.tryReserve(world, valid, 5000).status)
    }

    @Test
    fun reservationCountAndTotalFootprintAreBoundedAndReleasedSpaceIsReused() {
        val tokens = (0 until WildernessReservationApi.MAX_ACTIVE_RESERVATIONS).map {
            reservations.tryReserve(world, setOf(ChunkPos(it, 0)), 5000).token!!
        }
        assertEquals(
            WildernessReservationStatus.CAPACITY,
            reservations.tryReserve(world, setOf(ChunkPos(999, 0)), 5000).status
        )
        tokens.forEach { assertTrue(reservations.release(it)) }
        for (row in 0 until 16) {
            assertEquals(
                WildernessReservationStatus.ACQUIRED,
                reservations.tryReserve(world, (0 until 4096).map { ChunkPos(it, row) }.toSet(), 5000).status
            )
        }
        assertEquals(
            WildernessReservationStatus.CAPACITY,
            reservations.tryReserve(world, setOf(ChunkPos(0, 17)), 5000).status
        )
        clock.addAndGet(5_000_000_000)
        assertEquals(
            WildernessReservationStatus.ACQUIRED,
            reservations.tryReserve(world, setOf(ChunkPos(0, 17)), 5000).status
        )
    }

    @Test
    fun acquisitionCannotReenterAnUncommittedOwnerMutation() {
        lock.withLock {
            assertEquals(
                WildernessReservationStatus.BUSY,
                reservations.tryReserve(world, setOf(ChunkPos(0, 0)), 5000).status
            )
        }
    }

    @Test
    fun lockContentionNeverBlocksAcquisitionValidityReleaseOrShutdown() {
        val token = reservations.tryReserve(world, setOf(ChunkPos(0, 0)), 5000).token!!
        val executor = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val holder = executor.submit {
                lock.withLock {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            executor.submit {
                assertEquals(
                    WildernessReservationStatus.BUSY,
                    reservations.tryReserve(world, setOf(ChunkPos(1, 0)), 5000).status
                )
                assertTrue(reservations.isValid(token))
                assertEquals(5000, reservations.remainingMillis(token))
                assertTrue(reservations.release(token))
                assertFalse(reservations.release(token))
                assertFalse(reservations.isValid(token))
                reservations.close()
            }.get(1, TimeUnit.SECONDS)
            release.countDown()
            holder.get(5, TimeUnit.SECONDS)
            assertEquals(
                WildernessReservationStatus.UNAVAILABLE,
                reservations.tryReserve(world, setOf(ChunkPos(1, 0)), 5000).status
            )
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun unknownTokensAndShutdownCannotLookValid() {
        assertFalse(reservations.isValid(UUID.randomUUID()))
        assertEquals(0, reservations.remainingMillis(null))
        assertFalse(reservations.release(null))
        val token = reservations.tryReserve(world, setOf(ChunkPos(0, 0)), 5000).token!!
        reservations.close()
        assertFalse(reservations.isValid(token))
        assertEquals(0, reservations.remainingMillis(token))
    }
}
