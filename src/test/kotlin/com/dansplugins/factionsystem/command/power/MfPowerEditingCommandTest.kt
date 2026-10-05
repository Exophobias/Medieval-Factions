package com.dansplugins.factionsystem.command.power

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.anyArg
import com.dansplugins.factionsystem.command.faction.MfFactionCommand
import com.dansplugins.factionsystem.command.power.fill.MfPowerFillCommand
import com.dansplugins.factionsystem.command.power.set.MfPowerSetCommand
import com.dansplugins.factionsystem.command.power.setmax.MfPowerSetMaxCommand
import com.dansplugins.factionsystem.failure.ServiceFailure
import com.dansplugins.factionsystem.failure.ServiceFailureType
import com.dansplugins.factionsystem.lang.Language
import com.dansplugins.factionsystem.player.MfPlayer
import com.dansplugins.factionsystem.player.MfPlayerId
import com.dansplugins.factionsystem.player.MfPlayerService
import com.dansplugins.factionsystem.service.Services
import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import org.bukkit.OfflinePlayer
import org.bukkit.Server
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.plugin.ServicesManager
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.scheduler.BukkitTask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Answers
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.stubbing.Answer
import java.util.Locale
import java.util.UUID
import java.util.logging.Logger

class MfPowerEditingCommandTest {
    private lateinit var plugin: MedievalFactions
    private lateinit var server: Server
    private lateinit var config: FileConfiguration
    private lateinit var language: Language
    private lateinit var playerService: MfPlayerService
    private lateinit var sender: CommandSender
    private lateinit var command: Command
    private lateinit var target: OfflinePlayer
    private lateinit var currentPlayer: MfPlayer
    private val permissions = mutableSetOf<String>()
    private val messages = mutableListOf<String>()
    private val savedPlayers = mutableListOf<MfPlayer>()
    private val asyncTasks = ArrayDeque<Runnable>()
    private val syncTasks = ArrayDeque<Runnable>()
    private var saveFailure: ServiceFailure? = null
    private var throwOnNextSave = false
    private var playerExists = true

    @BeforeEach
    fun setup() {
        plugin = mock(MedievalFactions::class.java)
        server = mock(Server::class.java)
        config = mock(FileConfiguration::class.java)
        command = mock(Command::class.java)
        `when`(plugin.server).thenReturn(server)
        `when`(plugin.config).thenReturn(config)
        `when`(plugin.logger).thenReturn(mock(Logger::class.java))
        `when`(config.getDouble("players.initialPower")).thenReturn(10.0)
        `when`(config.getDouble("players.minPower")).thenReturn(0.0)
        `when`(config.getDouble("players.maxPower")).thenReturn(20.0)
        language = mock(
            Language::class.java,
            Answer {
            if (it.method.returnType == String::class.java) {
                it.arguments.firstOrNull()?.toString().orEmpty()
            } else {
                Answers.RETURNS_DEFAULTS.answer(it)
            }
        }
        )
        `when`(language.locale).thenReturn(Locale.US)
        `when`(plugin.language).thenReturn(language)
        sender = mock(CommandSender::class.java)
        `when`(sender.hasPermission(anyString())).thenAnswer { it.arguments[0] in permissions }
        doAnswer { messages += it.arguments[0].toString(); null }.`when`(sender).sendMessage(anyString())

        target = mock(OfflinePlayer::class.java)
        val playerId = UUID.randomUUID()
        `when`(target.uniqueId).thenReturn(playerId)
        `when`(target.name).thenReturn("Target")
        `when`(target.hasPlayedBefore()).thenReturn(true)
        `when`(server.getOfflinePlayer("Target")).thenReturn(target)
        `when`(server.offlinePlayers).thenReturn(arrayOf(target))
        currentPlayer = MfPlayer(MfPlayerId(playerId.toString()), name = "Target", power = 7.0, powerAtLogout = 3.0)

        val services = mock(Services::class.java)
        playerService = mock(MfPlayerService::class.java)
        `when`(plugin.services).thenReturn(services)
        `when`(services.playerService).thenReturn(playerService)
        `when`(playerService.getPlayer(currentPlayer.id)).thenAnswer { if (playerExists) currentPlayer else null }
        `when`(playerService.save(anyArg())).thenAnswer {
            if (throwOnNextSave) { throwOnNextSave = false; throw IllegalStateException("Write failed") }
            saveFailure?.let { failure -> Failure(failure) } ?: run {
                currentPlayer = it.getArgument<MfPlayer>(0).copy(version = currentPlayer.version + 1)
                playerExists = true
                savedPlayers += currentPlayer
                Success(currentPlayer)
            }
        }

        val scheduler = mock(BukkitScheduler::class.java)
        val task = mock(BukkitTask::class.java)
        `when`(server.scheduler).thenReturn(scheduler)
        `when`(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable::class.java))).thenAnswer {
            asyncTasks.addLast(it.getArgument(1)); task
        }
        `when`(scheduler.runTask(eq(plugin), any(Runnable::class.java))).thenAnswer {
            syncTasks.addLast(it.getArgument(1)); task
        }
        val serviceManager = mock(ServicesManager::class.java)
        `when`(server.servicesManager).thenReturn(serviceManager)
        `when`(serviceManager.getRegistrations(com.dansplugins.factionsystem.api.FactionSubcommandExtension::class.java)).thenReturn(emptyList())
    }

    private fun run(handler: CommandExecutor, vararg args: String) =
        handler.onCommand(sender, command, "f", args)
    private fun drain() {
        while (asyncTasks.isNotEmpty()) asyncTasks.removeFirst().run()
        while (syncTasks.isNotEmpty()) syncTasks.removeFirst().run()
    }

    @Test
    fun settingOfflineCurrentPowerAllowsFortyOverTwentyAndUpdatesDecayBaseline() {
        permissions += "mf.power.set"
        `when`(language["CommandPowerSetSuccess", "Target", "40", "20"]).thenReturn("Set Target's power to 40/20")
        assertTrue(run(MfPowerSetCommand(plugin), "Target", "40"))
        assertTrue(savedPlayers.isEmpty()); assertTrue(messages.isEmpty())
        asyncTasks.removeFirst().run()
        assertEquals(40.0, currentPlayer.power); assertEquals(40.0, currentPlayer.powerAtLogout)
        assertNull(currentPlayer.maxPowerOverride)
        assertTrue(messages.isEmpty(), "the asynchronous worker must not send a success message")
        syncTasks.removeFirst().run()
        assertEquals(listOf("§aSet Target's power to 40/20"), messages)
    }

    @Test
    fun fillUsesPersonalMaximumAndCanFillAKnownPlayerWithoutAnMfRecord() {
        permissions += "mf.power.fill"
        currentPlayer = currentPlayer.copy(maxPowerOverride = 50.0)
        assertTrue(run(MfPowerFillCommand(plugin), "Target")); drain()
        assertEquals(50.0, currentPlayer.power); assertEquals(50.0, currentPlayer.powerAtLogout)
        assertEquals(50.0, currentPlayer.maxPowerOverride)

        playerExists = false
        assertTrue(run(MfPowerFillCommand(plugin), "Target")); drain()
        assertEquals(20.0, currentPlayer.power); assertEquals(20.0, currentPlayer.powerAtLogout)
        assertNull(currentPlayer.maxPowerOverride)
    }

    @Test
    fun fillSetsOverflowDownToTheMaximumExactly() {
        permissions += "mf.power.fill"
        currentPlayer = currentPlayer.copy(power = 40.0, powerAtLogout = 40.0)
        assertTrue(run(MfPowerFillCommand(plugin), "Target")); drain()
        assertEquals(20.0, currentPlayer.power); assertEquals(20.0, currentPlayer.powerAtLogout)
    }

    @Test
    fun personalMaximumChangesAndResetPreserveCurrentPowerAndDecayBaseline() {
        permissions += "mf.power.setmax"
        `when`(language["CommandPowerSetMaxResetSuccess", "Target", "20", "7"]).thenReturn("Reset Target's maximum to 20; current power is 7/20")
        val handler = MfPowerSetMaxCommand(plugin)
        assertTrue(run(handler, "Target", "50")); drain()
        assertEquals(50.0, currentPlayer.maxPowerOverride)
        assertEquals(7.0, currentPlayer.power); assertEquals(3.0, currentPlayer.powerAtLogout)
        assertTrue(run(handler, "Target", "5")); drain()
        assertEquals(5.0, currentPlayer.maxPowerOverride)
        assertEquals(7.0, currentPlayer.power)
        assertTrue(run(handler, "Target", "RESET")); drain()
        assertNull(currentPlayer.maxPowerOverride)
        assertEquals(20.0, currentPlayer.effectiveMaxPower(plugin))
        assertEquals(7.0, currentPlayer.power); assertEquals(3.0, currentPlayer.powerAtLogout)
        assertEquals("§aReset Target's maximum to 20; current power is 7/20", messages.last())
        verify(language)["CommandPowerSetMaxResetSuccess", "Target", "20", "7"]
    }

    @Test
    fun bothRootsShareOneOrderedPlayerWorkerForMaximumThenFill() {
        permissions += "mf.force.power"
        val factions = MfFactionCommand(plugin)
        val power = MfPowerCommand(plugin)
        assertTrue(run(factions, "setmaxpower", "Target", "50"))
        assertTrue(run(power, "fill", "Target"))
        assertTrue(run(factions, "setpower", "Target", "60"))
        assertEquals(1, asyncTasks.size)
        drain()
        assertEquals(listOf(7.0, 50.0, 60.0), savedPlayers.map { it.power })
        assertTrue(savedPlayers.all { it.maxPowerOverride == 50.0 })
        assertEquals(60.0, currentPlayer.powerAtLogout)
    }

    @Test
    fun onlineFirstTimePlayerIsAValidTarget() {
        permissions += "mf.power.set"
        `when`(target.hasPlayedBefore()).thenReturn(false)
        `when`(target.isOnline).thenReturn(true)
        assertTrue(run(MfPowerSetCommand(plugin), "Target", "12.5")); drain()
        assertEquals(12.5, currentPlayer.power)
    }

    @Test
    fun unknownPlayersAndInvalidOrExtraArgumentsNeverScheduleEdits() {
        permissions += "mf.force.power"
        val set = MfPowerSetCommand(plugin)
        for (amount in listOf("NaN", "Infinity", "-Infinity", "1e999", "text", "-1")) {
            assertTrue(run(set, "Target", amount))
        }
        val setMax = MfPowerSetMaxCommand(plugin)
        for (maximum in listOf("NaN", "Infinity", "-Infinity", "1e999", "text", "0", "-1")) {
            assertTrue(run(setMax, "Target", maximum))
        }
        `when`(config.getDouble("players.minPower")).thenReturn(10.0)
        assertTrue(run(setMax, "Target", "5"))
        for (handler in listOf<CommandExecutor>(set, setMax)) {
            assertTrue(run(handler)); assertTrue(run(handler, "Target")); assertTrue(run(handler, "Target", "20", "extra"))
        }
        val fill = MfPowerFillCommand(plugin)
        assertTrue(run(fill)); assertTrue(run(fill, "Target", "extra"))
        `when`(target.hasPlayedBefore()).thenReturn(false)
        for (handler in listOf<CommandExecutor>(set, setMax)) assertTrue(run(handler, "Target", "20"))
        assertTrue(run(fill, "Target"))
        assertTrue(asyncTasks.isEmpty()); assertTrue(savedPlayers.isEmpty())
        assertTrue(messages.last().contains("CommandPowerSetInvalidTarget"))
    }

    @Test
    fun deniedCommandsAndTabCompletionRevealNoTargets() {
        val handlers: List<CommandExecutor> = listOf(MfPowerSetCommand(plugin), MfPowerSetMaxCommand(plugin), MfPowerFillCommand(plugin))
        handlers.forEach {
            assertTrue(run(it, "Target", "20"))
            assertTrue((it as TabCompleter).onTabComplete(sender, command, "f", arrayOf("")).orEmpty().isEmpty())
        }
        assertEquals(3, messages.size)
        assertTrue(messages.all { it.contains("NoPermission") })
        verify(server, never()).getOfflinePlayer(anyString())
        assertTrue(asyncTasks.isEmpty())
    }

    @Test
    fun individualPermissionsDoNotGrantOtherPowerEditsAndResetSuggestionsAreFiltered() {
        permissions += "mf.power.set"
        assertEquals(listOf("Target"), MfPowerSetCommand(plugin).onTabComplete(sender, command, "f", arrayOf("tar")))
        assertTrue(MfPowerFillCommand(plugin).onTabComplete(sender, command, "f", arrayOf("")).isEmpty())
        val setMax = MfPowerSetMaxCommand(plugin)
        assertTrue(setMax.onTabComplete(sender, command, "f", arrayOf("Target", "r")).isEmpty())
        permissions += "mf.power.setmax"
        assertEquals(listOf("reset"), setMax.onTabComplete(sender, command, "f", arrayOf("Target", "R")))
        assertTrue(setMax.onTabComplete(sender, command, "f", arrayOf("Target", "50", "")).isEmpty())
    }

    @Test
    fun failedSaveRepliesOnTheMainThreadWithoutSuccessOrMutation() {
        permissions += "mf.power.set"
        saveFailure = ServiceFailure(ServiceFailureType.CONFLICT, "Conflict", IllegalStateException("Conflict"))
        assertTrue(run(MfPowerSetCommand(plugin), "Target", "40"))
        asyncTasks.removeFirst().run()
        assertTrue(savedPlayers.isEmpty()); assertTrue(messages.isEmpty())
        assertEquals(7.0, currentPlayer.power)
        syncTasks.removeFirst().run()
        assertEquals(listOf("§cCommandPowerSetFailedToSaveTargetPlayer"), messages)
        assertFalse(messages.any { it.contains("Success") })
    }

    @Test
    fun unexpectedWriteFailureDoesNotStrandTheNextQueuedEdit() {
        permissions += "mf.force.power"
        `when`(language["CommandPowerFillSuccess", "Target", "20", "20"]).thenReturn("Filled Target's power to 20/20")
        throwOnNextSave = true
        assertTrue(run(MfPowerSetCommand(plugin), "Target", "40"))
        assertTrue(run(MfPowerFillCommand(plugin), "Target"))
        assertEquals(1, asyncTasks.size)
        drain()
        assertEquals(20.0, currentPlayer.power)
        assertEquals(1, savedPlayers.size)
        assertTrue(messages.first().contains("FailedToSave"))
        assertEquals("§aFilled Target's power to 20/20", messages.last())
        verify(language)["CommandPowerFillSuccess", "Target", "20", "20"]
    }

    @Test
    fun legacySetAndOtherRootAliasesUseTheSameSavedPlayerState() {
        permissions += "mf.force.power"
        val power = MfPowerCommand(plugin)
        val factions = MfFactionCommand(plugin)
        assertTrue(run(power, "set", "Target", "40"))
        assertTrue(run(power, "setmax", "Target", "50"))
        assertTrue(run(factions, "fillpower", "Target"))
        drain()
        assertEquals(listOf(40.0, 40.0, 50.0), savedPlayers.map { it.power })
        assertEquals(50.0, currentPlayer.powerAtLogout)
        assertEquals(50.0, currentPlayer.maxPowerOverride)
    }
}
