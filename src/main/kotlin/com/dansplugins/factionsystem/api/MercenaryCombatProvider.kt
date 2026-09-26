package com.dansplugins.factionsystem.api

import java.util.UUID

/**
 * A server-wide override for damage between players in different factions.
 *
 * Register an implementation with Bukkit's ServicesManager while the providing plugin is enabled.
 * MedievalFactions consults the highest-priority registered provider after its duel and friendly-fire
 * rules and before its ordinary war requirement. Implementations should use in-memory state only:
 * this callback runs inline on the damage event thread and may be called for every affected player
 * in a potion splash or area-effect cloud.
 */
fun interface MercenaryCombatProvider {
    fun decide(attacker: UUID, victim: UUID): Decision

    enum class Decision {
        /** Permit damage even without an MF war relationship. */
        ALLOW,

        /** Refuse damage even when MF has a war relationship. */
        DENY,

        /** Leave the result to MF's ordinary war requirement. */
        ABSTAIN
    }
}
