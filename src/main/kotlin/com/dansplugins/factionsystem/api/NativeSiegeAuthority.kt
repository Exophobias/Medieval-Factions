package com.dansplugins.factionsystem.api

import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Native MF policy only. Campaign, attendance, regions and value accounting belong to consumers.
 * All world/policy calls and effect leases belong to the server thread. Gate disruption prepares
 * durable intent on a worker, activates the reserved actuator on the server thread under a fresh
 * effect lease, then acknowledges durability on a worker. A lease never encloses database work;
 * the legacy one-call disruptGate entry point is denied by the native provider.
 */
interface NativeSiegeAuthority {
    val epoch: UUID
    fun isAvailable(): Boolean
    fun warIdentity(firstRealm: String, secondRealm: String): String?
    fun claimOwner(worldId: UUID, chunkX: Int, chunkZ: Int): String?
    fun permitsDestruction(actor: UUID, attackerRealm: String, targetRealm: String, blocks: List<SiegeBlock>): Boolean
    fun permitsPlayerDamage(attacker: UUID, victim: UUID): Boolean
    fun gatesAt(block: SiegeBlock): List<SiegeGate>
    fun tryAcquireEffectLease(expected: SiegeNativeExpectation): SiegeEffectLease?
    fun disruptGate(operationId: UUID, gateId: String, expectedRevision: Int): ApiOutcome<SiegeGate>
    /** Worker-only durable intent. No actuator/world effect; the gate becomes reserved. */
    fun prepareGateDisruption(operationId: UUID, gateId: String, expectedRevision: Int): ApiOutcome<SiegeGate> = ApiOutcome.failure("Gate preparation unavailable")
    /** Server-thread linearization, under an acquired native effect lease; never does I/O. */
    fun activateGateDisruption(operationId: UUID, gateId: String, expectedRevision: Int): ApiOutcome<SiegeGate> = ApiOutcome.failure("Gate activation unavailable")
    /** Worker-only acknowledgement of a server-thread activation. Never autoactivates. */
    fun commitGateDisruption(operationId: UUID, gateId: String, expectedRevision: Int): ApiOutcome<SiegeGate> = ApiOutcome.failure("Gate commit unavailable")
    /** Worker-only cancellation, solely for this runtime's unactivated preparation. */
    fun cancelGateDisruption(operationId: UUID, gateId: String, expectedRevision: Int): ApiOutcome<SiegeGate> = ApiOutcome.failure("Gate cancellation unavailable")
    /** Explicit observed-before/after review; real online staff, server thread, permission checked. */
    fun reviewGateDisruption(actor: UUID, operationId: UUID, gateId: String, expectedRevision: Int, observedAfter: Boolean): CompletableFuture<ApiOutcome<SiegeGate>> =
        CompletableFuture.completedFuture(ApiOutcome.failure("Gate review unavailable"))
}

data class SiegeBlock(val worldId: UUID, val x: Int, val y: Int, val z: Int)
data class SiegeClaim(val worldId: UUID, val chunkX: Int, val chunkZ: Int, val owner: String?)
data class SiegeMember(val actor: UUID, val realm: String)
data class SiegeWarPair(val firstRealm: String, val secondRealm: String, val identity: String)
class SiegeNativeExpectation @JvmOverloads constructor(val epoch: UUID, wars: List<SiegeWarPair>, claims: List<SiegeClaim>, members: List<SiegeMember> = emptyList()) {
    val wars: List<SiegeWarPair> = java.util.Collections.unmodifiableList(ArrayList(wars))
    val members: List<SiegeMember> = java.util.Collections.unmodifiableList(ArrayList(members))
    val claims: List<SiegeClaim> = java.util.Collections.unmodifiableList(ArrayList(claims))
}
class SiegeGate @JvmOverloads constructor(
    val id: String,
    val revision: Int,
    val owner: String,
                blocks: List<SiegeBlock>,
    val material: String,
    val state: String,
                val disruptionOperation: String? = null,
    val disruptionPhase: String? = null
) {
    val blocks: List<SiegeBlock> = java.util.Collections.unmodifiableList(ArrayList(blocks))
}
interface SiegeEffectLease : AutoCloseable { override fun close() }
