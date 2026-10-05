package com.dansplugins.factionsystem.gate

import com.dansplugins.factionsystem.faction.MfFactionId
import java.util.UUID

data class MfGateDisruption(val operationId: UUID, val original: MfGate, val phase: String)

interface MfGateRepository {

    fun getGate(id: MfGateId): MfGate?
    fun getGates(): List<MfGate>
    fun upsert(gate: MfGate): MfGate
    fun delete(gateId: MfGateId)
    fun deleteAll(factionId: MfFactionId)
    /** Permanent idempotent actuator disruption. Legacy/non-durable stores refuse this capability. */
    fun getDisruption(operationId: UUID): MfGate? = null
    fun disrupt(operationId: UUID, gate: MfGate): MfGate =
        throw UnsupportedOperationException("Gate repository does not support durable disruption")
    fun getGateDisruptions(): List<MfGateDisruption> = emptyList()
    fun getGateDisruption(operationId: UUID): MfGateDisruption? = null
    fun prepareDisruption(operationId: UUID, gate: MfGate): MfGateDisruption =
        throw UnsupportedOperationException("Gate repository does not support durable preparation")
    fun finishDisruption(operationId: UUID, gate: MfGate, after: Boolean): MfGateDisruption =
        throw UnsupportedOperationException("Gate repository does not support durable completion")
}
