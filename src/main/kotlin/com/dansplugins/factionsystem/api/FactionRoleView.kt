package com.dansplugins.factionsystem.api

/**
 * Stable read-only view of a player's role within a faction. v1 exposes the role name, which is
 * enough to gate actions on Owner/Officer/etc. Permission-level queries can be added later without
 * breaking consumers.
 */
interface FactionRoleView {
    val name: String

    /**
     * Whether this role may perform [permission].
     *
     * Prefer this to matching [name]. A faction may rename its own roles freely and names are not
     * unique across factions, so a name test lets a faction promote itself by relabelling a junior
     * role; a permission test cannot be fooled that way, because renaming leaves the permission map
     * untouched. See [FactionPermission].
     *
     * Resolution follows MF's internal rules exactly: the role's own grant, else the faction-wide
     * default for that permission, else the permission's built-in default.
     *
     * Denies by default so that adding this member stays additive - an existing implementation of
     * this interface, typically a consumer's test fake, keeps compiling and fails closed.
     */
    fun hasPermission(permission: FactionPermission): Boolean = false
}
