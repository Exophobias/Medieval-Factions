package com.dansplugins.factionsystem.claim

import com.dansplugins.factionsystem.api.WildernessReservationApi
import com.dansplugins.factionsystem.api.WildernessReservationResult
import com.dansplugins.factionsystem.api.WildernessReservationStatus
import com.dansplugins.factionsystem.api.geometry.ChunkPos
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/** Uses the claim owner's mutation lock: a consumer-side veto cannot fence an in-flight write. */
internal class WildernessReservations(
    private val mutationLock: ReentrantLock,
    private val claimed: (UUID, Int, Int) -> Boolean,
    private val nanoTime: () -> Long = System::nanoTime
) : WildernessReservationApi {
    private data class Cell(val worldId: UUID, val x: Int, val z: Int)
    private class Lease(val cells: Set<Cell>, val expires: Long) {
        val released = AtomicBoolean(false)
        fun active(now: Long) = !released.get() && expires - now > 0
    }

    private val tokens = ConcurrentHashMap<UUID, Lease>()
    // Only read or modified under the owner's lock. Token reads and releases need no lock.
    private val byCell = HashMap<Cell, Lease>()

    @Volatile private var available = true

    override fun tryReserve(worldId: UUID?, chunks: Set<ChunkPos?>?, durationMillis: Long): WildernessReservationResult {
        if (!available) return result(WildernessReservationStatus.UNAVAILABLE)
        if (worldId == null || chunks == null || chunks.isEmpty() ||
            chunks.size > WildernessReservationApi.MAX_CHUNKS_PER_RESERVATION ||
            durationMillis !in 1..WildernessReservationApi.MAX_DURATION_MILLIS
        ) {
            return result(WildernessReservationStatus.INVALID_REQUEST)
        }

        val cells = LinkedHashSet<Cell>()
        for (chunk in chunks) {
            if (chunk == null || cells.size >= WildernessReservationApi.MAX_CHUNKS_PER_RESERVATION) {
                return result(WildernessReservationStatus.INVALID_REQUEST)
            }
            cells.add(Cell(worldId, chunk.x, chunk.z))
        }
        if (cells.isEmpty()) return result(WildernessReservationStatus.INVALID_REQUEST)
        // A callback from inside an unfinished owner mutation must not reserve its pre-write state.
        if (mutationLock.isHeldByCurrentThread || !mutationLock.tryLock()) {
            return result(WildernessReservationStatus.BUSY)
        }
        try {
            if (!available) return result(WildernessReservationStatus.UNAVAILABLE)
            val now = nanoTime()
            purgeExpired(now)
            if (cells.any { claimed(it.worldId, it.x, it.z) }) return result(WildernessReservationStatus.CLAIMED)
            if (cells.any { byCell[it]?.active(now) == true }) return result(WildernessReservationStatus.RESERVED)
            if (tokens.size >= WildernessReservationApi.MAX_ACTIVE_RESERVATIONS ||
                byCell.size + cells.size > WildernessReservationApi.MAX_RESERVED_CHUNKS
            ) {
                return result(WildernessReservationStatus.CAPACITY)
            }
            val token = UUID.randomUUID()
            val lease = Lease(cells, now + durationMillis * 1_000_000)
            cells.forEach { byCell[it] = lease }
            tokens[token] = lease
            // Disable may race with acquisition; it invalidates all tokens without waiting for JDBC.
            return if (available) {
                WildernessReservationResult(WildernessReservationStatus.ACQUIRED, token)
            } else {
                lease.released.set(true)
                result(WildernessReservationStatus.UNAVAILABLE)
            }
        } finally {
            mutationLock.unlock()
        }
    }

    override fun isValid(token: UUID?): Boolean = available && token != null && tokens[token]?.active(nanoTime()) == true

    override fun remainingMillis(token: UUID?): Long {
        if (!available || token == null) return 0
        val lease = tokens[token] ?: return 0
        if (lease.released.get()) return 0
        return ((lease.expires - nanoTime()).coerceAtLeast(0) / 1_000_000)
    }

    override fun release(token: UUID?): Boolean = token != null && tokens[token]?.released?.compareAndSet(false, true) == true

    /** Invoked inside every claim insertion/transfer, before any event or repository write. */
    fun isReserved(worldId: UUID, x: Int, z: Int): Boolean {
        check(mutationLock.isHeldByCurrentThread)
        return available && byCell[Cell(worldId, x, z)]?.active(nanoTime()) == true
    }

    /** Nonblocking shutdown, including when another thread currently owns the mutation lock. */
    fun close() {
        available = false
        tokens.values.forEach { it.released.set(true) }
    }

    private fun purgeExpired(now: Long) {
        tokens.entries.removeIf { (_, lease) ->
            if (lease.active(now)) {
                false
            } else {
                lease.cells.forEach { byCell.remove(it, lease) }
                true
            }
        }
    }

    private fun result(status: WildernessReservationStatus) = WildernessReservationResult(status)
}
