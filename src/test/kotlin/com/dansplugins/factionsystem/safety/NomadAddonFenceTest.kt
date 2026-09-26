package com.dansplugins.factionsystem.safety

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class NomadAddonFenceTest {
    @TempDir lateinit var plugins: Path

    private fun fence() = NomadAddonFence(plugins.resolve("MedievalFactions"))
    private fun camps() = plugins.resolve("PatriamNomads/camps")
    private fun identities() = camps().resolve("identities.manifest")
    private fun marker() = plugins.resolve("MedievalFactions/nomad-addon-required.marker")

    private fun createCampManifest() {
        Files.createDirectories(camps())
        Files.writeString(identities(), "empty fixture")
    }

    @Test
    fun `unarmed server does not require the addon and cannot arm without its data directory`() {
        val fence = fence()
        assertFalse(fence.armed())
        assertNull(fence.startupIssue(false))
        assertThrows(IOException::class.java) { fence.arm() }
        Files.createDirectories(camps())
        assertThrows(IOException::class.java) { fence.arm() }
        assertFalse(Files.exists(marker()))
    }

    @Test
    fun `successful adoption latch survives restart and checks both startup phases`() {
        createCampManifest()
        fence().arm()

        val restarted = fence()
        assertTrue(restarted.armed())
        assertEquals("PatriamNomads is missing", restarted.startupIssue(false))
        assertNull(restarted.startupIssue(true))
        assertEquals("PatriamNomads did not enable", restarted.startupIssue(true, false))
        assertNull(restarted.startupIssue(true, true))
        assertEquals(1, Files.list(marker().parent).use { it.count() })
    }

    @Test
    fun `losing the Nomads data directory is detected even when the jar remains`() {
        createCampManifest()
        fence().arm()
        Files.delete(identities())
        assertEquals("PatriamNomads camp identity manifest is missing", fence().startupIssue(true, true))
        Files.delete(camps())
        assertEquals("PatriamNomads camp data directory is missing", fence().startupIssue(true, true))
    }

    @Test
    fun `bad marker is never mistaken for an unarmed server or overwritten`() {
        createCampManifest()
        Files.createDirectories(marker().parent)
        Files.writeString(marker(), "corrupt")
        val fence = fence()
        assertThrows(IOException::class.java) { fence.startupIssue(true, true) }
        assertThrows(IOException::class.java) { fence.arm() }
        assertEquals("corrupt", Files.readString(marker()))
    }

    @Test
    fun `arming is idempotent and final disband cannot silently remove the permanent latch`() {
        createCampManifest()
        val fence = fence()
        fence.arm()
        val bytes = Files.readAllBytes(marker())
        fence.arm()
        assertTrue(bytes.contentEquals(Files.readAllBytes(marker())))
        assertNull(fence.startupIssue(true, true)) // Empty camps directory after final disband.
        assertTrue(fence.armed())
    }
}
