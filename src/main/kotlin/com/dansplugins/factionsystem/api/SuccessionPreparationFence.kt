package com.dansplugins.factionsystem.api

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide identities of faction saves currently between external preparation and arbitration.
 *
 * This deliberately belongs to MedievalFactions rather than to a policy instance. An add-on can be
 * disabled and re-enabled while an asynchronous MF save still holds the old policy token; the new
 * add-on instance must not mistake the durable PREPARED marker for an abandoned save while this
 * exact id remains active. It is intentionally not durable: after a process restart no old database
 * call can still commit, so recovery may safely compare the marker with live persisted tenure.
 */
object SuccessionPreparationFence {

    private val active = ConcurrentHashMap.newKeySet<UUID>()

    @JvmStatic
    fun isActive(id: UUID): Boolean = id in active

    internal fun activate(id: UUID) {
        check(active.add(id)) { "Duplicate succession preparation id $id" }
    }

    internal fun release(id: UUID) {
        active.remove(id)
    }
}
