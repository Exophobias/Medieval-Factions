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

    /**
     * The single player recorded as the head of this faction, or null if none is recorded.
     *
     * An IDENTITY question - "who is THE head of this House" - with at most one answer. Use it to
     * name a faction's leader, to address them, to hold them responsible, or to key anything that
     * must belong to exactly one person. It is a real stored field: set to the founder at creation,
     * moved by the head handing the faction on with /f transfer, by an operator running
     * /f admin setleader, or by succession when the head departs, and untouched by anything else a
     * faction can do to itself. Renaming roles cannot move it, which is the whole point.
     *
     * Do NOT use it to decide whether someone may do something. A faction may grant real authority to
     * several members, none of whom is the recorded head, and the head may hold no permissions at all
     * if their role was stripped. For that question use [isLeader] or
     * [FactionRoleView.hasPermission].
     *
     * Null is uncommon but legitimate, so callers must handle it rather than assuming a head exists.
     * A departing head is normally replaced by succession, but a faction can still end up with none:
     * MF allows a faction with no members at all when factions.allowLeaderlessFactions is on, and
     * factions imported or created before this field existed have no head until one is appointed.
     *
     * Defaults to null so that adding this member stays additive - an existing implementation of this
     * interface, typically a consumer's test fake, keeps compiling.
     */
    val primaryOwnerId: UUID?
        get() = null

    /**
     * Whether this player may exercise the faction's highest authority.
     *
     * A CAPABILITY question - "may this player do the most powerful thing there is" - which any
     * number of members may satisfy, including none. Use it to gate an action. For "who is the head
     * of this House", use [primaryOwnerId] instead; the two answer different questions and neither
     * implies the other.
     *
     * MedievalFactions has no rank and no role flag - a faction is a flat list of roles, and MF's own
     * code used to find "the owner" by matching the role name "Owner". Consumers cannot safely copy
     * that, because a faction can rename its own roles: relabelling a junior role "Owner" would be
     * enough to self-authorise.
     *
     * The closest thing MF genuinely has is the [FactionPermission.DISBAND] right. Nothing outranks
     * the power to dissolve the faction, it is denied by default everywhere, and it can only be
     * obtained from someone who already holds the right to grant it. So "leader" here means "may
     * dissolve this faction", which is unforgeable in the way a name is not.
     *
     * Returns false for players who are not members.
     */
    fun isLeader(playerId: UUID): Boolean =
        roleOf(playerId)?.hasPermission(FactionPermission.DISBAND) == true

    /**
     * Every member who may exercise the faction's highest authority, in member order. Empty for a
     * faction with no such member.
     *
     * The capability answer, so it may hold several ids or none. It is not a list of heads of House,
     * and the recorded head need not appear in it - see [isLeader] and [primaryOwnerId].
     */
    val leaderIds: List<UUID>
        get() = memberIds.filter { isLeader(it) }
}
