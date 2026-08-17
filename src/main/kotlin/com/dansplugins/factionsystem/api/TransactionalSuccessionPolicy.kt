package com.dansplugins.factionsystem.api

import java.util.UUID

/**
 * Optional crash-recovery companion to [SuccessionPolicy].
 *
 * [SuccessionPolicy.successorFor] remains a fast, side-effect-free proposal. After MedievalFactions
 * validates that proposal, it calls [prepareSuccession] before attempting the faction commit. A
 * policy that must durably remember the constitutional transition can write a *provisional* marker
 * here and return a token which will be completed or aborted after commit arbitration.
 *
 * The marker must distinguish PREPARED from committed work and be recoverable after a crash. A save
 * can stop after preparation but before the database commit, or after the database commit but before
 * [PreparedSuccession.committed]. Implementations should persist [preparationId] with enough exact
 * source identity to tell those cases apart on restart. While the old save is still running—even
 * across an add-on-only reload—[SuccessionPreparationFence.isActive] keeps a new policy instance
 * from resolving that marker prematurely. Preparation may block on that small durable write; it
 * must not call back into MedievalFactions or wait for the server thread.
 *
 * Returning null refuses this save. MedievalFactions fails the mutation instead of silently using a
 * different succession rule after a policy already selected a valid answer.
 */
interface TransactionalSuccessionPolicy : SuccessionPolicy {

    fun prepareSuccession(
        preparationId: UUID,
        faction: FactionView,
        departingHead: UUID,
        successor: UUID,
        successorTerm: UUID
    ): PreparedSuccession?
}
