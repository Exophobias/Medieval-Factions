package com.dansplugins.factionsystem.api

/**
 * A capability a faction role may hold.
 *
 * This exists instead of a rank or an ordinal because MedievalFactions has no rank hierarchy to
 * expose. A faction's roles are a flat list with no order, no level and no owner flag; a role's
 * authority is exactly the set of permissions granted to it, and MF's own commands gate on nothing
 * else. Asking "may this role do X" is therefore the only authority question MF can actually answer.
 *
 * Unlike the role's name, the answer cannot be forged. Renaming a role rewrites its name and nothing
 * else, so a junior role renamed "Owner" still holds a junior permission map. Gaining a permission
 * requires an explicit grant from someone who already holds the right to hand it out, which is a real
 * delegation of authority rather than an impersonation of one.
 *
 * Only MF's simple permissions are listed. The parameterised ones - chat channels, faction flags, and
 * the per-role view/modify/delete/set family - carry an internal id in their argument, so exposing
 * them would leak MF's model through the API seam this package exists to keep closed.
 *
 * Constants may be added in later versions. Consumers should not treat this set as complete, and
 * should not write exhaustive `when` blocks over it.
 */
enum class FactionPermission {

    /**
     * Dissolve the faction outright.
     *
     * The terminal authority: nothing outranks the power to destroy the thing being governed. It
     * defaults to denied for every role and for the faction-wide fallback, so it is never held by
     * accident, which is what makes it a sound test for "highest authority" - see [FactionView.isLeader].
     */
    DISBAND,

    CHANGE_NAME,
    CHANGE_DESCRIPTION,
    CHANGE_PREFIX,

    /** Choose which role new members are given on joining. */
    SET_DEFAULT_ROLE,
    CREATE_ROLE,

    CLAIM,
    UNCLAIM,
    SET_HOME,

    KICK,
    APPROVE_APP,
    DENY_APP,

    DECLARE_WAR,
    MAKE_PEACE,

    /**
     * Run the faction's commercial estate: create a shop stall, stock it, set and change its prices,
     * and close it down.
     *
     * MedievalFactions itself does nothing with this. No MF command reads it, no MF listener consults
     * it, and removing every consumer would change no MF behaviour. It exists here so that a faction
     * can delegate trade the same way it delegates war or land, through one grant list that the
     * faction already knows how to edit, rather than through a second permission model living inside
     * whichever plugin happens to own the shops. PatriamEconomy is the consumer.
     *
     * Read it the way any other constant here is read, through
     * [FactionRoleView.hasPermission]. There is no MF-side helper.
     *
     * ## Why this had to ship before MedievalFactions was first deployed
     *
     * A faction that already exists in the database can never be given this permission, by anybody,
     * including an operator. Two separate creation-time snapshots are responsible, and both were
     * taken before this constant existed:
     *
     * - The Owner role's grant list is built once in `MfFactionRoles.defaults`, at the moment the
     *   faction is created, and includes a `SET_ROLE_PERMISSION(x)` entry for every permission MF knew
     *   about then. An older faction's Owner therefore holds no `SET_ROLE_PERMISSION(MANAGE_SHOPS)`.
     * - The faction's own `default_permissions` JSON column is likewise an associate over the
     *   permission list as it stood at creation, so it has no `MANAGE_SHOPS` key and no
     *   `SET_ROLE_PERMISSION(MANAGE_SHOPS)` key either.
     *
     * `MfFactionRole.hasPermission` resolves role grant, then faction default, then the permission's
     * built-in default. All three miss, and the built-in default is false, so `/f role setpermission`
     * refuses the grant for want of `SET_ROLE_PERMISSION(MANAGE_SHOPS)` and there is no operator
     * bypass anywhere in that path. The only escape is a database migration that backfills both
     * snapshots.
     *
     * MedievalFactions was not yet deployed when this landed, so every faction is created after the
     * constant exists and pays nothing. Do not delete this on the grounds that nothing reads it yet:
     * re-adding it later is a migration, not an enum edit.
     */
    MANAGE_SHOPS
}
