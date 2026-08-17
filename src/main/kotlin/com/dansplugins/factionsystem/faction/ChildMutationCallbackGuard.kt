package com.dansplugins.factionsystem.faction

import com.dansplugins.factionsystem.MedievalFactions
import org.bukkit.event.Event

/**
 * Marks synchronous plugin callbacks made while a child service still owns its mutation lock.
 *
 * Claim and relationship gates must remain inside those locks so the state they approve cannot
 * change before persistence. A callback must not synchronously disband a faction, though: deletion
 * acquires every child fence and two callbacks originating in different child locks can otherwise
 * wait claim -> relationship and relationship -> claim forever. Callers may schedule the disband
 * after the event returns, when this depth is zero and no child lock is retained.
 */
internal object ChildMutationCallbackGuard {

    private val depth = ThreadLocal.withInitial { 0 }

    fun isActive(): Boolean = depth.get() > 0

    fun callEvent(plugin: MedievalFactions, event: Event) {
        depth.set(depth.get() + 1)
        try {
            plugin.server.pluginManager.callEvent(event)
        } finally {
            val remaining = depth.get() - 1
            check(remaining >= 0) { "Child mutation callback depth underflow" }
            if (remaining == 0) depth.remove() else depth.set(remaining)
        }
    }
}
