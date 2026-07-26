package com.dansplugins.factionsystem.api

/**
 * What a player is trying to do on claimed land.
 *
 * MedievalFactions funnels every territory-protection decision through a single boolean, and until
 * now that boolean could not tell a block break from an inventory click from a PvP hit — all three
 * arrive at [com.dansplugins.factionsystem.claim.MfClaimService.isInteractionAllowed] as the same
 * question. That is fine while MF alone answers it, because MF wants the same answer for all of
 * them.
 *
 * It stops being fine the moment a [ClaimOverrideProvider] can grant an exception. A provider that
 * cannot distinguish these would necessarily hand over chest access along with the right to place a
 * block, which is a far larger grant than any caller intends. Passing the action makes the grant
 * expressible at the granularity people actually reason about.
 *
 * @since the Patriam fork
 */
enum class ClaimAction {

    /** Placing a block. */
    BUILD,

    /** Breaking a block. */
    BREAK,

    /** Right-clicking a block that is not a container: buttons, levers, crafting benches. */
    INTERACT,

    /** Opening a door, gate or trapdoor. */
    DOOR,

    /**
     * Opening a chest, barrel, hopper, furnace or any other inventory holder.
     *
     * Kept deliberately distinct from [INTERACT]. Consumers implementing a provider are strongly
     * advised to refuse this unconditionally: it is the action that turns a limited land exception
     * into unrestricted access to everything the landholder owns, and no plausible use case for a
     * third-party override needs it.
     */
    CONTAINER,

    /** Damaging another player or an entity inside the claim. */
    DAMAGE,

    /** Filling or emptying a bucket. */
    BUCKET,

    /** An explosion destroying blocks in the claim. */
    EXPLODE,

    /**
     * The action is not known.
     *
     * Produced only by the legacy two-argument overload of `isInteractionAllowed`, which predates
     * this enum and is retained so existing call sites keep compiling. **Providers are never
     * consulted for this value**, because an override that cannot see what it is permitting is
     * exactly the unscoped grant the enum exists to prevent.
     */
    UNKNOWN
}
