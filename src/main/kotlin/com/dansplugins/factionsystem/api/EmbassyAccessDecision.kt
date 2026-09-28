package com.dansplugins.factionsystem.api

/** The embassy parcel's answer for one player action at one chunk. */
enum class EmbassyAccessDecision {
    /** No effective embassy policy applies; use ordinary faction and war rules. */
    NONE,
    /** The guest may perform this action, or MF staff bypass is enabled. */
    GRANT,
    /** The embassy forbids this action, including external virtual actions. */
    DENY
}
