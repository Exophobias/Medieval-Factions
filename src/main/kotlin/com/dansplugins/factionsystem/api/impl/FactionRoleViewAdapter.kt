package com.dansplugins.factionsystem.api.impl

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.FactionPermission
import com.dansplugins.factionsystem.api.FactionRoleView
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.permission.MfFactionPermission
import com.dansplugins.factionsystem.faction.permission.MfFactionPermissions
import com.dansplugins.factionsystem.faction.role.MfFactionRole

/**
 * Wraps an internal [MfFactionRole] as a stable [FactionRoleView].
 *
 * Carries the owning [MfFaction] as well as the role because MF resolves a permission against both:
 * a role that grants nothing explicitly falls back to the faction's own defaults before the
 * permission's built-in default. Answering from the role alone would silently disagree with what MF's
 * commands actually allow.
 */
class FactionRoleViewAdapter(
    private val plugin: MedievalFactions,
    private val faction: MfFaction,
    private val role: MfFactionRole
) : FactionRoleView {

    override val name: String get() = role.name

    override fun hasPermission(permission: FactionPermission): Boolean =
        role.hasPermission(faction, permission.toInternal(plugin.factionPermissions))
}

/**
 * Maps an API permission onto MF's internal one.
 *
 * Written as an exhaustive `when` rather than a lookup by name so that the compiler, not a runtime
 * null, catches it if MF ever renames or drops one of these. That is the whole reason the API owns
 * its own enum instead of passing the internal name string through.
 */
private fun FactionPermission.toInternal(permissions: MfFactionPermissions): MfFactionPermission =
    when (this) {
        FactionPermission.DISBAND -> permissions.disband
        FactionPermission.CHANGE_NAME -> permissions.changeName
        FactionPermission.CHANGE_DESCRIPTION -> permissions.changeDescription
        FactionPermission.CHANGE_PREFIX -> permissions.changePrefix
        FactionPermission.SET_DEFAULT_ROLE -> permissions.setDefaultRole
        FactionPermission.CREATE_ROLE -> permissions.createRole
        FactionPermission.CLAIM -> permissions.claim
        FactionPermission.UNCLAIM -> permissions.unclaim
        FactionPermission.SET_HOME -> permissions.setHome
        FactionPermission.KICK -> permissions.kick
        FactionPermission.APPROVE_APP -> permissions.approveApp
        FactionPermission.DENY_APP -> permissions.denyApp
        FactionPermission.DECLARE_WAR -> permissions.declareWar
        FactionPermission.MAKE_PEACE -> permissions.makePeace
        FactionPermission.MANAGE_SHOPS -> permissions.manageShops
    }
