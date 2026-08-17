package com.dansplugins.factionsystem.api

import java.util.UUID

/**
 * Lets another plugin decide who inherits a faction whose recorded head has departed, in place of
 * MedievalFactions' built-in ladder.
 *
 * The motivating case is a server that models government: a realm whose form is elective wants its
 * remaining leaders to vote rather than have the longest-standing member handed the crown, and a
 * realm under a regency wants the regent seated rather than a successor chosen outright. MF has no
 * concept of either and should not acquire one.
 *
 * ## When this is consulted
 *
 * Only at the moment a faction's [FactionView.primaryOwnerId] names somebody who is no longer on the
 * member list, which MF reconciles on every save. That covers leaving, being kicked, and anything
 * else that rewrites the member list. It is NOT consulted for a head who is merely offline, idle or
 * declared absent, because MF does not model absence and succession here means departure.
 *
 * Disbanding never consults a policy: the faction row is deleted outright and there is nothing to
 * inherit.
 *
 * ## Deferring is the normal answer
 *
 * Return `null` to defer, and MF's own three-tier ladder applies (the departing head's nominee, then
 * the longest-standing member who may disband, then the longest-standing member holding the most
 * authority). A policy is expected to defer for most factions — typically every faction it does not
 * govern — and deferring must always be safe, so a policy that cannot reach its own state should
 * defer rather than guess.
 *
 * ## A policy can choose, but it cannot empty a seat
 *
 * The answer is validated before it is used. It must be a current member of that faction and must
 * not be the departing head. An answer failing either test is discarded and treated as a deferral,
 * so an implementation cannot seat an outsider, cannot reinstate the player who just left, and
 * cannot leave a faction headless when the server forbids that. `factions.allowLeaderlessFactions`
 * remains MF's own call and no policy can override it in either direction.
 *
 * This is the same asymmetry as [ClaimOverrideProvider]: a third-party plugin may redirect one of
 * MF's decisions, never invalidate the invariant underneath it.
 *
 * ## The proposal must not block or save
 *
 * This is consulted while `MfFactionService.save` is preparing a mutation, on whichever thread
 * called it. Answer from memory. Do not touch the database, do not call back into MF's services, and
 * above all do not save a faction from here — commit arbitration has not happened yet.
 *
 * A policy that must durably coordinate its own state with the faction commit can additionally
 * implement [TransactionalSuccessionPolicy]. Its validated answer then receives a separate
 * provisional prepare/commit/abort lifecycle; side effects do not belong in this proposal method.
 *
 * To *react* to a succession rather than decide one, listen for
 * [com.dansplugins.factionsystem.api.event.FactionPrimaryOwnerChangedEvent], which is fired after
 * the save completes and is safe to act on.
 *
 * ## Failure is contained
 *
 * A policy that throws anything at all, including the [NoClassDefFoundError] a policy built against
 * a since-changed class produces, is caught, logged once, disabled for the session, and treated as a
 * deferral. A broken third-party plugin must not be able to stop players leaving factions.
 *
 * @since the Patriam fork
 */
fun interface SuccessionPolicy {

    /**
     * Who should inherit [faction], or null to defer to MedievalFactions' own ladder.
     *
     * @param faction the faction as it stands at the moment of vacancy: [FactionView.primaryOwnerId]
     * still names [departingHead], and [FactionView.memberIds] no longer contains them
     * @param departingHead the head who has left the member list
     * @return a current member of [faction] other than [departingHead], or null to defer. Any other
     * answer is discarded as though null had been returned.
     */
    fun successorFor(faction: FactionView, departingHead: UUID): UUID?
}
