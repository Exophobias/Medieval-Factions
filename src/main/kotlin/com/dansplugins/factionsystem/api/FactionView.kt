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

    /*
     * A NOTE ON THE DEFAULTED MEMBERS BELOW, because their KDoc used to overstate what they buy.
     *
     * Several members here have a default getter, and adding one is source-compatible for a KOTLIN
     * implementation. It is NOT source-compatible for a JAVA one: this module does not set
     * -Xjvm-default, so Kotlin compiles a defaulted interface member to an abstract method plus a
     * DefaultImpls class, and javac sees only the abstract method. Every Java implementation - which
     * in practice means consumers' test fakes - must therefore override each new member.
     *
     * That is a compile error in the consumer's own test tier, which is the cheap place to find out,
     * so it has not been worth turning -Xjvm-default on for. But it is a real cost of adding a
     * member here and the KDoc should not claim otherwise.
     */

    val id: FactionId
    val name: String
    val description: String

    /** The faction's home/core location, or null if unset or its world is not loaded. */
    val home: Location?

    /** Bukkit player UUIDs of the faction's members. */
    val memberIds: List<UUID>

    /** Number of chunks this faction has claimed. Backed by an O(1) count. */
    val claimCount: Int

    /**
     * The faction's territory colour as `#RRGGBB`.
     *
     * Exposed as the string MedievalFactions actually stores and validates rather than as a packed
     * int, so this cannot disagree with what `/f flag set color` shows a player. MF's own flag
     * validator rejects anything not matching `#[A-Fa-f0-9]{6}`, so a consumer may parse it with
     * `Integer.decode` without defending against a malformed value.
     *
     * Defaults are assigned per faction at creation, randomised unless the server configures a fixed
     * one, so two factions being the same colour is possible but unlikely and is not prevented.
     */
    val color: String

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
     * Defaulted so a Kotlin implementation need not restate it. A Java one must override it;
     * see the note at the top of this interface for why.
     */
    val primaryOwnerId: UUID?
        get() = null

    /**
     * The player the head of this faction has nominated to inherit it, or null if none is nominated.
     *
     * A NOMINATION, not an office. The heir holds no authority whatsoever until they actually
     * inherit: they are an ordinary member with whatever role they already had, and naming them
     * changes nothing about what they may do. Do not gate anything on this. It answers only "who
     * would this faction pass to", which is a question about the future.
     *
     * Cleared the moment it stops being true rather than at the moment it is used, so it can never
     * name somebody who has left. Normally a member of this faction; it may instead be the recorded
     * head of a faction sworn to this one, which is the single case where an heir is not one of your
     * own people, and that nomination is dropped if the vassal declares independence or replaces its
     * own head.
     *
     * A nomination is only ever the FIRST tier of succession, not the whole of it. An heir who is no
     * longer eligible when the head departs is passed over silently and the faction still finds a
     * successor, so a consumer must not treat a null here as "this faction has nobody to inherit
     * it". Register a [SuccessionPolicy] if you need to know, or decide, what actually happens.
     *
     * Defaulted so a Kotlin implementation need not restate it. A Java one must override it;
     * see the note at the top of this interface for why.
     */
    val heirId: UUID?
        get() = null

    /**
     * When [primaryOwnerId] came to the seat, as epoch milliseconds, or 0 if MF does not know.
     *
     * Published because "how long have you held this position" is a real gate and nothing could
     * answer it. A tenure rule exists so that joining a group, being handed the top of it, and using
     * that position against somebody cannot all happen on one day -- and without this a consumer
     * could enforce that for a sub-group whose own plugin records a grant time, and not for the
     * faction itself, so the same rule applied at one level of a hierarchy and silently did nothing
     * at the other.
     *
     * **Zero means "not known", and a consumer should read it as long-held rather than as new.** It
     * is the value every faction carried before MF started recording this, and those heads really
     * have held their realms since before anybody was counting. Treating it as "took the seat at the
     * epoch" is both the truthful reading and the one that does not freeze an entire server out of a
     * rule on the day it ships.
     *
     * Meaningless when [primaryOwnerId] is null. Check that first.
     *
     * Defaulted so a Kotlin implementation need not restate it. A Java one must override it;
     * see the note at the top of this interface for why.
     */
    val primaryOwnerSince: Long
        get() = 0L

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

    /**
     * Where this faction sits in the liege/vassal hierarchy.
     *
     * Vassalage is the one piece of faction state that is neither owned by nor stored on the faction:
     * it lives in relationships between factions, and every question worth asking about it - who does
     * this faction answer to, who answers to it, how far down does it sit - needs several of those
     * walked together. [FactionHierarchyView] carries the answers as one snapshot so a consumer never
     * has to walk anything, and so the walking stays MF's problem.
     *
     * The intended use is deriving something a faction is not told and does not store: a rank, a
     * style of address, a display tier. Those are properties of a position rather than of a faction,
     * they change the moment the structure around the faction changes, and they must therefore be
     * recomputed rather than recorded. Read [FactionHierarchyView] for what each field means and what
     * asking costs; it is cheap enough to ask per message, and it is not a live object.
     *
     * Defaulted to [FactionHierarchyView.INDEPENDENT] so a Kotlin implementation need not restate
     * it. A Java one must override it; see the note at the top of this interface for why.
     */
    val hierarchy: FactionHierarchyView
        get() = FactionHierarchyView.INDEPENDENT
}
