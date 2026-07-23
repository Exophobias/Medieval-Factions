package com.dansplugins.factionsystem.api

import java.util.UUID

/**
 * Stable read-only view of a claimed chunk. Returned by [MedievalFactionsApi]; consumers depend only
 * on this interface, never on MedievalFactions' internal `MfClaimedChunk`.
 */
interface ClaimView {
    val worldId: UUID
    val chunkX: Int
    val chunkZ: Int
    val factionId: FactionId
}
