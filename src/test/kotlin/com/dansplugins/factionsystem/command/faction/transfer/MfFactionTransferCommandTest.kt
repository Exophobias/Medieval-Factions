package com.dansplugins.factionsystem.command.faction.transfer

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.anyArg
import com.dansplugins.factionsystem.captureArg
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.faction.MfFactionMember
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
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitScheduler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.RETURNS_SMART_NULLS
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.UUID
import java.util.logging.Logger

/**
 * Covers /f transfer: the head of a faction handing it to another member while still present.
 *
 * The confirmation case matters as much as the success case. The whole point of the -f step is that a
 * mistyped name cannot move the title, so "nothing was saved" is the assertion that keeps it honest.
 */
class MfFactionTransferCommandTest {

    private lateinit var plugin: MedievalFactions
    private lateinit var factionService: MfFactionService
    private lateinit var playerService: MfPlayerService
    private lateinit var scheduler: BukkitScheduler
    private lateinit var sender: Player
    private lateinit var command: Command
    private lateinit var uut: MfFactionTransferCommand

    private val ownerId = MfPlayerId(UUID.randomUUID().toString())
    private val successorId = MfPlayerId(UUID.randomUUID().toString())
    private val outsiderId = MfPlayerId(UUID.randomUUID().toString())

    private lateinit var roles: MfFactionRoles
    private lateinit var faction: MfFaction

    @BeforeEach
    fun setUp() {
        plugin = mock(MedievalFactions::class.java)

        `when`(plugin.config).thenReturn(mock(FileConfiguration::class.java))
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
        `when`(factionService.save(anyArg())).thenAnswer { Success(mock(MfFaction::class.java)) }

        val successor = mock(OfflinePlayer::class.java)
        `when`(successor.name).thenReturn("Successor")
        val outsider = mock(OfflinePlayer::class.java)
        `when`(outsider.name).thenReturn("Outsider")
        `when`(server.offlinePlayers).thenReturn(arrayOf(successor, outsider))
        `when`(playerService.getPlayer(successor)).thenReturn(MfPlayer(successorId, name = "Successor"))
        `when`(playerService.getPlayer(outsider)).thenReturn(MfPlayer(outsiderId, name = "Outsider"))

        sender = mock(Player::class.java)
        `when`(sender.hasPermission("mf.transfer")).thenReturn(true)
        `when`(sender.spigot()).thenReturn(mock(Player.Spigot::class.java))
        `when`(playerService.getPlayer(sender)).thenReturn(MfPlayer(ownerId, name = "Owner"))

        val factionId = MfFactionId.generate()
        roles = MfFactionRoles.defaults(plugin, factionId)
        // Built before the stubbing starts: constructing an MfFaction calls back into the mocked
        // plugin, and Mockito treats a mock call made mid-stubbing as an unfinished stub.
        faction = MfFaction(
            plugin,
            id = factionId,
            name = "Test Faction",
            roles = roles,
            members = listOf(
                MfFactionMember(ownerId, roles.leaderRole!!, joinedAt = 1_000),
                MfFactionMember(successorId, roles.default, joinedAt = 2_000)
            ),
            primaryOwnerId = ownerId,
            heirId = successorId
        )
        `when`(factionService.getFaction(ownerId)).thenReturn(faction)

        command = mock(Command::class.java)
        uut = MfFactionTransferCommand(plugin)
    }

    private fun run(vararg args: String) {
        assertTrue(uut.onCommand(sender, command, "f", args))
        val task = ArgumentCaptor.forClass(Runnable::class.java)
        verify(scheduler).runTaskAsynchronously(eq(plugin), task.capture())
        task.value.run()
    }

    private fun savedFaction(): MfFaction {
        val saved: ArgumentCaptor<MfFaction> = ArgumentCaptor.forClass(MfFaction::class.java)
        verify(factionService).save(saved.captureArg())
        return saved.value
    }

    @Test
    fun withoutConfirmationNothingIsTransferred() {
        run("Successor")

        verify(factionService, never()).save(anyArg())
    }

    @Test
    fun confirmedTransferMovesThePrimaryOwner() {
        run("Successor", "-f")

        val faction = savedFaction()

        assertEquals(successorId, faction.primaryOwnerId)
        assertNotEquals(ownerId, faction.primaryOwnerId)
    }

    /** The successor has to be able to act, so they receive the top role along with the title. */
    @Test
    fun confirmedTransferGivesTheSuccessorTheTopRole() {
        run("Successor", "-f")

        assertEquals(roles.leaderRole?.id, savedFaction().getRole(successorId)?.id)
    }

    /** A nomination is about who inherits from the head; once the head changes it is stale. */
    @Test
    fun confirmedTransferClearsAnyNominatedHeir() {
        run("Successor", "-f")

        assertNull(savedFaction().heirId)
    }

    @Test
    fun someoneWhoIsNotTheHeadCannotTransfer() {
        `when`(playerService.getPlayer(sender)).thenReturn(MfPlayer(successorId, name = "Successor"))
        `when`(factionService.getFaction(successorId)).thenReturn(faction)

        run("Owner", "-f")

        verify(factionService, never()).save(anyArg())
    }

    @Test
    fun theFactionCannotBeHandedToANonMember() {
        run("Outsider", "-f")

        verify(factionService, never()).save(anyArg())
    }
}
