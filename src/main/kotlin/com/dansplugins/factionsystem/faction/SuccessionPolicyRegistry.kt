package com.dansplugins.factionsystem.faction

import com.dansplugins.factionsystem.api.FactionView
import com.dansplugins.factionsystem.api.SuccessionPolicy
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Holds the registered [SuccessionPolicy] implementations, asks them safely, and validates what they
 * answer.
 *
 * Separate from [MfFactionService] for the same reasons
 * [com.dansplugins.factionsystem.claim.ClaimOverrideRegistry] is separate from the claim service: the
 * succession rule stays readable, and the failure handling and the answer validation live in exactly
 * one place rather than at every consult site. This is deliberately the same shape as that class, so
 * that the two third-party extension points behave alike.
 *
 * Backed by a [CopyOnWriteArrayList]. Registration happens a handful of times at startup and reads
 * happen only when a head actually departs, which is rare, so copy-on-write costs nothing and needs
 * no lock.
 */
class SuccessionPolicyRegistry(private val logger: Logger) {

    private val policies = CopyOnWriteArrayList<SuccessionPolicy>()

    /** Policies that have already thrown, so a broken one is logged once rather than every departure. */
    private val poisoned = CopyOnWriteArrayList<SuccessionPolicy>()

    fun register(policy: SuccessionPolicy) {
        if (!policies.contains(policy)) {
            policies.add(policy)
            logger.info("Registered succession policy: ${policy.javaClass.name}")
        }
    }

    fun unregister(policy: SuccessionPolicy) {
        if (policies.remove(policy)) {
            poisoned.remove(policy)
            logger.info("Unregistered succession policy: ${policy.javaClass.name}")
        }
    }

    fun isEmpty(): Boolean = policies.isEmpty()

    /**
     * The successor the first willing policy names, or null if none names a usable one.
     *
     * Null means "MF's own ladder applies", and it is returned for all three of the ways that can
     * happen: no policy is registered, every policy deferred, and a policy answered with somebody it
     * is not allowed to seat. Collapsing those into one answer is intentional — every one of them
     * has the same correct handling, and a caller that could tell them apart would be tempted to
     * treat the third as an error worth failing the save over, which would let a third-party bug
     * stop players leaving factions.
     *
     * ## What the validation is actually protecting
     *
     * [memberIds] is read from the faction as it stands at the moment of vacancy, so a policy cannot
     * seat somebody who was never in the faction, and cannot reinstate the head who just departed.
     * Both would otherwise be reachable: the first hands a stranger a faction's land and treasury,
     * and the second makes leaving impossible, since the save would keep restoring the head the
     * member list no longer contains.
     *
     * A policy is NOT permitted to answer "nobody". There is no way to express it, deliberately.
     * Whether a faction may exist without a head is governed by `factions.allowLeaderlessFactions`,
     * which is MF's own invariant, and a third-party plugin that could force a headless faction
     * would be overriding a server owner's setting from outside.
     */
    fun successorFor(faction: FactionView, departingHead: UUID): UUID? {
        if (policies.isEmpty()) {
            return null
        }
        for (policy in policies) {
            if (poisoned.contains(policy)) {
                continue
            }
            val answer = try {
                policy.successorFor(faction, departingHead)
            } catch (throwable: Throwable) {
                // Throwable, not Exception, and for the same reason ClaimOverrideRegistry catches
                // Throwable: the realistic failure is a LinkageError from a policy compiled against
                // a class the installed jar no longer has, which is an Error. Uncaught, it would
                // propagate out of MfFactionService.save and fail an ordinary /f leave.
                poisoned.add(policy)
                logger.log(
                    Level.SEVERE,
                    "Succession policy ${policy.javaClass.name} threw and has been disabled for " +
                        "this session. Succession falls back to MedievalFactions' own order.",
                    throwable
                )
                continue
            }
            if (answer != null && answer != departingHead && faction.memberIds.contains(answer)) {
                return answer
            }
            if (answer != null) {
                logger.warning(
                    "Succession policy ${policy.javaClass.name} named $answer to inherit faction " +
                        "${faction.id.value}, who is " +
                        (if (answer == departingHead) "the departing head" else "not a member") +
                        ". Ignoring it; MedievalFactions' own succession order applies."
                )
            }
        }
        return null
    }
}
