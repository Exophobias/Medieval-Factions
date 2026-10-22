package com.dansplugins.factionsystem.safety

import com.dansplugins.factionsystem.api.PatriamGovernanceAuthority
import com.dansplugins.factionsystem.exception.EventCancelledException
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.RegisteredServiceProvider
import org.bukkit.plugin.ServicePriority
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.nio.file.Path
import java.util.UUID
import java.util.logging.Logger

class PatriamGovernanceGuardListenerTest {
    @TempDir lateinit var folder: Path

    @Test
    fun `native disable event fences still-enabled authority until a ready enable recovers`() {
        val owner = mock(Plugin::class.java)
        `when`(owner.name).thenReturn("PatriamMFAddon")
        // Paper's disable event precedes clearing this flag and invoking addon onDisable.
        `when`(owner.isEnabled).thenReturn(true)
        var ready = true
        val authority = object : PatriamGovernanceAuthority {
            override fun ready() = ready
            override fun maySeat(playerId: UUID) = true
            override fun commandRefusal(actor: UUID, verb: String): String? = null
        }
        val registration = RegisteredServiceProvider(
            PatriamGovernanceAuthority::class.java,
            authority,
            ServicePriority.Normal,
            owner
        )
        val guard = PatriamGovernanceGuard(
            PatriamAddonFence(folder),
            { registration },
            Logger.getLogger(javaClass.name)
        )
        assertTrue(guard.arm().isSuccess)
        val listener = PatriamGovernanceGuardListener(guard)
        val player = UUID.randomUUID()
        assertNotNull(guard.requireAuthority())
        guard.requireRuler(player)
        assertNull(guard.commandRefusal(player, "dispossess"))

        listener.onPluginDisable(PluginDisableEvent(owner))

        assertTrue(owner.isEnabled)
        assertTrue(authority.ready())
        assertThrows(EventCancelledException::class.java) { guard.requireAuthority() }
        assertThrows(EventCancelledException::class.java) { guard.requireRuler(player) }
        assertNotNull(guard.commandRefusal(player, "transfer"))
        assertNotNull(guard.commandRefusal(player, "dispossess"))

        ready = false
        listener.onPluginEnable(PluginEnableEvent(owner))
        assertThrows(EventCancelledException::class.java) { guard.requireAuthority() }
        assertThrows(EventCancelledException::class.java) { guard.requireRuler(player) }
        assertNotNull(guard.commandRefusal(player, "dispossess"))
        ready = true
        assertNotNull(guard.requireAuthority())
        guard.requireRuler(player)
        assertNull(guard.commandRefusal(player, "dispossess"))
    }

    @Test
    fun `unrelated plugin lifecycle cannot stop or reopen Patriam authority`() {
        val owner = mock(Plugin::class.java)
        `when`(owner.name).thenReturn("PatriamMFAddon")
        `when`(owner.isEnabled).thenReturn(true)
        val other = mock(Plugin::class.java)
        `when`(other.name).thenReturn("OtherPlugin")
        val authority = object : PatriamGovernanceAuthority {
            override fun ready() = true
            override fun maySeat(playerId: UUID) = true
            override fun commandRefusal(actor: UUID, verb: String): String? = null
        }
        val registration = RegisteredServiceProvider(
            PatriamGovernanceAuthority::class.java,
            authority,
            ServicePriority.Normal,
            owner
        )
        val guard = PatriamGovernanceGuard(
            PatriamAddonFence(folder),
            { registration },
            Logger.getLogger(javaClass.name)
        )
        guard.arm()
        val listener = PatriamGovernanceGuardListener(guard)
        listener.onPluginDisable(PluginDisableEvent(other))
        assertNotNull(guard.requireAuthority())
        listener.onPluginDisable(PluginDisableEvent(owner))
        listener.onPluginEnable(PluginEnableEvent(other))
        assertThrows(EventCancelledException::class.java) { guard.requireAuthority() }
    }
}
