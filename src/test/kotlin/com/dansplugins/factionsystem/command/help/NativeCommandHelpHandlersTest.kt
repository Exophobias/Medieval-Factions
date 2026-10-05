package com.dansplugins.factionsystem.command.help

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.command.accessors.MfAccessorsCommand
import com.dansplugins.factionsystem.command.duel.MfDuelCommand
import com.dansplugins.factionsystem.command.faction.MfFactionCommand
import com.dansplugins.factionsystem.command.faction.admin.MfFactionAdminCommand
import com.dansplugins.factionsystem.command.faction.claim.MfFactionClaimCommand
import com.dansplugins.factionsystem.command.faction.dev.MfFactionDevCommand
import com.dansplugins.factionsystem.command.faction.dev.MfFactionDevGenerateCommand
import com.dansplugins.factionsystem.command.faction.embassy.MfFactionEmbassyCommand
import com.dansplugins.factionsystem.command.faction.law.MfFactionLawCommand
import com.dansplugins.factionsystem.command.faction.set.displayname.MfFactionSetDisplayNameCommand
import com.dansplugins.factionsystem.command.gate.MfGateCommand
import com.dansplugins.factionsystem.command.gate.cancel.MfGateCancelCommand
import com.dansplugins.factionsystem.command.power.MfPowerCommand
import com.dansplugins.factionsystem.lang.Language
import net.md_5.bungee.api.ChatColor
import net.md_5.bungee.api.chat.BaseComponent
import org.bukkit.Server
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.ServicesManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Answers
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*
import org.mockito.stubbing.Answer
import java.util.Locale

/** Exercises the actual roots/routers before any player, faction, scheduler or world context. */
class NativeCommandHelpHandlersTest {
    private lateinit var plugin: MedievalFactions
    private lateinit var config: FileConfiguration
    private lateinit var server: Server
    private lateinit var command: Command
    private lateinit var sender: CommandSender
    private val permissions = mutableSetOf<String>()
    private val text = mutableListOf<String>()
    private val components = mutableListOf<BaseComponent>()

    @BeforeEach fun setup() {
        plugin = mock(MedievalFactions::class.java)
        server = mock(Server::class.java)
        config = mock(FileConfiguration::class.java)
        command = mock(Command::class.java)
        val services = mock(ServicesManager::class.java)
        `when`(plugin.server).thenReturn(server)
        `when`(server.servicesManager).thenReturn(services)
        `when`(services.getRegistrations(com.dansplugins.factionsystem.api.FactionSubcommandExtension::class.java)).thenReturn(emptyList())
        `when`(plugin.config).thenReturn(config)
        val language = mock(Language::class.java, Answer { invocation ->
            if (invocation.method.returnType == String::class.java) invocation.arguments.firstOrNull()?.toString().orEmpty()
            else Answers.RETURNS_DEFAULTS.answer(invocation)
        })
        `when`(language.locale).thenReturn(Locale.US)
        `when`(plugin.language).thenReturn(language)
        sender = recording(CommandSender::class.java)
    }

    private fun <T : CommandSender> recording(type: Class<T>): T {
        val value = mock(type)
        `when`(value.hasPermission(anyString())).thenAnswer { it.arguments[0] in permissions }
        doAnswer { text += it.arguments[0].toString(); null }.`when`(value).sendMessage(anyString())
        fun capture(component: BaseComponent) { text += component.toPlainText(); components += component }
        val spigot = if (type == Player::class.java) object : Player.Spigot() {
            override fun sendMessage(component: BaseComponent) = capture(component)
            override fun sendMessage(vararg values: BaseComponent) { values.forEach(::capture) }
        } else object : CommandSender.Spigot() {
            override fun sendMessage(component: BaseComponent) = capture(component)
            override fun sendMessage(vararg values: BaseComponent) { values.forEach(::capture) }
        }
        doReturn(spigot).`when`(value).spigot()
        return value
    }
    private fun run(handler: CommandExecutor, vararg args: String) = handler.onCommand(sender, command, "f", args)
    private fun rows() = text.filter { it.startsWith("/") && " - " in it }
    private fun clear() { text.clear(); components.clear() }

    @Test fun rootNoArgumentsExplicitHelpUnknownAndPageClampingUsePermittedConsoleDocs() {
        permissions += setOf("mf.help", "mf.invite")
        val root = MfFactionCommand(plugin)
        run(root); val first = text.toList(); assertTrue(rows().any { it.startsWith("/f invite ") })
        assertTrue(text.first().contains("Medieval Factions")); assertTrue(text.last().all { it == '-' })
        clear(); run(root, "help"); assertEquals(first, text)
        for (page in listOf("0", "99", "banana")) { clear(); run(root, "help", page); assertEquals(first, text) }
        clear(); run(root, "unknown"); assertTrue(text.first().contains("Unknown subcommand")); assertEquals(first, text.drop(1))
        assertFalse(text.any { "Staff" in it })
        assertFalse(root.onTabComplete(sender, command, "f", arrayOf("help", "")).orEmpty().contains("staff"))
        assertEquals(setOf("help", "invite"), root.onTabComplete(sender, command, "f", arrayOf("")).orEmpty().toSet().filterNot { it.startsWith("Cmd") }.toSet())
    }

    @Test fun actualHelpKeepsEightRowsReadableColoursAndSeparatePartiallyPermittedStaffPages() {
        permissions += setOf("mf.help", "mf.invite", "mf.create", "mf.home", "mf.info", "mf.list", "mf.map", "mf.power", "mf.members", "mf.who", "mf.admin.create")
        val root = MfFactionCommand(plugin)
        run(root, "help"); assertEquals(8, rows().size); assertTrue(text.last().contains("/f help 2 for more"))
        assertFalse(root.onTabComplete(sender, command, "f", arrayOf("help", "")).orEmpty().contains("staff"))
        permissions += "mf.admin"
        clear(); run(root, "help", "staff", "99")
        assertTrue(text.first().contains("Medieval Factions Staff")); assertTrue(rows().all { it.startsWith("/f admin") })
        assertTrue(rows().any { " create " in it }); assertFalse(rows().any { "setleader" in it || "protection" in it })
        assertTrue(text.last().all { it == '-' }); assertTrue(root.onTabComplete(sender, command, "f", arrayOf("help", "")).orEmpty().contains("staff"))
        val leafParts = components.flatMap { it.extra.orEmpty() }
        assertTrue(leafParts.any { it.toPlainText().startsWith("/f admin create") && it.color == ChatColor.GREEN })
        assertTrue(leafParts.any { it.toPlainText().startsWith("Manage") && it.color == ChatColor.GRAY })
        clear(); run(root, "help", "999"); assertTrue(text.first().contains("Staff")); assertFalse(rows().any { it.startsWith("/f invite") })
    }

    @Test fun viewerWithoutStaffCannotReadStaffOrObtainTargetSuggestionsAndExecutionStillDenies() {
        permissions += setOf("mf.help", "mf.invite")
        val root = MfFactionCommand(plugin)
        run(root, "help", "staff"); assertEquals(listOf("§cYou may not use that."), text)
        assertTrue(root.onTabComplete(sender, command, "f", arrayOf("admin", "create", "")).orEmpty().isEmpty())
        clear(); run(root, "admin", "create", "Nation"); assertTrue(text.isNotEmpty()); assertFalse(text.any { "Created" in it })
        assertTrue(root.onTabComplete(sender, command, "f", arrayOf("declarewar", "")).orEmpty().isEmpty())
    }

    @Test fun standaloneRootsConsoleHelpAndStaffOnlyPowerHaveNoPlayerContextOrEmptySection() {
        permissions += setOf("mf.grantaccess", "mf.duel", "mf.gate", "mf.power.set")
        val handlers: List<CommandExecutor> = listOf(MfAccessorsCommand(plugin), MfDuelCommand(plugin), MfGateCommand(plugin), MfPowerCommand(plugin))
        handlers.forEach { handler -> clear(); run(handler); assertTrue(text.first().contains("Medieval Factions")); assertTrue(rows().isNotEmpty()); assertTrue(rows().size <= 8); assertTrue(text.last().all { it == '-' }) }
        assertTrue(text.first().contains("Staff")); assertEquals(1, rows().size)
        val accessors = handlers.first() as TabCompleter
        assertTrue(accessors.onTabComplete(sender, command, "accessors", arrayOf("")).orEmpty().contains("add"))
        assertFalse(accessors.onTabComplete(sender, command, "accessors", arrayOf("")).orEmpty().contains("remove"))
        assertTrue(accessors.onTabComplete(sender, command, "accessors", arrayOf("remove", "")).orEmpty().isEmpty())
        clear(); run(handlers.first(), "add", "Target"); assertTrue(text.any { "NotAPlayer" in it })
    }

    @Test fun deniedGateCancelAndDeveloperCommandsCannotReachSchedulerOrMutateWorld() {
        sender = recording(Player::class.java)
        run(MfGateCancelCommand(plugin)); assertTrue(text.first().contains("may not use"))
        clear(); run(MfFactionDevGenerateCommand(plugin)); assertTrue(text.first().contains("may not use"))
        permissions += "mf.admin"
        clear(); run(MfFactionDevCommand(plugin), "generate"); assertTrue(text.first().contains("may not use"))
        verify(server, never()).scheduler
        `when`(config.getBoolean("dev.enableDevCommands")).thenReturn(true)
        clear(); run(MfFactionDevCommand(plugin), "help"); assertTrue(rows().any { "dev generate" in it }); assertTrue(text.first().contains("Staff"))
    }

    @Test fun legacyAndPermissionsAreReflectedInNestedLawHelpAndDeniedExecution() {
        val law = MfFactionLawCommand(plugin)
        permissions += "mf.law.add"
        run(law, "help"); assertEquals(listOf("§cYou may not use that."), text)
        assertTrue(law.onTabComplete(sender, command, "f", arrayOf("add", "")).isEmpty())
        clear(); run(law, "add", "Law"); assertTrue(text.first().contains("NoPermission"))
        permissions += "mf.addlaw"
        clear(); run(law, "help"); assertEquals(1, rows().size); assertTrue(rows().first().startsWith("/f law add"))
    }

    @Test fun targetDisplayNameFlagRequiresBothActualStaffNodesBeforeFactionLookup() {
        val handler = MfFactionSetDisplayNameCommand(plugin)
        permissions += "mf.displayname"
        assertEquals(listOf("reset"), handler.onTabComplete(sender, command, "f", arrayOf("")))
        assertTrue(handler.onTabComplete(sender, command, "f", arrayOf("--faction", "")).isEmpty())
        run(handler, "--faction", "Nation", "Label"); assertTrue(text.first().contains("NoPermission"))
        permissions += "mf.admin.displayname"
        assertFalse(handler.onTabComplete(sender, command, "f", arrayOf("")).contains("--faction"))
        permissions += "mf.admin"
        assertTrue(handler.onTabComplete(sender, command, "f", arrayOf("")).contains("--faction"))
    }

    @Test fun embassyAndClaimDocumentationUsesRealVerbsBeforeConsoleContext() {
        permissions += setOf("mf.embassy", "mf.claim.circle")
        val embassy = MfFactionEmbassyCommand(plugin)
        run(embassy, "help", "99"); assertTrue(rows().any { "embassy list" in it }); assertTrue(text.last().all { it == '-' })
        clear(); run(embassy, "not-a-verb"); assertTrue(text.first().contains("Unknown subcommand")); assertTrue(rows().isNotEmpty())
        clear(); run(embassy, "offer", "Nation"); assertTrue(text.single().contains("in game"))
        clear(); run(MfFactionClaimCommand(plugin), "help"); assertTrue(rows().any { "claim circle" in it }); assertFalse(rows().any { "claim auto" in it })
    }
}
