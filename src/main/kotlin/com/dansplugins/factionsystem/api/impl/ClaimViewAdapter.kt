package com.dansplugins.factionsystem.api.impl

import com.dansplugins.factionsystem.api.ClaimView
import com.dansplugins.factionsystem.api.FactionId
import com.dansplugins.factionsystem.claim.MfClaimedChunk
import java.util.UUID

/** Wraps an internal [MfClaimedChunk] as a stable [ClaimView]. */
class ClaimViewAdapter(private val claim: MfClaimedChunk) : ClaimView {
    override val worldId: UUID get() = claim.worldId
    override val chunkX: Int get() = claim.x
    override val chunkZ: Int get() = claim.z
    override val factionId: FactionId get() = FactionId(claim.factionId.value)
}
