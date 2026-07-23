package com.dansplugins.factionsystem.api

import org.bukkit.Location
import java.util.UUID

/**
 * Stable read-only view of a faction. Returned by [MedievalFactionsApi]; consumers depend only on
 * this interface, never on MedievalFactions' internal `MfFaction`. Uses Bukkit types (Location, UUID)
 * and API-owned types ([FactionId], [FactionRoleView]) so that internal refactors cannot reach
 * consumers.
 */
interface FactionView {
    val id: FactionId
    val name: String
    val description: String

    /** The faction's home/core location, or null if unset or its world is not loaded. */
    val home: Location?

    /** Bukkit player UUIDs of the faction's members. */
    val memberIds: List<UUID>

    /** Number of chunks this faction has claimed. Backed by an O(1) count. */
    val claimCount: Int

    /** Factions this faction is currently at war with. */
    val factionsAtWarWith: List<FactionId>

    fun isAtWarWith(other: FactionId): Boolean

    /** The role of the given player within this faction, or null if they are not a member. */
    fun roleOf(playerId: UUID): FactionRoleView?
}
