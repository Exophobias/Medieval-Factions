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
    MAKE_PEACE
}
