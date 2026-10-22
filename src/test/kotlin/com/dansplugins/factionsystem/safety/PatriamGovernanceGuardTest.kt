package com.dansplugins.factionsystem.safety

import com.dansplugins.factionsystem.api.PatriamGovernanceAuthority
import com.dansplugins.factionsystem.exception.EventCancelledException
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.RegisteredServiceProvider
import org.bukkit.plugin.ServicePriority
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.logging.Logger

class PatriamGovernanceGuardTest {
    @TempDir lateinit var folder: Path
    private val player = UUID.randomUUID()
    private var registration: RegisteredServiceProvider<PatriamGovernanceAuthority>? = null
    private fun guard() = PatriamGovernanceGuard(PatriamAddonFence(folder), { registration }, Logger.getLogger(javaClass.name))
    private fun provider(
        name: String = "PatriamMFAddon",
        enabled: Boolean = true,
        ready: Boolean = true,
        allowed: Boolean = true,
        duringSeat: () -> Unit = {}
    ) {
        val owner = mock(Plugin::class.java)
        `when`(owner.name).thenReturn(name)
        `when`(owner.isEnabled).thenReturn(enabled)
        val authority = object : PatriamGovernanceAuthority {
            override fun ready() = ready
            override fun maySeat(playerId: UUID): Boolean {
                duringSeat()
                return allowed
            }
            override fun commandRefusal(actor: UUID, verb: String): String? = null
        }
        registration = RegisteredServiceProvider(
            PatriamGovernanceAuthority::class.java,
            authority,
            ServicePriority.Normal,
            owner
        )
    }

    @Test
    fun `unarmed upstream server retains its normal behavior`() {
        guard().requireAuthority()
        guard().requireRuler(player)
        assertNull(guard().commandRefusal(player, "fief.revoke"))
    }

    @Test
    fun `adoption survives restart and absent addon refuses protected writes`() {
        assertTrue(guard().arm().isSuccess)
        assertThrows(EventCancelledException::class.java) { guard().requireAuthority() }
        assertThrows(EventCancelledException::class.java) { guard().requireRuler(player) }
        assertNotNull(guard().commandRefusal(player, "transfer"))
        assertNotNull(guard().commandRefusal(player, "fief.transfer"))
        assertEquals(1L, Files.list(folder).use { it.count() })
    }

    @Test
    fun `wrong disabled and unready providers cannot authorize writes`() {
        val guard = guard()
        guard.arm()
        provider(name = "Imposter")
        assertThrows(EventCancelledException::class.java) { guard.requireAuthority() }
        provider(enabled = false)
        assertThrows(EventCancelledException::class.java) { guard.requireAuthority() }
        provider(ready = false)
        assertThrows(EventCancelledException::class.java) { guard.requireAuthority() }
        provider()
        assertNotNull(guard.requireAuthority())
    }

    @Test
    fun `Paper disable event fences a provider that still reports enabled and ready`() {
        val guard = guard()
        guard.arm()
        provider()
        assertNotNull(guard.requireAuthority())
        guard.providerStopping()
        assertTrue(registration!!.plugin.isEnabled)
        assertTrue(registration!!.provider.ready())
        assertThrows(EventCancelledException::class.java) { guard.requireAuthority() }
        assertThrows(EventCancelledException::class.java) { guard.requireRuler(player) }
        assertNotNull(guard.commandRefusal(player, "kick"))
        assertNotNull(guard.commandRefusal(player, "fief.kick"))
        guard.providerEnabled()
        assertNotNull(guard.requireAuthority())
    }

    @Test
    fun `new rulers require positive Member eligibility and stable live authority`() {
        val guard = guard()
        guard.arm()
        provider(allowed = false)
        assertThrows(EventCancelledException::class.java) { guard.requireRuler(player) }
        provider(allowed = true)
        guard.requireRuler(player)
        provider(allowed = true, duringSeat = { registration = null })
        assertThrows(EventCancelledException::class.java) { guard.requireRuler(player) }
    }

    @Test
    fun `bad adoption marker remains untouched and fails closed with a ready provider`() {
        Files.createDirectories(folder)
        val marker = folder.resolve("patriam-governance-required.marker")
        Files.writeString(marker, "damaged")
        val guard = guard()
        provider()
        assertTrue(guard.arm().isFailure)
        assertThrows(EventCancelledException::class.java) { guard.requireAuthority() }
        assertEquals("damaged", Files.readString(marker))
    }
}
