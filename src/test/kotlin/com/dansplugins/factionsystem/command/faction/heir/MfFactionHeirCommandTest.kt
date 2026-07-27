package com.dansplugins.factionsystem.command.faction.heir

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
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipService
import com.dansplugins.factionsystem.service.Services
import dev.forkhandles.result4k.Success
import org.bukkit.OfflinePlayer
import org.bukkit.Server
import org.bukkit.command.Command
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitScheduler
import org.junit.jupiter.api.Assertions.assertEquals
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

/** Covers /f heir: the head naming who inherits, without handing anything over yet. */
class MfFactionHeirCommandTest {

    private lateinit var plugin: MedievalFactions
    private lateinit var factionService: MfFactionService
    private lateinit var playerService: MfPlayerService
    private lateinit var relationshipService: MfFactionRelationshipService
    private lateinit var scheduler: BukkitScheduler
    private lateinit var sender: Player
    private lateinit var command: Command
    private lateinit var uut: MfFactionHeirCommand
    private lateinit var faction: MfFaction

    private val ownerId = MfPlayerId(UUID.randomUUID().toString())
    private val heirId = MfPlayerId(UUID.randomUUID().toString())
    private val outsiderId = MfPlayerId(UUID.randomUUID().toString())

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
        relationshipService = mock(MfFactionRelationshipService::class.java)
        `when`(services.factionRelationshipService).thenReturn(relationshipService)
        `when`(factionService.save(anyArg())).thenAnswer { Success(mock(MfFaction::class.java)) }

        val heir = mock(OfflinePlayer::class.java)
        `when`(heir.name).thenReturn("Heir")
        val outsider = mock(OfflinePlayer::class.java)
        `when`(outsider.name).thenReturn("Outsider")
        `when`(server.offlinePlayers).thenReturn(arrayOf(heir, outsider))
        `when`(playerService.getPlayer(heir)).thenReturn(MfPlayer(heirId, name = "Heir"))
        `when`(playerService.getPlayer(outsider)).thenReturn(MfPlayer(outsiderId, name = "Outsider"))

        sender = mock(Player::class.java)
        `when`(sender.hasPermission("mf.heir")).thenReturn(true)
        `when`(playerService.getPlayer(sender)).thenReturn(MfPlayer(ownerId, name = "Owner"))

        val factionId = MfFactionId.generate()
        val roles = MfFactionRoles.defaults(plugin, factionId)
        // Built before the stubbing starts: constructing an MfFaction calls back into the mocked
        // plugin, and Mockito treats a mock call made mid-stubbing as an unfinished stub.
        faction = MfFaction(
            plugin,
            id = factionId,
            name = "Test Faction",
            roles = roles,
            members = listOf(
                MfFactionMember(ownerId, roles.leaderRole!!, joinedAt = 1_000),
                MfFactionMember(heirId, roles.default, joinedAt = 2_000)
            ),
            primaryOwnerId = ownerId,
            heirId = heirId
        )
        `when`(factionService.getFaction(ownerId)).thenReturn(faction)

        command = mock(Command::class.java)
        uut = MfFactionHeirCommand(plugin)
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
    fun theHeadCanNominateAMember() {
        run("Heir")

        assertEquals(heirId, savedFaction().heirId)
    }

    @Test
    fun nominationCanBeWithdrawn() {
        run("none")

        assertNull(savedFaction().heirId)
    }

    @Test
    fun aNonMemberCannotBeNominated() {
        run("Outsider")

        verify(factionService, never()).save(anyArg())
    }

    /**
     * Puts the Outsider at the head of another faction, optionally sworn to the sender's, and
     * optionally with somebody else recorded as its head instead.
     */
    private fun outsiderLeads(sworn: Boolean, itsHead: MfPlayerId = outsiderId) {
        val vassalId = MfFactionId.generate()
        val roles = MfFactionRoles.defaults(plugin, vassalId)
        val vassal = MfFaction(
            plugin,
            id = vassalId,
            name = "Sworn Faction",
            roles = roles,
            members = listOf(MfFactionMember(outsiderId, roles.leaderRole!!, joinedAt = 1_000)),
            primaryOwnerId = itsHead
        )
        `when`(factionService.getFaction(outsiderId)).thenReturn(vassal)
        `when`(relationshipService.getLiege(vassalId)).thenReturn(if (sworn) faction.id else null)
    }

    /** The one case where an heir is not one of your own people. */
    @Test
    fun theHeadCanNominateTheLeaderOfASwornFaction() {
        outsiderLeads(sworn = true)

        run("Outsider")

        assertEquals(outsiderId, savedFaction().heirId)
    }

    @Test
    fun theLeaderOfAFactionSwornToNobodyCannotBeNominated() {
        outsiderLeads(sworn = false)

        run("Outsider")

        verify(factionService, never()).save(anyArg())
    }

    /** Naming a vassal's leader is naming a person, and only the one actually in charge counts. */
    @Test
    fun anOrdinaryMemberOfASwornFactionCannotBeNominated() {
        outsiderLeads(sworn = true, itsHead = MfPlayerId(UUID.randomUUID().toString()))

        run("Outsider")

        verify(factionService, never()).save(anyArg())
    }

    @Test
    fun someoneWhoIsNotTheHeadCannotNominate() {
        `when`(playerService.getPlayer(sender)).thenReturn(MfPlayer(heirId, name = "Heir"))
        `when`(factionService.getFaction(heirId)).thenReturn(faction)

        run("Owner")

        verify(factionService, never()).save(anyArg())
    }
}
