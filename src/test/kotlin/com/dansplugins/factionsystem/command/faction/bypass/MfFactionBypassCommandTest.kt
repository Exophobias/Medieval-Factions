package com.dansplugins.factionsystem.command.faction.bypass

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.anyArg
import com.dansplugins.factionsystem.lang.Language
import com.dansplugins.factionsystem.player.MfPlayer
import com.dansplugins.factionsystem.player.MfPlayerId
import com.dansplugins.factionsystem.player.MfPlayerService
import com.dansplugins.factionsystem.service.Services
import dev.forkhandles.result4k.Success
import org.bukkit.ChatColor.GREEN
import org.bukkit.ChatColor.RED
import org.bukkit.Server
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitScheduler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.UUID
import java.util.logging.Logger

class MfFactionBypassCommandTest {
    private lateinit var plugin: MedievalFactions
    private lateinit var player: Player
    private lateinit var playerService: MfPlayerService
    private lateinit var scheduler: BukkitScheduler
    private lateinit var command: Command
    private lateinit var uut: MfFactionBypassCommand
    private lateinit var currentPlayer: MfPlayer

    private val savedPlayers = mutableListOf<MfPlayer>()
    private val asyncTasks = ArrayDeque<Runnable>()
    private val syncTasks = ArrayDeque<Runnable>()

    @BeforeEach
    fun setUp() {
        plugin = mock(MedievalFactions::class.java)
        val language = mock(Language::class.java)
        `when`(plugin.language).thenReturn(language)
        `when`(language["CommandFactionBypassNoPermission"]).thenReturn("No permission")
        `when`(language["CommandFactionBypassNotAPlayer"]).thenReturn("Players only")
        `when`(language["CommandFactionBypassEnabled"]).thenReturn("Bypass enabled")
        `when`(language["CommandFactionBypassDisabled"]).thenReturn("Bypass disabled")
        `when`(language["CommandFactionBypassUsage"]).thenReturn("Usage")
        `when`(language["CommandFactionBypassWarningsOn"]).thenReturn("Warnings on")
        `when`(language["CommandFactionBypassWarningsOff"]).thenReturn("Warnings off")
        `when`(plugin.logger).thenReturn(mock(Logger::class.java))
        val config = mock(FileConfiguration::class.java)
        `when`(plugin.config).thenReturn(config)
        `when`(config.getDouble("players.initialPower")).thenReturn(10.0)

        val server = mock(Server::class.java)
        scheduler = mock(BukkitScheduler::class.java)
        `when`(plugin.server).thenReturn(server)
        `when`(server.scheduler).thenReturn(scheduler)
        `when`(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable::class.java))).thenAnswer {
            asyncTasks.addLast(it.getArgument(1))
            null
        }
        `when`(scheduler.runTask(eq(plugin), any(Runnable::class.java))).thenAnswer {
            syncTasks.addLast(it.getArgument(1))
            null
        }

        val services = mock(Services::class.java)
        playerService = mock(MfPlayerService::class.java)
        `when`(plugin.services).thenReturn(services)
        `when`(services.playerService).thenReturn(playerService)

        player = mock(Player::class.java)
        `when`(player.hasPermission("mf.bypass")).thenReturn(true)
        `when`(player.isOnline).thenReturn(true)
        `when`(player.uniqueId).thenReturn(UUID.randomUUID())
        `when`(server.getPlayer(player.uniqueId)).thenReturn(player)
        currentPlayer = MfPlayer(MfPlayerId(player.uniqueId.toString()))
        `when`(playerService.getPlayer(player)).thenAnswer { currentPlayer }
        `when`(playerService.getPlayer(currentPlayer.id)).thenAnswer { currentPlayer }
        `when`(playerService.save(anyArg())).thenAnswer { invocation ->
            currentPlayer = invocation.getArgument<MfPlayer>(0).copy(version = currentPlayer.version + 1)
            savedPlayers += currentPlayer
            Success(currentPlayer)
        }

        command = mock(Command::class.java)
        uut = MfFactionBypassCommand(plugin)
    }

    private fun runScheduledTasks() {
        while (asyncTasks.isNotEmpty()) asyncTasks.removeFirst().run()
        while (syncTasks.isNotEmpty()) syncTasks.removeFirst().run()
    }

    @Test
    fun bareBypassStillTogglesProtectionModeWithoutChangingWarningPreference() {
        currentPlayer = currentPlayer.copy(isBypassWarningMuted = true)
        assertTrue(uut.onCommand(player, command, "f", emptyArray()))
        runScheduledTasks()
        assertTrue(currentPlayer.isBypassEnabled)
        assertTrue(currentPlayer.isBypassWarningMuted)
        verify(player).sendMessage("${GREEN}Bypass enabled")
        verify(plugin).resetTerritoryBypassWarning(player.uniqueId)

        assertTrue(uut.onCommand(player, command, "f", emptyArray()))
        runScheduledTasks()
        assertFalse(currentPlayer.isBypassEnabled)
        assertTrue(currentPlayer.isBypassWarningMuted)
        assertEquals(2, savedPlayers.size)
        verify(player).sendMessage("${GREEN}Bypass disabled")
        verify(plugin, times(1)).resetTerritoryBypassWarning(player.uniqueId)
    }

    @Test
    fun warningStatusAndOnOffPersistWithoutChangingBypassMode() {
        currentPlayer = currentPlayer.copy(isBypassEnabled = true)

        assertTrue(uut.onCommand(player, command, "f", arrayOf("warnings")))
        verify(player).sendMessage("${GREEN}Warnings on")
        assertTrue(asyncTasks.isEmpty())
        assertTrue(savedPlayers.isEmpty())

        assertTrue(uut.onCommand(player, command, "f", arrayOf("warnings", "off")))
        runScheduledTasks()
        assertTrue(currentPlayer.isBypassWarningMuted)
        assertTrue(currentPlayer.isBypassEnabled)
        assertTrue(savedPlayers.single().isBypassWarningMuted)
        verify(player).sendMessage("${GREEN}Warnings off")

        assertTrue(uut.onCommand(player, command, "f", arrayOf("WARNINGS")))
        verify(player, times(2)).sendMessage("${GREEN}Warnings off")

        assertTrue(uut.onCommand(player, command, "f", arrayOf("warnings", "ON")))
        runScheduledTasks()
        assertFalse(currentPlayer.isBypassWarningMuted)
        assertTrue(currentPlayer.isBypassEnabled)
        assertEquals(2, savedPlayers.size)
        verify(player, times(2)).sendMessage("${GREEN}Warnings on")
    }

    @Test
    fun queuedCommandsUseEachPriorSaveAndFinishInSubmissionOrder() {
        assertTrue(uut.onCommand(player, command, "f", arrayOf("warnings", "off")))
        assertTrue(uut.onCommand(player, command, "f", arrayOf("warnings", "on")))
        assertTrue(uut.onCommand(player, command, "f", emptyArray()))
        assertTrue(uut.onCommand(player, command, "f", emptyArray()))

        assertEquals(1, asyncTasks.size, "one worker serializes this player's queued changes")
        assertTrue(savedPlayers.isEmpty())
        runScheduledTasks()

        assertEquals(
            listOf(true to false, false to false, false to true, false to false),
            savedPlayers.map { it.isBypassWarningMuted to it.isBypassEnabled }
        )
        assertFalse(currentPlayer.isBypassWarningMuted)
        assertFalse(currentPlayer.isBypassEnabled)
        assertEquals(4, currentPlayer.version)
        assertTrue(asyncTasks.isEmpty())
    }

    @Test
    fun invalidArgumentsNeverToggleBypassOrSavePlayer() {
        listOf(
            arrayOf("unexpected"),
            arrayOf("warnings", "maybe"),
            arrayOf("warnings", "on", "extra")
        ).forEach { args ->
            assertTrue(uut.onCommand(player, command, "f", args))
        }

        assertFalse(currentPlayer.isBypassEnabled)
        assertTrue(savedPlayers.isEmpty())
        assertTrue(asyncTasks.isEmpty())
        verify(player, times(3)).sendMessage("${RED}Usage")
        verify(playerService, never()).save(anyArg())
    }

    @Test
    fun permissionAndPlayerChecksPreventCommands() {
        `when`(player.hasPermission("mf.bypass")).thenReturn(false)
        assertTrue(uut.onCommand(player, command, "f", emptyArray()))
        verify(player).sendMessage("${RED}No permission")

        val console = mock(CommandSender::class.java)
        `when`(console.hasPermission("mf.bypass")).thenReturn(true)
        assertTrue(uut.onCommand(console, command, "f", arrayOf("warnings", "off")))
        verify(console).sendMessage("${RED}Players only")

        assertTrue(savedPlayers.isEmpty())
        assertTrue(asyncTasks.isEmpty())
        verify(playerService, never()).save(anyArg())
    }

    @Test
    fun tabCompletionShowsOnlyPermittedWarningOptions() {
        assertEquals(listOf("warnings"), uut.onTabComplete(player, command, "f", arrayOf("")))
        assertEquals(listOf("warnings"), uut.onTabComplete(player, command, "f", arrayOf("WAR")))
        assertEquals(listOf("on", "off"), uut.onTabComplete(player, command, "f", arrayOf("warnings", "")))
        assertEquals(listOf("off"), uut.onTabComplete(player, command, "f", arrayOf("WARNINGS", "of")))
        assertTrue(uut.onTabComplete(player, command, "f", arrayOf("other", "")).isEmpty())
        assertTrue(uut.onTabComplete(player, command, "f", arrayOf("warnings", "on", "extra")).isEmpty())

        val denied = mock(CommandSender::class.java)
        assertTrue(uut.onTabComplete(denied, command, "f", arrayOf("warnings", "")).isEmpty())
    }
}
