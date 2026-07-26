package com.dansplugins.factionsystem.command.faction.admin

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.anyArg
import com.dansplugins.factionsystem.captureArg
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.faction.MfFactionService
import com.dansplugins.factionsystem.faction.flag.MfFlags
import com.dansplugins.factionsystem.faction.permission.MfFactionPermissions
import com.dansplugins.factionsystem.faction.role.MfFactionRoles
import com.dansplugins.factionsystem.lang.Language
import com.dansplugins.factionsystem.player.MfPlayer
import com.dansplugins.factionsystem.player.MfPlayerId
import com.dansplugins.factionsystem.player.MfPlayerService
import com.dansplugins.factionsystem.service.Services
import dev.forkhandles.result4k.Success
import org.bukkit.OfflinePlayer
import org.bukkit.Server
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.FileConfiguration
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
 * Covers /f admin setleader against a faction that has renamed its top role.
 *
 * This is the call site where matching the name "Owner" was reachable by players: a faction that
 * renamed its own top role was told it had none, and one that relabelled a junior role could have had
 * an appointed leader handed that junior role instead. The faction here is leaderless with its top
 * role renamed, which is exactly the state an operator would be called in to fix.
 */
class MfFactionAdminSetLeaderCommandTest {

    private lateinit var plugin: MedievalFactions
    private lateinit var factionService: MfFactionService
    private lateinit var scheduler: BukkitScheduler
    private lateinit var sender: CommandSender
    private lateinit var command: Command
    private lateinit var uut: MfFactionAdminSetLeaderCommand

    private val successorId = MfPlayerId(UUID.randomUUID().toString())

    @BeforeEach
    fun setUp() {
        plugin = mock(MedievalFactions::class.java)

        val config = mock(FileConfiguration::class.java)
        `when`(plugin.config).thenReturn(config)
        `when`(config.getInt("factions.maxMembers")).thenReturn(0)
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
        val playerService = mock(MfPlayerService::class.java)
        `when`(services.playerService).thenReturn(playerService)
        `when`(factionService.save(anyArg())).thenAnswer { Success(mock(MfFaction::class.java)) }

        val successor = mock(OfflinePlayer::class.java)
        `when`(successor.hasPlayedBefore()).thenReturn(true)
        `when`(server.getOfflinePlayer("Successor")).thenReturn(successor)
        `when`(playerService.getPlayer(successor)).thenReturn(MfPlayer(successorId, name = "Successor"))

        // Built before the stubbing starts: constructing an MfFaction calls back into the mocked
        // plugin, and Mockito treats a mock call made mid-stubbing as an unfinished stub.
        val faction = leaderlessFactionWithRenamedTopRole()
        `when`(factionService.getFaction("Renamed Faction")).thenReturn(faction)

        sender = mock(CommandSender::class.java)
        `when`(sender.hasPermission("mf.admin.setleader")).thenReturn(true)
        command = mock(Command::class.java)
        uut = MfFactionAdminSetLeaderCommand(plugin)
    }

    private fun leaderlessFactionWithRenamedTopRole(): MfFaction {
        val factionId = MfFactionId.generate()
        val defaults = MfFactionRoles.defaults(plugin, factionId)
        val renamed = MfFactionRoles(
            defaults.defaultRoleId,
            defaults.roles.map { role -> if (role.name == "Owner") role.copy(name = "Monarch") else role }
        )
        return MfFaction(plugin, id = factionId, name = "Renamed Faction", roles = renamed, members = emptyList())
    }

    private fun setLeader(): MfFaction {
        assertTrue(uut.onCommand(sender, command, "f", arrayOf("Successor", "Renamed", "Faction")))
        val task = ArgumentCaptor.forClass(Runnable::class.java)
        verify(scheduler).runTaskAsynchronously(eq(plugin), task.capture())
        task.value.run()
        val saved: ArgumentCaptor<MfFaction> = ArgumentCaptor.forClass(MfFaction::class.java)
        verify(factionService).save(saved.captureArg())
        return saved.value
    }

    @Test
    fun appointedLeaderIsRecordedAsThePrimaryOwner() {
        assertEquals(successorId, setLeader().primaryOwnerId)
    }

    @Test
    fun appointedLeaderIsGivenTheTopRoleEvenThoughItHasBeenRenamed() {
        val faction = setLeader()

        assertEquals("Monarch", faction.getRole(successorId)?.name)
        assertEquals(
            true,
            faction.getRole(successorId)?.getPermissionValue(plugin.factionPermissions.disband)
        )
    }
}
