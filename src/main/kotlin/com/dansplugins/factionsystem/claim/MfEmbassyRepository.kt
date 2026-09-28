package com.dansplugins.factionsystem.claim

import java.util.UUID

interface MfEmbassyRepository {
    fun getAll(): List<MfEmbassy>
    fun upsert(embassy: MfEmbassy)
    fun delete(worldId: UUID, chunkX: Int, chunkZ: Int)

    /** All cells of an offer/acceptance/withdrawal commit together, or none of them do. */
    fun applyChanges(upserts: List<MfEmbassy>, deletes: List<MfEmbassy>)
}
