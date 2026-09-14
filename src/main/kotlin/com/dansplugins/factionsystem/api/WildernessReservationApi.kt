package com.dansplugins.factionsystem.api

import com.dansplugins.factionsystem.api.geometry.ChunkPos
import java.util.UUID

/**
 * Short-lived exclusion of new claims from currently unclaimed land. Resolve this separate,
 * additive service through Bukkit's ServicesManager; existing MedievalFactionsApi implementors
 * do not acquire new methods. Reservations never create claims or grant player protection.
 *
 * All methods are thread-safe and perform no world access, database work, or blocking lock waits.
 * Acquire only after slow preparation. An ACQUIRED result is ordered after every prior claim
 * commit; later claim mutations cannot enter its footprint while the token remains valid.
 * BUSY means an owner mutation is in progress: retry later, never spin on the server thread.
 *
 * Tokens expire on a monotonic clock, do not survive plugin disable/restart, and must be released
 * after use. Check remainingMillis immediately before an operation and leave enough time for its
 * completion. A successful check is a snapshot, not a renewal or a guarantee after expiration.
 */
interface WildernessReservationApi {
    /** Invalid/empty footprints and durations outside 1..60,000 ms return INVALID_REQUEST. */
    fun tryReserve(worldId: UUID?, chunks: Set<ChunkPos?>?, durationMillis: Long): WildernessReservationResult

    fun isValid(token: UUID?): Boolean

    /** Conservative whole milliseconds left, or zero for an unknown/released/expired token. */
    fun remainingMillis(token: UUID?): Long

    /** Idempotent, nonblocking invalidation. True only when this call released a known token. */
    fun release(token: UUID?): Boolean

    companion object {
        const val MAX_CHUNKS_PER_RESERVATION = 4096
        const val MAX_ACTIVE_RESERVATIONS = 256
        const val MAX_RESERVED_CHUNKS = 65536
        const val MAX_DURATION_MILLIS = 60000L
    }
}
