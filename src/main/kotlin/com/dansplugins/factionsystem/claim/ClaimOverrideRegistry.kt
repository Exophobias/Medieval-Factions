package com.dansplugins.factionsystem.claim

import com.dansplugins.factionsystem.api.ClaimAction
import com.dansplugins.factionsystem.api.ClaimOverrideProvider
import org.bukkit.World
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Holds the registered [ClaimOverrideProvider]s and asks them, safely.
 *
 * Separate from [MfClaimService] so the protection logic stays readable and so the failure handling
 * below lives in exactly one place rather than being repeated at every consult site.
 *
 * Backed by a [CopyOnWriteArrayList]: registration happens a handful of times at startup, reads
 * happen on the hot protection path, and copy-on-write makes the read path allocation-free with no
 * lock at all.
 */
class ClaimOverrideRegistry(private val logger: Logger) {

    private val providers = CopyOnWriteArrayList<ClaimOverrideProvider>()

    /** Providers that have already thrown, so a broken one is logged once rather than every tick. */
    private val poisoned = CopyOnWriteArrayList<ClaimOverrideProvider>()

    fun register(provider: ClaimOverrideProvider) {
        if (!providers.contains(provider)) {
            providers.add(provider)
            logger.info("Registered claim override provider: ${provider.javaClass.name}")
        }
    }

    fun unregister(provider: ClaimOverrideProvider) {
        if (providers.remove(provider)) {
            poisoned.remove(provider)
            logger.info("Unregistered claim override provider: ${provider.javaClass.name}")
        }
    }

    fun isEmpty(): Boolean = providers.isEmpty()

    /**
     * Whether any provider permits this action.
     *
     * [ClaimAction.UNKNOWN] always returns false without consulting anyone: an override that cannot
     * see what it is permitting is precisely the unscoped grant this whole mechanism exists to
     * avoid, and the legacy two-argument protection call is the only thing that produces it.
     *
     * The actions in [HARD_EXCLUDED] are refused the same way, before anyone is asked.
     *
     * A provider that throws is disabled for the rest of the session and treated as no-opinion. A
     * misbehaving third-party plugin must not be able to take territory protection down with it.
     */
    fun allows(playerId: UUID, world: World, x: Int, y: Int, z: Int, action: ClaimAction): Boolean {
        if (action == ClaimAction.UNKNOWN || action in HARD_EXCLUDED || providers.isEmpty()) {
            return false
        }
        for (provider in providers) {
            if (poisoned.contains(provider)) {
                continue
            }
            try {
                if (provider.allows(playerId, world, x, y, z, action)) {
                    return true
                }
            } catch (throwable: Throwable) {
                // Throwable, not Exception. The realistic failure here is not a bug in a provider's
                // own logic but a LinkageError: a provider compiled against a class the installed
                // jar no longer has throws NoClassDefFoundError, which is an Error. Caught as
                // Exception it walks straight out of allows, out of isOverridden, out of the
                // listener's `if`, and the protection branch never runs -- protection fails OPEN.
                //
                // Nothing is rethrown, deliberately. Swallowing it here means MF's own denial
                // stands, which is the safe direction; rethrowing would leave the calling listener
                // half-executed with the cancel not applied.
                poisoned.add(provider)
                logger.log(
                    // SEVERE rather than WARNING: an Error on this path is a broken installation,
                    // not merely a misbehaving plugin.
                    Level.SEVERE,
                    "Claim override provider ${provider.javaClass.name} threw and has been " +
                        "disabled for this session. Territory protection is unaffected.",
                    throwable
                )
            }
        }
        return false
    }

    private companion object {

        /**
         * Actions no provider may be granted, refused before the registry consults anyone.
         *
         * CONTAINER hands over every chest, barrel, hopper and furnace the landholder owns, which is
         * a far larger grant than any land exception intends. DAMAGE would make a carve-out a
         * rentable forward base on a war server, attackers able to fight and defenders not. EXPLODE
         * would let an exception blow up somebody else's claim.
         *
         * A well-behaved provider refuses all three anyway; the point of doing it here is that a
         * future third-party one cannot fail to. This is the structural half of the guarantee the
         * KDoc on [ClaimOverrideProvider] previously only advised.
         *
         * It lives in the registry rather than in [MfClaimService.isOverridden] so it sits in
         * exactly one place and holds for any future caller of the registry.
         */
        val HARD_EXCLUDED: Set<ClaimAction> =
            EnumSet.of(ClaimAction.CONTAINER, ClaimAction.DAMAGE, ClaimAction.EXPLODE)
    }
}
