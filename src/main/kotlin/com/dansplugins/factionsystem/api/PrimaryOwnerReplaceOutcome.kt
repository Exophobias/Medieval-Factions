package com.dansplugins.factionsystem.api

/** Outcome of an exact-tenure primary-owner replacement. */
enum class PrimaryOwnerReplaceOutcome {
    /** The expected tenure was still current and the replacement was persisted. */
    REPLACED,

    /** The expected tenure was current and already named the requested replacement. */
    UNCHANGED,

    /** The faction no longer has the expected owner tenure, so nothing was changed. */
    MISMATCH
}
