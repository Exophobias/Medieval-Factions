package com.dansplugins.factionsystem.claim

import com.dansplugins.factionsystem.faction.MfFactionId
import java.util.UUID

/** One durable cell of a connected faction embassy area. The host retains the claim. */
data class MfEmbassy(
    val worldId: UUID,
    val chunkX: Int,
    val chunkZ: Int,
    val hostId: MfFactionId,
    val guestId: MfFactionId,
    val status: MfEmbassyStatus,
    val createdAt: Long,
    val changedAt: Long,
    /** Offer, conquest decision or recovery deadline, in epoch milliseconds; absent while active. */
    val deadlineAt: Long?,
    /** New landholder after conquest; the old host's charter is not inherited. */
    val conquerorId: MfFactionId? = null,
    /** War start affecting recovery, including a pending conquest's later passage period. */
    val pausedAt: Long? = null,
    /** Number of cells promised by this offer batch; protects against partial FK loss on restart. */
    val offerSize: Int = 1
)

enum class MfEmbassyStatus { OFFERED, ACTIVE, CLEARING, CONQUEST_DECISION, CONQUEST_PASSAGE }

/** A native embassy answer is authoritative over ordinary claim permissions and additive providers. */
enum class EmbassyAccessDecision { NONE, GRANT, DENY }

/** Shared by service startup and strict JSON load; malformed rows must never be rewritten. */
internal fun MfEmbassy.validateStoredEmbassy() {
    require(offerSize in 1..4096) { "Invalid embassy offer size" }
    require(hostId != guestId) { "Embassy host and guest match" }
    require(hostId.value.isNotBlank() && guestId.value.isNotBlank()) { "Blank embassy faction identity" }
    require(conquerorId == null || (conquerorId.value.isNotBlank() && conquerorId != guestId)) {
        "Invalid embassy conqueror identity"
    }
    require(createdAt >= 0 && changedAt >= createdAt) { "Invalid embassy timestamps" }
    require(
        (status == MfEmbassyStatus.CONQUEST_DECISION || status == MfEmbassyStatus.CONQUEST_PASSAGE) ==
        (conquerorId != null)
    ) { "Conquest identity does not match embassy status" }
    require(
        pausedAt == null ||
        (
            (
                status == MfEmbassyStatus.CLEARING || status == MfEmbassyStatus.CONQUEST_PASSAGE ||
            status == MfEmbassyStatus.CONQUEST_DECISION
            ) && pausedAt >= changedAt
        )
    ) { "Invalid embassy war pause" }
    require(
        (status == MfEmbassyStatus.ACTIVE && deadlineAt == null) ||
        (status != MfEmbassyStatus.ACTIVE && deadlineAt != null && deadlineAt > changedAt)
    ) {
        "Invalid embassy deadline"
    }
}
