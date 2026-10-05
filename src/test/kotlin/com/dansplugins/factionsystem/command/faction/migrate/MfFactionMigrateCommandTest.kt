package com.dansplugins.factionsystem.command.faction.migrate

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.TestUtils
import com.dansplugins.factionsystem.lang.Language
import net.md_5.bungee.api.chat.BaseComponent
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.FileConfiguration
import org.mockito.Answers
import org.mockito.stubbing.Answer
import org.bukkit.ChatColor.RED
import org.bukkit.ChatColor.YELLOW
import org.bukkit.Server
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doCallRealMethod
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/**
 * Covers the guard rails on `/mf migrate` — the branches that run on the calling thread and never touch
 * a data source. The `toJson` and `toDatabase` paths hand off to the scheduler and open a real database
 * connection, so they are out of scope here.
 *
 * Documentation uses the shared permission-filtered renderer; migration safety errors retain their
 * existing explicit English messages.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MfFactionMigrateCommandTest {
    private val testUtils = TestUtils()

    private lateinit var fixture: TestUtils.CommandTestFixture
    private lateinit var plugin: MedievalFactions
    private lateinit var uut: MfFactionMigrateCommand
    private val helpMessages = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        fixture = testUtils.createCommandTestFixture()
        plugin = mock(MedievalFactions::class.java)
        helpMessages.clear()
        val language = mock(Language::class.java, Answer { invocation ->
            if (invocation.method.returnType == String::class.java) invocation.arguments.firstOrNull()?.toString().orEmpty()
            else Answers.RETURNS_DEFAULTS.answer(invocation)
        })
        `when`(plugin.language).thenReturn(language)
        `when`(plugin.config).thenReturn(mock(FileConfiguration::class.java))
        `when`(fixture.sender.spigot()).thenReturn(object : CommandSender.Spigot() {
            override fun sendMessage(component: BaseComponent) { helpMessages += component.toPlainText() }
            override fun sendMessage(vararg components: BaseComponent) { components.forEach { sendMessage(it) } }
        })
        uut = MfFactionMigrateCommand(plugin)
    }

    @Test
    fun `rejects a sender without the mf migrate permission`() {
        val sender = fixture.sender
        `when`(sender.hasPermission("mf.migrate")).thenReturn(false)

        val result = uut.onCommand(sender, fixture.command, "label", arrayOf("toJson"))

        assertTrue(result)
        verify(sender).sendMessage("$RED You do not have permission to use this command.")
        // Crucially, no migration may be started for an unauthorised sender.
        verify(plugin, never()).server
    }

    @Test
    fun `prints permitted staff help and starts nothing when no type is given`() {
        val sender = fixture.sender
        `when`(sender.hasPermission("mf.migrate")).thenReturn(true)

        val result = uut.onCommand(sender, fixture.command, "label", arrayOf())

        assertTrue(result)
        assertTrue(helpMessages.first().contains("Medieval Factions Staff"))
        assertTrue(helpMessages.any { it.startsWith("/f migrate toJson - ") })
        assertTrue(helpMessages.any { it.startsWith("/f migrate toDatabase - ") })
        assertTrue(helpMessages.last().all { it == '-' })
        verify(plugin, never()).server
    }

    @Test
    fun `rejects an unrecognised migration type`() {
        val sender = fixture.sender
        `when`(sender.hasPermission("mf.migrate")).thenReturn(true)

        val result = uut.onCommand(sender, fixture.command, "label", arrayOf("toXml"))

        assertTrue(result)
        verify(sender).sendMessage("§cUnknown subcommand.")
        assertTrue(helpMessages.first().contains("Medieval Factions Staff"))
        assertTrue(helpMessages.any { it.startsWith("/f migrate toJson - ") })
        verify(plugin, never()).server
    }

    @ParameterizedTest
    @ValueSource(strings = ["PatriamMFAddon", "PatriamTesting"])
    fun `rejects JSON migration before scheduling while a Patriam integration is installed`(blocker: String) {
        val sender = fixture.sender
        `when`(sender.hasPermission("mf.migrate")).thenReturn(true)
        val server = mock(Server::class.java)
        val pluginManager = mock(PluginManager::class.java)
        `when`(plugin.server).thenReturn(server)
        `when`(server.pluginManager).thenReturn(pluginManager)
        `when`(pluginManager.getPlugin(blocker)).thenReturn(mock(Plugin::class.java))
        doCallRealMethod().`when`(plugin).jsonStorageBlocker()

        assertTrue(uut.onCommand(sender, fixture.command, "label", arrayOf("toJson")))

        verify(sender).sendMessage(
            "$RED JSON storage is unavailable while $blocker is installed; " +
                "Patriam integrations require database storage."
        )
        verify(server, never()).scheduler
    }

    @Test
    fun `does not leak the usage text to a sender without permission`() {
        val sender = fixture.sender
        `when`(sender.hasPermission("mf.migrate")).thenReturn(false)

        uut.onCommand(sender, fixture.command, "label", arrayOf())

        verify(sender).sendMessage(anyString())
        verify(sender).sendMessage("$RED You do not have permission to use this command.")
    }

    @Test
    fun `tab completes permitted help and migration types and filters case-insensitively`() {
        val sender = fixture.sender
        `when`(sender.hasPermission("mf.migrate")).thenReturn(true)

        assertEquals(
            listOf("help", "toJson", "toDatabase"),
            uut.onTabComplete(sender, fixture.command, "label", arrayOf(""))
        )
        assertEquals(
            listOf("toDatabase"),
            uut.onTabComplete(sender, fixture.command, "label", arrayOf("tod"))
        )
        assertTrue(uut.onTabComplete(sender, fixture.command, "label", arrayOf("xyz")).isEmpty())
        // Only the first argument is completed.
        assertTrue(uut.onTabComplete(sender, fixture.command, "label", arrayOf("toJson", "")).isEmpty())
    }
}
