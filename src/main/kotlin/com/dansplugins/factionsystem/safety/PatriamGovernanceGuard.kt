package com.dansplugins.factionsystem.safety

import com.dansplugins.factionsystem.api.ApiResult
import com.dansplugins.factionsystem.api.PatriamGovernanceAuthority
import com.dansplugins.factionsystem.exception.EventCancelledException
import org.bukkit.plugin.RegisteredServiceProvider
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

/** Native policy boundary retained while PatriamMFAddon's listeners and workers are unavailable. */
class PatriamGovernanceGuard(
    private val fence: PatriamAddonFence,
    private val resolve: () -> RegisteredServiceProvider<PatriamGovernanceAuthority>?,
    private val logger: Logger
) {
    @Volatile private var armFailed = false

    @Volatile private var stopping = false
    private val warned = AtomicBoolean()

    fun arm(): ApiResult = try {
        fence.arm()
        armFailed = false
        ApiResult.success()
    } catch (failure: IOException) {
        armFailed = true
        logger.severe("Could not arm Patriam governance guard; protected actions are refused: ${failure.message}")
        ApiResult.failure("Could not arm Patriam governance guard")
    }

    /** Paper fires PluginDisableEvent while the plugin may still report enabled. Fence it first. */
    fun providerStopping() { stopping = true }
    fun providerEnabled() { stopping = false }

    private fun required(): Boolean = armFailed || fence.required()

    private fun authority(): PatriamGovernanceAuthority? {
        if (armFailed || stopping) return null
        val registration = resolve() ?: return null
        if (registration.plugin.name != "PatriamMFAddon" || !registration.plugin.isEnabled) return null
        return registration.provider.takeIf { it.ready() && !stopping }
    }

    fun requireAuthority(): PatriamGovernanceAuthority? {
        if (!required()) return null
        val available = try { authority() } catch (_: RuntimeException) { null } catch (_: LinkageError) { null }
        if (available == null) {
            if (warned.compareAndSet(false, true)) {
                logger.severe(
                "PatriamMFAddon authority unavailable; wars, founding and protected ruler actions are refused"
            )
            }
            throw EventCancelledException(
                "Faction governance is temporarily unavailable; ask staff to check PatriamMFAddon"
            )
        }
        warned.set(false)
        return available
    }

    fun requireRuler(playerId: UUID) {
        val provider = requireAuthority() ?: return
        val permitted = try { provider.maySeat(playerId) && authority() === provider } catch (_: RuntimeException) { false } catch (_: LinkageError) { false }
        if (!permitted) {
            throw EventCancelledException(
            "A new ruler must have current linked Member eligibility; verification may be unavailable"
        )
        }
    }

    fun commandRefusal(actor: UUID, verb: String): String? = try {
        requireAuthority()?.commandRefusal(actor, verb)
    } catch (failure: RuntimeException) {
        failure.message ?: "Faction governance is temporarily unavailable"
    } catch (_: LinkageError) {
        "Faction governance is temporarily unavailable"
    }
}
