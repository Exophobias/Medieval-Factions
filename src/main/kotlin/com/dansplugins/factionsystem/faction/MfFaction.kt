package com.dansplugins.factionsystem.faction

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.impl.FactionViewAdapter
import com.dansplugins.factionsystem.area.MfPosition
import com.dansplugins.factionsystem.exception.NoSuccessorException
import com.dansplugins.factionsystem.faction.flag.MfFlagValues
import com.dansplugins.factionsystem.faction.permission.MfFactionPermission
import com.dansplugins.factionsystem.faction.role.MfFactionRole
import com.dansplugins.factionsystem.faction.role.MfFactionRoleId
import com.dansplugins.factionsystem.faction.role.MfFactionRoles
import com.dansplugins.factionsystem.notification.MfNotification
import com.dansplugins.factionsystem.player.MfPlayerId
import java.util.Collections.emptyList
import java.util.UUID

data class MfFaction(
    private val plugin: MedievalFactions,
    @get:JvmName("getId")
    val id: MfFactionId = MfFactionId.generate(),
    val version: Int = 0,
    val name: String,
    val description: String = "",
    val members: List<MfFactionMember> = emptyList(),
    val invites: List<MfFactionInvite> = emptyList(),
    val flags: MfFlagValues = plugin.flags.defaults(),
    val prefix: String? = null,
    val home: MfPosition? = null,
    val bonusPower: Double = 0.0,
    val autoclaim: Boolean = false,
    val roles: MfFactionRoles = MfFactionRoles.defaults(plugin, id),
    val defaultPermissionsByName: Map<String, Boolean> = plugin.factionPermissions.permissionsFor(id, roles).associate { it.name to it.default },
    val applications: List<MfFactionApplication> = emptyList(),
    /**
     * The member recorded as the head of this faction, or null if none is recorded.
     *
     * Identity, not capability. This answers "who is THE head of this House", and there is at most
     * one. It is set from the creator at creation and reassigned only by /f admin setleader. Nothing
     * a faction can do to itself changes it: renaming roles, granting permissions and shuffling
     * members all leave it alone, which is the entire reason it exists. Asking instead whether a
     * given player may do something is a different question - see [MfFactionRole.hasPermission], or
     * the API's FactionView.isLeader, which several members may satisfy at once.
     *
     * Nullable because MF can produce a faction with no members at all when
     * factions.allowLeaderlessFactions is on, and because MF has no succession rule to fall back on
     * when the head departs.
     */
    val primaryOwnerId: MfPlayerId? = null,
    /**
     * The player the head of this faction has nominated to inherit it, or null if none is nominated.
     *
     * A nomination, not an office: the heir holds no authority until they actually inherit, and it is
     * cleared as soon as it is used. Only the head may set it, via /f heir.
     *
     * Normally a member. It may instead be the recorded head of a faction that has sworn fealty to
     * this one, which is the one case where an heir is not one of your own people - see
     * [heirsVassalFaction]. Either way the nomination is dropped the moment it stops being true, so it
     * can never name somebody who has left or a vassal that has walked away.
     */
    val heirId: MfPlayerId? = null,
    /**
     * When [primaryOwnerId] came to the seat, as epoch milliseconds, or 0 if it is not known.
     *
     * Stamped by [MfFactionService.save] whenever the head actually changes, rather than by the five
     * call sites that can move one. A comparison in the write path cannot be forgotten by a sixth
     * route added later; five scattered assignments can, and the failure would be a head whose tenure
     * silently dated from whenever the PREVIOUS one took over.
     *
     * Zero reads as "held since the epoch" and so clears every tenure gate. That is the correct
     * answer for a faction that predates this field: its head really has held it since before anybody
     * was counting.
     *
     * Not a substitute for asking whether somebody is the head. It is meaningless when
     * [primaryOwnerId] is null, and a caller that reads it without checking that first is asking how
     * long nobody has held the seat.
     */
    val primaryOwnerSince: Long = 0L
) {

    val memberPower
        get() = members.sumOf { plugin.services.playerService.getPlayer(it.playerId)?.power ?: 0.0 }
    val maxMemberPower
        get() = members.size * plugin.config.getDouble("players.maxPower")
    val vassalPower
        get() = plugin.services.factionRelationshipService.getVassals(id)
            .mapNotNull(plugin.services.factionService::getFaction)
            .sumOf { it.power * plugin.config.getDouble("factions.vassalPowerContributionMultiplier") }
    val maxVassalPower
        get() = plugin.services.factionRelationshipService.getVassals(id)
            .mapNotNull(plugin.services.factionService::getFaction)
            .sumOf { it.maxPower * plugin.config.getDouble("factions.vassalPowerContributionMultiplier") }

    val power: Double
        get() = memberPower + (if (memberPower >= maxMemberPower / 2.0) { vassalPower } else { 0.0 }) + (if (flags[plugin.flags.acceptBonusPower]) bonusPower else 0.0)

    val maxPower: Double
        get() = maxMemberPower + maxVassalPower + (if (flags[plugin.flags.acceptBonusPower]) bonusPower else 0.0)

    val defaultPermissions: Map<MfFactionPermission, Boolean>
        get() = defaultPermissionsByName.toList().map { (key, value) -> plugin.factionPermissions.parse(key) to value }
            .filter { (key, _) -> key != null }
            .associate { (key, value) -> key!! to value }

    @JvmName("getRoleByPlayerId")
    fun getRole(playerId: MfPlayerId): MfFactionRole? = members.singleOrNull { it.playerId == playerId }?.role

    @JvmName("getRoleByRoleId")
    fun getRole(roleId: MfFactionRoleId): MfFactionRole? = roles.getRole(roleId)

    @JvmName("getRoleByName")
    fun getRole(name: String): MfFactionRole? = roles.getRole(name)

    /** Members oldest first, which is what "longest-standing" means for succession. */
    private val membersByStanding: List<MfFactionMember>
        get() = members.sortedWith(compareBy({ it.joinedAt }, { it.playerId.value }))

    @JvmName("isMember")
    fun isMember(playerId: MfPlayerId): Boolean = members.any { it.playerId == playerId }

    /** Whether the record still names a head who is no longer on the member list. */
    private val primaryOwnerHasDeparted: Boolean
        get() = primaryOwnerId != null && !isMember(primaryOwnerId)

    /**
     * The faction sworn to this one whose recorded head is this faction's nominated heir, or null if
     * the nomination is not of that kind or no longer holds.
     *
     * A ruler may name the head of one of their vassals as successor. It is the only way an heir can
     * be somebody who is not already a member, and it is deliberate: it makes swearing fealty worth
     * something to the vassal rather than being purely a burden, and it produces a real succession
     * crisis rather than a field update, because a player belongs to exactly one faction and taking
     * the greater one means leaving their own.
     *
     * Every condition is rechecked here rather than trusted from the moment of nomination, because
     * every one of them can be undone by somebody else in the meantime: the vassal can declare
     * independence, the liege can grant it, and the vassal can replace its own head. A nomination that
     * has gone stale simply stops being an answer, so succession falls through to the ordinary order
     * rather than failing.
     *
     * Null while the plugin is still wiring its services up, which is the only time the lookups this
     * needs are unavailable. Nothing on that path is a succession - see MedievalFactions.servicesOrNull.
     */
    val heirsVassalFaction: MfFactionId?
        get() {
            val heir = heirId ?: return null
            if (isMember(heir)) return null
            val services = plugin.servicesOrNull ?: return null
            val heirsFaction = services.factionService.getFaction(heir) ?: return null
            if (heirsFaction.primaryOwnerId != heir) return null
            if (services.factionRelationshipService.getLiege(heirsFaction.id) != id) return null
            return heirsFaction.id
        }

    /**
     * The vassal faction whose head is about to leave it to take this one, or null if no such
     * succession is due.
     *
     * True only when this faction's seat has actually fallen vacant AND the standing nomination is of
     * a vassal's head that still checks out. Everything else - a nomination that is merely present, a
     * head who is still here - answers null.
     */
    val vassalHeirAscensionDue: MfFactionId?
        get() = if (primaryOwnerHasDeparted) heirsVassalFaction else null

    /**
     * This faction with a nominated heir who is not yet a member admitted as one, holding the top
     * role.
     *
     * The top role for the same reason /f transfer grants it: a head who cannot act is not a head.
     * Admitting them turns the cross-faction case back into the ordinary one, so [successorToPrimaryOwner]
     * needs no special tier for it and the rule stays a single ladder.
     *
     * Says nothing about whether the ascension is warranted - see [vassalHeirAscensionDue] - and it
     * cannot complete one on its own, since the heir must also be released from the faction they are
     * leaving and only the service can do that.
     */
    fun withVassalHeirAdmitted(): MfFaction {
        val heir = heirId ?: return this
        if (isMember(heir)) return this
        return copy(members = members + MfFactionMember(heir, roles.leaderRole ?: roles.default))
    }

    /**
     * Who would inherit this faction if the head departed right now, or null if nobody could.
     *
     * Three tiers, most deliberate first:
     *
     * 1. The head's own nominee, if they are still a member. An explicit choice outranks any rule.
     * 2. The longest-standing member whose role may disband the faction. They already hold the
     *    faction's terminal authority, so this hands the title to someone who could already exercise
     *    it rather than promoting a stranger.
     *
     *    "May disband" is the EFFECTIVE permission - the role's own grant, else the faction default,
     *    else the permission's default - and not the role's explicit grant alone. The two differ for
     *    a faction that has granted disband by default rather than role by role, and the effective
     *    reading is the one FactionView.isLeader and FactionView.leaderIds already use. When this
     *    tier read explicit grants only, a faction could contain members that MF's own API called
     *    leaders and that this ladder passed over, which is the sort of disagreement nobody finds
     *    until a succession goes to the wrong person.
     * 3. The longest-standing member holding the most authoritative role available, measured as the
     *    number of permissions the role is explicitly granted. Crude, but MF has no rank, no role
     *    ordering and no other comparison to offer, and a count of granted rights is at least
     *    unforgeable in the way a role name is not.
     *
     * Ties within a tier break on standing, then on player id, so the answer is deterministic rather
     * than dependent on the order a database returned rows in. Deliberately no consideration of
     * activity or absence: this is about departure, not idleness.
     */
    val successorToPrimaryOwner: MfPlayerId?
        get() {
            val candidates = membersByStanding.filter { it.playerId != primaryOwnerId }
            if (candidates.isEmpty()) return null
            heirId?.let { heir -> candidates.firstOrNull { it.playerId == heir } }
                ?.let { return it.playerId }
            candidates.firstOrNull { it.role.hasPermission(this, plugin.factionPermissions.disband) }
                ?.let { return it.playerId }
            val mostAuthority = candidates.maxOf { it.role.grantedPermissionCount }
            return candidates.first { it.role.grantedPermissionCount == mostAuthority }.playerId
        }

    /**
     * This faction with the head reconciled against the member list: unchanged while the head is
     * still a member, otherwise passed to [successorToPrimaryOwner].
     *
     * Applied on every save, so it covers every way a head can go - leaving, being kicked, or being
     * removed by anything else that rewrites the member list - without each of those having to
     * remember. Disbanding needs no handling at all, since the row is deleted outright.
     *
     * Leaving the faction headless is the last resort, not the default: it happens only when no
     * member remains to inherit. Whether that is allowed is MF's own call, so it is read from
     * factions.allowLeaderlessFactions. With leaderless factions forbidden there is no valid state to
     * fall back to, so the save is refused rather than quietly producing one.
     *
     * A nomination survives only while it is still true. That is one test for an ordinary heir - are
     * they still a member - and a second for a vassal's head, who is not one; see [heirsVassalFaction]
     * for what can silently invalidate the latter. Either way a nomination that no longer holds is
     * forgotten rather than honoured, so the ladder below it applies.
     */
    /**
     * Who a registered [com.dansplugins.factionsystem.api.SuccessionPolicy] says should inherit, or
     * null if none is registered, none has an opinion, or the answer was not one MF may use.
     *
     * Consulted ahead of [successorToPrimaryOwner] rather than as a tier within it, because the two
     * are different kinds of answer. The ladder is MF's rule about who is next in line. A policy is
     * an external government's decision about who rules, and a decision that could be outranked by a
     * default is not a decision. It is consulted AFTER the vassal heir has been admitted, so a
     * policy sees the same member list the ladder would and needs no special case for the one heir
     * who arrives from outside.
     *
     * Every way this can fail returns null, which means MF's own ladder applies. That includes the
     * plugin still wiring its services up, which is the only time the lookup is unavailable and is
     * never a real succession - see MedievalFactions.servicesOrNull.
     */
    private fun policySuccessor(departingHead: MfPlayerId): MfPlayerId? {
        val services = plugin.servicesOrNull ?: return null
        val policies = services.factionService.successionPolicies
        if (policies.isEmpty()) return null
        val departing = runCatching { UUID.fromString(departingHead.value) }.getOrNull() ?: return null
        val chosen = policies.successorFor(FactionViewAdapter(plugin, this), departing) ?: return null
        return MfPlayerId(chosen.toString())
    }

    fun withPrimaryOwnerSuccession(): MfFaction {
        val survivingHeir = heirId?.takeIf { heir -> isMember(heir) || heirsVassalFaction != null }
        val owner = primaryOwnerId
        if (owner == null || members.any { it.playerId == owner }) {
            return if (survivingHeir == heirId) this else copy(heirId = survivingHeir)
        }
        val successor = policySuccessor(owner) ?: successorToPrimaryOwner
        if (successor == null && !plugin.config.getBoolean("factions.allowLeaderlessFactions")) {
            throw NoSuccessorException(
                "Faction ${id.value} lost its primary owner with nobody to inherit, " +
                    "and factions.allowLeaderlessFactions forbids a faction without one."
            )
        }
        return copy(primaryOwnerId = successor, heirId = survivingHeir?.takeIf { it != successor })
    }

    fun sendMessage(title: String, message: String) {
        members.map { it.playerId }
            .forEach { mfPlayer ->
                val offlinePlayer = mfPlayer.toBukkitPlayer()
                val player = offlinePlayer.player
                if (player != null) {
                    player.sendMessage("$title - $message")
                } else {
                    plugin.server.scheduler.runTaskAsynchronously(
                        plugin,
                        Runnable {
                            plugin.services.notificationService.sendNotification(
                                mfPlayer,
                                MfNotification(
                                    title,
                                    message
                                )
                            )
                        }
                    )
                }
            }
    }
}
