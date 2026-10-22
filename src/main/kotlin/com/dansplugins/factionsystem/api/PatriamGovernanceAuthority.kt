package com.dansplugins.factionsystem.api

import java.util.UUID

/**
 * Fail-closed Patriam policy authority. Register only after all gameplay gates are ready.
 * Calls may arrive on MF save threads and must not perform web I/O or wait for the server thread.
 */
interface PatriamGovernanceAuthority {
    fun ready(): Boolean
    fun maySeat(playerId: UUID): Boolean
    /** Null permits the canonical faction command; a non-null message refuses it. */
    fun commandRefusal(actor: UUID, verb: String): String?
}
