package com.dansplugins.factionsystem.api

/**
 * Stable read-only view of a player's role within a faction. v1 exposes the role name, which is
 * enough to gate actions on Owner/Officer/etc. Permission-level queries can be added later without
 * breaking consumers.
 */
interface FactionRoleView {
    val name: String
}
