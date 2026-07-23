package com.dansplugins.factionsystem.api

/**
 * Stable, API-owned identity for a faction. Deliberately decoupled from MedievalFactions' internal
 * `MfFactionId` so that consumers of [MedievalFactionsApi] never bind to internal types.
 *
 * A plain data class (not a value class) for painless Java interop.
 */
data class FactionId(val value: String)
