package com.dansplugins.factionsystem.claim

import com.dansplugins.factionsystem.api.ClaimAction
import com.dansplugins.factionsystem.api.ClaimOverrideProvider
import org.bukkit.World
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
     * A provider that throws is disabled for the rest of the session and treated as no-opinion. A
     * misbehaving third-party plugin must not be able to take territory protection down with it.
     */
    fun allows(playerId: UUID, world: World, x: Int, y: Int, z: Int, action: ClaimAction): Boolean {
        if (action == ClaimAction.UNKNOWN || providers.isEmpty()) {
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
            } catch (exception: Exception) {
                poisoned.add(provider)
                logger.log(
                    Level.WARNING,
                    "Claim override provider ${provider.javaClass.name} threw and has been " +
                        "disabled for this session. Territory protection is unaffected.",
                    exception
                )
            }
        }
        return false
    }
}
