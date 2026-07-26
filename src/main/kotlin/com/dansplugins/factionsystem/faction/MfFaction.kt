package com.dansplugins.factionsystem.faction

import com.dansplugins.factionsystem.MedievalFactions
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
     * The member the head of this faction has nominated to inherit it, or null if none is nominated.
     *
     * A nomination, not an office: the heir holds no authority until they actually inherit. It is
     * cleared as soon as it is used, and as soon as the nominee stops being a member, so it can never
     * name someone who has left. Only the head may set it, via /f heir.
     */
    val heirId: MfPlayerId? = null
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

    /**
     * Who would inherit this faction if the head departed right now, or null if nobody could.
     *
     * Three tiers, most deliberate first:
     *
     * 1. The head's own nominee, if they are still a member. An explicit choice outranks any rule.
     * 2. The longest-standing member whose role is explicitly granted the right to disband. They
     *    already hold the faction's terminal authority, so this hands the title to someone who could
     *    already exercise it rather than promoting a stranger.
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
            candidates.firstOrNull { it.role.getPermissionValue(plugin.factionPermissions.disband) == true }
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
     */
    fun withPrimaryOwnerSuccession(): MfFaction {
        val survivingHeir = heirId?.takeIf { heir -> members.any { it.playerId == heir } }
        val owner = primaryOwnerId
        if (owner == null || members.any { it.playerId == owner }) {
            return if (survivingHeir == heirId) this else copy(heirId = survivingHeir)
        }
        val successor = successorToPrimaryOwner
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
