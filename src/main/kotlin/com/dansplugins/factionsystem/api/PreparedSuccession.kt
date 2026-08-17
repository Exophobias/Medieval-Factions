package com.dansplugins.factionsystem.api

/**
 * One provisional external succession decision prepared before MedievalFactions commits its save.
 *
 * MedievalFactions calls exactly one terminal method. Both methods are notifications: an exception
 * is contained and never changes the result of the faction save. Implementations therefore retain
 * any durable recovery marker when [committed] cannot finish, and make [aborted] idempotent.
 */
interface PreparedSuccession {

    /** The faction save committed, and [faction] is the exact persisted result. */
    fun committed(faction: FactionView)

    /** The faction save did not commit. Repeating this call must be harmless. */
    fun aborted()
}
