package com.dansplugins.factionsystem.command.faction.create

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.anyArg
import com.dansplugins.factionsystem.captureArg
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.MfFactionService
import com.dansplugins.factionsystem.faction.flag.MfFlags
import com.dansplugins.factionsystem.faction.permission.MfFactionPermissions
import com.dansplugins.factionsystem.lang.Language
import com.dansplugins.factionsystem.player.MfPlayer
import com.dansplugins.factionsystem.player.MfPlayerId
import com.dansplugins.factionsystem.player.MfPlayerService
import com.dansplugins.factionsystem.service.Services
import dev.forkhandles.result4k.Success
import org.bukkit.Server
import org.bukkit.command.Command
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitScheduler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.RETURNS_SMART_NULLS
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.UUID
import java.util.logging.Logger

/**
 * Covers what /f create writes down about the founder.
 *
 * The command body runs inside an async task, so the test captures the scheduled Runnable and runs it
 * on the spot, then inspects the faction handed to the service. Everything below the command is real:
 * real default roles over a real permission set, so the assertion about which role the founder gets is
 * about MF's actual permission map rather than a stubbed answer.
 */
class MfFactionCreateCommandTest {

    private lateinit var plugin: MedievalFactions
    private lateinit var factionService: MfFactionService
    private lateinit var playerService: MfPlayerService
    private lateinit var scheduler: BukkitScheduler
    private lateinit var player: Player
    private lateinit var command: Command
    private lateinit var uut: MfFactionCreateCommand

    private val founderId = MfPlayerId(UUID.randomUUID().toString())

    @BeforeEach
    fun setUp() {
        plugin = mock(MedievalFactions::class.java)

        val config = mock(FileConfiguration::class.java)
        `when`(plugin.config).thenReturn(config)
        `when`(config.getInt("factions.maxNameLength")).thenReturn(32)
        `when`(plugin.language).thenReturn(mock(Language::class.java, RETURNS_SMART_NULLS))
        `when`(plugin.logger).thenReturn(mock(Logger::class.java))
        val flags = MfFlags(plugin)
        `when`(plugin.flags).thenReturn(flags)
        val permissions = MfFactionPermissions(plugin)
        `when`(plugin.factionPermissions).thenReturn(permissions)

        val server = mock(Server::class.java)
        `when`(plugin.server).thenReturn(server)
        scheduler = mock(BukkitScheduler::class.java)
        `when`(server.scheduler).thenReturn(scheduler)

        val services = mock(Services::class.java)
        `when`(plugin.services).thenReturn(services)
        factionService = mock(MfFactionService::class.java)
        `when`(services.factionService).thenReturn(factionService)
        playerService = mock(MfPlayerService::class.java)
        `when`(services.playerService).thenReturn(playerService)
        `when`(factionService.save(anyArg()))
            .thenAnswer { Success(it.getArgument<MfFaction>(0)) }

        player = mock(Player::class.java)
        `when`(player.hasPermission("mf.create")).thenReturn(true)
        `when`(playerService.getPlayer(player)).thenReturn(MfPlayer(founderId, name = "Founder"))

        command = mock(Command::class.java)
        uut = MfFactionCreateCommand(plugin)
    }

    private fun createFaction(vararg args: String): MfFaction {
        assertTrue(uut.onCommand(player, command, "f", args))
        val task = ArgumentCaptor.forClass(Runnable::class.java)
        verify(scheduler).runTaskAsynchronously(eq(plugin), task.capture())
        task.value.run()
        val saved: ArgumentCaptor<MfFaction> = ArgumentCaptor.forClass(MfFaction::class.java)
        verify(factionService).save(saved.captureArg())
        return saved.value
    }

    @Test
    fun founderIsRecordedAsThePrimaryOwner() {
        val faction = createFaction("Test", "Faction")

        assertEquals("Test Faction", faction.name)
        assertEquals(founderId, faction.primaryOwnerId)
    }

    /**
     * The founder's role is chosen by the authority it carries, not by being called "Owner". Asserting
     * the permission rather than the name is what keeps this test honest if the default roles are ever
     * relabelled.
     */
    @Test
    fun founderIsGivenTheRoleCarryingTheRightToDisband() {
        val faction = createFaction("Test", "Faction")

        assertEquals(
            true,
            faction.getRole(founderId)?.getPermissionValue(plugin.factionPermissions.disband)
        )
    }
}
