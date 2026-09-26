package com.dansplugins.factionsystem.listener

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.MercenaryCombatProvider
import java.util.UUID

/** Resolves the live provider for each event so plugin disable cannot leave a stale callback. */
internal object MercenaryCombatGate {
    fun decide(plugin: MedievalFactions, attacker: UUID, victim: UUID): MercenaryCombatProvider.Decision {
        val provider = plugin.server?.servicesManager?.load(MercenaryCombatProvider::class.java)
            ?: return MercenaryCombatProvider.Decision.ABSTAIN
        // If an installed provider fails, an in-progress mercenary attack must fail closed.
        return runCatching { provider.decide(attacker, victim) }
            .getOrNull() ?: MercenaryCombatProvider.Decision.DENY
    }
}
